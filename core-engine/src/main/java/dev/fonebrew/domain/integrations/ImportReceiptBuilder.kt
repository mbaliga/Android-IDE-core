package dev.fonebrew.domain.integrations

import dev.fonebrew.contracts.common.IntegrityRef
import dev.fonebrew.contracts.integrations.ImportDecision
import dev.fonebrew.contracts.integrations.ImportPreview
import dev.fonebrew.contracts.integrations.ImportReceipt
import dev.fonebrew.contracts.integrations.ReceiptCreatedEntry
import dev.fonebrew.contracts.integrations.ReceiptDuplicateEntry
import dev.fonebrew.contracts.integrations.ReceiptRejectedEntry
import dev.fonebrew.contracts.integrations.ReceiptUpdatedEntry
import dev.fonebrew.domain.contracts.IdGenerator
import java.time.Instant

/**
 * R1 (WP-7) -- the Confirm/Receipt step (§2, steps 5-6) shared by both lanes
 * (`docs/ratified/IMPORT_RECEIPT_V1.md`: "shared by both lanes... a receipt does not care which
 * produced it beyond its `sourceType` field"). Core owns receipt writing but MUST NOT own
 * Incident creation itself (`docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §3's responsibility
 * matrix -- "Core must not own... Studio priority/boards/incident workflow") -- [incidentIdFor]
 * is the caller-supplied seam a real Studio surface fills with its own generated Incident ids;
 * this builder only assembles the receipt shape around whatever ids it's given.
 */
object ImportReceiptBuilder {

    /**
     * @param confirmedNewSourceIds Which of [ImportPreview.newRecords]/`changedRecords` the user
     *   selected at Confirm (INT-007 -- explicit approval, never an implicit "import everything").
     * @param incidentIdFor Resolves a confirmed sourceId to the Incident id Studio (or a test
     *   double) assigned it -- Core never invents this id itself.
     */
    fun buildReceipt(
        preview: ImportPreview,
        confirmedNewSourceIds: Set<String>,
        confirmedChangedSourceIds: Set<String>,
        incidentIdFor: (sourceId: String) -> String,
        initiatedBy: String,
        now: Instant = Instant.now(),
    ): ImportReceipt {
        val allOfferedIds = (preview.newRecords.map { it.sourceId } + preview.changedRecords.map { it.sourceId }).toSet()
        val confirmedIds = confirmedNewSourceIds + confirmedChangedSourceIds
        val decision = if (confirmedIds == allOfferedIds) ImportDecision.FULL else ImportDecision.PARTIAL

        val created = preview.newRecords
            .filter { it.sourceId in confirmedNewSourceIds }
            .map { ReceiptCreatedEntry(it.sourceId, incidentIdFor(it.sourceId)) }

        val updated = preview.changedRecords
            .filter { it.sourceId in confirmedChangedSourceIds }
            .map { ReceiptUpdatedEntry(it.sourceId, incidentIdFor(it.sourceId), it.changedFields) }

        val duplicates = preview.duplicateRecords.map { ReceiptDuplicateEntry(it.sourceId, it.priorReceiptId) }
        val rejected = preview.rejectedRecords.map { ReceiptRejectedEntry(it.sourceLocation, it.errorCode, it.detail) }

        return ImportReceipt(
            schemaVersion = "1.0.0", receiptId = "irec_" + IdGenerator.generate(), sourceType = preview.sourceType,
            sourceDigest = preview.sourceDigest, initiatedBy = initiatedBy, decision = decision, createdAtUtc = now,
            created = created, updated = updated, duplicates = duplicates, rejected = rejected,
            sourceLocation = preview.sourceLocation, previewId = preview.previewId,
        )
    }

    /** The "already imported at this exact digest" short-circuit path (INT-006) -- no Preview/Confirm needed, just the prior receipt. */
    fun duplicateSnapshotReceipt(priorReceipt: ImportReceipt, requestedDigest: IntegrityRef): Boolean =
        priorReceipt.sourceDigest == requestedDigest
}
