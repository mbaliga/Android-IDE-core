package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.AskChunk
import dev.fonebrew.watchbridge.AskError
import dev.fonebrew.watchbridge.Usage

/** A finished reply, as the phone declared it. Everything here came off the wire; nothing is inferred. */
data class ReplyRecord(
    val requestId: String,
    val text: String,
    val engineId: String,
    val watchedObject: Boolean,
    val usage: Usage,
    val completedAtMs: Long,
)

/** What had arrived when a reply broke: shown *labelled as incomplete*, never as an answer. */
data class PartialReply(val text: String, val engineId: String, val watchedObject: Boolean)

/** Why an ask did not end in a finished reply. Each is a distinct, worded state (see [AskFailurePresenter]). */
sealed interface AskFailure {
    /** The phone answered with an `AskError` — its own words are shown. */
    data class PhoneRefused(val reason: String) : AskFailure

    /** Nothing at all came back before the first-chunk timeout. The watch's guess; a late reply revives the ask. */
    data object NoAnswer : AskFailure

    /** Chunks were arriving and then stopped. The watch's guess; a late chunk revives the ask. */
    data object Stalled : AskFailure

    /** The reply stream ended with no terminal chunk and no error: a lost link, never a success. */
    data object LinkLost : AskFailure

    /** A frame of the reply did not decode (or spoke another contract version), so the text cannot be trusted as complete. */
    data object DamagedStream : AskFailure

    /** The request never left the watch. */
    data class SendFailed(val failure: LinkFailure) : AskFailure
}

/**
 * The Ask surface's state. One request in flight at a time; every non-success ending is its own
 * [Failed] variant so nothing degrades into a generic spinner or a blank.
 */
sealed interface AskState {
    data object Idle : AskState

    /** The request is on its way (or sent) and no chunk has arrived. */
    data class Sent(val requestId: String, val prompt: String, val sentAtMs: Long) : AskState

    data class Streaming(
        val requestId: String,
        val prompt: String,
        val text: String,
        val engineId: String,
        val watchedObject: Boolean,
        val sentAtMs: Long,
        val lastChunkAtMs: Long,
    ) : AskState

    data class Done(val requestId: String, val prompt: String, val reply: ReplyRecord) : AskState

    data class Failed(
        val requestId: String,
        val prompt: String,
        val failure: AskFailure,
        val partial: PartialReply?,
        val sentAtMs: Long,
    ) : AskState
}

val AskState.inFlight: Boolean get() = this is AskState.Sent || this is AskState.Streaming

sealed interface AskEvent : WatchEvent {
    data class Submitted(val requestId: String, val prompt: String, val atMs: Long) : AskEvent
    data class SendFailed(val requestId: String, val failure: LinkFailure) : AskEvent
    data class ChunkReceived(val chunk: AskChunk, val atMs: Long) : AskEvent
    data class PhoneRefused(val error: AskError) : AskEvent

    /** The reply stream closed. Only meaningful if no terminal chunk arrived first. */
    data class StreamEnded(val requestId: String) : AskEvent

    /** The reply stream's framing broke, or a frame inside it did not decode. */
    data class StreamDamaged(val requestId: String) : AskEvent

    /**
     * A watchdog fired. [marker] is the timestamp the watchdog was armed against (`sentAtMs` or
     * `lastChunkAtMs`): a stale watchdog — one armed before a newer chunk landed — has a different
     * marker and is ignored, so the reducer stays pure and the timer needs no cancellation protocol.
     */
    data class TimedOut(val requestId: String, val marker: Long) : AskEvent

    /** The user walked away from this ask; late chunks for it are ignored from now on. */
    data object Dismissed : AskEvent
}

/**
 * Chunk accumulation and the failure ladder for one relayed turn. Pure: the same events in the same
 * order always give the same state, so the "never a finished answer unless the phone said so" rules
 * are JVM-tested rather than hoped for.
 *
 * Rules worth knowing:
 *  - **A reply is finished only by a terminal chunk** (which carries `usage`). A stream that ends,
 *    stalls or breaks first is a [AskState.Failed], with whatever text arrived kept as *partial*.
 *  - **The watched-object flag only ever ratchets up.** If a reply's chunks disagree about
 *    `watchedObject`, the reply is treated as watched: the wrist must never understate that cloud
 *    was involved. The engine id shown is the latest chunk's.
 *  - **Timeouts are guesses, not facts**, so a chunk that arrives *after* a [AskFailure.NoAnswer] /
 *    [AskFailure.Stalled] for the same request revives it (the phone may just have been slow, e.g.
 *    loading a model). Facts — a phone refusal, a lost link, a damaged stream, a send failure — are
 *    final.
 *  - **A phone refusal outranks the watch's guesses**: an `AskError` arriving after a timeout or a
 *    dropped stream replaces it with the phone's own words. A completed reply is never overturned by
 *    a late error or a late chunk.
 *  - Chunks, errors and timers for any other request id (a dismissed ask, a stale retry) are ignored.
 */
