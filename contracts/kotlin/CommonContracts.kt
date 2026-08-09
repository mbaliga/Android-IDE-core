// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// CommonContracts.kt — the "foundations" domain's shared wire-shape data classes.
//
// Every other contract domain in the Fonebrew constellation (workspace kernel, execution
// contract/authority, loop engineering, device broker, integration lanes, search, ...)
// depends on the shapes in this file — get them right and self-consistent (see the WP-1
// task brief this file was generated from). Mirrors, field-for-field, the JSON Schema
// documents under schemas/common/*.schema.json:
//   ContractEnvelope<T>   -> envelope.schema.json
//   ErrorEnvelope         -> error.schema.json
//   CapabilityManifest    -> capability-manifest.schema.json
//   ArtifactRef           -> artifact-ref.schema.json
//   MigrationPlan         -> migration-plan.schema.json
//   ConformanceSuite      -> conformance-suite.schema.json
// If a field appears in one place, it MUST appear in the other, or the two have drifted
// and one of them is wrong. See docs/ratified/COMMON_CONVENTIONS.md for the citations
// (FB-RAT-COM-001..012, FB-RAT-DIST-004) each field operationalizes.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no
// Android imports. This means these are pure in-memory model types; JSON encode/decode is
// a data-layer concern for whichever module eventually wires this in (not yet wired into
// any Gradle module — see WP-0 survey §1(j): no contracts/kotlin/ directory existed in any
// of the four constellation repos before this file).
//
// COMPILATION STATUS: VERIFIED (WP-2, 2026-08-07). Wired into core-engine's build as a real
// compiled source directory (core-engine/build.gradle.kts) and confirmed via
// :core-engine:compileFullDebugKotlin BUILD SUCCESSFUL alongside every other contracts/kotlin/
// file. See docs/WP1_GATE_REPORT.md's addendum for the one real defect that first compile
// attempt found (in a different file, LoopPackageContracts.kt) and how it was fixed.
//
// Why no sealed-interface state machine appears in this file: the common envelope domain
// defines shared WIRE SHAPES referenced by every other domain, not a domain with its own
// state transitions. Per the WP-0 survey, real state machines belong to the domains that
// own actual state (workspace kernel, execution contract/authority, device broker,
// RemoteSessionDriver's own SessionMachine already in core-engine) — forcing an artificial
// sealed-interface state machine in here would misrepresent this domain's job. Enums are
// used below for the small closed wire vocabularies (severity, side-effect state,
// verification state, etc.) because they map 1:1 onto the JSON Schema `enum` keyword and
// keep this file's shapes exactly mirrored to schemas/common/*.schema.json.

package dev.aarso.contracts.common

import java.time.Instant

// ---------------------------------------------------------------------------------------
// Shared sub-shapes (referenced by more than one top-level contract below)
// ---------------------------------------------------------------------------------------

/**
 * Stable identity + version of whatever produced a contract instance.
 * FB-RAT-COM-003 (producerVersion), FB-RAT-COM-008 (provenance minimum — the `name`/
 * `instanceId` pair is part of "initiating principal" when the producer *is* the principal,
 * e.g. an automated CI producer).
 */
data class ProducerRef(
    val name: String,
    val version: String,
    val instanceId: String? = null
)

/** FB-RAT-COM-005: SHA-256 (or stronger) digest + exact byte length. */
enum class DigestAlgorithm(val wireValue: String) {
    SHA_256("SHA-256"),
    SHA_384("SHA-384"),
    SHA_512("SHA-512");

    companion object {
        fun fromWireValue(value: String): DigestAlgorithm =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown DigestAlgorithm wire value: $value")
    }
}

/**
 * FB-RAT-COM-005 integrity: SHA-256+ digest and byte length on artifacts/manifests/
 * receipts. `digestHex` MUST be lowercase hex of exactly the length implied by
 * `algorithm` (64/96/128 hex chars for SHA-256/384/512 respectively) — the JSON Schema
 * counterpart enforces this length structurally per algorithm; callers constructing this
 * type directly are expected to uphold the same invariant.
 */
