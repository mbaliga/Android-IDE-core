package dev.aarso.domain.integrations

import dev.aarso.contracts.integrations.AssayCompleteness
import dev.aarso.contracts.integrations.AssayIndex
import dev.aarso.contracts.integrations.AssayProjectRef
import dev.aarso.contracts.integrations.AssayToolRef
import dev.aarso.contracts.integrations.FindingFileRef
import dev.aarso.contracts.integrations.ImportErrorCode
import dev.aarso.contracts.integrations.ProvingTestsSummary
import dev.aarso.domain.contracts.Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AssayImportLaneTest {

    private fun index(completeness: AssayCompleteness = AssayCompleteness.COMPLETE, explanation: String? = null) = AssayIndex(
        schemaVersion = "1.0.0", runId = "run-1",
        projectRef = AssayProjectRef("https://github.com/owner/repo.git"),
        sourceCommit = "a".repeat(40), assayCommit = "b".repeat(40),
        tool = AssayToolRef("assay-cli", "0.9.2"),
        startedAt = Instant.parse("2026-08-06T02:00:00Z"), finishedAt = Instant.parse("2026-08-06T02:14:00Z"),
        findingFiles = listOf(FindingFileRef("findings.sarif", "c".repeat(64), 1)),
        provingTests = ProvingTestsSummary("proving-tests.v1.json", 0),
        completeness = completeness, explanation = explanation,
    )

    private val oneResult = SarifParseOutcome(
        results = listOf(SarifResult("r1", "error", "msg", listOf("a.kt"), "Gitleaks", "secret-rule", null)),
        rejections = emptyList()
    )

    private val digest = Digest.ofUtf8("index bytes")
    private val neverImported = AssayImportLane.RunDedupeIndex { null }

    @Test
    fun `a run never imported before produces one new record per parsed SARIF result`() {
        val result = AssayImportLane.buildPreview(index(), oneResult, emptyList(), neverImported, digest, "loc")
        assertEquals(1, result.preview.newRecords.size)
        assertTrue(result.preview.duplicateRecords.isEmpty())
        assertEquals(false, result.isPartialSource)
    }

    @Test
    fun `a runId already imported makes every finding a duplicate, not a new record`() {
        val dedupe = AssayImportLane.RunDedupeIndex { if (it == "run-1") "rcpt-prior" else null }
        val result = AssayImportLane.buildPreview(index(), oneResult, emptyList(), dedupe, digest, "loc")
        assertEquals(1, result.preview.duplicateRecords.size)
        assertEquals("rcpt-prior", result.preview.duplicateRecords.single().priorReceiptId)
        assertTrue(result.preview.newRecords.isEmpty())
    }

    @Test
    fun `a PARTIAL completeness run is flagged isPartialSource, requiring explicit confirmation upstream`() {
        val result = AssayImportLane.buildPreview(index(AssayCompleteness.PARTIAL, "Semgrep timed out"), oneResult, emptyList(), neverImported, digest, "loc")
        assertTrue(result.isPartialSource)
        assertEquals("Semgrep timed out", result.partialExplanation)
    }

    @Test
    fun `a rejected SARIF result is carried into the preview's rejectedRecords`() {
        val withRejection = SarifParseOutcome(results = emptyList(), rejections = listOf(SarifResultRejection(0, "SCANNERNAME")))
        val result = AssayImportLane.buildPreview(index(), withRejection, emptyList(), neverImported, digest, "loc")
        assertEquals(1, result.preview.rejectedRecords.size)
        assertEquals(ImportErrorCode.InvalidField("SCANNERNAME"), result.preview.rejectedRecords.single().errorCode)
    }

    @Test
    fun `validateFindingFileDigests rejects a mismatched sha256, not a matching one`() {
        val idx = index()
        val rejections = AssayImportLane.validateFindingFileDigests(idx, mapOf("findings.sarif" to "wrong-digest"))
        assertEquals(1, rejections.size)
        assertEquals(ImportErrorCode.DigestMismatch, rejections.single().errorCode)
    }

    @Test
    fun `validateFindingFileDigests treats a never-fetched file the same as a mismatch`() {
        val idx = index()
        val rejections = AssayImportLane.validateFindingFileDigests(idx, emptyMap())
        assertEquals(1, rejections.size)
    }

    @Test
    fun `schemaVersion major 2 is UnsupportedMajor`() {
        // Checked against the RAW string, not a constructed AssayIndex: that type's own init{}
        // already rejects a major-2 schemaVersion before this function could ever see one.
        assertEquals(ImportErrorCode.UnsupportedMajor, AssayImportLane.validateSchemaVersion("2.0.0"))
    }

    @Test
    fun `projectToAuditFinding maps SARIF level and carries the scanner-attributed ruleId, not SARIF's own`() {
        val result = oneResult.results.single()
        val finding = AssayImportLane.projectToAuditFinding(result, "run-1", "findings.sarif#/runs/0/results/0", "rcpt-1")
        assertEquals("secret-rule", finding.ruleId) // properties.ruleId, not SARIF's own top-level "r1"
        assertEquals("Gitleaks", finding.scannerName)
        assertEquals(dev.aarso.contracts.integrations.AuditFindingLevel.ERROR, finding.level)
    }
}
