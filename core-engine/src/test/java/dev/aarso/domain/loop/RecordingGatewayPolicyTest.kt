package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.BpmnEdge
import dev.aarso.domain.bpmn.BpmnNode
import dev.aarso.domain.bpmn.BpmnNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingGatewayPolicyTest {

    private val gate = BpmnNode("gate", BpmnNodeKind.EXCLUSIVE_GATEWAY, "Choice")

    @Test fun `a gateway with two outgoing edges is recorded`() {
        val policy = RecordingGatewayPolicy()
        val approve = BpmnEdge("e-approve", "gate", "end", name = "approve", condition = "approved")
        val refine = BpmnEdge("e-refine", "gate", "prop", name = "refine", condition = "!approved")
        val chosen = policy.choose(gate, "APPROVE this", listOf(approve, refine))

        assertEquals(approve, chosen)
        assertEquals(1, policy.recorded.size)
        val rec = policy.recorded.single()
        assertEquals("gate", rec.nodeId)
        assertEquals("e-approve", rec.chosenEdgeRef)
        assertEquals(listOf("e-refine"), rec.alternativeRefs)
    }

    @Test fun `a single-outgoing-edge gateway is not a choice - never recorded`() {
        val policy = RecordingGatewayPolicy()
        val only = BpmnEdge("e-only", "gate", "end")
        val chosen = policy.choose(gate, "anything", listOf(only))

        assertEquals(only, chosen)
        assertTrue(policy.recorded.isEmpty())
    }

    @Test fun `no outgoing edges - nothing chosen, nothing recorded`() {
        val policy = RecordingGatewayPolicy()
        val chosen = policy.choose(gate, "anything", emptyList())

        assertNull(chosen)
        assertTrue(policy.recorded.isEmpty())
    }

    @Test fun `recorded accumulates across multiple gateway visits in run order`() {
        val policy = RecordingGatewayPolicy()
        val a1 = BpmnEdge("a1", "gate", "x", condition = "approved")
        val a2 = BpmnEdge("a2", "gate", "y", condition = "!approved")
        policy.choose(gate, "APPROVE", listOf(a1, a2))
        policy.choose(gate, "no good", listOf(a1, a2))

        assertEquals(2, policy.recorded.size)
        assertEquals("a1", policy.recorded[0].chosenEdgeRef)
        assertEquals("a2", policy.recorded[1].chosenEdgeRef)
    }

    @Test fun `delegates the actual choice to the inner policy`() {
        val alwaysSecond = GatewayPolicy { _, _, outgoing -> outgoing.getOrNull(1) }
        val policy = RecordingGatewayPolicy(inner = alwaysSecond)
        val e1 = BpmnEdge("e1", "gate", "x")
        val e2 = BpmnEdge("e2", "gate", "y")
        val chosen = policy.choose(gate, "", listOf(e1, e2))

        assertEquals(e2, chosen)
        assertEquals("e2", policy.recorded.single().chosenEdgeRef)
        assertEquals(listOf("e1"), policy.recorded.single().alternativeRefs)
    }

    @Test fun `recorded is a snapshot - reading it again after another choose reflects the new entry`() {
        val policy = RecordingGatewayPolicy()
        val e1 = BpmnEdge("e1", "gate", "x", condition = "approved")
        val e2 = BpmnEdge("e2", "gate", "y", condition = "!approved")
        val before = policy.recorded
        policy.choose(gate, "APPROVE", listOf(e1, e2))
        assertTrue(before.isEmpty())
        assertEquals(1, policy.recorded.size)
    }
}
