package dev.aarso.domain.contracts

import dev.aarso.contracts.common.ArtifactRef
import dev.aarso.contracts.common.CapabilityManifest
import dev.aarso.contracts.common.CapabilitySubjectKind
import dev.aarso.contracts.common.CapabilityVersions
import dev.aarso.contracts.common.ConformanceSuite
import dev.aarso.contracts.common.ConformanceTestClass
import dev.aarso.contracts.common.ConformanceTestClassCoverage
import dev.aarso.contracts.common.ContractEnvelope
import dev.aarso.contracts.common.DataMigrationSteps
import dev.aarso.contracts.common.ErrorEnvelope
import dev.aarso.contracts.common.ErrorSeverity
import dev.aarso.contracts.common.JvmTestability
import dev.aarso.contracts.common.MigrationCompatibility
import dev.aarso.contracts.common.MigrationPlan
import dev.aarso.contracts.common.ProducerRef
import dev.aarso.contracts.common.RollbackPlan
import dev.aarso.contracts.common.SideEffectState
import dev.aarso.contracts.common.StorageKind
import dev.aarso.contracts.common.StorageLocation
import dev.aarso.contracts.common.VerificationState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Round-trip tests for every `EnvelopeCodec` encode/decode pair. The
 * `` `ContractEnvelope round-trip preserves an unknown field end to end` `` test below is the
 * real, runtime proof `docs/WP1_GATE_REPORT.md` §6 and `docs/WP1L_GATE_REPORT.md` §7 both named as
 * genuinely missing (a successful compile shows the shapes are sound, not that a real
 * encode-decode-re-encode cycle preserves an `unknownFields` map) — this is that proof.
 */
class EnvelopeCodecTest {

    private fun errorPayload() = ErrorEnvelope(
        code = "GIT_AUTH_EXPIRED",
        severity = ErrorSeverity.ERROR,
        retryable = true,
        sideEffectState = SideEffectState.NONE,
        detail = "401 from the Git host",
        recoveryAction = "Re-authenticate in Settings",
        userMessage = "Your Git connection needs to be renewed."
    )

    private fun envelope(payload: ErrorEnvelope = errorPayload()) = ContractEnvelope(
        schemaVersion = "1.0.0",
        objectId = "err_" + IdGenerator.generate(),
        createdAtUtc = Instant.parse("2026-08-07T12:00:00Z"),
        producer = ProducerRef(name = "core-engine", version = "1.0.0"),
        payload = payload
    )

    @Test
    fun `ContractEnvelope round-trips through encode and decode`() {
        val original = envelope()
        val json = EnvelopeCodec.encode(original, EnvelopeCodec::encodeErrorEnvelope)
        val decoded = EnvelopeCodec.decode(json, EnvelopeCodec::decodeErrorEnvelope)
        assertEquals(original, decoded)
    }

    @Test
    fun `ContractEnvelope round-trip preserves an unknown field end to end`() {
        val original = envelope()
        val json = EnvelopeCodec.encode(original, EnvelopeCodec::encodeErrorEnvelope)
        // Simulate a NEWER minor schema version adding a field this decoder does not know about.
        json.put("futureField", "a value from a newer minor version")
        json.put("futureNested", JSONObject().apply { put("inner", 42) })

        val decoded = EnvelopeCodec.decode(json, EnvelopeCodec::decodeErrorEnvelope)
        assertEquals("a value from a newer minor version", decoded.unknownFields["futureField"])
        @Suppress("UNCHECKED_CAST")
        val nested = decoded.unknownFields["futureNested"] as Map<String, Any?>
        assertEquals(42, nested["inner"])

        // Re-encoding MUST NOT silently drop the unknown fields.
        val reEncoded = EnvelopeCodec.encode(decoded, EnvelopeCodec::encodeErrorEnvelope)
        assertEquals("a value from a newer minor version", reEncoded.getString("futureField"))
        assertEquals(42, reEncoded.getJSONObject("futureNested").getInt("inner"))
    }

    @Test
    fun `unknown fields never override a field the decoder does recognize on re-encode`() {
        val original = envelope()
        val decoded = original.copy(unknownFields = mapOf("code" to "SHOULD_NOT_WIN"))
        val reEncoded = EnvelopeCodec.encode(decoded, EnvelopeCodec::encodeErrorEnvelope)
        // The known field (from the payload, written first) wins over the same-named "unknown" one.
        assertEquals("GIT_AUTH_EXPIRED", reEncoded.getJSONObject("payload").getString("code"))
    }

