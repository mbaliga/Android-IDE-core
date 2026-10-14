package dev.fonebrew.data.watch

import dev.fonebrew.data.TaskStore
import dev.fonebrew.domain.tasks.TaskState
import dev.fonebrew.watchbridge.AskError
import dev.fonebrew.watchbridge.AskRequest
import dev.fonebrew.watchbridge.ContractHello
import dev.fonebrew.watchbridge.DecodeResult
import dev.fonebrew.watchbridge.StreamFraming
import dev.fonebrew.watchbridge.TaskToggleRequest
import dev.fonebrew.watchbridge.TaskToggleResult
import dev.fonebrew.watchbridge.WatchBridgeCodec
import dev.fonebrew.watchbridge.WatchMessage
import dev.fonebrew.watchbridge.WatchPaths
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * All of the phone-side WatchBrew relay's logic, with no Android, Play Services or database types:
 * request to action, turn stream to chunk sequence, snapshot assembly, and the version handshake. The
 * Android service ([WearRelayService]) does nothing but hand each Data Layer event to [onMessage] with a
 * [WearTransport]; everything worth testing is here, and is (`WearRelayPresenterTest`).
 *
 * ## Inbound rules
 *
 * Every inbound message is classified by [classify] before anything acts on it:
 *
 *  - only paths the watch is allowed to use are acted on ([WatchPaths.SYNC], [WatchPaths.ASK],
 *    [WatchPaths.TASK_TOGGLE]); anything else, including the phone-to-watch paths, is ignored;
 *  - a message whose decoded type does not match its path is ignored (an `AskRequest` is only valid on
 *    the ask path);
 *  - a malformed or oversized payload is ignored, **never** partially acted on (the codec is
 *    all-or-nothing);
 *  - a well-formed envelope of another contract version is answered with this phone's
 *    [ContractHello] and nothing else, so the watch can say which side to update. Its payload is never read.
 *
 * ## The four behaviours
 *
 *  - **Ask**: open the reply channel FIRST (if the watch is unreachable nothing is run, so no orphan
 *    turn and no wasted compute), then drive [WristTurns] and write each event as a frame. One question
 *    at a time: a second arriving mid-answer is refused with a plain sentence rather than silently queued.
 *  - **Task toggle**: the desired state is applied through [TaskStore] (the phone stays the single
 *    writer); the authoritative [TaskToggleResult] goes back, followed by a fresh snapshot.
 *  - **Sync**: hello, then the tasks snapshot, then the loop runs, as independent best-effort messages.
 *  - **Everything else**: ignored.
 *
 * ## What never crosses the bridge
 *
 * Only contract messages are ever sent, and the contract has no field for credentials. Reply text is
 * the model's output; failures are reported by fixed sentences (see [WristTurnRunner], Secrets), never
 * by an exception message. This class has no reference to any key store or provider configuration.
 *
 * Nothing here logs: a prompt, a reply or a task title is the user's, and the only record of a relayed
 * turn is the message tree it is filed in.
 */
