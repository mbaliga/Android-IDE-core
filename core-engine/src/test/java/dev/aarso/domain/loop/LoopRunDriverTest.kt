package dev.aarso.domain.loop

import dev.aarso.contracts.loops.BaseRevisionRef
import dev.aarso.contracts.loops.DurableObjectRef
import dev.aarso.contracts.loops.RunState
import dev.aarso.contracts.loops.TerminalReasonCategory
import dev.aarso.domain.bpmn.BpmnEdge
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNode
import dev.aarso.domain.bpmn.BpmnNodeKind
import dev.aarso.domain.council.Generator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopRunDriverTest {

    private fun sequentialGraph(steps: Int = 2) = BpmnGraph(
        id = "seq",
        nodes = buildList {
            add(BpmnNode("start", BpmnNodeKind.START_EVENT))
            for (i in 1..steps) add(BpmnNode("t$i", BpmnNodeKind.TASK, "Task $i"))
            add(BpmnNode("end", BpmnNodeKind.END_EVENT))
        },
        edges = buildList {
            add(BpmnEdge("e0", "start", "t1"))
            for (i in 1 until steps) add(BpmnEdge("e$i", "t$i", "t${i + 1}"))
            add(BpmnEdge("e$steps", "t$steps", "end"))
        },
    )

    private val sourceRef = BaseRevisionRef.DraftBase("draft-1", 1)
    private val bindingRef = DurableObjectRef("binding-1")

    private fun driver(gen: (BpmnNode) -> Generator = { Generator { _, _ -> "output" } }) =
        LoopRunDriver(GraphRunner(gen))

    /** Every event in [events] must be a structurally valid §9 transition -- the strongest
     *  possible check, since [dev.aarso.contracts.loops.RunEvent]'s own constructor already
     *  throws for an illegal one; this re-derives it from the visited state sequence as a
     *  belt-and-suspenders assertion that the FULL sequence (not just each pairwise hop) is sane. */
    private fun assertLegalEventSequence(events: List<dev.aarso.contracts.loops.RunEvent>) {
        assertEquals(RunState.CREATED, events.first().toState)
        assertNull(events.first().fromState)
        for (i in 1 until events.size) {
            val e = events[i]
            assertEquals(events[i - 1].toState, e.fromState)
            assertTrue("illegal transition ${e.fromState} -> ${e.toState}", RunState.isValidTransition(e.fromState!!, e.toState))
        }
    }

    @Test
    fun `a run that reaches the end event succeeds unverified, visiting every required state in order`() = runTest {
        val run = driver().run(sequentialGraph(), "objective", sourceRef, bindingRef)

        assertEquals(RunState.SUCCEEDED_UNVERIFIED, run.runState)
        assertTrue(run.runState.isTerminal)
        assertEquals(TerminalReasonCategory.REQUIRED_VERIFIER_INCOMPLETE, run.terminalReason?.category)
        assertLegalEventSequence(run.eventLog)

        val visited = run.eventLog.map { it.toState }
        assertEquals(
            listOf(RunState.CREATED, RunState.PREFLIGHT, RunState.WAITING_BINDING, RunState.WAITING_AUTHORITY, RunState.READY, RunState.RUNNING, RunState.SUCCEEDED_UNVERIFIED),
            visited,
        )
    }

    @Test
    fun `nodeAttempts mirror GraphRunner's real steps, one per executed task node`() = runTest {
        val run = driver().run(sequentialGraph(steps = 3), "objective", sourceRef, bindingRef)
        assertEquals(3, run.nodeAttempts.size)
        assertEquals(listOf("t1", "t2", "t3"), run.nodeAttempts.map { it.nodeId })
    }

    @Test
    fun `missing params fails at PREFLIGHT with VALIDATION_FAILURE, never reaching RUNNING`() = runTest {
        val run = driver().run(sequentialGraph(), "objective needs \${missing_key}", sourceRef, bindingRef)
        assertEquals(RunState.FAILED_SAFE, run.runState)
        assertEquals(TerminalReasonCategory.VALIDATION_FAILURE, run.terminalReason?.category)
        assertTrue(RunState.RUNNING !in run.eventLog.map { it.toState })
        assertLegalEventSequence(run.eventLog)
    }

    @Test
    fun `a declined authority resolution cancels the run via CANCELLING, never straight to CANCELLED`() = runTest {
        val run = driver().run(sequentialGraph(), "objective", sourceRef, bindingRef, resolveAuthority = { false })
        assertEquals(RunState.CANCELLED, run.runState)
        val visited = run.eventLog.map { it.toState }
        assertTrue(RunState.CANCELLING in visited)
        assertEquals(visited.indexOf(RunState.CANCELLING) + 1, visited.indexOf(RunState.CANCELLED))
        assertLegalEventSequence(run.eventLog)
    }

    @Test
    fun `an unresolved binding cancels the run before ever reaching WAITING_AUTHORITY`() = runTest {
        val run = driver().run(sequentialGraph(), "objective", sourceRef, bindingRef, resolveBinding = { false })
        assertEquals(RunState.CANCELLED, run.runState)
        assertTrue(RunState.WAITING_AUTHORITY !in run.eventLog.map { it.toState })
        assertLegalEventSequence(run.eventLog)
    }

    @Test
    fun `a budget-exhausted run stops at STOPPED_BUDGET with the correct exhaustion category`() = runTest {
        val run = driver().run(sequentialGraph(steps = 5), "objective", sourceRef, bindingRef, budget = LoopBudget(maxSteps = 2))
        assertEquals(RunState.STOPPED_BUDGET, run.runState)
        assertEquals(TerminalReasonCategory.BUDGET_STEPS_EXHAUSTED, run.terminalReason?.category)
        assertLegalEventSequence(run.eventLog)
    }

    @Test
    fun `definitionDigest is a well-formed sha256 reference, matching the LoopRun contract's own regex`() = runTest {
        val run = driver().run(sequentialGraph(), "objective", sourceRef, bindingRef)
        assertTrue(run.definitionDigest.matches(Regex("^sha256:[0-9a-f]{64}\$")))
    }

    @Test
    fun `outputs carries the final step's output as the primary result`() = runTest {
        val run = driver({ Generator { _, _ -> "final answer" } }).run(sequentialGraph(steps = 1), "objective", sourceRef, bindingRef)
        assertEquals("final answer", run.outputs?.primaryResult)
    }
}
