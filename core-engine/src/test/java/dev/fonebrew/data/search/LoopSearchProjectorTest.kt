package dev.fonebrew.data.search

import dev.fonebrew.domain.bpmn.BpmnArchive
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.bpmn.Bounds
import dev.fonebrew.domain.loop.Loop
import dev.fonebrew.domain.loop.LoopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-Kotlin projection tests — no database, mirrors SearchProjectorTest's own shape. */
class LoopSearchProjectorTest {

    private fun bpmn(nodeName: String, systemPrompt: String? = null, role: String? = null): String {
        val ext = LinkedHashMap<String, String>()
        systemPrompt?.let { ext["systemPrompt"] = it }
        role?.let { ext["role"] = it }
        val graph = BpmnGraph(
            id = "g1",
            name = "Review loop",
            nodes = listOf(
                BpmnNode("start", BpmnNodeKind.START_EVENT, "Start", Bounds(0.0, 0.0)),
                BpmnNode("t1", BpmnNodeKind.TASK, nodeName, Bounds(100.0, 0.0), ext = ext),
                BpmnNode("end", BpmnNodeKind.END_EVENT, "End", Bounds(200.0, 0.0)),
            ),
        )
        return BpmnArchive.write(graph)
    }

    @Test fun `title is the loop's own name, not the BPMN graph name`() {
        val loop = Loop(id = "l1", name = "My Loop", bpmnXml = bpmn("Proposer"))
        val row = LoopSearchProjector.project(listOf(loop)).single()
        assertEquals("My Loop", row.titleRaw)
    }

    @Test fun `body includes node labels and instructions`() {
        val loop = Loop(id = "l1", name = "My Loop", bpmnXml = bpmn("Draft", systemPrompt = "review the PR carefully"))
        val row = LoopSearchProjector.project(listOf(loop)).single()
        assertTrue(row.bodyRaw.contains("Draft"))
        assertTrue(row.bodyRaw.contains("review the PR carefully"))
    }

    @Test fun `body includes a node's role extension too`() {
        val loop = Loop(id = "l1", name = "My Loop", bpmnXml = bpmn("Critic", role = "Reviewer"))
        val row = LoopSearchProjector.project(listOf(loop)).single()
        assertTrue(row.bodyRaw.contains("Reviewer"))
    }

    @Test fun `a brand-new draft with no saved graph is still findable by name alone`() {
        val loop = Loop(id = "l1", name = "Untitled loop", bpmnXml = null)
        val row = LoopSearchProjector.project(listOf(loop)).single()
        assertEquals("Untitled loop", row.titleRaw)
        assertTrue(row.bodyRaw.isEmpty())
    }

    @Test fun `malformed BPMN degrades to name-only rather than throwing`() {
        val loop = Loop(id = "l1", name = "Broken", bpmnXml = "<not-bpmn>")
        val row = LoopSearchProjector.project(listOf(loop)).single()
        assertEquals("Broken", row.titleRaw)
        assertFalse(row.bodyRaw.contains("<"))
    }

    @Test fun `state is carried through as the LoopState name`() {
        val loop = Loop(id = "l1", name = "L", state = LoopState.RETIRED)
        val row = LoopSearchProjector.project(listOf(loop)).single()
        assertEquals("RETIRED", row.state)
    }

    @Test fun `segmented fields are lowercased for FTS matching`() {
        val loop = Loop(id = "l1", name = "GRADLE Build", bpmnXml = null)
        val row = LoopSearchProjector.project(listOf(loop)).single()
        assertTrue(row.title.contains("gradle"))
    }
}
