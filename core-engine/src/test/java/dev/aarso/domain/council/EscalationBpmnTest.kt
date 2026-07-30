package dev.aarso.domain.council

import dev.aarso.domain.bpmn.BpmnArchive
import dev.aarso.domain.bpmn.BpmnNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationBpmnTest {

    private val policy = EscalationPolicy(
        autoBudget = Budget(tokens = 10_000, moneyCents = 50),
        gates = listOf(
            Gate(Approver(ApproverKind.AGENT, "Skeptic"), Budget(tokens = 50_000)),
            Gate(Approver(ApproverKind.TEAM_MEMBER, "Lead"), Budget(tokens = 100_000, moneyCents = 500)),
            Gate(Approver(ApproverKind.USER, "you"), Budget.UNLIMITED),
        ),
        autoBudgetWithTestsGreen = Budget(tokens = 30_000, moneyCents = 150),
    )

    @Test fun `expands into a gateway plus one approval task per rung`() {
        val g = EscalationBpmn.expand(policy)
        // a single exclusive gateway (the budget test) ...
        val gateways = g.nodes.filter { it.kind == BpmnNodeKind.EXCLUSIVE_GATEWAY }
        assertEquals(1, gateways.size)
        // ... and a task per gate: AGENT -> service task, humans -> user task.
        val tasks = g.nodes.filter { it.kind == BpmnNodeKind.SERVICE_TASK || it.kind == BpmnNodeKind.USER_TASK }
        assertEquals(3, tasks.size)
        assertEquals(BpmnNodeKind.SERVICE_TASK, tasks[0].kind) // agent
        assertEquals(BpmnNodeKind.USER_TASK, tasks[1].kind)    // teammate
        assertEquals(BpmnNodeKind.USER_TASK, tasks[2].kind)    // user
        assertEquals(3, g.edges.size)
    }

    @Test fun `collapse reconstructs the original policy`() {
        assertEquals(policy, EscalationBpmn.collapse(EscalationBpmn.expand(policy)))
    }

    @Test fun `cost gate survives a full BPMN write-read round-trip`() {
        // Proves the macro transports as standard BPMN and rebuilds the matrix.
        val xml = BpmnArchive.write(EscalationBpmn.expand(policy))
        val restored = EscalationBpmn.collapse(BpmnArchive.read(xml))
        assertEquals(policy, restored)
    }

    @Test fun `collapse returns null when no macro is present`() {
        val plain = EscalationBpmn.expand(policy).copy(
            nodes = EscalationBpmn.expand(policy).nodes.map { it.copy(ext = emptyMap()) },
        )
        assertNull(EscalationBpmn.collapse(plain))
    }

    @Test fun `lead gateway carries the macro marker`() {
        val g = EscalationBpmn.expand(policy)
        val lead = g.nodes.first { it.kind == BpmnNodeKind.EXCLUSIVE_GATEWAY }
        assertNotNull(lead.ext["macro"])
        assertTrue(lead.ext["gates"]!!.contains("AGENT:Skeptic"))
    }
}
