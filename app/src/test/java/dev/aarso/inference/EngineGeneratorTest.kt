package dev.aarso.inference

import dev.aarso.domain.council.Expert
import dev.aarso.domain.council.Stop
import dev.aarso.domain.council.WorkflowRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end: a real InferenceEngine (the Echo stand-in) → EngineGenerator → the
 * WorkflowRunner loop. Proves the whole council/workflow stack runs on the inference
 * surface, not just a fake generator.
 */
class EngineGeneratorTest {

    @Test fun `the refine loop runs over a real engine via the adapter`() = runTest {
        val echo = EchoInferenceEngine()
        val gen = EngineGenerator(echo, modelPath = null) // echo ignores the path
        val runner = WorkflowRunner { gen }

        val r = runner.run(
            objective = "make it good",
            proposer = Expert("proposer", "You draft."),
            critic = Expert("critic", "You critique."),
            stop = Stop.MaxIterations(2),
        )

        assertEquals(2, r.iterations.size)
        assertTrue("each proposal should be non-empty", r.iterations.all { it.proposal.isNotBlank() })
        assertTrue(echo.isLoaded) // the adapter loaded the engine on first use
    }
}
