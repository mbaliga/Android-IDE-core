package dev.aarso.ui.spatial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.aarso.domain.a11y.Depth
import dev.aarso.domain.a11y.Room
import dev.aarso.domain.a11y.SpatialLinearization
import dev.aarso.domain.a11y.SpatialPosition
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aarso.AarsoApp
import dev.aarso.data.DownloadCenter
import dev.aarso.ui.ChatScreen
import dev.aarso.ui.ChatViewModel
import dev.aarso.ui.ModelsViewModel
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.rooms.ChatsRoom
import dev.aarso.ui.rooms.SettingsRoom
import dev.aarso.ui.rooms.TreeRoom
import androidx.compose.foundation.border
import dev.aarso.ui.theme.LocalHyleColors
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The spatial shell (redesign brief §1–§3): Chat is home; Chats parks off the
 * left edge, Settings off the right, Models beneath the thread's bottom
 * boundary, and the Tree sits on the z-axis as a semantic zoom. No tab bar.
 *
 * Motion: every transition is finger-driven 1:1 and interruptible; release
 * settles on an eased cubic-bezier (0.4, 0, 0.2, 1), ~320 ms, no spring. Room
 * reveal is lift-and-part: the incoming room fills the screen while the live
 * Chat pane lifts (shadow, ~90% scale, rounded corners) and parks — still
 * visible, still the way back. Translation uses a placement offset (not just a
 * graphics layer) so touch targets travel with the card.
 */

/** Which room a settle should land in. The four edges + the two z-axis depths. */
enum class SpatialTarget { HOME, CHATS, SETTINGS, PROJECT, DEVELOP, TREE, LOOPS }

class SpatialController(private val scope: CoroutineScope) {

    /** -1 = Settings (right) … 0 = home … +1 = Chats (left). */
    val h = Animatable(0f)

    /** -1 = Dev tools (bottom) … 0 = home … +1 = Project planning (top). */
    val v = Animatable(0f)

    /** -1 = Loops (pinch OUT / zoom in) … 0 = thread … +1 = Tree (pinch IN / zoom out). */
    val z = Animatable(0f)

    var viewport = IntSize(1, 1)

    /** The brief's settle: ease-in-out cubic-bezier, no overshoot. */
    private val settleSpec = tween<Float>(durationMillis = 320, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f))

    private var lastHDelta = 0f
    private var lastVDelta = 0f

    val atHome: Boolean get() = abs(h.value) < 0.01f && abs(v.value) < 0.01f && abs(z.value) < 0.01f
    val openRoom: SpatialTarget
        get() = when {
            h.value >= 0.999f -> SpatialTarget.CHATS
            h.value <= -0.999f -> SpatialTarget.SETTINGS
            v.value >= 0.999f -> SpatialTarget.PROJECT
            v.value <= -0.999f -> SpatialTarget.DEVELOP
            z.value >= 0.999f -> SpatialTarget.TREE
            z.value <= -0.999f -> SpatialTarget.LOOPS
            else -> SpatialTarget.HOME
        }

    fun dragH(deltaPx: Float, min: Float, max: Float) {
        lastHDelta = deltaPx
        val next = (h.value + deltaPx / viewport.width).coerceIn(min, max)
        scope.launch { h.snapTo(next) }
    }

    fun settleH() {
        val value = h.value
        val target = when {
            abs(lastHDelta) > 2f -> if (lastHDelta > 0) {
                if (value >= 0f) 1f else 0f
            } else {
                if (value <= 0f) -1f else 0f
            }
            else -> when {
                value > 0.5f -> 1f
                value < -0.5f -> -1f
                else -> 0f
            }
        }
        val clamped = if (value in -0.001f..0.001f) 0f else target
        scope.launch { h.animateTo(clamped, settleSpec) }
    }

    fun dragV(deltaPx: Float, min: Float, max: Float) {
        lastVDelta = deltaPx
        // deltaPx is the finger's screen-y delta (positive = downward). Dragging down
        // from the top edge opens Project (+); dragging up from the bottom opens Dev (−).
        val next = (v.value + deltaPx / (viewport.height * 0.7f)).coerceIn(min, max)
        scope.launch { v.snapTo(next) }
    }

    fun settleV() {
        val value = v.value
        val target = when {
            abs(lastVDelta) > 2f -> if (lastVDelta > 0) {
                if (value >= 0f) 1f else 0f
            } else {
                if (value <= 0f) -1f else 0f
            }
            else -> when {
                value > 0.5f -> 1f
                value < -0.5f -> -1f
                else -> 0f
            }
        }
        val clamped = if (value in -0.001f..0.001f) 0f else target
        scope.launch { v.animateTo(clamped, settleSpec) }
    }

    fun dragZTo(value: Float) {
        scope.launch { z.snapTo(value.coerceIn(-1f, 1f)) }
    }

    /** Settle the z-axis to the nearest depth — Loops (−1), thread (0), Tree (+1). */
    fun settleZ() {
        val target = when {
            z.value > 0.5f -> 1f
            z.value < -0.5f -> -1f
            else -> 0f
        }
        scope.launch { z.animateTo(target, settleSpec) }
    }

    fun open(target: SpatialTarget) {
        scope.launch {
            when (target) {
                SpatialTarget.CHATS -> h.animateTo(1f, settleSpec)
                SpatialTarget.SETTINGS -> h.animateTo(-1f, settleSpec)
                SpatialTarget.PROJECT -> v.animateTo(1f, settleSpec)
                SpatialTarget.DEVELOP -> v.animateTo(-1f, settleSpec)
                SpatialTarget.TREE -> z.animateTo(1f, settleSpec)
                SpatialTarget.LOOPS -> z.animateTo(-1f, settleSpec)
                SpatialTarget.HOME -> closeAll()
            }
        }
    }

    fun closeAll() {
        scope.launch { h.animateTo(0f, settleSpec) }
        scope.launch { v.animateTo(0f, settleSpec) }
        scope.launch { z.animateTo(0f, settleSpec) }
    }
}

