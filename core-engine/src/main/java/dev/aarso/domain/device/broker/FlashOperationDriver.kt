package dev.aarso.domain.device.broker

import dev.aarso.contracts.devices.DeviceIdentity
import dev.aarso.contracts.devices.DeviceOperation
import dev.aarso.contracts.devices.DeviceOperationKind
import dev.aarso.contracts.devices.DeviceOperationState
import dev.aarso.contracts.devices.DeviceRollbackPlan
import dev.aarso.contracts.devices.DeviceSideEffect
import dev.aarso.contracts.devices.DeviceVerificationState
import dev.aarso.contracts.devices.FirmwareArtifact
import dev.aarso.contracts.devices.IdentityConfidenceLevel
import dev.aarso.contracts.devices.OperationAuthority
import dev.aarso.contracts.devices.OperationPreconditions
import dev.aarso.contracts.devices.OperationProtocol
import dev.aarso.contracts.devices.OperationReceipt
import dev.aarso.contracts.devices.OperationVerification
import dev.aarso.domain.contracts.IdGenerator
import java.time.Instant

/** What a real transfer provider reports back — never a bare exception, so the driver always knows whether a destructive transfer definitely began before things went wrong. */
sealed interface TransferOutcome {
    object Completed : TransferOutcome
    /** [sideEffectsConfirmed] mirrors FB-RAT-DEV-006's own two-way split: true if the provider can confirm the device was left partially written, false if even that much is genuinely unknown. */
    data class Disconnected(val sideEffectsConfirmed: Boolean) : TransferOutcome
}

/**
 * The flash contract's seven steps (`DEVICE_STATE_AND_SAFETY_SPEC.md` §4), driven for real
 * around [DeviceOperationMachine] + [DeviceBroker] + [WrongBoardPreflight] — the same "adapter
 * around already-real pieces, not a rewrite" posture [dev.aarso.domain.loop.LoopInstallationDriver]
 * (WP-8a) established for the import/activation flow. Every transition this driver performs is
 * checked against [DeviceOperationMachine] internally (`advance()`'s own throw-on-illegal check).
 *
 * Lock discipline: the connection's exclusive lock is acquired **before** precondition
 * evaluation (an operation attempt needs exclusive device access to safely even inspect the
 * connected board, not just to flash it) and released in a `finally` block covering every exit
 * path, including the two disconnect-shaped terminals — this driver's own view is "my attempt is
 * over, release my hold on the broker's arbitration record"; correcting the connection's *state*
 * after a real physical disconnect (`DISCONNECTED`/`STATE_UNKNOWN`) is a separate connection-
 * watcher's job, not this driver's, per [DeviceConnectionMachine]'s own cyclic, long-lived shape.
 */
