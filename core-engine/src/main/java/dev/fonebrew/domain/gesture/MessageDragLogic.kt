package dev.fonebrew.domain.gesture

import kotlin.math.abs

/**
 * Pure event/intent core for the message-bubble drag gesture (STUDIO_UX_SPEC.md §4.2, Owner
 * decision 1 and the "Gesture arbitration" section of `docs/THREAD_TOPOLOGY_PLAN.md`, WP4).
 * Exhaustively JVM-tested here; the Compose-side `Modifier.messageGestures`
 * (`dev.fonebrew.ui.MessageGestures.kt`) is a thin, owner-verified `awaitEachGesture` wrapper that
 * feeds real touch samples into [resolve] and dispatches whatever [Intent] comes back (haptics,
 * ViewModel calls, ribbon state) — no gesture arbitration logic lives there, only real-device
 * timing/consumption plumbing that cannot be exercised without a device (this repo's environment
 * has none; see CLAUDE.md "Environment honesty").
 *
 * **Patent design-around (binding constraint 1, US 9,729,695):** the vertical channel selects a
 * judgment VALUE (a [Intent.CommitVerdict] grade), never a destination or collection, and the
 * message itself never moves — see [dev.fonebrew.domain.curation.Verdict]'s own KDoc for the same
 * rule stated from the data-model side.
 *
 * **Design choice — a pure classifier over the whole trace-so-far, not an incremental reducer.**
 * [resolve] is a stateless function of the entire gesture trace (every sample from the initial
 * [Phase.DOWN] to whatever the latest sample is); the caller re-invokes it after every new pointer
 * sample. This makes the "9 event names" from the plan ([Intent.Yield]/[Intent.Arm]/
 * [Intent.VerdictDetent]/[Intent.CommitVerdict]/[Intent.Reply]/[Intent.Quote]/[Intent.OpenRadial]/
 * [Intent.LongPress]/[Intent.DoubleTap]) trivial to test exhaustively (build a trace, assert the
 * one [Intent] it resolves to) without needing to model a hidden internal phase enum and its
 * transition table separately from the tests. The trade-off, which the caller must handle: several
 * of the "live" intents ([Intent.Arm], [Intent.LongPress] while still pressed) will be returned
 * again on every subsequent call until the trace's phase changes — the caller de-dupes one-shot
 * side effects (haptic ticks, opening the radial menu) itself, exactly once per gesture; see that
 * file's KDoc for why this is safe. [Intent.Yield] is deliberately overloaded: it means either
 * "nothing to report yet, keep waiting" (while the finger is still down) or "this gesture
 * resolved to no action" (once the finger is up) — the caller only ever needs to *act* on it in
 * the latter case (a plain tap, or a directional drag that snapped back without reaching a
 * commit threshold), and it is always safe to keep waiting in the former.
 */
object MessageDragLogic {

    // ---- Timings/thresholds (STUDIO_UX_SPEC.md §4.2 + the plan's Gesture arbitration table) ---

    /** Hold-still-to-arm delay; fires the arm-tick haptic (§4.2: "hold a message 150ms"). */
    const val ARM_MS = 150L

    /** Stationary-after-arm delay that opens the [Intent.LongPress] turn-actions sheet instead
     *  (the plan's "500 ms stationary = TurnActionsSheet"). */
    const val LONG_PRESS_MS = 500L

    /** How long a "pull right" drag must be held once decided before it fans into
     *  [Intent.OpenRadial] instead of committing [Intent.Quote] on release (Owner decision 1:
     *  "pull right + hold = fan Branch/Fork/Spawn"). */
    const val RADIAL_HOLD_MS = 400L

    /** Two taps land inside this window (of each other's up-time) to become [Intent.DoubleTap]. */
    const val DOUBLE_TAP_WINDOW_MS = 300L

    /** First verdict detent, dp (§4.2: "±24/±56 dp detents"). */
    const val DETENT_1_DP = 24f

    /** Second verdict detent, dp. */
    const val DETENT_2_DP = 56f

    /** Movement below this (dp) before arming is "held still"; at/after arming it decides which
     *  axis (vertical verdict vs horizontal reply/quote) a drag committed to. */
    const val TOUCH_SLOP_DP = 8f

    // ---- Trace shape ----------------------------------------------------------------------

    enum class Phase { DOWN, MOVE, UP }

    /**
     * One sample of the finger's position, relative to where it went down, in dp — density
     * conversion is the wrapper's job, kept out of this pure module for JVM-testability.
     * [atMs] is the pointer system's own uptime clock (matches
     * `androidx.compose.ui.input.pointer.PointerInputChange.uptimeMillis` — never wall-clock,
     * which can jump).
     */
    data class Sample(val phase: Phase, val dxDp: Float, val dyDp: Float, val atMs: Long)

    // ---- Outcomes ---------------------------------------------------------------------------

    sealed interface Intent {
        /** Not our gesture (yet, or ever) — see the class KDoc's note on its double duty. */
        data object Yield : Intent

        /** 150ms hold-still reached; the wrapper fires the arm-tick haptic exactly once. */
        data object Arm : Intent

        /** Live verdict preview while a vertical drag is in progress; `grade` is null below the
         *  first detent (ribbon stretching, no judgment committed yet). */
        data class VerdictDetent(val grade: Int?) : Intent

        /** Release inside a detent: commits the verdict (annotation only — see the class KDoc's
         *  patent note; the message never moves). */
        data class CommitVerdict(val grade: Int) : Intent