@Composable
fun SpatialRoot() {
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory)
    val container = (LocalContext.current.applicationContext as AarsoApp).container
    val scope = rememberCoroutineScope()
    val controller = remember { SpatialController(scope) }

    // §7: content shared/selected into the app lands in the composer — go home.
    val intake by chatViewModel.intake.collectAsState()
    LaunchedEffect(intake) { if (intake != null) controller.closeAll() }

    val hProgress = controller.h.value
    val vProgress = controller.v.value
    val zProgress = controller.z.value
    val anyRoom = abs(hProgress) > 0.001f || abs(vProgress) > 0.001f

    BackHandler(enabled = anyRoom || zProgress > 0.001f) { controller.closeAll() }

    // a11y (Doc 00 §3.5): the spatial model is invisible to a screen reader, so announce the
    // settled room as a polite live region. We map the gesture progress to the pure
    // domain Room/Depth and let SpatialLinearization phrase the announcement; the string only
    // changes when a room actually settles (|progress| > 0.5), so TalkBack isn't spammed mid-drag.
    val spatialPos = SpatialPosition(
        room = when {
            hProgress > 0.5f -> Room.CONVERSATIONS
            hProgress < -0.5f -> Room.SETTINGS
            vProgress > 0.5f -> Room.PROJECT
            vProgress < -0.5f -> Room.DEVELOP
            else -> Room.CHAT
        },
        depth = if (zProgress > 0.5f) Depth.TREE else Depth.ORIGIN,
    )

    val density = androidx.compose.ui.platform.LocalDensity.current
    val bandPx = with(density) { 72.dp.toPx() }
    val edgePx = with(density) { 56.dp.toPx() }
    val ac = LocalHyleColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ac.ink)
            .systemBarsPadding()
            .onSizeChanged { controller.viewport = it }
            .spatialEdgeDrag(controller, edgePx),
    ) {
        // Screen-reader room announcer: an invisible polite live region whose description is
        // the linearized room announcement. It re-announces only when [spatialPos] settles.
        Box(
            Modifier.size(1.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = SpatialLinearization.announce(spatialPos)
            },
        )

        val w = controller.viewport.width.toFloat()
        val hgt = controller.viewport.height.toFloat()
        val scale = 1f - 0.10f * max(abs(hProgress), vProgress)
        // Park distance leaves exactly the grabbable band on-screen at p=1.
        fun parkDistance(extent: Float) = extent * (1f + scale) / 2f - bandPx

        // ── Rooms, beneath the home card. Each room insets itself by the
        // parked card's band on the side the card occupies, so the band never
        // covers room content (titles, form fields, actions). ───────────────
        if (hProgress > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset((-w * (1f - hProgress)).roundToInt(), 0) }
                    .padding(end = 72.dp),
            ) {
                ChatsRoom(viewModel = chatViewModel, onClose = { controller.closeAll() })
            }
        }
        if (hProgress < -0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset((w * (1f + hProgress)).roundToInt(), 0) }
                    .padding(start = 72.dp),
            ) {
                SettingsRoom(onShowSpatialMap = { container.sessionStore.setSpatialMapSeen(false) })
            }
        }
        // Top room — Project planning. Slides down from above as v → +1; the card
        // parks downward, so inset the bottom band.
        if (vProgress > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, (-hgt * (1f - vProgress)).roundToInt()) }
                    .padding(bottom = 72.dp),
            ) {
                // S6 seam: the paid Studio installs the real Project room; the open core
                // shows the locked placeholder. Core never references ProjectRoom directly.
                (ProjectRoomSlot.content ?: { onClose -> ProjectRoomLocked(onClose) })(
                    { controller.closeAll() },
                )
            }
        }
        // Bottom room — dev tools. Slides up from below as v → −1; the card parks up.
        if (vProgress < -0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, (hgt * (1f + vProgress)).roundToInt()) }
                    .padding(top = 72.dp),
            ) {
                dev.aarso.ui.develop.DevelopRoom(onClose = { controller.closeAll() })
            }
        }

        // ── The home card: live chat, lifted and parked when a room is open ─
        val tx = (hProgress * parkDistance(w)).roundToInt()
        // +v (Project, top) parks the card down; −v (Dev, bottom) parks it up.
        val ty = (vProgress * parkDistance(hgt)).roundToInt()
        val lift = max(abs(hProgress), abs(vProgress))
        val cardShape = RoundedCornerShape((20f * lift).dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(tx, ty) }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = 24f * lift
                    shape = cardShape
                    clip = lift > 0.001f
                    alpha = 1f - 0.6f * abs(zProgress)
                }
                // Violet ring appears as the card lifts — defines the boundary on the
                // dark background where shadow alone provides no contrast.
                .border(1.dp, ac.violet.copy(alpha = lift * 0.6f), cardShape)
                // Raised surface so the parked card is visually distinct from the
                // Ink-floored rooms behind it.
                .background(ac.raised, cardShape),
        ) {
            ChatScreen(
                viewModel = chatViewModel,
                threadModifier = Modifier.spatialPinch(controller),
                onOpenModels = { controller.open(SpatialTarget.SETTINGS) },
                onOpenChats = { controller.open(SpatialTarget.CHATS) },
                onOpenSettings = { controller.open(SpatialTarget.SETTINGS) },
            )
            // While parked, the card is one big return affordance: tap or drag
            // it home; nothing inside it should react. A scrim quiets the
            // card's own content (so it can't read as overlap) and a grip pill
            // marks the visible band as grabbable.
            if (anyRoom) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(10f)
                        .returnDrag(controller)
                        .background(ac.ink.copy(alpha = 0.62f * lift)),
                ) {
                    val grip = Modifier.background(ac.violet.copy(alpha = 0.9f * lift), RoundedCornerShape(2.dp))
                    when {
                        // Card parked right (Chats open): band = card's left edge.
                        hProgress > 0.01f -> Box(
                            Modifier.align(Alignment.CenterStart).padding(start = 34.dp)
                                .size(width = 4.dp, height = 48.dp).then(grip),
                        )
                        // Card parked left (Settings open): band = card's right edge.
                        hProgress < -0.01f -> Box(
                            Modifier.align(Alignment.CenterEnd).padding(end = 34.dp)
                                .size(width = 4.dp, height = 48.dp).then(grip),
                        )
                        // Card parked down (Project/top open): band = card's bottom edge.
                        vProgress > 0.01f -> Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp)
                                .size(width = 48.dp, height = 4.dp).then(grip),
                        )
                        // Card parked up (Dev/bottom open): band = card's top edge.
                        else -> Box(
                            Modifier.align(Alignment.TopCenter).padding(top = 34.dp)
                                .size(width = 48.dp, height = 4.dp).then(grip),
                        )
                    }
                }
            }
        }

        // ── Tree: pinch IN (zoom out) — the whole conversation at once ─────
        if (zProgress > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = zProgress
                        val s = 1.15f - 0.15f * zProgress
                        scaleX = s
                        scaleY = s
                    }
                    .spatialPinch(controller),
            ) {
                TreeRoom(
                    viewModel = chatViewModel,
                    onNodeChosen = { controller.closeAll() },
                )
            }
        }

        // ── Loops: pinch OUT (zoom in) — descend into this level's loops ───
        if (zProgress < -0.001f) {
            val d = -zProgress // 0 → 1 as we descend
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = d
                        val s = 0.9f + 0.1f * d // grows in as you zoom in
                        scaleX = s
                        scaleY = s
                    }
                    .spatialPinch(controller),
            ) {
                dev.aarso.ui.loops.LoopRoom(onClose = { controller.closeAll() })
            }
        }

        // ── Edge peek (§7): the structure is seen, not memorized ───────────
        if (controller.atHome) {
            EdgePeek(alignment = Alignment.CenterStart)
            EdgePeek(alignment = Alignment.CenterEnd)
            EdgePeek(alignment = Alignment.TopCenter, vertical = true)
            EdgePeek(alignment = Alignment.BottomCenter, vertical = true)
        }

        // ── Docked download strip: top edge, every room, tap → Models (in Settings) ──
        TopDock(
            center = container.downloadCenter,
            visible = abs(hProgress) < 0.5f && abs(vProgress) < 0.5f,
            onTap = { controller.open(SpatialTarget.SETTINGS) },
            modifier = Modifier.align(Alignment.TopCenter).zIndex(20f),
        )

        // ── First-run spatial map (§7) ──────────────────────────────────────
        val mapSeen by container.sessionStore.spatialMapSeen.collectAsState()
        if (!mapSeen) {
            SpatialMapOverlay(
                onDismiss = { container.sessionStore.setSpatialMapSeen(true) },
                modifier = Modifier.zIndex(30f),
            )
        }
    }
}

