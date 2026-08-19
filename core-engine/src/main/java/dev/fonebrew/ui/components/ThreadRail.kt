@file:OptIn(ExperimentalMaterial3Api::class)

package dev.fonebrew.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fonebrew.domain.provenance.ProvenanceState
import dev.fonebrew.domain.tree.TreeFork
import dev.aarso.hyle.Pulse
import dev.aarso.hyle.cells.hylePulse
import dev.aarso.hyle.cells.rememberHyleHaptics
import dev.fonebrew.ui.state.ThreadRailPresenter
import dev.fonebrew.ui.theme.LocalHyleColors

/**
 * THREAD_TOPOLOGY_PLAN.md's ThreadRail — the right-edge dash minimap, one [ThreadRailPresenter.Dash]
 * per turn on the active path. Render-only: every value it draws is already resolved by
 * [ThreadRailPresenter] (pure, JVM-tested); this file's own job is strictly gesture wiring +
 * Canvas drawing, both owner-verified on device (no Robolectric/device here — CLAUDE.md
 * "Environment honesty").
 *
 * **Sizing** (THREAD_TOPOLOGY_PLAN.md's ThreadRail section): a ~24dp hit strip carries the gesture
 * handlers (bigger, thumb-friendly target); a ~10dp visual strip centered inside it is what's
 * actually drawn — the same "generous hit target, modest visual footprint" split
 * `dev.fonebrew.ui.loops.LoopRoom`'s own node handles use.
 *
 * **Gestures + parity (binding constraint 4).** Tap jumps to the nearest turn (reusing the
 * caller's own `findScrollPrefix`-aware scroll, not duplicated here — see [onJump]'s KDoc).
 * Vertical drag scrubs with one [dev.aarso.hyle.cells.HyleHaptics.tap] tick per turn crossed,
 * settling ([dev.aarso.hyle.cells.HyleHaptics.settle]) into a jump on release. Long-press — and,
 * with no gesture at all, a [CustomAccessibilityAction] — opens the **"Jump to…" sheet**: the
 * tappable/TalkBack-reachable parity surface for the whole rail, listing every turn by its
 * [ThreadRailPresenter.Dash.label].
 *
 * **Motion (binding constraint 6).** Only [ThreadRailPresenter.Dash.pulseWatched] pulses
 * ([dev.aarso.hyle.cells.hylePulse] at [Pulse.WATCHED]) — every other dash, including a live but
 * on-device generation, is rendered still ([Pulse.STILL]/no modifier). Motion is reserved for the
 * one real state it means: a watched, off-device generation in flight.
 *
 * @param dashes render-ready rail marks, root-first — [ThreadRailPresenter.present]'s own output.
 * @param onJump called with a [ThreadRailPresenter.Dash.nodeId] the user picked (tap, scrub
 *   release, or a "Jump to…" row); the caller resolves that id back to a `LazyColumn` index and
 *   adds its own `findScrollPrefix` offset (see `dev.fonebrew.ui.ChatScreen`) — this component never
 *   touches scroll state directly, so it stays reusable outside Chat's own list-prefix quirk.
 */
