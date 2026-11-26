package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnArchive
import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.council.Generator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRunnerToolsTest {

    /** A fake tool in the repo's fake idiom: scripted outcome, counts calls, declared effect + cap. */
    private class FakeTool(
        override val id: String = "fake_local",
        override val sideEffect: ToolSideEffect = ToolSideEffect.READ_LOCAL,
        override val maxResultChars: Int = 1000,
        private val hostList: List<String> = emptyList(),
        private val outcome: () -> ToolOutcome = { ToolOutcome.Ok("fake result") },
    ) : LoopTool {
        override val label = "Fake"
        override val args = listOf(ToolArg("q", false, "q"))
        var calls = 0
        var lastArgs: Map<String, String> = emptyMap()
        override fun validate(args: Map<String, String>): String? = if (args["bad"] != null) "bad arg" else null
        override fun hosts(args: Map<String, String>) = hostList
        override fun summarize(args: Map<String, String>) = "q=${args["q"]}"
        override suspend fun invoke(args: Map<String, String>): ToolOutcome { calls++; lastArgs = args; return outcome() }
    }

    private fun graph(toolNodeExt: Map<String, String>, withModelAfter: Boolean = true): BpmnGraph {
        val nodes = buildList {
            add(BpmnNode("start", BpmnNodeKind.START_EVENT))
            add(BpmnNode("tool", BpmnNodeKind.SERVICE_TASK, "Look it up", ext = toolNodeExt))
            if (withModelAfter) add(BpmnNode("m", BpmnNodeKind.TASK, "Summarise"))
            add(BpmnNode("end", BpmnNodeKind.END_EVENT))
        }
        val edges = buildList {
            add(BpmnEdge("e1", "start", "tool"))
            if (withModelAfter) { add(BpmnEdge("e2", "tool", "m")); add(BpmnEdge("e3", "m", "end")) }
            else add(BpmnEdge("e2", "tool", "end"))
        }
        return BpmnGraph("g", nodes = nodes, edges = edges)
    }

    private val gen: (BpmnNode) -> Generator = { Generator { _, _ -> "model said ok" } }
    private fun runner(tool: LoopTool, onUser: (String) -> Unit = {}) = GraphRunner(
        generatorFor = { Generator { _, user -> onUser(user); "model said ok" } },
        tools = LoopToolRegistry(listOf(tool)),
    )
    private fun confirmFor(g: BpmnGraph, tool: LoopTool, params: Map<String, String> = emptyMap()) =
        RunConfirmation.accepting(RunPlan.of(g, LoopToolRegistry(listOf(tool)), params))

    @Test fun `a tool node runs, records tool id args size and duration, and feeds the transcript`() = runTest {
        val tool = FakeTool()
        val g = graph(ToolNodes.toExt("fake_local", mapOf("q" to "cats")))
        var seenUser = ""
        val r = runner(tool) { seenUser = it }.run(g, "obj", confirmation = confirmFor(g, tool))
        assertTrue(r.reachedEnd)
        val s = r.steps.first()
        assertEquals("fake_local", s.toolId)
        assertEquals("q=cats", s.argsSummary)
        assertEquals("fake result".length, s.resultChars)
        assertEquals(ToolSideEffect.READ_LOCAL, s.sideEffect)
        assertEquals(StepCost.Free, s.cost)
        assertEquals(0L, s.tokensIn); assertEquals(0L, s.tokensOut)
        assertEquals("tool:fake_local", s.role)
        assertTrue(seenUser, seenUser.contains("[[WORKSPACE DATA from fake_local"))
        assertTrue(seenUser.contains("fake result"))
        assertEquals(2, r.steps.size)
    }

    @Test fun `params are substituted into tool arguments`() = runTest {
        val tool = FakeTool()
        val g = graph(ToolNodes.toExt("fake_local", mapOf("q" to "find \${topic}")))
        val params = mapOf("topic" to "dogs")
        runner(tool).run(g, "obj", params = params, confirmation = confirmFor(g, tool, params))
        assertEquals("find dogs", tool.lastArgs["q"])
    }

    @Test fun `a tool param placeholder with no value refuses to start`() = runTest {
        val tool = FakeTool()
        val g = graph(ToolNodes.toExt("fake_local", mapOf("q" to "find \${topic}")))
        val r = runner(tool).run(g, "obj", confirmation = RunConfirmation.accepting(RunPlan.of(g, LoopToolRegistry(listOf(tool)), mapOf("topic" to "x"))))
        assertEquals("missing params: topic", r.stoppedBecause)
        assertEquals(0, tool.calls)
    }

    @Test fun `a tool error is recorded as a failed step and stops the run with the reason`() = runTest {
        val tool = FakeTool(outcome = { ToolOutcome.Failed("backend unavailable") })
        val g = graph(ToolNodes.toExt("fake_local", emptyMap()))
        val r = runner(tool).run(g, "obj", confirmation = confirmFor(g, tool))
        assertFalse(r.reachedEnd)
        assertEquals("tool error: fake_local: backend unavailable", r.stoppedBecause)
        assertEquals(1, r.steps.size)
        assertTrue(r.steps[0].failed)
    }

    @Test fun `a tool that throws is a failed step naming the class only`() = runTest {
        val tool = FakeTool(outcome = { throw IllegalStateException("leaks https://x/?token=SECRET") })
        val g = graph(ToolNodes.toExt("fake_local", emptyMap()))
        val r = runner(tool).run(g, "obj", confirmation = confirmFor(g, tool))
        assertEquals("tool error: fake_local: tool crashed (IllegalStateException)", r.stoppedBecause)
        assertFalse(r.stoppedBecause.contains("SECRET"))
    }

    @Test fun `a result over the tool cap is truncated with an explicit marker`() = runTest {
        val tool = FakeTool(maxResultChars = 50, outcome = { ToolOutcome.Ok("x".repeat(500)) })
        val g = graph(ToolNodes.toExt("fake_local", emptyMap()), withModelAfter = false)
        val r = runner(tool).run(g, "obj", confirmation = confirmFor(g, tool))
        val s = r.steps.single()
        assertTrue(s.truncated)
        assertTrue(s.output.startsWith("x".repeat(50)))
        assertTrue(s.output.contains("[result truncated at 50 characters]"))
        assertTrue(s.output.length < 200)
    }

    @Test fun `an unknown tool id stops the run before any step with the stated reason`() = runTest {
        var modelCalls = 0
        val g = graph(ToolNodes.toExt("teleport", emptyMap()))
        val r = GraphRunner(generatorFor = { Generator { _, _ -> modelCalls++; "x" } })
            .run(g, "obj", confirmation = RunConfirmation(setOf("teleport"), emptySet()))
        assertTrue(r.steps.isEmpty())
        assertEquals("unsupported in this version: node \"tool\" uses tool \"teleport\"", r.stoppedBecause)
        assertEquals(0, modelCalls)
    }

    @Test fun `invalid tool arguments stop before any step`() = runTest {
        val tool = FakeTool()
        val g = graph(ToolNodes.toExt("fake_local", mapOf("bad" to "1")))
        val r = runner(tool).run(g, "obj", confirmation = RunConfirmation(setOf("fake_local"), emptySet()))
        assertEquals("tool node \"tool\" is invalid: bad arg", r.stoppedBecause)
        assertEquals(0, tool.calls)
    }

    // ── the run-time gate: nothing with a tool node runs unconfirmed ─────────────

    @Test fun `a loop with a tool node never runs without a confirmation`() = runTest {
        val tool = FakeTool()
        val g = graph(ToolNodes.toExt("fake_local", emptyMap()))
        val r = runner(tool).run(g, "obj") // no confirmation passed
        assertTrue(r.steps.isEmpty())
        assertTrue(r.stoppedBecause, r.stoppedBecause.startsWith("egress not confirmed"))
        assertEquals(0, tool.calls)
    }

    @Test fun `a network tool with an unconfirmed host does not run`() = runTest {
        val tool = FakeTool(id = "net", sideEffect = ToolSideEffect.NETWORK, hostList = listOf("a.example"))
        val g = graph(ToolNodes.toExt("net", emptyMap()))
        val r = runner(tool).run(g, "obj", confirmation = RunConfirmation(setOf("net"), setOf("other.example")))
        assertTrue(r.stoppedBecause, r.stoppedBecause.contains("host a.example"))
        assertEquals(0, tool.calls)
    }

    @Test fun `hosts changed since the confirmation are refused`() = runTest {
        // The user confirmed host a, then the loop was edited to host b: the old confirmation does not cover it.
        val tool = FakeTool(id = "net", sideEffect = ToolSideEffect.NETWORK, hostList = listOf("b.example"))
        val g = graph(ToolNodes.toExt("net", emptyMap()))
        val r = runner(tool).run(g, "obj", confirmation = RunConfirmation(setOf("net"), setOf("a.example")))
        assertTrue(r.stoppedBecause.startsWith("egress not confirmed"))
        assertEquals(0, tool.calls)
    }

    @Test fun `a gateway loop-back into a network node re-checks every call`() = runTest {
        // start -> net -> gate --(refine)--> net ... : the second entry still passes the same gate.
        val tool = FakeTool(id = "net", sideEffect = ToolSideEffect.NETWORK, hostList = listOf("a.example"))
        val g = BpmnGraph(
            "loop",
            nodes = listOf(
                BpmnNode("start", BpmnNodeKind.START_EVENT),
                BpmnNode("t", BpmnNodeKind.SERVICE_TASK, ext = ToolNodes.toExt("net", emptyMap())),
                BpmnNode("gate", BpmnNodeKind.EXCLUSIVE_GATEWAY),
                BpmnNode("end", BpmnNodeKind.END_EVENT),
            ),
            edges = listOf(
                BpmnEdge("e1", "start", "t"),
                BpmnEdge("e2", "t", "gate"),
                BpmnEdge("e3", "gate", "t", name = "again", condition = "contains(fake)"),
                BpmnEdge("e4", "gate", "end"),
            ),
        )
        val r = runner(tool).run(g, "obj", hardCap = 3, confirmation = confirmFor(g, tool))
        assertEquals(3, tool.calls)                      // re-entered until the step cap, all confirmed
        assertEquals(listOf("a.example"), r.hostsContacted)
        // And with no confirmation, not even the first call happens.
        val tool2 = FakeTool(id = "net", sideEffect = ToolSideEffect.NETWORK, hostList = listOf("a.example"))
        runner(tool2).run(g, "obj", hardCap = 3)
        assertEquals(0, tool2.calls)
    }

    @Test fun `an old model-only loop runs unchanged with no confirmation and no registry`() = runTest {
        val g = BpmnGraph(
            "old",
            nodes = listOf(BpmnNode("s", BpmnNodeKind.START_EVENT), BpmnNode("t", BpmnNodeKind.TASK, "T"),
                BpmnNode("sv", BpmnNodeKind.SERVICE_TASK, "plain service task, no tool"), BpmnNode("e", BpmnNodeKind.END_EVENT)),
            edges = listOf(BpmnEdge("1", "s", "t"), BpmnEdge("2", "t", "sv"), BpmnEdge("3", "sv", "e")),
        )
        val r = GraphRunner(gen).run(g, "obj")
        assertTrue(r.reachedEnd)
        assertEquals(2, r.steps.size)
        assertTrue(r.steps.none { it.isTool })
        assertTrue(r.hostsContacted.isEmpty())
    }

    // ── gateway decisions are logged ─────────────────────────────────────────────

    @Test fun `a gateway that matches nothing logs the default-branch reason on the result`() = runTest {
        val g = BpmnGraph(
            "g",
            nodes = listOf(BpmnNode("s", BpmnNodeKind.START_EVENT), BpmnNode("t", BpmnNodeKind.TASK, "T"),
                BpmnNode("gate", BpmnNodeKind.EXCLUSIVE_GATEWAY), BpmnNode("a", BpmnNodeKind.END_EVENT), BpmnNode("b", BpmnNodeKind.END_EVENT)),
            edges = listOf(BpmnEdge("1", "s", "t"), BpmnEdge("2", "t", "gate"),
                BpmnEdge("3", "gate", "a", condition = "contains(zzz)"), BpmnEdge("4", "gate", "b")),
        )
        val r = GraphRunner(gen).run(g, "obj")
        assertEquals(listOf(GatewayDecision("gate", "4", "no condition matched - default branch")), r.gatewayDecisions)
    }

    // ── BPMN: standard, round-trips, old files unchanged ─────────────────────────

    @Test fun `a tool node is a standard serviceTask and round-trips through BPMN`() {
        val g = graph(ToolNodes.toExt("http_request", mapOf("url" to "https://example.com/a?x=1&y=<2>", "method" to "GET")))
        val xml = BpmnArchive.write(g)
        assertTrue(xml.contains("<bpmn:serviceTask id=\"tool\""))
        assertTrue(xml.contains("tool=\"http_request\""))
        assertTrue(xml.contains("arg.url="))
        val back = BpmnArchive.read(xml)
        assertEquals(g.nodes.map { it.id to it.kind }, back.nodes.map { it.id to it.kind })
        val n = back.nodes.first { it.id == "tool" }
        assertEquals("http_request", ToolNodes.toolId(n))
        assertEquals("https://example.com/a?x=1&y=<2>", ToolNodes.rawArgs(n)["url"])
        assertEquals(ToolNodes.rawArgs(g.nodes[1]), ToolNodes.rawArgs(n))
        assertTrue(ToolNodes.isToolNode(n))
    }

    @Test fun `a legacy serviceTask without a tool ext is not a tool node`() {
        assertFalse(ToolNodes.isToolNode(BpmnNode("x", BpmnNodeKind.SERVICE_TASK, ext = mapOf("model" to "m"))))
        assertFalse(ToolNodes.isToolNode(BpmnNode("x", BpmnNodeKind.TASK, ext = mapOf("tool" to "http_request"))))
        assertNull(ToolNodes.toolId(BpmnNode("x", BpmnNodeKind.SERVICE_TASK)))
    }

    @Test fun `scan finds params referenced only in tool arguments`() {
        val g = graph(ToolNodes.toExt("http_request", mapOf("url" to "https://\${host}/x")))
        assertEquals(listOf("host"), LoopParams.scan(g, "no params here"))
    }
}