class WearRelayPresenter(
    private val turns: WristTurns,
    private val tasks: TaskStore,
    private val loops: LoopRunSource,
    phoneAppVersion: String,
) {
    private val appVersion: String = phoneAppVersion.toWireSafe().ifBlank { UNKNOWN_APP_VERSION }
    private val askGate = Mutex()

    /**
     * Handles one Data Layer message from [sourceNodeId]. Never throws (a relay problem must not take down
     * the listener service); only coroutine cancellation propagates.
     */
    suspend fun onMessage(sourceNodeId: String, path: String, payload: ByteArray, transport: WearTransport) {
        try {
            when (val inbound = classify(path, payload)) {
                is Inbound.Sync -> sync(sourceNodeId, transport)
                is Inbound.IncompatibleVersion -> sendQuietly(transport, sourceNodeId, WatchPaths.HELLO, helloMessage())
                is Inbound.Ask -> ask(sourceNodeId, inbound.request, transport)
                is Inbound.Toggle -> toggle(sourceNodeId, inbound.request, transport)
                is Inbound.Ignored -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliberately silent: no payload, prompt or message text is ever logged from here.
        }
    }

    // ---- classification (pure) --------------------------------------------------------------------

    internal sealed interface Inbound {
        data object Sync : Inbound
        data class Ask(val request: AskRequest) : Inbound
        data class Toggle(val request: TaskToggleRequest) : Inbound
        data object IncompatibleVersion : Inbound
        data class Ignored(val why: String) : Inbound
    }

    /** Path plus payload to what to do about it. Pure; the exhaustive table of what the phone accepts. */
    internal fun classify(path: String, payload: ByteArray): Inbound {
        if (path == WatchPaths.SYNC) return Inbound.Sync // a bare trigger: no envelope, nothing to version or parse
        if (path != WatchPaths.ASK && path != WatchPaths.TASK_TOGGLE) return Inbound.Ignored("not a path the watch may use: ${path.take(40)}")
        if (payload.size > MAX_INBOUND_BYTES) return Inbound.Ignored("payload of ${payload.size} bytes exceeds $MAX_INBOUND_BYTES")

        return when (val decoded = WatchBridgeCodec.decode(payload)) {
            is DecodeResult.Malformed -> Inbound.Ignored("malformed")
            is DecodeResult.Incompatible -> Inbound.IncompatibleVersion
            is DecodeResult.Decoded -> when (val message = decoded.message) {
                is AskRequest -> if (path == WatchPaths.ASK) Inbound.Ask(message) else Inbound.Ignored("AskRequest on $path")
                is TaskToggleRequest -> if (path == WatchPaths.TASK_TOGGLE) Inbound.Toggle(message) else Inbound.Ignored("TaskToggleRequest on $path")
                else -> Inbound.Ignored("${message.javaClass.simpleName} is not a watch-to-phone message")
            }
        }
    }

    // ---- version handshake --------------------------------------------------------------------------

    /** This phone's hello: the contract version it speaks and the Fonebrew build it is. */
    internal fun helloMessage(): WatchMessage = ContractHello.forThisBuild(appVersion)

    // ---- ask ----------------------------------------------------------------------------------------

    private suspend fun ask(nodeId: String, request: AskRequest, transport: WearTransport) {
        if (request.prompt.length > MAX_PROMPT_CHARS) {
            replyWithErrorOnly(nodeId, request, PROMPT_TOO_LONG_REASON, transport)
            return
        }
        if (!askGate.tryLock()) {
            replyWithErrorOnly(nodeId, request, BUSY_REASON, transport)
            return
        }
        try {
            stream(nodeId, request, transport)
        } finally {
            askGate.unlock()
        }
    }

    private suspend fun stream(nodeId: String, request: AskRequest, transport: WearTransport) {
        // Channel first: an unreachable watch means the question is not run at all.
        val channel = openOrNull(transport, nodeId) ?: return
        var chunker: ReplyChunker? = null
        try {
            turns.run(request.prompt) { event ->
                when (event) {
                    is WristTurnEvent.Engine ->
                        chunker = ReplyChunker(request.requestId, event.engineId.toWireSafe().ifBlank { UNKNOWN_ENGINE }, event.watchedObject)
                    is WristTurnEvent.Token -> {
                        val active = checkNotNull(chunker) { "token before the engine was announced" }
                        for (chunk in active.onToken(event.text)) channel.write(StreamFraming.encodeFrame(chunk))
                    }
                    is WristTurnEvent.Done -> {
                        val active = checkNotNull(chunker) { "done before the engine was announced" }
                        channel.write(StreamFraming.encodeFrame(active.finish(event.usage.toWire())))
                    }
                    is WristTurnEvent.Failed ->
                        channel.write(StreamFraming.encodeFrame(AskError(request.requestId, event.reason.toWireSafe().ifBlank { GENERIC_FAILURE_REASON })))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The link dropped (or a runner contract was broken). The turn has already been stopped and
            // its partial text filed by the runner; the stream simply ends with no terminal chunk, which
            // the wrist renders as "phone unreachable", never as a finished answer.
        } finally {
            withContext(NonCancellable) { channel.close() }
        }
    }

    private suspend fun replyWithErrorOnly(nodeId: String, request: AskRequest, reason: String, transport: WearTransport) {
        val channel = openOrNull(transport, nodeId) ?: return
        try {
            channel.write(StreamFraming.encodeFrame(AskError(request.requestId, reason)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing more to try: the watch is gone.
        } finally {
            withContext(NonCancellable) { channel.close() }
        }
    }

    private suspend fun openOrNull(transport: WearTransport, nodeId: String): ReplyChannel? =
        try {
            transport.openReplyChannel(nodeId, WatchPaths.ASK_REPLY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    // ---- tasks --------------------------------------------------------------------------------------

    /**
     * Applies a desired done-state through the phone's [TaskStore] and reports what the store now holds.
     * `done = true` sets DONE; `done = false` returns a DONE task to TODO and leaves a DOING or BLOCKED
     * task exactly as it is (it is already not done, and a checkbox on a wrist is not a way to demote it).
     */
    internal suspend fun applyToggle(request: TaskToggleRequest): TaskToggleResult {
        val task = tasks.tasks.first().firstOrNull { it.id == request.taskId }
            ?: return TaskToggleResult.Rejected(request.taskId, TASK_GONE_REASON)
        return try {
            when {
                request.done && task.state != TaskState.DONE -> tasks.setState(task, TaskState.DONE)
                !request.done && task.state == TaskState.DONE -> tasks.setState(task, TaskState.TODO)
                else -> Unit // already as requested; a retry is harmless
            }
            TaskToggleResult.Applied(request.taskId, done = request.done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TaskToggleResult.Rejected(request.taskId, TASK_SAVE_FAILED_REASON)
        }
    }

    private suspend fun toggle(nodeId: String, request: TaskToggleRequest, transport: WearTransport) {
        sendQuietly(transport, nodeId, WatchPaths.TASK_TOGGLE_RESULT, applyToggle(request))
        sendTasks(nodeId, transport) // authoritative: the watch reconciles its whole list to the phone's
    }

    private suspend fun sendTasks(nodeId: String, transport: WearTransport) {
        val snapshot = try {
            WristSnapshots.tasks(tasks.tasks.first())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        sendQuietly(transport, nodeId, WatchPaths.TASKS, snapshot)
    }

    // ---- sync ---------------------------------------------------------------------------------------

    private suspend fun sync(nodeId: String, transport: WearTransport) {
        sendQuietly(transport, nodeId, WatchPaths.HELLO, helloMessage())
        sendTasks(nodeId, transport)
        val runs = try {
            loops.recentRuns()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        for (run in runs) sendQuietly(transport, nodeId, WatchPaths.LOOPS, run)
    }

    private suspend fun sendQuietly(transport: WearTransport, nodeId: String, path: String, message: WatchMessage) {
        try {
            transport.send(nodeId, path, WatchBridgeCodec.encodeToBytes(message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort by design: the watch pulls again when it next opens.
        }
    }

    companion object {
        /** A watch-to-phone envelope is tiny; anything bigger is not ours and is not decoded. */
        const val MAX_INBOUND_BYTES: Int = 32 * 1024

        /** A prompt typed or dictated on a wrist; longer is refused in words rather than run. */
        const val MAX_PROMPT_CHARS: Int = 4000

        const val BUSY_REASON: String = "The phone is still answering your last question. Try again when it finishes."
        const val PROMPT_TOO_LONG_REASON: String = "That question is too long for the wrist (limit $MAX_PROMPT_CHARS characters). Ask it on the phone instead."
        const val GENERIC_FAILURE_REASON: String = "The phone could not answer that. Open Fonebrew there for details."
        const val TASK_GONE_REASON: String = "That task no longer exists on the phone."
        const val TASK_SAVE_FAILED_REASON: String = "The phone could not save that change."

        private const val UNKNOWN_APP_VERSION = "unknown"
        private const val UNKNOWN_ENGINE = "model"
    }
}