class FlashOperationDriver(
    private val broker: DeviceBroker,
    private val idGenerator: () -> String = { "devop_" + IdGenerator.generate() },
    private val now: () -> Instant = Instant::now,
) {
    class IllegalDriverTransitionException(message: String) : Exception(message)

    suspend fun flash(
        deviceIdentity: DeviceIdentity,
        connectionId: String,
        firmwareArtifact: FirmwareArtifact,
        principal: String,
        requiredConfidence: IdentityConfidenceLevel,
        powerWiringAssumptionsAcknowledged: Boolean,
        destructiveActionExplicitlyAuthorized: Boolean,
        performTransfer: suspend () -> TransferOutcome,
        performVerification: suspend () -> OperationVerification,
    ): DeviceOperation {
        val operationId = idGenerator()
        var wireState = DeviceOperationMachine.start()

        fun advance(event: DeviceOperationMachine.Event) {
            val result = DeviceOperationMachine.apply(wireState, event)
            if (result !is DeviceOperationMachine.Result.Advanced) {
                throw IllegalDriverTransitionException("FlashOperationDriver: internal -- ${(result as DeviceOperationMachine.Result.Rejected).reason}")
            }
            wireState = result.state
        }

        val lock = broker.acquireLock(connectionId, principal, operationId)
        val authority = OperationAuthority(
            exclusiveLockId = lock.lockId, destructiveActionExplicitlyAuthorized = destructiveActionExplicitlyAuthorized,
            authorizedPrincipal = principal, authorizedAtUtc = if (destructiveActionExplicitlyAuthorized) now() else null,
        )

        // Steps 1-3, evaluated once, before we know whether we'll even reach AUTHORIZED --
        // preconditions is shared across every terminal branch below.
        val confidenceOk = deviceIdentity.confidence.level.strength() >= requiredConfidence.strength()
        val permissionOk = broker.connection(connectionId)?.usbPermissionGranted == true
        val boardOk = WrongBoardPreflight.isCompatible(deviceIdentity.board, firmwareArtifact.boardCompatibility)
        val lockCurrent = broker.isLockCurrent(connectionId, lock.lockId) // FB-RAT-DEV-001 §1's own re-validation, at the authorization moment
        val preconditions = OperationPreconditions(
            identityConfidenceAtLeast = requiredConfidence, usbPermissionGranted = permissionOk,
            boardFirmwareCompatibilityChecked = boardOk, powerWiringAssumptionsAcknowledged = powerWiringAssumptionsAcknowledged,
        )

        // "sideEffectsPossible flips true the instant the destructive transfer actually begins"
        // (step 5) -- tracked independently of any given branch's own receipt.sideEffects list,
        // since a TARGET_STATE_UNKNOWN outcome can legitimately carry zero *confirmed* side
        // effects while a transfer had still genuinely begun.
        var transferBegan = false

        fun terminalOperation(sideEffects: List<DeviceSideEffect>, verification: OperationVerification): DeviceOperation {
            val receipt = OperationReceipt(finishedAtUtc = now(), exitState = wireState, sideEffects = sideEffects)
            return DeviceOperation(
                operationId = operationId, deviceIdentityId = deviceIdentity.deviceIdentityId, connectionId = connectionId,
                operationKind = DeviceOperationKind.FLASH, preconditions = preconditions, authority = authority,
                protocol = OperationProtocol(family = deviceIdentity.family, sideEffectsPossible = transferBegan),
                state = wireState, verification = verification, rollback = DeviceRollbackPlan(possible = false, steps = emptyList()),
                artifactId = firmwareArtifact.firmwareArtifactId, receipt = receipt,
            )
        }

        try {
            if (!confidenceOk || !permissionOk || !boardOk || !powerWiringAssumptionsAcknowledged || !lockCurrent) {
                advance(DeviceOperationMachine.Event.PreconditionFailed)
                return terminalOperation(sideEffects = emptyList(), verification = OperationVerification(DeviceVerificationState.NOT_PERFORMED))
            }

            if (!destructiveActionExplicitlyAuthorized) {
                advance(DeviceOperationMachine.Event.Cancel)
                return terminalOperation(sideEffects = emptyList(), verification = OperationVerification(DeviceVerificationState.NOT_PERFORMED))
            }

            advance(DeviceOperationMachine.Event.PreconditionsPassedAuthorized)

            // Step 5: transfer.
            advance(DeviceOperationMachine.Event.TransferBegins)
            transferBegan = true
            val transferOutcome = performTransfer()
            if (transferOutcome is TransferOutcome.Disconnected) {
                advance(DeviceOperationMachine.Event.DisconnectOrFailureAfterTransferBegan(transferOutcome.sideEffectsConfirmed))
                val sideEffects = if (wireState == DeviceOperationState.FAILED_SIDE_EFFECTS_POSSIBLE) {
                    listOf(DeviceSideEffect("firmware transfer was interrupted after it began", reversible = null, occurredAtUtc = now()))
                } else emptyList()
                return terminalOperation(sideEffects, verification = OperationVerification(DeviceVerificationState.NOT_PERFORMED))
            }

            // Step 6: verify.
            advance(DeviceOperationMachine.Event.TransferCompletedVerificationBegins)
            val verification = performVerification()
            return if (verification.state == DeviceVerificationState.VERIFIED) {
                advance(DeviceOperationMachine.Event.VerificationPassed)
                terminalOperation(sideEffects = emptyList(), verification = verification)
            } else {
                // A verification that came back non-VERIFIED with the device still responsive
                // counts as side effects confirmed (a firmware transfer genuinely happened, it
                // just didn't verify) -- only a verification provider reporting an actual
                // disconnect would motivate TARGET_STATE_UNKNOWN instead; this seam's contract
                // (a returned OperationVerification, not a thrown disconnect) means
                // FAILED_SIDE_EFFECTS_POSSIBLE is the correct, documented choice here, not an
                // arbitrary one (FB-RAT-DEV-006 permits either outcome; this is the driver's own
                // stated policy for this seam shape).
                advance(DeviceOperationMachine.Event.VerificationFailedOrDisconnected(sideEffectsConfirmed = true))
                terminalOperation(
                    sideEffects = listOf(DeviceSideEffect("firmware transfer completed but verification did not confirm success", reversible = null, occurredAtUtc = now())),
                    verification = verification,
                )
            }
        } finally {
            broker.releaseLock(connectionId, lock.lockId)
        }
    }
}

private fun IdentityConfidenceLevel.strength(): Int = when (this) {
    IdentityConfidenceLevel.CONFIRMED -> 3
    IdentityConfidenceLevel.PROBABLE -> 2
    IdentityConfidenceLevel.UNCERTAIN -> 1
    IdentityConfidenceLevel.UNKNOWN -> 0
}
