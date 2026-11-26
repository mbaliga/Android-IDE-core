package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.cost.PricingBook
import dev.fonebrew.domain.cost.UsagePricing
import dev.fonebrew.domain.cost.UsageReport
import dev.fonebrew.domain.council.Generator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyBudgetTest {

    private fun seq(n: Int) = BpmnGraph(
        "seq",
        nodes = listOf(BpmnNode("s", BpmnNodeKind.START_EVENT)) +
            (1..n).map { BpmnNode("t$it", BpmnNodeKind.TASK, "T$it") } + BpmnNode("e", BpmnNodeKind.END_EVENT),
        edges = (listOf("s") + (1..n).map { "t$it" } + "e").zipWithNext().mapIndexed { i, (a, b) -> BpmnEdge("e$i", a, b) },
    )

    private val gen: (BpmnNode) -> Generator = { Generator { _, _ -> "out" } }
    private val tokens: suspend (String, String, String) -> StepTokens? = { _, _, _ -> StepTokens(100, 50, estimated = false) }

    private fun runner(costs: List<StepCost>): GraphRunner {
        var i = 0
        return GraphRunner(gen, tokenCounter = tokens, stepCost = StepCostResolver { _, _ -> costs.getOrElse(i++) { StepCost.Free } })
    }

    // ── pricing: the existing path, only with an explicit price ──────────────────

    private val book = PricingBook().with("cloud:m", UsagePricing(centsPer1kInput = 10, centsPer1kOutput = 30))

    @Test fun `on-device costs exactly zero`() {
        assertEquals(StepCost.Free, StepPricing.price(false, UsageReport(5000, 5000), "local", book))
        assertEquals(StepCost.Free, StepPricing.price(false, null, null, book))
    }

    @Test fun `cloud is priced from provider usage through UsageReport toAdviceCost`() {
        val usage = UsageReport(2000, 1000)
        val expected = usage.toAdviceCost(UsagePricing(10, 30)).moneyMinor   // the ledger's own path
        assertEquals(StepCost.Priced(expected), StepPricing.price(true, usage, "cloud:m", book))
        assertEquals(50L, expected)
    }

    @Test fun `cloud with no explicit price is unknown - the fallback placeholder is not a quote`() {
        val c = StepPricing.price(true, UsageReport(1000, 1000), "cloud:other", book)
        assertTrue(c is StepCost.Unknown)
        assertTrue((c as StepCost.Unknown).reason.contains("no price"))
    }

    @Test fun `cloud with no or zero usage is unknown`() {
        assertTrue(StepPricing.price(true, null, "cloud:m", book) is StepCost.Unknown)
        assertTrue(StepPricing.price(true, UsageReport(0, 0), "cloud:m", book) is StepCost.Unknown)
        assertTrue(StepPricing.price(true, UsageReport(1, 1), null, book) is StepCost.Unknown)
    }

    // ── enforcement ──────────────────────────────────────────────────────────────

    @Test fun `on-device steps never trip a money cap`() = runTest {
        val r = runner(emptyList()).run(seq(4), "x", budget = LoopBudget(maxSpendMinor = 1))
        assertTrue(r.reachedEnd)
        assertEquals(0L, r.totalSpendMinor)
    }

    @Test fun `the run stops BEFORE the next step once spend reaches the cap`() = runTest {
        val r = runner(listOf(StepCost.Priced(40), StepCost.Priced(40), StepCost.Priced(40)))
            .run(seq(5), "x", budget = LoopBudget(maxSpendMinor = 70))
        assertEquals("budget:spend", r.stoppedBecause)
        assertEquals(2, r.steps.size)          // 40 -> 80: step 3 never starts
        assertEquals(80L, r.totalSpendMinor)
    }

    @Test fun `a single step can overshoot the cap - stated behaviour`() = runTest {
        val r = runner(listOf(StepCost.Priced(500)))
            .run(seq(3), "x", budget = LoopBudget(maxSpendMinor = 100))
        assertEquals(1, r.steps.size)          // the first step ran in full: 500 > 100
        assertEquals("budget:spend", r.stoppedBecause)
        assertEquals(500L, r.totalSpendMinor)
    }

    @Test fun `an unknown cloud price stops the run before the next step - never unenforced`() = runTest {
        val r = runner(listOf(StepCost.Unknown("no price is set for this model")))
            .run(seq(4), "x", budget = LoopBudget(maxSpendMinor = 1000))
        assertEquals("cost unknown - cannot enforce the money budget", r.stoppedBecause)
        assertEquals(1, r.steps.size)
        assertFalse(r.reachedEnd)
    }

    @Test fun `with no resolver wired a money budget cannot be enforced and stops`() = runTest {
        val r = GraphRunner(gen, tokenCounter = tokens).run(seq(3), "x", budget = LoopBudget(maxSpendMinor = 1000))
        assertEquals("cost unknown - cannot enforce the money budget", r.stoppedBecause)
        assertEquals(1, r.steps.size)
    }

    @Test fun `an unknown price does not stop a run that has no money budget`() = runTest {
        val r = runner(listOf(StepCost.Unknown("x"), StepCost.Unknown("x"))).run(seq(2), "x", budget = LoopBudget(maxSteps = 10))
        assertTrue(r.reachedEnd)
        assertEquals(2, r.unpricedSteps)
        assertNull(r.totalSpendMinor)          // nothing priced, nothing free: unknown stays unknown
    }

    @Test fun `a zero cap allows free steps and stops after any spend`() = runTest {
        val free = runner(emptyList()).run(seq(2), "x", budget = LoopBudget(maxSpendMinor = 0))
        assertTrue(free.reachedEnd)
        val spent = runner(listOf(StepCost.Priced(1))).run(seq(3), "x", budget = LoopBudget(maxSpendMinor = 0))
        assertEquals("budget:spend", spent.stoppedBecause)
    }

    @Test fun `known overspend is reported ahead of an unknown price`() = runTest {
        val r = runner(listOf(StepCost.Priced(200), StepCost.Unknown("u"))).run(seq(5), "x", budget = LoopBudget(maxSpendMinor = 100))
        assertEquals("budget:spend", r.stoppedBecause)
    }

    @Test fun `tool steps cost zero and do not disturb the money budget`() = runTest {
        val tool = object : LoopTool {
            override val id = "t"; override val label = "t"; override val sideEffect = ToolSideEffect.READ_LOCAL
            override val args = emptyList<ToolArg>(); override val maxResultChars = 100
            override fun validate(args: Map<String, String>): String? = null
            override fun hosts(args: Map<String, String>) = emptyList<String>()
            override fun summarize(args: Map<String, String>) = ""
            override suspend fun invoke(args: Map<String, String>) = ToolOutcome.Ok("r")
        }
        val g = BpmnGraph(
            "g",
            nodes = listOf(BpmnNode("s", BpmnNodeKind.START_EVENT), BpmnNode("x", BpmnNodeKind.SERVICE_TASK, ext = ToolNodes.toExt("t", emptyMap())), BpmnNode("e", BpmnNodeKind.END_EVENT)),
            edges = listOf(BpmnEdge("1", "s", "x"), BpmnEdge("2", "x", "e")),
        )
        val r = GraphRunner(gen, tools = LoopToolRegistry(listOf(tool)))
            .run(g, "o", budget = LoopBudget(maxSpendMinor = 5), confirmation = RunConfirmation(setOf("t"), emptySet()))
        assertTrue(r.reachedEnd)
        assertEquals(0L, r.totalSpendMinor)
    }

    // ── what the editor and summary say ──────────────────────────────────────────

    @Test fun `the budget editor says plainly what is and is not enforced`() {
        val lines = BudgetEnforcementPresenter.lines(LoopBudget(maxSpendMinor = 100)).joinToString("\n")
        assertTrue(lines.contains("A single step can overshoot"))
        assertTrue(lines.contains("On-device steps cost zero"))
        assertTrue(lines.contains("STOPS rather than run unenforced"))
        assertTrue(BudgetEnforcementPresenter.lines(null).joinToString("\n").contains("Money cap: not set"))
        assertTrue(BudgetEnforcementPresenter.lines(LoopBudget(maxTokensTotal = 10)).joinToString("\n").contains("est."))
    }

    @Test fun `the summary never shows an unknown or unmeasured spend as exact`() = runTest {
        val fmt = { m: Long -> "$m¢" }
        val exact = GraphRunResult(listOf(step(StepCost.Priced(30)), step(StepCost.Free)), "reached end", true)
        assertTrue(RunSummaryPresenter.lines(exact, null, fmt).first().startsWith("Money: 30¢ from provider usage"))
        val partial = GraphRunResult(listOf(step(StepCost.Priced(30)), step(StepCost.Unknown("x"))), "x", false)
        assertTrue(RunSummaryPresenter.lines(partial, null, fmt).first().startsWith("Money: at least 30¢"))
        val none = GraphRunResult(listOf(step(StepCost.NotMeasured)), "x", false)
        assertTrue(RunSummaryPresenter.lines(none, null, fmt).first().startsWith("Money: not measured"))
    }

    @Test fun `the summary lists hosts contacted`() {
        val http = step(StepCost.Free).copy(toolId = "http_request", hosts = listOf("a.example"), sideEffect = ToolSideEffect.NETWORK, argsSummary = "GET https://a.example/", resultChars = 12)
        val r = GraphRunResult(listOf(http, http.copy(index = 1, hosts = listOf("b.example", "a.example"))), "reached end", true)
        val lines = RunSummaryPresenter.lines(r, null) { "$it" }
        assertTrue(lines.any { it == "⇄ Network: contacted a.example, b.example" })
        assertTrue(lines.any { it.startsWith("Tools: http_request×2") })
        assertEquals("⇄ http_request · GET https://a.example/ · 12 chars · 0ms", RunSummaryPresenter.toolStepLine(http))
    }

    @Test fun `stop labels are glyph-led`() {
        assertEquals("⏸", RunSummaryPresenter.stopLabel("budget:spend")!!.first)
        assertTrue(RunSummaryPresenter.stopLabel(StepPricing.UNKNOWN_STOP_REASON)!!.second.contains("cannot enforce the money budget"))
        assertTrue(RunSummaryPresenter.stopLabel("unsupported in this version: node \"a\" uses tool \"b\"")!!.second.startsWith("Unsupported in this version"))
        assertNull(RunSummaryPresenter.stopLabel("reached end"))
    }

    private fun step(cost: StepCost) = GraphStep(0, "n", "r", null, "o", cost = cost)
    private fun assertFalse(b: Boolean) = org.junit.Assert.assertFalse(b)
}
