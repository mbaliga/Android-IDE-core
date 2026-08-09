package dev.aarso.domain.contracts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips against the REAL WP-1 fixtures (`fixtures/integrations/valid/`), embedded
 * verbatim rather than loaded from disk -- this module's JVM test working directory isn't
 * established as reading repo-root fixture files anywhere else in this codebase, so embedding
 * keeps this test self-contained the same way every other codec test in this session has been.
 */
class IntegrationsCodecTest {

    // Verbatim copy of fixtures/integrations/valid/issues-manifest-baseline-valid.json
    private val issuesManifestJson = """
        {
          "schemaVersion": "1.0.0",
          "exportId": "01J9A0000000000000000EX1",
          "exportedAt": "2026-08-01T09:00:00Z",
          "producer": { "app": "csapp", "version": "2.4.1" },
          "projectRef": { "externalId": "csapp-proj-aarso-mobile" },
          "issues": [
            {
              "id": "csapp-iss-1001",
              "title": "Crash on markdown-heavy reply",
              "detail": "User reports app crash when a model reply contains a large table. Repro attached in CSApp.",
              "severity": "SEV2",
              "reporterRef": "csapp-user-88231",
              "occurredAt": "2026-07-28T14:12:00Z",
              "updatedAt": "2026-07-29T08:05:00Z",
              "sourceRevision": "rev-14",
              "status": "triaged"
            },
            {
              "id": "csapp-iss-1002",
              "title": "Council responses sometimes duplicate a participant",
              "detail": "Two council members returned near-identical text in one run.",
              "severity": "SEV3",
              "reporterRef": "csapp-user-40217",
              "occurredAt": "2026-07-30T11:40:00Z",
              "updatedAt": "2026-07-30T11:40:00Z",
              "sourceRevision": null,
              "status": "open"
            }
          ],
          "unknownFields": {}
        }
    """.trimIndent()

    // Verbatim copy of fixtures/integrations/valid/assay-index-valid.json
    private val assayIndexJson = """
        {
          "schemaVersion": "1.0.0",
          "runId": "01J9E0000000000000000RN1",
          "projectRef": {
            "gitRemote": "https://github.com/owner/target-repo.git",
            "fonebrewProjectHint": "core-mobile"
          },
          "sourceCommit": "ab3567ecf1a2b3c4d5e6f7089a1b2c3d4e5f6789",
          "assayCommit": "cd7890ab1c2d3e4f5061728394a5b6c7d8e9f01",
          "tool": { "name": "assay-cli", "version": "0.9.2" },
          "startedAt": "2026-08-06T02:00:00Z",
          "finishedAt": "2026-08-06T02:14:00Z",
          "findingFiles": [
            { "path": "findings.sarif", "sha256": "fe943a5951937392e255d625e6093e3045d992be85dc0821573517d0b03e3a9d", "count": 7 },
            { "path": "findings.json", "sha256": "cdfaffbe111e0b693aea1146f910c39070161c566f3ce4ba7b475fffab31ba4c", "count": 7 }
          ],
          "provingTests": { "path": "proving-tests.v1.json", "count": 3 },
          "completeness": "COMPLETE",
          "explanation": null,
          "unknownFields": {}
        }
    """.trimIndent()

