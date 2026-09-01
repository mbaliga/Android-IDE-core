package dev.fonebrew.ui.loops

import dev.fonebrew.contracts.loops.AuthorityRung
import dev.fonebrew.contracts.loops.BackoffStrategy
import dev.fonebrew.contracts.loops.Budgets
import dev.fonebrew.contracts.loops.BudgetCost
import dev.fonebrew.contracts.loops.Compensation
import dev.fonebrew.contracts.loops.DistributionConstraints
import dev.fonebrew.contracts.loops.EdgeDefinition
import dev.fonebrew.contracts.loops.ExecutionTargetConstraints
import dev.fonebrew.contracts.loops.ExecutionTargetType
import dev.fonebrew.contracts.loops.GatewayDefinition
import dev.fonebrew.contracts.loops.GatewayOutcome
import dev.fonebrew.contracts.loops.IdempotencyMode
import dev.fonebrew.contracts.loops.ImplementationReference
import dev.fonebrew.contracts.loops.LicenseRef
import dev.fonebrew.contracts.loops.LoopDefinition
import dev.fonebrew.contracts.loops.LoopProvenance
import dev.fonebrew.contracts.loops.NodeCategory
import dev.fonebrew.contracts.loops.NodeDefinition
import dev.fonebrew.contracts.loops.SchemaReference
import dev.fonebrew.contracts.loops.SideEffectClass
import dev.fonebrew.contracts.loops.TerminalRunState
import dev.fonebrew.contracts.loops.TerminalStateMapping
import dev.fonebrew.contracts.loops.TimeoutAction
import dev.fonebrew.contracts.loops.TimeoutPolicy
import dev.fonebrew.contracts.loops.VerificationPolicy
import dev.fonebrew.contracts.loops.VerificationRequirements
import dev.fonebrew.contracts.loops.RetryPolicy
import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.loop.LoopParams
import dev.fonebrew.domain.loop.authoring.RunViewAction
import dev.fonebrew.domain.loop.authoring.RunViewActionGuard
import dev.fonebrew.domain.loop.authoring.RunViewStateClass
import dev.fonebrew.domain.loop.authoring.StageLinearizer
import dev.fonebrew.domain.model.ModelSpec

/**
 * `LOOP_PHONE_AUTHORING_SPEC.md` §3.2/§4/§9/§15 — the **pure, UI-free** presenter that mounts the
 * already-tested [StageLinearizer] / [RunViewActionGuard] domain machinery over this app's actual
 * editor draft shape ([BpmnGraph]/[dev.fonebrew.domain.bpmn.BpmnNode]), so `StageView.kt`'s
 * composables stay thin render code (per this lane's "keep the logic out of composables" rule).
 * Every function here is a pure mapping over its arguments — no Compose, no I/O, no persistence —
 * and is JVM-tested in `StagePresenterTest.kt`.
 */
object StagePresenter {

    private fun titleOf(graph: BpmnGraph, id: String) = graph.node(id)?.name?.ifBlank { id } ?: id

    // ── Stage narrative (§3.2) ──────────────────────────────────────────────────────────────

    enum class StageKind { START, END, TASK, GATEWAY }

    /** One gateway outcome, for a stage row's branch summary — "gateway branches summarized with
     *  their edge labels" per this lane's brief. [isRepeat] marks an outcome that loops back to an
     *  already-visited ancestor (a back edge, §3.2's "bounded cycle") rather than a forward branch;
     *  shown, never hidden, but not fed into the structurability/rejoin computation below (that is
     *  exactly [StageLinearizer]'s own `LOOP-VALIDATE-NO-CONCURRENCY` framing — a repeat edge
     *  re-enters at the gateway itself, not at a shared downstream rejoin). */
    data class BranchSummary(val label: String, val targetNodeId: String, val targetTitle: String, val isRepeat: Boolean)

