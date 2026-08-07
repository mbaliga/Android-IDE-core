package dev.aarso.domain.device.broker

import dev.aarso.contracts.devices.DeviceConnectionState

/**
 * `DEVICE_STATE_AND_SAFETY_SPEC.md` §3 (FB-RAT-DEV-003), made real: the nine-state
 * `DeviceConnection` lifecycle table, encoded exactly as [dev.aarso.domain.workspace.
 * DocumentBufferMachine] encodes its own already-ratified table -- a pure `object`, fail-closed,
 * every row of the spec's table is one branch below, anything else is [Result.Rejected]. Unlike
 * a one-shot run, this machine is long-lived and cyclic (most settled states transition back
 * out), matching `WorkspaceProviderState`'s precedent more than `ExecutionState`'s.
 */
object DeviceConnectionMachine {

    sealed interface Event {
        object RequestUsbPermission : Event
        object PermissionGranted : Event
        object PermissionDenied : Event
        object HandshakeSucceededOperatingTarget : Event
        object HandshakeDetectedBootloader : Event
        object HandshakeFailed : Event
        object HandshakeUnrecognizedFamily : Event
        object OperationAuthorized : Event
        object OperationReachedTerminal : Event
        object BootloaderEntryRequestedOrDetected : Event
        object BootloaderExitOrNormalHandshake : Event
        object CleanUnplugDetected : Event
        object ConnectivityLostAmbiguously : Event
        object ReconnectRequested : Event
        object ReconnectSucceeded : Event
        object ReconnectFailed : Event
    }

    sealed interface Result {
        data class Advanced(val state: DeviceConnectionState) : Result
        data class Rejected(val reason: String) : Result
    }

    fun apply(state: DeviceConnectionState, event: Event): Result = when (state) {
        DeviceConnectionState.DISCOVERED -> when (event) {
            Event.RequestUsbPermission -> ok(DeviceConnectionState.PERMISSION_REQUIRED)
            else -> reject(state, event)
        }
        DeviceConnectionState.PERMISSION_REQUIRED -> when (event) {
            Event.PermissionGranted -> ok(DeviceConnectionState.CONNECTING)
            Event.PermissionDenied -> ok(DeviceConnectionState.DISCONNECTED)
            else -> reject(state, event)
        }
        DeviceConnectionState.CONNECTING -> when (event) {
            Event.HandshakeSucceededOperatingTarget -> ok(DeviceConnectionState.READY)
            Event.HandshakeDetectedBootloader -> ok(DeviceConnectionState.BOOTLOADER)
            Event.HandshakeFailed -> ok(DeviceConnectionState.DISCONNECTED)
            Event.HandshakeUnrecognizedFamily -> ok(DeviceConnectionState.UNSUPPORTED)
            else -> reject(state, event)
        }
        DeviceConnectionState.READY -> when (event) {
            Event.OperationAuthorized -> ok(DeviceConnectionState.BUSY)
            Event.BootloaderEntryRequestedOrDetected -> ok(DeviceConnectionState.BOOTLOADER)
            Event.CleanUnplugDetected -> ok(DeviceConnectionState.DISCONNECTED)
            Event.ConnectivityLostAmbiguously -> ok(DeviceConnectionState.STATE_UNKNOWN)
            else -> reject(state, event)
        }
        DeviceConnectionState.BUSY -> when (event) {
            Event.OperationReachedTerminal -> ok(DeviceConnectionState.READY)
            Event.CleanUnplugDetected -> ok(DeviceConnectionState.DISCONNECTED)
            Event.ConnectivityLostAmbiguously -> ok(DeviceConnectionState.STATE_UNKNOWN)
            else -> reject(state, event)
        }
        DeviceConnectionState.BOOTLOADER -> when (event) {
            Event.BootloaderExitOrNormalHandshake -> ok(DeviceConnectionState.READY)
            Event.CleanUnplugDetected -> ok(DeviceConnectionState.DISCONNECTED)
            Event.ConnectivityLostAmbiguously -> ok(DeviceConnectionState.STATE_UNKNOWN)
            else -> reject(state, event)
        }
        DeviceConnectionState.DISCONNECTED -> when (event) {
            Event.ReconnectRequested -> ok(DeviceConnectionState.CONNECTING)
            else -> reject(state, event)
        }
        DeviceConnectionState.STATE_UNKNOWN -> when (event) {
            Event.ReconnectSucceeded -> ok(DeviceConnectionState.READY)
            Event.ReconnectFailed -> ok(DeviceConnectionState.DISCONNECTED)
            else -> reject(state, event)
        }
        DeviceConnectionState.UNSUPPORTED -> reject(state, event)
    }

    /** Belt-and-suspenders re-derivation, same discipline [dev.aarso.domain.loop.LoopRunDriver]'s `assertLegalEventSequence` test helper established: is `to` reachable from `from` by exactly one of the rows above, independent of any particular event payload? */
    fun isValidTransition(from: DeviceConnectionState, to: DeviceConnectionState): Boolean =
        possibleNextStates(from).contains(to)

    private fun possibleNextStates(from: DeviceConnectionState): Set<DeviceConnectionState> = when (from) {
        DeviceConnectionState.DISCOVERED -> setOf(DeviceConnectionState.PERMISSION_REQUIRED)
        DeviceConnectionState.PERMISSION_REQUIRED -> setOf(DeviceConnectionState.CONNECTING, DeviceConnectionState.DISCONNECTED)
        DeviceConnectionState.CONNECTING -> setOf(
            DeviceConnectionState.READY, DeviceConnectionState.BOOTLOADER,
            DeviceConnectionState.DISCONNECTED, DeviceConnectionState.UNSUPPORTED,
        )
        DeviceConnectionState.READY -> setOf(
            DeviceConnectionState.BUSY, DeviceConnectionState.BOOTLOADER,
            DeviceConnectionState.DISCONNECTED, DeviceConnectionState.STATE_UNKNOWN,
        )
        DeviceConnectionState.BUSY -> setOf(DeviceConnectionState.READY, DeviceConnectionState.DISCONNECTED, DeviceConnectionState.STATE_UNKNOWN)
        DeviceConnectionState.BOOTLOADER -> setOf(DeviceConnectionState.READY, DeviceConnectionState.DISCONNECTED, DeviceConnectionState.STATE_UNKNOWN)
        DeviceConnectionState.DISCONNECTED -> setOf(DeviceConnectionState.CONNECTING)
        DeviceConnectionState.STATE_UNKNOWN -> setOf(DeviceConnectionState.READY, DeviceConnectionState.DISCONNECTED)
        DeviceConnectionState.UNSUPPORTED -> emptySet()
    }

    private fun ok(state: DeviceConnectionState): Result = Result.Advanced(state)

    private fun reject(state: DeviceConnectionState, event: Event): Result.Rejected = Result.Rejected(
        "DeviceConnectionMachine: event ${event::class.simpleName} is not legal from state $state (DEVICE_STATE_AND_SAFETY_SPEC.md §3)."
    )
}
