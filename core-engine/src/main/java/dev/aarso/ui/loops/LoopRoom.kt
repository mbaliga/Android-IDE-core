package dev.aarso.ui.loops

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.aarso.AarsoApp
import dev.aarso.domain.bpmn.BpmnArchive
import dev.aarso.domain.bpmn.BpmnEdge
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNode
import dev.aarso.domain.bpmn.BpmnNodeKind
import dev.aarso.domain.bpmn.Bounds
import dev.aarso.domain.loop.GraphRunResult
import dev.aarso.domain.loop.GraphRunner
import dev.aarso.domain.loop.Loop
import dev.aarso.domain.loop.LoopState
import dev.aarso.domain.model.ModelSpec
import dev.aarso.inference.EngineGenerator
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.hyle.HyleChip
import dev.aarso.ui.hyle.HyleDropdownField
import dev.aarso.ui.hyle.HyleField
import dev.aarso.ui.theme.LocalHyleColors
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DEFAULT_PROPOSER_PROMPT =
    "You are the proposer. Produce the best possible attempt at the objective."
private const val DEFAULT_CRITIC_PROMPT =
    "You are the critic. Find concrete flaws against the objective; begin your reply with APPROVE only if it fully meets it."

/** An editor node: a BPMN kind + free position + (for tasks) its own instructions and model. */
private data class LoopNode(
    val id: String,
    val kind: BpmnNodeKind,
    val label: String,
    val role: String = "",
    val xPx: Float,
    val yPx: Float,
    val systemPrompt: String = "",
    val modelId: String? = null,
)

/** A connector; [label] drives gateway branching ("approve" / "refine" / "else"). */
private data class LoopEdge(val from: String, val to: String, val label: String? = null) {
    val accent: Boolean get() = label != null && !label.equals("else", ignoreCase = true)
}

private enum class NodeStatus { IDLE, ACTIVE, DONE }

private fun loopStateLabel(s: LoopState) = when (s) {
    LoopState.RUNNING -> "Running"
    LoopState.RETIRED -> "Retired"
    LoopState.UNUSED -> "Draft"
}

private fun isEvent(kind: BpmnNodeKind) =
    kind == BpmnNodeKind.START_EVENT || kind == BpmnNodeKind.END_EVENT

/** Editor graph → BPMN (positions + per-node prompt/model travel in extension elements). */
private fun toBpmnGraph(id: String, name: String, nodes: List<LoopNode>, edges: List<LoopEdge>): BpmnGraph =
    BpmnGraph(
        id = id, name = name,
        nodes = nodes.map { n ->
            BpmnNode(
                id = n.id, kind = n.kind, name = n.label,
                bounds = Bounds(n.xPx.toDouble(), n.yPx.toDouble()),
                ext = buildMap {
                    if (n.systemPrompt.isNotBlank()) put("systemPrompt", n.systemPrompt)
                    n.modelId?.let { put("model", it) }
                    if (n.role.isNotBlank()) put("role", n.role)
                },
            )
        },
        edges = edges.mapIndexed { i, e -> BpmnEdge(id = "edge-$i", sourceId = e.from, targetId = e.to, name = e.label) },
    )

private fun fromBpmnNodes(g: BpmnGraph): List<LoopNode> = g.nodes.map { b ->
    LoopNode(
        id = b.id, kind = b.kind, label = b.name, role = b.ext["role"].orEmpty(),
        xPx = b.bounds.x.toFloat(), yPx = b.bounds.y.toFloat(),
        systemPrompt = b.ext["systemPrompt"].orEmpty(), modelId = b.ext["model"],
    )
}

private fun fromBpmnEdges(g: BpmnGraph): List<LoopEdge> =
    g.edges.map { LoopEdge(it.sourceId, it.targetId, it.name) }

/**
 * The Loop editor: a free-form **graph** editor on a dot-grid canvas (docs/design/workflow-builder.md).
 * Drag a node to move it; **long-press the canvas to add** a node (Task / Gateway / End); **tap** a
 * task/gateway to edit its name, instructions and model; **long-press a node** for delete / connect.
 * Connect is tap-to-connect (long-press → "Connect from here" → tap the target). The graph runs via
 * [GraphRunner] (arbitrary graphs, gateway branching on edge labels) and saves as standard BPMN 2.0.
 * Generation/run is owner-verified — no model in CI.
 */
