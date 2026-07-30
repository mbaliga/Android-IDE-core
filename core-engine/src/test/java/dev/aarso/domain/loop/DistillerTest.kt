package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.BpmnArchive
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNodeKind
import dev.aarso.domain.council.Generator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistillerTest {

    /** A fake extractor that replays scripted replies, ignoring the prompt. */
    private class Scripted(vararg replies: String) : Generator {
        private val queue = ArrayDeque(replies.toList())
        var calls = 0
            private set
        override suspend fun complete(system: String, user: String): String {
            calls++
            return queue.removeFirstOrNull() ?: ""
        }
    }

    private val moaReply = """
        TITLE: Mixture of Agents
        PATTERN: fan-out-aggregate
        AGENTS: 3
        ROUNDS: 2
        AGGREGATION: synthesize
        STOP: rounds
        ROLE: Proposer | Draft an answer to the objective
        ROLE: Aggregator | Synthesise the proposals into one
        SUMMARY: N proposers answer in parallel; an aggregator synthesises per layer.
    """.trimIndent()

    private suspend fun distill(gen: Generator, id: String = "loop_x") =
        Distiller(gen).distill(
            source = "(method text)",
            sourceLabel = "Wang et al. 2024 (arXiv:2406.04692)",
            distilledBy = "Echo (test)",
            distilledOn = "2026-06-18",
            id = id,
        )

    private fun graphOf(ok: DistillResult.Ok): BpmnGraph = BpmnArchive.read(ok.loop.bpmnXml!!)

    @Test fun `distils a recognised pattern into a valid Unused loop`() = runTest {
        val res = distill(Scripted(moaReply))
        assertTrue(res is DistillResult.Ok)
        val ok = res as DistillResult.Ok
        assertEquals(TopologyKind.FAN_OUT_AGGREGATE, ok.spec.pattern)
        assertEquals("Mixture of Agents", ok.loop.name)
        assertEquals(LoopState.UNUSED, ok.loop.state)
        assertNotNull(ok.loop.bpmnXml)
        val g = graphOf(ok)
        assertEquals(3, g.nodes.count { it.ext["role"] == "Proposer" })
        // the bpmn is valid, transportable BPMN (round-trips)
        assertEquals(g, BpmnArchive.read(BpmnArchive.write(g)))
    }

    @Test fun `provenance is stamped into the start event of the bpmn`() = runTest {
        val ok = distill(Scripted(moaReply)) as DistillResult.Ok
        val start = graphOf(ok).nodes.first { it.kind == BpmnNodeKind.START_EVENT }
        assertEquals("Echo (test)", start.ext["distilledBy"])
        assertEquals("2026-06-18", start.ext["distilledOn"])
        assertEquals("fan-out-aggregate", start.ext["pattern"])
        assertEquals("Wang et al. 2024 (arXiv:2406.04692)", start.ext["source"])
    }

    @Test fun `nothing defaults to a watched cloud model (on-device default)`() = runTest {
        val ok = distill(Scripted(moaReply)) as DistillResult.Ok
        for (n in graphOf(ok).nodes) {
            assertFalse(n.ext["watched"] == "true")
            assertFalse(n.ext.containsKey("model"))
        }
    }

    @Test fun `repairs after an unparseable first reply`() = runTest {
        val gen = Scripted("Here is the method described in prose with no structure", moaReply)
        assertTrue(distill(gen) is DistillResult.Ok)
        assertEquals(2, gen.calls)
    }

    @Test fun `falls back to a pipeline when the pattern is unrecognised`() = runTest {
        val weird = """
            TITLE: Weird Method
            PATTERN: quantum-entangle
            AGENTS: 2
            ROLE: Extract | pull the facts
            ROLE: Compose | write the answer
            SUMMARY: a two-step thing
        """.trimIndent()
        val gen = Scripted(weird, weird)
        val ok = distill(gen) as DistillResult.Ok
        assertEquals(TopologyKind.PIPELINE, ok.spec.pattern)
        assertEquals(2, gen.calls)
        assertEquals(2, graphOf(ok).nodes.count { it.kind == BpmnNodeKind.SERVICE_TASK })
    }

    @Test fun `fails cleanly when nothing usable comes back`() = runTest {
        assertTrue(distill(Scripted("no structure at all", "still nothing useful")) is DistillResult.Failed)
    }

    @Test fun `clamps an absurd agent count`() = runTest {
        val ok = distill(Scripted(moaReply.replace("AGENTS: 3", "AGENTS: 99"))) as DistillResult.Ok
        assertEquals(6, graphOf(ok).nodes.count { it.ext["role"] == "Proposer" })
    }

    @Test fun `every topology builds a valid round-tripping graph`() = runTest {
        val patterns = mapOf(
            "sample-vote" to "ROLE: Solver | reason then answer",
            "refine-loop" to "ROLE: Actor | attempt\nROLE: Evaluator | score\nROLE: Reflector | feedback",
            "debate" to "ROLE: Debater | argue\nROLE: Judge | decide",
            "fan-out-aggregate" to "ROLE: Proposer | answer\nROLE: Aggregator | merge",
            "pipeline" to "ROLE: A | step a\nROLE: B | step b",
        )
        for ((kind, roles) in patterns) {
            val reply = "TITLE: T\nPATTERN: $kind\nAGENTS: 3\nROUNDS: 2\nSUMMARY: s\n$roles"
            val ok = distill(Scripted(reply), id = "loop_$kind") as DistillResult.Ok
            assertEquals(TopologyKind.from(kind), ok.spec.pattern)
            val g = graphOf(ok)
            assertEquals("one start in $kind", 1, g.nodes.count { it.kind == BpmnNodeKind.START_EVENT })
            assertTrue("an end in $kind", g.nodes.any { it.kind == BpmnNodeKind.END_EVENT })
            val ids = g.nodes.map { it.id }.toSet()
            assertTrue("edges resolve in $kind", g.edges.all { it.sourceId in ids && it.targetId in ids })
            assertEquals("round-trips ($kind)", g, BpmnArchive.read(BpmnArchive.write(g)))
        }
    }
}
