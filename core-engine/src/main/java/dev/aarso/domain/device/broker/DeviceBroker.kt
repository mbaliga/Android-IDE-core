package dev.aarso.domain.device.broker

import dev.aarso.contracts.devices.DeviceConnection
import dev.aarso.contracts.devices.DeviceConnectionState
import dev.aarso.contracts.devices.DeviceLock
import dev.aarso.domain.contracts.IdGenerator
import java.time.Instant

/**
 * FB-RAT-DEV-001 ("one Device Broker arbitrates serial, flash, debug, USB permission, device
 * locks"), made real. Closes exactly the gap `DEVICE_STATE_AND_SAFETY_SPEC.md` §1 names: "a real
 * Device Broker implementation MUST re-validate, at the moment a `DeviceOperation` is authorized
 * to leave `PENDING_PRECONDITIONS`, that `authority.exclusiveLockId` equals the referenced
 * `DeviceConnection`'s CURRENT `exclusiveLock.lockId`" -- [isLockCurrent] is that re-validation,
 * and [acquireLock]/[releaseLock] are the only two ways a lock's identity ever changes, so
 * nothing outside this class can produce a stale-but-plausible lock id.
 *
 * Every state change routes through [DeviceConnectionMachine] rather than re-deriving READY⇄BUSY
 * locally -- the same "adapter over the already-real machine, not a parallel re-implementation"
 * posture [dev.aarso.domain.loop.LoopInstallationDriver] takes toward `InstallationState`.
 *
 * In-memory only, one broker instance owning one connection registry -- matches the "no
 * `DeviceBroker` abstraction... a future work package's job" note in the ratified spec's §0 (this
 * pass IS that work package) and the "no consumer yet" pattern this build-out has left for every
 * comparable new piece since WP-2; a Room-backed registry is a natural follow-up once a real UI
 * surface needs broker state to survive process death.
 */
class DeviceBroker(
    private val idGenerator: () -> String = { "lock_" + IdGenerator.generate() },
    private val now: () -> Instant = Instant::now,
) {
    class NotReadyException(message: String) : Exception(message)
    class NoSuchConnectionException(message: String) : Exception(message)
    class StaleLockException(message: String) : Exception(message)

    private val connections = mutableMapOf<String, DeviceConnection>()

    fun register(connection: DeviceConnection) {
        connections[connection.connectionId] = connection
    }

    fun connection(connectionId: String): DeviceConnection? = connections[connectionId]

    /**
     * READY -> BUSY via [DeviceConnectionMachine], granting the connection's own exclusive lock.
     * The single-arbiter rule (FB-RAT-DEV-001) holds by construction: a connection can never end
     * up holding two locks, because [DeviceConnection]'s own `init{}` forbids a non-null
     * `exclusiveLock` outside `BUSY`, and this is the only path into `BUSY`.
     */
    fun acquireLock(connectionId: String, holderPrincipal: String, operationId: String? = null): DeviceLock {
        val connection = connections[connectionId] ?: throw NoSuchConnectionException("DeviceBroker: no registered connection '$connectionId'.")
        val result = DeviceConnectionMachine.apply(connection.state, DeviceConnectionMachine.Event.OperationAuthorized)
        if (result !is DeviceConnectionMachine.Result.Advanced) {
            throw NotReadyException("DeviceBroker: connection '$connectionId' cannot be locked from state ${connection.state}: ${(result as DeviceConnectionMachine.Result.Rejected).reason}")
        }
        val lock = DeviceLock(lockId = idGenerator(), holderPrincipal = holderPrincipal, acquiredAtUtc = now(), operationId = operationId)
        connections[connectionId] = connection.copy(
            state = result.state, exclusiveLock = lock, enteredStateAtUtc = now(), stateReason = "locked by $holderPrincipal",
        )
        return lock
    }

    /** BUSY -> READY via [DeviceConnectionMachine], clearing the lock. Only the CURRENT lock's id releases it — a stale or foreign lockId is refused, not silently accepted. */
    fun releaseLock(connectionId: String, lockId: String) {
        val connection = connections[connectionId] ?: throw NoSuchConnectionException("DeviceBroker: no registered connection '$connectionId'.")
        if (connection.exclusiveLock?.lockId != lockId) {
            throw StaleLockException(
                "DeviceBroker: releaseLock lockId '$lockId' does not match connection '$connectionId's current lock " +
                    "(${connection.exclusiveLock?.lockId}) -- refusing to release a stale or foreign lock."
            )
        }
        val result = DeviceConnectionMachine.apply(connection.state, DeviceConnectionMachine.Event.OperationReachedTerminal)
        check(result is DeviceConnectionMachine.Result.Advanced) { "DeviceBroker: internal invariant violated -- a BUSY connection must accept OperationReachedTerminal." }
        connections[connectionId] = connection.copy(state = result.state, exclusiveLock = null, enteredStateAtUtc = now(), stateReason = null)
    }

    /**
     * §1's exact obligation: is [claimedLockId] the connection's CURRENT lock, right now — not
     * merely a non-blank string that was once valid. A `DeviceOperation`'s
     * `authority.exclusiveLockId` MUST be checked through this, never trusted on its own, at the
     * moment authorization is granted (flash contract step 4).
     */
    fun isLockCurrent(connectionId: String, claimedLockId: String): Boolean =
        connections[connectionId]?.exclusiveLock?.lockId == claimedLockId
}
