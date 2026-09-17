// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// DeviceContracts.kt — the "devices" domain's shared wire-shape data classes.
//
// Mirrors, field-for-field, the JSON Schema documents under schemas/devices/*.schema.json:
//   DeviceIdentity     -> device-identity.schema.json
//   DeviceConnection   -> connection.schema.json
//   FirmwareArtifact   -> firmware-artifact.schema.json
//   DeviceSnapshot     -> snapshot.schema.json
//   DeviceOperation    -> operation.schema.json
// plus ArtifactRef, CapabilityManifest, ErrorEnvelope, IntegrityRef, reused as-is from
// dev.fonebrew.contracts.common (schemas/common/*.schema.json) — NOT redefined here, per the
// WP-1 task brief. If a field appears in one place, it MUST appear in the other, or the two
// have drifted and one of them is wrong. See docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md
// for the citations (FB-RAT-DEV-001..011) each field/rule operationalizes.
//
// DISTRIBUTION_CAPABILITY_SPLIT.md (FB-RAT-DIST-001/002/003) has NO schema or Kotlin of its
// own per the traceability matrix given in the WP-1 task brief — it is policy prose plus a
// reference into dev.fonebrew.contracts.common.CapabilityManifest (subjectKind EXTENSION or
// DEVICE, narrowed via targetRequirements["distributionFlavor"]), which "foundations" already
// wrote. Nothing in this file adds Distribution-specific types.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no
// Android imports, no kotlinx-coroutines (this domain's provider seam, unlike execution's or
// workspace's, has no streaming/Flow-shaped method in the WP-1 task brief's object model — a
// future DeviceBroker interface, if one is specified in a later work package, may need one;
// this file does not invent it).
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build
// environment — this file has been written carefully (balanced braces, matched types, no
// typos attempted) but has NOT been compiled. Do not report it as compiling; that is for
// the next session with Gradle available to confirm. This file also depends on
// contracts/kotlin/CommonContracts.kt (package dev.fonebrew.contracts.common) being compiled
// in the same module/source set — it is not a standalone-compilable file by itself.
//
// Why DeviceOperationState IS a sealed interface (mirroring ExecutionContracts.kt's
// ExecutionLifecycleState, unlike CommonContracts.kt's deliberate absence of one): this
// domain owns a real state machine — the 7-step flash contract's 9-state lifecycle
// (docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md §4) — and per the WP-0 survey, real state
// machines belong to the domains that own actual state. The wire-facing DeviceOperationState
// enum (mirrors operation.schema.json's $defs/DeviceOperationState 1:1) stays a plain enum
// for the same reason ExecutionState does: it is what JSON Schema's `enum` keyword maps onto
// 1:1. DeviceConnectionState is left as a plain enum only (no sealed-interface twin) because,
// unlike ExecutionState/DeviceOperationState, its lifecycle is not a one-shot
// queued->terminal run but a long-lived, cyclic connection state (READY <-> BUSY,
// DISCONNECTED -> CONNECTING on reconnect, mirroring WorkspaceContracts.kt's
// WorkspaceProviderState precedent) — the task brief's object-model list does not ask for a
// sealed-interface encoding of it, and inventing one here would not mirror any existing
// precedent in this constellation's contracts corpus.

package dev.fonebrew.contracts.devices

import dev.fonebrew.contracts.common.ArtifactRef
import dev.fonebrew.contracts.common.CapabilityManifest
import dev.fonebrew.contracts.common.ErrorEnvelope
import dev.fonebrew.contracts.common.DigestAlgorithm
import dev.fonebrew.contracts.common.IntegrityRef
import java.time.Instant

// =========================================================================================
// Shared sub-shapes (used by more than one top-level contract below)
// =========================================================================================

/**
 * FB-RAT-DEV-010 (EXPERIMENTAL — cross-reference docs/non_ratified/EXPERIMENTAL_DECISIONS.md):
 * the nine-family initial USB target matrix. CMSIS_DAP carries the FB-RAT-DEV-011 research-spike
 * caveat (docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md §7) — its presence here is a target-matrix
 * placeholder, not a claim that CMSIS-DAP/OpenOCD support exists or is precedented on Android.
 */
