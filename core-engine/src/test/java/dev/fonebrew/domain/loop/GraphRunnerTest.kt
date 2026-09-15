package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.Bounds
import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import dev.fonebrew.domain.council.Generator
import dev.fonebrew.domain.loop.authoring.GatewayConditionPresenter
import dev.fonebrew.domain.loop.authoring.TouchConnectionGrammar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRunnerTest {

    /** A plain linear graph — start → t1 → t2 → t3 → end, no gateways — for tests where
     *  branching would just be noise (budgets, params, ordering, cancellation). */
    private fun sequentialGraph(steps: Int = 3, systemPrompt: String = "") = BpmnGraph(
        id = "seq",
        nodes = buildList {
            add(BpmnNode("start", BpmnNodeKind.START_EVENT))
            for (i in 1..steps) {
                add(
                    BpmnNode(
                        "t$i", BpmnNodeKind.TASK, "Task $i",
                        ext = if (systemPrompt.isNotEmpty()) mapOf("systemPrompt" to systemPrompt) else emptyMap(),
                    ),
                )
            }
            add(BpmnNode("end", BpmnNodeKind.END_EVENT))
        },
        edges = buildList {
            add(BpmnEdge("e0", "start", "t1"))
            for (i in 1 until steps) add(BpmnEdge("e$i", "t$i", "t${i + 1}"))
            add(BpmnEdge("e$steps", "t$steps", "end"))
        },
    )

    // A refine loop with a gateway: start → proposer → critic → choice →(approve) end / (refine) proposer.
    private fun refineGraph() = BpmnGraph(
        id = "g", name = "refine",
        nodes = listOf(
            BpmnNode("start", BpmnNodeKind.START_EVENT),
            BpmnNode("prop", BpmnNodeKind.TASK, "Proposer", Bounds(0.0, 0.0), mapOf("role" to "proposer", "model" to "qwen")),
            BpmnNode("crit", BpmnNodeKind.TASK, "Critic", Bounds(0.0, 0.0), mapOf("role" to "critic", "model" to "claude")),
            BpmnNode("gate", BpmnNodeKind.EXCLUSIVE_GATEWAY, "Choice"),
            BpmnNode("end", BpmnNodeKind.END_EVENT),
        ),
        edges = listOf(
            BpmnEdge("e1", "start", "prop"),
            BpmnEdge("e2", "prop", "crit"),
            BpmnEdge("e3", "crit", "gate"),
            BpmnEdge("e4", "gate", "end", name = "approve", condition = "approved"),
            BpmnEdge("e5", "gate", "prop", name = "refine", condition = "!approved"),
        ),
    )

    /** Critic approves on the 2nd pass; proposer always proposes. */
    private fun generators(): (BpmnNode) -> Generator {
        var criticCalls = 0
        return { node ->
            when (node.id) {
                "crit" -> Generator { _, _ -> if (++criticCalls >= 2) "APPROVE looks good" else "needs work" }
                else -> Generator { _, _ -> "proposal v${node.id}" }
            }
        }
    }

    @Test fun `a non-linear graph runs the refine loop and reaches the end`() = runTest {
        val result = GraphRunner(generators()).run(refineGraph(), objective = "tighten it")
        assertTrue(result.reachedEnd)
        assertEquals("reached end", result.stoppedBecause)
        // proposer, critic(needs work), proposer, critic(APPROVE) = 4 task steps
        assertEquals(4, result.steps.size)
        assertEquals(listOf("proposer", "critic", "proposer", "critic"), result.steps.map { it.role })
        assertTrue(result.finalOutput.startsWith("APPROVE"))
    }

    @Test fun `the step cap bounds a loop that never approves`() = runTest {
        val never: (BpmnNode) -> Generator = { node ->
            if (node.id == "crit") Generator { _, _ -> "still not good" } else Generator { _, _ -> "again" }
        }
        val result = GraphRunner(never).run(refineGraph(), objective = "x", hardCap = 5)
        assertFalse(result.reachedEnd)
        assertTrue(result.stoppedBecause.contains("step cap"))
        assertEquals(5, result.steps.size)
    }

    @Test fun `a missing start event is reported, not crashed`() = runTest {
        val g = BpmnGraph("g", nodes = listOf(BpmnNode("end", BpmnNodeKind.END_EVENT)))
        val result = GraphRunner({ Generator { _, _ -> "" } }).run(g, "x")
        assertFalse(result.reachedEnd)
        assertEquals("no start event", result.stoppedBecause)
    }

    @Test fun `the model behind each step is captured`() = runTest {
        val result = GraphRunner(generators()).run(refineGraph(), "x")
        assertEquals("qwen", result.steps.first { it.role == "proposer" }.model)
        assertEquals("claude", result.steps.first { it.role == "critic" }.model)
    }

    // ── P3: params + refuse-to-start ────────────────────────────────────────────

    @Test fun `param substitution resolves placeholders in the objective and node system prompts`() = runTest {
        var seenSystem: String? = null
        var seenUser: String? = null
        val runner = GraphRunner(generatorFor = {
            Generator { system, user -> seenSystem = system; seenUser = user; "ok" }
        })
        runner.run(
            sequentialGraph(steps = 1, systemPrompt = "Focus on \${aspect}."),
            objective = "Improve \${feature} now.",
            params = mapOf("feature" to "login", "aspect" to "latency"),
        )
        assertEquals("Focus on latency.", seenSystem)
        assertTrue(seenUser!!.contains("Improve login now."))
    }

    @Test fun `a run refuses to start when a param is missing, and never calls a generator`() = runTest {
        var called = false
        val runner = GraphRunner(generatorFor = { Generator { _, _ -> called = true; "should not run" } })
        val result = runner.run(
            sequentialGraph(steps = 1, systemPrompt = "Use \${a} and \${b}."),
            objective = "go",
            params = mapOf("a" to "x"),
        )
        assertFalse(called)
        assertTrue(result.steps.isEmpty())
        assertFalse(result.reachedEnd)
        assertEquals("missing params: b", result.stoppedBecause)
    }

    @Test fun `every existing GraphRunner caller compiles and runs unchanged with no params or budget`() = runTest {
        // No params/budget/onStep supplied — mirrors every pre-P3 call site (LoopRoom.kt,
        // and the tests above this line).
        val result = GraphRunner(generators()).run(refineGraph(), objective = "tighten it", hardCap = 5)
        assertTrue(result.reachedEnd)
    }

    // ── P3: budget stop on each axis ─────────────────────────────────────────────

    @Test fun `budget stops on maxSteps and never starts the over-run step`() = runTest {
        val result = GraphRunner(generatorFor = { Generator { _, _ -> "out" } })
            .run(sequentialGraph(steps = 5), objective = "x", budget = LoopBudget(maxSteps = 2))
        assertEquals(2, result.steps.size)
        assertEquals("budget:steps", result.stoppedBecause)
        assertFalse(result.reachedEnd)
    }

    @Test fun `budget stops on maxWallMs using the injected clock, before the over-run step`() = runTest {
        var t = 0L
        val result = GraphRunner(
            generatorFor = { Generator { _, _ -> t += 1000; "out" } },
            now = { t },
        ).run(sequentialGraph(steps = 5), objective = "x", budget = LoopBudget(maxWallMs = 1500))
        // step1: elapsed 0 -> runs (t=1000); step2: elapsed 1000 -> runs (t=2000);
        // step3: elapsed 2000 >= 1500 -> stop, never runs.
        assertEquals(2, result.steps.size)
        assertEquals("budget:wall", result.stoppedBecause)
        assertEquals(2000L, result.elapsedMs)
    }

    @Test fun `budget stops on maxTokensTotal using the wired counter, before the over-run step`() = runTest {
        val result = GraphRunner(
            generatorFor = { Generator { _, _ -> "out" } },
            tokenCounter = { _, _, _ -> StepTokens(inputTokens = 40, outputTokens = 10, estimated = false) },
        ).run(sequentialGraph(steps = 5), objective = "x", budget = LoopBudget(maxTokensTotal = 90))
        // each step is 50 tokens; the check runs BEFORE a step using totals from completed steps
        // only (a step's own cost isn't known in advance): before step1 total=0, before step2
        // total=50, before step3 total=100 >= 90 -> stop. Steps 1 and 2 already ran (100 total).
        assertEquals(2, result.steps.size)
        assertEquals("budget:tokens", result.stoppedBecause)
        assertEquals(100L, (result.totalTokensIn ?: 0) + (result.totalTokensOut ?: 0))
    }

    @Test fun `hardCap remains the engine safety net even when no budget is set`() = runTest {
        val result = GraphRunner(generatorFor = { Generator { _, _ -> "out" } })
            .run(sequentialGraph(steps = 20), objective = "x", hardCap = 3)
        assertEquals(3, result.steps.size)
        assertEquals("hit step cap (3)", result.stoppedBecause)
    }

    // ── P3: onStep ordering + cancellation ──────────────────────────────────────

    @Test fun `onStep fires once per completed step, in ascending order`() = runTest {
        val seen = mutableListOf<Int>()
        val result = GraphRunner(generatorFor = { Generator { _, _ -> "out" } })
            .run(sequentialGraph(steps = 4), objective = "x", onStep = { seen += it.index })
        assertEquals(listOf(0, 1, 2, 3), seen)
        assertEquals(seen, result.steps.map { it.index })
    }

    @Test fun `cancelling the caller's coroutine stops the run gracefully and preserves completed steps`() = runTest {
        lateinit var job: Job
        var result: GraphRunResult? = null
        val seen = mutableListOf<Int>()
        job = launch {
            result = GraphRunner(generatorFor = { node -> Generator { _, _ -> "out-${node.id}" } })
                .run(
                    sequentialGraph(steps = 5),
                    objective = "x",
                    onStep = { step ->
                        seen += step.index
                        if (step.index == 1) job.cancel() // ask for cancellation right after step 2
                    },
                )
        }
        job.join()

        // steps 0 and 1 completed and were observed; step 2 never started.
        assertEquals(listOf(0, 1), seen)
        assertEquals(2, result?.steps?.size)
        assertEquals("cancelled", result?.stoppedBecause)
        assertFalse(result!!.reachedEnd)
    }

    // ── P3: totals math + estimated propagation ─────────────────────────────────

    @Test fun `totals sum only steps with a counted value, and estimated propagates per step`() = runTest {
        var call = 0
        val result = GraphRunner(
            generatorFor = { Generator { _, _ -> "out" } },
            tokenCounter = { _, _, _ ->
                call++
                when (call) {
                    1 -> StepTokens(inputTokens = 10, outputTokens = 5, estimated = false) // cloud, authoritative
                    2 -> StepTokens(inputTokens = 7, outputTokens = 3, estimated = true) // on-device, estimated
                    else -> null // uncounted
                }
            },
        ).run(sequentialGraph(steps = 3), objective = "x")

        assertEquals(3, result.steps.size)
        assertEquals(10L, result.steps[0].tokensIn); assertEquals(5L, result.steps[0].tokensOut)
        assertFalse(result.steps[0].estimated)
        assertEquals(7L, result.steps[1].tokensIn); assertEquals(3L, result.steps[1].tokensOut)
        assertTrue(result.steps[1].estimated)
        assertNull(result.steps[2].tokensIn); assertNull(result.steps[2].tokensOut)
        assertTrue(result.steps[2].estimated) // no counter data -> never asserted authoritative

        assertEquals(17L, result.totalTokensIn)
        assertEquals(8L, result.totalTokensOut)
    }

    @Test fun `totals are null, not zero, when no step ever reports a token count`() = runTest {
        val result = GraphRunner(generatorFor = { Generator { _, _ -> "out" } })
            .run(sequentialGraph(steps = 2), objective = "x")
        assertNull(result.totalTokensIn)
        assertNull(result.totalTokensOut)
    }

    @Test fun `durationMs and elapsedMs are measured from the injected clock`() = runTest {
        var t = 0L
        val result = GraphRunner(generatorFor = { Generator { _, _ -> t += 100; "out" } }, now = { t })
            .run(sequentialGraph(steps = 3), objective = "x")
        assertTrue(result.steps.all { it.durationMs == 100L })
        assertEquals(300L, result.elapsedMs)
    }

    // ── Gateway condition editor (asoc-reachability audit, 2026-09-15): an edge authored
    // through GatewayConditionPresenter/TouchConnectionGrammar — not hand-typed — must actually
    // route a real run, the same way LoopRoom.kt's toBpmnGraph turns a COMMITTED draft into a
    // BpmnEdge (name = draft.label, condition = draft.conditionExpression). ─────────────────────

    @Test fun `an edge condition committed through GatewayConditionPresenter actually routes a real run`() = runTest {
        var draft = GatewayConditionPresenter.start("gate", "end")
        draft = (GatewayConditionPresenter.chooseLabel(draft, "approve", condition = "approved") as TouchConnectionGrammar.Result.Advanced).draft
        draft = (GatewayConditionPresenter.defineCondition(draft, "approved") as TouchConnectionGrammar.Result.Advanced).draft
        draft = (GatewayConditionPresenter.requestPreview(draft) as TouchConnectionGrammar.Result.Advanced).draft
        draft = (GatewayConditionPresenter.commit(draft) as TouchConnectionGrammar.Result.Advanced).draft
        assertEquals(dev.fonebrew.domain.loop.authoring.ConnectionDraftState.COMMITTED, draft.state)

        // Exactly how LoopRoom.kt's toBpmnGraph builds every other edge — no special-cased path
        // for an authored-through-the-grammar one.
        val editedEdge = BpmnEdge(
            id = "edited", sourceId = draft.sourceNodeId!!, targetId = draft.destinationNodeId!!,
            name = draft.label, condition = draft.conditionExpression,
        )

        val graph = BpmnGraph(
            id = "g",
            nodes = listOf(
                BpmnNode("start", BpmnNodeKind.START_EVENT),
                BpmnNode("crit", BpmnNodeKind.TASK, "Critic"),
                BpmnNode("gate", BpmnNodeKind.EXCLUSIVE_GATEWAY, "Choice"),
                BpmnNode("end", BpmnNodeKind.END_EVENT),
                // A wrongly-routed run would step onto this TASK next (it has no outgoing edge,
                // so it would then stop with "no outgoing edge" instead of reaching "end") — an
                // externally observable difference between the two branches, not just an internal
                // policy-match assertion.
                BpmnNode("decoy", BpmnNodeKind.TASK, "Decoy"),
            ),
            edges = listOf(
                BpmnEdge("e1", "start", "crit"),
                BpmnEdge("e2", "crit", "gate"),
                editedEdge, // gate -> end, condition "approved" (authored via the presenter)
                BpmnEdge("e4", "gate", "decoy", name = "refine", condition = "!approved"),
            ),
        )
        val result = GraphRunner({ Generator { _, _ -> "APPROVE, ship it" } }).run(graph, objective = "x")
        assertTrue(result.reachedEnd)
        assertEquals("reached end", result.stoppedBecause)
        assertEquals(1, result.steps.size) // only "crit" ran; "decoy" never did
        assertEquals("crit", result.steps.single().nodeId)
    }
}
