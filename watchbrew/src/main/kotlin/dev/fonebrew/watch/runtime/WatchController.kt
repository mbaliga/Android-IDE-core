package dev.fonebrew.watch.runtime

import dev.fonebrew.watch.core.AskEvent
import dev.fonebrew.watch.core.AskState
import dev.fonebrew.watch.core.Connection
import dev.fonebrew.watch.core.LinkEvent
import dev.fonebrew.watch.core.LinkFailure
import dev.fonebrew.watch.core.MessageRouter
import dev.fonebrew.watch.core.PhoneNode
import dev.fonebrew.watch.core.PhoneNodePicker
import dev.fonebrew.watch.core.PromptPolicy
import dev.fonebrew.watch.core.SnapshotCache
import dev.fonebrew.watch.core.TaskEvent
import dev.fonebrew.watch.core.WatchEvent
import dev.fonebrew.watch.core.WatchReducer
import dev.fonebrew.watch.core.WatchState
import dev.fonebrew.watch.core.inFlight
import dev.fonebrew.watchbridge.AskRequest
import dev.fonebrew.watchbridge.AskChunk
import dev.fonebrew.watchbridge.AskError
import dev.fonebrew.watchbridge.DecodeResult
import dev.fonebrew.watchbridge.TaskToggleRequest
import dev.fonebrew.watchbridge.WatchBridgeCodec
import dev.fonebrew.watchbridge.WatchPaths
import dev.fonebrew.watchbridge.WatchTaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

/** How long the watch waits before it stops *guessing the phone is slow* and says so (see [dev.fonebrew.watch.core.AskReducer]). */
data class WatchTimeouts(
    /** Request sent, no chunk yet. Generous: a phone may be loading a model. A late reply still revives the ask. */
    val firstChunkMs: Long = 45_000L,
    /** Chunks were flowing and stopped. */
    val idleMs: Long = 20_000L,
    /** A task toggle that the phone never confirmed. */
    val toggleMs: Long = 15_000L,
)

/** Hooks fired when a fact the Tile or complication shows changes. No-ops in tests; real refresh requests on the watch. */
interface WatchEffects {
    fun tasksChanged() {}
    fun lastReplyChanged() {}
}

sealed interface AskSubmission {
    data class Accepted(val requestId: String) : AskSubmission
    data class Rejected(val reason: String) : AskSubmission
}

/**
 * The watch's engine room: owns the [WatchState], turns user intents into requests, turns what the
 * phone sends into events, runs the watchdogs, and persists the last-known facts. **No Android
 * types** — the Data Layer arrives through [PhoneLink] and the callbacks the listener service forwards
 * ([onPhoneMessage], [onReplyChannel]) — so the whole flow is JVM-tested with a fake link and virtual
 * time (`WatchControllerTest`).
 *
 * What it will never do: edit a task row because the user tapped (the phone is the single writer),
 * declare a reply finished without a terminal chunk, send anything anywhere but through [PhoneLink],
 * or hold a credential of any kind (there is no field for one in the contract).
 */
