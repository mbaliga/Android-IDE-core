package dev.aarso.domain.device.broker

import dev.aarso.contracts.devices.DeviceOperationState

/**
 * `DEVICE_STATE_AND_SAFETY_SPEC.md` §4 (the flash contract's `DeviceOperation.state` table,
 * FB-RAT-DEV-004/005/006/007), made real. Same fail-closed `object` posture as
 * [DeviceConnectionMachine] and every state machine this build-out has written. This is the pure
 * state-shape law; [FlashOperationDriver] is the real orchestration that walks a concrete
 * operation through it, exactly the "machine vs. driver" split
 * [dev.aarso.domain.workspace.DocumentBufferMachine] and its own real-I/O consumers established.
 *
 * `DeviceOperationState` has **no generic `FAILED` value at all** (FB-RAT-DEV-006, enforced one
 * layer up by the enum itself, not by this machine) — every disconnect/failure resolves to
 * exactly one of the two permitted outcomes, chosen by the caller supplying [Event.DisconnectOrFailureAfterTransferBegan]/
 * [Event.VerificationFailedOrDisconnected]'s `sideEffectsConfirmed` flag, mirroring
 * `FlashOperationDriver`'s own "the broker judges, the machine just enforces which two outcomes
 * are legal" split.
 */
object DeviceOperationMachine {

    sealed interface Event {
        object PreconditionFailed : Event
        object PreconditionsPassedAuthorized : Event
        object TransferBegins : Event
        object TransferCompletedVerificationBegins : Event
        /** `sideEffectsConfirmed=true` -> [DeviceOperationState.FAILED_SIDE_EFFECTS_POSSIBLE]; `false` -> [DeviceOperationState.TARGET_STATE_UNKNOWN] (FB-RAT-DEV-006 — never a generic failure). */
        data class DisconnectOrFailureAfterTransferBegan(val sideEffectsConfirmed: Boolean) : Event
        object VerificationPassed : Event
        data class VerificationFailedOrDisconnected(val sideEffectsConfirmed: Boolean) : Event
        object Cancel : Event
    }

    sealed interface Result {
        data class Advanced(val state: DeviceOperationState) : Result
        data class Rejected(val reason: String) : Result
    }

    fun start(): DeviceOperationState = DeviceOperationState.PENDING_PRECONDITIONS

    fun apply(state: DeviceOperationState, event: Event): Result {
        if (event is Event.Cancel) {
            return if (state.isTerminal) reject(state, event) else Result.Advanced(DeviceOperationState.CANCELLED)
        }
        return when (state) {
            DeviceOperationState.PENDING_PRECONDITIONS -> when (event) {
                Event.PreconditionFailed -> Result.Advanced(DeviceOperationState.FAILED_SAFE)
                Event.PreconditionsPassedAuthorized -> Result.Advanced(DeviceOperationState.AUTHORIZED)
                else -> reject(state, event)
            }
            DeviceOperationState.AUTHORIZED -> when (event) {
                Event.TransferBegins -> Result.Advanced(DeviceOperationState.IN_PROGRESS)
                else -> reject(state, event)
            }
            DeviceOperationState.IN_PROGRESS -> when (event) {
                Event.TransferCompletedVerificationBegins -> Result.Advanced(DeviceOperationState.VERIFYING)
                is Event.DisconnectOrFailureAfterTransferBegan -> Result.Advanced(
                    if (event.sideEffectsConfirmed) DeviceOperationState.FAILED_SIDE_EFFECTS_POSSIBLE else DeviceOperationState.TARGET_STATE_UNKNOWN
                )
                else -> reject(state, event)
            }
            DeviceOperationState.VERIFYING -> when (event) {
                Event.VerificationPassed -> Result.Advanced(DeviceOperationState.SUCCEEDED)
                is Event.VerificationFailedOrDisconnected -> Result.Advanced(
                    if (event.sideEffectsConfirmed) DeviceOperationState.FAILED_SIDE_EFFECTS_POSSIBLE else DeviceOperationState.TARGET_STATE_UNKNOWN
                )
                else -> reject(state, event)
            }
            else -> reject(state, event) // every terminal state: no further event is legal (Cancel already handled above)
        }
    }

    private fun reject(state: DeviceOperationState, event: Event): Result.Rejected = Result.Rejected(
        "DeviceOperationMachine: event ${event::class.simpleName} is not legal from state $state (DEVICE_STATE_AND_SAFETY_SPEC.md §4)."
    )
}
