package dev.fonebrew.data.watch

import dev.fonebrew.data.MessageTreeRepository
import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.cost.UsagePricing
import dev.fonebrew.domain.ledger.LedgerCapture
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.ledger.Status
import dev.fonebrew.domain.ledger.Tier
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.thread.MessageConfidence
import dev.fonebrew.domain.tree.Nodes
import dev.fonebrew.inference.InferenceEngine
import dev.fonebrew.inference.cloud.CloudEngine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs one relayed question through the SAME engine path a phone chat turn uses, and persists it as an
 * ordinary turn. This is the relay's only engine and tree contact; [WearRelayPresenter] drives it and
 * knows nothing of engines.
 *
 * ## What "the same path" means here, and what it does not
 *
 * The phone's chat turn lives inside `ChatViewModel.runTurn`, a private method of a 2,400-line
 * ViewModel that is welded to UI state flows, so it cannot be called from a service. This class
 * therefore **re-implements that turn's contract against the same collaborators**, and
 * `WristTurnRunnerTest` pins the parity point by point:
 *
 *  - the engine comes from the same factory (`EngineProvider.engineFor`, via [WristEngineResolver]),
 *    is loaded with `loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)`, and generates over the
 *    root-to-user-turn path (`MessageTreeRepository.path`) with default [SamplingParams], using the
 *    model's own chat template inside the engine, KV-cache snapshots for a local model included;
 *  - the user and assistant turns are appended with `Nodes.child` and `MessageTreeRepository.insert`
 *    (**append-only: nothing is updated, deleted or back-dated**), the assistant node tagged with the
 *    producing `modelId`, per-tokenizer token counts recorded, the user turn embedded for the
 *    cold-start log, and the per-turn confidence metadata minted at persist time;
 *  - a cloud turn's provider-reported usage (`CloudEngine.lastUsage`) is priced through the user's
 *    pricing book, counted against the provider's free tier, and written as the same `costMinor` /
 *    `tokensIn` / `tokensOut` node metadata;
 *  - one usage-ledger entry per turn, via `LedgerCapture.singleTurn`.
 *
 * Where it deliberately differs, each honest and each tested:
 *
 *  - Every node it inserts is tagged `via = watchbrew` at insert time ([WristConversation.VIA_KEY]).
 *  - The ledger tier follows [ModelSpec.isOnDevice] (the phone chat marks anything that is not a local
 *    GGUF, including on-device Gemini Nano, as CLOUD tier; the relay does not copy that), and a cloud
 *    turn's ledger OUTPUT tokens are the provider-reported figure (the phone chat records its own coarse
 *    chars-per-token count there while flagging the row as provider-reported).
 *  - Web search is never enabled for a relayed turn (the watched-object fact stays as small as it was asked to be).
 *  - A partial answer the wrist already received is kept as a stopped turn when the engine breaks
 *    mid-stream (the phone chat discards it); either way nothing is fabricated.
 *  - **Not carried over:** compaction-boundary routing of the prompt (THREAD_TOPOLOGY WP3). If the user
 *    compacts the Wrist conversation on the phone, relayed turns still send the full path. Named
 *    follow-up, along with extracting one shared turn-persistence routine from `ChatViewModel` so the two
 *    callers cannot drift (not done in this lane: it would mean editing that ViewModel while other work is in flight).
 *  - **Not guarded:** the phone's chat and the relay can both drive the single resident local engine at
 *    once. The relay serializes only its own turns ([WearRelayPresenter]); a cross-surface "generation
 *    gate" is the named follow-up.
 *
 * ## Honest numbers
 *
 * [TurnUsage.EngineReported] only when the provider reported usage. Otherwise [TurnUsage.Estimated] with
 * the engine's own tokenizer counts, where a count the tokenizer failed to produce is `null` (unknown),
 * never 0. The ledger, whose schema has no null, records 0 for a failed count exactly as the phone chat
 * does; the wire does not.
 *
 * ## Secrets
 *
 * No exception message ever leaves this class. Provider errors can echo key fragments ("Incorrect API
 * key provided: sk-...abc"), so a failure is reported as a FIXED sentence chosen by whether the engine
 * was cloud or on-device. The API key itself is never visible here: the resolver hands over a built engine.
 */
class WristTurnRunner(
    private val repository: MessageTreeRepository,
    private val resolver: WristEngineResolver,
    private val appendLedger: suspend (LedgerEntry) -> Unit,
    private val onUserNode: suspend (MessageNode) -> Unit = {},
    private val priceFor: (tokenizerId: String) -> UsagePricing = { UsagePricing.ON_DEVICE },
    private val recordFreeTier: (providerId: String, inputTokens: Long, outputTokens: Long) -> Unit = { _, _, _ -> },
    private val projectOf: (chatId: String) -> String? = { null },
    private val kv: KvSnapshots? = null,
    private val keepAlive: ProcessKeepAlive = ProcessKeepAlive.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) : WristTurns {

    override suspend fun run(prompt: String, emit: suspend (WristTurnEvent) -> Unit) {
        val ready = when (val resolution = resolver.resolve()) {
            is EngineResolution.Unavailable -> {
                emit(WristTurnEvent.Failed(resolution.reason.toWireSafe()))
                return
            }
            is EngineResolution.Ready -> resolution
        }
        val spec = ready.spec
        val engine = ready.engine
        val watchedObject = spec.watched || spec.runtime == Runtime.CLOUD

        emit(WristTurnEvent.Engine(engineLabel(spec), watchedObject))

        val userNode = try {
            val parent = WristConversation.tipForNextTurn(repository, clock(), idGen)
            Nodes.child(
                parent = parent,
                role = Role.USER,
                content = prompt,
                now = clock(),
                metadata = mapOf(WristConversation.VIA_KEY to WristConversation.VIA_WATCHBREW),
                idGen = idGen,
            ).also { repository.insert(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(WristTurnEvent.Failed(SAVE_FAILED_REASON))
            return
        }
        bestEffort { onUserNode(userNode) }

        generate(spec, engine, userNode, watchedObject, emit)
    }

    private suspend fun generate(
        spec: ModelSpec,
        engine: InferenceEngine,
        userNode: MessageNode,
        watchedObject: Boolean,
        emit: suspend (WristTurnEvent) -> Unit,
    ) {
        val isLocal = spec.runtime == Runtime.LOCAL_GGUF
        val assistantId = idGen()
        val loadPath = if (isLocal) userNode.parentId?.let { kv?.loadPathFor?.invoke(it) } else null
        val savePath = if (isLocal) kv?.savePathFor?.invoke(assistantId) else null

        val tokens = mutableListOf<GeneratedToken>()
        var fullPath: List<MessageNode> = emptyList()
        var engineFailure: Throwable? = null
        var linkFailure: Throwable? = null
        var cancelled: CancellationException? = null
        val genStart = clock()

        keepAlive.hold(spec).use {
            try {
                engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
                bestEffort {
                    repository.setTokenCount(userNode.id, spec.tokenizerId, runCatching { engine.countTokens(userNode.content) }.getOrDefault(0))
                }
                fullPath = repository.path(userNode.id)
                engine.generate(fullPath, SamplingParams(), loadPath, savePath).collect { token ->
                    tokens += token
                    if (token.text.isNotEmpty()) {
                        try {
                            emit(WristTurnEvent.Token(token.text))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw LinkLost(e)
                        }
                    }
                }
            } catch (e: LinkLost) {
                linkFailure = e.cause ?: e
            } catch (e: CancellationException) {
                cancelled = e
            } catch (t: Throwable) {
                engineFailure = t
            }
        }

        val stopped = linkFailure != null || cancelled != null || engineFailure != null
        val text = tokens.joinToString("") { it.text }.trim()
        var usage: TurnUsage = TurnUsage.Unknown
        var saveFailed = false

        // Persistence must survive cancellation: a stopped turn keeps its partial text (the phone's Stop
        // button does the same), so this block runs even when the surrounding scope is being torn down.
        withContext(NonCancellable) {
            if (text.isEmpty() && stopped) return@withContext // nothing was said and nothing is fabricated

            val cloudEngine = engine as? CloudEngine
            val cloudUsage = cloudEngine?.lastUsage?.takeIf { it.totalTokens > 0 }
            val costMinor: Long? = cloudUsage?.let { reported ->
                spec.providerId?.let { recordFreeTier(it, reported.inputTokens, reported.outputTokens) }
                reported.toAdviceCost(priceFor(engine.tokenizerId)).moneyMinor
            }
            val costMeta = if (cloudUsage != null) {
                mapOf(
                    "costMinor" to costMinor.toString(),
                    "tokensIn" to cloudUsage.inputTokens.toString(),
                    "tokensOut" to cloudUsage.outputTokens.toString(),
                )
            } else {
                emptyMap()
            }
            val confidenceMeta = MessageConfidence.fromEntropies(tokens.map { it.entropy })?.let {
                mapOf(MessageConfidence.METADATA_KEY to it.toString())
            } ?: emptyMap()

            val assistantNode = Nodes.child(
                parent = userNode,
                role = Role.ASSISTANT,
                content = text,
                now = clock(),
                modelId = spec.id,
                metadata = mapOf(WristConversation.VIA_KEY to WristConversation.VIA_WATCHBREW) +
                    (if (stopped) mapOf("stopped" to "true") else emptyMap()) + costMeta + confidenceMeta,
                idGen = { assistantId }, // so the KV snapshot is keyed to this node
            )
            val persisted = try {
                repository.insert(assistantNode)
                true
            } catch (e: Exception) {
                false
            }
            if (!persisted) {
                saveFailed = true
                return@withContext
            }

            val outCount = runCatching { engine.countTokens(text) }.getOrNull()
            val inCount = runCatching { engine.countTokens(fullPath.joinToString("\n") { it.content }) }.getOrNull()
            if (outCount != null) bestEffort { repository.setTokenCount(assistantNode.id, spec.tokenizerId, outCount) }

            usage = when {
                cloudUsage != null -> TurnUsage.EngineReported(cloudUsage.inputTokens, cloudUsage.outputTokens)
                inCount == null && outCount == null -> TurnUsage.Unknown
                else -> TurnUsage.Estimated(inCount?.toLong(), outCount?.toLong())
            }

            // Usage ledger (the "Myself" record): one honest entry per turn, never allowed to fail the turn itself.
            bestEffort {
                val chatId = fullPath.firstOrNull()?.id ?: userNode.id
                appendLedger(
                    LedgerCapture.singleTurn(
                        timestampMillis = clock(),
                        chatId = chatId,
                        nodeId = assistantNode.id,
                        projectId = projectOf(chatId),
                        model = spec.id,
                        provider = spec.providerId ?: "on-device",
                        tier = if (spec.isOnDevice) Tier.ON_DEVICE else Tier.CLOUD,
                        inputTokens = cloudUsage?.inputTokens ?: inCount?.toLong() ?: 0L,
                        // Provider-reported output when there is one (the phone chat records its own coarse
                        // tokenizer count here even for a cloud turn it flags as provider-reported; the relay
                        // does not).
                        outputTokens = cloudUsage?.outputTokens ?: outCount?.toLong() ?: 0L,
                        estCostMinor = costMinor ?: 0L,
                        latencyMs = clock() - genStart,
                        status = when {
                            engineFailure != null -> Status.ERROR
                            stopped -> Status.STOPPED
                            else -> Status.COMPLETE
                        },
                        estimated = cloudUsage == null,
                    ),
                )
            }
        }

        when {
            linkFailure != null -> throw linkFailure!!
            cancelled != null -> throw cancelled!!
            // Whatever the wrist already received was real; but a reply that never reached the tree must
            // not be presented as a finished turn.
            saveFailed -> emit(WristTurnEvent.Failed(REPLY_NOT_SAVED_REASON))
            engineFailure != null -> emit(WristTurnEvent.Failed(failureReason(watchedObject)))
            else -> emit(WristTurnEvent.Done(usage))
        }
    }

    /** Wraps a failed [emit] so it is told apart from an engine failure inside one `collect`. */
    private class LinkLost(cause: Throwable) : Exception(cause)

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A side record (embedding, token count, ledger) must never fail the answer itself.
        }
    }

    companion object {
        /** Fixed sentence: the question could not be written to the phone's tree, so it was not run. */
        const val SAVE_FAILED_REASON: String = "The phone could not save your question, so it was not sent to the model."

        /** Fixed sentence: the reply was produced but could not be written to the phone's tree. */
        const val REPLY_NOT_SAVED_REASON: String = "The phone could not save this reply to its history, so treat it as unsent."

        /** Fixed sentence for an on-device engine failure. Never the exception's own message (see class KDoc, Secrets). */
        const val ON_DEVICE_FAILED_REASON: String = "The model could not finish on the phone. Open Fonebrew there for details."

        /** Fixed sentence for a cloud engine failure. Never the exception's own message (see class KDoc, Secrets). */
        const val CLOUD_FAILED_REASON: String = "The cloud provider could not finish this reply. Open Fonebrew on the phone for details."

        private const val MAX_ENGINE_LABEL_CHARS = 80

        internal fun failureReason(watchedObject: Boolean): String =
            if (watchedObject) CLOUD_FAILED_REASON else ON_DEVICE_FAILED_REASON

        /**
         * The legible engine name for the wrist. The phone's cloud display name leads with a cloud
         * marker; that fact travels separately as `watchedObject`, so it is dropped here rather than
         * shown twice. Falls back to the model id if the name is blank.
         */
        internal fun engineLabel(spec: ModelSpec): String {
            val shown = spec.displayName.removePrefix("☁").trim()
            return (shown.ifBlank { spec.id }).toWireTruncated(MAX_ENGINE_LABEL_CHARS).ifBlank { "model" }
        }
    }
}