@Composable
fun ThreadRail(
    dashes: List<ThreadRailPresenter.Dash>,
    modifier: Modifier = Modifier,
    onJump: (String) -> Unit = {},
) {
    if (dashes.isEmpty()) return

    var jumpSheetOpen by remember { mutableStateOf(false) }
    val haptics = rememberHyleHaptics()
    val colors = LocalHyleColors.current
    val local = MaterialTheme.colorScheme.onSurfaceVariant
    val watched = MaterialTheme.colorScheme.tertiary
    val unknown = MaterialTheme.colorScheme.outline

    fun colorFor(provenance: ProvenanceState): Color = when (provenance) {
        ProvenanceState.LOCAL -> local
        ProvenanceState.CLOUD, ProvenanceState.MIXED -> watched
        ProvenanceState.UNKNOWN -> unknown
    }

    BoxWithConstraints(
        modifier = modifier
            .width(HIT_STRIP_WIDTH)
            .fillMaxHeight()
            .semantics {
                contentDescription =
                    "Conversation rail, ${dashes.size} turns. Drag to scrub, tap to jump, long-press for the full list."
                customActions = listOf(
                    CustomAccessibilityAction("Open jump to list") { jumpSheetOpen = true; true },
                    CustomAccessibilityAction("Jump to latest turn") { onJump(dashes.last().nodeId); true },
                )
            }
            .pointerInput(dashes) {
                detectTapGestures(
                    onTap = { offset ->
                        val idx = ThreadRailPresenter.indexForFraction(dashes.size, offset.y / size.height.toFloat())
                        if (idx >= 0) onJump(dashes[idx].nodeId)
                    },
                    onLongPress = { jumpSheetOpen = true },
                )
            }
            .pointerInput(dashes) {
                var lastIndex = -1
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        lastIndex = ThreadRailPresenter.indexForFraction(dashes.size, offset.y / size.height.toFloat())
                    },
                    onDragEnd = {
                        if (lastIndex >= 0) {
                            onJump(dashes[lastIndex].nodeId)
                            haptics.settle()
                        }
                        lastIndex = -1
                    },
                    onDragCancel = { lastIndex = -1 },
                    onVerticalDrag = { change, _ ->
                        val idx = ThreadRailPresenter.indexForFraction(dashes.size, change.position.y / size.height.toFloat())
                        if (idx >= 0 && idx != lastIndex) {
                            repeat(ThreadRailPresenter.ticksCrossed(lastIndex, idx)) { haptics.tap() }
                            lastIndex = idx
                        }
                    },
                )
            },
    ) {
        val count = dashes.size
        val heightDp = maxHeight

        Canvas(
            Modifier
                .width(VISUAL_WIDTH)
                .fillMaxHeight()
                .align(Alignment.Center),
        ) {
            // The continuous hairline every dash sits on — gives the strip a "rail," not just
            // a scatter of unconnected marks.
            drawLine(
                color = colors.hairline,
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = 1.dp.toPx(),
            )
            dashes.forEachIndexed { index, dash ->
                val y = yFor(index, count, size.height)
                drawDash(dash, y, colorFor(dash.provenance), colors.hairline)
            }
        }

        // The live dash's own pulse — a separate, positioned overlay (not the shared Canvas
        // above) because `hylePulse` is a Modifier, and only ONE mark on the whole rail is ever
        // allowed to move (binding constraint 6): the in-flight, watched-cloud generation.
        val liveIndex = dashes.indexOfLast { it.live }
        if (liveIndex >= 0) {
            val live = dashes[liveIndex]
            val liveFraction = if (count == 1) 0.5f else liveIndex / (count - 1).toFloat()
            Spacer(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = heightDp * liveFraction)
                    .size(width = live.thicknessDp.dp, height = LIVE_MARK_HEIGHT)
                    .hylePulse(if (live.pulseWatched) Pulse.WATCHED else Pulse.STILL)
                    .background(colorFor(live.provenance)),
            )
        }
    }

    if (jumpSheetOpen) {
        ThreadRailJumpSheet(
            dashes = dashes,
            onJump = { onJump(it); jumpSheetOpen = false },
            onDismiss = { jumpSheetOpen = false },
        )
    }
}

/** 0f (oldest, top) .. [height] (latest, bottom); a single dash centers itself. */
private fun yFor(index: Int, count: Int, height: Float): Float =
    if (count <= 1) height / 2f else height * index / (count - 1)

/**
 * Draws one [ThreadRailPresenter.Dash]: the fidelity tick (thickness = width) at [hue], plus its
 * accent marks — each accent a distinct *shape*, never colour-alone (binding constraint 6),
 * offset clear of the tick so co-occurring accents (e.g. a bookmarked Version tip) don't overlap.
 */
