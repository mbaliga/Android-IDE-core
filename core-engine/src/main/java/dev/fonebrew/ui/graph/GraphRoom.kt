package dev.fonebrew.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fonebrew.domain.thread.ThreadEdgeKind
import dev.fonebrew.domain.thread.ThreadGraph
import dev.fonebrew.domain.thread.ThreadGraphNode
import dev.fonebrew.domain.thread.ThreadMapLayout
import dev.fonebrew.domain.thread.ThreadNodeKind
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleChip
import dev.aarso.hyle.cells.HyleRadialMenu
import dev.aarso.hyle.cells.HyleRadialMenuItem
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.ui.ChatViewModel
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val COL_SPACING = 180.dp
private val ROW_SPACING = 92.dp
private val CANVAS_PADDING = 60.dp
private val NODE_SIZE = 40.dp

/**
 * **WP10** — the living graph, native (THREAD_TOPOLOGY_PLAN.md WP10 "Native graph surfaces"):
 * every conversation/fork/spawn/marker/delegation the whole app knows about, laid out by
 * [ThreadMapLayout] (chains as columns, turn depth as rows) and rendered on a pan/zoom canvas that
 * reuses `ui/loops/LoopRoom.kt`'s `LoopCanvas` shape (dot-grid backdrop, `Canvas`-drawn edges, node
 * `Box`es with their own `pointerInput`) — the graph editor precedent the plan names for this WP.
 * Opened from TreeRoom's "Graph" chip; [onOpenNode] mirrors [dev.fonebrew.ui.ChatViewModel.branchFrom]
 * + "close everything" the same way `TreeRoom`'s own row tap already does, so jumping from the
 * graph lands exactly where tapping the same node in the flat Tree list would.
 *
 * Read-only: unlike the Loop editor, nothing here can be dragged into a different position — the
 * layout is a deterministic projection of the tree, not a user-arranged canvas (there's nothing to
 * save). Tap a node for its [NodeDetailsDialog] (kind/label/time + an "Open" button when the node
 * has somewhere to jump to); long-press fans the same two actions through [HyleRadialMenu] as a
 * faster path — never the *only* path, so the whole surface stays reachable by tap alone.
 *
 * **Binding constraint 6 (WCAG 1.4.1):** node kind is never colour-only — [glyphFor] pairs a
 * distinct **shape** with each [ThreadNodeKind], and the on-screen [Legend] spells out shape+label
 * together, so kind is legible with colour perception off entirely.
 *
 * The **observer remark card** is a separate, explicit "Observations" toggle that calls
 * [ChatViewModel.observerRemarks] — gated by [ChatViewModel.observerEnabled] (owner decision 5) —
 * never fetched automatically, per [dev.fonebrew.domain.thread.ObserverScript]'s "presented on
 * request, never pushed" rule.
 *
 * **WP11** adds the "Deep view" entry point next to it: once [graph] has loaded and
 * [dev.fonebrew.domain.disclosure.Surface.GRAPH_DEEP] is revealed at the session's current
 * [dev.fonebrew.domain.disclosure.DisclosureTier], a second button opens [GraphWebRoom] — the same
 * [ThreadGraph] snapshot, rendered by G6 in a locked-down WebView (`docs/design`'s hybrid-
 * rendering decision) as a second full-screen overlay on top of this one.
 */