    data class StageCardRow(
        val nodeId: String,
        val title: String,
        val kind: StageKind,
        /** "⌂ <name>" for an on-device model, "☁ <name>" for a watched cloud one (glyph first,
         *  never a color-only cue — binding rule: red/green colorblind). Null on a default-model
         *  or non-model-bound node. */
        val modelChip: String?,
        val gatewayBranches: List<BranchSummary> = emptyList(),
        /** "Rejoins MUST explicitly name their destination stage" (§4) — only set for a
         *  structurable gateway that actually has a shared downstream rejoin. */
        val rejoinsAtTitle: String? = null,
        /** True when a back edge originates from this node — this node is a bounded-cycle entry. */
        val hasBoundedCycle: Boolean = false,
    )

    /** §3.2 point 3: the explicit unstructured-region card. Never a silent flattening — every
     *  node in [nodeIdsInRegion] is named, plus the named inbound/outbound jump markers point 3
     *  requires. */
    data class UnstructuredRegionRow(
        val gatewayId: String,
        val gatewayTitle: String,
        val nodeIdsInRegion: List<String>,
        val nodeTitlesInRegion: List<String>,
        val inboundDescription: String,
        val outboundDescription: String,
    )

    sealed interface StageNarrativeItem {
        data class Card(val row: StageCardRow) : StageNarrativeItem
        data class Unstructured(val row: UnstructuredRegionRow) : StageNarrativeItem
    }

    data class StageNarrative(
        val items: List<StageNarrativeItem>,
        val hasStartEvent: Boolean,
        /** Nodes never reached walking forward from Start — a validation surface (§3.1's "current
         *  validation summary"), not merely a rendering detail. */
        val disconnectedNodeIds: List<String>,
    )

    /**
     * §3.2's "total by construction" narrative: one [StageCardRow] per reachable node, in
     * forward-walk order, except a non-structurable gateway region which becomes exactly one
     * [UnstructuredRegionRow] instead of silently flattening its member nodes into false linear
     * cards (§3.2 point 4). Structurability itself is computed by [StageLinearizer] — this
     * function only turns that verdict into the render-tree StageLinearizer's own KDoc assigns to
     * `ui/loops/` ("Turning that computation into the actual ... RENDER TREE ... is Compose UI
     * work belonging to ui/loops/ ... and is explicitly not attempted here").
     */
    fun linearize(graph: BpmnGraph, models: List<ModelSpec> = emptyList()): StageNarrative {
        val start = graph.nodes.firstOrNull { it.kind == BpmnNodeKind.START_EVENT }
            ?: return StageNarrative(items = emptyList(), hasStartEvent = false, disconnectedNodeIds = graph.nodes.map { it.id })

        fun titleOf(id: String) = graph.node(id)?.name?.ifBlank { id } ?: id
        val modelById = models.associateBy { it.id }
        fun modelChip(modelId: String?): String? {
            if (modelId == null) return null
            val spec = modelById[modelId]
            val label = spec?.displayName ?: modelId
            return (if (spec?.isOnDevice == true) "⌂ " else "☁ ") + label // ⌂ / ☁ — glyph, never color-only
        }

        val edgeDefs = graph.edges.map { EdgeDefinition(edgeId = it.id, fromNodeId = it.sourceId, toNodeId = it.targetId, label = it.name) }
        val edgeClass = StageLinearizer.classifyEdges(start.id, edgeDefs)
        val backEdgeIds = edgeClass.backEdgeIds
        val backSourceIds = graph.edges.filter { it.id in backEdgeIds }.map { it.sourceId }.toSet()

        val gatewayDefs = graph.nodes.filter { isGatewayKind(it.kind) }.mapNotNull { gw ->
            val forwardOut = graph.outgoing(gw.id).filter { it.id !in backEdgeIds }
            if (forwardOut.isEmpty()) return@mapNotNull null
            GatewayDefinition(
                gatewayId = gw.id,
                outcomes = forwardOut.map { e -> GatewayOutcome(label = e.name?.ifBlank { null } ?: "else-${e.id}", targetNodeId = e.targetId) },
                exhaustive = true,
            )
        }
        val definition = placeholderLoopDefinition(start.id, edgeDefs, gatewayDefs)
        val analysisByGateway = StageLinearizer.analyze(definition).associateBy { it.gatewayId }
        val forwardEdgesBySource = graph.edges.filter { it.id !in backEdgeIds }.groupBy { it.sourceId }

        val visited = LinkedHashSet<String>()
        val swallowed = mutableSetOf<String>()
        val items = mutableListOf<StageNarrativeItem>()
        val queue = ArrayDeque<String>()
        val enqueued = mutableSetOf(start.id)
        queue.addLast(start.id)

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id in visited) continue
            visited += id
            val node = graph.node(id) ?: continue

