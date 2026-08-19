package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopParamsTest {

    @Test fun `placeholdersIn finds every distinct key in first-seen order`() {
        assertEquals(
            listOf("feature", "reviewer"),
            LoopParams.placeholdersIn("Improve \${feature} per \${reviewer}, then re-check \${feature}."),
        )
    }

    @Test fun `placeholdersIn is empty for plain text`() {
        assertTrue(LoopParams.placeholdersIn("no templating here").isEmpty())
    }

    @Test fun `scan collects keys from the objective and every node system prompt`() {
        val graph = BpmnGraph(
            id = "g",
            nodes = listOf(
                BpmnNode("start", BpmnNodeKind.START_EVENT),
                BpmnNode("t1", BpmnNodeKind.TASK, ext = mapOf("systemPrompt" to "Focus on \${aspect}.")),
                BpmnNode("t2", BpmnNodeKind.TASK, ext = mapOf("systemPrompt" to "Address \${aspect} and \${tone}.")),
                BpmnNode("end", BpmnNodeKind.END_EVENT),
            ),
        )
        assertEquals(listOf("thing", "aspect", "tone"), LoopParams.scan(graph, "Do \${thing} now."))
    }

    @Test fun `missing lists only keys params does not supply`() {
        val graph = BpmnGraph(
            id = "g",
            nodes = listOf(BpmnNode("t1", BpmnNodeKind.TASK, ext = mapOf("systemPrompt" to "Use \${a} and \${b}."))),
        )
        assertEquals(listOf("b"), LoopParams.missing(graph, objective = "\${a}", params = mapOf("a" to "x")))
    }

    @Test fun `missing is empty once every key has a value`() {
        val graph = BpmnGraph(
            id = "g",
            nodes = listOf(BpmnNode("t1", BpmnNodeKind.TASK, ext = mapOf("systemPrompt" to "Use \${a} and \${b}."))),
        )
        assertTrue(LoopParams.missing(graph, "\${a}", mapOf("a" to "x", "b" to "y")).isEmpty())
    }

    @Test fun `substitute replaces every occurrence of a key`() {
        assertEquals(
            "Improve login per Alice, then re-check login.",
            LoopParams.substitute(
                "Improve \${feature} per \${reviewer}, then re-check \${feature}.",
                mapOf("feature" to "login", "reviewer" to "Alice"),
            ),
        )
    }

    @Test fun `substitute leaves an unresolved key untouched rather than blanking it`() {
        assertEquals(
            "Use x and \${b}.",
            LoopParams.substitute("Use \${a} and \${b}.", mapOf("a" to "x")),
        )
    }
}