data class IntegrityRef(
    val algorithm: DigestAlgorithm,
    val digestHex: String,
    val byteLength: Long
)

// ---------------------------------------------------------------------------------------
// ContractEnvelope<T> — schemas/common/envelope.schema.json
// ---------------------------------------------------------------------------------------

/**
 * Generic wrapper every serialized contract in the constellation carries its payload in
 * (FB-RAT-COM-011). This Kotlin type IS major version 1 of the ContractEnvelope contract,
 * matching envelope.schema.json's `schemaVersion` pattern pin to `^1\.` — a decoder that
 * meets `schemaVersion` outside the 1.x.x line MUST refuse to materialize this type at all
 * (reject before construction, per FB-RAT-COM-003 "readers reject unknown MAJOR").
 *
 * @param schemaVersion MAJOR.MINOR.PATCH of this envelope+payload contract shape (FB-RAT-COM-003).
 * @param objectId Globally unique stable ID for the durable object this envelope carries,
 *   independent of display name/path (FB-RAT-COM-002).
 * @param createdAtUtc UTC instant this envelope was produced (FB-RAT-COM-004).
 * @param sourceTimezone Optional IANA timezone of the originating principal's local clock,
 *   display-only — ordering MUST NOT depend on this (FB-RAT-COM-004).
 * @param sequence Optional monotonic sequence number, preferred over createdAtUtc for
 *   ordering when present (FB-RAT-COM-004).
 * @param producer Who/what produced this envelope.
 * @param idempotencyKey Caller-supplied de-duplication key for retried writes
 *   (FB-RAT-COM-006). Null only for envelopes with no retryable side effect.
 * @param integrity Digest+length over the serialized `payload`, or null when payload has
 *   no independently integrity-checked canonical byte form (FB-RAT-COM-005).
 * @param payload The contract-specific body.
 * @param unknownFields Fields a decoder received but did not recognize (e.g. from a newer
 *   MINOR of this schema), preserved so re-encoding does not silently drop data
 *   (FB-RAT-COM-003 "preserve unknown fields on round-trip"). Encoders SHOULD merge this
 *   back into the wire-level object rather than nesting it under a literal key.
 */
data class ContractEnvelope<T>(
    val schemaVersion: String,
    val objectId: String,
    val createdAtUtc: Instant,
    val producer: ProducerRef,
    val payload: T,
    val sourceTimezone: String? = null,
    val sequence: Long? = null,
    val idempotencyKey: String? = null,
    val integrity: IntegrityRef? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "ContractEnvelope.schemaVersion must be major version 1 (got '$schemaVersion') — " +
                "an envelope declaring an unsupported major MUST be rejected before " +
                "construction, per FB-RAT-COM-003."
        }
        require(objectId.isNotBlank()) { "ContractEnvelope.objectId must be non-blank (FB-RAT-COM-002)." }
    }
}

// ---------------------------------------------------------------------------------------
// ErrorEnvelope — schemas/common/error.schema.json
// ---------------------------------------------------------------------------------------

/** FB-RAT-COM-007: coarse severity band for an ErrorEnvelope. */
enum class ErrorSeverity { INFO, WARNING, ERROR, CRITICAL }

/**
 * FB-RAT-COM-007: what happened to any side effect the failed operation attempted.
 * UNKNOWN MUST be used rather than guessing NONE when the producer cannot determine the
 * outcome (e.g. a network write that timed out after the request left the device).
 */
enum class SideEffectState { NONE, PARTIAL, COMPLETED, UNKNOWN }