            if (id !in swallowed) {
                if (isGatewayKind(node.kind)) {
                    val analysis = analysisByGateway[id]
                    val branches = graph.outgoing(id).map { e ->
                        BranchSummary(e.name?.ifBlank { null } ?: "else", e.targetId, titleOf(e.targetId), isRepeat = e.id in backEdgeIds)
                    }
                    if (analysis != null && !analysis.structurable) {
                        val regionIds = regionNodeIds(analysis, forwardEdgesBySource)
                        swallowed += regionIds
                        items += StageNarrativeItem.Unstructured(
                            UnstructuredRegionRow(
                                gatewayId = id, gatewayTitle = titleOf(id),
                                nodeIdsInRegion = regionIds.toList(), nodeTitlesInRegion = regionIds.map(::titleOf),
                                inboundDescription = "enters from “${titleOf(id)}”",
                                outboundDescription = analysis.rejoinNodeId?.let { "rejoins at “${titleOf(it)}”" }
                                    ?: "branches end independently — no shared rejoin",
                            ),
                        )
                    } else {
                        items += StageNarrativeItem.Card(
                            StageCardRow(
                                nodeId = id, title = titleOf(id), kind = StageKind.GATEWAY, modelChip = null,
                                gatewayBranches = branches, rejoinsAtTitle = analysis?.rejoinNodeId?.let(::titleOf),
                                hasBoundedCycle = id in backSourceIds,
                            ),
                        )
                    }
                } else {
                    items += StageNarrativeItem.Card(
                        StageCardRow(
                            nodeId = id, title = titleOf(id),
                            kind = when (node.kind) {
                                BpmnNodeKind.START_EVENT -> StageKind.START
                                BpmnNodeKind.END_EVENT -> StageKind.END
                                else -> StageKind.TASK
                            },
                            modelChip = modelChip(node.ext["model"]),
                            hasBoundedCycle = id in backSourceIds,
                        ),
                    )
                }
            }

