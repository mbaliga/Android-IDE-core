package dev.fonebrew.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.fonebrew.domain.curation.CompactionContract
import dev.fonebrew.domain.curation.Fates
import dev.fonebrew.domain.curation.Verdict
import dev.fonebrew.domain.gesture.MessageDragLogic
import dev.aarso.hyle.cells.HyleHaptics
import dev.fonebrew.ui.theme.LocalHyleColors
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * `docs/THREAD_TOPOLOGY_PLAN.md` WP4 — the thin, owner-verified `awaitEachGesture` wrapper around
 * the pure, exhaustively-JVM-tested [MessageDragLogic]. Replaces the plain `combinedClickable`
 * that used to sit on [dev.fonebrew.ui.ChatScreen]'s `MessageBubble` Card (long-press + double-tap
 * only) with one detector that also arbitrates the vertical verdict-drag, the horizontal
 * reply/quote drags, and the "pull right + hold" radial fan (Owner decision 1).
 *
 * **Never contends with [dev.fonebrew.ui.spatial.SpatialRoot]'s own detectors** (verified against
 * that file, unmodified by this WP): `spatialEdgeDrag` only claims gestures that both originate
 * within its edge band AND resolve to be its axis on the very first slop-break, standing down
 * (never consuming) otherwise; `spatialPinch` only claims ≥2 simultaneous pointers. A single-finger
 * drag starting on a message bubble (almost never at the screen edge) is untouched by either, and
 * this modifier itself never calls [androidx.compose.ui.input.pointer.PointerInputChange.consume]
 * before the 150ms arm fires — exactly the plan's "LazyColumn scroll is beaten by the 150ms
 * hold-arm," never before it.
 *
 * **Why `withTimeoutOrNull` around `awaitPointerEvent()`:** [MessageDragLogic]'s arm/long-press/
 * radial-hold thresholds must fire even while the finger sits perfectly still (no new pointer
 * event is delivered when nothing moves). This mirrors Compose Foundation's own
 * `detectTapGestures` long-press implementation (which races `waitForUpOrCancellation()` against
 * `withTimeoutOrNull(longPressTimeoutMillis)`), not a novel technique.
 *
 * **One-shot side effects, de-duplicated here, not in [MessageDragLogic]:** because
 * [MessageDragLogic.resolve] is a pure re-classification of the trace-so-far, it keeps returning
 * [MessageDragLogic.Intent.Arm] (and, once stationary long enough, [MessageDragLogic.Intent.LongPress])
 * on every subsequent call until the phase changes — by design, see that object's KDoc. This
 * wrapper tracks `armed`/`terminalIntent` locally so the arm-tick haptic and the long-press/radial
 * callbacks each fire exactly once per physical gesture.
 */
private const val GESTURE_POLL_MS = 24L

class MessageGestureCallbacks(
    val onArmed: () -> Unit = {},
    val onVerdictPreview: (grade: Int?) -> Unit = {},
    val onCommitVerdict: (grade: Int) -> Unit = {},
    /** Live, best-effort direction hint while a horizontal drag is in progress (null once it's
     *  not horizontal, or the gesture ended) — purely visual (drives [HorizontalDragHint]); the
     *  actual left/right decision for [onReply]/[onQuote]/[onOpenRadial] is
     *  [dev.fonebrew.domain.gesture.MessageDragLogic]'s alone. */
    val onHorizontalPreview: (direction: HorizontalDragDirection?) -> Unit = {},
    val onReply: () -> Unit = {},
    val onQuote: () -> Unit = {},
    val onOpenRadial: (anchor: Offset) -> Unit = {},
    val onLongPress: () -> Unit = {},
    val onDoubleTap: () -> Unit = {},
    /** Fires exactly once, whenever the physical gesture ends, regardless of outcome (including
     *  a directional drag that snapped back without committing) — the caller's one place to reset
     *  any live-preview UI state ([onVerdictPreview]/[onHorizontalPreview] otherwise have no
     *  "it's over, clear yourself" signal of their own). */
    val onGestureEnded: () -> Unit = {},
)

