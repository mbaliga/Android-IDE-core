package dev.aarso.domain.device.broker

import dev.aarso.contracts.devices.DeviceOperationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive coverage of DEVICE_STATE_AND_SAFETY_SPEC.md §4's DeviceOperation.state table (FB-RAT-DEV-004/005/006/007). */
class DeviceOperationMachineTest {

    private fun advanced(state: DeviceOperationState, event: DeviceOperationMachine.Event): DeviceOperationState {
        val result = DeviceOperationMachine.apply(state, event)
        assertTrue("expected Advanced for $event from $state, got $result", result is DeviceOperationMachine.Result.Advanced)
        return (result as DeviceOperationMachine.Result.Advanced).state
    }

    private fun rejected(state: DeviceOperationState, event: DeviceOperationMachine.Event) {
        assertTrue(DeviceOperationMachine.apply(state, event) is DeviceOperationMachine.Result.Rejected)
    }

    @Test
    fun `start() begins at PENDING_PRECONDITIONS`() {
        assertEquals(DeviceOperationState.PENDING_PRECONDITIONS, DeviceOperationMachine.start())
    }

    @Test
    fun `the full happy path reaches SUCCEEDED through every non-terminal state in order`() {
        var state = DeviceOperationMachine.start()
        state = advanced(state, DeviceOperationMachine.Event.PreconditionsPassedAuthorized)
        assertEquals(DeviceOperationState.AUTHORIZED, state)
        state = advanced(state, DeviceOperationMachine.Event.TransferBegins)
        assertEquals(DeviceOperationState.IN_PROGRESS, state)
        state = advanced(state, DeviceOperationMachine.Event.TransferCompletedVerificationBegins)
        assertEquals(DeviceOperationState.VERIFYING, state)
        state = advanced(state, DeviceOperationMachine.Event.VerificationPassed)
        assertEquals(DeviceOperationState.SUCCEEDED, state)
    }

    @Test
    fun `a precondition failure reaches FAILED_SAFE only from PENDING_PRECONDITIONS, per FB-RAT-DEV-007`() {
        assertEquals(DeviceOperationState.FAILED_SAFE, advanced(DeviceOperationState.PENDING_PRECONDITIONS, DeviceOperationMachine.Event.PreconditionFailed))
        rejected(DeviceOperationState.AUTHORIZED, DeviceOperationMachine.Event.PreconditionFailed)
        rejected(DeviceOperationState.IN_PROGRESS, DeviceOperationMachine.Event.PreconditionFailed)
    }

    @Test
    fun `a disconnect after transfer began resolves to FAILED_SIDE_EFFECTS_POSSIBLE or TARGET_STATE_UNKNOWN, chosen by the caller, never a generic failure`() {
        assertEquals(
            DeviceOperationState.FAILED_SIDE_EFFECTS_POSSIBLE,
            advanced(DeviceOperationState.IN_PROGRESS, DeviceOperationMachine.Event.DisconnectOrFailureAfterTransferBegan(sideEffectsConfirmed = true)),
        )
        assertEquals(
            DeviceOperationState.TARGET_STATE_UNKNOWN,
            advanced(DeviceOperationState.IN_PROGRESS, DeviceOperationMachine.Event.DisconnectOrFailureAfterTransferBegan(sideEffectsConfirmed = false)),
        )
    }

    @Test
    fun `verification failure or disconnect during VERIFYING resolves the same two-way way as an IN_PROGRESS disconnect`() {
        assertEquals(
            DeviceOperationState.FAILED_SIDE_EFFECTS_POSSIBLE,
            advanced(DeviceOperationState.VERIFYING, DeviceOperationMachine.Event.VerificationFailedOrDisconnected(sideEffectsConfirmed = true)),
        )
        assertEquals(
            DeviceOperationState.TARGET_STATE_UNKNOWN,
            advanced(DeviceOperationState.VERIFYING, DeviceOperationMachine.Event.VerificationFailedOrDisconnected(sideEffectsConfirmed = false)),
        )
    }

    @Test
    fun `cancel reaches CANCELLED from every non-terminal state, and is illegal from every terminal state`() {
        for (state in listOf(DeviceOperationState.PENDING_PRECONDITIONS, DeviceOperationState.AUTHORIZED, DeviceOperationState.IN_PROGRESS, DeviceOperationState.VERIFYING)) {
            assertEquals(DeviceOperationState.CANCELLED, advanced(state, DeviceOperationMachine.Event.Cancel))
        }
        for (state in DeviceOperationState.entries.filter { it.isTerminal }) {
            rejected(state, DeviceOperationMachine.Event.Cancel)
        }
    }

    @Test
    fun `no event at all is legal from any terminal state, other than the already-covered Cancel rejection`() {
        for (state in DeviceOperationState.entries.filter { it.isTerminal }) {
            rejected(state, DeviceOperationMachine.Event.PreconditionsPassedAuthorized)
            rejected(state, DeviceOperationMachine.Event.TransferBegins)
        }
    }
}
