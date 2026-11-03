package dev.fonebrew.ui.loops

import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.loop.authoring.RunViewAction
import dev.fonebrew.domain.loop.authoring.RunViewStateClass
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime
import dev.fonebrew.domain.template.TemplateId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `LOOP_PHONE_AUTHORING_SPEC.md` §3.2/§9/§15 -- proves [StagePresenter] mounts the already-tested
 * [dev.fonebrew.domain.loop.authoring.StageLinearizer] / [dev.fonebrew.domain.loop.authoring
 * .RunViewActionGuard] correctly over this app's actual [BpmnGraph] editor draft shape.
 */
class StagePresenterTest {

    private fun node(id: String, kind: BpmnNodeKind, name: String = id, ext: Map<String, String> = emptyMap()) =
        BpmnNode(id = id, kind = kind, name = name, ext = ext)

    private fun edge(from: String, to: String, label: String? = null) =
        BpmnEdge(id = "$from-$to", sourceId = from, targetId = to, name = label)

    // ── linearize: a structurable diamond ───────────────────────────────────────────────────

    @Test
    fun `linearize renders a structurable diamond as ordered cards with a named rejoin`() {
        val graph = BpmnGraph(
            id = "g", nodes = listOf(
                node("start", BpmnNodeKind.START_EVENT, "Start"),
                node("gate", BpmnNodeKind.EXCLUSIVE_GATEWAY, "Approved?"),
                node("b", BpmnNodeKind.TASK, "Path B"),
                node("c", BpmnNodeKind.TASK, "Path C"),
                node("end", BpmnNodeKind.END_EVENT, "End"),
            ),
            edges = listOf(edge("start", "gate"), edge("gate", "b", "approve"), edge("gate", "c", "refine"), edge("b", "end"), edge("c", "end")),
        )
        val narrative = StagePresenter.linearize(graph)
        assertTrue(narrative.hasStartEvent)
        assertTrue(narrative.disconnectedNodeIds.isEmpty())
        assertTrue(narrative.items.none { it is StagePresenter.StageNarrativeItem.Unstructured })

        val gateCard = narrative.items.filterIsInstance<StagePresenter.StageNarrativeItem.Card>()
            .map { it.row }.first { it.nodeId == "gate" }
        assertEquals(2, gateCard.gatewayBranches.size)
        assertEquals(setOf("approve", "refine"), gateCard.gatewayBranches.map { it.label }.toSet())
        assertEquals("End", gateCard.rejoinsAtTitle)
    }

    // ── linearize: a non-structurable region renders as one explicit card ──────────────────

    @Test
    fun `linearize renders a non-structurable gateway region as one explicit unstructured card, never flattened`() {
        // A(gateway) -> B, A -> C; B -> D -> X; C -> X; B -> C directly is the crossing jump.
        val graph = BpmnGraph(
            id = "g", nodes = listOf(
                node("A", BpmnNodeKind.EXCLUSIVE_GATEWAY, "Gate"),
                node("start", BpmnNodeKind.START_EVENT, "Start"),
                node("B", BpmnNodeKind.TASK, "B"),
                node("C", BpmnNodeKind.TASK, "C"),
                node("D", BpmnNodeKind.TASK, "D"),
                node("X", BpmnNodeKind.END_EVENT, "X"),
            ),
            edges = listOf(
                edge("start", "A"),
                edge("A", "B", "b"), edge("A", "C", "c"),
                edge("B", "D"), edge("D", "X"), edge("C", "X"),
                edge("B", "C"),
            ),
        )
        val narrative = StagePresenter.linearize(graph)
        val unstructured = narrative.items.filterIsInstance<StagePresenter.StageNarrativeItem.Unstructured>()
        assertEquals(1, unstructured.size)
        val row = unstructured.single().row
        assertEquals("A", row.gatewayId)
        assertTrue("C" in row.nodeIdsInRegion)
        // The nodes swallowed into the region must not ALSO appear as their own top-level cards.
        val cardIds = narrative.items.filterIsInstance<StagePresenter.StageNarrativeItem.Card>().map { it.row.nodeId }.toSet()
        assertTrue(row.nodeIdsInRegion.none { it in cardIds })
    }

    // ── linearize: a back edge is a repeat branch, not a structurability crossing ──────────

