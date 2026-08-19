package dev.fonebrew.domain.integrations

import dev.fonebrew.contracts.common.IntegrityRef
import dev.fonebrew.contracts.integrations.AssayCompleteness
import dev.fonebrew.contracts.integrations.AssayIndex
import dev.fonebrew.contracts.integrations.AuditFinding
import dev.fonebrew.contracts.integrations.AuditFindingLevel
import dev.fonebrew.contracts.integrations.ImportErrorCode
import dev.fonebrew.contracts.integrations.ImportPreview
import dev.fonebrew.contracts.integrations.ImportSourceType
import dev.fonebrew.contracts.integrations.PreviewDuplicateRecord
import dev.fonebrew.contracts.integrations.PreviewNewRecord
import dev.fonebrew.contracts.integrations.PreviewRecordKind
import dev.fonebrew.contracts.integrations.PreviewRejectedRecord
import dev.fonebrew.domain.contracts.IdGenerator
import java.time.Instant

/**
 * R3 (WP-7) -- the Assay repo lane's Validate/Preview logic, real per
 * `docs/ratified/ASSAY_REPO_CONTRACT_V1.md`. `runId` is the dedupe key (§2, INT-019) -- unlike
 * CSApp's per-issue dedupe, a whole Assay run is either already-imported (every finding becomes a
 * duplicate record pointing at the prior receipt) or new (every parsed SARIF result becomes a new
 * record) -- Assay's model has no per-finding "changed" concept across runs, since a run's
 * findings are an immutable batch tied to its `runId`.
 */
object AssayImportLane {

    fun interface RunDedupeIndex {
        /** Null if [runId] has never been imported before. */
        fun priorReceiptForRun(runId: String): String?
    }

    /**
     * Same raw-string rationale as [dev.fonebrew.domain.integrations.CsAppImportLane.
     * validateSchemaVersion]: [AssayIndex]'s own `init{}` already enforces `^1\.\d+\.\d+$`, so an
     * already-constructed instance can never carry an unsupported major to check here.
     */
    fun validateSchemaVersion(schemaVersionRaw: String): ImportErrorCode? =
        if (!schemaVersionRaw.startsWith("1.")) ImportErrorCode.UnsupportedMajor else null

    /**
     * INT-019/§2's "`sha256` MUST be recomputed and compared before trust" -- [actualSha256Hex]
     * is what the caller already fetched-and-hashed for each `findingFiles[].path` (real I/O,
     * outside this pure function's scope, mirroring [dev.fonebrew.domain.workspace.
     * LocalWorkspaceProvider]'s own content-digest pattern). A missing entry in the map is treated
     * the same as a mismatch -- the file was never actually verified.
     */
    fun validateFindingFileDigests(index: AssayIndex, actualSha256Hex: Map<String, String>): List<PreviewRejectedRecord> =
        index.findingFiles.mapNotNull { file ->
            val actual = actualSha256Hex[file.path]
            if (actual != file.sha256) {
                PreviewRejectedRecord(file.path, ImportErrorCode.DigestMismatch, "expected=${file.sha256} actual=${actual ?: "unread"}")
            } else null
        }

    data class AssayPreviewResult(
        val preview: ImportPreview,
        /** True when `index.completeness != COMPLETE` -- Confirm MUST require explicit partial-import acknowledgement (IMPORT_PARTIAL_SOURCE). */
        val isPartialSource: Boolean,
        val partialExplanation: String?,
    )

    fun buildPreview(
        index: AssayIndex,
        sarifOutcome: SarifParseOutcome,
        digestRejections: List<PreviewRejectedRecord>,
        dedupeIndex: RunDedupeIndex,
        sourceDigest: IntegrityRef,
        sourceLocation: String,
        now: Instant = Instant.now(),
    ): AssayPreviewResult {
        val priorReceiptId = dedupeIndex.priorReceiptForRun(index.runId)
        val rejectedFromSarif = sarifOutcome.rejections.map {
            PreviewRejectedRecord("findings.sarif#/runs/0/results/${it.resultIndex}", ImportErrorCode.InvalidField(it.missingField), "missing required properties.${it.missingField.lowercase()}")
        }

        val newRecords: List<PreviewNewRecord>
        val duplicateRecords: List<PreviewDuplicateRecord>

        if (priorReceiptId != null) {
            newRecords = emptyList()
            duplicateRecords = sarifOutcome.results.indices.map { i ->
                PreviewDuplicateRecord("findings.sarif#/runs/0/results/$i", priorReceiptId)
            }
        } else {
            newRecords = sarifOutcome.results.mapIndexed { i, result ->
                PreviewNewRecord("findings.sarif#/runs/0/results/$i", PreviewRecordKind.AUDIT_FINDING, result.messageText.take(200))
            }
            duplicateRecords = emptyList()
        }

        val preview = ImportPreview(
            schemaVersion = "1.0.0", previewId = "prev_" + IdGenerator.generate(),
            sourceType = ImportSourceType.ASSAY_REPO_INDEX, sourceDigest = sourceDigest, generatedAt = now,
            newRecords = newRecords, changedRecords = emptyList(), duplicateRecords = duplicateRecords,
            conflictingRecords = emptyList(), rejectedRecords = digestRejections + rejectedFromSarif, sourceLocation = sourceLocation,
        )
        return AssayPreviewResult(
            preview = preview,
            isPartialSource = index.completeness != AssayCompleteness.COMPLETE,
            partialExplanation = index.explanation,
        )
    }

    fun projectToAuditFinding(result: SarifResult, runId: String, sourceFindingRef: String, importReceiptId: String?): AuditFinding =
        AuditFinding(
            id = "audf_" + IdGenerator.generate(), sourceFindingRef = sourceFindingRef, runId = runId,
            ruleId = result.ruleIdFromProperties, scannerName = result.scannerName, level = mapLevel(result.level),
            message = result.messageText, locations = result.locationUris, provingTestRef = result.provingTestRef,
            importReceiptId = importReceiptId,
        )

    private fun mapLevel(sarifLevel: String): AuditFindingLevel = when (sarifLevel) {
        "error" -> AuditFindingLevel.ERROR
        "warning" -> AuditFindingLevel.WARNING
        "note" -> AuditFindingLevel.NOTE
        "none" -> AuditFindingLevel.NONE
        else -> AuditFindingLevel.WARNING // SARIF's own default level when a result omits "level" (2.1.0 spec section 3.27.10).
    }
}
