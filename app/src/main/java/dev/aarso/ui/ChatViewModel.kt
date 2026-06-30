package dev.aarso.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.aarso.AarsoApp
import dev.aarso.data.DeviceInfo
import dev.aarso.data.DownloadCenter
import dev.aarso.data.ImageProviderStore
import dev.aarso.data.ImageStore
import dev.aarso.data.KvCacheStore
import dev.aarso.data.LocalModelStore
import dev.aarso.data.MessageTreeRepository
import dev.aarso.data.ModelDownloader
import dev.aarso.data.ProviderStore
import dev.aarso.data.Intake
import dev.aarso.data.SdModelStore
import dev.aarso.data.SessionStore
import dev.aarso.data.SharedIntake
import dev.aarso.domain.catalog.CatalogModel
import dev.aarso.domain.catalog.ModelCatalog
import dev.aarso.domain.catalog.StarterModels
import dev.aarso.domain.device.ModelFit
import dev.aarso.domain.GeneratedToken
import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.SamplingParams
import dev.aarso.domain.council.Council
import dev.aarso.domain.image.ImageParams
import dev.aarso.domain.instrument.TokenStats
import dev.aarso.domain.model.ContextCheck
import dev.aarso.domain.model.DefaultModelPolicy
import dev.aarso.domain.model.ModelSpec
import dev.aarso.domain.model.Runtime
import dev.aarso.domain.model.checkContext
import dev.aarso.domain.template.ChatTemplates
import dev.aarso.domain.tree.Conversations
import dev.aarso.domain.tree.Nodes
import dev.aarso.domain.tree.PathView
import dev.aarso.domain.tree.TreeOutline
import android.content.Context
import dev.aarso.embedding.EmbeddingLogger
import dev.aarso.inference.EngineProvider
import dev.aarso.inference.InferenceEngine
import dev.aarso.inference.ModelRegistry
import dev.aarso.inference.image.ImageEngineFactory
import dev.aarso.service.GenerationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where a turn is in its lifecycle, for the progress indicator. */
enum class GenPhase { IDLE, LOADING, GENERATING }

/** A switchable model as shown in the picker. */
data class ModelOption(
    val id: String,
    val displayName: String,
    val contextWindow: Int,
    val runnable: Boolean,
    val watched: Boolean,
    /** File size for downloaded on-device models; null for cloud/dev specs. */
    val sizeBytes: Long? = null,
)

/**
 * The composer mode: one voice, a council of them (never labelled "MoE"), or an
 * image turn (§6 of the spatial redesign — Images is a mode, not a room).
 */
enum class ComposerMode { SINGLE, PERSONAS, MODELS, IMAGE }

/** One voice in the council panel (§4b) — streaming or persisted. */
data class CouncilCard(
    val agent: String,
    val text: String,
    val done: Boolean,
    val nodeId: String?,
)

data class ChatUiState(
    val steps: List<PathView.Step> = emptyList(),
    /** Per-token while streaming, so the UI can colour by entropy/confidence (§5a). */
    val streamingTokens: List<GeneratedToken> = emptyList(),
    val isGenerating: Boolean = false,
    val models: List<ModelOption> = emptyList(),
    val activeModelId: String = "",
    val activeModelLabel: String = "",
    val context: ContextCheck? = null,
    /** Input/output token ratio for the visible path (§5a). */
    val tokenStats: TokenStats? = null,
    val prefillPending: Boolean = false,
    val engineAvailable: Boolean = true,
    /** True when no model is active at all — the first-run / setup state. */
    val noModelActive: Boolean = false,
    /** Why the active model can't run (cloud needs a key, GGUF needs native build). */
    val unavailableReason: String? = null,
    /** A runtime error from the last turn (model load / generation failure). */
    val error: String? = null,
    /** The visible path ends on a user turn with no reply — Retry can regenerate. */
    val canRegenerate: Boolean = false,
    val genPhase: GenPhase = GenPhase.IDLE,
    /** On-demand prompt rewrite (§6b): a suggested rewrite of the input, or null. */
    val promptSuggestion: String? = null,
    val rewriting: Boolean = false,
    /** Council (§4b): whether council mode is on, and the current panel of voices. */
    val councilEnabled: Boolean = false,
    val councilCards: List<CouncilCard> = emptyList(),
    /** Council diversity: false = personas (one model, many hats), true = different models. */
    val modelDiversity: Boolean = false,
    /** Image mode (§6): send generates an image turn instead of a text turn. */
    val imageMode: Boolean = false,
) {
    val composerMode: ComposerMode
        get() = when {
            imageMode -> ComposerMode.IMAGE
            councilEnabled && modelDiversity -> ComposerMode.MODELS
            councilEnabled -> ComposerMode.PERSONAS
            else -> ComposerMode.SINGLE
        }
    val streamingText: String? get() = if (isGenerating) streamingTokens.joinToString("") { it.text } else null
    val councilPending: Boolean get() = councilCards.isNotEmpty()
}

