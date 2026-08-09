package dev.aarso.domain.loop.authoring

import dev.aarso.contracts.loops.AuthorityRung
import dev.aarso.contracts.loops.BackoffStrategy
import dev.aarso.contracts.loops.Budgets
import dev.aarso.contracts.loops.BudgetCost
import dev.aarso.contracts.loops.Compensation
import dev.aarso.contracts.loops.DistributionConstraints
import dev.aarso.contracts.loops.EdgeDefinition
import dev.aarso.contracts.loops.ExecutionTargetConstraints
import dev.aarso.contracts.loops.ExecutionTargetType
import dev.aarso.contracts.loops.GatewayDefinition
import dev.aarso.contracts.loops.GatewayOutcome
import dev.aarso.contracts.loops.IdempotencyMode
import dev.aarso.contracts.loops.ImplementationReference
import dev.aarso.contracts.loops.LicenseRef
import dev.aarso.contracts.loops.LoopDefinition
import dev.aarso.contracts.loops.LoopProvenance
import dev.aarso.contracts.loops.NodeCategory
import dev.aarso.contracts.loops.NodeDefinition
import dev.aarso.contracts.loops.SchemaReference
import dev.aarso.contracts.loops.SideEffectClass
import dev.aarso.contracts.loops.TerminalRunState
import dev.aarso.contracts.loops.TerminalStateMapping
import dev.aarso.contracts.loops.TimeoutAction
import dev.aarso.contracts.loops.TimeoutPolicy
import dev.aarso.contracts.loops.VerificationPolicy
import dev.aarso.contracts.loops.VerificationRequirements
import dev.aarso.contracts.loops.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3.2, LOOP_PHONE_AUTHORING_SPEC.md -- proves [StageLinearizer]'s bounded structurability check
 * against hand-derived fixtures: a structurable diamond, the textbook non-structurable "branch
 * jumps into a sibling branch" shape, independent terminal branches, and a bounded cycle correctly
 * excluded from the acyclic structurability question entirely.
 */
class StageLinearizerTest {

    /** [LoopDefinition]'s own `init{}` does not cross-check nodeId references (see that file's
     *  header comment -- LOOP-ID-001/LOOP-GRAPH-001/002 are validator-time, not constructor-time),
     *  so one reusable minimal node satisfies construction while edges/gateways below freely
     *  reference whatever node-id strings the fixture needs. */
    private fun dummyNode(id: String) = NodeDefinition(
        nodeId = id, category = NodeCategory.DETERMINISTIC_TRANSFORMATION,
        inputPorts = emptyList(), outputPorts = emptyList(),
        implementation = ImplementationReference.TypedSlot("slot-1"),
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
        explanation = "test node",
    )

    private fun edge(id: String, from: String, to: String, gatewayId: String? = null) =
        EdgeDefinition(edgeId = id, fromNodeId = from, toNodeId = to, gatewayId = gatewayId)

    private fun definition(
        startNodeId: String,
        edges: List<EdgeDefinition>,
        gateways: List<GatewayDefinition>,
        terminalNodeIds: List<String>,
    ) = LoopDefinition(
        loopId = "loop-test", semanticVersion = "1.0.0", objective = "test objective",
        documentationRef = null,
        inputSchema = SchemaReference.Inline(emptyMap()), outputSchema = SchemaReference.Inline(emptyMap()),
        startNodeId = startNodeId, terminalNodeIds = terminalNodeIds,
        nodes = listOf(dummyNode(startNodeId)),
        edges = edges, gateways = gateways,
        budgets = Budgets(steps = 10, wallClockSeconds = 60.0, tokens = 1000, cost = BudgetCost(0.0, "USD"), toolCalls = 5),
        bindingSlots = emptyList(), requiredCapabilities = emptyList(),
        executionTargetConstraints = ExecutionTargetConstraints(permittedTargetTypes = listOf(ExecutionTargetType.LOCAL_ANDROID)),
        distributionConstraints = DistributionConstraints(),
        minEngineVersion = "1.0.0", maxEngineVersion = null,
        verificationPolicy = VerificationPolicy(requireVerificationForSuccess = false),
        terminalStateMapping = TerminalStateMapping(unverifiedSuccessAllowed = true),
        provenance = LoopProvenance(sourceLocation = "test", projectRevision = "rev-1", initiatingPrincipal = "test-user"),
        licenseRef = LicenseRef(spdxId = "Apache-2.0"),
    )