/**
 * Stable code + severity + retryability + side-effect state + technical detail + recovery
 * action (FB-RAT-COM-007), plus `userMessage` carrying the textual semantics accessibility
 * requires (FB-RAT-COM-009 — color/gesture/haptic/position is never the sole carrier).
 *
 * Typically carried as the `payload` of a `ContractEnvelope<ErrorEnvelope>`, inheriting
 * schemaVersion/objectId/createdAtUtc/producer from that outer envelope — this type does
 * not repeat them.
 *
 * @param code Stable machine-readable error code, e.g. "GIT_AUTH_EXPIRED". Never
 *   localized, never reused for a different meaning across releases.
 * @param detail Technical detail for logs/diagnostics. MUST NOT contain secrets or API
 *   keys — binding rule: keys are never logged. A producer that cannot strip a secret from
 *   the underlying error MUST redact it before populating this field (see
 *   fixtures/common/adversarial/error-secret-leak-in-detail.adversarial.json for the
 *   fixture this guards against).
 * @param recoveryAction A concrete next step, machine- or human-actionable. Never just
 *   repeats `detail`.
 * @param userMessage Plain-language textual semantics of this error state, independent of
 *   color/icon/haptic/position (FB-RAT-COM-009) — MUST stand on its own.
 * @param objectId Optional back-reference to the ContractEnvelope.objectId of the
 *   operation/artifact this error concerns (FB-RAT-COM-002).
 */
data class ErrorEnvelope(
    val code: String,
    val severity: ErrorSeverity,
    val retryable: Boolean,
    val sideEffectState: SideEffectState,
    val detail: String,
    val recoveryAction: String,
    val userMessage: String,
    val objectId: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(code.matches(Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$"))) {
            "ErrorEnvelope.code must be SCREAMING_SNAKE_CASE (got '$code')."
        }
        require(detail.isNotBlank()) { "ErrorEnvelope.detail must be non-blank (FB-RAT-COM-007)." }
        require(recoveryAction.isNotBlank()) { "ErrorEnvelope.recoveryAction must be non-blank (FB-RAT-COM-007)." }
        require(userMessage.isNotBlank()) { "ErrorEnvelope.userMessage must be non-blank (FB-RAT-COM-009)." }
    }
}

// ---------------------------------------------------------------------------------------
// CapabilityManifest — schemas/common/capability-manifest.schema.json
// ---------------------------------------------------------------------------------------

/** Which capability-ladder family a CapabilityManifest describes. */
enum class CapabilitySubjectKind { LSP, DAP, EXECUTION, DEVICE, MODEL_PROVIDER, EXTENSION }

/** Subject + protocol version pair for a CapabilityManifest. */
data class CapabilityVersions(
    val subjectVersion: String,
    val protocolVersion: String? = null
)

/**
 * Supported operations, limits, versions, and target requirements for a subject — used by
 * LSP/DAP/execution/devices/model providers/extensions (FB-RAT-COM-011). `limits` and
 * `targetRequirements` are open maps because that vocabulary is subject-kind-specific; a
 * capability-authority engine (WP-0 survey (c), not built by this file) is what validates
 * `supportedOperations` against a fixed per-subjectKind vocabulary at runtime — this data
 * class only carries the claimed shape, it does not itself enforce the vocabulary (see
 * fixtures/common/adversarial/capability-manifest-privilege-escalation.adversarial.json).
 *
 * Typically carried as the `payload` of a `ContractEnvelope<CapabilityManifest>`.
 */
data class CapabilityManifest(
    val manifestId: String,
    val subjectKind: CapabilitySubjectKind,
    val supportedOperations: List<String>,
    val limits: Map<String, Any?>,
    val versions: CapabilityVersions,
    val subjectId: String? = null,
    val targetRequirements: Map<String, Any?> = emptyMap(),
    val producer: ProducerRef? = null,
    val issuedAtUtc: Instant? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(manifestId.isNotBlank()) { "CapabilityManifest.manifestId must be non-blank (FB-RAT-COM-002)." }
    }
}

// ---------------------------------------------------------------------------------------
// ArtifactRef — schemas/common/artifact-ref.schema.json
// ---------------------------------------------------------------------------------------

/** Where an ArtifactRef's bytes actually live. */
enum class StorageKind { LOCAL_FS, CONTENT_ADDRESSED_STORE, GIT_HOST, REMOTE_URL }

/**
 * Kind-specific locator for an artifact's bytes. Readers resolving a LOCAL_FS locator
 * MUST reject any '..' path segment and MUST verify the normalized path stays within the
 * artifact store's configured root (see
 * fixtures/common/adversarial/artifact-ref-path-traversal.adversarial.json) — this data
 * class does not perform that check itself; it is a resolver-side obligation.
 */
