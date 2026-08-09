package dev.aarso.domain.device.broker

import dev.aarso.contracts.common.ArtifactRef
import dev.aarso.contracts.common.StorageKind
import dev.aarso.contracts.common.StorageLocation
import dev.aarso.contracts.common.VerificationState
import dev.aarso.contracts.devices.BoardRef
import dev.aarso.contracts.devices.CatalogRef
import dev.aarso.contracts.devices.DeviceConnection
import dev.aarso.contracts.devices.DeviceConnectionState
import dev.aarso.contracts.devices.DeviceIdentity
import dev.aarso.contracts.devices.DeviceOperationState
import dev.aarso.contracts.devices.DeviceVerificationState
import dev.aarso.contracts.devices.FirmwareArtifact
import dev.aarso.contracts.devices.IdentityConfidence
import dev.aarso.contracts.devices.IdentityConfidenceLevel
import dev.aarso.contracts.devices.MemoryLayout
import dev.aarso.contracts.devices.OperationVerification
import dev.aarso.contracts.devices.SigningInfo
import dev.aarso.contracts.devices.UsbFamily
import dev.aarso.domain.contracts.Digest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** End-to-end coverage of DEVICE_STATE_AND_SAFETY_SPEC.md §4's seven-step flash contract, driven for real around DeviceBroker + DeviceOperationMachine + WrongBoardPreflight. */
class FlashOperationDriverTest {

    private val unoBoard = BoardRef(CatalogRef.ARDUINO_CLI, "Arduino Uno", "arduino:avr:uno")
    private val nanoBoard = BoardRef(CatalogRef.ARDUINO_CLI, "Arduino Nano", "arduino:avr:nano")

    private fun identity(confidence: IdentityConfidenceLevel = IdentityConfidenceLevel.CONFIRMED, board: BoardRef = unoBoard) = DeviceIdentity(
        deviceIdentityId = "dev-1", family = UsbFamily.STK500, board = board,
        confidence = IdentityConfidence(confidence, basis = listOf("USB VID/PID match")),
    )

    private fun artifact(compatibleWith: List<BoardRef> = listOf(unoBoard)) = FirmwareArtifact(
        firmwareArtifactId = "fw-1",
        artifact = ArtifactRef(
            id = "art-1", mediaType = "application/octet-stream", digest = Digest.ofUtf8("firmware bytes"),
            sizeBytes = Digest.ofUtf8("firmware bytes").byteLength,
            storageLocation = StorageLocation(StorageKind.LOCAL_FS, "/data/fw/blink.hex"),
            verificationState = VerificationState.VERIFIED,
        ),
        boardCompatibility = compatibleWith,
        memoryLayout = MemoryLayout(flashBytes = 32768),
        signing = SigningInfo(signed = false),
    )

    private fun connection(id: String = "conn-1", usbPermissionGranted: Boolean = true) = DeviceConnection(
        connectionId = id, deviceIdentityId = "dev-1", state = DeviceConnectionState.READY,
        enteredStateAtUtc = Instant.parse("2026-08-07T09:00:00Z"), usbPermissionGranted = usbPermissionGranted,
    )

    private fun brokerWithReadyConnection(connectionId: String = "conn-1", usbPermissionGranted: Boolean = true): DeviceBroker {
        val broker = DeviceBroker()
        broker.register(connection(connectionId, usbPermissionGranted))
        return broker
    }

    @Test
    fun `a fully successful flash reaches SUCCEEDED, verified, and releases the connection back to READY`() = runTest {
        val broker = brokerWithReadyConnection()
        val operation = FlashOperationDriver(broker).flash(
            deviceIdentity = identity(), connectionId = "conn-1", firmwareArtifact = artifact(), principal = "user-a",
            requiredConfidence = IdentityConfidenceLevel.PROBABLE, powerWiringAssumptionsAcknowledged = true,
            destructiveActionExplicitlyAuthorized = true,
            performTransfer = { TransferOutcome.Completed },
            performVerification = { OperationVerification(DeviceVerificationState.VERIFIED) },
        )
        assertEquals(DeviceOperationState.SUCCEEDED, operation.state)
        assertTrue(operation.receipt != null)
        assertEquals(DeviceConnectionState.READY, broker.connection("conn-1")?.state)
        assertEquals(null, broker.connection("conn-1")?.exclusiveLock)
    }

