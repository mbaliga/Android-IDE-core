package dev.fonebrew.ui.loops

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.aarso.hyle.component.HyleContextMenu
import dev.fonebrew.FonebrewApp
import dev.fonebrew.domain.bpmn.BpmnArchive
import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.bpmn.Bounds
import dev.fonebrew.domain.loop.GraphRunLedger
import dev.fonebrew.domain.loop.GraphRunLog
import dev.fonebrew.domain.loop.GraphRunResult
import dev.fonebrew.domain.loop.GraphRunner
import dev.fonebrew.domain.loop.GraphStep
import dev.fonebrew.domain.loop.Loop
import dev.fonebrew.domain.loop.LoopBudget
import dev.fonebrew.domain.loop.LoopParams
import dev.fonebrew.domain.loop.LoopState
import dev.fonebrew.domain.loop.RecordingGatewayPolicy
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.thread.DelegationKind
import dev.fonebrew.inference.EngineGenerator
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleCard
import dev.aarso.hyle.cells.HyleChip
import dev.aarso.hyle.cells.HyleDropdownField
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.theme.LocalHyleColors
import kotlinx.coroutines.Job
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

/** Icon+label for a run's stop reason (CORE_PHASES.md P3 "summary row") — never red (§1.4). */
private fun stopLabel(reason: String): Pair<String, String> = when {
    reason == "reached end" -> "✓" to "Reached end"
    reason == "cancelled" -> "⏹" to "Stopped"
    reason.startsWith("hit step cap") -> "⏹" to "Step cap"
    reason == "budget:tokens" -> "⏸" to "Token budget reached"
    reason == "budget:steps" -> "⏸" to "Step budget reached"
    reason == "budget:wall" -> "⏸" to "Time budget reached"
    reason.startsWith("missing params:") -> "⚠" to reason.removePrefix("missing params:").trim().let { "Missing: $it" }
    else -> "⚠" to reason
}

/** Role/model, tokens (with an "est." marker when not provider-authoritative), and duration —
 *  the live step list's per-row metadata (CORE_PHASES.md P3 "Live run view"). */
private fun stepMeta(step: GraphStep): String = buildString {
    append(step.model ?: step.role)
    val tokens = step.tokensIn?.let { tin -> step.tokensOut?.let { tout -> tin + tout } }
    if (tokens != null) {
        append(" · ").append(tokens).append(if (step.estimated) " tok (est.)" else " tok")
    }
    append(" · ").append(step.durationMs).append("ms")
}

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
    /** Everything in a loaded node's BPMN `ext` this editor doesn't have a named field for —
     *  chiefly a distilled loop's start-event provenance (source/pattern/distilledBy/
     *  distilledOn/summary, see [dev.fonebrew.domain.loop.Distiller]). Carried through untouched
     *  so editing and re-saving a distilled loop doesn't silently drop where it came from. */
    val provenanceExt: Map<String, String> = emptyMap(),
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
private val NAMED_EXT_KEYS = setOf("systemPrompt", "model", "role")

