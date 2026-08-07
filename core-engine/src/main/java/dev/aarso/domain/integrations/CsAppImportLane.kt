package dev.aarso.domain.integrations

import dev.aarso.contracts.common.IntegrityRef
import dev.aarso.contracts.integrations.CsAppIssue
import dev.aarso.contracts.integrations.CustomerIssue
import dev.aarso.contracts.integrations.ImportErrorCode
import dev.aarso.contracts.integrations.ImportPreview
import dev.aarso.contracts.integrations.ImportSourceType
import dev.aarso.contracts.integrations.IssueSeverity
import dev.aarso.contracts.integrations.IssuesManifest
import dev.aarso.contracts.integrations.PreviewChangedRecord
import dev.aarso.contracts.integrations.PreviewConflictingRecord
import dev.aarso.contracts.integrations.PreviewDuplicateRecord
import dev.aarso.contracts.integrations.PreviewNewRecord
import dev.aarso.contracts.integrations.PreviewRecordKind
import dev.aarso.contracts.integrations.PreviewRejectedRecord
import dev.aarso.domain.contracts.IdGenerator
import java.time.Instant

/**
 * R2 (WP-7) -- the CSApp file lane's Validate/Preview logic, real per
 * `docs/ratified/CSAPP_ISSUES_MANIFEST_V1.md`. Snapshot (bytes + digest) and Confirm/Receipt-
 * writing are shared across lanes -- see [ManualImportGrammar]/[ImportReceiptBuilder]; this file
 * is the CSApp-specific Validate + Preview steps (§2, MANUAL_INTEGRATION_GRAMMAR.md).
 */
object CsAppImportLane {

    /** What Core already has on file for one `(projectExternalId, issueId)` dedupe key (§2). */
    data class ExistingCustomerIssueRecord(
        val incidentId: String,
        val reporterRef: String,
        val occurredAt: Instant,
        val updatedAt: Instant,
        val sourceRevision: String?,
        val lastReceiptId: String,
    )

    fun interface DedupeIndex {
        /** Null if this exact `(projectExternalId, issueId)` has never been imported. */
        fun existingIssue(projectExternalId: String, issueId: String): ExistingCustomerIssueRecord?
    }

    /** Null if [digest] does not match any prior full-manifest import for [projectExternalId]. */
    fun interface PriorSnapshotIndex {
        fun priorReceiptForDigest(projectExternalId: String, digest: IntegrityRef): String?
    }

    /**
     * Validate step (§2, step 3): schemaVersion major check. Takes the RAW string read from JSON,
     * not an already-constructed [IssuesManifest] -- that type's own `init{}` already enforces
     * `^1\.\d+\.\d+$` and throws for anything else, so by the time a caller has a real
     * `IssuesManifest` instance in hand, an unsupported major version could never have survived
     * construction to be checked here. A real pipeline calls this against the raw JSON field
     * BEFORE attempting [dev.aarso.domain.contracts.IntegrationsCodec.decodeIssuesManifest].
     */
    fun validateSchemaVersion(schemaVersionRaw: String): ImportErrorCode? =
        if (!schemaVersionRaw.startsWith("1.")) ImportErrorCode.UnsupportedMajor else null

    /**
     * Preview step (§2, step 4). A whole-manifest digest match against a prior import
     * (`priorSnapshotIndex`) makes every issue a duplicate record and short-circuits per-issue
     * analysis (`docs/ratified/CSAPP_ISSUES_MANIFEST_V1.md` §2's "duplicate snapshot" case);
     * otherwise each issue is classified new/changed/conflicting/duplicate against [dedupeIndex].
     */
    fun buildPreview(
        manifest: IssuesManifest,
        sourceDigest: IntegrityRef,
        dedupeIndex: DedupeIndex,
        priorSnapshotIndex: PriorSnapshotIndex,
        sourceLocation: String,
        now: Instant = Instant.now(),
    ): ImportPreview {
        val projectExternalId = manifest.projectRef.externalId
        val wholeSourceDuplicateReceiptId = priorSnapshotIndex.priorReceiptForDigest(projectExternalId, sourceDigest)

        val newRecords = mutableListOf<PreviewNewRecord>()
        val changedRecords = mutableListOf<PreviewChangedRecord>()
        val duplicateRecords = mutableListOf<PreviewDuplicateRecord>()
        val conflictingRecords = mutableListOf<PreviewConflictingRecord>()

        if (wholeSourceDuplicateReceiptId != null) {
            manifest.issues.forEach { issue ->
                duplicateRecords += PreviewDuplicateRecord(issue.id, wholeSourceDuplicateReceiptId)
            }
        } else {
            for (issue in manifest.issues) {
                val existing = dedupeIndex.existingIssue(projectExternalId, issue.id)
                when {
                    existing == null -> newRecords += PreviewNewRecord(issue.id, PreviewRecordKind.CUSTOMER_ISSUE, issue.title)

                    existing.reporterRef != issue.reporterRef || existing.occurredAt != issue.occurredAt ->
                        conflictingRecords += PreviewConflictingRecord(
                            sourceId = issue.id,
                            conflictingFields = buildList {
                                if (existing.reporterRef != issue.reporterRef) add("reporterRef")
                                if (existing.occurredAt != issue.occurredAt) add("occurredAt")
                            },
                            summary = "Identity fields differ from what Core already has on file for this issue.",
                            existingIncidentId = existing.incidentId,
                        )

                    existing.updatedAt != issue.updatedAt || existing.sourceRevision != issue.sourceRevision ->
                        changedRecords += PreviewChangedRecord(
                            sourceId = issue.id, existingIncidentId = existing.incidentId,
                            changedFields = buildList {
                                if (existing.updatedAt != issue.updatedAt) add("updatedAt")
                                if (existing.sourceRevision != issue.sourceRevision) add("sourceRevision")
                            },
                            summary = issue.title,
                        )

                    else -> duplicateRecords += PreviewDuplicateRecord(issue.id, existing.lastReceiptId)
                }
            }
        }

        return ImportPreview(
            schemaVersion = "1.0.0", previewId = "prev_" + IdGenerator.generate(),
            sourceType = ImportSourceType.CSAPP_ISSUES_MANIFEST, sourceDigest = sourceDigest, generatedAt = now,
            newRecords = newRecords, changedRecords = changedRecords, duplicateRecords = duplicateRecords,
            conflictingRecords = conflictingRecords, rejectedRecords = emptyList(), sourceLocation = sourceLocation,
        )
    }

