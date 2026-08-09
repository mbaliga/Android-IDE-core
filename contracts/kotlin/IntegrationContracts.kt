// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// IntegrationContracts.kt — the "integration" domain's shared wire-shape data classes:
// the CSApp file lane, the Assay repo lane, the shared ImportPreview/ImportReceipt grammar
// objects, and the neutral records Core derives from imported source records.
//
// Mirrors, field-for-field, the JSON Schema documents under schemas/integrations/*.schema.json:
//   IssuesManifest   -> issues-manifest.v1.schema.json
//   AssayIndex       -> assay-index.v1.schema.json
//   ProvingTests     -> proving-tests.v1.schema.json
//   ImportPreview    -> import-preview.v1.schema.json
//   ImportReceipt    -> import-receipt.v1.schema.json
// plus IntegrityRef, reused as-is from dev.aarso.contracts.common (schemas/common/
// artifact-ref.schema.json) — NOT redefined here, per this repo's convention. If a field
// appears in one place, it MUST appear in the other, or the two have drifted and one of
// them is wrong. See docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md, CSAPP_ISSUES_MANIFEST_V1.md,
// ASSAY_REPO_CONTRACT_V1.md, IMPORT_RECEIPT_V1.md, INCIDENT_SOURCE_AND_PROOF_CONTRACT.md for
// the citations (INT-001..026, minus 003/004/014/017/027 which are cross-referenced only) each
// field operationalizes.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no
// Android imports. This domain has no named Flow exception (unlike execution/workspace) —
// nothing here is a live provider interface.
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build
// environment — this file has been written carefully (balanced braces, matched types, no
// typos attempted) but has NOT been compiled. Do not report it as compiling; that is for
// the next session with Gradle available to confirm. This file also depends on
// contracts/kotlin/CommonContracts.kt (package dev.aarso.contracts.common) being compiled
// in the same module/source set — it is not a standalone-compilable file by itself.
//
// What is deliberately NOT in this file, and why:
//  - `Incident` — Studio-owned per the responsibility matrix (docs/ratified/
//    MANUAL_INTEGRATION_GRAMMAR.md §3: "Core must not own Studio priority/boards/incident
//    workflow"). This file's ImportReceipt.created[]/updated[] entries reference an
//    `incidentId: String` — an opaque reference into Studio's own model — never an embedded
//    Incident shape.
//  - A standalone `PermanentOverrideReceipt` top-level schema/type — out of scope for this
//    work package's schema list (docs/ratified/INCIDENT_SOURCE_AND_PROOF_CONTRACT.md §2).
//    `ProofRef.PermanentOverride` below is the in-memory shape a future Studio-owned schema
//    would mirror, not a claim that one is ratified yet.
//  - A `CsAppProducerRef`/`AssayToolRef` unification with dev.aarso.contracts.common.ProducerRef
//    — CSApp's own field name is `app` (not `name`), matching the source pack's worked field
//    list exactly (docs/ratified/CSAPP_ISSUES_MANIFEST_V1.md §1); forcing it into the common
//    ProducerRef shape would silently rename a field the wire schema does not rename.
//
// Why a sealed interface DOES appear twice in this file (ImportErrorCode, ProofRef) despite
// CommonContracts.kt's header explaining why the common/foundations domain needs none: both
// are genuinely open-or-closed vocabularies this domain's own object model owns outright —
// ImportErrorCode is a closed set of eight fixed codes PLUS an open per-field family
// (IMPORT_INVALID_<FIELD>, the registry-hygiene fix — docs/ratified/MANUAL_INTEGRATION_
// GRAMMAR.md §4 / docs/ratified/IMPORT_RECEIPT_V1.md §2) that a plain Kotlin `enum class`
// cannot represent at all (enums are closed by language construction); ProofRef is a small
// closed set of "what satisfies the INT-021 proof gate" cases (docs/ratified/
// INCIDENT_SOURCE_AND_PROOF_CONTRACT.md §2) where an exhaustive `when` at a resolution-gate
// call site is exactly the compile-time guarantee this domain wants, mirroring
// ExecutionContracts.kt's ExecutionLifecycleState precedent.

package dev.aarso.contracts.integrations

import dev.aarso.contracts.common.IntegrityRef
import java.time.Instant

// =========================================================================================
// Shared vocabulary
// =========================================================================================