    @Test
    fun `a gateway's own back edge is shown as a repeat branch and does not break structurability`() {
        val graph = BpmnGraph(
            id = "g", nodes = listOf(
                node("start", BpmnNodeKind.START_EVENT, "Start"),
                node("proposer", BpmnNodeKind.TASK, "Proposer"),
                node("critic", BpmnNodeKind.TASK, "Critic"),
                node("gate", BpmnNodeKind.EXCLUSIVE_GATEWAY, "Approved?"),
                node("end", BpmnNodeKind.END_EVENT, "End"),
            ),
            edges = listOf(
                edge("start", "proposer"), edge("proposer", "critic"), edge("critic", "gate"),
                edge("gate", "end", "approve"), edge("gate", "proposer", "refine"),
            ),
        )
        val narrative = StagePresenter.linearize(graph)
        assertTrue(narrative.items.none { it is StagePresenter.StageNarrativeItem.Unstructured })
        val gateCard = narrative.items.filterIsInstance<StagePresenter.StageNarrativeItem.Card>().map { it.row }.first { it.nodeId == "gate" }
        val repeatBranch = gateCard.gatewayBranches.first { it.label == "refine" }
        assertTrue(repeatBranch.isRepeat)
        val proposerCard = narrative.items.filterIsInstance<StagePresenter.StageNarrativeItem.Card>().map { it.row }.first { it.nodeId == "proposer" }
        assertFalse(proposerCard.hasBoundedCycle) // the back edge SOURCE is "gate", not "proposer"
        assertTrue(gateCard.hasBoundedCycle)
    }

    @Test
    fun `linearize with no start event reports every node as disconnected`() {
        val graph = BpmnGraph(id = "g", nodes = listOf(node("a", BpmnNodeKind.TASK, "A")), edges = emptyList())
        val narrative = StagePresenter.linearize(graph)
        assertFalse(narrative.hasStartEvent)
        assertEquals(listOf("a"), narrative.disconnectedNodeIds)
    }

    @Test
    fun `linearize reports a node unreachable from start as disconnected`() {
        val graph = BpmnGraph(
            id = "g", nodes = listOf(node("start", BpmnNodeKind.START_EVENT, "Start"), node("orphan", BpmnNodeKind.TASK, "Orphan")),
            edges = emptyList(),
        )
        val narrative = StagePresenter.linearize(graph)
        assertEquals(listOf("orphan"), narrative.disconnectedNodeIds)
    }

    @Test
    fun `linearize builds a model chip with an on-device glyph, never color-only`() {
        val onDevice = ModelSpec(
            id = "m1", displayName = "Local Model", family = "test", contextWindow = 4096,
            tokenizerId = "t", templateId = TemplateId.LLAMA3, runtime = Runtime.LOCAL_GGUF, modelPath = "/x.gguf",
        )
        val graph = BpmnGraph(
            id = "g", nodes = listOf(
                node("start", BpmnNodeKind.START_EVENT, "Start"),
                node("task", BpmnNodeKind.TASK, "Task", ext = mapOf("model" to "m1")),
            ),
            edges = listOf(edge("start", "task")),
        )
        val narrative = StagePresenter.linearize(graph, listOf(onDevice))
        val row = narrative.items.filterIsInstance<StagePresenter.StageNarrativeItem.Card>().map { it.row }.first { it.nodeId == "task" }
        assertEquals("⌂ Local Model", row.modelChip)
    }

    // ── deleteImpact ─────────────────────────────────────────────────────────────────────

    @Test
    fun `deleteImpact lists touching edges and orphaned downstream nodes`() {
        val graph = BpmnGraph(
            id = "g", nodes = listOf(
                node("start", BpmnNodeKind.START_EVENT, "Start"),
                node("mid", BpmnNodeKind.TASK, "Mid"),
                node("tail", BpmnNodeKind.TASK, "Tail"),
            ),
            edges = listOf(edge("start", "mid"), edge("mid", "tail")),
        )
        val impact = StagePresenter.deleteImpact(graph, "mid")
        assertEquals("Mid", impact.nodeTitle)
        assertEquals(2, impact.removedEdgeDescriptions.size)
        assertEquals(listOf("tail"), impact.orphanedNodeIds)
    }

    @Test
    fun `deleteImpact reports no orphans when a node has no downstream dependents`() {
        val graph = BpmnGraph(
            id = "g", nodes = listOf(node("start", BpmnNodeKind.START_EVENT, "Start"), node("leaf", BpmnNodeKind.TASK, "Leaf")),
            edges = listOf(edge("start", "leaf")),
        )
        val impact = StagePresenter.deleteImpact(graph, "leaf")
        assertTrue(impact.orphanedNodeIds.isEmpty())
    }