enum class UsbFamily { CDC, FTDI, CP210X, CH34X, STK500, UF2, DFU, ESP_BOOTLOADER, CMSIS_DAP }

/** FB-RAT-DEV-009: interoperate with Arduino CLI/PlatformIO metadata, never own a separate board/package universe. */
enum class CatalogRef { ARDUINO_CLI, PLATFORMIO, UNCATALOGED }

/**
 * A board reference resolved (or not) against an external catalog. `displayName` is
 * display-only, never identity — see [DeviceIdentity.deviceIdentityId].
 */
data class BoardRef(
    val catalogRef: CatalogRef,
    val displayName: String,
    val catalogId: String? = null
) {
    init {
        require(displayName.isNotBlank()) { "BoardRef.displayName must be non-blank." }
    }
}

// =========================================================================================
// DeviceIdentity — schemas/devices/device-identity.schema.json
// =========================================================================================

data class UsbDescriptor(
    val vendorId: String,
    val productId: String,
    val manufacturer: String? = null,
    val productName: String? = null
) {
    init {
        require(Regex("^0x[0-9A-Fa-f]{4}$").matches(vendorId)) { "UsbDescriptor.vendorId must look like '0xNNNN' (got '$vendorId')." }
        require(Regex("^0x[0-9A-Fa-f]{4}$").matches(productId)) { "UsbDescriptor.productId must look like '0xNNNN' (got '$productId')." }
    }
}

/** How a DeviceIdentity's board enters/speaks a bootloader protocol, when applicable. */
data class BootloaderInfo(
    val supported: Boolean,
    val family: UsbFamily? = null,
    val entryMethod: String? = null
)

/**
 * FB-RAT-DEV-004: flash preflight validates board identity confidence before erase. See
 * [IdentityConfidenceLevel] for the ordered scale a [dev.fonebrew.contracts.devices.DeviceOperation]
 * gates on via [OperationPreconditions.identityConfidenceAtLeast].
 */
enum class IdentityConfidenceLevel { CONFIRMED, PROBABLE, UNCERTAIN, UNKNOWN }

data class IdentityConfidence(
    val level: IdentityConfidenceLevel,
    val basis: List<String> = emptyList()
)

/**
 * What a discovered device claims to be. Typically carried as the `payload` of a
 * dev.fonebrew.contracts.common.ContractEnvelope<DeviceIdentity>. `deviceIdentityId` is this
 * object's own FB-RAT-COM-002 stable ID — a device keeps the same id across reconnects,
 * USB-port moves, and even a firmware re-flash that changes its USB descriptor strings.
 */
data class DeviceIdentity(
    val deviceIdentityId: String,
    val family: UsbFamily,
    val board: BoardRef,
    val confidence: IdentityConfidence,
    val mcu: String? = null,
    val usbDescriptor: UsbDescriptor? = null,
    val serial: String? = null,
    val bootloaderInfo: BootloaderInfo? = null,
    val capabilities: CapabilityManifest? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(deviceIdentityId.isNotBlank()) { "DeviceIdentity.deviceIdentityId must be non-blank (FB-RAT-COM-002)." }
    }
}

// =========================================================================================
// DeviceConnection — schemas/devices/connection.schema.json
// =========================================================================================

/**
 * FB-RAT-DEV-003: device uncertainty represented explicitly. DISCOVERED/PERMISSION_REQUIRED/
 * CONNECTING are the transient entry path; READY/BOOTLOADER/BUSY/DISCONNECTED/STATE_UNKNOWN/
 * UNSUPPORTED are settled states a connection can dwell in and (other than UNSUPPORTED)
 * transition back out of. See docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md §3 for the full
 * transition table. STATE_UNKNOWN is distinct from DISCONNECTED — never collapse the two.
 */