/** Which lane produced a source snapshot an ImportPreview/ImportReceipt concerns. */
enum class ImportSourceType { CSAPP_ISSUES_MANIFEST, ASSAY_REPO_INDEX }

/**
 * The closed `IMPORT_*` / `PROOF_MISSING` vocabulary (docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md
 * §4) plus the open IMPORT_INVALID_<FIELD> record-level family (registry-hygiene fix — same
 * document, and docs/ratified/IMPORT_RECEIPT_V1.md §2). A plain `enum class` cannot represent
 * the open family, hence a sealed interface with one `data class` case for it, mirroring
 * dev.aarso.contracts.common.DigestAlgorithm's `fromWireValue` pattern for the closed part.
 */
sealed interface ImportErrorCode {
    val wireCode: String

    data object UnsupportedMajor : ImportErrorCode { override val wireCode = "IMPORT_UNSUPPORTED_MAJOR" }
    data object ProjectMismatch : ImportErrorCode { override val wireCode = "IMPORT_PROJECT_MISMATCH" }
    data object DigestMismatch : ImportErrorCode { override val wireCode = "IMPORT_DIGEST_MISMATCH" }
    data object DuplicateSnapshot : ImportErrorCode { override val wireCode = "IMPORT_DUPLICATE_SNAPSHOT" }
    data object RecordConflict : ImportErrorCode { override val wireCode = "IMPORT_RECORD_CONFLICT" }
    data object PartialSource : ImportErrorCode { override val wireCode = "IMPORT_PARTIAL_SOURCE" }
    data object SourceUnavailable : ImportErrorCode { override val wireCode = "IMPORT_SOURCE_UNAVAILABLE" }
    data object ProofMissing : ImportErrorCode { override val wireCode = "PROOF_MISSING" }

    /**
     * The registry-hygiene fix: one record-level field failed validation. `fieldName` MUST be
     * the SCREAMING_SNAKE_CASE name of the offending field (e.g. "SEVERITY", "TIMESTAMP") —
     * `wireCode` derives as "IMPORT_INVALID_" + fieldName, matching
     * schemas/integrations/import-receipt.v1.schema.json's `^IMPORT_INVALID_[A-Z0-9_]+$` family
     * pattern.
     */
    data class InvalidField(val fieldName: String) : ImportErrorCode {
        override val wireCode: String = "IMPORT_INVALID_${fieldName.uppercase()}"

        init {
            require(fieldName.isNotBlank()) { "ImportErrorCode.InvalidField.fieldName must be non-blank." }
            require(fieldName.matches(Regex("^[A-Za-z0-9_]+$"))) {
                "ImportErrorCode.InvalidField.fieldName must be alphanumeric/underscore only (got '$fieldName')."
            }
        }
    }

    companion object {
        private val FIXED: List<ImportErrorCode> = listOf(
            UnsupportedMajor, ProjectMismatch, DigestMismatch, DuplicateSnapshot,
            RecordConflict, PartialSource, SourceUnavailable, ProofMissing
        )

        /** Inverse of `wireCode` — throws on a code matching neither the fixed set nor the IMPORT_INVALID_<FIELD> pattern. */
        fun fromWireCode(code: String): ImportErrorCode {
            FIXED.firstOrNull { it.wireCode == code }?.let { return it }
            if (code.startsWith("IMPORT_INVALID_") && code.length > "IMPORT_INVALID_".length) {
                return InvalidField(code.removePrefix("IMPORT_INVALID_"))
            }
            throw IllegalArgumentException("Unknown ImportErrorCode wire value: $code")
        }
    }
}

// =========================================================================================
// IssuesManifest — schemas/integrations/issues-manifest.v1.schema.json (CSApp file lane)
// =========================================================================================

/** Field name is `app`, not `name` — mirrors the source pack's worked field list exactly. */
data class CsAppProducerRef(
    val app: String,
    val version: String
) {
    init {
        require(app.isNotBlank()) { "CsAppProducerRef.app must be non-blank." }
        require(version.isNotBlank()) { "CsAppProducerRef.version must be non-blank." }
    }
}

data class CsAppProjectRef(
    val externalId: String
) {
    init {
        require(externalId.isNotBlank()) { "CsAppProjectRef.externalId must be non-blank." }
    }
}

/** CSApp's own closed severity vocabulary, SEV1 (highest) through SEV4 (lowest). */
enum class IssueSeverity { SEV1, SEV2, SEV3, SEV4 }