/** Mirrors the three Settings → Gestures switches ([dev.fonebrew.data.SessionStore]). Every switch
 *  OFF collapses this modifier down to nothing (long-press/double-tap still work via the plain
 *  parity controls already on the bubble/TurnActionsSheet — gestures are additive sugar, never
 *  the only way in, per binding constraint 4). */
data class MessageGestureToggles(
    val verdictDrag: Boolean = true,
    val quoteReply: Boolean = true,
    val radialFan: Boolean = true,
)

/**
 * See this file's top KDoc. [enabled] is the per-role gate (verdict/radial gestures only make
 * sense on assistant turns today — bookmarking and long-press stay available on every role via
 * [toggles], independent of that).
 */
fun Modifier.messageGestures(
    enabled: Boolean,
    toggles: MessageGestureToggles,
    haptics: HyleHaptics,
    callbacks: MessageGestureCallbacks,
): Modifier = if (!enabled) this else pointerInput(toggles) {
    var lastTapUpAtMs: Long? = null
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        val pointerId = firstDown.id
        val downMs = firstDown.uptimeMillis
        val trace = mutableListOf(MessageDragLogic.Sample(MessageDragLogic.Phase.DOWN, 0f, 0f, downMs))
        var armed = false
        var terminalIntent: MessageDragLogic.Intent? = null
        var released = false

        while (!released) {
            val event = withTimeoutOrNull(GESTURE_POLL_MS) { awaitPointerEvent() }
            val change = event?.changes?.firstOrNull { it.id == pointerId }
            val nowMs = change?.uptimeMillis ?: (trace.last().atMs + GESTURE_POLL_MS)
            if (change != null) {
                val dxDp = with(density) { (change.position.x - firstDown.position.x).toDp().value }
                val dyDp = with(density) { (change.position.y - firstDown.position.y).toDp().value }
                trace += MessageDragLogic.Sample(
                    if (change.pressed) MessageDragLogic.Phase.MOVE else MessageDragLogic.Phase.UP,
                    dxDp,
                    dyDp,
                    nowMs,
                )
            } else {
                // A named threshold's poll interval elapsed with no fresh pointer event -- append
                // a same-position tick so resolve() sees the clock has advanced (arm/long-press/
                // radial-hold all fire on elapsed time alone, not on movement).
                trace += trace.last().copy(atMs = nowMs)
            }

            if (terminalIntent == null) {
                when (val intent = MessageDragLogic.resolve(trace, lastTapUpAtMs)) {
                    is MessageDragLogic.Intent.Arm -> if (!armed) {
                        armed = true
                        haptics.tap()
                        callbacks.onArmed()
                    }
                    is MessageDragLogic.Intent.VerdictDetent ->
                        if (toggles.verdictDrag) callbacks.onVerdictPreview(intent.grade)
                    is MessageDragLogic.Intent.CommitVerdict -> {
                        terminalIntent = intent
                        if (toggles.verdictDrag) {
                            haptics.settle()
                            callbacks.onCommitVerdict(intent.grade)
                        }
                    }
                    MessageDragLogic.Intent.Reply -> {
                        terminalIntent = intent
                        if (toggles.quoteReply) {
                            haptics.tap()
                            callbacks.onReply()
                        }
                    }
                    MessageDragLogic.Intent.Quote -> {
                        terminalIntent = intent
                        if (toggles.quoteReply) {
                            haptics.tap()
                            callbacks.onQuote()
                        }
                    }
                    is MessageDragLogic.Intent.OpenRadial -> {
                        terminalIntent = intent
                        if (toggles.radialFan) {
                            haptics.tap()
                            callbacks.onOpenRadial(Offset(firstDown.position.x, firstDown.position.y))
                        }
                    }
                    MessageDragLogic.Intent.LongPress -> {
                        terminalIntent = intent
                        haptics.tap()
                        callbacks.onLongPress()
                    }
                    MessageDragLogic.Intent.DoubleTap -> {
                        terminalIntent = intent
                        haptics.tap()
                        callbacks.onDoubleTap()
                        lastTapUpAtMs = null
                    }
                    MessageDragLogic.Intent.Yield -> {
                        if (change != null && !change.pressed) {
                            // A completed, unresolved gesture: either a plain tap (remember it for
                            // double-tap correlation -- only genuine PRE-ARM taps count) or a
                            // directional drag that snapped back below its commit threshold
                            // (nothing to remember).
                            terminalIntent = intent
                            val elapsedAtUp = trace.last().atMs - trace.first().atMs
                            if (elapsedAtUp < MessageDragLogic.ARM_MS) lastTapUpAtMs = nowMs
                        }
                        // else: still pending (pre-arm, or armed but not yet past a commit
                        // threshold) or this trace was already flagged as a scroll -- keep
                        // looping without consuming either way.
                    }
                }
            }
            // Live horizontal direction hint -- purely visual, independent of what resolve() just
            // returned (which stays Yield right up until release for this channel; see its KDoc).
            if (armed && terminalIntent == null && change != null && change.pressed) {
                val dxLast = trace.last().dxDp
                val dyLast = trace.last().dyDp
                val horizontal = abs(dxLast) > MessageDragLogic.TOUCH_SLOP_DP && abs(dxLast) >= abs(dyLast)
                callbacks.onHorizontalPreview(
                    if (!horizontal) null else if (dxLast < 0) HorizontalDragDirection.LEFT else HorizontalDragDirection.RIGHT,
                )
            }
            // Once armed, this IS our gesture -- claim every subsequent event so the LazyColumn
            // scroll / bubble tap detectors never also react to the same drag.
            if (armed) change?.consume()
            if (change != null && !change.pressed) released = true
        }
        callbacks.onGestureEnded()
    }
}

