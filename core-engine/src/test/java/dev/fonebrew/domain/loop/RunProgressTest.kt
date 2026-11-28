package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.council.Generator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunProgressTest {

    private fun linear() = BpmnGraph(
        id = "seq",
        nodes = listOf(
            BpmnNode("start", BpmnNodeKind.START_EVENT),
            BpmnNode("a", BpmnNodeKind.TASK, "A", ext = mapOf("role" to "alpha", "model" to "m1")),
            BpmnNode("b", BpmnNodeKind.TASK, "B"),
            BpmnNode("end", BpmnNodeKind.END_EVENT),
        ),
        edges = listOf(BpmnEdge("e0", "start", "a"), BpmnEdge("e1", "a", "b"), BpmnEdge("e2", "b", "end")),
    )

    // start → prop → crit → gw →(approve) end / (refine) prop
    private fun refine() = BpmnGraph(
        id = "r",
        nodes = listOf(
            BpmnNode("start", BpmnNodeKind.START_EVENT),
            BpmnNode("prop", BpmnNodeKind.TASK, "Proposer"),
            BpmnNode("crit", BpmnNodeKind.TASK, "Critic"),
            BpmnNode("gw", BpmnNodeKind.EXCLUSIVE_GATEWAY, "ok?"),
            BpmnNode("end", BpmnNodeKind.END_EVENT),
        ),
        edges = listOf(
            BpmnEdge("e0", "start", "prop"), BpmnEdge("e1", "prop", "crit"), BpmnEdge("e2", "crit", "gw"),
            BpmnEdge("yes", "gw", "end", name = "approve"), BpmnEdge("no", "gw", "prop", name = "refine"),
        ),
    )

    private suspend fun collect(graph: BpmnGraph, gen: Generator, hardCap: Int = 24): Pair<GraphRunResult, List<RunProgress>> {
        val events = ArrayList<RunProgress>()
        var clock = 1000L
        val result = GraphRunner({ gen }, now = { clock += 10; clock })
            .run(graph, "obj", hardCap = hardCap, onEvent = { events += it })
        return result to events
    }

    @Test fun `events arrive in order and end with RunFinished`() = runTest {
        val (res, ev) = collect(linear(), Generator { _, _ -> "out" })
        assertTrue(res.reachedEnd)
        assertTrue(ev.first() is RunProgress.RunStarted)
        assertTrue(ev.last() is RunProgress.RunFinished)
        val starts = ev.filterIsInstance<RunProgress.StepStarted>()
        val dones = ev.filterIsInstance<RunProgress.StepCompleted>()
        assertEquals(listOf("a", "b"), starts.map { it.nodeId })
        assertEquals(listOf("alpha", "B"), starts.map { it.role })
        assertEquals("m1", starts[0].model)
        assertEquals(2, dones.size)
        // each StepStarted precedes its StepCompleted
        assertTrue(ev.indexOf(starts[0]) < ev.indexOf(dones[0]))
        assertTrue(ev.indexOf(dones[0]) < ev.indexOf(starts[1]))
        // every node is entered, edges taken in walk order
        assertEquals(listOf("a", "b", "end"), ev.filterIsInstance<RunProgress.NodeEntered>().drop(1).map { it.nodeId })
        assertEquals(listOf("e0", "e1", "e2"), ev.filterIsInstance<RunProgress.EdgeTaken>().map { it.edgeId })
        val fin = ev.last() as RunProgress.RunFinished
        assertEquals("reached end", fin.stoppedBecause)
        assertEquals(2, fin.stepCount)
    }

    @Test fun `no observer means unchanged behaviour`() = runTest {
        val r = GraphRunner({ Generator { _, _ -> "x" } }).run(linear(), "obj")
        assertTrue(r.reachedEnd)
        assertEquals(2, r.steps.size)
    }

    @Test fun `a streaming generator produces deltas that rebuild the output`() = runTest {
        val gen = object : StreamingGenerator {
            override suspend fun stream(system: String, user: String, onDelta: suspend (String) -> Unit): String {
                listOf("he", "llo", " world").forEach { onDelta(it) }
                return "hello world"
            }
        }
        val (res, ev) = collect(linear(), gen)
        val deltas0 = ev.filterIsInstance<RunProgress.StepDelta>().filter { it.stepIndex == 0 }
        assertEquals("hello world", deltas0.joinToString("") { it.text })
        assertEquals("hello world", res.steps[0].output)
        // deltas sit between start and completion
        val s = ev.indexOfFirst { it is RunProgress.StepStarted }
        val c = ev.indexOfFirst { it is RunProgress.StepCompleted }
        assertTrue(ev.indices.filter { ev[it] is RunProgress.StepDelta }.take(3).all { it in (s + 1) until c })
    }

    @Test fun `a plain generator emits no deltas`() = runTest {
        val (_, ev) = collect(linear(), Generator { _, _ -> "x" })
        assertTrue(ev.none { it is RunProgress.StepDelta })
    }

    @Test fun `a gateway decision names the chosen and the rejected edge and loop-backs repeat`() = runTest {
        var critCalls = 0
        val gen = Generator { system, _ ->
            if (system == "Critic") { critCalls++; if (critCalls < 2) "REFINE it" else "APPROVE" } else "draft"
        }
        val (res, ev) = collect(refine(), gen)
        assertTrue(res.reachedEnd)
        val decisions = ev.filterIsInstance<RunProgress.GatewayDecided>()
        assertEquals(listOf("no", "yes"), decisions.map { it.chosenEdgeId })
        assertEquals(listOf("yes"), decisions[0].alternativeEdgeIds)
        assertEquals("approve", decisions[1].chosenLabel)
        // the loop-back edge was crossed once; the proposer ran twice → two agents for one node
        val starts = ev.filterIsInstance<RunProgress.StepStarted>().filter { it.nodeId == "prop" }
        assertEquals(2, starts.size)
        assertEquals(2, starts.map { it.agentId }.toSet().size)
    }

    @Test fun `cancellation still delivers RunFinished`() = runTest {
        val events = ArrayList<RunProgress>()
        var job: Job? = null
        val gen = Generator { _, _ -> job?.cancel(); kotlinx.coroutines.yield(); "x" }
        var result: GraphRunResult? = null
        job = launch { result = GraphRunner({ gen }).run(linear(), "o", onEvent = { events += it }) }
        job.join()
        assertTrue(events.last() is RunProgress.RunFinished)
        assertEquals("cancelled", (events.last() as RunProgress.RunFinished).stoppedBecause)
    }

    @Test fun `a failing generator emits StepFailed and RunFinished then rethrows`() = runTest {
        val events = ArrayList<RunProgress>()
        try {
            GraphRunner({ Generator { _, _ -> error("boom") } }).run(linear(), "o", onEvent = { events += it })
            fail("should rethrow")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertTrue(events.any { it is RunProgress.StepFailed && it.message == "boom" })
        assertEquals("error: boom", (events.last() as RunProgress.RunFinished).stoppedBecause)
    }

    @Test fun `a throwing observer never breaks the run`() = runTest {
        val r = GraphRunner({ Generator { _, _ -> "x" } }).run(linear(), "o", onEvent = { error("observer bug") })
        assertTrue(r.reachedEnd)
    }

    @Test fun `budget stop and step cap each end with RunFinished`() = runTest {
        val (_, ev) = collect(linear(), Generator { _, _ -> "x" }, hardCap = 1)
        assertEquals("hit step cap (1)", (ev.last() as RunProgress.RunFinished).stoppedBecause)
    }

    // ───────────── reducer ─────────────

    @Test fun `the view is a pure fold of the stream`() = runTest {
        val gen = object : StreamingGenerator {
            override suspend fun stream(system: String, user: String, onDelta: suspend (String) -> Unit): String {
                onDelta("par"); onDelta("tial"); return "partial!"
            }
        }
        val (_, ev) = collect(linear(), gen)
        // mid-run: after the first delta, node a is ACTIVE with the streamed text
        val mid = RunViewReducer.fold(ev.takeWhile { it !is RunProgress.StepCompleted })
        assertEquals(RunPhase.RUNNING, mid.phase)
        assertEquals(setOf("a"), mid.activeNodeIds)
        assertEquals("partial", mid.agents["step-0"]!!.text)
        assertEquals(AgentStatus.RUNNING, mid.agents["step-0"]!!.status)
        // final
        val done = RunViewReducer.fold(ev)
        assertEquals(RunPhase.FINISHED, done.phase)
        assertTrue(done.activeNodeIds.isEmpty())
        assertEquals(NodeRunStatus.DONE, done.nodeStatus["a"])
        assertEquals(NodeRunStatus.DONE, done.nodeStatus["end"])
        assertEquals("partial!", done.agents["step-0"]!!.text)
        assertEquals(AgentStatus.DONE, done.agents[RunProgress.ROOT_AGENT]!!.status)
        assertEquals(2, done.steps.size)
    }

    @Test fun `tokens stay null when nothing measured them`() = runTest {
        val (_, ev) = collect(linear(), Generator { _, _ -> "x" })
        val v = RunViewReducer.fold(ev)
        assertNull(v.tokensIn); assertNull(v.tokensOut)
    }

    @Test fun `tree nests children and attaches orphans at the top`() {
        var v = RunViewReducer.reduce(RunView.IDLE, RunProgress.RunStarted(0, "o", 3, 10))
        v = RunViewReducer.reduce(v, RunProgress.StepStarted(1, 0, "n", "lead", null))
        v = RunViewReducer.reduce(v, RunProgress.StepStarted(2, 1, "n2", "sub", null, agentId = "sub-1", parentAgentId = "step-0"))
        v = RunViewReducer.reduce(v, RunProgress.StepStarted(3, 2, "n3", "stray", null, agentId = "stray", parentAgentId = "never-announced"))
        val rows = v.tree().map { it.depth to it.agent.id }
        assertEquals(listOf(0 to "run", 1 to "step-0", 2 to "sub-1", 0 to "stray"), rows.let { r ->
            // root subtree first (run → step-0 → sub-1), then the orphan
            r
        })
    }

    @Test fun `long streamed text keeps the tail and flags truncation`() {
        var v = RunViewReducer.reduce(RunView.IDLE, RunProgress.RunStarted(0, "o", 1, 1))
        v = RunViewReducer.reduce(v, RunProgress.StepStarted(1, 0, "n", "r", null))
        repeat(10) { v = RunViewReducer.reduce(v, RunProgress.StepDelta(2, 0, "n", "x".repeat(1000))) }
        val a = v.agents["step-0"]!!
        assertEquals(RunViewReducer.TEXT_CAP, a.text.length)
        assertTrue(a.truncated)
    }

    @Test fun `unfinished agents are closed as cancelled when the run ends`() {
        var v = RunViewReducer.reduce(RunView.IDLE, RunProgress.RunStarted(0, "o", 1, 1))
        v = RunViewReducer.reduce(v, RunProgress.StepStarted(1, 0, "n", "r", null))
        v = RunViewReducer.reduce(v, RunProgress.RunFinished(9, "cancelled", false, 0, 9))
        assertEquals(AgentStatus.CANCELLED, v.agents["step-0"]!!.status)
        assertEquals(AgentStatus.CANCELLED, v.agents[RunProgress.ROOT_AGENT]!!.status)
        assertEquals(NodeRunStatus.IDLE, v.nodeStatus["n"])
    }

    @Test fun `elapsed ticks while running and freezes after`() {
        var v = RunViewReducer.reduce(RunView.IDLE, RunProgress.RunStarted(100, "o", 1, 1))
        assertEquals(50L, v.elapsedMs(150))
        v = RunViewReducer.reduce(v, RunProgress.RunFinished(300, "reached end", true, 0, 200))
        assertEquals(200L, v.elapsedMs(9999))
    }

    // ───────────── codec ─────────────

    @Test fun `every event survives an encode-decode round trip`() = runTest {
        val gen = object : StreamingGenerator {
            override suspend fun stream(system: String, user: String, onDelta: suspend (String) -> Unit): String {
                onDelta("line one\n\"quoted\" \\ é"); return "line one\n\"quoted\" \\ é"
            }
        }
        val events = ArrayList<RunProgress>()
        var clock = 0L
        GraphRunner({ gen }, tokenCounter = { _, _, _ -> StepTokens(3, 4, estimated = true) }, now = { clock += 1; clock })
            .run(refine(), "obj", hardCap = 3, onEvent = { events += it })
        val text = RunProgressCodec.encodeAll(events)
        assertFalse("one event per line", text.lines().any { it.isBlank() })
        assertEquals(events, RunProgressCodec.decodeAll(text))
        // and the view they fold to is identical
        assertEquals(RunViewReducer.fold(events), RunViewReducer.fold(RunProgressCodec.decodeAll(text)))
    }

    @Test fun `unknown, malformed and future events decode to null instead of throwing`() {
        assertNull(RunProgressCodec.decode(""))
        assertNull(RunProgressCodec.decode("not json"))
        assertNull(RunProgressCodec.decode("""{"v":1,"t":1,"e":"something_new"}"""))
        assertNull(RunProgressCodec.decode("""{"v":99,"t":1,"e":"run_finished","stoppedBecause":"x","reachedEnd":true,"stepCount":0,"elapsedMs":0}"""))
        assertNotNull(RunProgressCodec.decode("""{"v":1,"t":1,"e":"edge_taken","edgeId":"e","from":"a","to":"b","extraField":1}"""))
    }
}
