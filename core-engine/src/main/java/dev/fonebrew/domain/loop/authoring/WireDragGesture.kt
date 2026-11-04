package dev.fonebrew.domain.loop.authoring

import kotlin.math.hypot

/**
 * Drag-a-wire's pure decision core (`LOOP_PHONE_AUTHORING_SPEC.md` §7). The touch mechanics
 * (port hit-testing against live node positions, tracking the pointer for a preview line) live
 * in `ui/loops/LoopRoom.kt`'s `LoopCanvas`, but whether a drag-release actually produces a
 * connection routes through [TouchConnectionGrammar] -- the SAME grammar the tap flow is
 * specified against -- not a second, parallel state machine. `FB-RAT-PHN-003` rejects drag as
 * the *primary or only* path to a connection (callers must keep tap working, gesture parity),
 * but says nothing against the two gestures sharing this one grammar; this object gives
 * [TouchConnectionGrammar] its first caller.
 */
object WireDragGesture {

    /**
     * [begin] collapses [TouchConnectionGrammar.Event.SelectSource] and
     * [TouchConnectionGrammar.Event.ConnectFromHere] into one call: a drag gesture has no
     * separate "confirm the source" tap the way the phone's tap-to-connect flow does --
     * crossing the drag threshold (LoopCanvas already having decided the touch-down was over a
     * node's port, [isPortStart]) *is* that confirmation. Always legal from a fresh draft, so
     * this never returns [TouchConnectionGrammar.Result.Rejected] -- a `check()` documents that
     * rather than pushing a Result the caller would have to branch on for a case that can't
     * happen.
     */
    fun begin(sourceNodeId: String): TouchConnectionGrammar.ConnectionDraft {
        val selected = TouchConnectionGrammar.apply(TouchConnectionGrammar.start(), TouchConnectionGrammar.Event.SelectSource(sourceNodeId))
        check(selected is TouchConnectionGrammar.Result.Advanced) { "SelectSource is legal from a fresh IDLE draft; got $selected" }
        val awaiting = TouchConnectionGrammar.apply(selected.draft, TouchConnectionGrammar.Event.ConnectFromHere)
        check(awaiting is TouchConnectionGrammar.Result.Advanced) { "ConnectFromHere is legal right after SelectSource; got $awaiting" }
        return awaiting.draft
    }

    sealed interface Outcome {
        data class Connect(val from: String, val to: String) : Outcome
        object Cancelled : Outcome
    }

    /**
     * Release. [targetNodeId] `null` (dropped over empty canvas) or equal to [draft]'s own
     * source (dropped back on itself -- same no-op as the tap flow's `from == id` guard in
     * `LoopRoom.onTapNode`) cancels via the grammar's own [TouchConnectionGrammar.Event.Cancel]
     * -- a pure discard, nothing committed, mirroring "release on empty cancels". Anything else
     * drives [TouchConnectionGrammar.Event.ChooseDestination]; this only reports whether to
     * connect -- the caller applies it through its own existing edge-creation mutation (no
     * parallel one here).
     */
    fun release(draft: TouchConnectionGrammar.ConnectionDraft, targetNodeId: String?): Outcome {
        val source = draft.sourceNodeId
        if (source == null || targetNodeId == null || targetNodeId == source) {
            TouchConnectionGrammar.apply(draft, TouchConnectionGrammar.Event.Cancel)
            return Outcome.Cancelled
        }
        val chosen = TouchConnectionGrammar.apply(draft, TouchConnectionGrammar.Event.ChooseDestination(targetNodeId))
        return if (chosen is TouchConnectionGrammar.Result.Advanced) Outcome.Connect(source, targetNodeId) else Outcome.Cancelled
    }

    /**
     * A candidate drop target for [hitTest]: [id]'s on-canvas center and the pixel radius that
     * counts as "released on it". The radius is per-candidate rather than owned by this object
     * because node shape varies (event circles vs. task/gateway boxes) and only the caller
     * (LoopCanvas) knows which.
     */
    data class NodeHitTarget(val id: String, val centerX: Float, val centerY: Float, val radiusPx: Float)

    /**
     * The nearest [NodeHitTarget] whose radius actually contains ([pointerX], [pointerY]), or
     * `null` if the release point isn't over any node -- LoopCanvas feeds that `null` straight
     * into [release] as "dropped on empty canvas cancels".
     */
    fun hitTest(pointerX: Float, pointerY: Float, candidates: List<NodeHitTarget>): String? =
        candidates
            .filter { hypot((pointerX - it.centerX).toDouble(), (pointerY - it.centerY).toDouble()) <= it.radiusPx }
            .minByOrNull { hypot((pointerX - it.centerX).toDouble(), (pointerY - it.centerY).toDouble()) }
            ?.id

    /**
     * Is a drag's start point within [hitRadiusPx] of the node's port anchor -- its right-edge
     * center, in the node Box's own local coordinates (origin top-left, [widthPx] x [heightPx])?
     * A start elsewhere in the body is not a wire-drag; LoopCanvas falls back to its existing
     * move-node drag there. That fallback -- not this function -- is what keeps the node body
     * draggable-to-move exactly as before; the port is additive on top of it, never a
     * replacement (gesture parity, same rule that keeps tap-to-connect working).
     */
    fun isPortStart(localX: Float, localY: Float, widthPx: Float, heightPx: Float, hitRadiusPx: Float): Boolean =
        hypot((localX - widthPx).toDouble(), (localY - heightPx / 2f).toDouble()) <= hitRadiusPx
}