enum class DeviceConnectionState {
    DISCOVERED, PERMISSION_REQUIRED, CONNECTING,
    READY, BOOTLOADER, BUSY, DISCONNECTED, STATE_UNKNOWN, UNSUPPORTED;

    companion object {
        val STATES_REQUIRING_REASON: Set<DeviceConnectionState> = setOf(
            PERMISSION_REQUIRED, BOOTLOADER, BUSY, DISCONNECTED, STATE_UNKNOWN, UNSUPPORTED
        )
    }
}

/**
 * FB-RAT-DEV-001: one Device Broker arbitrates serial, flash, debug, USB permission, and
 * device locks — this is the wire-visible record of that arbitration. See
 * fixtures/devices/adversarial/operation-authority-without-current-lock.adversarial.json for
 * the privilege-escalation obligation this type's `lockId` alone cannot enforce (a consumer
 * MUST re-validate a claimed lockId against the connection's OWN, live lock at authorization
 * time — this data class cannot do that cross-object check itself).
 */
data class DeviceLock(
    val lockId: String,
    val holderPrincipal: String,
    val acquiredAtUtc: Instant,
    val operationId: String? = null
) {
    init {
        require(lockId.isNotBlank()) { "DeviceLock.lockId must be non-blank (FB-RAT-COM-002)." }
        require(holderPrincipal.isNotBlank()) { "DeviceLock.holderPrincipal must be non-blank (FB-RAT-COM-008)." }
    }
}

/**
 * Live connection state for a [DeviceIdentity]. Typically carried as the `payload` of a
 * dev.fonebrew.contracts.common.ContractEnvelope<DeviceConnection>. `connectionId` is this
 * object's own FB-RAT-COM-002 stable ID — a single physical device unplugged and replugged
 * produces a NEW connectionId each time; `deviceIdentityId` is what stays constant.
 *
 * Structurally operationalizes (via the init{} checks below, mirroring
 * schemas/devices/connection.schema.json's allOf/if/then blocks):
 *  - FB-RAT-COM-009: a non-plain-connected state MUST carry a non-blank stateReason.
 *  - FB-RAT-DEV-001: state=BUSY MUST carry a non-null exclusiveLock, and a non-null
 *    exclusiveLock MUST NOT appear on a connection reporting any state other than BUSY.
 */
data class DeviceConnection(
    val connectionId: String,
    val deviceIdentityId: String,
    val state: DeviceConnectionState,
    val enteredStateAtUtc: Instant,
    val usbPermissionGranted: Boolean,
    val stateReason: String? = null,
    val transportPath: String? = null,
    val exclusiveLock: DeviceLock? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(connectionId.isNotBlank()) { "DeviceConnection.connectionId must be non-blank (FB-RAT-COM-002)." }
        require(deviceIdentityId.isNotBlank()) { "DeviceConnection.deviceIdentityId must be non-blank." }
        if (state in DeviceConnectionState.STATES_REQUIRING_REASON) {
            require(!stateReason.isNullOrBlank()) {
                "DeviceConnection.stateReason must be non-blank when state=$state (FB-RAT-COM-009 accessibility)."
            }
        }
        if (state == DeviceConnectionState.BUSY) {
            requireNotNull(exclusiveLock) { "DeviceConnection: state=BUSY requires a non-null exclusiveLock (FB-RAT-DEV-001)." }
        } else {
            require(exclusiveLock == null) {
                "DeviceConnection: exclusiveLock MUST be null when state is not BUSY (got state=$state) — " +
                    "a stale lock left behind after a state transition is a bypass of the single-arbiter rule (FB-RAT-DEV-001)."
            }
        }
    }
}

// =========================================================================================
// FirmwareArtifact — schemas/devices/firmware-artifact.schema.json
// =========================================================================================