@Composable
private fun BoxScope.EdgePeek(alignment: Alignment, vertical: Boolean = false) {
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(if (vertical) 8.dp else 8.dp)
            .then(if (vertical) Modifier.size(width = 56.dp, height = 4.dp) else Modifier.size(width = 4.dp, height = 56.dp))
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                RoundedCornerShape(2.dp),
            ),
    )
}

/**
 * Edge-origin drag (§2): runs on the Initial pass so it sees the gesture before the
 * thread does. A slop race decides the axis — horizontal from the L/R edges opens
 * Chats/Settings, vertical from the T/B edges opens Project/Dev — and otherwise the
 * events pass through untouched (no dead zones).
 */
private fun Modifier.spatialEdgeDrag(controller: SpatialController, edgePx: Float): Modifier =
    pointerInput(controller) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            if (!controller.atHome) return@awaitEachGesture
            val fromLeft = down.position.x <= edgePx
            val fromRight = down.position.x >= size.width - edgePx
            val fromTop = down.position.y <= edgePx
            val fromBottom = down.position.y >= size.height - edgePx
            if (!fromLeft && !fromRight && !fromTop && !fromBottom) return@awaitEachGesture

            // Slop race: whichever axis wins first claims the gesture, but only if a
            // matching edge was the origin; otherwise stand down (no dead zones).
            var dx = 0f
            var dy = 0f
            var axis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
            while (axis == 0) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                dx += change.positionChange().x
                dy += change.positionChange().y
                if (abs(dx) > viewConfiguration.touchSlop && abs(dx) >= abs(dy)) {
                    if (fromLeft || fromRight) axis = 1 else return@awaitEachGesture
                } else if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) {
                    if (fromTop || fromBottom) axis = 2 else return@awaitEachGesture
                }
            }

            if (axis == 1) {
                val min = if (fromRight) -1f else 0f
                val max = if (fromLeft) 1f else 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    controller.dragH(change.positionChange().x, min, max)
                    change.consume()
                }
                controller.settleH()
            } else {
                // Top edge opens Project (v → +1); bottom edge opens Dev (v → −1).
                val min = if (fromBottom) -1f else 0f
                val max = if (fromTop) 1f else 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    controller.dragV(change.positionChange().y, min, max)
                    change.consume()
                }
                controller.settleV()
            }
        }
    }