/**
 * One CSApp issue within an export. `id` + the owning IssuesManifest.projectRef.externalId
 * together form the INT-006 dedupe key (docs/ratified/CSAPP_ISSUES_MANIFEST_V1.md §2).
 *
 * @param status Descriptive only (INT-015) — MUST NOT be auto-mapped onto a Studio Incident
 *   state; Studio maps it only after explicit user confirmation.
 */
data class CsAppIssue(
    val id: String,
    val title: String,
    val detail: String,
    val severity: IssueSeverity,
    val reporterRef: String,
    val occurredAt: Instant,
    val updatedAt: Instant,
    val sourceRevision: String? = null,
    val status: String? = null
) {
    init {
        require(id.isNotBlank()) { "CsAppIssue.id must be non-blank." }
        require(title.isNotBlank()) { "CsAppIssue.title must be non-blank." }
        require(reporterRef.isNotBlank()) { "CsAppIssue.reporterRef must be non-blank." }
    }
}

/**
 * CSApp's file-lane export — one snapshot of one project's issue backlog (INT-013).
 * Deliberately NOT wrapped in a dev.aarso.contracts.common.ContractEnvelope: CSApp is a fully
 * independent, standalone-useful app (INT-002) that MUST NOT depend on this constellation's
 * envelope library to write its own export file.
 */
data class IssuesManifest(
    val schemaVersion: String,
    val exportId: String,
    val exportedAt: Instant,
    val producer: CsAppProducerRef,
    val projectRef: CsAppProjectRef,
    val issues: List<CsAppIssue>,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "IssuesManifest.schemaVersion must be major version 1 (got '$schemaVersion') — " +
                "IMPORT_UNSUPPORTED_MAJOR for anything else (FB-RAT-COM-003)."
        }
        require(exportId.isNotBlank()) { "IssuesManifest.exportId must be non-blank (FB-RAT-COM-002)." }
    }
}

// =========================================================================================
// AssayIndex — schemas/integrations/assay-index.v1.schema.json (Assay repo lane)
// =========================================================================================

data class AssayProjectRef(
    val gitRemote: String,
    val fonebrewProjectHint: String? = null
) {
    init {
        require(gitRemote.isNotBlank()) { "AssayProjectRef.gitRemote must be non-blank." }
    }
}

data class AssayToolRef(
    val name: String,
    val version: String
) {
    init {
        require(name.isNotBlank()) { "AssayToolRef.name must be non-blank." }
        require(version.isNotBlank()) { "AssayToolRef.version must be non-blank." }
    }
}

data class FindingFileRef(
    val path: String,
    val sha256: String,
    val count: Int
) {
    init {
        require(path.isNotBlank()) { "FindingFileRef.path must be non-blank." }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "FindingFileRef.sha256 must be 64 lowercase-hex chars." }
        require(count >= 0) { "FindingFileRef.count must be >= 0." }
    }
}

data class ProvingTestsSummary(
    val path: String,
    val count: Int
) {
    init {
        require(path.isNotBlank()) { "ProvingTestsSummary.path must be non-blank." }
        require(count >= 0) { "ProvingTestsSummary.count must be >= 0." }
    }
}

/** Whether every configured scanner (MobSF/OSV-Scanner/Semgrep/Gitleaks, the SOLE finding sources) ran to completion. */
enum class AssayCompleteness { COMPLETE, PARTIAL, FAILED }

/**
 * The manifest at .assay/assay-index.v1.json (INT-018), read only when the user selects
 * repo+branch and taps "Check branch" (INT-020 — Studio never invokes or polls Assay).
 * `runId` is the stable dedupe key (INT-019).
 */
data class AssayIndex(
    val schemaVersion: String,
    val runId: String,
    val projectRef: AssayProjectRef,
    val sourceCommit: String,
    val assayCommit: String,
    val tool: AssayToolRef,
    val startedAt: Instant,
    val finishedAt: Instant,
    val findingFiles: List<FindingFileRef>,
    val provingTests: ProvingTestsSummary,
    val completeness: AssayCompleteness,
    val explanation: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "AssayIndex.schemaVersion must be major version 1 (got '$schemaVersion')."
        }
        require(runId.isNotBlank()) { "AssayIndex.runId must be non-blank (INT-019)." }
        require(sourceCommit.isNotBlank()) { "AssayIndex.sourceCommit must be non-blank." }
        require(assayCommit.isNotBlank()) { "AssayIndex.assayCommit must be non-blank." }
        if (completeness == AssayCompleteness.PARTIAL || completeness == AssayCompleteness.FAILED) {
            require(!explanation.isNullOrBlank()) {
                "AssayIndex.explanation must be non-blank when completeness=$completeness — " +
                    "a run MUST NOT silently under-report scanner coverage."
            }
        }
    }
}