data class MemoryLayout(
    val flashBytes: Long,
    val ramBytes: Long? = null,
    val eepromBytes: Long? = null,
    val flashStartAddress: String? = null
) {
    init {
        require(flashBytes >= 1) { "MemoryLayout.flashBytes must be >= 1." }
        flashStartAddress?.let {
            require(Regex("^0x[0-9A-Fa-f]+$").matches(it)) { "MemoryLayout.flashStartAddress must look like '0x...' (got '$it')." }
        }
    }
}

data class SigningInfo(
    val signed: Boolean,
    val signatureScheme: String? = null,
    val verified: Boolean? = null
)

/**
 * A firmware image that can be flashed to a device. Typically carried as the `payload` of a
 * dev.fonebrew.contracts.common.ContractEnvelope<FirmwareArtifact>. `firmwareArtifactId` is this
 * object's own FB-RAT-COM-002 stable ID.
 *
 * @param artifact The underlying byte artifact (digest, storage, verification, build
 *   provenance), reused as-is from dev.fonebrew.contracts.common.ArtifactRef — NOT redefined here.
 * @param boardCompatibility FB-RAT-DEV-007 (wrong-board block): non-empty — see the init{}
 *   check below and fixtures/devices/adversarial/wrong-board-attempt.adversarial.json for why
 *   this field alone cannot itself enforce a cross-object match; that is a DeviceOperation
 *   provider-gate obligation (docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md §4).
 */
data class FirmwareArtifact(
    val firmwareArtifactId: String,
    val artifact: ArtifactRef,
    val boardCompatibility: List<BoardRef>,
    val memoryLayout: MemoryLayout,
    val signing: SigningInfo,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(firmwareArtifactId.isNotBlank()) { "FirmwareArtifact.firmwareArtifactId must be non-blank (FB-RAT-COM-002)." }
        require(boardCompatibility.isNotEmpty()) {
            "FirmwareArtifact.boardCompatibility must be non-empty (FB-RAT-DEV-007 needs a declared compatibility " +
                "target to check DeviceIdentity.board against before erase)."
        }
    }
}

// =========================================================================================
// DeviceSnapshot — schemas/devices/snapshot.schema.json
// =========================================================================================

/** Flash contract step 6's four verification methods (docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md §4). */
enum class DeviceVerificationMethod { PROTOCOL_RESPONSE, READ_BACK_HASH, VERSION_HANDSHAKE, SELF_TEST }

/** Mirrors schemas/execution/receipt.schema.json's verification.state vocabulary — success and verification are kept structurally separate. */
enum class DeviceVerificationState { NOT_PERFORMED, VERIFIED, FAILED, SKIPPED }

data class SnapshotVerification(
    val state: DeviceVerificationState,
    val method: DeviceVerificationMethod? = null,
    val verifiedAtUtc: Instant? = null
)

/**
 * A point-in-time belief about what firmware a device is running. Typically carried as the
 * `payload` of a dev.fonebrew.contracts.common.ContractEnvelope<DeviceSnapshot>. `snapshotId` is
 * this object's own FB-RAT-COM-002 stable ID. Snapshots are append-only — a corrected belief
 * is a NEW snapshot, never an in-place edit.
 *
 * FB-RAT-DEV-005 (verification before good state): `lastKnownGood` requires
 * `verification.state == VERIFIED` — see the init{} check below.
 */
data class DeviceSnapshot(
    val snapshotId: String,
    val deviceIdentityId: String,
    val connectionStateAtCapture: DeviceConnectionState,
    val verification: SnapshotVerification,
    val lastKnownGood: Boolean,
    val capturedAtUtc: Instant,
    val firmwareArtifactId: String? = null,
    val firmwareDigest: IntegrityRef? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(snapshotId.isNotBlank()) { "DeviceSnapshot.snapshotId must be non-blank (FB-RAT-COM-002)." }
        require(deviceIdentityId.isNotBlank()) { "DeviceSnapshot.deviceIdentityId must be non-blank." }
        if (lastKnownGood) {
            require(verification.state == DeviceVerificationState.VERIFIED) {
                "DeviceSnapshot: lastKnownGood=true requires verification.state=VERIFIED (FB-RAT-DEV-005), " +
                    "got ${verification.state}."
            }
        }
    }
}

