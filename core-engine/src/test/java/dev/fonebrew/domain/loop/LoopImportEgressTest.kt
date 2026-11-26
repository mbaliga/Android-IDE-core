package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopImportEgressTest {
    @Test fun `tool nodes need no model binding slot`() {
        val g = BpmnGraph(
            "g",
            nodes = listOf(
                BpmnNode("s", BpmnNodeKind.START_EVENT),
                BpmnNode("t", BpmnNodeKind.SERVICE_TASK, ext = ToolNodes.toExt("http_request", mapOf("url" to "https://a.example"))),
                BpmnNode("m", BpmnNodeKind.TASK, ext = mapOf("model" to "q")),
            ),
        )
        val slots = LoopImportReview.modelBindingSlots(g)
        assertEquals(listOf("model.q"), slots.map { it.slotId })
        assertEquals(listOf("m"), slots.single().nodeIds)
    }

    @Test fun `a graph of only tool nodes still yields the required default slot`() {
        val g = BpmnGraph("g", nodes = listOf(BpmnNode("t", BpmnNodeKind.SERVICE_TASK, ext = ToolNodes.toExt("search_workspace", emptyMap()))))
        assertEquals(listOf("model.default"), LoopImportReview.modelBindingSlots(g).map { it.slotId })
    }

    @Test fun `egress notice is derived from the graph even when the manifest declares nothing`() {
        val g = BpmnGraph("g", nodes = listOf(BpmnNode("t", BpmnNodeKind.SERVICE_TASK, ext = ToolNodes.toExt("http_request", mapOf("url" to "https://hidden.example/x")))))
        val n = EgressNotice.of(g, LoopTools.describeOnly)
        assertTrue(n.lines().first().contains("hidden.example"))
    }
}
