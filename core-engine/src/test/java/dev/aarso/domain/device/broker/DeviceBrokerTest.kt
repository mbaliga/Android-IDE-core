package dev.aarso.domain.device.broker

import dev.aarso.contracts.devices.DeviceConnection
import dev.aarso.contracts.devices.DeviceConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Exhaustive coverage of FB-RAT-DEV-001's single-arbiter rule, including the §1-named "re-validate the CURRENT lock" obligation a schema alone cannot enforce. */
class DeviceBrokerTest {

    private fun readyConnection(id: String = "conn-1") = DeviceConnection(
        connectionId = id, deviceIdentityId = "dev-1", state = DeviceConnectionState.READY,
        enteredStateAtUtc = Instant.parse("2026-08-07T09:00:00Z"), usbPermissionGranted = true,
    )

    @Test
    fun `acquiring a lock on a READY connection moves it to BUSY and grants a lock`() {
        val broker = DeviceBroker()
        broker.register(readyConnection())
        val lock = broker.acquireLock("conn-1", "user-a")
        assertEquals(DeviceConnectionState.BUSY, broker.connection("conn-1")?.state)
        assertEquals(lock.lockId, broker.connection("conn-1")?.exclusiveLock?.lockId)
    }

    @Test
    fun `acquiring a lock on an already-BUSY connection is rejected -- the single-arbiter rule`() {
        val broker = DeviceBroker()
        broker.register(readyConnection())
        broker.acquireLock("conn-1", "user-a")
        try {
            broker.acquireLock("conn-1", "user-b")
            org.junit.Assert.fail("expected NotReadyException")
        } catch (expected: DeviceBroker.NotReadyException) {
            // expected
        }
    }

    @Test
    fun `releasing with the current lockId returns the connection to READY and clears the lock`() {
        val broker = DeviceBroker()
        broker.register(readyConnection())
        val lock = broker.acquireLock("conn-1", "user-a")
        broker.releaseLock("conn-1", lock.lockId)
        assertEquals(DeviceConnectionState.READY, broker.connection("conn-1")?.state)
        assertEquals(null, broker.connection("conn-1")?.exclusiveLock)
    }

    @Test
    fun `releasing with a stale or foreign lockId is refused, not silently accepted`() {
        val broker = DeviceBroker()
        broker.register(readyConnection())
        broker.acquireLock("conn-1", "user-a")
        try {
            broker.releaseLock("conn-1", "some-other-lock-id")
            org.junit.Assert.fail("expected StaleLockException")
        } catch (expected: DeviceBroker.StaleLockException) {
            // expected
        }
        // The connection stays BUSY under its real lock -- a rejected release must not have side-effected the state.
        assertEquals(DeviceConnectionState.BUSY, broker.connection("conn-1")?.state)
    }

    @Test
    fun `isLockCurrent is the exact FB-RAT-DEV-001 section-1 obligation -- true only for the connection's live lock, right now`() {
        val broker = DeviceBroker()
        broker.register(readyConnection())
        val lock = broker.acquireLock("conn-1", "user-a")
        assertTrue(broker.isLockCurrent("conn-1", lock.lockId))
        assertFalse(broker.isLockCurrent("conn-1", "a-plausible-but-stale-lock-id"))
        broker.releaseLock("conn-1", lock.lockId)
        // The very same, previously-real lockId is no longer current once released -- a replayed
        // old lock id is structurally indistinguishable from a live one UNLESS this check is
        // performed, which is exactly the gap §1 names.
        assertFalse(broker.isLockCurrent("conn-1", lock.lockId))
    }

    @Test
    fun `operating on an unregistered connectionId throws NoSuchConnectionException, not a silent no-op`() {
        val broker = DeviceBroker()
        try {
            broker.acquireLock("never-registered", "user-a")
            org.junit.Assert.fail("expected NoSuchConnectionException")
        } catch (expected: DeviceBroker.NoSuchConnectionException) {
            // expected
        }
    }

    @Test
    fun `two different connections lock independently -- a lock on one never blocks the other`() {
        val broker = DeviceBroker()
        broker.register(readyConnection("conn-a"))
        broker.register(readyConnection("conn-b"))
        broker.acquireLock("conn-a", "user-a")
        val lockB = broker.acquireLock("conn-b", "user-b") // must not throw
        assertEquals(DeviceConnectionState.BUSY, broker.connection("conn-b")?.state)
        assertEquals(lockB.lockId, broker.connection("conn-b")?.exclusiveLock?.lockId)
    }
}