data class StorageLocation(
    val kind: StorageKind,
    val locator: String
) {
    init {
        require(locator.isNotBlank()) { "StorageLocation.locator must be non-blank." }
    }
}

/** Whether an ArtifactRef's digest+size have been independently re-checked since issue. */
enum class VerificationState { UNVERIFIED, VERIFIED, FAILED, EXPIRED }

/**
 * FB-RAT-COM-008 provenance minimum: source location, project revision, initiating
 * principal, evidence links, for the execution/import result that produced an artifact.
 */
data class ProducerReceiptRef(
    val receiptObjectId: String,
    val producer: ProducerRef,
    val sourceLocation: String,
    val projectRevision: String,
    val initiatingPrincipal: String,
    val evidenceLinks: List<String> = emptyList()
) {
    init {
        require(receiptObjectId.isNotBlank()) { "ProducerReceiptRef.receiptObjectId must be non-blank (FB-RAT-COM-002)." }
        require(sourceLocation.isNotBlank()) { "ProducerReceiptRef.sourceLocation must be non-blank (FB-RAT-COM-008)." }
        require(projectRevision.isNotBlank()) { "ProducerReceiptRef.projectRevision must be non-blank (FB-RAT-COM-008)." }
        require(initiatingPrincipal.isNotBlank()) { "ProducerReceiptRef.initiatingPrincipal must be non-blank (FB-RAT-COM-008)." }
    }
}

/**
 * A digest-addressed reference to a byte artifact: id, mediaType, digest, storage
 * location, verification state, and (when available) the provenance receipt that
 * produced it (FB-RAT-COM-011). Typically carried as the `payload` of a
 * `ContractEnvelope<ArtifactRef>`.
 *
 * @param sizeBytes MUST equal `digest.byteLength` — kept as a separate field because
 *   ArtifactRef is frequently filtered/sorted by size without needing to reach into
 *   `digest`.
 */
data class ArtifactRef(
    val id: String,
    val mediaType: String,
    val digest: IntegrityRef,
    val sizeBytes: Long,
    val storageLocation: StorageLocation,
    val verificationState: VerificationState,
    val producerReceipt: ProducerReceiptRef? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "ArtifactRef.id must be non-blank (FB-RAT-COM-002)." }
        require(mediaType.matches(Regex("^[\\w.+-]+/[\\w.+-]+$"))) {
            "ArtifactRef.mediaType must look like an IANA media type (got '$mediaType')."
        }
        require(sizeBytes == digest.byteLength) {
            "ArtifactRef.sizeBytes (${sizeBytes}) must equal digest.byteLength (${digest.byteLength})."
        }
    }
}

// ---------------------------------------------------------------------------------------
// MigrationPlan — schemas/common/migration-plan.schema.json
// ---------------------------------------------------------------------------------------

/**
 * Whether a schema transition crosses a MAJOR boundary (old readers MUST reject the new
 * payload per FB-RAT-COM-003 until they adopt this plan) or is MINOR (old readers keep
 * working unmigrated, preserving unknown fields).
 */
enum class MigrationCompatibility { MAJOR, MINOR }

/** Ordered data-migration steps a store/reader executes, and whether they're invertible. */
data class DataMigrationSteps(
    val steps: List<String>,
    val reversible: Boolean = false
) {
    init {
        require(steps.isNotEmpty()) { "DataMigrationSteps.steps must be non-empty." }
    }
}

/**
 * Ordered rollback steps. `steps` MUST be non-empty when `possible` is true, and MUST be
 * empty when `possible` is false — a plan MUST NOT claim rollback steps it does not
 * actually support (mirrors the if/then in migration-plan.schema.json; see
 * fixtures/common/invalid/migration-plan-rollback-contradiction.invalid.json for the
 * fixture that violates this).
 */
data class RollbackPlan(
    val possible: Boolean,
    val steps: List<String>
) {
    init {
        if (possible) {
            require(steps.isNotEmpty()) { "RollbackPlan.steps must be non-empty when possible=true." }
        } else {
            require(steps.isEmpty()) { "RollbackPlan.steps must be empty when possible=false." }
        }
    }
}