    @Test
    fun `classifyEdges finds exactly the back edge in a bounded cycle, not a forward edge`() {
        val edges = listOf(edge("e1", "A", "B"), edge("e2", "B", "C"), edge("e3", "C", "A"))
        val result = StageLinearizer.classifyEdges("A", edges)
        assertEquals(setOf("e3"), result.backEdgeIds)
        assertEquals(setOf("A"), result.cycleEntryNodeIds)
    }

    @Test
    fun `a simple diamond gateway is structurable, with the shared downstream node as its rejoin`() {
        val edges = listOf(edge("e1", "A", "B", "g1"), edge("e2", "A", "C", "g1"), edge("e3", "B", "D"), edge("e4", "C", "D"))
        val gateway = GatewayDefinition("g1", listOf(GatewayOutcome("b", "B"), GatewayOutcome("c", "C")), exhaustive = true)
        val analysis = StageLinearizer.analyze(definition("A", edges, listOf(gateway), listOf("D"))).single()
        assertTrue(analysis.structurable)
        assertEquals("D", analysis.rejoinNodeId)
        assertTrue(analysis.crossingNodeIds.isEmpty())
    }

    @Test
    fun `a branch that jumps directly into a sibling branch's interior is flagged non-structurable`() {
        // A(gateway) -> B, A -> C; B -> D -> X; C -> X; and B -> C directly is the crossing jump.
        val edges = listOf(
            edge("e1", "A", "B", "g1"), edge("e2", "A", "C", "g1"),
            edge("e3", "B", "D"), edge("e4", "D", "X"), edge("e5", "C", "X"),
            edge("e6", "B", "C"),
        )
        val gateway = GatewayDefinition("g1", listOf(GatewayOutcome("b", "B"), GatewayOutcome("c", "C")), exhaustive = true)
        val analysis = StageLinearizer.analyze(definition("A", edges, listOf(gateway), listOf("X"))).single()
        assertTrue(!analysis.structurable)
        assertTrue("C" in analysis.crossingNodeIds)
    }

    @Test
    fun `independent terminal branches that never remerge are still structurable, with a null rejoin`() {
        val edges = listOf(edge("e1", "A", "B", "g1"), edge("e2", "A", "C", "g1"))
        val gateway = GatewayDefinition("g1", listOf(GatewayOutcome("b", "B"), GatewayOutcome("c", "C")), exhaustive = true)
        val analysis = StageLinearizer.analyze(definition("A", edges, listOf(gateway), listOf("B", "C"))).single()
        assertTrue(analysis.structurable)
        assertEquals(null, analysis.rejoinNodeId)
    }

    @Test
    fun `a bounded cycle inside one branch does not itself break that gateway's structurability`() {
        // A(gateway) -> B, A -> C; B -> B2 -> B (bounded cycle inside branch B); B -> D; C -> D.
        val edges = listOf(
            edge("e1", "A", "B", "g1"), edge("e2", "A", "C", "g1"),
            edge("e3", "B", "B2"), edge("e4", "B2", "B"),
            edge("e5", "B", "D"), edge("e6", "C", "D"),
        )
        val gateway = GatewayDefinition("g1", listOf(GatewayOutcome("b", "B"), GatewayOutcome("c", "C")), exhaustive = true)
        val def = definition("A", edges, listOf(gateway), listOf("D"))

        val edgeClass = StageLinearizer.classifyEdges("A", edges)
        assertEquals(setOf("e4"), edgeClass.backEdgeIds)

        val analysis = StageLinearizer.analyze(def).single()
        assertTrue(analysis.structurable)
        assertEquals("D", analysis.rejoinNodeId)
    }

    @Test
    fun `a gateway with only one distinct branch target is trivially structurable`() {
        val edges = listOf(edge("e1", "A", "B", "g1"))
        val gateway = GatewayDefinition("g1", listOf(GatewayOutcome("only", "B")), exhaustive = true)
        val analysis = StageLinearizer.analyze(definition("A", edges, listOf(gateway), listOf("B"))).single()
        assertTrue(analysis.structurable)
        assertEquals("B", analysis.rejoinNodeId)
    }
}