private data class Transient(
    val stream: StreamState = StreamState(),
    val context: ContextCheck? = null,
    val tokenStats: TokenStats? = null,
    val prefillPending: Boolean = false,
    val error: String? = null,
    val genPhase: GenPhase = GenPhase.IDLE,
    val promptSuggestion: String? = null,
    val rewriting: Boolean = false,
    val councilMode: Boolean = false,
    val modelDiversity: Boolean = false,
    val imageMode: Boolean = false,
    /** Live per-agent streaming during a council fan-out; null when not fanning. */
    val councilStreaming: List<CouncilCard>? = null,
)

private data class StreamState(
    val tokens: List<GeneratedToken> = emptyList(),
    val isGenerating: Boolean = false,
)

/**
 * Drives the chat loop, branching, model switching, and the light instrumentation
 * (handoff §2/§3/§5a). The visible conversation is the path from the root to
 * [activeLeafId]; the active model sets the template, tokenizer, and window.
 */
class ChatViewModel(
    private val repository: MessageTreeRepository,
    private val registry: ModelRegistry,
    private val engines: EngineProvider,
    private val providers: ProviderStore,
    private val locals: LocalModelStore,
    private val embeddingLogger: EmbeddingLogger,
    private val kvCache: KvCacheStore,
    private val sharedIntake: SharedIntake,
    private val session: SessionStore,
    private val downloadCenter: DownloadCenter,
    private val downloader: ModelDownloader,
    private val imageStore: ImageStore,
    private val imageProviders: ImageProviderStore,
    private val sdModels: SdModelStore,
    private val pricingStore: dev.aarso.data.PricingStore,
    private val freeTierUsage: dev.aarso.data.FreeTierUsageStore,
    private val councilStore: dev.aarso.data.CouncilStore,
    private val appContext: Context,
) : ViewModel() {

    /** Content routed in from share / text-selection / assist (§7). */
    val intake: StateFlow<Intake?> get() = sharedIntake.pending
    fun consumeIntake(): Intake? = sharedIntake.consume()

    // Restored from the previous session and written through on every change,
    // so the app reopens where the user left it instead of an empty chat.
    private val activeLeafId = MutableStateFlow(session.activeLeafId.value)
    private val activeModelId = MutableStateFlow(
        DefaultModelPolicy.resolveActive(registry.allSpecs(), session.activeModelId.value)?.id,
    )
    private val transient = MutableStateFlow(
        // New conversations start in the owner's preferred council mode (Settings → Global).
        when (session.councilDefault.value) {
            "PERSONAS" -> Transient(councilMode = true, modelDiversity = false)
            "MODELS" -> Transient(councilMode = true, modelDiversity = true)
            else -> Transient()
        },
    )

    init {
        // A persisted leaf can be stale only if the DB was wiped (the tree is
        // append-only); fall back to a fresh chat rather than a broken path.
        viewModelScope.launch {
            val leaf = activeLeafId.value
            if (leaf != null && repository.node(leaf) == null) moveLeaf(null)
        }
        // When a download lands and nothing is active yet, adopt it. On-device
        // only — DefaultModelPolicy never resolves to a cloud model.
        viewModelScope.launch {
            locals.models.collect {
                if (activeModelId.value == null) {
                    DefaultModelPolicy.resolveActive(registry.allSpecs(), session.activeModelId.value)
                        ?.let { spec -> setActiveModel(spec.id) }
                }
            }
        }
    }

    // The in-flight token collection; cancelling it is how Stop works. The local
    // engine's Flow already requests a native stop in its awaitClose, and cloud
    // engines cancel their SSE call — partial text survives and is persisted.
    private var generationJob: Job? = null
    private var stopRequested = false

    /** Stop the current generation, keeping whatever streamed so far. */
    fun stopGeneration() {
        stopRequested = true
        generationJob?.cancel()
    }

    /**
     * Run [collect] as a cancellable child, swallowing the cancellation (a stop
     * keeps partial output) but re-throwing real engine errors in the caller.
     */
    private suspend fun collectCancellable(collect: suspend () -> Unit) {
        var genError: Throwable? = null
        val job = viewModelScope.launch {
            try {
                collect()
            } catch (t: Throwable) {
                if (t !is CancellationException) genError = t
            }
        }
        generationJob = job
        job.join()
        generationJob = null
        genError?.let { throw it }
    }

    private fun moveLeaf(id: String?) {
        activeLeafId.value = id
        session.setActiveLeafId(id)
    }

    private fun setActiveModel(id: String?) {
        activeModelId.value = id
        session.setActiveModelId(id)
    }

    // First-run setup card (§ usability rework): the one recommended starter
    // model for this device, and its download state. The init collector above
    // auto-activates it the moment the file lands.
    val device = DeviceInfo.read(appContext)
    val starter: CatalogModel? = StarterModels.recommend(ModelCatalog.models, device)
    val starterFitReason: String? = starter?.let { ModelFit.check(it.sizeBytes, device).reason }

    val starterDownload: StateFlow<ModelDownloader.Progress?> =
        downloadCenter.active
            .map { active -> starter?.let { active[it.id]?.progress } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun downloadStarter() {
        val model = starter ?: return
        downloadCenter.enqueue(model.id, model.downloadUrl, model.hfFile, downloader)
    }

    fun retryStarter() {
        starter?.let { downloadCenter.retry(it.id) }
    }

    /** Start a fresh conversation: the next send becomes a new root in the tree. */
    fun newChat() {
        if (transient.value.genPhase != GenPhase.IDLE) return
        moveLeaf(null)
        transient.value = transient.value.copy(
            context = null,
            tokenStats = null,
            error = null,
            promptSuggestion = null,
        )
    }

    /** Emits whenever the available model set changes (cloud providers or downloads). */
    private val registryChanges = combine(providers.providers, locals.models) { _, _ -> Unit }

    private fun ModelSpec.toOption() = ModelOption(
        id = id,
        displayName = displayName,
        contextWindow = contextWindow,
        runnable = engines.isRunnable(this),
        watched = watched,
        sizeBytes = locals.models.value.firstOrNull { "local:${it.name}" == id }?.sizeBytes,
    )

    /** UI prefs (persisted): instruments disclosure and entropy colouring. */
    val instrumentsExpanded: StateFlow<Boolean> get() = session.instrumentsExpanded
    fun setInstrumentsExpanded(expanded: Boolean) = session.setInstrumentsExpanded(expanded)
    val entropyColoring: StateFlow<Boolean> get() = session.entropyColoring
    fun setEntropyColoring(enabled: Boolean) = session.setEntropyColoring(enabled)

    fun setComposerMode(mode: ComposerMode) {
        transient.value = transient.value.copy(
            councilMode = mode == ComposerMode.PERSONAS || mode == ComposerMode.MODELS,
            modelDiversity = mode == ComposerMode.MODELS,
            imageMode = mode == ComposerMode.IMAGE,
        )
    }

    // ── Interaction-model immutability (IA §B4) ──────────────────────────────
    // Once a conversation has started, its interaction model (Single / Council·personas /
    // Council·models) is locked. Changing it doesn't mutate in place — it branches, with a
    // summary of the conversation so far folded into the new branch. Image is a transient
    // generation mode, not an interaction model, so it never triggers this.
    private val _pendingInteractionChange = MutableStateFlow<ComposerMode?>(null)
    val pendingInteractionChange: StateFlow<ComposerMode?> = _pendingInteractionChange.asStateFlow()

    private fun currentInteractionMode(): ComposerMode {
        val t = transient.value
        return when {
            t.councilMode && t.modelDiversity -> ComposerMode.MODELS
            t.councilMode -> ComposerMode.PERSONAS
            else -> ComposerMode.SINGLE
        }
    }

    /** Called by the composer's interaction chips. Branches-with-summary if the conversation
     *  has already started and the interaction model genuinely changes; else switches directly. */
    fun requestComposerMode(mode: ComposerMode) {
        val started = activeLeafId.value != null
        if (mode == ComposerMode.IMAGE || !started || mode == currentInteractionMode()) {
            setComposerMode(mode)
            return
        }
        _pendingInteractionChange.value = mode
    }

    fun cancelInteractionChange() { _pendingInteractionChange.value = null }

    /** Confirmed: summarize the active path, append it as a new branch, then switch the model. */
    fun confirmInteractionChange() {
        val mode = _pendingInteractionChange.value ?: return
        _pendingInteractionChange.value = null
        if (transient.value.genPhase != GenPhase.IDLE) return
        viewModelScope.launch {
            try {
                val summary = summarizeActivePath()
                val parent = activeLeafId.value?.let { repository.node(it) }
                val label = when (mode) {
                    ComposerMode.MODELS -> "Council · models"
                    ComposerMode.PERSONAS -> "Council · personas"
                    else -> "Single"
                }
                val node = Nodes.child(
                    parent = parent,
                    role = Role.SYSTEM,
                    content = "Interaction model → $label. Summary of the conversation so far:\n\n$summary",
                    now = System.currentTimeMillis(),
                    metadata = mapOf("interactionSwitch" to mode.name, "summary" to "true"),
                )
                repository.insert(node)
                moveLeaf(node.id)
                setComposerMode(mode)
                recomputeStatusAsync()
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "couldn't switch model")
            }
        }
    }

    /** A concise brief of the active path — model-made if a model is runnable, else an excerpt. */
    private suspend fun summarizeActivePath(): String {
        val leaf = activeLeafId.value ?: return "(no conversation yet)"
        val path = repository.tree().pathToRoot(leaf)
        val transcript = path.filter { it.role != Role.SYSTEM && it.content.isNotBlank() }
            .joinToString("\n") {
                val who = if (it.role == Role.USER) "User" else "Assistant"
                "$who: ${it.content.take(800)}"
            }
        if (transcript.isBlank()) return "(no conversation yet)"
        val spec = activeModelId.value?.let { registry.byId(it) }
        val engine = spec?.let { engines.engineFor(it) }
        if (engine != null && spec != null) {
            return runCatching {
                engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
                val now = System.currentTimeMillis()
                val msgs = listOf(
                    MessageNode("sum-sys", null, Role.SYSTEM, SUMMARY_SYSTEM, createdAt = now),
                    MessageNode("sum-usr", "sum-sys", Role.USER, transcript, createdAt = now + 1),
                )
                val sb = StringBuilder()
                engine.generate(msgs, SamplingParams()).collect { sb.append(it.text) }
                sb.toString().trim().ifBlank { transcript.take(1200) }
            }.getOrElse { transcript.take(1200) }
        }
        return transcript.take(1200)
    }

    fun clearError() {
        transient.value = transient.value.copy(error = null)
    }

    val uiState: StateFlow<ChatUiState> =
        combine(
            repository.observeTree(),
            activeLeafId,
            activeModelId,
            transient,
            registryChanges, // rebuild options when providers or downloads change
        ) { tree, leaf, modelId, t, _ ->
            val spec = modelId?.let { registry.byId(it) }
            val steps = if (leaf == null) emptyList() else PathView.annotate(tree, leaf)
            // Council panel: live stream if fanning out, else the persisted set of
            // agent siblings under a user leaf (the lateral axis, §4b).
            val councilCards = t.councilStreaming ?: run {
                val leafNode = leaf?.let { tree.node(it) }
                if (leafNode != null && leafNode.role == Role.USER) {
                    tree.childrenOf(leafNode.id)
                        .filter { it.metadata.containsKey("agent") }
                        .map { CouncilCard(it.metadata["agent"] ?: "voice", it.content, true, it.id) }
                } else {
                    emptyList()
                }
            }
            ChatUiState(
                steps = steps,
                streamingTokens = t.stream.tokens,
                isGenerating = t.stream.isGenerating,
                models = registry.allSpecs().map { it.toOption() },
                activeModelId = spec?.id ?: "",
                activeModelLabel = spec?.displayName ?: "No model",
                context = t.context,
                tokenStats = t.tokenStats,
                prefillPending = t.prefillPending,
                engineAvailable = spec != null && engines.isRunnable(spec),
                noModelActive = spec == null,
                unavailableReason = spec?.let { engines.unavailableReason(it) },
                error = t.error,
                canRegenerate = steps.lastOrNull()?.node?.role == Role.USER &&
                    councilCards.isEmpty() && t.genPhase == GenPhase.IDLE,
                genPhase = t.genPhase,
                promptSuggestion = t.promptSuggestion,
                rewriting = t.rewriting,
                councilEnabled = t.councilMode,
                councilCards = councilCards,
                modelDiversity = t.modelDiversity,
                imageMode = t.imageMode,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ChatUiState(
                models = registry.allSpecs().map { it.toOption() },
                activeModelId = activeModelId.value ?: "",
                activeModelLabel = activeModelId.value
                    ?.let { registry.byId(it)?.displayName } ?: "No model",
                noModelActive = activeModelId.value == null,
            ),
        )

    /** Every conversation (one per root), newest activity first, for the map. */
    val conversations: StateFlow<List<Conversations.Summary>> =
        repository.observeTree()
            .map { Conversations.summarize(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The active conversation as an indented outline (§8.5). Scoped to the active
     * root: the cross-conversation view is the [conversations] list above it.
     */
    val treeOutline: StateFlow<List<TreeOutline.Row>> =
        combine(repository.observeTree(), activeLeafId) { tree, leaf ->
            val rows = TreeOutline.build(tree, leaf)
            val activeRoot = leaf?.let { Conversations.rootOf(tree, it) }
                ?: return@combine rows.takeIf { tree.roots().size <= 1 }.orEmpty()
            // Rows are grouped by root in walk order; keep the active root's slice.
            val start = rows.indexOfFirst { it.depth == 0 && it.node.id == activeRoot }
            if (start < 0) return@combine emptyList()
            val next = rows.drop(start + 1).indexOfFirst { it.depth == 0 }
            val end = if (next < 0) rows.size else start + 1 + next
            rows.subList(start, end)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    /** Open a conversation from the map: land on the tip of its newest branch. */
    fun openConversation(rootId: String) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        viewModelScope.launch {
            val tree = repository.tree()
            moveLeaf(tree.descendToLeaf(rootId) ?: return@launch)
            recomputeStatusAsync()
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || transient.value.genPhase != GenPhase.IDLE) return
        if (transient.value.imageMode) { sendImage(trimmed); return }
        if (transient.value.councilMode) { sendCouncil(trimmed); return }
        val spec = activeModelId.value?.let { registry.byId(it) } ?: return
        val engine = engines.engineFor(spec) ?: return // not runnable yet

        viewModelScope.launch {
            transient.value = transient.value.copy(error = null)
            try {
                val parent = activeLeafId.value?.let { repository.node(it) }
                val userNode = Nodes.child(
                    parent = parent,
                    role = Role.USER,
                    content = trimmed,
                    now = System.currentTimeMillis(),
                )
                repository.insert(userNode)
                embeddingLogger.onMessageInserted(userNode)
                moveLeaf(userNode.id)
                runTurn(spec, engine, userNode)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "send failed")
            }
        }
    }

    /**
     * Regenerate a reply for the user turn the path currently ends on — the
     * state a failed or stopped-before-output turn leaves behind.
     */
    fun regenerate() {
        if (transient.value.genPhase != GenPhase.IDLE) return
        val spec = activeModelId.value?.let { registry.byId(it) } ?: return
        val engine = engines.engineFor(spec) ?: return
        viewModelScope.launch {
            val leafId = activeLeafId.value ?: return@launch
            val userNode = repository.node(leafId)?.takeIf { it.role == Role.USER } ?: return@launch
            transient.value = transient.value.copy(error = null)
            runTurn(spec, engine, userNode)
        }
    }

    /**
     * Load + stream + persist one assistant turn under [userNode]. KV-cache
     * (§8.3): resume from the node being continued (the user turn's parent) and
     * snapshot keyed to the new assistant node — local engine only; cloud/echo
     * ignore the session paths.
     */
    private suspend fun runTurn(spec: ModelSpec, engine: InferenceEngine, userNode: MessageNode) {
        val isLocal = spec.runtime == Runtime.LOCAL_GGUF
        val loadPath = userNode.parentId
            ?.takeIf { isLocal && kvCache.exists(it) }
            ?.let { kvCache.pathFor(it) }
        val assistantId = java.util.UUID.randomUUID().toString()
        val savePath = if (isLocal) kvCache.pathFor(assistantId) else null

        // Keep the process alive while an ON-DEVICE model loads/generates (survives minimize).
        // Echo/cloud turns don't hold a model process, so they never need the foreground service —
        // and starting one for them risks the framework's async "FGS did not start in time" crash
        // (it sits outside this try, so it can't be caught). This is the dev-Echo send crash.
        if (isLocal) GenerationService.start(appContext)
        stopRequested = false
        try {
            // Load the active model (idempotent; a local GGUF may take several
            // seconds — shown as the "loading model" phase).
            transient.value = transient.value.copy(genPhase = GenPhase.LOADING)
            engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
            repository.setTokenCount(
                userNode.id, spec.tokenizerId,
                runCatching { engine.countTokens(userNode.content) }.getOrDefault(0),
            )
            if (stopRequested) {
                // Stopped while the model was loading: keep the user turn, no reply.
                transient.value = transient.value.copy(stream = StreamState(), genPhase = GenPhase.IDLE)
                return
            }

            // Stream the assistant turn, keeping per-token data (entropy) for
            // the §5a colouring.
            transient.value = transient.value.copy(
                stream = StreamState(tokens = emptyList(), isGenerating = true),
                prefillPending = false,
                genPhase = GenPhase.GENERATING,
            )
            val path = repository.path(userNode.id)
            val tokens = mutableListOf<GeneratedToken>()
            collectCancellable {
                engine.generate(path, SamplingParams(), loadPath, savePath).collect { token ->
                    tokens += token
                    transient.value = transient.value.copy(
                        stream = StreamState(tokens = tokens.toList(), isGenerating = true),
                    )
                }
            }

            // Persist the assistant turn (tagged with the producing model; a
            // stopped turn keeps its partial text and says it was cut).
            val assistantText = tokens.joinToString("") { it.text }.trim()
            if (assistantText.isEmpty() && stopRequested) {
                transient.value = transient.value.copy(stream = StreamState(), genPhase = GenPhase.IDLE)
                return
            }
            // Cost (G1): price a finished cloud turn from the provider-reported usage the
            // engine captured this turn (UsageAccumulator). On-device turns report no usage,
            // so they carry no cost line — the honest "no money changed hands" state.
            val costMeta = (engine as? dev.aarso.inference.cloud.CloudEngine)?.lastUsage
                ?.takeIf { it.totalTokens > 0 }
                ?.let { usage ->
                    // Count this turn against the provider's free-tier usage (owner ask).
                    spec.providerId?.let { freeTierUsage.record(it, usage.inputTokens.toLong(), usage.outputTokens.toLong()) }
                    val cost = usage.toAdviceCost(pricingStore.book.value.priceFor(engine.tokenizerId))
                    mapOf(
                        "costMinor" to cost.moneyMinor.toString(),
                        "tokensIn" to usage.inputTokens.toString(),
                        "tokensOut" to usage.outputTokens.toString(),
                    )
                }.orEmpty()
            val assistantNode = Nodes.child(
                parent = userNode,
                role = Role.ASSISTANT,
                content = assistantText,
                now = System.currentTimeMillis(),
                modelId = spec.id,
                metadata = (if (stopRequested) mapOf("stopped" to "true") else emptyMap()) + costMeta,
                idGen = { assistantId }, // so the KV snapshot is keyed to this node
            )
            repository.insert(assistantNode)
            repository.setTokenCount(
                assistantNode.id, spec.tokenizerId,
                runCatching { engine.countTokens(assistantText) }.getOrDefault(0),
            )
            moveLeaf(assistantNode.id)
            transient.value = transient.value.copy(stream = StreamState(), genPhase = GenPhase.IDLE)
            recomputeStatus(spec, assistantNode.id)
        } catch (t: Throwable) {
            transient.value = transient.value.copy(
                stream = StreamState(),
                error = t.message ?: "generation failed",
                genPhase = GenPhase.IDLE,
            )
        } finally {
            if (isLocal) GenerationService.stop(appContext)
        }
    }

    fun toggleCouncil() {
        transient.value = transient.value.copy(councilMode = !transient.value.councilMode)
    }

    fun toggleModelDiversity() {
        transient.value = transient.value.copy(modelDiversity = !transient.value.modelDiversity)
    }

    /** A single voice in a council fan-out: its label, the model + engine it runs on,
     *  and an optional persona system prompt. */
    private data class Voice(
        val label: String,
        val spec: ModelSpec,
        val engine: InferenceEngine,
        val systemPrompt: String?,
    )

    private fun councilVoices(): List<Voice> {
        return if (transient.value.modelDiversity) {
            // Model-diversity: each genuinely different runnable model is a voice
            // (a group chat of models), no personas.
            registry.allSpecs()
                .filter { it.runtime != Runtime.ECHO_DEV && engines.isRunnable(it) }
                .mapNotNull { spec -> engines.engineFor(spec)?.let { Voice(spec.displayName, spec, it, null) } }
        } else {
            // Persona council = the editable roster (IA §B4): each member runs its own model
            // (or the chat's active model) with its instructions + long-term memory folded in.
            val activeSpec = activeModelId.value?.let { registry.byId(it) }
            councilStore.participants.value.ifEmpty {
                dev.aarso.domain.council.Council.defaultAgents.map {
                    dev.aarso.data.Participant("d-${it.name}", it.name, it.systemPrompt)
                }
            }.mapNotNull { p ->
                val spec = (p.modelId?.let { registry.byId(it) } ?: activeSpec) ?: return@mapNotNull null
                val engine = engines.engineFor(spec) ?: return@mapNotNull null
                Voice(p.name, spec, engine, dev.aarso.data.CouncilStore.systemPromptFor(p))
            }
        }
    }

    /**
     * Council fan-out (handoff §4a/§4b): send the same context to N named agents
     * (persona-diversity — one model, different system prompts) and hold their
     * answers as sibling responses under the user turn. On-device this is
     * sequential — one phone cannot truly run N models at once (§4b) — streamed
     * into separate panes as each completes.
     */
    private fun sendCouncil(text: String) {
        val voices = councilVoices()
        if (voices.isEmpty()) {
            transient.value = transient.value.copy(
                error = if (transient.value.modelDiversity) "no runnable models for a model-diversity council — download/add at least two" else "active model not runnable",
            )
            return
        }
        viewModelScope.launch {
            transient.value = transient.value.copy(error = null)
            val parent = activeLeafId.value?.let { repository.node(it) }
            val userNode = Nodes.child(parent, Role.USER, text, System.currentTimeMillis())
            try {
                repository.insert(userNode)
                embeddingLogger.onMessageInserted(userNode)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "council failed")
                return@launch
            }
            moveLeaf(userNode.id)

            GenerationService.start(appContext)
            stopRequested = false
            try {
                transient.value = transient.value.copy(genPhase = GenPhase.LOADING)
                val basePath = repository.path(userNode.id)
                val cards = voices.map { CouncilCard(it.label, "", false, null) }.toMutableList()
                transient.value = transient.value.copy(councilStreaming = cards.toList(), genPhase = GenPhase.GENERATING)
                for ((i, voice) in voices.withIndex()) {
                    if (stopRequested) break // stop skips the remaining voices
                    voice.engine.loadModel(voice.spec.modelPath ?: "(dev)", voice.spec.contextWindow)
                    if (stopRequested) break
                    val msgs = buildList {
                        voice.systemPrompt?.let { add(MessageNode("council-sys-$i", null, Role.SYSTEM, it, createdAt = System.currentTimeMillis())) }
                        addAll(basePath)
                    }
                    val sb = StringBuilder()
                    collectCancellable {
                        voice.engine.generate(msgs, SamplingParams()).collect { tok ->
                            sb.append(tok.text)
                            cards[i] = cards[i].copy(text = sb.toString())
                            transient.value = transient.value.copy(councilStreaming = cards.toList())
                        }
                    }
                    if (sb.isBlank() && stopRequested) break
                    val node = Nodes.child(
                        parent = userNode, role = Role.ASSISTANT, content = sb.toString().trim(),
                        now = System.currentTimeMillis(), modelId = voice.spec.id,
                        metadata = buildMap {
                            put("council", userNode.id)
                            put("agent", voice.label)
                            if (stopRequested) put("stopped", "true")
                        },
                    )
                    repository.insert(node)
                    repository.setTokenCount(node.id, voice.spec.tokenizerId, runCatching { voice.engine.countTokens(sb.toString()) }.getOrDefault(0))
                    cards[i] = cards[i].copy(done = true, nodeId = node.id)
                    transient.value = transient.value.copy(councilStreaming = cards.toList())
                }
                // Leaf stays at the user node: the panel is now held simultaneously.
                transient.value = transient.value.copy(councilStreaming = null, genPhase = GenPhase.IDLE)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(councilStreaming = null, genPhase = GenPhase.IDLE, error = t.message ?: "council failed")
            } finally {
                GenerationService.stop(appContext)
            }
        }
    }

    /**
     * Image turn (§6): the prompt becomes a user node, the generated image an
     * assistant node tagged [Conversations.IMAGE_KEY] — every turn is a node,
     * including image turns. On-device SD is preferred (binding rule 2); a cloud
     * image provider with a key is the explicit fallback; neither → error.
     */
    private fun sendImage(prompt: String) {
        val sdModel = sdModels.models.value.firstOrNull()
        val cloud = imageProviders.providers.value.firstOrNull { imageProviders.hasApiKey(it.id) }
        if (sdModel == null && cloud == null) {
            transient.value = transient.value.copy(
                error = "no image model — download one in Models, or add an image provider in Settings",
            )
            return
        }
        viewModelScope.launch {
            transient.value = transient.value.copy(error = null)
            val parent = activeLeafId.value?.let { repository.node(it) }
            val userNode = Nodes.child(parent, Role.USER, prompt, System.currentTimeMillis())
            try {
                repository.insert(userNode)
                embeddingLogger.onMessageInserted(userNode)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(error = t.message ?: "send failed")
                return@launch
            }
            moveLeaf(userNode.id)

            GenerationService.start(appContext)
            try {
                transient.value = transient.value.copy(genPhase = GenPhase.GENERATING)
                val (path, producerId) = if (sdModel != null) {
                    // On-device: unload the LLM first (RAM), then the slow SD run.
                    engines.unloadLocalModel()
                    val engine = dev.aarso.inference.image.SdImageEngine(sdModel.path, imageStore)
                    try {
                        engine.generate(prompt, ImageParams(size = 512, steps = 20)) to "sd:${sdModel.name}"
                    } finally {
                        engine.release()
                    }
                } else {
                    val key = imageProviders.apiKey(cloud!!.id)
                        ?: throw IllegalStateException("no key for ${cloud.displayName}")
                    val engine = ImageEngineFactory.create(cloud, key, imageStore)
                    engine.generate(prompt, ImageParams()) to "image:${cloud.id}"
                }
                val imageNode = Nodes.child(
                    parent = userNode,
                    role = Role.ASSISTANT,
                    content = "",
                    now = System.currentTimeMillis(),
                    modelId = producerId,
                    metadata = mapOf(Conversations.IMAGE_KEY to path),
                )
                repository.insert(imageNode)
                moveLeaf(imageNode.id)
                transient.value = transient.value.copy(genPhase = GenPhase.IDLE)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(
                    genPhase = GenPhase.IDLE,
                    error = t.message ?: "image generation failed",
                )
            } finally {
                GenerationService.stop(appContext)
            }
        }
    }

    /** Every image turn across all conversations — the §6 "browse images" filter. */
    val imageNodes: StateFlow<List<MessageNode>> =
        repository.observeTree()
            .map { Conversations.imageNodes(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Model-as-aggregator convergence (§4b, a mode). The on-thesis default is
     * human-as-aggregator — the user simply picks a voice (branchFrom) — so this
     * is an explicit, optional action.
     */
    fun autoMergeCouncil() {
        if (transient.value.genPhase != GenPhase.IDLE) return
        val spec = activeModelId.value?.let { registry.byId(it) } ?: return
        val engine = engines.engineFor(spec) ?: return
        viewModelScope.launch {
            val tree = repository.tree()
            val leaf = activeLeafId.value ?: return@launch
            val userNode = tree.node(leaf) ?: return@launch
            if (userNode.role != Role.USER) return@launch
            val kids = tree.childrenOf(userNode.id).filter { it.metadata.containsKey("agent") }
            if (kids.isEmpty()) return@launch
            GenerationService.start(appContext)
            stopRequested = false
            try {
                transient.value = transient.value.copy(genPhase = GenPhase.LOADING)
                engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
                val answers = kids.map { (it.metadata["agent"] ?: "voice") to it.content }
                val now = System.currentTimeMillis()
                val msgs = listOf(
                    MessageNode("agg-sys", null, Role.SYSTEM, Council.aggregatorSystemPrompt(), createdAt = now),
                    MessageNode("agg-usr", "agg-sys", Role.USER, Council.aggregatorUserPrompt(userNode.content, answers), createdAt = now + 1),
                )
                transient.value = transient.value.copy(genPhase = GenPhase.GENERATING)
                val sb = StringBuilder()
                collectCancellable {
                    engine.generate(msgs, SamplingParams()).collect { sb.append(it.text) }
                }
                if (sb.isBlank() && stopRequested) {
                    transient.value = transient.value.copy(genPhase = GenPhase.IDLE)
                    return@launch
                }
                val node = Nodes.child(
                    parent = userNode, role = Role.ASSISTANT, content = sb.toString().trim(),
                    now = System.currentTimeMillis(), modelId = spec.id,
                    metadata = buildMap {
                        put("aggregator", "model")
                        if (stopRequested) put("stopped", "true")
                    },
                )
                repository.insert(node)
                moveLeaf(node.id) // continue from the convergence
                transient.value = transient.value.copy(genPhase = GenPhase.IDLE)
            } catch (t: Throwable) {
                transient.value = transient.value.copy(genPhase = GenPhase.IDLE, error = t.message ?: "merge failed")
            } finally {
                GenerationService.stop(appContext)
            }
        }
    }

    override fun onCleared() {
        // App task removed: don't leave a dangling foreground service.
        GenerationService.stop(appContext)
    }

    /**
     * On-demand prompt rewrite (handoff §6b): ask the active model to improve the
     * draft prompt. Runs over ephemeral (non-persisted) messages; the result is a
     * suggestion the user can accept or dismiss — never auto-applied.
     */
    fun refinePrompt(text: String) {
        val draft = text.trim()
        if (draft.isEmpty() || transient.value.rewriting || transient.value.genPhase != GenPhase.IDLE) return
        val spec = activeModelId.value?.let { registry.byId(it) } ?: return
        val engine = engines.engineFor(spec) ?: return
        viewModelScope.launch {
            transient.value = transient.value.copy(rewriting = true, promptSuggestion = null, error = null)
            try {
                engine.loadModel(spec.modelPath ?: "(dev)", spec.contextWindow)
                val now = System.currentTimeMillis()
                val msgs = listOf(
                    MessageNode("rw-sys", null, Role.SYSTEM, REWRITE_SYSTEM, createdAt = now),
                    MessageNode("rw-usr", "rw-sys", Role.USER, "Rewrite this prompt:\n\n$draft", createdAt = now + 1),
                )
                val sb = StringBuilder()
                engine.generate(msgs, SamplingParams()).collect { sb.append(it.text) }
                transient.value = transient.value.copy(rewriting = false, promptSuggestion = sb.toString().trim())
            } catch (e: Throwable) {
                transient.value = transient.value.copy(rewriting = false, error = e.message ?: "rewrite failed")
            }
        }
    }

    fun clearSuggestion() {
        transient.value = transient.value.copy(promptSuggestion = null)
    }

    /** Restore to a touchpoint (handoff §2): make [nodeId] the active leaf. */
    fun branchFrom(nodeId: String) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        moveLeaf(nodeId)
        recomputeStatusAsync()
    }

    /** Move between the alternative continuations at a branch point. */
    fun switchAlternative(branchNodeId: String, direction: Int) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        viewModelScope.launch {
            val tree = repository.tree()
            val children = tree.childrenOf(branchNodeId)
            if (children.size < 2) return@launch
            val leaf = activeLeafId.value ?: return@launch
            val path = tree.pathToRoot(leaf).map { it.id }
            val branchIndex = path.indexOf(branchNodeId)
            val currentChildId = path.getOrNull(branchIndex + 1)
            val currentIndex = children.indexOfFirst { it.id == currentChildId }.coerceAtLeast(0)
            val nextIndex = (currentIndex + direction).mod(children.size)
            moveLeaf(tree.descendToLeaf(children[nextIndex].id))
            recomputeStatusAsync()
        }
    }

    /**
     * Switch the model the conversation continues with (handoff §3). Re-renders
     * the path under the new template / tokenizer to re-measure context, and
     * raises the prefill reminder: the KV cache cannot cross models.
     */
    fun switchModel(modelId: String) {
        if (transient.value.genPhase != GenPhase.IDLE) return
        val spec = registry.byId(modelId) ?: return
        setActiveModel(modelId)
        transient.value = transient.value.copy(prefillPending = true)
        recomputeStatusAsync(spec)
    }

    private fun recomputeStatusAsync(spec: ModelSpec? = null) {
        viewModelScope.launch {
            val active = spec ?: activeModelId.value?.let { registry.byId(it) } ?: return@launch
            recomputeStatus(active, activeLeafId.value)
        }
    }

    /** Recompute the per-path instrumentation: token I/O ratio and context fit. */
    private suspend fun recomputeStatus(spec: ModelSpec, leafId: String?) {
        if (leafId == null) {
            transient.value = transient.value.copy(context = null, tokenStats = null)
            return
        }
        val path = repository.path(leafId)

        // Token I/O ratio from stored counts — independent of the active model.
        val counts = path.map { it.role to repository.totalTokens(it.id) }
        val stats = TokenStats.of(counts)

        // Context fit needs an engine to tokenize the templated prompt.
        val engine = engines.engineFor(spec)
        val context = if (engine == null) {
            null
        } else {
            val rendered = ChatTemplates.forId(spec.templateId).render(path)
            checkContext(engine.countTokens(rendered), spec.contextWindow)
        }
        transient.value = transient.value.copy(context = context, tokenStats = stats)
    }

    companion object {
        private const val REWRITE_SYSTEM =
            "You improve user prompts. Output ONLY the rewritten prompt — clearer, " +
                "specific, with role/format/constraints where useful. No preamble, no commentary."

        private const val SUMMARY_SYSTEM =
            "Summarize the conversation below into a concise brief that preserves the goals, " +
                "key decisions, facts established, and open threads — enough for a fresh start to " +
                "continue seamlessly. Output ONLY the summary."

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as AarsoApp
                val c = app.container
                ChatViewModel(
                    c.repository,
                    c.modelRegistry,
                    c.engineProvider,
                    c.providerStore,
                    c.localModelStore,
                    c.embeddingLogger,
                    c.kvCacheStore,
                    c.sharedIntake,
                    c.sessionStore,
                    c.downloadCenter,
                    c.modelDownloader,
                    c.imageStore,
                    c.imageProviderStore,
                    c.sdModelStore,
                    c.pricingStore,
                    c.freeTierUsageStore,
                    c.councilStore,
                    app.applicationContext,
                )
            }
        }
    }
}
