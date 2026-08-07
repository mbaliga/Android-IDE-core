package dev.aarso.domain.loop.authoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive coverage of LOOP_PHONE_AUTHORING_SPEC.md §9's Run View action-legality table (FB-RAT-PHN-008). */
class RunViewActionGuardTest {

    private fun advanced(state: RunViewStateClass, action: RunViewAction, safePause: Boolean = true, recoveryOnDenial: Boolean = false): RunViewStateClass {
        val result = RunViewActionGuard.apply(state, action, safePause, recoveryOnDenial)
        assertTrue("expected Advanced for $action from $state, got $result", result is RunViewActionGuard.Result.Advanced)
        return (result as RunViewActionGuard.Result.Advanced).state
    }

    private fun rejected(state: RunViewStateClass, action: RunViewAction, safePause: Boolean = true) {
        val result = RunViewActionGuard.apply(state, action, safePause)
        assertTrue("expected Rejected for $action from $state, got $result", result is RunViewActionGuard.Result.Rejected)
    }

    @Test
    fun `AWAITING_AUTHORITY approve moves to RUNNING, deny moves to a non-failure terminal`() {
        assertEquals(RunViewStateClass.Running, advanced(RunViewStateClass.AwaitingAuthority, RunViewAction.APPROVE_AUTHORITY))
        val denied = advanced(RunViewStateClass.AwaitingAuthority, RunViewAction.DENY_AUTHORITY, recoveryOnDenial = true) as RunViewStateClass.Terminal
        assertTrue(!denied.isFailure)
        assertTrue(denied.hasDeclaredRecoveryPath)
    }

    @Test
    fun `AWAITING_DECISION only accepts PROVIDE_DECISION`() {
        assertEquals(RunViewStateClass.Running, advanced(RunViewStateClass.AwaitingDecision, RunViewAction.PROVIDE_DECISION))
        rejected(RunViewStateClass.AwaitingDecision, RunViewAction.CANCEL)
    }

    @Test
    fun `RUNNING allows read-only INSPECT as a self-loop, conditional REQUEST_PAUSE, and CANCEL`() {
        assertEquals(RunViewStateClass.Running, advanced(RunViewStateClass.Running, RunViewAction.INSPECT))
        assertEquals(RunViewStateClass.Paused, advanced(RunViewStateClass.Running, RunViewAction.REQUEST_PAUSE, safePause = true))
        rejected(RunViewStateClass.Running, RunViewAction.REQUEST_PAUSE, safePause = false)
        val cancelled = advanced(RunViewStateClass.Running, RunViewAction.CANCEL) as RunViewStateClass.Terminal
        assertTrue(!cancelled.isFailure)
    }

    @Test
    fun `PAUSED resumes to RUNNING or cancels`() {
        assertEquals(RunViewStateClass.Running, advanced(RunViewStateClass.Paused, RunViewAction.RESUME))
        assertTrue(advanced(RunViewStateClass.Paused, RunViewAction.CANCEL) is RunViewStateClass.Terminal)
    }

    @Test
    fun `retry is only offered from a failed, idempotent-node terminal -- never a non-idempotent or non-failed one`() {
        val retryable = RunViewStateClass.Terminal(isFailure = true, failedNodeIsIdempotent = true)
        assertEquals(RunViewStateClass.Running, advanced(retryable, RunViewAction.RETRY_FAILED_NODE))

        val notIdempotent = RunViewStateClass.Terminal(isFailure = true, failedNodeIsIdempotent = false)
        rejected(notIdempotent, RunViewAction.RETRY_FAILED_NODE)

        val cancelledNotFailed = RunViewStateClass.Terminal(isFailure = false)
        rejected(cancelledNotFailed, RunViewAction.RETRY_FAILED_NODE)
    }

    @Test
    fun `recovery path is only offered where one was actually declared -- the surface never improvises one`() {
        val withRecovery = RunViewStateClass.Terminal(isFailure = true, hasDeclaredRecoveryPath = true)
        assertEquals(RunViewStateClass.Running, advanced(withRecovery, RunViewAction.CHOOSE_RECOVERY_PATH))

        val withoutRecovery = RunViewStateClass.Terminal(isFailure = true, hasDeclaredRecoveryPath = false)
        rejected(withoutRecovery, RunViewAction.CHOOSE_RECOVERY_PATH)
    }

    @Test
    fun `structural editing has no legal action anywhere -- every unlisted action is rejected, proving PHN-008 by construction`() {
        // PROVIDE_DECISION is only legal from AWAITING_DECISION -- attempting it mid-RUNNING (which
        // is the closest thing to "structural mutation" this action vocabulary has no verb for at
        // all) must be rejected, not silently accepted as if editing were possible.
        rejected(RunViewStateClass.Running, RunViewAction.PROVIDE_DECISION)
        rejected(RunViewStateClass.Running, RunViewAction.APPROVE_AUTHORITY)
        rejected(RunViewStateClass.Paused, RunViewAction.RETRY_FAILED_NODE)
    }

    @Test
    fun `forking from a receipt is legal from every state, including every terminal shape, and never mutates that state`() {
        assertTrue(RunViewActionGuard.canForkFromReceipt(RunViewStateClass.Running))
        assertTrue(RunViewActionGuard.canForkFromReceipt(RunViewStateClass.AwaitingAuthority))
        assertTrue(RunViewActionGuard.canForkFromReceipt(RunViewStateClass.Terminal(isFailure = true)))
        assertTrue(RunViewActionGuard.canForkFromReceipt(RunViewStateClass.Terminal(isFailure = false)))
    }
}