// =========================================================================================
// ProvingTests — schemas/integrations/proving-tests.v1.schema.json
// =========================================================================================

enum class ProvingTestKind { UNIT, INTEGRATION, EXPLOIT_POC, REGRESSION, OTHER }

enum class ProvingTestStatus { PASSING, FAILING, NOT_RUN, ERROR }

/**
 * One generated proving test. `testId` is what a SARIF result's `properties.provingTestRef`
 * (docs/ratified/ASSAY_REPO_CONTRACT_V1.md §4) points back at.
 */
data class ProvingTestEntry(
    val testId: String,
    val targetFindingRef: String,
    val testKind: ProvingTestKind,
    val sourcePath: String,
    val status: ProvingTestStatus,
    val description: String? = null,
    val lastRunAtUtc: Instant? = null
) {
    init {
        require(testId.isNotBlank()) { "ProvingTestEntry.testId must be non-blank (FB-RAT-COM-002)." }
        require(targetFindingRef.isNotBlank()) { "ProvingTestEntry.targetFindingRef must be non-blank." }
        require(sourcePath.isNotBlank()) { "ProvingTestEntry.sourcePath must be non-blank." }
        if (status != ProvingTestStatus.NOT_RUN) {
            requireNotNull(lastRunAtUtc) {
                "ProvingTestEntry.lastRunAtUtc must be non-null when status=$status — a test " +
                    "cannot be reported PASSING/FAILING/ERROR without having actually run once."
            }
        }
    }
}

/** Container for one Assay run's proving-test entries — runId MUST equal the owning AssayIndex.runId. */
data class ProvingTests(
    val schemaVersion: String,
    val runId: String,
    val generatedAt: Instant,
    val tests: List<ProvingTestEntry>,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "ProvingTests.schemaVersion must be major version 1 (got '$schemaVersion')."
        }
        require(runId.isNotBlank()) { "ProvingTests.runId must be non-blank — must equal the owning AssayIndex.runId." }
    }
}

// =========================================================================================
// ImportPreview — schemas/integrations/import-preview.v1.schema.json
// =========================================================================================

enum class PreviewRecordKind { CUSTOMER_ISSUE, AUDIT_FINDING }

data class PreviewNewRecord(
    val sourceId: String,
    val recordKind: PreviewRecordKind,
    val summary: String
) {
    init {
        require(sourceId.isNotBlank()) { "PreviewNewRecord.sourceId must be non-blank." }
        require(summary.isNotBlank()) { "PreviewNewRecord.summary must be non-blank." }
    }
}

data class PreviewChangedRecord(
    val sourceId: String,
    val existingIncidentId: String,
    val changedFields: List<String>,
    val summary: String
) {
    init {
        require(sourceId.isNotBlank()) { "PreviewChangedRecord.sourceId must be non-blank." }
        require(existingIncidentId.isNotBlank()) { "PreviewChangedRecord.existingIncidentId must be non-blank (INT-016)." }
        require(changedFields.isNotEmpty()) { "PreviewChangedRecord.changedFields must be non-empty." }
        require(summary.isNotBlank()) { "PreviewChangedRecord.summary must be non-blank." }
    }
}

data class PreviewDuplicateRecord(
    val sourceId: String,
    val priorReceiptId: String,
    val priorImportedAtUtc: Instant? = null
) {
    init {
        require(sourceId.isNotBlank()) { "PreviewDuplicateRecord.sourceId must be non-blank." }
        require(priorReceiptId.isNotBlank()) { "PreviewDuplicateRecord.priorReceiptId must be non-blank (INT-006)." }
    }
}

data class PreviewConflictingRecord(
    val sourceId: String,
    val conflictingFields: List<String>,
    val summary: String,
    val existingIncidentId: String? = null
) {
    init {
        require(sourceId.isNotBlank()) { "PreviewConflictingRecord.sourceId must be non-blank." }
        require(conflictingFields.isNotEmpty()) { "PreviewConflictingRecord.conflictingFields must be non-empty." }
        require(summary.isNotBlank()) { "PreviewConflictingRecord.summary must be non-blank." }
    }
}

