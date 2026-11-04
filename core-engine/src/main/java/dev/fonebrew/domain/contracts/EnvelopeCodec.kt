// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.contracts

import dev.fonebrew.contracts.common.ArtifactRef
import dev.fonebrew.contracts.common.CapabilityManifest
import dev.fonebrew.contracts.common.CapabilitySubjectKind
import dev.fonebrew.contracts.common.CapabilityVersions
import dev.fonebrew.contracts.common.ConformanceTestClass
import dev.fonebrew.contracts.common.ConformanceTestClassCoverage
import dev.fonebrew.contracts.common.ConformanceSuite
import dev.fonebrew.contracts.common.ContractEnvelope
import dev.fonebrew.contracts.common.DataMigrationSteps
import dev.fonebrew.contracts.common.DigestAlgorithm
import dev.fonebrew.contracts.common.ErrorEnvelope
import dev.fonebrew.contracts.common.ErrorSeverity
import dev.fonebrew.contracts.common.IntegrityRef
import dev.fonebrew.contracts.common.JvmTestability
import dev.fonebrew.contracts.common.MigrationCompatibility
import dev.fonebrew.contracts.common.MigrationPlan
import dev.fonebrew.contracts.common.ProducerRef
import dev.fonebrew.contracts.common.ProducerReceiptRef
import dev.fonebrew.contracts.common.RollbackPlan
import dev.fonebrew.contracts.common.SideEffectState
import dev.fonebrew.contracts.common.StorageKind
import dev.fonebrew.contracts.common.StorageLocation
import dev.fonebrew.contracts.common.VerificationState
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * `org.json` encode/decode for every `dev.fonebrew.contracts.common` shape (WP-2 — "envelope
 * serialization... implement, not just declare"). Mirrors every schema file under
 * `schemas/common/` field-for-field, the same discipline `CommonContracts.kt`'s own header
 * comment states for the Kotlin/JSON-Schema pair.
 *
 * [ContractEnvelope] is generic, and `org.json` has no reflection-based (de)serialization, so its
 * codec takes a payload encoder/decoder pair — callers pass one of the other `encode*`/`decode*`
 * functions in this file (or their own, for a payload type this file does not own) as that pair.
 * Every codec here round-trips [unknown fields][ContractEnvelope.unknownFields] via
 * [extractUnknownFields]/[mergeUnknownFields] (`JsonInterop.kt`) — a decoder never silently drops
 * a field it does not recognize, and a re-encode never overwrites a field it does.
 */
object EnvelopeCodec {

    // -------------------------------------------------------------------------------------
    // ContractEnvelope<T>
    // -------------------------------------------------------------------------------------

    private val ENVELOPE_KNOWN_KEYS = setOf(
        "schemaVersion", "objectId", "createdAtUtc", "sourceTimezone", "sequence",
        "producer", "idempotencyKey", "integrity", "payload"
    )

    fun <T> encode(envelope: ContractEnvelope<T>, encodePayload: (T) -> JSONObject): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", envelope.schemaVersion)
        obj.put("objectId", envelope.objectId)
        obj.put("createdAtUtc", envelope.createdAtUtc.toString())
        envelope.sourceTimezone?.let { obj.put("sourceTimezone", it) }
        envelope.sequence?.let { obj.put("sequence", it) }
        obj.put("producer", encodeProducerRef(envelope.producer))
        envelope.idempotencyKey?.let { obj.put("idempotencyKey", it) }
        envelope.integrity?.let { obj.put("integrity", encodeIntegrityRef(it)) }
        obj.put("payload", encodePayload(envelope.payload))
        mergeUnknownFields(obj, envelope.unknownFields)
        return obj
    }

    fun <T> decode(json: JSONObject, decodePayload: (JSONObject) -> T): ContractEnvelope<T> =
        ContractEnvelope(
            schemaVersion = json.getString("schemaVersion"),
            objectId = json.getString("objectId"),
            createdAtUtc = Instant.parse(json.getString("createdAtUtc")),
            sourceTimezone = json.optStringOrNull("sourceTimezone"),
            sequence = json.optLongOrNull("sequence"),
            producer = decodeProducerRef(json.getJSONObject("producer")),
            idempotencyKey = json.optStringOrNull("idempotencyKey"),
            integrity = json.optJSONObjectOrNull("integrity")?.let(::decodeIntegrityRef),
            payload = decodePayload(json.getJSONObject("payload")),
            unknownFields = extractUnknownFields(json, ENVELOPE_KNOWN_KEYS)
        )

    // -------------------------------------------------------------------------------------
    // Shared sub-shapes
    // -------------------------------------------------------------------------------------

    fun encodeProducerRef(ref: ProducerRef): JSONObject = JSONObject().apply {
        put("name", ref.name)
        put("version", ref.version)
        ref.instanceId?.let { put("instanceId", it) }
    }

    fun decodeProducerRef(json: JSONObject): ProducerRef = ProducerRef(
        name = json.getString("name"),
        version = json.getString("version"),
        instanceId = json.optStringOrNull("instanceId")
    )

    fun encodeIntegrityRef(ref: IntegrityRef): JSONObject = JSONObject().apply {
        put("algorithm", ref.algorithm.wireValue)
        put("digestHex", ref.digestHex)
        put("byteLength", ref.byteLength)
    }

    fun decodeIntegrityRef(json: JSONObject): IntegrityRef = IntegrityRef(
        algorithm = DigestAlgorithm.fromWireValue(json.getString("algorithm")),
        digestHex = json.getString("digestHex"),
        byteLength = json.getLong("byteLength")
    )

    // -------------------------------------------------------------------------------------
    // ErrorEnvelope
    // -------------------------------------------------------------------------------------

    private val ERROR_KNOWN_KEYS = setOf(
        "code", "severity", "retryable", "sideEffectState", "detail", "recoveryAction",
        "userMessage", "objectId"
    )

    fun encodeErrorEnvelope(error: ErrorEnvelope): JSONObject {
        val obj = JSONObject()
        obj.put("code", error.code)
        obj.put("severity", error.severity.name)
        obj.put("retryable", error.retryable)
        obj.put("sideEffectState", error.sideEffectState.name)
        obj.put("detail", error.detail)
        obj.put("recoveryAction", error.recoveryAction)
        obj.put("userMessage", error.userMessage)
        error.objectId?.let { obj.put("objectId", it) }
        mergeUnknownFields(obj, error.unknownFields)
        return obj
    }

    fun decodeErrorEnvelope(json: JSONObject): ErrorEnvelope = ErrorEnvelope(
        code = json.getString("code"),
        severity = ErrorSeverity.valueOf(json.getString("severity")),
        retryable = json.getBoolean("retryable"),
        sideEffectState = SideEffectState.valueOf(json.getString("sideEffectState")),
        detail = json.getString("detail"),
        recoveryAction = json.getString("recoveryAction"),
        userMessage = json.getString("userMessage"),
        objectId = json.optStringOrNull("objectId"),
        unknownFields = extractUnknownFields(json, ERROR_KNOWN_KEYS)
    )

    // -------------------------------------------------------------------------------------
    // CapabilityManifest
    // -------------------------------------------------------------------------------------

    private val CAPABILITY_MANIFEST_KNOWN_KEYS = setOf(
        "manifestId", "subjectKind", "supportedOperations", "limits", "versions",
        "subjectId", "targetRequirements", "producer", "issuedAtUtc"
    )

    fun encodeCapabilityManifest(manifest: CapabilityManifest): JSONObject {
        val obj = JSONObject()
        obj.put("manifestId", manifest.manifestId)
        obj.put("subjectKind", manifest.subjectKind.name)
        obj.put("supportedOperations", JSONArray(manifest.supportedOperations))
        obj.put("limits", kotlinValueToJson(manifest.limits))
        obj.put("versions", JSONObject().apply {
            put("subjectVersion", manifest.versions.subjectVersion)
            manifest.versions.protocolVersion?.let { put("protocolVersion", it) }
        })
        manifest.subjectId?.let { obj.put("subjectId", it) }
        obj.put("targetRequirements", kotlinValueToJson(manifest.targetRequirements))
        manifest.producer?.let { obj.put("producer", encodeProducerRef(it)) }
        manifest.issuedAtUtc?.let { obj.put("issuedAtUtc", it.toString()) }
        mergeUnknownFields(obj, manifest.unknownFields)
        return obj
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeCapabilityManifest(json: JSONObject): CapabilityManifest {
        val versionsJson = json.getJSONObject("versions")
        return CapabilityManifest(
            manifestId = json.getString("manifestId"),
            subjectKind = CapabilitySubjectKind.valueOf(json.getString("subjectKind")),
            supportedOperations = json.getJSONArray("supportedOperations").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            },
            limits = jsonValueToKotlin(json.getJSONObject("limits")) as Map<String, Any?>,
            versions = CapabilityVersions(
                subjectVersion = versionsJson.getString("subjectVersion"),
                protocolVersion = versionsJson.optStringOrNull("protocolVersion")
            ),
            subjectId = json.optStringOrNull("subjectId"),
            targetRequirements = json.optJSONObjectOrNull("targetRequirements")
                ?.let { jsonValueToKotlin(it) as Map<String, Any?> } ?: emptyMap(),
            producer = json.optJSONObjectOrNull("producer")?.let(::decodeProducerRef),
            issuedAtUtc = json.optStringOrNull("issuedAtUtc")?.let(Instant::parse),
            unknownFields = extractUnknownFields(json, CAPABILITY_MANIFEST_KNOWN_KEYS)
        )
    }

    // -------------------------------------------------------------------------------------
    // ArtifactRef
    // -------------------------------------------------------------------------------------

    private val ARTIFACT_REF_KNOWN_KEYS = setOf(
        "id", "mediaType", "digest", "sizeBytes", "storageLocation", "verificationState", "producerReceipt"
    )

    fun encodeArtifactRef(ref: ArtifactRef): JSONObject {
        val obj = JSONObject()
        obj.put("id", ref.id)
        obj.put("mediaType", ref.mediaType)
        obj.put("digest", encodeIntegrityRef(ref.digest))
        obj.put("sizeBytes", ref.sizeBytes)
        obj.put("storageLocation", JSONObject().apply {
            put("kind", ref.storageLocation.kind.name)
            put("locator", ref.storageLocation.locator)
        })
        obj.put("verificationState", ref.verificationState.name)
        ref.producerReceipt?.let { obj.put("producerReceipt", encodeProducerReceiptRef(it)) }
        mergeUnknownFields(obj, ref.unknownFields)
        return obj
    }

    fun decodeArtifactRef(json: JSONObject): ArtifactRef {
        val storageJson = json.getJSONObject("storageLocation")
        return ArtifactRef(
            id = json.getString("id"),
            mediaType = json.getString("mediaType"),
            digest = decodeIntegrityRef(json.getJSONObject("digest")),
            sizeBytes = json.getLong("sizeBytes"),
            storageLocation = StorageLocation(
                kind = StorageKind.valueOf(storageJson.getString("kind")),
                locator = storageJson.getString("locator")
            ),
            verificationState = VerificationState.valueOf(json.getString("verificationState")),
            producerReceipt = json.optJSONObjectOrNull("producerReceipt")?.let(::decodeProducerReceiptRef),
            unknownFields = extractUnknownFields(json, ARTIFACT_REF_KNOWN_KEYS)
        )
    }

    private fun encodeProducerReceiptRef(ref: ProducerReceiptRef): JSONObject = JSONObject().apply {
        put("receiptObjectId", ref.receiptObjectId)
        put("producer", encodeProducerRef(ref.producer))
        put("sourceLocation", ref.sourceLocation)
        put("projectRevision", ref.projectRevision)
        put("initiatingPrincipal", ref.initiatingPrincipal)
        put("evidenceLinks", JSONArray(ref.evidenceLinks))
    }

    private fun decodeProducerReceiptRef(json: JSONObject): ProducerReceiptRef = ProducerReceiptRef(
        receiptObjectId = json.getString("receiptObjectId"),
        producer = decodeProducerRef(json.getJSONObject("producer")),
        sourceLocation = json.getString("sourceLocation"),
        projectRevision = json.getString("projectRevision"),
        initiatingPrincipal = json.getString("initiatingPrincipal"),
        evidenceLinks = json.optJSONArray("evidenceLinks")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
    )

    // -------------------------------------------------------------------------------------
    // MigrationPlan
    // -------------------------------------------------------------------------------------

    private val MIGRATION_PLAN_KNOWN_KEYS = setOf(
        "migrationId", "fromSchemaVersion", "toSchemaVersion", "compatibility",
        "dataMigration", "rollback", "contractRef", "issuedAtUtc"
    )

    fun encodeMigrationPlan(plan: MigrationPlan): JSONObject {
        val obj = JSONObject()
        obj.put("migrationId", plan.migrationId)
        obj.put("fromSchemaVersion", plan.fromSchemaVersion)
        obj.put("toSchemaVersion", plan.toSchemaVersion)
        obj.put("compatibility", plan.compatibility.name)
        obj.put("dataMigration", JSONObject().apply {
            put("steps", JSONArray(plan.dataMigration.steps))
            put("reversible", plan.dataMigration.reversible)
        })
        obj.put("rollback", JSONObject().apply {
            put("possible", plan.rollback.possible)
            put("steps", JSONArray(plan.rollback.steps))
        })
        plan.contractRef?.let { obj.put("contractRef", it) }
        plan.issuedAtUtc?.let { obj.put("issuedAtUtc", it.toString()) }
        mergeUnknownFields(obj, plan.unknownFields)
        return obj
    }

    fun decodeMigrationPlan(json: JSONObject): MigrationPlan {
        val dataMigrationJson = json.getJSONObject("dataMigration")
        val rollbackJson = json.getJSONObject("rollback")
        return MigrationPlan(
            migrationId = json.getString("migrationId"),
            fromSchemaVersion = json.getString("fromSchemaVersion"),
            toSchemaVersion = json.getString("toSchemaVersion"),
            compatibility = MigrationCompatibility.valueOf(json.getString("compatibility")),
            dataMigration = DataMigrationSteps(
                steps = dataMigrationJson.getJSONArray("steps").let { arr -> (0 until arr.length()).map { arr.getString(it) } },
                reversible = dataMigrationJson.optBoolean("reversible", false)
            ),
            rollback = RollbackPlan(
                possible = rollbackJson.getBoolean("possible"),
                steps = rollbackJson.getJSONArray("steps").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            ),
            contractRef = json.optStringOrNull("contractRef"),
            issuedAtUtc = json.optStringOrNull("issuedAtUtc")?.let(Instant::parse),
            unknownFields = extractUnknownFields(json, MIGRATION_PLAN_KNOWN_KEYS)
        )
    }

    // -------------------------------------------------------------------------------------
    // ConformanceSuite
    // -------------------------------------------------------------------------------------

    private val CONFORMANCE_SUITE_KNOWN_KEYS = setOf(
        "suiteId", "name", "contractRef", "testClasses", "fixtureDirectoryRef", "definitionOfReady"
    )

    fun encodeConformanceSuite(suite: ConformanceSuite): JSONObject {
        val obj = JSONObject()
        obj.put("suiteId", suite.suiteId)
        obj.put("name", suite.name)
        obj.put("contractRef", suite.contractRef)
        obj.put("testClasses", JSONArray(suite.testClasses.map { coverage ->
            JSONObject().apply {
                put("testClass", coverage.testClass.name)
                put("jvmTestable", coverage.jvmTestable.name)
                coverage.notes?.let { put("notes", it) }
            }
        }))
        obj.put("fixtureDirectoryRef", suite.fixtureDirectoryRef)
        obj.put("definitionOfReady", suite.definitionOfReady)
        mergeUnknownFields(obj, suite.unknownFields)
        return obj
    }

    fun decodeConformanceSuite(json: JSONObject): ConformanceSuite {
        val testClassesJson = json.getJSONArray("testClasses")
        return ConformanceSuite(
            suiteId = json.getString("suiteId"),
            name = json.getString("name"),
            contractRef = json.getString("contractRef"),
            testClasses = (0 until testClassesJson.length()).map { i ->
                val entry = testClassesJson.getJSONObject(i)
                ConformanceTestClassCoverage(
                    testClass = ConformanceTestClass.valueOf(entry.getString("testClass")),
                    jvmTestable = JvmTestability.valueOf(entry.getString("jvmTestable")),
                    notes = entry.optStringOrNull("notes")
                )
            },
            fixtureDirectoryRef = json.getString("fixtureDirectoryRef"),
            definitionOfReady = json.optBoolean("definitionOfReady", false),
            unknownFields = extractUnknownFields(json, CONFORMANCE_SUITE_KNOWN_KEYS)
        )
    }
}