/**
 * Major/minor compatibility, data migration, and rollback for a schema-version transition
 * of some other contract (FB-RAT-COM-011). Typically carried as the `payload` of a
 * `ContractEnvelope<MigrationPlan>`.
 */
data class MigrationPlan(
    val migrationId: String,
    val fromSchemaVersion: String,
    val toSchemaVersion: String,
    val compatibility: MigrationCompatibility,
    val dataMigration: DataMigrationSteps,
    val rollback: RollbackPlan,
    val contractRef: String? = null,
    val issuedAtUtc: Instant? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(migrationId.isNotBlank()) { "MigrationPlan.migrationId must be non-blank (FB-RAT-COM-002)." }
    }
}

// ---------------------------------------------------------------------------------------
// ConformanceSuite — schemas/common/conformance-suite.schema.json
// ---------------------------------------------------------------------------------------

/** FB-RAT-DIST-004: the eight conformance test classes required as release governance. */
enum class ConformanceTestClass {
    GOLDEN_SERIALIZATION,
    STATE_TRANSITION,
    ADVERSARIAL,
    PROVIDER_CONFORMANCE,
    RECOVERY,
    PERFORMANCE,
    ACCESSIBILITY,
    COMPATIBILITY
}

/**
 * Whether a given ConformanceTestClass is exercisable on the JVM gate with no device
 * (`YES`), partially so (`PARTIAL` — structural/logic half is JVM-testable, a
 * device-owner-verified half remains), or not at all (`NO` — requires a real
 * device/emulator/board). See CLAUDE.md "Environment honesty" — this constellation's build
 * container has no device, emulator, board, or SSH host.
 */
enum class JvmTestability { YES, PARTIAL, NO }

/** One test class's coverage entry within a ConformanceSuite. */
data class ConformanceTestClassCoverage(
    val testClass: ConformanceTestClass,
    val jvmTestable: JvmTestability,
    val notes: String? = null
)

/**
 * Descriptor: name, test classes covered, fixture directory ref (FB-RAT-COM-011,
 * FB-RAT-DIST-004). Typically carried as the `payload` of a
 * `ContractEnvelope<ConformanceSuite>`.
 *
 * @param definitionOfReady FB-RAT-DIST-004: true only once golden-serialization +
 *   state-machine + adversarial + recovery + compatibility + provider-conformance
 *   fixtures exist for the referenced contract BEFORE feature implementation begins.
 */
data class ConformanceSuite(
    val suiteId: String,
    val name: String,
    val contractRef: String,
    val testClasses: List<ConformanceTestClassCoverage>,
    val fixtureDirectoryRef: String,
    val definitionOfReady: Boolean = false,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(suiteId.isNotBlank()) { "ConformanceSuite.suiteId must be non-blank (FB-RAT-COM-002)." }
        require(testClasses.isNotEmpty()) { "ConformanceSuite.testClasses must be non-empty." }
    }
}

// ---------------------------------------------------------------------------------------
// ImportReceipt — owned by the integration domain (INT-024), stub only
// ---------------------------------------------------------------------------------------

/**
 * ImportReceipt's full shape belongs to the integration domain (INT-024, a separate work
 * package/agent per the WP-1 task brief) — this file only declares the shared envelope
 * shape it reuses: an ImportReceipt is (in effect) a `ContractEnvelope<T>` where `T`
 * implements this open marker interface. Declared here, rather than in the integration
 * domain's own file, so that domain can depend on `foundations` without `foundations`
 * depending on it back.
 */
interface ImportReceiptPayload

/**
 * ExecutionReceipt is listed alongside ImportReceipt as a shared envelope concept under
 * FB-RAT-COM-011, but its concrete shape is owned by the Execution Contract + Authority
 * engine domain (WP-0 survey (c) — create-new, not detailed in this work package's object
 * model). Declared here as the same kind of open marker, for the same reason as
 * ImportReceiptPayload above: a forward pointer, not an invented shape. Do not add fields
 * to this interface from this file — that would be self-ratifying a shape nobody has
 * specified yet.
 */
interface ExecutionReceiptPayload