    // ── legalRunActions / shouldForkBeforeEdit (FB-RAT-PHN-008) ─────────────────────────────

    @Test
    fun `legalRunActions from Running includes cancel and inspect but not authority actions`() {
        val actions = StagePresenter.legalRunActions(RunViewStateClass.Running)
        assertTrue(RunViewAction.CANCEL in actions)
        assertTrue(RunViewAction.INSPECT in actions)
        assertFalse(RunViewAction.APPROVE_AUTHORITY in actions)
        assertFalse(RunViewAction.RETRY_FAILED_NODE in actions)
    }

    @Test
    fun `legalRunActions from a non-failure terminal state offers no retry or recovery`() {
        val actions = StagePresenter.legalRunActions(RunViewStateClass.Terminal(isFailure = false))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `shouldForkBeforeEdit is true only while a run is active`() {
        assertTrue(StagePresenter.shouldForkBeforeEdit(running = true))
        assertFalse(StagePresenter.shouldForkBeforeEdit(running = false))
    }

    // ── validationSummary ────────────────────────────────────────────────────────────────

    @Test
    fun `validationSummary flags a missing start event as a blocker`() {
        val graph = BpmnGraph(id = "g", nodes = listOf(node("a", BpmnNodeKind.TASK, "A")), edges = emptyList())
        val summary = StagePresenter.validationSummary(graph, objective = "do the thing")
        assertTrue(summary.isBlocked)
        assertTrue(summary.findings.any { it.code == "LOOP-GRAPH-NO-START" })
    }

    @Test
    fun `validationSummary is not blocked for a start-having graph even with warnings`() {
        val graph = BpmnGraph(id = "g", nodes = listOf(node("start", BpmnNodeKind.START_EVENT, "Start")), edges = emptyList())
        val summary = StagePresenter.validationSummary(graph, objective = "do the thing")
        assertFalse(summary.isBlocked)
        assertTrue(summary.findings.any { it.code == "LOOP-GRAPH-NO-END" })
    }

    @Test
    fun `validationSummary surfaces unfilled placeholders as informational, not a blocker`() {
        val graph = BpmnGraph(
            id = "g",
            nodes = listOf(node("start", BpmnNodeKind.START_EVENT, "Start"), node("end", BpmnNodeKind.END_EVENT, "End")),
            edges = listOf(edge("start", "end")),
        )
        val summary = StagePresenter.validationSummary(graph, objective = "Summarize \${topic}")
        assertFalse(summary.isBlocked)
        assertTrue(summary.findings.any { it.code == "LOOP-PARAMS-UNFILLED" && "topic" in it.message })
    }

    // ── authorityEnvelope ────────────────────────────────────────────────────────────────

    // ── reorderAdjacent / insertAdjacent (§3.2 stage editing verbs) ─────────────────────────

    @Test
    fun `reorderAdjacent swaps two simple sequential stages by rewiring exactly their shared edges`() {
        // P -> A -> B -> Q
        val graph = BpmnGraph(
            id = "g", nodes = listOf(node("P", BpmnNodeKind.TASK), node("A", BpmnNodeKind.TASK), node("B", BpmnNodeKind.TASK), node("Q", BpmnNodeKind.TASK)),
            edges = listOf(edge("P", "A"), edge("A", "B"), edge("B", "Q")),
        )
        val outcome = StagePresenter.reorderAdjacent(graph, "A", direction = 1)
        assertEquals(null, outcome.blockedReason)
        val edges = outcome.edges!!.associate { it.sourceId to it.targetId }
        assertEquals(mapOf("P" to "B", "B" to "A", "A" to "Q"), edges)
    }

    @Test
    fun `reorderAdjacent refuses when the anchor has more than one outgoing edge`() {
        val graph = BpmnGraph(
            id = "g", nodes = listOf(node("A", BpmnNodeKind.EXCLUSIVE_GATEWAY), node("B", BpmnNodeKind.TASK), node("C", BpmnNodeKind.TASK)),
            edges = listOf(edge("A", "B"), edge("A", "C")),
        )
        val outcome = StagePresenter.reorderAdjacent(graph, "A", direction = 1)
        assertEquals(null, outcome.edges)
        assertTrue(outcome.blockedReason != null)
    }

    @Test
    fun `reorderAdjacent refuses to reorder a start or end event`() {
        val graph = BpmnGraph(
            id = "g", nodes = listOf(node("start", BpmnNodeKind.START_EVENT, "Start"), node("a", BpmnNodeKind.TASK, "A")),
            edges = listOf(edge("start", "a")),
        )
        val onStart = StagePresenter.reorderAdjacent(graph, "start", direction = 1)
        assertEquals(null, onStart.edges)
        val onA = StagePresenter.reorderAdjacent(graph, "a", direction = -1) // swaps with Start
        assertEquals(null, onA.edges)
    }

    @Test
    fun `insertAdjacent refuses before Start or after End`() {
        val graph = BpmnGraph(
            id = "g", nodes = listOf(node("start", BpmnNodeKind.START_EVENT, "Start"), node("end", BpmnNodeKind.END_EVENT, "End")),
            edges = listOf(edge("start", "end")),
        )
        assertEquals(null, StagePresenter.insertAdjacent(graph, "start", StagePresenter.InsertPosition.BEFORE, "NEW").edges)
        assertEquals(null, StagePresenter.insertAdjacent(graph, "end", StagePresenter.InsertPosition.AFTER, "NEW").edges)
        assertTrue(StagePresenter.insertAdjacent(graph, "start", StagePresenter.InsertPosition.AFTER, "NEW").edges != null)
        assertTrue(StagePresenter.insertAdjacent(graph, "end", StagePresenter.InsertPosition.BEFORE, "NEW").edges != null)
    }

    @Test
    fun `insertAdjacent AFTER splices a new node between an anchor and its successor, preserving the displaced edge's label`() {
        val graph = BpmnGraph(
            id = "g", nodes = listOf(node("A", BpmnNodeKind.TASK), node("B", BpmnNodeKind.TASK)),
            edges = listOf(edge("A", "B", "next")),
        )
        val outcome = StagePresenter.insertAdjacent(graph, "A", StagePresenter.InsertPosition.AFTER, "NEW")
        assertEquals(null, outcome.blockedReason)
        val edges = outcome.edges!!
        assertTrue(edges.any { it.sourceId == "A" && it.targetId == "NEW" })
        val tail = edges.single { it.sourceId == "NEW" && it.targetId == "B" }
        assertEquals("next", tail.name)
    }

    @Test
    fun `insertAdjacent BEFORE splices a new node between an anchor and its predecessor`() {
        val graph = BpmnGraph(
            id = "g", nodes = listOf(node("A", BpmnNodeKind.TASK), node("B", BpmnNodeKind.TASK)),
            edges = listOf(edge("A", "B")),
        )
        val outcome = StagePresenter.insertAdjacent(graph, "B", StagePresenter.InsertPosition.BEFORE, "NEW")
        assertEquals(null, outcome.blockedReason)
        val edges = outcome.edges!!
        assertTrue(edges.any { it.sourceId == "A" && it.targetId == "NEW" })
        assertTrue(edges.any { it.sourceId == "NEW" && it.targetId == "B" })
    }

    @Test
    fun `authorityEnvelope splits watched cloud models from on-device ones and counts side effects`() {
        val cloud = ModelSpec(
            id = "c1", displayName = "Cloud Model", family = "test", contextWindow = 4096,
            tokenizerId = "t", templateId = TemplateId.LLAMA3, runtime = Runtime.CLOUD, providerId = "anthropic", watched = true,
        )
        val onDevice = ModelSpec(
            id = "m1", displayName = "Local Model", family = "test", contextWindow = 4096,
            tokenizerId = "t", templateId = TemplateId.LLAMA3, runtime = Runtime.LOCAL_GGUF, modelPath = "/x.gguf",
        )
        val graph = BpmnGraph(
            id = "g", nodes = listOf(
                node("start", BpmnNodeKind.START_EVENT, "Start"),
                node("t1", BpmnNodeKind.TASK, "T1", ext = mapOf("model" to "c1", "sideEffectClass" to "DESTRUCTIVE")),
                node("t2", BpmnNodeKind.TASK, "T2", ext = mapOf("model" to "m1")),
            ),
            edges = listOf(edge("start", "t1"), edge("t1", "t2")),
        )
        val envelope = StagePresenter.authorityEnvelope(graph, listOf(cloud, onDevice))
        assertEquals(listOf("Cloud Model"), envelope.watchedCloudModels)
        assertEquals(listOf("Local Model"), envelope.onDeviceModels)
        assertEquals(1, envelope.sideEffectCounts["DESTRUCTIVE"])
        assertEquals(1, envelope.undeclaredSideEffectTaskCount)
    }
}