data class PreviewRejectedRecord(
    val sourceLocation: String,
    val errorCode: ImportErrorCode,
    val detail: String? = null
) {
    init {
        require(sourceLocation.isNotBlank()) { "PreviewRejectedRecord.sourceLocation must be non-blank." }
    }
}

/**
 * Step 4 ("Preview") of the six-step manual import grammar (INT-011) — computed after Validate,
 * before any mutation, so the user can make an informed Confirm decision (INT-007/INT-010).
 */
data class ImportPreview(
    val schemaVersion: String,
    val previewId: String,
    val sourceType: ImportSourceType,
    val sourceDigest: IntegrityRef,
    val generatedAt: Instant,
    val newRecords: List<PreviewNewRecord>,
    val changedRecords: List<PreviewChangedRecord>,
    val duplicateRecords: List<PreviewDuplicateRecord>,
    val conflictingRecords: List<PreviewConflictingRecord>,
    val rejectedRecords: List<PreviewRejectedRecord>,
    val sourceLocation: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "ImportPreview.schemaVersion must be major version 1 (got '$schemaVersion')."
        }
        require(previewId.isNotBlank()) { "ImportPreview.previewId must be non-blank (FB-RAT-COM-002)." }
    }
}

// =========================================================================================
// ImportReceipt — schemas/integrations/import-receipt.v1.schema.json
// =========================================================================================

/**
 * FULL: every importable record the preview offered was imported as offered. PARTIAL: the
 * user explicitly excluded at least one (INT-007) — never an implicit default.
 */
enum class ImportDecision { FULL, PARTIAL }

data class ReceiptCreatedEntry(
    val sourceId: String,
    /** Always an Incident id — imports create/update Incidents, NEVER Tasks (INT-016). */
    val incidentId: String
) {
    init {
        require(sourceId.isNotBlank()) { "ReceiptCreatedEntry.sourceId must be non-blank." }
        require(incidentId.isNotBlank()) { "ReceiptCreatedEntry.incidentId must be non-blank." }
    }
}

data class ReceiptUpdatedEntry(
    val sourceId: String,
    val incidentId: String,
    val changedFields: List<String> = emptyList()
) {
    init {
        require(sourceId.isNotBlank()) { "ReceiptUpdatedEntry.sourceId must be non-blank." }
        require(incidentId.isNotBlank()) { "ReceiptUpdatedEntry.incidentId must be non-blank." }
    }
}

data class ReceiptDuplicateEntry(
    val sourceId: String,
    val priorReceiptId: String
) {
    init {
        require(sourceId.isNotBlank()) { "ReceiptDuplicateEntry.sourceId must be non-blank." }
        require(priorReceiptId.isNotBlank()) { "ReceiptDuplicateEntry.priorReceiptId must be non-blank (INT-006)." }
    }
}

data class ReceiptRejectedEntry(
    val sourceLocation: String,
    val errorCode: ImportErrorCode,
    val detail: String? = null
) {
    init {
        require(sourceLocation.isNotBlank()) { "ReceiptRejectedEntry.sourceLocation must be non-blank." }
    }
}

/**
 * Step 6 ("Receipt") of the six-step manual import grammar (INT-011, INT-024) — the durable,
 * append-only (FB-RAT-COM-006) record of one confirmed import action. A correction is a NEW
 * receipt, never an in-place edit.
 */
data class ImportReceipt(
    val schemaVersion: String,
    val receiptId: String,
    val sourceType: ImportSourceType,
    val sourceDigest: IntegrityRef,
    val initiatedBy: String,
    val decision: ImportDecision,
    val createdAtUtc: Instant,
    val created: List<ReceiptCreatedEntry>,
    val updated: List<ReceiptUpdatedEntry>,
    val duplicates: List<ReceiptDuplicateEntry>,
    val rejected: List<ReceiptRejectedEntry>,
    val sourceLocation: String? = null,
    val previewId: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "ImportReceipt.schemaVersion must be major version 1 (got '$schemaVersion')."
        }
        require(receiptId.isNotBlank()) { "ImportReceipt.receiptId must be non-blank (FB-RAT-COM-002)." }
        require(initiatedBy.isNotBlank()) { "ImportReceipt.initiatedBy must be non-blank (FB-RAT-COM-008)." }
    }
}