    @Test
    fun `a wrong-board artifact is blocked at FAILED_SAFE before any transfer, zero side effects, transfer callback never invoked`() = runTest {
        val broker = brokerWithReadyConnection()
        var transferCalled = false
        val operation = FlashOperationDriver(broker).flash(
            deviceIdentity = identity(board = unoBoard), connectionId = "conn-1",
            firmwareArtifact = artifact(compatibleWith = listOf(nanoBoard)), // wrong board on purpose
            principal = "user-a", requiredConfidence = IdentityConfidenceLevel.PROBABLE,
            powerWiringAssumptionsAcknowledged = true, destructiveActionExplicitlyAuthorized = true,
            performTransfer = { transferCalled = true; TransferOutcome.Completed },
            performVerification = { OperationVerification(DeviceVerificationState.VERIFIED) },
        )
        assertEquals(DeviceOperationState.FAILED_SAFE, operation.state)
        assertTrue(!transferCalled)
        assertTrue(operation.receipt?.sideEffects.isNullOrEmpty())
        assertEquals(DeviceConnectionState.READY, broker.connection("conn-1")?.state) // lock released even on FAILED_SAFE
    }

    @Test
    fun `insufficient identity confidence is FAILED_SAFE, same as a wrong board`() = runTest {
        val broker = brokerWithReadyConnection()
        val operation = FlashOperationDriver(broker).flash(
            deviceIdentity = identity(confidence = IdentityConfidenceLevel.UNCERTAIN), connectionId = "conn-1",
            firmwareArtifact = artifact(), principal = "user-a", requiredConfidence = IdentityConfidenceLevel.PROBABLE,
            powerWiringAssumptionsAcknowledged = true, destructiveActionExplicitlyAuthorized = true,
            performTransfer = { TransferOutcome.Completed }, performVerification = { OperationVerification(DeviceVerificationState.VERIFIED) },
        )
        assertEquals(DeviceOperationState.FAILED_SAFE, operation.state)
    }

    @Test
    fun `no USB permission is FAILED_SAFE`() = runTest {
        val broker = brokerWithReadyConnection(usbPermissionGranted = false)
        val operation = FlashOperationDriver(broker).flash(
            deviceIdentity = identity(), connectionId = "conn-1", firmwareArtifact = artifact(), principal = "user-a",
            requiredConfidence = IdentityConfidenceLevel.PROBABLE, powerWiringAssumptionsAcknowledged = true,
            destructiveActionExplicitlyAuthorized = true,
            performTransfer = { TransferOutcome.Completed }, performVerification = { OperationVerification(DeviceVerificationState.VERIFIED) },
        )
        assertEquals(DeviceOperationState.FAILED_SAFE, operation.state)
    }

    @Test
    fun `declining explicit destructive authorization cancels the operation, never reaching IN_PROGRESS`() = runTest {
        val broker = brokerWithReadyConnection()
        var transferCalled = false
        val operation = FlashOperationDriver(broker).flash(
            deviceIdentity = identity(), connectionId = "conn-1", firmwareArtifact = artifact(), principal = "user-a",
            requiredConfidence = IdentityConfidenceLevel.PROBABLE, powerWiringAssumptionsAcknowledged = true,
            destructiveActionExplicitlyAuthorized = false,
            performTransfer = { transferCalled = true; TransferOutcome.Completed },
            performVerification = { OperationVerification(DeviceVerificationState.VERIFIED) },
        )
        assertEquals(DeviceOperationState.CANCELLED, operation.state)
        assertTrue(!transferCalled)
    }