        /** Pull-left release: quote the message into the composer, framed as a reply. */
        data object Reply : Intent

        /** Pull-right release (before the radial-hold threshold): quote the message into the
         *  composer (Owner decision 1). */
        data object Quote : Intent

        /** Pull-right held ≥400ms: fan Branch/Fork/Spawn ([dev.aarso.hyle.cells.HyleRadialMenu]).
         *  `anchorDxDp`/`anchorDyDp` are the current offset from the down point, in case the
         *  caller wants to anchor the fan at the drag position rather than the original touch. */
        data class OpenRadial(val anchorDxDp: Float, val anchorDyDp: Float) : Intent

        /** 500ms stationary (armed, never left the touch-slop box): opens TurnActionsSheet. */
        data object LongPress : Intent

        /** Two quick taps, neither one ever arming: toggles the message bookmark. */
        data object DoubleTap : Intent
    }

    /**
     * Resolves one finger-down gesture trace to its current (if the finger is still down) or
     * final (if the trace ends in [Phase.UP]) [Intent].
     *
     * @param trace must start with exactly one [Phase.DOWN] sample at index 0; every later sample
     *   is [Phase.MOVE] until at most one trailing [Phase.UP] sample. Safe to call repeatedly with
     *   a growing trace as new pointer samples arrive.
     * @param previousTapUpAtMs the `atMs` the previous gesture on this same message ended with a
     *   plain tap (i.e. the [Intent.Yield] returned when a [Phase.UP] landed before arming), or
     *   null if there wasn't one / it's stale. The caller is responsible for tracking this across
     *   gesture instances (it is naturally out of scope for a single trace) and clearing it once
     *   consumed by a resolved [Intent.DoubleTap].
     */
    fun resolve(trace: List<Sample>, previousTapUpAtMs: Long? = null): Intent {
        require(trace.isNotEmpty()) { "MessageDragLogic.resolve requires a non-empty trace" }
        val down = trace.first()
        require(down.phase == Phase.DOWN) { "MessageDragLogic.resolve requires trace[0] to be Phase.DOWN" }
        val last = trace.last()
        val armedAtMs = down.atMs + ARM_MS

        // A slop-breaking move before arm time is a scroll, not our gesture — permanently, for
        // the rest of this trace (this check only ever looks at samples strictly before armedAtMs,
        // a fixed historical set, so it can't "un-happen" later in the same gesture).
        val movedBeforeArm = trace.drop(1)
            .filter { it.atMs < armedAtMs }
            .any { maxOf(abs(it.dxDp), abs(it.dyDp)) > TOUCH_SLOP_DP }
        if (movedBeforeArm) return Intent.Yield

        if (last.atMs < armedAtMs) {
            if (last.phase == Phase.UP) {
                val isDoubleTap = previousTapUpAtMs != null &&
                    (down.atMs - previousTapUpAtMs) in 0..DOUBLE_TAP_WINDOW_MS
                return if (isDoubleTap) Intent.DoubleTap else Intent.Yield
            }
            return Intent.Yield // still waiting to arm; nothing to report yet
        }

        // Armed (150ms elapsed with no pre-arm slop break). Decide the axis from the current
        // offset — recomputed fresh on every call, so a drag can still change its mind about
        // direction up until release (deliberate; see the class KDoc).
        val dx = last.dxDp
        val dy = last.dyDp
        val elapsed = last.atMs - down.atMs
        val axisVertical = abs(dy) > abs(dx) && abs(dy) > TOUCH_SLOP_DP
        val axisHorizontal = !axisVertical && abs(dx) > TOUCH_SLOP_DP

        if (!axisVertical && !axisHorizontal) {
            // Armed and still inside the slop box.
            if (elapsed >= LONG_PRESS_MS) return Intent.LongPress
            return if (last.phase == Phase.UP) Intent.Yield else Intent.Arm
        }

        if (axisVertical) {
            val grade = gradeFor(dy)
            return if (last.phase == Phase.UP) {
                if (grade != null) Intent.CommitVerdict(grade) else Intent.Yield
            } else {
                Intent.VerdictDetent(grade)
            }
        }

        // Horizontal.
        return if (dx < 0) {
            if (last.phase == Phase.UP) Intent.Reply else Intent.Yield
        } else {
            val decidedAtMs = trace
                .filter { it.atMs >= armedAtMs && it.dxDp > TOUCH_SLOP_DP && abs(it.dxDp) >= abs(it.dyDp) }
                .minOfOrNull { it.atMs } ?: last.atMs
            val heldMs = last.atMs - decidedAtMs
            when {
                heldMs >= RADIAL_HOLD_MS -> Intent.OpenRadial(dx, dy)
                last.phase == Phase.UP -> Intent.Quote
                else -> Intent.Yield // still pulling right, not yet at either threshold
            }
        }
    }

    /** §4.2's four detents: -2 (wrong/harmful) / -1 (off) / +1 (useful) / +2 (reference-grade).
     *  "Pull up for good, down for bad" — up means the finger moved toward smaller y, i.e. [dyDp]
     *  negative. Below the first detent in either direction, no grade is committed (null). */
    private fun gradeFor(dyDp: Float): Int? = when {
        dyDp <= -DETENT_2_DP -> 2
        dyDp <= -DETENT_1_DP -> 1
        dyDp >= DETENT_2_DP -> -2
        dyDp >= DETENT_1_DP -> -1
        else -> null
    }
}