/**
 * The judgment ribbon (STUDIO_UX_SPEC.md §4.2: "the drag stretches a judgment ribbon out of the
 * bubble"). Shown while [grade] is non-null-or-being-previewed (caller passes null to hide it
 * entirely vs [MessageDragLogic.Intent.VerdictDetent]'s own null, which means "armed but below the
 * first detent" — [visible] disambiguates). Colourblind-safe by construction (WCAG 1.4.1 / house
 * gate, binding constraint 6): shape (filled vs outlined chevron) + a text label carry the meaning,
 * never violet/neutral alone.
 *
 * Also renders the "capture-importance chip" the plan's Gesture arbitration section calls for:
 * [Fates.forResolution] over what [CompactionContract.resolve] would floor this message to if the
 * previewed grade committed right now — a preview only, nothing is persisted until release.
 */
@Composable
fun VerdictDragRibbon(
    visible: Boolean,
    grade: Int?,
    bookmarked: Boolean,
    isOnVersionSpine: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val c = LocalHyleColors.current
    val label = when (grade) {
        2 -> "reference-grade"
        1 -> "useful"
        -1 -> "off"
        -2 -> "wrong"
        else -> "keep pulling…"
    }
    val previewVerdict = grade?.let { Verdict(msgId = "preview", grade = it, at = 0L) }
    val resolved = CompactionContract.resolve(
        msgId = "preview",
        directive = null,
        verdict = previewVerdict,
        isBookmarked = bookmarked,
        isOnVersionSpine = isOnVersionSpine,
    )
    val fate = Fates.forResolution(resolved)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (grade != null && grade > 0) c.violet.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            (if (grade != null && grade > 0) "▲ " else if (grade != null) "▼ " else "↕ ") + label,
            style = MaterialTheme.typography.labelSmall,
            color = if (grade != null && grade > 0) c.violet else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (grade != null) {
            Text(
                "  ·  would keep: ${fate.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A quiet, momentary hint under the bubble while a horizontal reply/quote drag is in flight
 *  (before release commits it) — the visual half of the horizontal channel's parity, the vertical
 *  ribbon's simpler sibling (no detents to preview, just "this is what letting go does now"). */
@Composable
fun HorizontalDragHint(direction: HorizontalDragDirection?, modifier: Modifier = Modifier) {
    if (direction == null) return
    val c = LocalHyleColors.current
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(c.raised).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(
            when (direction) {
                HorizontalDragDirection.LEFT -> "↰ Reply"
                HorizontalDragDirection.RIGHT -> "↱ Quote  ·  hold to Branch / Fork / Spawn"
            },
            style = MaterialTheme.typography.labelSmall,
            color = c.textMid,
        )
    }
}

enum class HorizontalDragDirection { LEFT, RIGHT }
