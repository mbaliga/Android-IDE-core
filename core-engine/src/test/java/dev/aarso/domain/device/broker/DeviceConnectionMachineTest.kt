package dev.aarso.domain.device.broker

import dev.aarso.contracts.devices.DeviceConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exhaustive coverage of DEVICE_STATE_AND_SAFETY_SPEC.md §3's DeviceConnection table (FB-RAT-DEV-003). */
class DeviceConnectionMachineTest {

    private fun advanced(state: DeviceConnectionState, event: DeviceConnectionMachine.Event): DeviceConnectionState {
        val result = DeviceConnectionMachine.apply(state, event)
        assertTrue("expected Advanced for $event from $state, got $result", result is DeviceConnectionMachine.Result.Advanced)
        return (result as DeviceConnectionMachine.Result.Advanced).state
    }

    private fun rejected(state: DeviceConnectionState, event: DeviceConnectionMachine.Event) {
        assertTrue(DeviceConnectionMachine.apply(state, event) is DeviceConnectionMachine.Result.Rejected)
    }

    @Test
    fun `the full happy path from DISCOVERED to READY, then BUSY and back`() {
        var state = DeviceConnectionState.DISCOVERED
        state = advanced(state, DeviceConnectionMachine.Event.RequestUsbPermission)
        assertEquals(DeviceConnectionState.PERMISSION_REQUIRED, state)
        state = advanced(state, DeviceConnectionMachine.Event.PermissionGranted)
        assertEquals(DeviceConnectionState.CONNECTING, state)
        state = advanced(state, DeviceConnectionMachine.Event.HandshakeSucceededOperatingTarget)
        assertEquals(DeviceConnectionState.READY, state)
        state = advanced(state, DeviceConnectionMachine.Event.OperationAuthorized)
        assertEquals(DeviceConnectionState.BUSY, state)
        state = advanced(state, DeviceConnectionMachine.Event.OperationReachedTerminal)
        assertEquals(DeviceConnectionState.READY, state)
    }

    @Test
    fun `denying USB permission goes straight to DISCONNECTED, not CONNECTING`() {
        assertEquals(DeviceConnectionState.DISCONNECTED, advanced(DeviceConnectionState.PERMISSION_REQUIRED, DeviceConnectionMachine.Event.PermissionDenied))
    }

    @Test
    fun `CONNECTING has all four documented outcomes -- READY, BOOTLOADER, DISCONNECTED, UNSUPPORTED`() {
        assertEquals(DeviceConnectionState.READY, advanced(DeviceConnectionState.CONNECTING, DeviceConnectionMachine.Event.HandshakeSucceededOperatingTarget))
        assertEquals(DeviceConnectionState.BOOTLOADER, advanced(DeviceConnectionState.CONNECTING, DeviceConnectionMachine.Event.HandshakeDetectedBootloader))
        assertEquals(DeviceConnectionState.DISCONNECTED, advanced(DeviceConnectionState.CONNECTING, DeviceConnectionMachine.Event.HandshakeFailed))
        assertEquals(DeviceConnectionState.UNSUPPORTED, advanced(DeviceConnectionState.CONNECTING, DeviceConnectionMachine.Event.HandshakeUnrecognizedFamily))
    }

    @Test
    fun `STATE_UNKNOWN is reachable from READY, BUSY, and BOOTLOADER on an ambiguous connectivity loss`() {
        assertEquals(DeviceConnectionState.STATE_UNKNOWN, advanced(DeviceConnectionState.READY, DeviceConnectionMachine.Event.ConnectivityLostAmbiguously))
        assertEquals(DeviceConnectionState.STATE_UNKNOWN, advanced(DeviceConnectionState.BUSY, DeviceConnectionMachine.Event.ConnectivityLostAmbiguously))
        assertEquals(DeviceConnectionState.STATE_UNKNOWN, advanced(DeviceConnectionState.BOOTLOADER, DeviceConnectionMachine.Event.ConnectivityLostAmbiguously))
    }

    @Test
    fun `STATE_UNKNOWN and DISCONNECTED are never conflated -- STATE_UNKNOWN resolves independently, via its own two events`() {
        assertEquals(DeviceConnectionState.READY, advanced(DeviceConnectionState.STATE_UNKNOWN, DeviceConnectionMachine.Event.ReconnectSucceeded))
        assertEquals(DeviceConnectionState.DISCONNECTED, advanced(DeviceConnectionState.STATE_UNKNOWN, DeviceConnectionMachine.Event.ReconnectFailed))
        // DISCONNECTED itself only reaches CONNECTING, never READY directly -- a real reconnect handshake is required.
        rejected(DeviceConnectionState.DISCONNECTED, DeviceConnectionMachine.Event.ReconnectSucceeded)
    }

    @Test
    fun `UNSUPPORTED is a true dead end -- no event is legal from it`() {
        rejected(DeviceConnectionState.UNSUPPORTED, DeviceConnectionMachine.Event.ReconnectRequested)
        rejected(DeviceConnectionState.UNSUPPORTED, DeviceConnectionMachine.Event.RequestUsbPermission)
    }

    @Test
    fun `a clean unplug from READY, BUSY, or BOOTLOADER always reaches DISCONNECTED, never STATE_UNKNOWN`() {
        assertEquals(DeviceConnectionState.DISCONNECTED, advanced(DeviceConnectionState.READY, DeviceConnectionMachine.Event.CleanUnplugDetected))
        assertEquals(DeviceConnectionState.DISCONNECTED, advanced(DeviceConnectionState.BUSY, DeviceConnectionMachine.Event.CleanUnplugDetected))
        assertEquals(DeviceConnectionState.DISCONNECTED, advanced(DeviceConnectionState.BOOTLOADER, DeviceConnectionMachine.Event.CleanUnplugDetected))
    }

    @Test
    fun `isValidTransition agrees with apply() for the full happy path and correctly rejects a skipped state`() {
        assertTrue(DeviceConnectionMachine.isValidTransition(DeviceConnectionState.DISCOVERED, DeviceConnectionState.PERMISSION_REQUIRED))
        assertTrue(DeviceConnectionMachine.isValidTransition(DeviceConnectionState.READY, DeviceConnectionState.BUSY))
        assertTrue(DeviceConnectionMachine.isValidTransition(DeviceConnectionState.STATE_UNKNOWN, DeviceConnectionState.DISCONNECTED))
        assertTrue(!DeviceConnectionMachine.isValidTransition(DeviceConnectionState.DISCOVERED, DeviceConnectionState.READY))
        assertTrue(!DeviceConnectionMachine.isValidTransition(DeviceConnectionState.UNSUPPORTED, DeviceConnectionState.CONNECTING))
    }
}