    @Test
    fun `a disconnect after transfer began with confirmed side effects reaches FAILED_SIDE_EFFECTS_POSSIBLE, non-empty side effects`() = runTest {
        val broker = brokerWithReadyConnection()
        val operation = FlashOperationDriver(broker).flash(
            deviceIdentity = identity(), connectionId = "conn-1", firmwareArtifact = artifact(), principal = "user-a",
            requiredConfidence = IdentityConfidenceLevel.PROBABLE, powerWiringAssumptionsAcknowledged = true,
            destructiveActionExplicitlyAuthorized = true,
            performTransfer = { TransferOutcome.Disconnected(sideEffectsConfirmed = true) },
            performVerification = { OperationVerification(DeviceVerificationState.VERIFIED) },
        )
        assertEquals(DeviceOperationState.FAILED_SIDE_EFFECTS_POSSIBLE, operation.state)
        assertTrue(operation.receipt!!.sideEffects.isNotEmpty())
    }

    @Test
    fun `a disconnect after transfer began with unconfirmed side effects reaches TARGET_STATE_UNKNOWN, never a generic failure`() = runTest {
        val broker = brokerWithReadyConnection()
        val operation = FlashOperationDriver(broker).flash(
            deviceIdentity = identity(), connectionId = "conn-1", firmwareArtifact = artifact(), principal = "user-a",
            requiredConfidence = IdentityConfidenceLevel.PROBABLE, powerWiringAssumptionsAcknowledged = true,
            destructiveActionExplicitlyAuthorized = true,
            performTransfer = { TransferOutcome.Disconnected(sideEffectsConfirmed = false) },
            performVerification = { OperationVerification(DeviceVerificationState.VERIFIED) },
        )
        assertEquals(DeviceOperationState.TARGET_STATE_UNKNOWN, operation.state)
        assertTrue(operation.receipt != null)
    }

    @Test
    fun `a failed verification -- transfer completed but not confirmed -- reaches FAILED_SIDE_EFFECTS_POSSIBLE, never SUCCEEDED`() = runTest {
        val broker = brokerWithReadyConnection()
        val operation = FlashOperationDriver(broker).flash(
            deviceIdentity = identity(), connectionId = "conn-1", firmwareArtifact = artifact(), principal = "user-a",
            requiredConfidence = IdentityConfidenceLevel.PROBABLE, powerWiringAssumptionsAcknowledged = true,
            destructiveActionExplicitlyAuthorized = true,
            performTransfer = { TransferOutcome.Completed },
            performVerification = { OperationVerification(DeviceVerificationState.FAILED) },
        )
        assertEquals(DeviceOperationState.FAILED_SIDE_EFFECTS_POSSIBLE, operation.state)
        assertTrue(operation.receipt!!.sideEffects.isNotEmpty())
    }

    @Test
    fun `the connection is always released back to READY, in every terminal outcome including both disconnect shapes`() = runTest {
        val outcomes = listOf(
            TransferOutcome.Completed to OperationVerification(DeviceVerificationState.VERIFIED),
            TransferOutcome.Disconnected(sideEffectsConfirmed = true) to OperationVerification(DeviceVerificationState.NOT_PERFORMED),
            TransferOutcome.Disconnected(sideEffectsConfirmed = false) to OperationVerification(DeviceVerificationState.NOT_PERFORMED),
        )
        for ((transferOutcome, verification) in outcomes) {
            val broker = brokerWithReadyConnection()
            FlashOperationDriver(broker).flash(
                deviceIdentity = identity(), connectionId = "conn-1", firmwareArtifact = artifact(), principal = "user-a",
                requiredConfidence = IdentityConfidenceLevel.PROBABLE, powerWiringAssumptionsAcknowledged = true,
                destructiveActionExplicitlyAuthorized = true,
                performTransfer = { transferOutcome }, performVerification = { verification },
            )
            assertEquals(DeviceConnectionState.READY, broker.connection("conn-1")?.state)
        }
    }
}