// =========================================================================================
// DeviceOperation — schemas/devices/operation.schema.json
// =========================================================================================

/**
 * FB-RAT-DEV-001: the operation classes the Device Broker arbitrates (serial, flash, debug),
 * plus the read-back-verify/self-test steps the flash contract requires, plus a standalone erase.
 */
enum class DeviceOperationKind { FLASH, ERASE, READ_BACK_VERIFY, SELF_TEST, SERIAL_SESSION, DEBUG_SESSION }

data class OperationPreconditions(
    val identityConfidenceAtLeast: IdentityConfidenceLevel,
    val usbPermissionGranted: Boolean,
    val boardFirmwareCompatibilityChecked: Boolean,
    val powerWiringAssumptionsAcknowledged: Boolean
)

/**
 * @param exclusiveLockId FB-RAT-DEV-001: references a [DeviceLock.lockId]. Non-blank — a
 *   DeviceOperation cannot be modeled without holding the broker's exclusive lock (mirrors
 *   FB-RAT-COM-012 'no contract bypass' the same way ExecutionRequest.authorityGrant does in
 *   the execution domain). This field alone does NOT prove the lock is still current — see
 *   fixtures/devices/adversarial/operation-authority-without-current-lock.adversarial.json.
 * @param destructiveActionExplicitlyAuthorized Flash contract step 4: explicit, non-implicit
 *   authorization for a destructive action, separate from merely holding the lock.
 */
data class OperationAuthority(
    val exclusiveLockId: String,
    val destructiveActionExplicitlyAuthorized: Boolean,
    val authorizedPrincipal: String,
    val authorizedAtUtc: Instant? = null
) {
    init {
        require(exclusiveLockId.isNotBlank()) { "OperationAuthority.exclusiveLockId must be non-blank (FB-RAT-DEV-001)." }
        require(authorizedPrincipal.isNotBlank()) { "OperationAuthority.authorizedPrincipal must be non-blank (FB-RAT-COM-008)." }
    }
}

data class OperationProtocol(
    val family: UsbFamily,
    val progressPercent: Double? = null,
    /**
     * Flash contract step 5's 'recorded possible side effects': flips true the moment a
     * destructive transfer (erase/write) actually begins — the field a disconnect handler
     * reads to decide between FAILED_SAFE (still false) and FAILED_SIDE_EFFECTS_POSSIBLE/
     * TARGET_STATE_UNKNOWN (true).
     */
    val sideEffectsPossible: Boolean = false
) {
    init {
        progressPercent?.let { require(it in 0.0..100.0) { "OperationProtocol.progressPercent must be within [0, 100]." } }
    }
}

/**
 * Non-terminal: PENDING_PRECONDITIONS, AUTHORIZED, IN_PROGRESS, VERIFYING. Terminal:
 * SUCCEEDED, FAILED_SAFE, FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN, CANCELLED. See
 * docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md §4 for the full transition table mapping this
 * directly onto the 7-step flash contract. FAILED_SAFE is reached only from
 * PENDING_PRECONDITIONS (FB-RAT-DEV-007) — never from IN_PROGRESS or VERIFYING.
 */
enum class DeviceOperationState {
    PENDING_PRECONDITIONS, AUTHORIZED, IN_PROGRESS, VERIFYING,
    SUCCEEDED, FAILED_SAFE, FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN, CANCELLED;

    val isTerminal: Boolean get() = this in TERMINAL

    companion object {
        val TERMINAL: Set<DeviceOperationState> = setOf(
            SUCCEEDED, FAILED_SAFE, FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN, CANCELLED
        )
        val DESTRUCTIVE_KINDS: Set<DeviceOperationKind> = setOf(DeviceOperationKind.FLASH, DeviceOperationKind.ERASE)
        val REQUIRES_AUTHORIZATION_FROM: Set<DeviceOperationState> = setOf(
            IN_PROGRESS, VERIFYING, SUCCEEDED, FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN
        )
    }
}

