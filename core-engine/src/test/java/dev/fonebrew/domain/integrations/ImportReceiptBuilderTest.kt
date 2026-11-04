package dev.fonebrew.domain.integrations

import dev.fonebrew.contracts.integrations.ImportDecision
import dev.fonebrew.contracts.integrations.ImportPreview
import dev.fonebrew.contracts.integrations.ImportSourceType
import dev.fonebrew.contracts.integrations.PreviewDuplicateRecord
import dev.fonebrew.contracts.integrations.PreviewNewRecord
import dev.fonebrew.contracts.integrations.PreviewRecordKind
import dev.fonebrew.domain.contracts.Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ImportReceiptBuilderTest {

    private fun preview(vararg newIds: String) = ImportPreview(
        schemaVersion = "1.0.0", previewId = "prev-1", sourceType = ImportSourceType.CSAPP_ISSUES_MANIFEST,
        sourceDigest = Digest.ofUtf8("bytes"), generatedAt = Instant.parse("2026-08-01T09:00:00Z"),
        newRecords = newIds.map { PreviewNewRecord(it, PreviewRecordKind.CUSTOMER_ISSUE, "s") },
        changedRecords = emptyList(), duplicateRecords = emptyList(), conflictingRecords = emptyList(), rejectedRecords = emptyList(),
    )

    @Test
    fun `confirming every offered new record produces a FULL decision`() {
        val receipt = ImportReceiptBuilder.buildReceipt(
            preview("i1", "i2"), confirmedNewSourceIds = setOf("i1", "i2"), confirmedChangedSourceIds = emptySet(),
            incidentIdFor = { "inc_$it" }, initiatedBy = "user:jordan"
        )
        assertEquals(ImportDecision.FULL, receipt.decision)
        assertEquals(2, receipt.created.size)
    }

    @Test
    fun `confirming only some offered records produces a PARTIAL decision -- INT-007, never an implicit default`() {
        val receipt = ImportReceiptBuilder.buildReceipt(
            preview("i1", "i2"), confirmedNewSourceIds = setOf("i1"), confirmedChangedSourceIds = emptySet(),
            incidentIdFor = { "inc_$it" }, initiatedBy = "user:jordan"
        )
        assertEquals(ImportDecision.PARTIAL, receipt.decision)
        assertEquals(1, receipt.created.size)
        assertEquals("i1", receipt.created.single().sourceId)
    }

    @Test
    fun `duplicates and rejections from the preview carry straight through to the receipt`() {
        val withDup = preview("i1").copy(duplicateRecords = listOf(PreviewDuplicateRecord("i-dup", "rcpt-prior")))
        val receipt = ImportReceiptBuilder.buildReceipt(
            withDup, confirmedNewSourceIds = setOf("i1"), confirmedChangedSourceIds = emptySet(),
            incidentIdFor = { "inc_$it" }, initiatedBy = "user:jordan"
        )
        assertEquals(1, receipt.duplicates.size)
        assertEquals("rcpt-prior", receipt.duplicates.single().priorReceiptId)
    }

    @Test
    fun `receiptId and sourceDigest are carried from the preview, never re-derived`() {
        val p = preview("i1")
        val receipt = ImportReceiptBuilder.buildReceipt(p, setOf("i1"), emptySet(), { "inc_$it" }, "user:x")
        assertEquals(p.sourceDigest, receipt.sourceDigest)
        assertEquals(p.previewId, receipt.previewId)
    }

    @Test
    fun `duplicateSnapshotReceipt is true only when digests match exactly`() {
        val p = preview("i1")
        val receipt = ImportReceiptBuilder.buildReceipt(p, setOf("i1"), emptySet(), { "inc_$it" }, "user:x")
        assertTrue(ImportReceiptBuilder.duplicateSnapshotReceipt(receipt, p.sourceDigest))
        assertTrue(!ImportReceiptBuilder.duplicateSnapshotReceipt(receipt, Digest.ofUtf8("different bytes")))
    }
}