            forwardEdgesBySource[id].orEmpty().forEach { e ->
                if (e.targetId !in enqueued) { enqueued += e.targetId; queue.addLast(e.targetId) }
            }
        }

        val disconnected = graph.nodes.map { it.id }.filterNot { it in visited }
        return StageNarrative(items, hasStartEvent = true, disconnectedNodeIds = disconnected)
    }

    private fun isGatewayKind(kind: BpmnNodeKind) = kind.name.contains("GATEWAY")

    private fun regionNodeIds(analysis: StageLinearizer.GatewayStructurability, forwardEdgesBySource: Map<String, List<BpmnEdge>>): Set<String> {
        val rejoinDownstream = analysis.rejoinNodeId?.let { reachableForwardOnly(it, forwardEdgesBySource) } ?: emptySet()
        val result = mutableSetOf<String>()
        for (target in analysis.branchTargetNodeIds) result += reachableForwardOnly(target, forwardEdgesBySource) - rejoinDownstream
        return result
    }

    private fun reachableForwardOnly(start: String, forwardEdgesBySource: Map<String, List<BpmnEdge>>): Set<String> {
        val seen = linkedSetOf<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (!seen.add(n)) continue
            forwardEdgesBySource[n].orEmpty().forEach { if (it.targetId !in seen) stack.addLast(it.targetId) }
        }
        return seen
    }

    /**
     * A minimal, honestly-scoped single-node [LoopDefinition] built solely so [StageLinearizer]'s
     * tested structurability math — which per its own test fixtures (`StageLinearizerTest
     * .dummyNode`) never reads [LoopDefinition.nodes] beyond `nodes.isNotEmpty()` — can run
     * against this editor's lightweight [BpmnGraph] draft. The one placeholder [NodeDefinition]
     * carries no real authority/side-effect/port data about the draft's actual nodes; it exists
     * only to satisfy [LoopDefinition]'s constructor invariant. Real per-node authority/side-effect
     * data is derived separately, honestly, from [BpmnGraph] node `ext` fields — see
     * [authorityEnvelope] below — never invented here.
     */
    private fun placeholderLoopDefinition(startId: String, edges: List<EdgeDefinition>, gateways: List<GatewayDefinition>): LoopDefinition {
        val dummyNode = NodeDefinition(
            nodeId = startId, category = NodeCategory.DETERMINISTIC_TRANSFORMATION,
            inputPorts = emptyList(), outputPorts = emptyList(),
            implementation = ImplementationReference.TypedSlot("stage-presenter-placeholder"),
            requestedCapabilities = emptyList(), authorityClass = AuthorityRung.OBSERVE,
            preferredExecutionTargets = listOf(ExecutionTargetType.LOCAL_ANDROID),
            permittedExecutionTargets = listOf(ExecutionTargetType.LOCAL_ANDROID),
            timeoutPolicy = TimeoutPolicy(timeoutSeconds = null, onTimeout = TimeoutAction.FAIL_SAFE),
            retryPolicy = RetryPolicy(maxRetries = 0, backoff = BackoffStrategy.NONE),
            idempotencyMode = IdempotencyMode.PURE,
            compensation = Compensation.NonCompensableAcknowledged,
            verificationRequirements = VerificationRequirements(requiredForSuccess = false),
            sideEffectClass = SideEffectClass.NONE,
            onFailureTerminalState = TerminalRunState.FAILED_SAFE,
            explanation = "StagePresenter structurability placeholder node -- not a real draft node.",
        )
        return LoopDefinition(
            loopId = "stage-view-draft", semanticVersion = "0.0.0", objective = "stage view analysis",
            documentationRef = null,
            inputSchema = SchemaReference.Inline(emptyMap()), outputSchema = SchemaReference.Inline(emptyMap()),
            startNodeId = startId, terminalNodeIds = listOf(startId),
            nodes = listOf(dummyNode), edges = edges, gateways = gateways,
            budgets = Budgets(steps = 1, wallClockSeconds = 0.0, tokens = 0, cost = BudgetCost(0.0, "USD"), toolCalls = 0),
            bindingSlots = emptyList(), requiredCapabilities = emptyList(),
            executionTargetConstraints = ExecutionTargetConstraints(permittedTargetTypes = listOf(ExecutionTargetType.LOCAL_ANDROID)),
            distributionConstraints = DistributionConstraints(),
            minEngineVersion = "0.0.0", maxEngineVersion = null,
            verificationPolicy = VerificationPolicy(requireVerificationForSuccess = false),
            terminalStateMapping = TerminalStateMapping(unverifiedSuccessAllowed = true),
            provenance = LoopProvenance(sourceLocation = "stage-view", projectRevision = "draft", initiatingPrincipal = "stage-view"),
            licenseRef = LicenseRef(spdxId = "Apache-2.0"),
        )
    }

    // ── Delete impact preview (§3.2 "delete with impact preview") ──────────────────────────

    data class DeleteImpactPreview(
        val nodeId: String,
        val nodeTitle: String,
        /** Every edge touching this node, described as "<from> → <to>" (+ label). */
        val removedEdgeDescriptions: List<String>,
        /** Nodes reachable from Start today that would no longer be reachable once this node
         *  (and its edges) are removed — the concrete "affected nodes" list §3.2 requires
         *  before confirming a delete. */
        val orphanedNodeIds: List<String>,
        val orphanedNodeTitles: List<String>,
    )

    fun deleteImpact(graph: BpmnGraph, nodeId: String): DeleteImpactPreview {
        fun titleOf(id: String) = graph.node(id)?.name?.ifBlank { id } ?: id
        val touching = graph.edges.filter { it.sourceId == nodeId || it.targetId == nodeId }
        val descriptions = touching.map { e ->
            buildString {
                append(titleOf(e.sourceId)).append(" → ").append(titleOf(e.targetId))
                e.name?.let { append(" (").append(it).append(")") }
            }
        }
        val remainingNodes = graph.nodes.filterNot { it.id == nodeId }
        val remainingEdges = graph.edges.filterNot { it.sourceId == nodeId || it.targetId == nodeId }
        val remaining = graph.copy(nodes = remainingNodes, edges = remainingEdges)
        val start = remaining.nodes.firstOrNull { it.kind == BpmnNodeKind.START_EVENT }
        val reachableAfter = start?.let { reachableAny(it.id, remaining) } ?: emptySet()
        val beforeIds = graph.nodes.map { it.id }.toSet() - nodeId
        val orphaned = (beforeIds - reachableAfter).toList()
        return DeleteImpactPreview(nodeId, titleOf(nodeId), descriptions, orphaned, orphaned.map(::titleOf))
    }

    private fun reachableAny(start: String, graph: BpmnGraph): Set<String> {
        val seen = linkedSetOf<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (!seen.add(n)) continue
            graph.outgoing(n).forEach { if (it.targetId !in seen) stack.addLast(it.targetId) }
        }
        return seen
    }

    // ── Run View interventions (§9, FB-RAT-PHN-008) ─────────────────────────────────────────

    /** Every [RunViewAction] [RunViewActionGuard] currently permits from [state] — drives the
     *  intervention button row without re-deriving the guard's own legality table. */
    fun legalRunActions(
        state: RunViewStateClass,
        runtimeSupportsSafePause: Boolean = false,
        denialHasDeclaredRecoveryPath: Boolean = false,
    ): List<RunViewAction> = RunViewAction.entries.filter {
        RunViewActionGuard.apply(state, it, runtimeSupportsSafePause, denialHasDeclaredRecoveryPath) is RunViewActionGuard.Result.Advanced
    }

    /** FB-RAT-PHN-008: "editing the graph while a run is active MUST fork into a new draft
     *  revision without mutating the active execution." [state] is accepted (not just [running])
     *  so this reads as a real call into [RunViewActionGuard.canForkFromReceipt] rather than a
     *  bare boolean re-implementation, even though that guard is legal from every state today. */
    fun shouldForkBeforeEdit(running: Boolean, state: RunViewStateClass = RunViewStateClass.Running): Boolean =
        running && RunViewActionGuard.canForkFromReceipt(state)

    // ── Intent View derivations (§3.1) ──────────────────────────────────────────────────────

    data class ValidationFinding(val code: String, val message: String, val severity: Severity) {
        enum class Severity { BLOCKER, WARNING, INFO }
    }

    data class ValidationSummary(val findings: List<ValidationFinding>) {
        val isBlocked: Boolean get() = findings.any { it.severity == ValidationFinding.Severity.BLOCKER }
    }

    /** §3.1's "current validation summary": [dev.fonebrew.domain.loop.GraphRunner]'s own start
     *  preconditions (no start event, missing `\${...}` params) plus [StageLinearizer]
     *  structurability, surfaced as findings — never a color-only status (binding rule). */
    fun validationSummary(graph: BpmnGraph, objective: String): ValidationSummary {
        val findings = mutableListOf<ValidationFinding>()
        val hasStart = graph.nodes.any { it.kind == BpmnNodeKind.START_EVENT }
        if (!hasStart) {
            findings += ValidationFinding("LOOP-GRAPH-NO-START", "No start event — GraphRunner refuses to run.", ValidationFinding.Severity.BLOCKER)
        }
        if (graph.nodes.none { it.kind == BpmnNodeKind.END_EVENT }) {
            findings += ValidationFinding("LOOP-GRAPH-NO-END", "No end event — a run can only stop via cancel, the step cap, or a budget.", ValidationFinding.Severity.WARNING)
        }
        val missingParams = LoopParams.scan(graph, objective)
        if (missingParams.isNotEmpty()) {
            findings += ValidationFinding(
                "LOOP-PARAMS-UNFILLED",
                "\${...} placeholders referenced, filled in at run time: ${missingParams.joinToString()}",
                ValidationFinding.Severity.INFO,
            )
        }
        if (hasStart) {
            val narrative = linearize(graph)
            for (item in narrative.items) {
                if (item is StageNarrativeItem.Unstructured) {
                    findings += ValidationFinding(
                        "LOOP-STRUCT-NON-STRUCTURABLE",
                        "Gateway “${item.row.gatewayTitle}” is not structurable — shown as an unstructured region, not flattened.",
                        ValidationFinding.Severity.WARNING,
                    )
                }
            }
            if (narrative.disconnectedNodeIds.isNotEmpty()) {
                val titles = narrative.disconnectedNodeIds.joinToString { id -> graph.node(id)?.name?.ifBlank { id } ?: id }
                findings += ValidationFinding(
                    "LOOP-GRAPH-UNREACHABLE",
                    "${narrative.disconnectedNodeIds.size} node(s) unreachable from Start: $titles",
                    ValidationFinding.Severity.WARNING,
                )
            }
        }
        return ValidationSummary(findings)
    }

    data class AuthorityEnvelopeSummary(
        val watchedCloudModels: List<String>,
        val onDeviceModels: List<String>,
        val sideEffectCounts: Map<String, Int>,
        val undeclaredSideEffectTaskCount: Int,
    )

    /** §3.1's "expected side effects, authority envelope ... summarized from the draft nodes" —
     *  built only from what the draft actually carries (`ext["model"]`/`ext["sideEffectClass"]`),
     *  never a fabricated capability grant. Binding rule 2: every cloud model is a watched object. */
    fun authorityEnvelope(graph: BpmnGraph, models: List<ModelSpec>): AuthorityEnvelopeSummary {
        val byId = models.associateBy { it.id }
        val watched = mutableListOf<String>()
        val onDevice = mutableListOf<String>()
        val sideEffects = LinkedHashMap<String, Int>()
        var undeclared = 0
        for (n in graph.nodes) {
            n.ext["model"]?.let { mid ->
                val spec = byId[mid]
                val label = spec?.displayName ?: mid
                if (spec?.isOnDevice == true) onDevice += label else watched += label
            }
            if (n.kind == BpmnNodeKind.TASK) {
                val se = n.ext["sideEffectClass"]
                if (se.isNullOrBlank()) undeclared++ else sideEffects[se] = (sideEffects[se] ?: 0) + 1
            }
        }
        return AuthorityEnvelopeSummary(watched.distinct(), onDevice.distinct(), sideEffects, undeclared)
    }

    // ── Stage editing verbs (§3.2): reorder adjacent, insert before/after ──────────────────
    //
    // Both verbs are deliberately scoped to "where semantics allow" (§3.2's own qualifier):
    // they operate only on a simple, unambiguous 1-in/1-out edge between two stages. A branchy
    // join/split is left to Graph View rather than guessing which branch the user meant — an
    // honest scope cut, not a silent wrong answer. Both return the plain new edge list (never
    // mutate [graph]); the caller applies it to the mutable draft.

    data class ReorderOutcome(val edges: List<BpmnEdge>?, val blockedReason: String?)

    /** Swaps [nodeId] with its single successor ([direction] = +1) or single predecessor
     *  ([direction] = -1) in the narrative order, by rewiring the (at most) three edges between
     *  them -- never touching any other edge in the graph. */
    fun reorderAdjacent(graph: BpmnGraph, nodeId: String, direction: Int): ReorderOutcome {
        val (firstId, secondId) = when (direction) {
            1 -> {
                val next = graph.outgoing(nodeId).singleOrNull()?.targetId
                    ?: return ReorderOutcome(null, "“${titleOf(graph, nodeId)}” has more than one outgoing edge — reorder needs a single, unambiguous next stage.")
                nodeId to next
            }
            -1 -> {
                val prev = graph.incoming(nodeId).singleOrNull()?.sourceId
                    ?: return ReorderOutcome(null, "“${titleOf(graph, nodeId)}” has more than one incoming edge — reorder needs a single, unambiguous previous stage.")
                prev to nodeId
            }
            else -> return ReorderOutcome(null, "Unknown reorder direction: $direction.")
        }
        return swapAdjacent(graph, firstId, secondId)
    }

    private fun swapAdjacent(graph: BpmnGraph, firstId: String, secondId: String): ReorderOutcome {
        // Start/End are structural markers, not repositionable steps -- swapping either would
        // reverse a start event's edge direction (an incoming edge on a node GraphRunner only
        // ever walks forward FROM) or relocate the terminal marker mid-sequence.
        listOf(firstId, secondId).forEach { id ->
            val kind = graph.node(id)?.kind
            if (kind == BpmnNodeKind.START_EVENT || kind == BpmnNodeKind.END_EVENT) {
                return ReorderOutcome(null, "“${titleOf(graph, id)}” is a Start/End marker and can't be reordered.")
            }
        }
        val bridging = graph.edges.singleOrNull { it.sourceId == firstId && it.targetId == secondId }
            ?: return ReorderOutcome(null, "“${titleOf(graph, firstId)}” and “${titleOf(graph, secondId)}” aren't connected by a single, unambiguous edge.")
        val incomingToSecond = graph.edges.filter { it.targetId == secondId }
        if (incomingToSecond.size != 1) {
            return ReorderOutcome(null, "“${titleOf(graph, secondId)}” has more than one incoming edge — swapping would leave another branch dangling.")
        }
        val newEdges = graph.edges.map { e ->
            when {
                e === bridging -> e.copy(sourceId = secondId, targetId = firstId)
                e.targetId == firstId -> e.copy(targetId = secondId)
                e.sourceId == secondId -> e.copy(sourceId = firstId)
                else -> e
            }
        }
        return ReorderOutcome(newEdges, null)
    }

    enum class InsertPosition { BEFORE, AFTER }

    data class InsertOutcome(val edges: List<BpmnEdge>?, val blockedReason: String?)

    /** Splices a new node (already minted as [newNodeId] by the caller — [StagePresenter] mints
     *  no IDs) directly before or after [anchorNodeId], preserving whatever label/condition rode
     *  on the edge it displaces. */
    fun insertAdjacent(graph: BpmnGraph, anchorNodeId: String, position: InsertPosition, newNodeId: String): InsertOutcome {
        val anchorKind = graph.node(anchorNodeId)?.kind
        // A start event has no incoming edge to displace, and an end event has no outgoing edge
        // to displace -- inserting on that side would only produce a node nothing ever reaches.
        if (position == InsertPosition.BEFORE && anchorKind == BpmnNodeKind.START_EVENT) {
            return InsertOutcome(null, "Nothing comes before Start.")
        }
        if (position == InsertPosition.AFTER && anchorKind == BpmnNodeKind.END_EVENT) {
            return InsertOutcome(null, "Nothing comes after End.")
        }
        return when (position) {
            InsertPosition.AFTER -> {
                val out = graph.outgoing(anchorNodeId)
                if (out.size > 1) {
                    return InsertOutcome(null, "“${titleOf(graph, anchorNodeId)}” has more than one outgoing edge — pick a branch to insert into from Graph view.")
                }
                val nextEdge = out.singleOrNull()
                val newEdges = graph.edges.filter { it !== nextEdge }.toMutableList()
                newEdges += BpmnEdge(id = "e-$anchorNodeId-$newNodeId", sourceId = anchorNodeId, targetId = newNodeId)
                if (nextEdge != null) {
                    newEdges += BpmnEdge(id = "e-$newNodeId-${nextEdge.targetId}", sourceId = newNodeId, targetId = nextEdge.targetId, name = nextEdge.name, condition = nextEdge.condition)
                }
                InsertOutcome(newEdges, null)
            }
            InsertPosition.BEFORE -> {
                val inc = graph.incoming(anchorNodeId)
                if (inc.size > 1) {
                    return InsertOutcome(null, "“${titleOf(graph, anchorNodeId)}” has more than one incoming edge — pick a branch to insert into from Graph view.")
                }
                val prevEdge = inc.singleOrNull()
                val newEdges = graph.edges.filter { it !== prevEdge }.toMutableList()
                if (prevEdge != null) {
                    newEdges += BpmnEdge(id = "e-${prevEdge.sourceId}-$newNodeId", sourceId = prevEdge.sourceId, targetId = newNodeId, name = prevEdge.name, condition = prevEdge.condition)
                }
                newEdges += BpmnEdge(id = "e-$newNodeId-$anchorNodeId", sourceId = newNodeId, targetId = anchorNodeId)
                InsertOutcome(newEdges, null)
            }
        }
    }
}