/**
 * Sealed-interface runtime encoding of [DeviceOperationState] — the in-memory state machine a
 * Device Broker implementation programs against, so an exhaustive `when` over this type is a
 * compile-time-checked guarantee every lifecycle state is handled. Mirrors
 * ExecutionContracts.kt's ExecutionLifecycleState pattern for the same reason: this domain
 * owns real state transitions (the 7-step flash contract).
 */
sealed interface DeviceOperationLifecycleState {
    val wireState: DeviceOperationState

    data object PendingPreconditions : DeviceOperationLifecycleState { override val wireState = DeviceOperationState.PENDING_PRECONDITIONS }
    data object Authorized : DeviceOperationLifecycleState { override val wireState = DeviceOperationState.AUTHORIZED }
    data object InProgress : DeviceOperationLifecycleState { override val wireState = DeviceOperationState.IN_PROGRESS }
    data object Verifying : DeviceOperationLifecycleState { override val wireState = DeviceOperationState.VERIFYING }

    sealed interface Terminal : DeviceOperationLifecycleState

    data object Succeeded : Terminal { override val wireState = DeviceOperationState.SUCCEEDED }
    /** FB-RAT-DEV-007: reached only from PendingPreconditions — a precondition/wrong-board failure BEFORE any destructive action. */
    data object FailedSafe : Terminal { override val wireState = DeviceOperationState.FAILED_SAFE }
    /** FB-RAT-DEV-006: disconnect (or other failure) after a destructive transfer began. */
    data object FailedSideEffectsPossible : Terminal { override val wireState = DeviceOperationState.FAILED_SIDE_EFFECTS_POSSIBLE }
    /** FB-RAT-DEV-006's other permitted disconnect outcome, when even that much cannot be established. */
    data object TargetStateUnknown : Terminal { override val wireState = DeviceOperationState.TARGET_STATE_UNKNOWN }
    data object Cancelled : Terminal { override val wireState = DeviceOperationState.CANCELLED }
}

data class OperationVerification(
    val state: DeviceVerificationState,
    val method: DeviceVerificationMethod? = null,
    val verifiedAtUtc: Instant? = null
)

/**
 * Mirrors dev.fonebrew.contracts.common.RollbackPlan's pattern: `steps` MUST be non-empty when
 * `possible` is true, and MUST be empty when `possible` is false.
 */
data class DeviceRollbackPlan(
    val possible: Boolean,
    val steps: List<String>
) {
    init {
        if (possible) {
            require(steps.isNotEmpty()) { "DeviceRollbackPlan.steps must be non-empty when possible=true." }
        } else {
            require(steps.isEmpty()) { "DeviceRollbackPlan.steps must be empty when possible=false." }
        }
    }
}

data class DeviceSideEffect(
    val description: String,
    val reversible: Boolean?,
    val occurredAtUtc: Instant? = null
) {
    init {
        require(description.isNotBlank()) { "DeviceSideEffect.description must be non-blank." }
    }
}

/** Flash contract step 7. Non-null only once a DeviceOperation reaches a terminal state. */
data class OperationReceipt(
    val finishedAtUtc: Instant,
    val exitState: DeviceOperationState,
    val sideEffects: List<DeviceSideEffect>,
    val errorEnvelope: ErrorEnvelope? = null,
    val snapshotAfterId: String? = null
) {
    init {
        require(exitState.isTerminal) { "OperationReceipt.exitState must be one of DeviceOperationState.TERMINAL, got $exitState." }
    }
}