    // Verbatim copy of fixtures/integrations/valid/import-receipt-baseline-valid.json
    private val importReceiptJson = """
        {
          "schemaVersion": "1.0.0",
          "receiptId": "01J9B0000000000000000RC1",
          "sourceType": "CSAPP_ISSUES_MANIFEST",
          "sourceDigest": {
            "algorithm": "SHA-256",
            "digestHex": "1ec02cdcc218b1fc73ccfcb61d70faf443998c5342631c4bbae16c50df498d2b",
            "byteLength": 812
          },
          "sourceLocation": "csapp-export://csapp-proj-aarso-mobile/2026-08-01T09:00:00Z",
          "previewId": "01J9C0000000000000000PV1",
          "initiatedBy": "studio-user:jordan",
          "decision": "FULL",
          "createdAtUtc": "2026-08-01T09:05:00Z",
          "created": [
            { "sourceId": "csapp-iss-1001", "incidentId": "01J9D0000000000000000IN1" },
            { "sourceId": "csapp-iss-1002", "incidentId": "01J9D0000000000000000IN2" }
          ],
          "updated": [],
          "duplicates": [],
          "rejected": [],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test
    fun `decodes the real WP-1 issues-manifest-baseline-valid fixture exactly`() {
        val manifest = IntegrationsCodec.decodeIssuesManifest(JSONObject(issuesManifestJson))
        assertEquals("01J9A0000000000000000EX1", manifest.exportId)
        assertEquals("csapp", manifest.producer.app)
        assertEquals("csapp-proj-aarso-mobile", manifest.projectRef.externalId)
        assertEquals(2, manifest.issues.size)
        assertEquals("rev-14", manifest.issues[0].sourceRevision)
        assertEquals(null, manifest.issues[1].sourceRevision)
    }

    @Test
    fun `IssuesManifest round-trips through encode after decode`() {
        val original = IntegrationsCodec.decodeIssuesManifest(JSONObject(issuesManifestJson))
        val reDecoded = IntegrationsCodec.decodeIssuesManifest(IntegrationsCodec.encodeIssuesManifest(original))
        assertEquals(original, reDecoded)
    }

    @Test
    fun `decodes the real WP-1 assay-index-valid fixture exactly`() {
        val index = IntegrationsCodec.decodeAssayIndex(JSONObject(assayIndexJson))
        assertEquals("01J9E0000000000000000RN1", index.runId)
        assertEquals("core-mobile", index.projectRef.fonebrewProjectHint)
        assertEquals(2, index.findingFiles.size)
        assertEquals(dev.aarso.contracts.integrations.AssayCompleteness.COMPLETE, index.completeness)
    }

    @Test
    fun `AssayIndex round-trips through encode after decode`() {
        val original = IntegrationsCodec.decodeAssayIndex(JSONObject(assayIndexJson))
        val reDecoded = IntegrationsCodec.decodeAssayIndex(IntegrationsCodec.encodeAssayIndex(original))
        assertEquals(original, reDecoded)
    }

    @Test
    fun `decodes the real WP-1 import-receipt-baseline-valid fixture exactly`() {
        val receipt = IntegrationsCodec.decodeImportReceipt(JSONObject(importReceiptJson))
        assertEquals("01J9B0000000000000000RC1", receipt.receiptId)
        assertEquals(dev.aarso.contracts.integrations.ImportDecision.FULL, receipt.decision)
        assertEquals(2, receipt.created.size)
        assertTrue(receipt.updated.isEmpty() && receipt.duplicates.isEmpty() && receipt.rejected.isEmpty())
    }

    @Test
    fun `ImportReceipt round-trips through encode after decode`() {
        val original = IntegrationsCodec.decodeImportReceipt(JSONObject(importReceiptJson))
        val reDecoded = IntegrationsCodec.decodeImportReceipt(IntegrationsCodec.encodeImportReceipt(original))
        assertEquals(original, reDecoded)
    }

    @Test
    fun `ImportErrorCode wire round-trip covers the fixed set and the IMPORT_INVALID_ FIELD family`() {
        assertEquals("IMPORT_UNSUPPORTED_MAJOR", IntegrationsCodec.encodeErrorCode(dev.aarso.contracts.integrations.ImportErrorCode.UnsupportedMajor))
        val invalidField = dev.aarso.contracts.integrations.ImportErrorCode.InvalidField("SEVERITY")
        assertEquals("IMPORT_INVALID_SEVERITY", IntegrationsCodec.encodeErrorCode(invalidField))
        assertEquals(invalidField, IntegrationsCodec.decodeErrorCode("IMPORT_INVALID_SEVERITY"))
    }
}
