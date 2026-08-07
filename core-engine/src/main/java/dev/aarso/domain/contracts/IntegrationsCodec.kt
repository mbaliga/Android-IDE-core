// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.aarso.domain.contracts

import dev.aarso.contracts.integrations.AssayCompleteness
import dev.aarso.contracts.integrations.AssayIndex
import dev.aarso.contracts.integrations.AssayProjectRef
import dev.aarso.contracts.integrations.AssayToolRef
import dev.aarso.contracts.integrations.CsAppIssue
import dev.aarso.contracts.integrations.CsAppProducerRef
import dev.aarso.contracts.integrations.CsAppProjectRef
import dev.aarso.contracts.integrations.FindingFileRef
import dev.aarso.contracts.integrations.ImportDecision
import dev.aarso.contracts.integrations.ImportErrorCode
import dev.aarso.contracts.integrations.ImportPreview
import dev.aarso.contracts.integrations.ImportReceipt
import dev.aarso.contracts.integrations.ImportSourceType
import dev.aarso.contracts.integrations.IssueSeverity
import dev.aarso.contracts.integrations.IssuesManifest
import dev.aarso.contracts.integrations.PreviewChangedRecord
import dev.aarso.contracts.integrations.PreviewConflictingRecord
import dev.aarso.contracts.integrations.PreviewDuplicateRecord
import dev.aarso.contracts.integrations.PreviewNewRecord
import dev.aarso.contracts.integrations.PreviewRecordKind
import dev.aarso.contracts.integrations.PreviewRejectedRecord
import dev.aarso.contracts.integrations.ProvingTestEntry
import dev.aarso.contracts.integrations.ProvingTestKind
import dev.aarso.contracts.integrations.ProvingTestStatus
import dev.aarso.contracts.integrations.ProvingTests
import dev.aarso.contracts.integrations.ProvingTestsSummary
import dev.aarso.contracts.integrations.ReceiptCreatedEntry
import dev.aarso.contracts.integrations.ReceiptDuplicateEntry
import dev.aarso.contracts.integrations.ReceiptRejectedEntry
import dev.aarso.contracts.integrations.ReceiptUpdatedEntry
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * WP-7: encode/decode for every `dev.aarso.contracts.integrations` wire shape (§ the R1 "freeze
 * the common grammar" release step, `docs/ratified/MANUAL_INTEGRATION_GRAMMAR.md` §9). Same
 * unknown-field-preserving pattern as [EnvelopeCodec]/[WorkspaceCodec] for the shapes that carry
 * `unknownFields`; `IssuesManifest`/`AssayIndex`/`ProvingTests`/`ImportPreview`/`ImportReceipt` are
 * all "self-contained record, not wrapped in a `ContractEnvelope`" per their own doc comments —
 * this codec still preserves their own `unknownFields` maps, it just doesn't nest them under an
 * envelope the way `EnvelopeCodec` does for `dev.aarso.contracts.common` shapes.
 */
object IntegrationsCodec {

    // -------------------------------------------------------------------------------------
    // IssuesManifest (CSApp file lane)
    // -------------------------------------------------------------------------------------

    private val MANIFEST_KNOWN_KEYS = setOf("schemaVersion", "exportId", "exportedAt", "producer", "projectRef", "issues")

    fun decodeIssuesManifest(json: JSONObject): IssuesManifest {
        val producerJson = json.getJSONObject("producer")
        val issuesJson = json.getJSONArray("issues")
        return IssuesManifest(
            schemaVersion = json.getString("schemaVersion"),
            exportId = json.getString("exportId"),
            exportedAt = Instant.parse(json.getString("exportedAt")),
            producer = CsAppProducerRef(producerJson.getString("app"), producerJson.getString("version")),
            projectRef = CsAppProjectRef(json.getJSONObject("projectRef").getString("externalId")),
            issues = (0 until issuesJson.length()).map { decodeCsAppIssue(issuesJson.getJSONObject(it)) },
            unknownFields = extractUnknownFields(json, MANIFEST_KNOWN_KEYS)
        )
    }

    private fun decodeCsAppIssue(json: JSONObject): CsAppIssue = CsAppIssue(
        id = json.getString("id"),
        title = json.getString("title"),
        detail = json.getString("detail"),
        severity = IssueSeverity.valueOf(json.getString("severity")),
        reporterRef = json.getString("reporterRef"),
        occurredAt = Instant.parse(json.getString("occurredAt")),
        updatedAt = Instant.parse(json.getString("updatedAt")),
        sourceRevision = json.optStringOrNull("sourceRevision"),
        status = json.optStringOrNull("status")
    )

    fun encodeIssuesManifest(manifest: IssuesManifest): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", manifest.schemaVersion)
        obj.put("exportId", manifest.exportId)
        obj.put("exportedAt", manifest.exportedAt.toString())
        obj.put("producer", JSONObject().apply { put("app", manifest.producer.app); put("version", manifest.producer.version) })
        obj.put("projectRef", JSONObject().apply { put("externalId", manifest.projectRef.externalId) })
        obj.put("issues", JSONArray().apply {
            manifest.issues.forEach { issue ->
                put(JSONObject().apply {
                    put("id", issue.id); put("title", issue.title); put("detail", issue.detail)
                    put("severity", issue.severity.name); put("reporterRef", issue.reporterRef)
                    put("occurredAt", issue.occurredAt.toString()); put("updatedAt", issue.updatedAt.toString())
                    put("sourceRevision", issue.sourceRevision); put("status", issue.status)
                })
            }
        })
        mergeUnknownFields(obj, manifest.unknownFields)
        return obj
    }

    // -------------------------------------------------------------------------------------
    // AssayIndex + ProvingTests (Assay repo lane)
    // -------------------------------------------------------------------------------------

    private val ASSAY_INDEX_KNOWN_KEYS = setOf(
        "schemaVersion", "runId", "projectRef", "sourceCommit", "assayCommit", "tool", "startedAt",
        "finishedAt", "findingFiles", "provingTests", "completeness", "explanation"
    )

    fun decodeAssayIndex(json: JSONObject): AssayIndex {
        val projectRefJson = json.getJSONObject("projectRef")
        val toolJson = json.getJSONObject("tool")
        val findingFilesJson = json.getJSONArray("findingFiles")
        val provingTestsJson = json.getJSONObject("provingTests")
        return AssayIndex(
            schemaVersion = json.getString("schemaVersion"),
            runId = json.getString("runId"),
            projectRef = AssayProjectRef(projectRefJson.getString("gitRemote"), projectRefJson.optStringOrNull("fonebrewProjectHint")),
            sourceCommit = json.getString("sourceCommit"),
            assayCommit = json.getString("assayCommit"),
            tool = AssayToolRef(toolJson.getString("name"), toolJson.getString("version")),
            startedAt = Instant.parse(json.getString("startedAt")),
            finishedAt = Instant.parse(json.getString("finishedAt")),
            findingFiles = (0 until findingFilesJson.length()).map {
                val f = findingFilesJson.getJSONObject(it)
                FindingFileRef(f.getString("path"), f.getString("sha256"), f.getInt("count"))
            },
            provingTests = ProvingTestsSummary(provingTestsJson.getString("path"), provingTestsJson.getInt("count")),
            completeness = AssayCompleteness.valueOf(json.getString("completeness")),
            explanation = json.optStringOrNull("explanation"),
            unknownFields = extractUnknownFields(json, ASSAY_INDEX_KNOWN_KEYS)
        )
    }

    fun encodeAssayIndex(index: AssayIndex): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", index.schemaVersion)
        obj.put("runId", index.runId)
        obj.put("projectRef", JSONObject().apply {
            put("gitRemote", index.projectRef.gitRemote); put("fonebrewProjectHint", index.projectRef.fonebrewProjectHint)
        })
        obj.put("sourceCommit", index.sourceCommit)
        obj.put("assayCommit", index.assayCommit)
        obj.put("tool", JSONObject().apply { put("name", index.tool.name); put("version", index.tool.version) })
        obj.put("startedAt", index.startedAt.toString())
        obj.put("finishedAt", index.finishedAt.toString())
        obj.put("findingFiles", JSONArray().apply {
            index.findingFiles.forEach { put(JSONObject().apply { put("path", it.path); put("sha256", it.sha256); put("count", it.count) }) }
        })
        obj.put("provingTests", JSONObject().apply { put("path", index.provingTests.path); put("count", index.provingTests.count) })
        obj.put("completeness", index.completeness.name)
        obj.put("explanation", index.explanation)
        mergeUnknownFields(obj, index.unknownFields)
        return obj
    }

    private val PROVING_TESTS_KNOWN_KEYS = setOf("schemaVersion", "runId", "generatedAt", "tests")

    fun decodeProvingTests(json: JSONObject): ProvingTests {
        val testsJson = json.getJSONArray("tests")
        return ProvingTests(
            schemaVersion = json.getString("schemaVersion"),
            runId = json.getString("runId"),
            generatedAt = Instant.parse(json.getString("generatedAt")),
            tests = (0 until testsJson.length()).map { decodeProvingTestEntry(testsJson.getJSONObject(it)) },
            unknownFields = extractUnknownFields(json, PROVING_TESTS_KNOWN_KEYS)
        )
    }

    private fun decodeProvingTestEntry(json: JSONObject): ProvingTestEntry = ProvingTestEntry(
        testId = json.getString("testId"),
        targetFindingRef = json.getString("targetFindingRef"),
        testKind = ProvingTestKind.valueOf(json.getString("testKind")),
        sourcePath = json.getString("sourcePath"),
        status = ProvingTestStatus.valueOf(json.getString("status")),
        description = json.optStringOrNull("description"),
        lastRunAtUtc = json.optStringOrNull("lastRunAtUtc")?.let { Instant.parse(it) }
    )

    fun encodeProvingTests(tests: ProvingTests): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", tests.schemaVersion)
        obj.put("runId", tests.runId)
        obj.put("generatedAt", tests.generatedAt.toString())
        obj.put("tests", JSONArray().apply {
            tests.tests.forEach { t ->
                put(JSONObject().apply {
                    put("testId", t.testId); put("targetFindingRef", t.targetFindingRef); put("testKind", t.testKind.name)
                    put("sourcePath", t.sourcePath); put("status", t.status.name)
                    put("description", t.description); put("lastRunAtUtc", t.lastRunAtUtc?.toString())
                })
            }
        })
        mergeUnknownFields(obj, tests.unknownFields)
        return obj
    }

    // -------------------------------------------------------------------------------------
    // ImportErrorCode
    // -------------------------------------------------------------------------------------

    fun encodeErrorCode(code: ImportErrorCode): String = code.wireCode
    fun decodeErrorCode(wire: String): ImportErrorCode = ImportErrorCode.fromWireCode(wire)

    // -------------------------------------------------------------------------------------
    // ImportPreview
    // -------------------------------------------------------------------------------------

    private val IMPORT_PREVIEW_KNOWN_KEYS = setOf(
        "schemaVersion", "previewId", "sourceType", "sourceDigest", "generatedAt", "newRecords",
        "changedRecords", "duplicateRecords", "conflictingRecords", "rejectedRecords", "sourceLocation"
    )

    fun decodeImportPreview(json: JSONObject): ImportPreview = ImportPreview(
        schemaVersion = json.getString("schemaVersion"),
        previewId = json.getString("previewId"),
        sourceType = ImportSourceType.valueOf(json.getString("sourceType")),
        sourceDigest = EnvelopeCodec.decodeIntegrityRef(json.getJSONObject("sourceDigest")),
        generatedAt = Instant.parse(json.getString("generatedAt")),
        newRecords = json.getJSONArray("newRecords").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).let { r -> PreviewNewRecord(r.getString("sourceId"), PreviewRecordKind.valueOf(r.getString("recordKind")), r.getString("summary")) } }
        },
        changedRecords = json.getJSONArray("changedRecords").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).let { r ->
                PreviewChangedRecord(r.getString("sourceId"), r.getString("existingIncidentId"), jsonArrayToStringList(r.getJSONArray("changedFields")), r.getString("summary"))
            } }
        },
        duplicateRecords = json.getJSONArray("duplicateRecords").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).let { r ->
                PreviewDuplicateRecord(r.getString("sourceId"), r.getString("priorReceiptId"), r.optStringOrNull("priorImportedAtUtc")?.let { Instant.parse(it) })
            } }
        },
        conflictingRecords = json.getJSONArray("conflictingRecords").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).let { r ->
                PreviewConflictingRecord(r.getString("sourceId"), jsonArrayToStringList(r.getJSONArray("conflictingFields")), r.getString("summary"), r.optStringOrNull("existingIncidentId"))
            } }
        },
        rejectedRecords = json.getJSONArray("rejectedRecords").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).let { r ->
                PreviewRejectedRecord(r.getString("sourceLocation"), decodeErrorCode(r.getString("errorCode")), r.optStringOrNull("detail"))
            } }
        },
        sourceLocation = json.optStringOrNull("sourceLocation"),
        unknownFields = extractUnknownFields(json, IMPORT_PREVIEW_KNOWN_KEYS)
    )

    fun encodeImportPreview(preview: ImportPreview): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", preview.schemaVersion)
        obj.put("previewId", preview.previewId)
        obj.put("sourceType", preview.sourceType.name)
        obj.put("sourceDigest", EnvelopeCodec.encodeIntegrityRef(preview.sourceDigest))
        obj.put("generatedAt", preview.generatedAt.toString())
        obj.put("newRecords", JSONArray().apply { preview.newRecords.forEach { put(JSONObject().apply { put("sourceId", it.sourceId); put("recordKind", it.recordKind.name); put("summary", it.summary) }) } })
        obj.put("changedRecords", JSONArray().apply { preview.changedRecords.forEach { put(JSONObject().apply { put("sourceId", it.sourceId); put("existingIncidentId", it.existingIncidentId); put("changedFields", JSONArray(it.changedFields)); put("summary", it.summary) }) } })
        obj.put("duplicateRecords", JSONArray().apply { preview.duplicateRecords.forEach { put(JSONObject().apply { put("sourceId", it.sourceId); put("priorReceiptId", it.priorReceiptId); put("priorImportedAtUtc", it.priorImportedAtUtc?.toString()) }) } })
        obj.put("conflictingRecords", JSONArray().apply { preview.conflictingRecords.forEach { put(JSONObject().apply { put("sourceId", it.sourceId); put("conflictingFields", JSONArray(it.conflictingFields)); put("summary", it.summary); put("existingIncidentId", it.existingIncidentId) }) } })
        obj.put("rejectedRecords", JSONArray().apply { preview.rejectedRecords.forEach { put(JSONObject().apply { put("sourceLocation", it.sourceLocation); put("errorCode", encodeErrorCode(it.errorCode)); put("detail", it.detail) }) } })
        obj.put("sourceLocation", preview.sourceLocation)
        mergeUnknownFields(obj, preview.unknownFields)
        return obj
    }

    // -------------------------------------------------------------------------------------
    // ImportReceipt
    // -------------------------------------------------------------------------------------

    private val IMPORT_RECEIPT_KNOWN_KEYS = setOf(
        "schemaVersion", "receiptId", "sourceType", "sourceDigest", "initiatedBy", "decision",
        "createdAtUtc", "created", "updated", "duplicates", "rejected", "sourceLocation", "previewId"
    )

    fun decodeImportReceipt(json: JSONObject): ImportReceipt = ImportReceipt(
        schemaVersion = json.getString("schemaVersion"),
        receiptId = json.getString("receiptId"),
        sourceType = ImportSourceType.valueOf(json.getString("sourceType")),
        sourceDigest = EnvelopeCodec.decodeIntegrityRef(json.getJSONObject("sourceDigest")),
        initiatedBy = json.getString("initiatedBy"),
        decision = ImportDecision.valueOf(json.getString("decision")),
        createdAtUtc = Instant.parse(json.getString("createdAtUtc")),
        created = json.getJSONArray("created").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).let { r -> ReceiptCreatedEntry(r.getString("sourceId"), r.getString("incidentId")) } } },
        updated = json.getJSONArray("updated").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).let { r -> ReceiptUpdatedEntry(r.getString("sourceId"), r.getString("incidentId"), jsonArrayToStringList(r.optJSONArray("changedFields") ?: JSONArray())) } } },
        duplicates = json.getJSONArray("duplicates").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).let { r -> ReceiptDuplicateEntry(r.getString("sourceId"), r.getString("priorReceiptId")) } } },
        rejected = json.getJSONArray("rejected").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).let { r -> ReceiptRejectedEntry(r.getString("sourceLocation"), decodeErrorCode(r.getString("errorCode")), r.optStringOrNull("detail")) } } },
        sourceLocation = json.optStringOrNull("sourceLocation"),
        previewId = json.optStringOrNull("previewId"),
        unknownFields = extractUnknownFields(json, IMPORT_RECEIPT_KNOWN_KEYS)
    )

    fun encodeImportReceipt(receipt: ImportReceipt): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", receipt.schemaVersion)
        obj.put("receiptId", receipt.receiptId)
        obj.put("sourceType", receipt.sourceType.name)
        obj.put("sourceDigest", EnvelopeCodec.encodeIntegrityRef(receipt.sourceDigest))
        obj.put("initiatedBy", receipt.initiatedBy)
        obj.put("decision", receipt.decision.name)
        obj.put("createdAtUtc", receipt.createdAtUtc.toString())
        obj.put("created", JSONArray().apply { receipt.created.forEach { put(JSONObject().apply { put("sourceId", it.sourceId); put("incidentId", it.incidentId) }) } })
        obj.put("updated", JSONArray().apply { receipt.updated.forEach { put(JSONObject().apply { put("sourceId", it.sourceId); put("incidentId", it.incidentId); put("changedFields", JSONArray(it.changedFields)) }) } })
        obj.put("duplicates", JSONArray().apply { receipt.duplicates.forEach { put(JSONObject().apply { put("sourceId", it.sourceId); put("priorReceiptId", it.priorReceiptId) }) } })
        obj.put("rejected", JSONArray().apply { receipt.rejected.forEach { put(JSONObject().apply { put("sourceLocation", it.sourceLocation); put("errorCode", encodeErrorCode(it.errorCode)); put("detail", it.detail) }) } })
        obj.put("sourceLocation", receipt.sourceLocation)
        obj.put("previewId", receipt.previewId)
        mergeUnknownFields(obj, receipt.unknownFields)
        return obj
    }

    private fun jsonArrayToStringList(arr: JSONArray): List<String> = (0 until arr.length()).map { arr.getString(it) }
}