    /** Confirm/Receipt step's per-issue projection into Core's neutral record (§3 of the manual-integration-grammar responsibility matrix). */
    fun projectToCustomerIssue(issue: CsAppIssue, projectExternalId: String, importReceiptId: String?): CustomerIssue =
        CustomerIssue(
            id = "cisu_" + IdGenerator.generate(), sourceId = issue.id, projectExternalId = projectExternalId,
            title = issue.title, detail = issue.detail, severity = issue.severity, reporterRef = issue.reporterRef,
            occurredAt = issue.occurredAt, updatedAt = issue.updatedAt, sourceRevision = issue.sourceRevision,
            statusRaw = issue.status, importReceiptId = importReceiptId,
        )

    // -----------------------------------------------------------------------------------
    // Tolerant per-record parsing (§4, MANUAL_INTEGRATION_GRAMMAR.md): unlike
    // IntegrationsCodec.decodeIssuesManifest (a strict round-trip codec, throws on any malformed
    // field), Validate/Preview needs a bad SEVERITY or an unparsable TIMESTAMP on ONE issue to
    // reject only that record (IMPORT_INVALID_<FIELD>), not the whole manifest -- constructing
    // a real dev.aarso.contracts.integrations.CsAppIssue can't hold an invalid value at all
    // (IssueSeverity is a closed enum), so this parses defensively at the JSON level first.
    // -----------------------------------------------------------------------------------

    data class TolerantParseResult(val validIssues: List<CsAppIssue>, val rejected: List<PreviewRejectedRecord>)

    fun parseIssuesTolerant(issuesJson: org.json.JSONArray, sourceLocation: String): TolerantParseResult {
        val valid = mutableListOf<CsAppIssue>()
        val rejected = mutableListOf<PreviewRejectedRecord>()
        for (i in 0 until issuesJson.length()) {
            val j = issuesJson.getJSONObject(i)
            val id = j.optString("id").ifBlank { "issue[$i]" }
            val severityRaw = j.optString("severity")
            val severity = runCatching { IssueSeverity.valueOf(severityRaw) }.getOrNull()
            if (severity == null) {
                rejected += PreviewRejectedRecord("$sourceLocation#$id", ImportErrorCode.InvalidField("SEVERITY"), "severity='$severityRaw'")
                continue
            }
            val occurredAt = runCatching { Instant.parse(j.getString("occurredAt")) }.getOrNull()
            val updatedAt = runCatching { Instant.parse(j.getString("updatedAt")) }.getOrNull()
            if (occurredAt == null || updatedAt == null) {
                rejected += PreviewRejectedRecord("$sourceLocation#$id", ImportErrorCode.InvalidField("TIMESTAMP"), "unparsable occurredAt/updatedAt")
                continue
            }
            valid += CsAppIssue(
                id = j.getString("id"), title = j.getString("title"), detail = j.getString("detail"),
                severity = severity, reporterRef = j.getString("reporterRef"), occurredAt = occurredAt, updatedAt = updatedAt,
                sourceRevision = if (j.has("sourceRevision") && !j.isNull("sourceRevision")) j.getString("sourceRevision") else null,
                status = if (j.has("status") && !j.isNull("status")) j.getString("status") else null,
            )
        }
        return TolerantParseResult(valid, rejected)
    }
}