/**
 * One Device-Broker-arbitrated operation. Typically carried as the `payload` of a
 * dev.fonebrew.contracts.common.ContractEnvelope<DeviceOperation>. `operationId` is this
 * object's own FB-RAT-COM-002 stable ID.
 *
 * Structurally operationalizes (via the init{} checks below, mirroring
 * schemas/devices/operation.schema.json's allOf/if/then blocks):
 *  - A FLASH operation MUST reference the firmware image it is writing (`artifactId` non-null).
 *  - FB-RAT-DEV-005: `state=SUCCEEDED` requires `verification.state=VERIFIED`. There is no
 *    'succeeded unverified' terminal state for DeviceOperation, unlike ExecutionReceipt — an
 *    unverified completion is FAILED_SIDE_EFFECTS_POSSIBLE or TARGET_STATE_UNKNOWN, never a
 *    silent SUCCEEDED.
 *  - Flash contract step 4: a destructive (FLASH/ERASE) operation that has progressed past
 *    preconditions into IN_PROGRESS or beyond MUST carry explicit destructive authorization.
 *  - FB-RAT-DEV-007: `state=FAILED_SAFE` implies zero side effects were recorded.
 *  - FB-RAT-DEV-006: `state=FAILED_SIDE_EFFECTS_POSSIBLE` requires a non-null receipt with a
 *    non-empty `sideEffects` list — never a generic failure. `state=TARGET_STATE_UNKNOWN`
 *    requires a non-null receipt.
 */
data class DeviceOperation(
    val operationId: String,
    val deviceIdentityId: String,
    val connectionId: String,
    val operationKind: DeviceOperationKind,
    val preconditions: OperationPreconditions,
    val authority: OperationAuthority,
    val protocol: OperationProtocol,
    val state: DeviceOperationState,
    val verification: OperationVerification,
    val rollback: DeviceRollbackPlan,
    val artifactId: String? = null,
    val receipt: OperationReceipt? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(operationId.isNotBlank()) { "DeviceOperation.operationId must be non-blank (FB-RAT-COM-002)." }
        require(deviceIdentityId.isNotBlank()) { "DeviceOperation.deviceIdentityId must be non-blank." }
        require(connectionId.isNotBlank()) { "DeviceOperation.connectionId must be non-blank." }

        if (operationKind == DeviceOperationKind.FLASH) {
            require(!artifactId.isNullOrBlank()) {
                "DeviceOperation: operationKind=FLASH requires a non-blank artifactId."
            }
        }

        if (state == DeviceOperationState.SUCCEEDED) {
            require(verification.state == DeviceVerificationState.VERIFIED) {
                "DeviceOperation: state=SUCCEEDED requires verification.state=VERIFIED (FB-RAT-DEV-005), got ${verification.state}."
            }
        }

        if (operationKind in DeviceOperationState.DESTRUCTIVE_KINDS && state in DeviceOperationState.REQUIRES_AUTHORIZATION_FROM) {
            require(authority.destructiveActionExplicitlyAuthorized) {
                "DeviceOperation: a destructive operationKind=$operationKind that reached state=$state " +
                    "MUST carry authority.destructiveActionExplicitlyAuthorized=true (flash contract step 4)."
            }
        }

        if (state == DeviceOperationState.FAILED_SAFE) {
            require(receipt?.sideEffects.isNullOrEmpty()) {
                "DeviceOperation: state=FAILED_SAFE MUST record zero side effects (FB-RAT-DEV-007 — " +
                    "blocked before any destructive action)."
            }
        }

        if (state == DeviceOperationState.FAILED_SIDE_EFFECTS_POSSIBLE) {
            requireNotNull(receipt) { "DeviceOperation: state=FAILED_SIDE_EFFECTS_POSSIBLE requires a non-null receipt (FB-RAT-DEV-006)." }
            require(receipt.sideEffects.isNotEmpty()) {
                "DeviceOperation: state=FAILED_SIDE_EFFECTS_POSSIBLE requires a non-empty receipt.sideEffects list (FB-RAT-DEV-006) — never a generic failure."
            }
        }

        if (state == DeviceOperationState.TARGET_STATE_UNKNOWN) {
            requireNotNull(receipt) { "DeviceOperation: state=TARGET_STATE_UNKNOWN requires a non-null receipt (FB-RAT-DEV-006)." }
        }
    }
}