@Composable
fun LoopRoom(onClose: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AarsoApp).container
    val runnable = remember { container.modelRegistry.allSpecs().filter { container.engineProvider.isRunnable(it) } }
    val density = LocalDensity.current.density
    val store = container.loopStore
    val savedLoops by store.loops.collectAsState()

    val nodes = remember {
        mutableStateListOf(
            LoopNode("start", BpmnNodeKind.START_EVENT, "Start", xPx = 40f * density, yPx = 150f * density),
            LoopNode("proposer", BpmnNodeKind.TASK, "Proposer", "proposer", 150f * density, 70f * density, DEFAULT_PROPOSER_PROMPT, runnable.firstOrNull()?.id),
            LoopNode("critic", BpmnNodeKind.TASK, "Critic", "critic", 360f * density, 150f * density, DEFAULT_CRITIC_PROMPT, runnable.getOrNull(1)?.id ?: runnable.firstOrNull()?.id),
            LoopNode("gate", BpmnNodeKind.EXCLUSIVE_GATEWAY, "Approved?", xPx = 560f * density, yPx = 150f * density),
            LoopNode("end", BpmnNodeKind.END_EVENT, "End", xPx = 700f * density, yPx = 250f * density),
        )
    }
    val edges = remember {
        mutableStateListOf(
            LoopEdge("start", "proposer"),
            LoopEdge("proposer", "critic"),
            LoopEdge("critic", "gate"),
            LoopEdge("gate", "end", "approve"),
            LoopEdge("gate", "proposer", "refine"),
        )
    }

    var objective by remember { mutableStateOf("") }
    var loopId by remember { mutableStateOf<String?>(null) }
    var loopName by remember { mutableStateOf("Untitled loop") }

    var running by remember { mutableStateOf(false) }
    var graphResult by remember { mutableStateOf<GraphRunResult?>(null) }
    var ranNodeIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var runError by remember { mutableStateOf<String?>(null) }
    var savedNote by remember { mutableStateOf<String?>(null) }

    var configNodeId by remember { mutableStateOf<String?>(null) }
    var menuNodeId by remember { mutableStateOf<String?>(null) }
    var addAt by remember { mutableStateOf<Offset?>(null) }
    var connectingFrom by remember { mutableStateOf<String?>(null) }
    var pendingEdge by remember { mutableStateOf<Pair<String, String>?>(null) } // gateway edge awaiting a label
    var showSave by remember { mutableStateOf(false) }
    var showLoad by remember { mutableStateOf(false) }
    var syncNote by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val colors = LocalHyleColors.current

    fun nodeById(id: String) = nodes.firstOrNull { it.id == id }
    fun moveNode(id: String, x: Float, y: Float) {
        val i = nodes.indexOfFirst { it.id == id }
        if (i >= 0) nodes[i] = nodes[i].copy(xPx = x, yPx = y)
    }
    fun deleteNode(id: String) {
        nodes.removeAll { it.id == id }
        edges.removeAll { it.from == id || it.to == id }
    }
    fun addEdge(from: String, to: String, label: String? = null) {
        if (from == to) return
        if (edges.any { it.from == from && it.to == to }) return
        edges.add(LoopEdge(from, to, label))
    }
    fun onTapNode(id: String) {
        val from = connectingFrom
        if (from != null) {
            connectingFrom = null
            if (from != id) {
                if (nodeById(from)?.let { it.kind.name.contains("GATEWAY") } == true) pendingEdge = from to id
                else addEdge(from, id)
            }
        } else if (nodeById(id)?.let { !isEvent(it.kind) } == true) {
            configNodeId = id
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text("‹ Back") }
                    Text(loopName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showLoad = true }) { Text("Loops") }
                        TextButton(onClick = { showSave = true }, enabled = objective.isNotBlank()) { Text("Save") }
                        if (running) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        HyleButton(
                            "Run",
                            enabled = !running && objective.isNotBlank() && runnable.isNotEmpty() &&
                                nodes.any { it.kind == BpmnNodeKind.START_EVENT },
                            onClick = {
                                running = true; runError = null; graphResult = null; ranNodeIds = emptySet()
                                scope.launch {
                                    runCatching {
                                        val cache = HashMap<String, EngineGenerator>()
                                        fun genFor(spec: ModelSpec) = cache.getOrPut(spec.id) {
                                            EngineGenerator(container.engineProvider.engineFor(spec)!!, spec.modelPath)
                                        }
                                        val fallback = runnable.first()
                                        val graph = toBpmnGraph(loopId ?: "loop", loopName, nodes.toList(), edges.toList())
                                        GraphRunner(generatorFor = { bn ->
                                            val spec = bn.ext["model"]?.let { mid -> runnable.firstOrNull { it.id == mid } } ?: fallback
                                            genFor(spec)
                                        }).run(graph = graph, objective = objective)
                                    }.fold(
                                        { graphResult = it; ranNodeIds = it.steps.map { s -> s.nodeId }.toSet() },
                                        { runError = it.message },
                                    )
                                    running = false
                                }
                            },
                        )
                    }
                }
                savedNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
                }
                HorizontalDivider()

                if (runnable.isEmpty()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            "Download a model (Models room) or add a cloud provider (Settings → Text) to run loops.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    val status: Map<String, NodeStatus> = when {
                        running -> emptyMap()
                        graphResult != null -> ranNodeIds.associateWith { NodeStatus.DONE }
                        else -> emptyMap()
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LoopCanvas(
                            nodes = nodes,
                            edges = edges,
                            status = status,
                            connectingFrom = connectingFrom,
                            dotColor = colors.hairline.copy(alpha = 0.35f),
                            edgeColor = colors.textMid.copy(alpha = 0.55f),
                            accentEdge = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            onMove = ::moveNode,
                            onTapNode = ::onTapNode,
                            onLongPressNode = { menuNodeId = it },
                            onAddAt = { o -> if (connectingFrom != null) connectingFrom = null else addAt = o },
                        )
                        val caption = when {
                            connectingFrom != null -> "tap a node to connect from “${nodeById(connectingFrom!!)?.label}” · tap empty to cancel"
                            running -> "running…"
                            graphResult != null -> "ran ${graphResult!!.steps.size} step(s) · ${graphResult!!.stoppedBecause}"
                            else -> "long-press canvas to add · tap a node to edit · long-press for menu"
                        }
                        Text(
                            caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (running || graphResult != null || connectingFrom != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        )
                    }

                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HyleField(objective, { objective = it }, label = "Objective", mandatory = true, singleLine = false, modifier = Modifier.fillMaxWidth())
                    }
                }

                if (graphResult != null || runError != null) {
                    HorizontalDivider()
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        runError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        graphResult?.steps?.forEach { step ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "${step.index + 1}. ${nodeById(step.nodeId)?.label ?: step.role}",
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(step.output, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Add-node palette (long-press canvas) ──────────────────────────────────
    addAt?.let { at ->
        AddNodeDialog(
            onDismiss = { addAt = null },
            onAdd = { kind ->
                val id = "n-${UUID.randomUUID().toString().take(6)}"
                val label = when (kind) {
                    BpmnNodeKind.END_EVENT -> "End"
                    BpmnNodeKind.EXCLUSIVE_GATEWAY -> "Gateway"
                    else -> "Task"
                }
                nodes.add(LoopNode(id, kind, label, xPx = at.x, yPx = at.y))
                addAt = null
                if (kind == BpmnNodeKind.TASK) configNodeId = id
            },
        )
    }

    // ── Node menu (long-press a node) ─────────────────────────────────────────
    menuNodeId?.let { id ->
        val node = nodeById(id)
        NodeMenuDialog(
            label = node?.label ?: id,
            canEdit = node != null && !isEvent(node.kind),
            onDismiss = { menuNodeId = null },
            onEdit = { menuNodeId = null; configNodeId = id },
            onConnect = { menuNodeId = null; connectingFrom = id },
            onDelete = { menuNodeId = null; deleteNode(id) },
        )
    }

    // ── Edge label for a gateway branch ───────────────────────────────────────
    pendingEdge?.let { (from, to) ->
        EdgeLabelDialog(
            onDismiss = { pendingEdge = null },
            onPick = { label -> addEdge(from, to, label); pendingEdge = null },
        )
    }

    // ── Per-node config (tap a task/gateway) ──────────────────────────────────
    configNodeId?.let { id ->
        val node = nodeById(id) ?: return@let
        NodeConfigDialog(
            node = node,
            runnable = runnable,
            onDismiss = { configNodeId = null },
            onSave = { newName, newPrompt, newModel ->
                val i = nodes.indexOfFirst { it.id == id }
                if (i >= 0) nodes[i] = nodes[i].copy(label = newName.ifBlank { nodes[i].label }, systemPrompt = newPrompt, modelId = newModel)
                configNodeId = null
            },
        )
    }

    if (showSave) {
        SaveLoopDialog(
            initialName = loopName,
            onDismiss = { showSave = false },
            onSave = { name ->
                val id = loopId ?: UUID.randomUUID().toString()
                val xml = BpmnArchive.write(toBpmnGraph(id, name, nodes.toList(), edges.toList()))
                val now = System.currentTimeMillis()
                val existing = store.get(id)
                store.save(
                    Loop(
                        id = id, name = name, bpmnXml = xml,
                        state = existing?.state ?: LoopState.UNUSED,
                        createdAt = existing?.createdAt ?: now, updatedAt = now, lastRunAt = existing?.lastRunAt,
                    ),
                )
                loopId = id; loopName = name; savedNote = "Saved “$name” (BPMN)"; showSave = false
            },
        )
    }

    if (showLoad) {
        LoadLoopDialog(
            loops = savedLoops,
            onDismiss = { showLoad = false },
            onDelete = { store.delete(it) },
            onDuplicate = { store.duplicate(it) },
            syncNote = syncNote,
            onPush = {
                syncNote = "pushing…"
                scope.launch {
                    container.operationWorker.enqueue("loop.push", "")
                    container.operationWorker.drainOnce()
                    val q = container.operationQueueStore.queue.value
                    syncNote = when {
                        q.hasFailures -> "push parked (retry later): ${q.pending().lastOrNull()?.lastError ?: ""}"
                        q.pending().isNotEmpty() -> "push queued — will sync when online"
                        else -> "pushed to Git"
                    }
                }
            },
            onPull = {
                syncNote = "pulling…"
                scope.launch { container.loopSyncRepo.pull().fold({ syncNote = "pulled $it loop(s) from Git" }, { syncNote = it.message }) }
            },
            onPick = { loop ->
                loop.bpmnXml?.let { xml ->
                    runCatching { BpmnArchive.read(xml) }.getOrNull()?.let { g ->
                        nodes.clear(); nodes.addAll(fromBpmnNodes(g))
                        edges.clear(); edges.addAll(fromBpmnEdges(g))
                        loopId = loop.id; loopName = loop.name; savedNote = "Loaded “${loop.name}”"
                    }
                }
                showLoad = false
            },
        )
    }
}

@Composable
private fun LoopCanvas(
    nodes: List<LoopNode>,
    edges: List<LoopEdge>,
    status: Map<String, NodeStatus>,
    connectingFrom: String?,
    dotColor: Color,
    edgeColor: Color,
    accentEdge: Color,
    onMove: (id: String, xPx: Float, yPx: Float) -> Unit,
    onTapNode: (id: String) -> Unit,
    onLongPressNode: (id: String) -> Unit,
    onAddAt: (Offset) -> Unit,
) {
    val bgColor = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val nodeWidthDp = 148.dp
    val nodeHeightDp = 62.dp
    val hwPx = with(density) { nodeWidthDp.toPx() } / 2f
    val hhPx = with(density) { nodeHeightDp.toPx() } / 2f
    val startRPx = with(density) { 24.dp.toPx() }
    fun cx(n: LoopNode) = n.xPx + if (isEvent(n.kind)) startRPx else hwPx
    fun cy(n: LoopNode) = n.yPx + if (isEvent(n.kind)) startRPx else hhPx

    Box(
        Modifier.fillMaxSize().background(bgColor)
            // Long-press empty canvas → add a node there; tap empty cancels a pending connect.
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onAddAt(it) }, onTap = { if (connectingFrom != null) onAddAt(it) })
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 24.dp.toPx(); val baseR = 1.5.dp.toPx(); val bloom = 150.dp.toPx()
            val centers = nodes.map { Offset(cx(it), cy(it)) }
            val cols = (size.width / step).toInt() + 2; val rows = (size.height / step).toInt() + 2
            for (c in 0..cols) for (r2 in 0..rows) {
                val p = Offset(c * step, r2 * step)
                val nearest = centers.minOfOrNull { hypot(p.x - it.x, p.y - it.y) } ?: continue
                val t = (1f - nearest / bloom).coerceIn(0f, 1f)
                if (t <= 0.02f) continue
                val e = t * t
                drawCircle(dotColor.copy(alpha = dotColor.alpha * (0.15f + 0.85f * e)), baseR * (0.7f + 0.7f * e), p)
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            for (edge in edges) {
                val from = nodes.find { it.id == edge.from } ?: continue
                val to = nodes.find { it.id == edge.to } ?: continue
                val sx = cx(from); val sy = cy(from); val tx = cx(to); val ty = cy(to)
                val color = if (edge.accent) accentEdge else edgeColor
                val stroke = 1.5.dp.toPx()
                drawLine(color, Offset(sx, sy), Offset(tx, ty), stroke, StrokeCap.Round)
                val angle = atan2((ty - sy).toDouble(), (tx - sx).toDouble())
                val aLen = 9.dp.toPx().toDouble(); val aAngle = 0.4
                drawLine(color, Offset(tx, ty), Offset((tx - aLen * cos(angle - aAngle)).toFloat(), (ty - aLen * sin(angle - aAngle)).toFloat()), stroke, StrokeCap.Round)
                drawLine(color, Offset(tx, ty), Offset((tx - aLen * cos(angle + aAngle)).toFloat(), (ty - aLen * sin(angle + aAngle)).toFloat()), stroke, StrokeCap.Round)
            }
        }

        // Edge labels (approve/refine/…) as overlays at the segment midpoint.
        for (edge in edges) {
            val label = edge.label ?: continue
            val from = nodes.find { it.id == edge.from } ?: continue
            val to = nodes.find { it.id == edge.to } ?: continue
            val mx = (cx(from) + cx(to)) / 2f; val my = (cy(from) + cy(to)) / 2f
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = accentEdge,
                modifier = Modifier.absoluteOffset { IntOffset(mx.roundToInt(), my.roundToInt()) }
                    .background(bgColor).padding(horizontal = 3.dp),
            )
        }

        for (node in nodes) {
            key(node.id) {
                var dxPx by remember(node.id) { mutableFloatStateOf(0f) }
                var dyPx by remember(node.id) { mutableFloatStateOf(0f) }
                Box(
                    Modifier
                        .absoluteOffset { IntOffset((node.xPx + dxPx).roundToInt(), (node.yPx + dyPx).roundToInt()) }
                        .pointerInput(node.id) {
                            detectTapGestures(onTap = { onTapNode(node.id) }, onLongPress = { onLongPressNode(node.id) })
                        }
                        .pointerInput(node.id) {
                            detectDragGestures(
                                onDragEnd = { onMove(node.id, node.xPx + dxPx, node.yPx + dyPx); dxPx = 0f; dyPx = 0f },
                                onDrag = { _, d -> dxPx += d.x; dyPx += d.y },
                            )
                        },
                ) {
                    val st = if (node.id == connectingFrom) NodeStatus.ACTIVE else status[node.id] ?: NodeStatus.IDLE
                    when (node.kind) {
                        BpmnNodeKind.START_EVENT -> EventCircle(MaterialTheme.colorScheme.primary, "▶", st)
                        BpmnNodeKind.END_EVENT -> EventCircle(MaterialTheme.colorScheme.outline, "■", st)
                        BpmnNodeKind.EXCLUSIVE_GATEWAY, BpmnNodeKind.PARALLEL_GATEWAY, BpmnNodeKind.INCLUSIVE_GATEWAY ->
                            GatewayDiamond(node.label, st)
                        else -> TaskCard(node, nodeWidthDp, st)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCircle(color: Color, icon: String, status: NodeStatus) {
    val ring = statusColor(status)
    Box(
        Modifier.size(48.dp).background(color, CircleShape)
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Text(icon, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun GatewayDiamond(label: String, status: NodeStatus) {
    val ring = statusColor(status)
    val colors = LocalHyleColors.current
    Box(
        Modifier.size(64.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .border(if (ring != null) 2.dp else 1.dp, ring ?: colors.hairline, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("◇ $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 2)
    }
}

@Composable
private fun TaskCard(node: LoopNode, widthDp: androidx.compose.ui.unit.Dp, status: NodeStatus) {
    val colors = LocalHyleColors.current
    val ring = statusColor(status)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(if (ring != null) 2.dp else 1.dp, ring ?: colors.hairline),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.width(widthDp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(node.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            val sub = node.modelId?.substringAfter(':') ?: node.role.ifBlank { "task" }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            val foot = when (status) {
                NodeStatus.ACTIVE -> "● running"
                NodeStatus.DONE -> "✓ done"
                NodeStatus.IDLE -> "⠿ tap to edit"
            }
            Text(foot, style = MaterialTheme.typography.labelSmall, color = (ring ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = if (status == NodeStatus.IDLE) 0.5f else 1f))
        }
    }
}

@Composable
private fun statusColor(status: NodeStatus): Color? {
    val colors = LocalHyleColors.current
    return when (status) {
        NodeStatus.ACTIVE -> MaterialTheme.colorScheme.primary
        NodeStatus.DONE -> colors.success
        NodeStatus.IDLE -> null
    }
}

@Composable
private fun AddNodeDialog(onDismiss: () -> Unit, onAdd: (BpmnNodeKind) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add a node", style = MaterialTheme.typography.titleMedium)
                HyleButton("Task — an AI step", onClick = { onAdd(BpmnNodeKind.TASK) })
                HyleButton("Gateway — a branch", onClick = { onAdd(BpmnNodeKind.EXCLUSIVE_GATEWAY) })
                HyleButton("End", onClick = { onAdd(BpmnNodeKind.END_EVENT) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun NodeMenuDialog(label: String, canEdit: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onConnect: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                HyleButton("Connect from here →", onClick = onConnect)
                if (canEdit) HyleButton("Edit", onClick = onEdit)
                HyleButton("Delete", onClick = onDelete)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun EdgeLabelDialog(onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Branch label", style = MaterialTheme.typography.titleMedium)
                Text("When this gateway is reached, which output takes this edge?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HyleButton("approve — when the last step begins APPROVE", onClick = { onPick("approve") })
                HyleButton("refine — otherwise", onClick = { onPick("refine") })
                HyleButton("else — default branch", onClick = { onPick("else") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun NodeConfigDialog(node: LoopNode, runnable: List<ModelSpec>, onDismiss: () -> Unit, onSave: (name: String, prompt: String, modelId: String?) -> Unit) {
    var n by remember { mutableStateOf(node.label) }
    var p by remember { mutableStateOf(node.systemPrompt) }
    var model by remember { mutableStateOf(node.modelId) }
    val isTask = !isEvent(node.kind) && !node.kind.name.contains("GATEWAY")
    val options = listOf("Default model") + runnable.map { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Edit ${node.label}", style = MaterialTheme.typography.titleMedium)
                HyleField(n, { n = it }, label = "Name", modifier = Modifier.fillMaxWidth())
                if (isTask) {
                    HyleField(p, { p = it }, label = "Instructions (system prompt)", singleLine = false, modifier = Modifier.fillMaxWidth())
                    HyleDropdownField(
                        value = model?.let { id -> runnable.firstOrNull { it.id == id }?.let { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName } } ?: "Default model",
                        options = options,
                        onSelect = { idx -> model = if (idx == 0) null else runnable[idx - 1].id },
                        label = "Model",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    HyleButton("Done", onClick = { onSave(n, p, model) })
                }
            }
        }
    }
}

@Composable
private fun SaveLoopDialog(initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Save loop", style = MaterialTheme.typography.titleMedium)
                HyleField(name, { name = it }, label = "Name", modifier = Modifier.fillMaxWidth())
                Text("Stored as BPMN 2.0 on this device (syncs to your Git host when wired).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    HyleButton("Save", onClick = { onSave(name.ifBlank { "Untitled loop" }) })
                }
            }
        }
    }
}

@Composable
private fun LoadLoopDialog(
    loops: List<Loop>,
    onDismiss: () -> Unit,
    onPick: (Loop) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    syncNote: String?,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Loops", style = MaterialTheme.typography.titleMedium)
                var stateTab by remember { mutableStateOf(LoopState.UNUSED) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HyleChip(stateTab == LoopState.RUNNING, { stateTab = LoopState.RUNNING }, "Running")
                    HyleChip(stateTab == LoopState.RETIRED, { stateTab = LoopState.RETIRED }, "Retired")
                    HyleChip(stateTab == LoopState.UNUSED, { stateTab = LoopState.UNUSED }, "Drafts")
                }
                val shown = loops.filter { it.state == stateTab }
                if (shown.isEmpty()) {
                    Text(
                        when (stateTab) {
                            LoopState.RUNNING -> "No running loops."
                            LoopState.RETIRED -> "No retired loops."
                            LoopState.UNUSED -> "No drafts yet — build one and tap Save. Only drafts are editable."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (loop in shown) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().clickable { onPick(loop) }) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(loop.name.ifBlank { "Untitled loop" }, style = MaterialTheme.typography.bodyMedium)
                                        Text(loopStateLabel(loop.state), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { onPick(loop) }) { Text(if (loop.state == LoopState.UNUSED) "Edit" else "View") }
                                        TextButton(onClick = { onDuplicate(loop.id) }) { Text("Duplicate") }
                                        TextButton(onClick = { onDelete(loop.id) }) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("Your loops live as .bpmn in your Git repo (loops/).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                syncNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onPush) { Text("Push to Git") }
                        TextButton(onClick = onPull) { Text("Pull from Git") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