private fun DrawScope.drawDash(dash: ThreadRailPresenter.Dash, y: Float, hue: Color, hairline: Color) {
    val cx = size.width / 2f
    val tickW = dash.thicknessDp.dp.toPx()
    val tickH = 4.dp.toPx()

    // The fidelity tick itself — provenance hue, shape-coded on top of colour (never colour-alone):
    // LOCAL = filled disc, CLOUD = hollow ring, MIXED = half-filled disc, UNKNOWN = hollow square.
    when (dash.provenance) {
        ProvenanceState.LOCAL -> drawCircle(hue, radius = tickW, center = Offset(cx, y))
        ProvenanceState.CLOUD -> drawCircle(hue, radius = tickW, center = Offset(cx, y), style = Stroke(width = tickH / 2f))
        ProvenanceState.MIXED -> drawArc(
            color = hue,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(cx - tickW, y - tickW),
            size = androidx.compose.ui.geometry.Size(tickW * 2f, tickW * 2f),
        )
        ProvenanceState.UNKNOWN -> drawRect(
            hue,
            topLeft = Offset(cx - tickW / 2f, y - tickW / 2f),
            size = androidx.compose.ui.geometry.Size(tickW, tickW),
            style = Stroke(width = tickH / 3f),
        )
    }

    if (dash.tombstone) {
        // Strike-through: the −2/failure-tombstone accent, independent of the tick already
        // bottoming out at 1dp — the shape alone (a disc) can't carry "this path failed."
        drawLine(hue, Offset(cx - tickW * 1.5f, y - tickW * 1.5f), Offset(cx + tickW * 1.5f, y + tickW * 1.5f), strokeWidth = 1.dp.toPx())
    }
    if (dash.bookmarked) {
        // A small pinned dot, offset left of the tick.
        drawCircle(hue, radius = 1.5.dp.toPx(), center = Offset(cx - BOOKMARK_OFFSET.toPx(), y))
    }
    if (dash.versionFlag) {
        // A small flag triangle, offset right of the tick.
        val fx = cx + VERSION_OFFSET.toPx()
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(fx, y - 3.dp.toPx())
            lineTo(fx + 4.dp.toPx(), y)
            lineTo(fx, y + 3.dp.toPx())
            close()
        }
        drawPath(path, hue)
    }
    if (dash.compactionDiamond) {
        // A small diamond outline around the tick.
        val d = tickW + 3.dp.toPx()
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, y - d)
            lineTo(cx + d, y)
            lineTo(cx, y + d)
            lineTo(cx - d, y)
            close()
        }
        drawPath(path, hue, style = Stroke(width = 1.dp.toPx()))
    }
    if (dash.chapterBracket) {
        // A bracket "[  ]" spanning the strip, just above and below this y.
        val half = size.width / 2.4f
        val armH = 2.dp.toPx()
        drawLine(hairline, Offset(cx - half, y - CHAPTER_GAP.toPx()), Offset(cx - half, y - CHAPTER_GAP.toPx() + armH), strokeWidth = 1.dp.toPx())
        drawLine(hairline, Offset(cx + half, y - CHAPTER_GAP.toPx()), Offset(cx + half, y - CHAPTER_GAP.toPx() + armH), strokeWidth = 1.dp.toPx())
    }
    if (dash.sessionHairline) {
        // A full-bleed rule across the whole visual strip — a session boundary, not a per-turn mark.
        drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
    }
    if (dash.lineageBranch != null) {
        // A small chevron at the very top — only ever the root dash carries this (a Fork/Spawn's
        // fresh root), so it never competes with another accent for space.
        val chevronY = y - 6.dp.toPx()
        val w = 3.dp.toPx()
        drawLine(hue, Offset(cx - w, chevronY + w), Offset(cx, chevronY), strokeWidth = 1.dp.toPx())
        drawLine(hue, Offset(cx, chevronY), Offset(cx + w, chevronY + w), strokeWidth = 1.dp.toPx())
    }
}

/**
 * The tappable/TalkBack parity surface for the whole rail (binding constraint 4): every turn as a
 * plain list row, reachable with no gesture at all.
 */
@Composable
private fun ThreadRailJumpSheet(
    dashes: List<ThreadRailPresenter.Dash>,
    onJump: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "Jump to…",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth()) {
                items(dashes, key = { it.nodeId }) { dash ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !dash.live) { onJump(dash.nodeId) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics { contentDescription = jumpRowDescription(dash) },
                    ) {
                        Text(
                            jumpRowPrefix(dash) + dash.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (dash.live) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun jumpRowPrefix(dash: ThreadRailPresenter.Dash): String = buildString {
    if (dash.sessionHairline) append("─ ") // session start
    if (dash.chapterBracket) append("[ch] ")
    if (dash.compactionDiamond) append("◆ ")
    if (dash.lineageBranch == TreeFork.LineageKind.FORK) append("fork ‹ ")
    if (dash.lineageBranch == TreeFork.LineageKind.SPAWN) append("spawn ‹ ")
    if (dash.bookmarked) append("★ ")
    if (dash.versionFlag) append("⚑ ")
}

private fun jumpRowDescription(dash: ThreadRailPresenter.Dash): String = buildString {
    append(dash.label)
    if (dash.chapterBracket) append(", chapter marker")
    if (dash.sessionHairline) append(", session start")
    if (dash.compactionDiamond) append(", compaction run")
    if (dash.bookmarked) append(", bookmarked")
    if (dash.versionFlag) append(", version")
    if (dash.tombstone) append(", marked wrong")
    if (dash.provenance.watched) append(", ${dash.provenance.label}, watched")
}

private val HIT_STRIP_WIDTH = 24.dp
private val VISUAL_WIDTH = 10.dp
private val LIVE_MARK_HEIGHT = 4.dp
private val BOOKMARK_OFFSET = 6.dp
private val VERSION_OFFSET = 6.dp
private val CHAPTER_GAP = 6.dp
