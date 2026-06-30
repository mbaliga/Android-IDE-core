package dev.aarso.domain.bpmn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BpmnArchiveTest {

    private val graph = BpmnGraph(
        id = "loop_1",
        name = "Late-attendance audit",
        nodes = listOf(
            BpmnNode("start", BpmnNodeKind.START_EVENT, "Start", Bounds(40.0, 100.0)),
            BpmnNode(
                "draft", BpmnNodeKind.USER_TASK, "Draft",
                Bounds(160.0, 90.0),
                ext = linkedMapOf("model" to "Qwen2.5-7B", "role" to "Proposer"),
            ),
            BpmnNode(
                "critique", BpmnNodeKind.SERVICE_TASK, "Critique",
                Bounds(320.0, 90.0),
                ext = linkedMapOf("model" to "Claude", "watched" to "true"),
            ),
            BpmnNode("gw", BpmnNodeKind.EXCLUSIVE_GATEWAY, "Approved?", Bounds(480.0, 96.0, 64.0, 64.0)),
            BpmnNode("end", BpmnNodeKind.END_EVENT, "Output", Bounds(600.0, 100.0)),
        ),
        edges = listOf(
            BpmnEdge("e1", "start", "draft"),
            BpmnEdge("e2", "draft", "critique"),
            BpmnEdge("e3", "critique", "gw"),
            BpmnEdge("e4", "gw", "draft", name = "no", condition = "cost > budget"),
            BpmnEdge("e5", "gw", "end", name = "yes"),
        ),
    )

    @Test fun `round-trips through standard BPMN XML`() {
        val restored = BpmnArchive.read(BpmnArchive.write(graph))
        assertEquals(graph, restored)
    }

    @Test fun `emits valid-standard BPMN structure`() {
        val xml = BpmnArchive.write(graph)
        assertTrue(xml.contains("<bpmn:definitions"))
        assertTrue(xml.contains("xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""))
        assertTrue(xml.contains("isExecutable=\"false\""))
        assertTrue(xml.contains("<bpmn:userTask id=\"draft\""))
        assertTrue(xml.contains("<bpmn:exclusiveGateway id=\"gw\""))
        // Aarso data rides in an extension element, not a custom top-level tag.
        assertTrue(xml.contains("<bpmn:extensionElements><aarso:meta"))
        assertTrue(xml.contains("model=\"Qwen2.5-7B\""))
        // layout travels in BPMN-DI
        assertTrue(xml.contains("<dc:Bounds"))
    }

    @Test fun `preserves extension data and edge conditions`() {
        val r = BpmnArchive.read(BpmnArchive.write(graph))
        assertEquals("Claude", r.node("critique")!!.ext["model"])
        assertEquals("true", r.node("critique")!!.ext["watched"])
        val loopBack = r.edges.first { it.id == "e4" }
        assertEquals("cost > budget", loopBack.condition)
        assertEquals("no", loopBack.name)
    }

    @Test fun `escapes special characters in names`() {
        val g = BpmnGraph(
            "x", "A & B <loop>",
            nodes = listOf(BpmnNode("n", BpmnNodeKind.TASK, "quote\"& <here>")),
        )
        val r = BpmnArchive.read(BpmnArchive.write(g))
        assertEquals("A & B <loop>", r.name)
        assertEquals("quote\"& <here>", r.node("n")!!.name)
    }
}