@Composable
fun GraphRoom(
    viewModel: ChatViewModel,
    onOpenNode: (String) -> Unit,
    onClose: () -> Unit,
) {
    var graph by remember { mutableStateOf<ThreadGraph?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { viewModel.loadThreadGraph() }.fold(
            onSuccess = { graph = it },
            onFailure = { loadError = it.message ?: "couldn't load the graph" },
        )
    }

    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var radialNodeId by remember { mutableStateOf<String?>(null) }
    var showObserverPanel by remember { mutableStateOf(false) }
    var observerRemarks by remember { mutableStateOf<List<String>>(emptyList()) }
    var observerLoading by remember { mutableStateOf(false) }
    var showDeepView by remember { mutableStateOf(false) }
    val observerEnabled by viewModel.observerEnabled.collectAsState()
    val disclosureTier by viewModel.disclosureTier.collectAsState()
    // WP11's G6 deep-view entry point — Surface.GRAPH_DEEP is POWER-tier, same "surface + tier"
    // gate every other disclosure-controlled entry point uses (Disclosure.isRevealed).
    val deepViewRevealed = dev.fonebrew.domain.disclosure.Disclosure.isRevealed(
        dev.fonebrew.domain.disclosure.Surface.GRAPH_DEEP,
        dev.fonebrew.domain.disclosure.Disclosure.tierOf(disclosureTier),
    )
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text("‹ Back") }
                    Text("Graph", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // THREAD_TOPOLOGY_PLAN.md WP11: the same graph this room already loaded,
                        // handed to the G6 WebView room rather than re-fetched — GraphWebRoom
                        // takes a plain ThreadGraph, not a ViewModel, so it stays a pure renderer.
                        // Fixed per audit: desktop-class-kit.md §3 names graph rooms explicitly —
                        // these are togglable-visibility controls (HyleChip's own idiom, mirroring
                        // LoopRoom's stateTab chips), not one-shot actions like "‹ Back" (which
                        // stays TextButton — that specific usage is the app-wide dismiss convention).
                        if (deepViewRevealed && graph != null) {
                            HyleChip(
                                selected = showDeepView,
                                onClick = { showDeepView = true },
                                label = "Deep view",
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                        HyleChip(
                            selected = showObserverPanel,
                            onClick = { showObserverPanel = !showObserverPanel },
                            label = "Observations",
                        )
                    }
                }
                HorizontalDivider()

                BoxWithConstraints(Modifier.weight(1f).fillMaxSize()) {
                    val density = LocalDensity.current
                    val roomCenter = with(density) { Offset(maxWidth.toPx() / 2f, maxHeight.toPx() / 2f) }
                    val g = graph
                    when {
                        loadError != null -> Text(
                            loadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        g == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        g.nodes.isEmpty() -> Text(
                            "Nothing to map yet — start a conversation, then fork or spawn from a " +
                                "turn (radial menu → Branch/Fork/Spawn) to grow the graph.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        else -> {
                            val layout = remember(g) { ThreadMapLayout.compute(g) }
                            ThreadMapCanvas(
                                graph = g,
                                layout = layout,
                                onTapNode = { id -> selectedNodeId = id },
                                onLongPressNode = { id -> radialNodeId = id },
                            )
                        }
                    }
                    Legend(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))

                    radialNodeId?.let { id ->
                        val node = graph?.nodes?.firstOrNull { it.id == id }
                        if (node != null) {
                            NodeRadialMenu(
                                node = node,
                                anchor = roomCenter,
                                onOpen = { target -> radialNodeId = null; onOpenNode(target) },
                                onDetails = { radialNodeId = null; selectedNodeId = id },
                                onDismiss = { radialNodeId = null },
                            )
                        }
                    }
                }

                if (showObserverPanel) {
                    HorizontalDivider()
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (!observerEnabled) {
                            Text(
                                "The observer is off — turn it on in Settings → Global to see " +
                                    "descriptive remarks about this graph. Off by default; nothing is " +
                                    "inferred while it's off.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Observations", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                if (observerLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    // Fixed per audit: raw TextButton -> HyleButton(secondary) —
                                    // same fix rationale as the header chips above.
                                    HyleButton(
                                        "Refresh",
                                        onClick = {
                                            observerLoading = true
                                            scope.launch {
                                                observerRemarks = runCatching { viewModel.observerRemarks() }.getOrDefault(emptyList())
                                                observerLoading = false
                                            }
                                        },
                                        secondary = true,
                                    )
                                }
                            }
                            if (observerRemarks.isEmpty()) {
                                Text(
                                    "Nothing to say yet — tap Refresh.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                observerRemarks.forEach { remark ->
                                    Text("· $remark", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedNodeId?.let { id ->
        val node = graph?.nodes?.firstOrNull { it.id == id }
        if (node != null) {
            NodeDetailsDialog(
                node = node,
                onOpen = openTargetId(node)?.let { target -> { selectedNodeId = null; onOpenNode(target) } },
                onDismiss = { selectedNodeId = null },
            )
        } else {
            selectedNodeId = null
        }
    }

    if (showDeepView) {
        val g = graph
        if (g != null) {
            GraphWebRoom(graph = g, onClose = { showDeepView = false })
        } else {
            showDeepView = false
        }
    }
}

/** Where "Open" lands for [node] — the node itself for a real tree node, its anchor for a marker
 *  or delegation, or nothing for an unanchored marker (never fabricated). A COMMIT node (1.2.0)
 *  has no conversation view of its own — like MARKER/DECISION/DELEGATION, "Open" lands on the
 *  message/run node it's anchored from (its `parentId`), never the commit itself. */
private fun openTargetId(node: ThreadGraphNode): String? = when (node.kind) {
    ThreadNodeKind.MESSAGE, ThreadNodeKind.FORK_ROOT, ThreadNodeKind.SPAWN_ROOT, ThreadNodeKind.RUN_ROOT -> node.id
    ThreadNodeKind.MARKER, ThreadNodeKind.DECISION, ThreadNodeKind.DELEGATION, ThreadNodeKind.COMMIT -> node.parentId
}

private fun kindLabel(kind: ThreadNodeKind): String = when (kind) {
    ThreadNodeKind.MESSAGE -> "Turn"
    ThreadNodeKind.FORK_ROOT -> "Fork"
    ThreadNodeKind.SPAWN_ROOT -> "Spawn"
    ThreadNodeKind.RUN_ROOT -> "Loop run"
    ThreadNodeKind.MARKER -> "Marker"
    ThreadNodeKind.DECISION -> "Decision"
    ThreadNodeKind.DELEGATION -> "Delegation"
    ThreadNodeKind.COMMIT -> "Commit"
}

/** Outcome text is the WCAG-safe channel for a [ThreadGraphNode.outcome] (binding constraint 6 —
 *  never colour-only): shown in [NodeDetailsDialog] and appended to the label the deep G6 view
 *  draws, never left as a colour swap alone. */
private fun outcomeLabel(outcome: dev.fonebrew.domain.thread.DelegationOutcome): String = when (outcome) {
    dev.fonebrew.domain.thread.DelegationOutcome.PENDING -> "Pending"
    dev.fonebrew.domain.thread.DelegationOutcome.KEPT -> "Kept"
    dev.fonebrew.domain.thread.DelegationOutcome.REVERTED -> "Reverted"
}

@Composable
private fun NodeDetailsDialog(node: ThreadGraphNode, onOpen: (() -> Unit)?, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kindLabel(node.kind)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(node.label?.takeIf { it.isNotBlank() } ?: "(no label)", style = MaterialTheme.typography.bodyMedium)
                Text(node.at.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Node inspector text channel (2026-08-29 audit, gaps 2+4) — never only a shape/
                // colour cue: the outcome/confidence value itself is always readable here.
                node.outcome?.let {
                    Text("Outcome: ${outcomeLabel(it)}", style = MaterialTheme.typography.labelSmall)
                }
                node.confidence?.let {
                    Text("Confidence: ${(it * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                // 1.2.0: COMMIT's own identity/display fields — same "always readable as text,
                // never a shape/colour-only cue" discipline outcome/confidence already follow.
                node.sha?.let {
                    Text("Commit: ${it.take(12)}", style = MaterialTheme.typography.labelSmall)
                }
                node.repoRef?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (onOpen != null) HyleButton("Open", onClick = onOpen)
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun NodeRadialMenu(node: ThreadGraphNode, anchor: Offset, onOpen: (String) -> Unit, onDetails: () -> Unit, onDismiss: () -> Unit) {
    val target = openTargetId(node)
    val items = buildList {
        if (target != null) {
            add(
                HyleRadialMenuItem(
                    label = "Open",
                    glyph = { tint -> drawCircle(tint, radius = size.minDimension / 3f) },
                    onClick = { onOpen(target) },
                ),
            )
        }
        add(
            HyleRadialMenuItem(
                label = "Details",
                glyph = { tint -> drawRect(tint, topLeft = center * 0.5f, size = size * 0.4f) },
                onClick = onDetails,
            ),
        )
    }
    // Anchored at the room's centre — a Canvas-drawn node moves under pan/zoom, so this fans from
    // a stable point in the room rather than a stale pre-gesture coordinate; the connector threads
    // HyleRadialMenu draws still make "these options came from a long-press" legible without
    // needing a precise per-node anchor.
    HyleRadialMenu(
        visible = true,
        anchor = anchor,
        items = items,
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    val colors = LocalHyleColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (kind in ThreadNodeKind.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(12.dp)) { glyphFor(kind, colors)(this) }
                Spacer(Modifier.width(4.dp))
                Text(kindLabel(kind), style = MaterialTheme.typography.labelSmall, color = colors.textMid)
            }
        }
    }
}

/** Shape (never colour alone — binding constraint 6) + colour per [ThreadNodeKind]. */
private fun glyphFor(kind: ThreadNodeKind, colors: dev.aarso.hyle.theme.HyleColors): DrawScope.() -> Unit = when (kind) {
    ThreadNodeKind.MESSAGE -> { { drawCircle(colors.textMid, radius = size.minDimension / 2f) } }
    ThreadNodeKind.FORK_ROOT -> {
        {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, colors.cyan)
        }
    }
    ThreadNodeKind.SPAWN_ROOT -> { { drawRect(colors.warning, size = size) } }
    ThreadNodeKind.RUN_ROOT -> {
        // A hexagon — distinct from every other filled shape here (circle/triangle/rect/diamond/
        // star/cross), matching this kind's own 'hexagon' G6 built-in type on the deep view.
        {
            val w = size.width; val h = size.height
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.25f, 0f); lineTo(w * 0.75f, 0f); lineTo(w, h / 2f)
                lineTo(w * 0.75f, h); lineTo(w * 0.25f, h); lineTo(0f, h / 2f)
                close()
            }
            drawPath(path, colors.error)
        }
    }
    ThreadNodeKind.MARKER -> {
        {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width / 2f, size.height)
                lineTo(0f, size.height / 2f)
                close()
            }
            drawPath(path, colors.success)
        }
    }
    ThreadNodeKind.DECISION -> {
        // A 5-point star — distinct from MARKER's diamond and DELEGATION's cross. The deep G6
        // view uses its own 'ellipse' built-in for this kind instead (see build-graph-room.js's
        // own comment) — the two surfaces don't share one shape vocabulary 1:1, only "each kind
        // gets its own distinct shape" as the rule both independently satisfy.
        {
            val cx = size.width / 2f; val cy = size.height / 2f
            val outerR = size.minDimension / 2f; val innerR = outerR * 0.42f
            val path = androidx.compose.ui.graphics.Path()
            for (i in 0 until 10) {
                val angle = (Math.PI / 5 * i - Math.PI / 2).toFloat()
                val r = if (i % 2 == 0) outerR else innerR
                val x = cx + r * cos(angle); val y = cy + r * sin(angle)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, colors.textHigh)
        }
    }
    ThreadNodeKind.DELEGATION -> {
        {
            val t = size.minDimension * 0.28f
            drawRect(colors.violet, topLeft = Offset(0f, size.height / 2f - t / 2f), size = androidx.compose.ui.geometry.Size(size.width, t))
            drawRect(colors.violet, topLeft = Offset(size.width / 2f - t / 2f, 0f), size = androidx.compose.ui.geometry.Size(t, size.height))
        }
    }
    ThreadNodeKind.COMMIT -> {
        // 1.2.0, minimal (schema-only work package — full rendering polish is a later work
        // package's job, per this file's own audit history for prior kinds): a diagonal "X"
        // cross, distinct from DELEGATION's upright "+" cross and every other glyph here
        // (circle/triangle/rect/hexagon/diamond/star). colors.outline is otherwise unused in
        // this file, so COMMIT gets its own colour too, not just its own shape.
        {
            val t = size.minDimension * 0.22f
            drawLine(colors.outline, Offset(0f, 0f), Offset(size.width, size.height), t, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(colors.outline, Offset(size.width, 0f), Offset(0f, size.height), t, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/**
 * A small, shape-only badge drawn at a DELEGATION glyph's corner for its
 * [dev.fonebrew.domain.thread.DelegationOutcome] (2026-08-29 audit, gap 2) — a second,
 * non-colour-alone channel alongside the outcome TEXT [NodeDetailsDialog] always shows (binding
 * constraint 6 / WCAG 1.4.1: never colour-only). Neutral [colors.textHigh] on purpose — the
 * differentiator is the badge's SHAPE (hollow ring / filled dot / filled triangle — three
 * genuinely different glyphs), not a colour swap on one shape.
 */
private fun outcomeBadge(
    outcome: dev.fonebrew.domain.thread.DelegationOutcome,
    colors: dev.aarso.hyle.theme.HyleColors,
): DrawScope.() -> Unit = {
    val r = size.minDimension * 0.16f
    val center = Offset(size.width - r * 1.1f, r * 1.1f)
    when (outcome) {
        dev.fonebrew.domain.thread.DelegationOutcome.PENDING ->
            drawCircle(
                colors.textHigh, radius = r, center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.35f),
            )
        dev.fonebrew.domain.thread.DelegationOutcome.KEPT ->
            drawCircle(colors.textHigh, radius = r, center = center)
        dev.fonebrew.domain.thread.DelegationOutcome.REVERTED -> {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(center.x, center.y - r)
                lineTo(center.x + r, center.y + r)
                lineTo(center.x - r, center.y + r)
                close()
            }
            drawPath(path, colors.textHigh)
        }
    }
}

/**
 * The pan/zoom canvas: reuses `LoopRoom.kt`'s `LoopCanvas` shape (dot-grid backdrop + `Canvas`-
 * drawn edges + node `Box`es, each with their own `pointerInput`), with a manual top-left-pivot
 * scale+pan transform ([detectTransformGestures]) in place of `LoopCanvas`'s per-node drag — this
 * map's node positions come from [ThreadMapLayout], not a user's own arrangement.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadMapCanvas(
    graph: ThreadGraph,
    layout: ThreadMapLayout.Layout,
    onTapNode: (String) -> Unit,
    onLongPressNode: (String) -> Unit,
) {
    val colors = LocalHyleColors.current
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val colSpacingPx = with(density) { COL_SPACING.toPx() }
    val rowSpacingPx = with(density) { ROW_SPACING.toPx() }
    val paddingPx = with(density) { CANVAS_PADDING.toPx() }
    val nodeSizePx = with(density) { NODE_SIZE.toPx() }

    fun centerOf(id: String): Offset? {
        val pos = layout.positions[id] ?: return null
        return Offset(paddingPx + colSpacingPx * pos.column, paddingPx + rowSpacingPx * pos.row)
    }

    val contentWidthPx = paddingPx * 2 + colSpacingPx * (layout.columnCount - 1).coerceAtLeast(0)
    val contentHeightPx = paddingPx * 2 + rowSpacingPx * (layout.rowCount - 1).coerceAtLeast(0)
    // 2026-08-29 audit gap 4: looked up per REPLY edge below, for the confidence-weighted stroke —
    // the message-level number itself is only ever readable via a tap (NodeDetailsDialog); this
    // is the always-visible line-weight/opacity channel binding constraint 6 also requires never
    // stand alone (paired with that text, never colour-only or opacity-only by itself).
    val nodesById = remember(graph) { graph.nodes.associateBy { it.id } }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    scale = (scale * zoomChange).coerceIn(0.35f, 3f)
                    pan += panChange
                }
            },
    ) {
        Box(
            Modifier
                .size(with(density) { contentWidthPx.toDp() }, with(density) { contentHeightPx.toDp() })
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = pan.x; translationY = pan.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Dot-grid backdrop — same bloom-near-nodes treatment as LoopCanvas.
                val step = 24.dp.toPx(); val baseR = 1.5.dp.toPx(); val bloom = 150.dp.toPx()
                val centers = graph.nodes.mapNotNull { centerOf(it.id) }
                val cols = (size.width / step).toInt() + 2
                val rows = (size.height / step).toInt() + 2
                for (c in 0..cols) for (r in 0..rows) {
                    val p = Offset(c * step, r * step)
                    val nearest = centers.minOfOrNull { hypot(p.x - it.x, p.y - it.y) } ?: continue
                    val t = (1f - nearest / bloom).coerceIn(0f, 1f)
                    if (t <= 0.02f) continue
                    val e = t * t
                    drawCircle(colors.hairline.copy(alpha = colors.hairline.alpha * (0.15f + 0.85f * e)), baseR * (0.7f + 0.7f * e), p)
                }

                for (edge in graph.edges) {
                    val from = centerOf(edge.from) ?: continue
                    val to = centerOf(edge.to) ?: continue
                    val edgeColor = when (edge.kind) {
                        ThreadEdgeKind.REPLY -> colors.textMid.copy(alpha = 0.55f)
                        ThreadEdgeKind.FORK -> colors.cyan.copy(alpha = 0.7f)
                        ThreadEdgeKind.SPAWN -> colors.warning.copy(alpha = 0.7f)
                        ThreadEdgeKind.LINEAGE -> colors.violet.copy(alpha = 0.5f)
                        ThreadEdgeKind.MARKER_ANCHOR, ThreadEdgeKind.DECISION_ANCHOR, ThreadEdgeKind.DELEGATION_ANCHOR -> colors.hairline
                        // 1.2.0, minimal (schema-only work package — see glyphFor's own note):
                        // colors.outline pairs this edge with the COMMIT node glyph above.
                        ThreadEdgeKind.COMMIT_ANCHOR -> colors.outline.copy(alpha = 0.7f)
                    }
                    // Fixed per audit (binding constraint 6 / WCAG 1.4.1): colour was the ONLY
                    // channel distinguishing edge kinds (FORK and SPAWN were indistinguishable —
                    // same colour, same dash, same everything). Dash pattern is now the redundant
                    // channel, mirroring graph-room.html's own edgeStyleFor (the WP11 G6 bootstrap
                    // this room's own "Deep view" opens already got this right).
                    val pathEffect = when (edge.kind) {
                        ThreadEdgeKind.REPLY, ThreadEdgeKind.FORK -> null
                        ThreadEdgeKind.SPAWN -> PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                        ThreadEdgeKind.LINEAGE -> PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                        ThreadEdgeKind.MARKER_ANCHOR, ThreadEdgeKind.DECISION_ANCHOR, ThreadEdgeKind.DELEGATION_ANCHOR ->
                            PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx()))
                        // A long dash — distinct from every pattern above (solid / 6-4 / 4-3 / 1-3).
                        ThreadEdgeKind.COMMIT_ANCHOR -> PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 2.dp.toPx()))
                    }
                    // A second, non-colour, non-dash channel for FORK vs SPAWN specifically (both
                    // solid otherwise): SPAWN draws fractionally thicker, same distinction the
                    // node glyphs already carry via shape (triangle vs rect).
                    val baseStroke = if (edge.kind == ThreadEdgeKind.SPAWN) 2.2.dp.toPx() else 1.5.dp.toPx()
                    // 2026-08-29 audit gap 4: a REPLY edge into a MESSAGE that captured a
                    // confidence draws thicker + more opaque as that confidence rises — absent
                    // (this turn's engine reported no entropy, or it predates the field) leaves the
                    // edge exactly as before, never a fabricated middle value.
                    val confidence = if (edge.kind == ThreadEdgeKind.REPLY) nodesById[edge.to]?.confidence else null
                    val stroke = confidence?.let { (baseStroke * (0.7f + 1.8f * it.toFloat())) } ?: baseStroke
                    val finalEdgeColor = confidence?.let { edgeColor.copy(alpha = edgeColor.alpha * (0.5f + 0.5f * it.toFloat())) } ?: edgeColor
                    drawLine(finalEdgeColor, from, to, stroke, androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = pathEffect)
                    // COMMIT_ANCHOR included (1.2.0): like REPLY/FORK/SPAWN it is a forward,
                    // causal edge (message/run node -> the commit it minted), unlike the
                    // annotation-pointing-at-a-message *_ANCHOR kinds, which stay arrow-less.
                    if (edge.kind == ThreadEdgeKind.REPLY || edge.kind == ThreadEdgeKind.FORK ||
                        edge.kind == ThreadEdgeKind.SPAWN || edge.kind == ThreadEdgeKind.COMMIT_ANCHOR
                    ) {
                        val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
                        val aLen = 8.dp.toPx().toDouble(); val aAngle = 0.4
                        drawLine(finalEdgeColor, to, Offset((to.x - aLen * cos(angle - aAngle)).toFloat(), (to.y - aLen * sin(angle - aAngle)).toFloat()), stroke)
                        drawLine(finalEdgeColor, to, Offset((to.x - aLen * cos(angle + aAngle)).toFloat(), (to.y - aLen * sin(angle + aAngle)).toFloat()), stroke)
                    }
                }
            }

            for (node in graph.nodes) {
                val c = centerOf(node.id) ?: continue
                // Fixed per audit (desktop-class-kit.md §0 / material-language.md's a11y section):
                // raw pointerInput gave zero visual pressed-state feedback and no accessible name
                // (unlike combinedClickable, plain pointerInput never creates a merged semantics
                // node) — ThreadRail.kt (same WP batch) is meticulous about exactly this for a
                // structurally similar canvas-drawn surface; this brings the graph nodes to parity.
                val interactionSource = remember(node.id) { MutableInteractionSource() }
                Box(
                    Modifier
                        .absoluteOffset { IntOffset((c.x - nodeSizePx / 2).roundToInt(), (c.y - nodeSizePx / 2).roundToInt()) }
                        .size(NODE_SIZE)
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            onClick = { onTapNode(node.id) },
                            onLongClick = { onLongPressNode(node.id) },
                        )
                        .semantics {
                            contentDescription = "${kindLabel(node.kind)}: ${node.label?.takeIf { it.isNotBlank() } ?: "(no label)"}"
                        },
                ) {
                    Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                        glyphFor(node.kind, colors)(this)
                        if (node.kind == ThreadNodeKind.DELEGATION) {
                            node.outcome?.let { outcomeBadge(it, colors)(this) }
                        }
                    }
                }
            }
        }
    }
}