class WatchController(
    private val link: PhoneLink,
    private val cache: SnapshotCache,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val newRequestId: () -> String,
    private val effects: WatchEffects = object : WatchEffects {},
    private val timeouts: WatchTimeouts = WatchTimeouts(),
) {
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    private val lock = Any()
    private var askWatchdog: Job? = null

    /** Loads the last-known facts (tasks, loops, last reply) so a cold start opens on them, with their real age. */
    fun hydrate() {
        dispatch(cache.hydration())
    }

    // ---- user intents ------------------------------------------------------------------------

    fun ask(rawPrompt: CharSequence?): AskSubmission {
        val prompt = when (val checked = PromptPolicy.check(rawPrompt)) {
            is PromptPolicy.Result.Rejected -> return AskSubmission.Rejected(checked.reason)
            is PromptPolicy.Result.Ok -> checked.prompt
        }
        val current = _state.value
        if (current.ask.inFlight) return AskSubmission.Rejected("Still waiting for the last answer")
        (current.connection as? Connection.Incompatible)?.let { return AskSubmission.Rejected(it.mismatch.reason) }

        val requestId = newRequestId()
        dispatch(AskEvent.Submitted(requestId, prompt, clock()))
        scope.launch { sendAsk(requestId, prompt) }
        return AskSubmission.Accepted(requestId)
    }

    /** Walk away from the current ask (or clear a finished/failed one). Late chunks for it are ignored afterwards. */
    fun dismissAsk() {
        dispatch(AskEvent.Dismissed)
    }

    /**
     * Ask the phone to set the task's done-ness to the opposite of what the watch last *confirmed*.
     * Nothing on the watch changes until the phone answers.
     */
    fun toggleTask(taskId: String) {
        val tasks = _state.value.tasks
        val task = tasks.snapshot?.tasks?.firstOrNull { it.id == taskId } ?: return
        if (tasks.pending.containsKey(taskId)) return
        val wantDone = task.state != WatchTaskState.DONE
        val sentAt = clock()
        dispatch(TaskEvent.ToggleRequested(taskId, wantDone, sentAt))
        scope.launch { sendToggle(taskId, wantDone) }
        scope.launch {
            delay(timeouts.toggleMs)
            dispatch(TaskEvent.ToggleTimedOut(taskId, sentAt))
        }
    }

    /** Re-check the link and pull hello + tasks + loop runs from the phone. Cheap; call on resume and on "Check again". */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    // ---- inbound from the listener service ---------------------------------------------------

    fun onPhoneMessage(sourceNodeId: String, path: String, payload: ByteArray) {
        val atMs = clock()
        MessageRouter.route(path, WatchBridgeCodec.decode(payload), sourceNodeId, atMs).forEach(::dispatch)
    }

    /**
     * Reads one reply channel to its end (blocking — call from the listener's worker thread). Frames
     * become events as they arrive; when the stream ends, the reducer decides whether that finished
     * the reply (it did only if a terminal chunk came first) or lost the link.
     */
    fun onReplyChannel(sourceNodeId: String, input: InputStream) {
        val session = ReplySession(sourceNodeId)
        val end = ReplyStreamReader(input).readAll(session::onFrame)
        session.onEnd(end)
    }

    /** The channel could not even be opened: the ask it was answering (if any) lost its link. */
    fun onReplyChannelFailed(sourceNodeId: String) {
        ReplySession(sourceNodeId).onEnd(StreamEnd.IO_FAILURE)
    }

    /**
     * One reply stream's bookkeeping: which request it is answering (the last id the stream itself
     * named, else the ask in flight when it opened). Split out of [onReplyChannel] so tests can drive
     * a *live* stream — a frame, then silence — which a blocking reader cannot model.
     */
    internal inner class ReplySession(private val sourceNodeId: String) {
        private var requestId: String? = when (val ask = _state.value.ask) {
            is AskState.Sent -> ask.requestId
            is AskState.Streaming -> ask.requestId
            else -> null
        }

        fun onFrame(result: DecodeResult) {
            (result as? DecodeResult.Decoded)?.message?.let { message ->
                when (message) {
                    is AskChunk -> requestId = message.requestId
                    is AskError -> requestId = message.requestId
                    else -> Unit
                }
            }
            MessageRouter.routeReplyFrame(result, sourceNodeId, clock(), requestId).forEach(::dispatch)
        }

        fun onEnd(end: StreamEnd) {
            // A stream that never named a request and found nothing in flight has nothing to fail.
            val id = requestId ?: return
            dispatch(if (end == StreamEnd.BROKEN) AskEvent.StreamDamaged(id) else AskEvent.StreamEnded(id))
        }
    }

    internal fun openReplySession(sourceNodeId: String): ReplySession = ReplySession(sourceNodeId)

    // ---- sending -----------------------------------------------------------------------------

    private suspend fun sendAsk(requestId: String, prompt: String) {
        when (val resolved = resolveNode()) {
            is Resolved.Failed -> dispatch(AskEvent.SendFailed(requestId, resolved.failure)) // the node query already told the link state
            is Resolved.Found -> try {
                link.send(resolved.node.id, WatchPaths.ASK, WatchBridgeCodec.encodeToBytes(AskRequest(requestId, prompt)))
                dispatch(LinkEvent.SendSucceeded(resolved.node.id))
            } catch (e: LinkException) {
                dispatch(AskEvent.SendFailed(requestId, e.failure))
                dispatch(LinkEvent.CallFailed(e.failure))
            }
        }
    }

    private suspend fun sendToggle(taskId: String, wantDone: Boolean) {
        when (val resolved = resolveNode()) {
            is Resolved.Failed -> dispatch(TaskEvent.ToggleSendFailed(taskId, resolved.failure))
            is Resolved.Found -> try {
                link.send(resolved.node.id, WatchPaths.TASK_TOGGLE, WatchBridgeCodec.encodeToBytes(TaskToggleRequest(taskId, wantDone)))
                dispatch(LinkEvent.SendSucceeded(resolved.node.id))
            } catch (e: LinkException) {
                dispatch(TaskEvent.ToggleSendFailed(taskId, e.failure))
                dispatch(LinkEvent.CallFailed(e.failure))
            }
        }
    }

    private suspend fun refreshNow() {
        val resolved = resolveNode(forceQuery = true)
        if (resolved !is Resolved.Found) return
        try {
            link.send(resolved.node.id, WatchPaths.SYNC, ByteArray(0))
            dispatch(LinkEvent.SendSucceeded(resolved.node.id))
        } catch (e: LinkException) {
            dispatch(LinkEvent.CallFailed(e.failure))
        }
    }

    private sealed interface Resolved {
        data class Found(val node: PhoneNode) : Resolved
        data class Failed(val failure: LinkFailure) : Resolved
    }

    /**
     * The phone to talk to: the node we already believe is reachable, else a fresh query. A failed
     * query is reported to the connection state machine here, so callers only decide what *their*
     * request does about it.
     */
    private suspend fun resolveNode(forceQuery: Boolean = false): Resolved {
        val connection = _state.value.connection
        if (!forceQuery && connection is Connection.Reachable) return Resolved.Found(connection.node)
        return try {
            val nodes = link.connectedNodes()
            dispatch(LinkEvent.NodesObserved(nodes))
            PhoneNodePicker.pick(nodes)?.let { Resolved.Found(it) } ?: Resolved.Failed(LinkFailure.NO_NODE)
        } catch (e: LinkException) {
            dispatch(LinkEvent.CallFailed(e.failure))
            Resolved.Failed(e.failure)
        }
    }

    // ---- the one place state changes ---------------------------------------------------------

    private fun dispatch(event: WatchEvent) {
        var before: WatchState? = null
        var after: WatchState? = null
        _state.update { current ->
            before = current
            WatchReducer.reduce(current, event).also { after = it }
        }
        afterReduce(checkNotNull(before), checkNotNull(after))
    }

    private fun afterReduce(before: WatchState, after: WatchState) {
        if (after.tasks.snapshot !== before.tasks.snapshot) {
            after.tasks.snapshot?.let { snapshot -> after.tasks.receivedAtMs?.let { cache.saveTasks(snapshot, it) } }
            effects.tasksChanged()
        }
        if (after.loops !== before.loops) cache.saveLoops(after.loops.runs)
        if (after.lastReply !== before.lastReply) {
            after.lastReply?.let(cache::saveLastReply)
            effects.lastReplyChanged()
        }
        if (after.ask !== before.ask) armAskWatchdog(after.ask)
        if (after.tasks.needsResync && !before.tasks.needsResync) {
            dispatch(TaskEvent.ResyncHandled)
            refresh()
        }
    }

    private fun armAskWatchdog(ask: AskState) {
        synchronized(lock) {
            askWatchdog?.cancel()
            askWatchdog = null
            val (requestId, marker, waitMs) = when (ask) {
                is AskState.Sent -> Triple(ask.requestId, ask.sentAtMs, timeouts.firstChunkMs)
                is AskState.Streaming -> Triple(ask.requestId, ask.lastChunkAtMs, timeouts.idleMs)
                else -> return
            }
            askWatchdog = scope.launch {
                delay(waitMs)
                dispatch(AskEvent.TimedOut(requestId, marker))
            }
        }
    }
}