// =========================================================================================
// Neutral records — Core-owned per the responsibility matrix (docs/ratified/
// MANUAL_INTEGRATION_GRAMMAR.md §3): what Core derives from an imported source record, before
// Studio turns it into an Incident. Not wire types with their own schema files — these are
// this domain's in-memory shapes an import pipeline implementation programs against.
// =========================================================================================

/**
 * Core's neutral projection of one imported CsAppIssue. `id` is this projection's own stable
 * id (FB-RAT-COM-002), distinct from `sourceId` (the CsAppIssue.id it was derived from).
 */
data class CustomerIssue(
    val id: String,
    val sourceId: String,
    val projectExternalId: String,
    val title: String,
    val detail: String,
    val severity: IssueSeverity,
    val reporterRef: String,
    val occurredAt: Instant,
    val updatedAt: Instant,
    val sourceRevision: String? = null,
    /** Descriptive only (INT-015) — carried through verbatim, never interpreted by Core. */
    val statusRaw: String? = null,
    val importReceiptId: String? = null
) {
    init {
        require(id.isNotBlank()) { "CustomerIssue.id must be non-blank (FB-RAT-COM-002)." }
        require(sourceId.isNotBlank()) { "CustomerIssue.sourceId must be non-blank." }
        require(projectExternalId.isNotBlank()) { "CustomerIssue.projectExternalId must be non-blank." }
        require(title.isNotBlank()) { "CustomerIssue.title must be non-blank." }
    }
}

/** SARIF result severity band — mirrors SARIF 2.1.0's own `level` vocabulary (docs/ratified/ASSAY_REPO_CONTRACT_V1.md §4). */
enum class AuditFindingLevel { ERROR, WARNING, NOTE, NONE }

/**
 * Core's neutral projection of one Assay/SARIF finding. `id` is this projection's own stable
 * id, distinct from `sourceFindingRef` (the opaque SARIF result pointer it was derived from).
 */
data class AuditFinding(
    val id: String,
    val sourceFindingRef: String,
    val runId: String,
    val ruleId: String,
    val scannerName: String,
    val level: AuditFindingLevel,
    val message: String,
    val locations: List<String> = emptyList(),
    val provingTestRef: String? = null,
    val importReceiptId: String? = null
) {
    init {
        require(id.isNotBlank()) { "AuditFinding.id must be non-blank (FB-RAT-COM-002)." }
        require(sourceFindingRef.isNotBlank()) { "AuditFinding.sourceFindingRef must be non-blank." }
        require(runId.isNotBlank()) { "AuditFinding.runId must be non-blank (INT-019)." }
        require(ruleId.isNotBlank()) { "AuditFinding.ruleId must be non-blank." }
        require(scannerName.isNotBlank()) { "AuditFinding.scannerName must be non-blank (SOLE finding source, docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md §3)." }
        require(message.isNotBlank()) { "AuditFinding.message must be non-blank." }
    }
}

/**
 * What satisfies the INT-021 proof gate for resolving an Assay-sourced Incident (docs/ratified/
 * INCIDENT_SOURCE_AND_PROOF_CONTRACT.md §2). The ABSENCE of a ProofRef (a null reference held
 * by Studio's own Incident model, not represented as a case of this sealed interface) is the
 * PROOF_MISSING state — this type only models the two ways a gate CAN be satisfied.
 */
sealed interface ProofRef {
    /** A proving test (schemas/integrations/proving-tests.v1.schema.json) with status=PASSING satisfies the gate. */
    data class ProvingTest(
        val testId: String,
        val verifiedAtUtc: Instant
    ) : ProofRef {
        init {
            require(testId.isNotBlank()) { "ProofRef.ProvingTest.testId must be non-blank." }
        }
    }

    /**
     * A Studio user's explicit, permanent override of the proof requirement (docs/ratified/
     * INCIDENT_SOURCE_AND_PROOF_CONTRACT.md §2). Permanent — does not expire, does not
     * silently disappear if the Incident is later reopened.
     */
    data class PermanentOverride(
        val recordedBy: String,
        val recordedAtUtc: Instant,
        val justification: String
    ) : ProofRef {
        init {
            require(recordedBy.isNotBlank()) { "ProofRef.PermanentOverride.recordedBy must be non-blank (FB-RAT-COM-008)." }
            require(justification.isNotBlank()) { "ProofRef.PermanentOverride.justification must be non-blank." }
        }
    }
}
