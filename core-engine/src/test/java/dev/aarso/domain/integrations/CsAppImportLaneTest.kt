package dev.aarso.domain.integrations

import dev.aarso.contracts.integrations.CsAppIssue
import dev.aarso.contracts.integrations.CsAppProducerRef
import dev.aarso.contracts.integrations.CsAppProjectRef
import dev.aarso.contracts.integrations.ImportErrorCode
import dev.aarso.contracts.integrations.IssueSeverity
import dev.aarso.contracts.integrations.IssuesManifest
import dev.aarso.domain.contracts.Digest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CsAppImportLaneTest {

    private fun issue(id: String, occurredAt: String = "2026-07-28T14:12:00Z", updatedAt: String = "2026-07-29T08:05:00Z", reporterRef: String = "user-1", sourceRevision: String? = "rev-1") =
        CsAppIssue(
            id = id, title = "t", detail = "d", severity = IssueSeverity.SEV2, reporterRef = reporterRef,
            occurredAt = Instant.parse(occurredAt), updatedAt = Instant.parse(updatedAt), sourceRevision = sourceRevision
        )

    private fun manifest(vararg issues: CsAppIssue) = IssuesManifest(
        schemaVersion = "1.0.0", exportId = "exp1", exportedAt = Instant.parse("2026-08-01T09:00:00Z"),
        producer = CsAppProducerRef("csapp", "2.4.1"), projectRef = CsAppProjectRef("proj-1"), issues = issues.toList()
    )

    private val digest = Digest.ofUtf8("manifest bytes")
    private val noPriorSnapshot = CsAppImportLane.PriorSnapshotIndex { _, _ -> null }
    private val emptyDedupe = CsAppImportLane.DedupeIndex { _, _ -> null }

    @Test
    fun `an issue never seen before is a new record`() {
        val preview = CsAppImportLane.buildPreview(manifest(issue("i1")), digest, emptyDedupe, noPriorSnapshot, "loc")
        assertEquals(1, preview.newRecords.size)
        assertTrue(preview.changedRecords.isEmpty() && preview.duplicateRecords.isEmpty() && preview.conflictingRecords.isEmpty())
    }

    @Test
    fun `an issue with an advanced updatedAt is a changed record`() {
        val dedupe = CsAppImportLane.DedupeIndex { _, id ->
            if (id == "i1") CsAppImportLane.ExistingCustomerIssueRecord("inc1", "user-1", Instant.parse("2026-07-28T14:12:00Z"), Instant.parse("2026-07-28T20:00:00Z"), "rev-0", "rcpt-old")
            else null
        }
        val preview = CsAppImportLane.buildPreview(manifest(issue("i1", updatedAt = "2026-07-29T08:05:00Z")), digest, dedupe, noPriorSnapshot, "loc")
        assertEquals(1, preview.changedRecords.size)
        assertEquals("inc1", preview.changedRecords.single().existingIncidentId)
        assertTrue("updatedAt" in preview.changedRecords.single().changedFields)
    }

    @Test
    fun `an issue whose identity fields differ from what is on file is a conflicting record, not changed`() {
        val dedupe = CsAppImportLane.DedupeIndex { _, id ->
            if (id == "i1") CsAppImportLane.ExistingCustomerIssueRecord("inc1", "user-DIFFERENT", Instant.parse("2026-07-28T14:12:00Z"), Instant.parse("2026-07-29T08:05:00Z"), "rev-1", "rcpt-old")
            else null
        }
        val preview = CsAppImportLane.buildPreview(manifest(issue("i1")), digest, dedupe, noPriorSnapshot, "loc")
        assertEquals(1, preview.conflictingRecords.size)
        assertTrue(preview.changedRecords.isEmpty())
        assertTrue("reporterRef" in preview.conflictingRecords.single().conflictingFields)
    }

    @Test
    fun `an issue identical to what is already on file is a per-record duplicate`() {
        val dedupe = CsAppImportLane.DedupeIndex { _, id ->
            if (id == "i1") CsAppImportLane.ExistingCustomerIssueRecord("inc1", "user-1", Instant.parse("2026-07-28T14:12:00Z"), Instant.parse("2026-07-29T08:05:00Z"), "rev-1", "rcpt-prior")
            else null
        }
        val preview = CsAppImportLane.buildPreview(manifest(issue("i1")), digest, dedupe, noPriorSnapshot, "loc")
        assertEquals(1, preview.duplicateRecords.size)
        assertEquals("rcpt-prior", preview.duplicateRecords.single().priorReceiptId)
    }

    @Test
    fun `a whole-source digest match makes every issue a duplicate, short-circuiting per-issue analysis`() {
        val priorSnapshot = CsAppImportLane.PriorSnapshotIndex { _, d -> if (d == digest) "rcpt-whole" else null }
        val m = manifest(issue("i1"), issue("i2"))
        val preview = CsAppImportLane.buildPreview(m, digest, emptyDedupe, priorSnapshot, "loc")
        assertEquals(2, preview.duplicateRecords.size)
        assertTrue(preview.duplicateRecords.all { it.priorReceiptId == "rcpt-whole" })
        assertTrue(preview.newRecords.isEmpty())
    }

    @Test
    fun `schemaVersion major 2 is UnsupportedMajor`() {
        // Checked against the RAW string, not a constructed IssuesManifest: that type's own
        // init{} already rejects a major-2 schemaVersion before this function could ever see one.
        assertEquals(ImportErrorCode.UnsupportedMajor, CsAppImportLane.validateSchemaVersion("2.0.0"))
    }

    @Test
    fun `schemaVersion major 1 validates clean`() {
        assertEquals(null, CsAppImportLane.validateSchemaVersion("1.0.0"))
    }

    @Test
    fun `tolerant parsing rejects only the record with an invalid severity, keeping the rest`() {
        val issuesJson = JSONArray(
            """
            [
              {"id":"good","title":"t","detail":"d","severity":"SEV1","reporterRef":"r","occurredAt":"2026-07-28T14:12:00Z","updatedAt":"2026-07-29T08:05:00Z"},
              {"id":"bad","title":"t","detail":"d","severity":"SEV9","reporterRef":"r","occurredAt":"2026-07-28T14:12:00Z","updatedAt":"2026-07-29T08:05:00Z"}
            ]
            """.trimIndent()
        )
        val result = CsAppImportLane.parseIssuesTolerant(issuesJson, "manifest.json")
        assertEquals(1, result.validIssues.size)
        assertEquals("good", result.validIssues.single().id)
        assertEquals(1, result.rejected.size)
        assertEquals(ImportErrorCode.InvalidField("SEVERITY"), result.rejected.single().errorCode)
    }

    @Test
    fun `tolerant parsing rejects an unparsable timestamp as IMPORT_INVALID_TIMESTAMP`() {
        val issuesJson = JSONArray(
            """[{"id":"bad","title":"t","detail":"d","severity":"SEV1","reporterRef":"r","occurredAt":"not-a-date","updatedAt":"2026-07-29T08:05:00Z"}]"""
        )
        val result = CsAppImportLane.parseIssuesTolerant(issuesJson, "manifest.json")
        assertTrue(result.validIssues.isEmpty())
        assertEquals(ImportErrorCode.InvalidField("TIMESTAMP"), result.rejected.single().errorCode)
    }

    @Test
    fun `projectToCustomerIssue carries the source issue's fields into the neutral record`() {
        val neutral = CsAppImportLane.projectToCustomerIssue(issue("i1"), "proj-1", "rcpt-1")
        assertEquals("i1", neutral.sourceId)
        assertEquals("proj-1", neutral.projectExternalId)
        assertEquals("rcpt-1", neutral.importReceiptId)
    }
}