private fun toBpmnGraph(id: String, name: String, nodes: List<LoopNode>, edges: List<LoopEdge>): BpmnGraph =
    BpmnGraph(
        id = id, name = name,
        nodes = nodes.map { n ->
            BpmnNode(
                id = n.id, kind = n.kind, name = n.label,
                bounds = Bounds(n.xPx.toDouble(), n.yPx.toDouble()),
                // Named fields first, then whatever carried through unrecognised (a distilled
                // loop's provenance) — named fields win if a key somehow collides.
                ext = n.provenanceExt + buildMap {
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
        provenanceExt = b.ext.filterKeys { it !in NAMED_EXT_KEYS },
    )
}

private fun fromBpmnEdges(g: BpmnGraph): List<LoopEdge> =
    g.edges.map { LoopEdge(it.sourceId, it.targetId, it.name) }

/**
 * The Loop editor: a free-form **graph** editor on a dot-grid canvas (docs/design/workflow-builder.md).
 * Drag a node to move it; **long-press the canvas to add** a node (Task / Gateway / End) via a
 * radial fan menu anchored at the touch point; **tap** a task/gateway to edit its name,
 * instructions and model; **long-press a node** for its own radial fan (Connect / Edit / Delete).
 * Connect is tap-to-connect (radial → Connect → tap the target). The graph runs via [GraphRunner]
 * (arbitrary graphs, gateway branching on edge labels) and saves as standard BPMN 2.0.
 * Generation/run is owner-verified — no model in CI.
 */
@Composable
fun LoopRoom(onClose: () -> Unit) {
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
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
    // P3: run sheet (params + budget) + live streaming step list + a cancellable run job.
    var showRunSheet by remember { mutableStateOf(false) }
    var liveSteps by remember { mutableStateOf<List<GraphStep>>(emptyList()) }
    var runBudget by remember { mutableStateOf<LoopBudget?>(null) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var loggedNote by remember { mutableStateOf<String?>(null) }

    var configNodeId by remember { mutableStateOf<String?>(null) }
    var menuNodeId by remember { mutableStateOf<String?>(null) }
    var addAt by remember { mutableStateOf<Offset?>(null) }
    var connectingFrom by remember { mutableStateOf<String?>(null) }
    var pendingEdge by remember { mutableStateOf<Pair<String, String>?>(null) } // gateway edge awaiting a label
    var showSave by remember { mutableStateOf(false) }
    var showLoad by remember { mutableStateOf(false) }
    var showDistill by remember { mutableStateOf(false) }
    var syncNote by remember { mutableStateOf<String?>(null) }
    var showExportPackage by remember { mutableStateOf(false) }
    var showImportPackage by remember { mutableStateOf(false) }
    var packageNote by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val colors = LocalHyleColors.current

    fun loadLoop(loop: Loop) {
        loop.bpmnXml?.let { xml ->
            runCatching { BpmnArchive.read(xml) }.getOrNull()?.let { g ->
                nodes.clear(); nodes.addAll(fromBpmnNodes(g))
                edges.clear(); edges.addAll(fromBpmnEdges(g))
                loopId = loop.id; loopName = loop.name; savedNote = "Loaded “${loop.name}”"
            }
        }
    }
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

    /** Starts the run (CORE_PHASES.md P3): streams each [GraphStep] into [liveSteps] via
     *  `onStep`, then — win, budget-stopped, or cancelled alike — tree-logs the run
     *  ([GraphRunLog]) and writes its ledger rows ([GraphRunLedger]), tagged `surface = "loop"`
     *  so they're legible apart from Chat usage. A refuse-to-start (missing params) or an
     *  immediate structural stop (no start event) writes nothing — there's no step to log.
     *  Also records every gateway auto-choice ([RecordingGatewayPolicy], WP8, owner decision 2's
     *  GATEWAY_AUTO surface) and brackets the whole run with [dev.fonebrew.data.BackgroundJobs]
     *  so it shows up wherever the app surfaces in-flight background work. */
    fun startRun(params: Map<String, String>, budget: LoopBudget?) {
        running = true; runError = null; graphResult = null; ranNodeIds = emptySet()
        liveSteps = emptyList(); runBudget = budget; loggedNote = null
        runJob = scope.launch {
            val jobId = container.backgroundJobs.start(loopName.ifBlank { "Loop" }, "loop")
            runCatching {
                val cache = HashMap<String, EngineGenerator>()
                fun genFor(spec: ModelSpec) = cache.getOrPut(spec.id) {
                    EngineGenerator(container.engineProvider.engineFor(spec)!!, spec.modelPath)
                }
                val fallback = runnable.first()
                val graph = toBpmnGraph(loopId ?: "loop", loopName, nodes.toList(), edges.toList())
                // THREAD_TOPOLOGY_PLAN.md WP8: capture every gateway auto-choice this run makes
                // (owner decision 2's GATEWAY_AUTO surface) — see RecordingGatewayPolicy's KDoc
                // for why persisting happens below, after the run, rather than mid-choose.
                val recordingPolicy = RecordingGatewayPolicy()
                GraphRunner(
                    generatorFor = { bn ->
                        val spec = bn.ext["model"]?.let { mid -> runnable.firstOrNull { it.id == mid } } ?: fallback
                        genFor(spec)
                    },
                    gatewayPolicy = recordingPolicy,
                ).run(
                    graph = graph,
                    objective = objective,
                    params = params,
                    budget = budget,
                    onStep = { step -> liveSteps = liveSteps + step },
                ) to recordingPolicy
            }.fold(
                { (result, recordingPolicy) ->
                    graphResult = result
                    ranNodeIds = result.steps.map { it.nodeId }.toSet()
                    liveSteps = result.steps
                    if (result.steps.isNotEmpty()) {
                        val runId = UUID.randomUUID().toString()
                        val treeNodes = GraphRunLog.toNodes(
                            objective = objective, result = result, loopRunId = runId, loopId = loopId,
                            now = System.currentTimeMillis(), idGen = { UUID.randomUUID().toString() },
                        )
                        treeNodes.forEach { container.repository.insert(it) }
                        val entries = GraphRunLedger.toEntries(
                            result = result, treeNodes = treeNodes, loopId = loopId, runId = runId,
                            projectId = null, timestampMillis = System.currentTimeMillis(),
                        )
                        entries.forEach { container.ledgerStore.append(it) }
                        loggedNote = "Logged ${result.steps.size} step(s) to Tree · run $runId"
                    }
                    for (choice in recordingPolicy.recorded) {
                        container.delegationRecorder.record(
                            kind = DelegationKind.GATEWAY_AUTO,
                            chosenRef = choice.chosenEdgeRef,
                            alternatives = choice.alternativeRefs,
                        )
                    }
                    container.backgroundJobs.finish(jobId)
                },
                { runError = it.message; container.backgroundJobs.finish(jobId, failed = true) },
            )
            running = false
            runJob = null
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
                        TextButton(onClick = { showDistill = true }, enabled = runnable.isNotEmpty()) { Text("Distill…") }
                        TextButton(onClick = { showImportPackage = true }) { Text("Import…") }
                        TextButton(onClick = { showExportPackage = true }, enabled = nodes.isNotEmpty()) { Text("Export…") }
                        TextButton(onClick = { showSave = true }, enabled = objective.isNotBlank()) { Text("Save") }
                        if (running) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { runJob?.cancel() }) { Text("Stop") }
                        }
                        HyleButton(
                            "Run…",
                            enabled = !running && objective.isNotBlank() && runnable.isNotEmpty() &&
                                nodes.any { it.kind == BpmnNodeKind.START_EVENT },
                            onClick = { showRunSheet = true },
                        )
                    }
                }
                savedNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
                }
                packageNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
                }
                // Import provenance (mirrors the distillation banner below): an imported package's
                // identity travels in the start event's ext map — the same carrier distillation
                // already uses — so it survives edit/re-save without a second storage mechanism.
                nodes.firstOrNull { it.kind == BpmnNodeKind.START_EVENT }?.provenanceExt
                    ?.takeIf { it.containsKey("importedLoopId") }
                    ?.let { prov ->
                        Text(
                            "Imported from “${prov["importedLoopId"]}” v${prov["importedSemanticVersion"]} " +
                                "(${prov["importedSignatureState"]?.lowercase()?.replace('_', ' ')}) " +
                                "on ${prov["importedOn"]}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                // Provenance surfacing (docs/design/loop-distillation.md step 5): a distilled
                // loop's start-event carries who/what/when in its ext map, preserved end to end
                // by LoopNode.provenanceExt — shown here so influence stays visible, never a
                // silent black box.
                nodes.firstOrNull { it.kind == BpmnNodeKind.START_EVENT }?.provenanceExt
                    ?.takeIf { it.containsKey("distilledBy") }
                    ?.let { prov ->
                        Text(
                            "Distilled from ${prov["source"] ?: "a source"} by ${prov["distilledBy"]} " +
                                "on ${prov["distilledOn"]} — review before running.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
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
                            running -> "running… ${liveSteps.size} step(s)"
                            graphResult != null -> "ran ${graphResult!!.steps.size} step(s) · ${stopLabel(graphResult!!.stoppedBecause).second}"
                            else -> "long-press canvas to add · tap a node to edit · long-press for menu"
                        }
                        Text(
                            caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (running || graphResult != null || connectingFrom != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        )

                        // Radial fans, anchored at the point that was actually long-pressed —
                        // in THIS Box, the same coordinate space LoopCanvas measures node
                        // positions in, not a dialog centred wherever the platform likes
                        // (owner ask, 2026-07-27). Both must live here, not elsewhere in the
                        // tree, or `anchor` wouldn't line up with the canvas underneath it.
                        addAt?.let { at ->
                            fun addNode(kind: BpmnNodeKind) {
                                val id = "n-${UUID.randomUUID().toString().take(6)}"
                                val label = when (kind) {
                                    BpmnNodeKind.END_EVENT -> "End"
                                    BpmnNodeKind.EXCLUSIVE_GATEWAY -> "Gateway"
                                    else -> "Task"
                                }
                                nodes.add(LoopNode(id, kind, label, xPx = at.x, yPx = at.y))
                                if (kind == BpmnNodeKind.TASK) configNodeId = id
                            }
                            dev.aarso.hyle.cells.HyleRadialMenu(
                                visible = true,
                                anchor = at,
                                onDismiss = { addAt = null },
                                modifier = Modifier.fillMaxSize(),
                                items = listOf(
                                    dev.aarso.hyle.cells.HyleRadialMenuItem(label = "Task", glyph = { tint ->
                                        drawRoundRect(
                                            tint,
                                            topLeft = Offset(size.width * 0.2f, size.height * 0.3f),
                                            size = androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.4f),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.08f),
                                        )
                                    }, onClick = { addNode(BpmnNodeKind.TASK) }),
                                    dev.aarso.hyle.cells.HyleRadialMenuItem(label = "Gateway", glyph = { tint ->
                                        val path = androidx.compose.ui.graphics.Path().apply {
                                            moveTo(size.width * 0.5f, size.height * 0.1f)
                                            lineTo(size.width * 0.9f, size.height * 0.5f)
                                            lineTo(size.width * 0.5f, size.height * 0.9f)
                                            lineTo(size.width * 0.1f, size.height * 0.5f)
                                            close()
                                        }
                                        drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.09f))
                                    }, onClick = { addNode(BpmnNodeKind.EXCLUSIVE_GATEWAY) }),
                                    dev.aarso.hyle.cells.HyleRadialMenuItem(label = "End", glyph = { tint ->
                                        drawCircle(tint, radius = size.width * 0.32f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.1f))
                                    }, onClick = { addNode(BpmnNodeKind.END_EVENT) }),
                                ),
                            )
                        }

                        menuNodeId?.let { id ->
                            val node = nodeById(id)
                            val nodeAnchor = node?.let { Offset(it.xPx, it.yPx) } ?: Offset.Zero
                            val canEdit = node != null && !isEvent(node.kind)
                            dev.aarso.hyle.cells.HyleRadialMenu(
                                visible = true,
                                anchor = nodeAnchor,
                                onDismiss = { menuNodeId = null },
                                modifier = Modifier.fillMaxSize(),
                                items = buildList {
                                    add(
                                        dev.aarso.hyle.cells.HyleRadialMenuItem(label = "Connect", glyph = { tint ->
                                            drawLine(tint, Offset(size.width * 0.15f, size.height * 0.5f), Offset(size.width * 0.75f, size.height * 0.5f), strokeWidth = size.width * 0.09f)
                                            val arrow = androidx.compose.ui.graphics.Path().apply {
                                                moveTo(size.width * 0.55f, size.height * 0.3f)
                                                lineTo(size.width * 0.85f, size.height * 0.5f)
                                                lineTo(size.width * 0.55f, size.height * 0.7f)
                                            }
                                            drawPath(arrow, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.09f))
                                        }, onClick = { connectingFrom = id }),
                                    )
                                    if (canEdit) {
                                        add(
                                            dev.aarso.hyle.cells.HyleRadialMenuItem(label = "Edit", glyph = { tint ->
                                                drawLine(tint, Offset(size.width * 0.25f, size.height * 0.75f), Offset(size.width * 0.75f, size.height * 0.25f), strokeWidth = size.width * 0.1f)
                                            }, onClick = { configNodeId = id }),
                                        )
                                    }
                                    add(
                                        dev.aarso.hyle.cells.HyleRadialMenuItem(label = "Delete", destructive = true, glyph = { tint ->
                                            drawLine(tint, Offset(size.width * 0.28f, size.height * 0.28f), Offset(size.width * 0.72f, size.height * 0.72f), strokeWidth = size.width * 0.1f)
                                            drawLine(tint, Offset(size.width * 0.72f, size.height * 0.28f), Offset(size.width * 0.28f, size.height * 0.72f), strokeWidth = size.width * 0.1f)
                                        }, onClick = { deleteNode(id) }),
                                    )
                                },
                            )
                        }
                    }

                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HyleField(objective, { objective = it }, label = "Objective", mandatory = true, singleLine = false, modifier = Modifier.fillMaxWidth())
                    }
                }

                // Running totals vs budget: a luminance-filling violet bar with a cyan cap
                // tick (never a red ramp — CORE_PHASES.md §1.4). Only shown when a budget is set.
                runBudget?.let { b ->
                    val used = liveSteps.sumOf { (it.tokensIn ?: 0L) + (it.tokensOut ?: 0L) }
                    val fraction = when {
                        b.maxTokensTotal != null && b.maxTokensTotal > 0 -> used.toFloat() / b.maxTokensTotal
                        b.maxSteps != null && b.maxSteps > 0 -> liveSteps.size.toFloat() / b.maxSteps
                        else -> null
                    }
                    if (fraction != null) {
                        BudgetBar(fraction, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }

                if (liveSteps.isNotEmpty() || runError != null || graphResult != null) {
                    HorizontalDivider()
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        runError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        liveSteps.forEach { step ->
                            HyleCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            "${step.index + 1}. ${nodeById(step.nodeId)?.label ?: step.role}",
                                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            stepMeta(step),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(step.output, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        // Summary row: stop reason as icon+label, plus the tree/ledger log note.
                        graphResult?.let { r ->
                            val (glyph, label) = stopLabel(r.stoppedBecause)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(glyph, color = LocalHyleColors.current.violet, modifier = Modifier.padding(end = 6.dp))
                                Text(label, style = MaterialTheme.typography.labelMedium, color = LocalHyleColors.current.textHigh)
                            }
                            loggedNote?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Run sheet (P3): auto-generated params form + optional budget, refuse-to-start ────
    if (showRunSheet) {
        val graphForScan = toBpmnGraph(loopId ?: "loop", loopName, nodes.toList(), edges.toList())
        RunSheetDialog(
            paramKeys = LoopParams.scan(graphForScan, objective),
            onDismiss = { showRunSheet = false },
            onRun = { params, budget ->
                showRunSheet = false
                startRun(params, budget)
            },
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
            // Desktop-class kit §3: the HyleContextMenu parity path's two items call the exact
            // same operations the node's long-press radial menu offers (connectingFrom-from-here
            // / deleteNode above) — Connect starts the same connectingFrom-from-here mode, Delete
            // calls the same deleteNode(id).
            onConnect = { configNodeId = null; connectingFrom = id },
            onDelete = { configNodeId = null; deleteNode(id) },
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
            onPick = { loop -> loadLoop(loop); showLoad = false },
        )
    }

    if (showDistill) {
        DistillDialog(
            runnable = runnable,
            onDismiss = { showDistill = false },
            onDistilled = { loop ->
                store.save(loop)
                loadLoop(loop)
                showDistill = false
            },
        )
    }

    if (showExportPackage) {
        val exportGraph = toBpmnGraph(loopId ?: "loop", loopName, nodes.toList(), edges.toList())
        val suggestedId = loopId ?: ("loop-" + loopName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "untitled" })
        ExportLoopPackageDialog(
            graph = exportGraph, objective = objective, suggestedLoopId = suggestedId,
            onDismiss = { showExportPackage = false },
            onExported = { fileName -> packageNote = "Exported “$fileName”"; showExportPackage = false },
        )
    }

    if (showImportPackage) {
        ImportLoopPackageDialog(
            localModels = runnable,
            onDismiss = { showImportPackage = false },
            onImported = { graph, importedObjective, provenanceExt ->
                nodes.clear(); nodes.addAll(fromBpmnNodes(graph))
                edges.clear(); edges.addAll(fromBpmnEdges(graph))
                // Merge import provenance into the start event, same carrier distillation uses.
                val startIdx = nodes.indexOfFirst { it.kind == BpmnNodeKind.START_EVENT }
                if (startIdx >= 0) nodes[startIdx] = nodes[startIdx].copy(provenanceExt = nodes[startIdx].provenanceExt + provenanceExt)
                objective = importedObjective
                loopId = null // a fresh local draft — Save mints this device's own id, per LOOP_IMPORT_ACTIVATION_CONTRACT.md §8 (installed vs. locally edited are distinct)
                loopName = graph.name.ifBlank { "Imported loop" }
                packageNote = "Imported “${provenanceExt["importedLoopId"]}” — review and Save to keep it."
                showImportPackage = false
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

/** Running totals vs. budget (CORE_PHASES.md P3): a luminance-filling violet bar with a cyan
 *  cap tick at the ceiling — never a red ramp (§1.4). [usedFraction] is clamped to [0,1]. */
@Composable
private fun BudgetBar(usedFraction: Float, modifier: Modifier = Modifier) {
    val colors = LocalHyleColors.current
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        drawRoundRect(colors.hairline, cornerRadius = r)
        val w = size.width * usedFraction.coerceIn(0f, 1f)
        if (w > 0f) {
            drawRoundRect(colors.violet, size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = r)
        }
        val tickX = size.width - 1.dp.toPx()
        drawLine(colors.cyan, Offset(tickX, 0f), Offset(tickX, size.height), strokeWidth = 2.dp.toPx())
    }
}

/**
 * Run sheet (CORE_PHASES.md P3): an auto-generated field per `${key}` the graph references,
 * plus optional budget fields. Refuse-to-start is inline — missing keys are listed and Run
 * stays disabled, so a run either has everything it needs or the sheet says exactly what's
 * absent (mirrors [GraphRunner.run]'s own refusal, before ever starting the coroutine).
 */
@Composable
private fun RunSheetDialog(
    paramKeys: List<String>,
    onDismiss: () -> Unit,
    onRun: (params: Map<String, String>, budget: LoopBudget?) -> Unit,
) {
    var values by remember { mutableStateOf(paramKeys.associateWith { "" }) }
    var stepsText by remember { mutableStateOf("") }
    var wallSecText by remember { mutableStateOf("") }
    val missing = paramKeys.filter { values[it].isNullOrBlank() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(
                Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Run", style = MaterialTheme.typography.titleMedium)
                if (paramKeys.isNotEmpty()) {
                    Text(
                        "This loop uses \${…} placeholders — fill them in below.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (key in paramKeys) {
                        HyleField(
                            values[key].orEmpty(),
                            { values = values + (key to it) },
                            label = key,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (missing.isNotEmpty()) {
                        Text(
                            "Missing: ${missing.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalHyleColors.current.violet, // never red (§1.4)
                        )
                    }
                    HorizontalDivider()
                }
                Text("Budget (optional)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The step that would exceed a limit is never started. Token budgets " +
                        "arrive once on-device token counting is wired to Loops — steps and " +
                        "wall time work today.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HyleField(stepsText, { stepsText = it.filter(Char::isDigit) }, label = "Max steps", modifier = Modifier.fillMaxWidth())
                HyleField(wallSecText, { wallSecText = it.filter(Char::isDigit) }, label = "Max wall time (seconds)", modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    HyleButton(
                        "Run",
                        enabled = missing.isEmpty(),
                        onClick = {
                            val budget = LoopBudget(
                                maxSteps = stepsText.toIntOrNull(),
                                maxWallMs = wallSecText.toLongOrNull()?.times(1000L),
                            )
                            onRun(
                                values.filterValues { it.isNotBlank() },
                                budget.takeIf { it.maxSteps != null || it.maxWallMs != null },
                            )
                        },
                    )
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
    // ACTIVE == this node is the current connect-from selection → HyleCard's own `selected` look.
    // DONE gets its own success-green completion ring, layered on since HyleCard's selected is violet-only.
    Box(
        Modifier.width(widthDp).then(
            if (status == NodeStatus.DONE) {
                Modifier.border(2.dp, colors.success, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            } else {
                Modifier
            },
        ),
    ) {
        HyleCard(selected = status == NodeStatus.ACTIVE) {
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
private fun NodeConfigDialog(
    node: LoopNode,
    runnable: List<ModelSpec>,
    onDismiss: () -> Unit,
    onSave: (name: String, prompt: String, modelId: String?) -> Unit,
    // Desktop-class kit §3 parity path: same two operations as the long-press radial node menu
    // (minus Edit — this dialog IS the edit surface already open). See [loopNodeMenuItems].
    onConnect: () -> Unit,
    onDelete: () -> Unit,
) {
    var n by remember { mutableStateOf(node.label) }
    var p by remember { mutableStateOf(node.systemPrompt) }
    var model by remember { mutableStateOf(node.modelId) }
    var showActions by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isTask = !isEvent(node.kind) && !node.kind.name.contains("GATEWAY")
    val options = listOf("Default model") + runnable.map { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Edit ${node.label}", style = MaterialTheme.typography.titleMedium)
                    Box {
                        Text(
                            "⋮",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .clickable { showActions = true }
                                .padding(8.dp)
                                .semantics { contentDescription = "Node actions" },
                        )
                        HyleContextMenu(
                            expanded = showActions,
                            onDismissRequest = { showActions = false },
                            items = loopNodeMenuItems(),
                            onItemClick = { id ->
                                when (id) {
                                    "connect" -> onConnect()
                                    "delete" -> confirmDelete = true
                                }
                            },
                        )
                    }
                }
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
    // Destructive confirm (binding rule: destructive actions confirm before acting) — the
    // long-press radial menu's own Delete wedge is untouched; this is only this new parity
    // path's gate in front of the same deleteNode(id) call.
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${node.label}”?") },
            text = { Text("Removes the node and any edges connected to it. This can't be undone.") },
            confirmButton = {
                HyleButton("Delete", onClick = { confirmDelete = false; onDelete() })
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
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
                            HyleCard(modifier = Modifier.fillMaxWidth(), onClick = { onPick(loop) }) {
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