    @Test
    fun `ContractEnvelope with all optional fields populated round-trips`() {
        val original = envelope().copy(
            sourceTimezone = "Asia/Kolkata",
            sequence = 42L,
            idempotencyKey = "idem-123",
            integrity = Digest.ofUtf8("payload bytes")
        )
        val decoded = EnvelopeCodec.decode(
            EnvelopeCodec.encode(original, EnvelopeCodec::encodeErrorEnvelope),
            EnvelopeCodec::decodeErrorEnvelope
        )
        assertEquals(original, decoded)
    }

    @Test
    fun `ArtifactRef round-trips including sizeBytes-digest agreement`() {
        val digest = Digest.ofUtf8("file contents")
        val original = ArtifactRef(
            id = "art_" + IdGenerator.generate(),
            mediaType = "application/json",
            digest = digest,
            sizeBytes = digest.byteLength,
            storageLocation = StorageLocation(StorageKind.LOCAL_FS, "/data/artifacts/x.json"),
            verificationState = VerificationState.VERIFIED
        )
        val decoded = EnvelopeCodec.decodeArtifactRef(EnvelopeCodec.encodeArtifactRef(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `CapabilityManifest round-trips open maps (limits, targetRequirements)`() {
        val original = CapabilityManifest(
            manifestId = "cap_" + IdGenerator.generate(),
            subjectKind = CapabilitySubjectKind.EXECUTION,
            supportedOperations = listOf("read", "write"),
            limits = mapOf("maxConcurrent" to 4L, "timeoutMs" to 30000L),
            versions = CapabilityVersions(subjectVersion = "2.1.0", protocolVersion = "1")
        )
        val decoded = EnvelopeCodec.decodeCapabilityManifest(EnvelopeCodec.encodeCapabilityManifest(original))
        assertEquals(original.manifestId, decoded.manifestId)
        assertEquals(original.limits["maxConcurrent"], (decoded.limits["maxConcurrent"] as Number).toLong())
        assertEquals(original.supportedOperations, decoded.supportedOperations)
    }

    @Test
    fun `MigrationPlan round-trips including the rollback possible-steps agreement`() {
        val original = MigrationPlan(
            migrationId = "mig_" + IdGenerator.generate(),
            fromSchemaVersion = "1.0.0",
            toSchemaVersion = "2.0.0",
            compatibility = MigrationCompatibility.MAJOR,
            dataMigration = DataMigrationSteps(steps = listOf("addColumn", "backfill"), reversible = true),
            rollback = RollbackPlan(possible = true, steps = listOf("dropColumn"))
        )
        val decoded = EnvelopeCodec.decodeMigrationPlan(EnvelopeCodec.encodeMigrationPlan(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `ConformanceSuite round-trips its testClasses list`() {
        val original = ConformanceSuite(
            suiteId = "suite_" + IdGenerator.generate(),
            name = "Envelope conformance",
            contractRef = "docs/ratified/COMMON_CONVENTIONS.md",
            testClasses = listOf(
                ConformanceTestClassCoverage(ConformanceTestClass.GOLDEN_SERIALIZATION, JvmTestability.YES),
                ConformanceTestClassCoverage(ConformanceTestClass.ADVERSARIAL, JvmTestability.YES, notes = "see fixtures/common/adversarial"),
            ),
            fixtureDirectoryRef = "fixtures/common/",
            definitionOfReady = true
        )
        val decoded = EnvelopeCodec.decodeConformanceSuite(EnvelopeCodec.encodeConformanceSuite(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `decode rejects an envelope with an unsupported major schemaVersion before construction`() {
        val json = EnvelopeCodec.encode(envelope(), EnvelopeCodec::encodeErrorEnvelope)
        json.put("schemaVersion", "2.0.0")
        try {
            EnvelopeCodec.decode(json, EnvelopeCodec::decodeErrorEnvelope)
            org.junit.Assert.fail("expected IllegalArgumentException for an unsupported major schemaVersion")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("major version 1"))
        }
    }
}