object AskReducer {
    fun reduce(state: AskState, event: AskEvent): AskState = when (event) {
        is AskEvent.Submitted ->
            if (state.inFlight) state else AskState.Sent(event.requestId, event.prompt, event.atMs)

        is AskEvent.SendFailed ->
            if (state is AskState.Sent && state.requestId == event.requestId) {
                AskState.Failed(state.requestId, state.prompt, AskFailure.SendFailed(event.failure), null, state.sentAtMs)
            } else {
                state
            }

        is AskEvent.ChunkReceived -> onChunk(state, event.chunk, event.atMs)
        is AskEvent.PhoneRefused -> onRefused(state, event.error)

        is AskEvent.StreamEnded -> failInFlight(state, event.requestId, AskFailure.LinkLost)
        is AskEvent.StreamDamaged -> failInFlight(state, event.requestId, AskFailure.DamagedStream)

        is AskEvent.TimedOut -> when {
            state is AskState.Sent && state.requestId == event.requestId && state.sentAtMs == event.marker ->
                AskState.Failed(state.requestId, state.prompt, AskFailure.NoAnswer, null, state.sentAtMs)
            state is AskState.Streaming && state.requestId == event.requestId && state.lastChunkAtMs == event.marker ->
                AskState.Failed(state.requestId, state.prompt, AskFailure.Stalled, state.partial(), state.sentAtMs)
            else -> state
        }

        AskEvent.Dismissed -> AskState.Idle
    }

    private fun onChunk(state: AskState, chunk: AskChunk, atMs: Long): AskState = when (state) {
        is AskState.Sent ->
            if (state.requestId != chunk.requestId) state
            else advance(state.requestId, state.prompt, "", false, state.sentAtMs, chunk, atMs)

        is AskState.Streaming ->
            if (state.requestId != chunk.requestId) state
            else advance(state.requestId, state.prompt, state.text, state.watchedObject, state.sentAtMs, chunk, atMs)

        is AskState.Failed ->
            // Revive only from the watch's own guesses; facts stay final.
            if (state.requestId == chunk.requestId && (state.failure == AskFailure.NoAnswer || state.failure == AskFailure.Stalled)) {
                advance(state.requestId, state.prompt, state.partial?.text.orEmpty(), state.partial?.watchedObject ?: false, state.sentAtMs, chunk, atMs)
            } else {
                state
            }

        AskState.Idle, is AskState.Done -> state
    }

    private fun advance(
        requestId: String,
        prompt: String,
        priorText: String,
        priorWatched: Boolean,
        sentAtMs: Long,
        chunk: AskChunk,
        atMs: Long,
    ): AskState {
        val text = priorText + chunk.text
        val watched = priorWatched || chunk.watchedObject
        return if (chunk.terminal) {
            val usage = checkNotNull(chunk.usage) { "a terminal AskChunk always carries usage (bridge invariant)" }
            AskState.Done(requestId, prompt, ReplyRecord(requestId, text, chunk.engineId, watched, usage, atMs))
        } else {
            AskState.Streaming(requestId, prompt, text, chunk.engineId, watched, sentAtMs, atMs)
        }
    }

    private fun onRefused(state: AskState, error: AskError): AskState {
        val failure = AskFailure.PhoneRefused(error.reason)
        return when {
            state is AskState.Sent && state.requestId == error.requestId ->
                AskState.Failed(state.requestId, state.prompt, failure, null, state.sentAtMs)
            state is AskState.Streaming && state.requestId == error.requestId ->
                AskState.Failed(state.requestId, state.prompt, failure, state.partial(), state.sentAtMs)
            // The phone's own words replace the watch's guess or the dropped-stream report.
            state is AskState.Failed && state.requestId == error.requestId && state.failure !is AskFailure.PhoneRefused && state.failure !is AskFailure.SendFailed ->
                state.copy(failure = failure)
            else -> state
        }
    }

    private fun failInFlight(state: AskState, requestId: String, failure: AskFailure): AskState = when {
        state is AskState.Sent && state.requestId == requestId ->
            AskState.Failed(state.requestId, state.prompt, failure, null, state.sentAtMs)
        state is AskState.Streaming && state.requestId == requestId ->
            AskState.Failed(state.requestId, state.prompt, failure, state.partial(), state.sentAtMs)
        else -> state
    }

    private fun AskState.Streaming.partial() = PartialReply(text, engineId, watchedObject)
}

/** A failure as words + a shape. */
data class FailureView(val glyph: Glyph, val headline: String, val detail: String)

object AskFailurePresenter {
    fun present(failure: AskFailure): FailureView = when (failure) {
        is AskFailure.PhoneRefused -> FailureView(Glyph.NOTICE, "Phone couldn't answer", failure.reason)
        AskFailure.NoAnswer -> FailureView(
            Glyph.LINK_BROKEN, "No answer yet", "Fonebrew on the phone hasn't replied. Open it there and try again. A late reply will still appear here.",
        )
        AskFailure.Stalled -> FailureView(Glyph.LINK_BROKEN, "Reply stalled", "The phone stopped sending partway. What arrived is shown below, not as a finished answer.")
        AskFailure.LinkLost -> FailureView(Glyph.LINK_BROKEN, "Phone link lost", "The connection dropped mid-reply. This is not a finished answer.")
        AskFailure.DamagedStream -> FailureView(
            Glyph.NOTICE, "Reply couldn't be read", "Part of the reply didn't arrive intact, or isn't in a format this watch understands, so it isn't shown as complete.",
        )
        is AskFailure.SendFailed -> when (failure.failure) {
            LinkFailure.NO_NODE -> FailureView(Glyph.LINK_BROKEN, "Phone not reachable", "Nothing was sent. Check Bluetooth and that your phone is nearby.")
            LinkFailure.API_UNAVAILABLE -> FailureView(Glyph.LINK_BROKEN, "Phone link unavailable", "Nothing was sent. Google Play services isn't available for the watch-phone link.")
            LinkFailure.OTHER -> FailureView(Glyph.LINK_BROKEN, "Couldn't send", "The message to the phone failed. Try again.")
        }
    }
}