/**
 * Pinch (§2/§3): two-finger pinch IN on the thread background draws back to the
 * Tree (the whole conversation at once); pinching OUT on the Tree descends into
 * the thread. Initial pass + consumption once engaged, so list scrolling never
 * fights it. Single-finger gestures pass by.
 */
private fun Modifier.spatialPinch(controller: SpatialController): Modifier =
    pointerInput(controller) {
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            // Zoom is only meaningful on the z-axis (home / Tree / Loops), never while
            // an edge room is parked.
            if (abs(controller.h.value) > 0.01f || abs(controller.v.value) > 0.01f) return@awaitEachGesture
            var accumulated = 1f
            var engaged = false
            val base = controller.z.value
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                if (pressed >= 2) {
                    val zoom = event.calculateZoom()
                    if (!zoom.isNaN() && !zoom.isInfinite() && zoom > 0f) accumulated *= zoom
                    if (!engaged && abs(accumulated - 1f) > 0.04f) engaged = true
                    if (engaged) {
                        // Pinch IN (accumulated < 1) raises z → Tree; pinch OUT lowers
                        // z → Loops. (1 − accumulated) carries the sign for both.
                        controller.dragZTo(base + (1f - accumulated) * 2.2f)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            if (engaged) controller.settleZ()
        }
    }

/** The parked card: tap or drag it back home. Consumes everything else. */
private fun Modifier.returnDrag(controller: SpatialController): Modifier =
    pointerInput(controller) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val horizontal = abs(controller.h.value) > 0.5f
            var moved = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                if (abs(delta.x) + abs(delta.y) > 0f) moved = true
                if (horizontal) {
                    controller.dragH(delta.x, min = -1f, max = 1f)
                } else {
                    controller.dragV(delta.y, min = -1f, max = 1f)
                }
                change.consume()
            }
            when {
                !moved -> controller.closeAll() // a plain tap restores home
                horizontal -> controller.settleH()
                else -> controller.settleV()
            }
        }
    }

