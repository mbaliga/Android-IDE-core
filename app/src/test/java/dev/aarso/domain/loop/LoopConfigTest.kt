package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.BpmnArchive
import dev.aarso.domain.bpmn.BpmnNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopConfigTest {

    private val config = LoopConfig(
        id = "loop-1",
        name = "Tighten an argument",
        objective = "Make the case airtight.",
        proposer = ExpertConfig("proposer", "Proposer", "You propose.", modelId = "qwen", x = 160.0, y = 60.0),
        critic = ExpertConfig("critic", "Critic", "You critique; APPROVE only if perfect.", modelId = "claude", x = 380.0, y = 140.0),
        iterations = 3,
    )

    @Test fun `toGraph lays the canonical refine shape`() {
        val g = LoopConfigBpmn.toGraph(config)
        assertEquals(BpmnNodeKind.START_EVENT, g.node("start")!!.kind)
        assertEquals(BpmnNodeKind.END_EVENT, g.node("end")!!.kind)
        // start → proposer → critic, with refine (critic→proposer) and approve (critic→end)
        assertTrue(g.outgoing("proposer").any { it.targetId == "critic" })
        assertTrue(g.outgoing("critic").any { it.targetId == "proposer" && it.name == "refine" })
        assertTrue(g.outgoing("critic").any { it.targetId == "end" && it.name == "approve" })
    }

    @Test fun `config round-trips through the graph mapping`() {
        assertEquals(config, LoopConfigBpmn.fromGraph(LoopConfigBpmn.toGraph(config)))
    }

    @Test fun `config survives serialisation to BPMN XML and back`() {
        val xml = BpmnArchive.write(LoopConfigBpmn.toGraph(config))
        assertTrue(xml.contains("bpmn", ignoreCase = true)) // it is BPMN, not bespoke JSON
        val restored = LoopConfigBpmn.fromGraph(BpmnArchive.read(xml))
        assertEquals(config.objective, restored.objective)
        assertEquals(config.proposer.systemPrompt, restored.proposer.systemPrompt)
        assertEquals(config.critic.modelId, restored.critic.modelId)
        assertEquals(config.iterations, restored.iterations)
    }
}