/**
 * The global download dock (§11): one thin determinate strip at the top edge,
 * violet fill on a dark track. One aggregate bar with a count badge when more
 * than one download runs (the single specified behavior). Hidden inside Models,
 * where the cards carry the detail.
 */
@Composable
private fun TopDock(
    center: DownloadCenter,
    visible: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active by center.active.collectAsState()
    val running = active.values.filter { it.running }
    if (running.isEmpty() || !visible) return

    val totals = running.mapNotNull { it.progress.totalBytes.takeIf { t -> t > 0 } }
    val fraction = if (totals.isNotEmpty()) {
        running.sumOf { it.progress.downloadedBytes }.toFloat() / totals.sum()
    } else {
        0f
    }
    val label = if (running.size == 1) {
        running.first().request.fileName
    } else {
        "${running.size} downloads"
    }
    Surface(
        color = LocalHyleColors.current.inset,
        modifier = modifier.fillMaxWidth().clickable(onClick = onTap),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = LocalHyleColors.current.inset,
            )
        }
    }
}

/** One-time spatial map (§7): the four rooms + the pinch, told plainly. */
@Composable
private fun SpatialMapOverlay(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LocalHyleColors.current.ink.copy(alpha = 0.94f))
            // Scrim swallows taps so nothing beneath reacts while the map shows.
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(32.dp)) {
            Text("How to move", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(20.dp))
            MapLine("⟶", "Drag from the left edge: Chats")
            MapLine("⟵", "Drag from the right edge: Settings & Models")
            MapLine("↓", "Drag down from the top edge: Project planning")
            MapLine("↑", "Drag up from the bottom edge: Dev tools")
            MapLine("⤢", "Pinch out on the thread: Loops")
            MapLine("⤡", "Pinch in on the thread: the Tree")
            Spacer(Modifier.height(8.dp))
            Text(
                "Everything follows your finger; the chat card stays in reach — tap it to come back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            HyleButton("Got it", onClick = onDismiss)
        }
    }
}

@Composable
private fun MapLine(glyph: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
