package dev.fonebrew.domain.object3d

import dev.fonebrew.contracts.common.ErrorEnvelope
import dev.fonebrew.contracts.common.ErrorSeverity
import dev.fonebrew.contracts.common.SideEffectState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Coverage for [Object3dJob] (structural `init` invariants), [Object3dJobMachine] (the pure
 * QUEUED->RUNNING->DOWNLOADING->DONE/FAILED transition law, §5/§6), and [Object3dJobCodec]
 * (`org.json` round trip) — plus the REAL valid/invalid/adversarial `object3d-job-...json`
 * fixtures under fixtures/object3d/ embedded verbatim and decoded, mirroring
 * `dev.fonebrew.domain.thread.ThreadCodecTest`'s fixture-embedding discipline.
 */
class Object3dJobTest {

    private fun textJob(
        state: Object3dJobState = Object3dJobState.QUEUED,
        downloadUrl: String? = null,
        resultNodeId: String? = null,
        errorEnvelope: ErrorEnvelope? = null,
        progressPercent: Double? = null,
    ) = Object3dJob(
        jobId = "job-1",
        provider = Object3dProvider.MESHY,
        providerJobId = "msy_1",
        mode = Object3dJobMode.TEXT_TO_3D,
        prompt = "a teapot",
        state = state,
        downloadUrl = downloadUrl,
        resultNodeId = resultNodeId,
        errorEnvelope = errorEnvelope,
        progressPercent = progressPercent,
        createdAtUtc = Instant.parse("2026-08-15T13:00:00Z"),
        updatedAtUtc = Instant.parse("2026-08-15T13:05:00Z"),
    )

    private fun error() = ErrorEnvelope(
        code = "OBJECT3D_PROVIDER_FAILED",
        severity = ErrorSeverity.ERROR,
        retryable = true,
        sideEffectState = SideEffectState.NONE,
        detail = "provider returned a terminal failure status",
        recoveryAction = "retry generation, or try the other provider",
        userMessage = "Generation failed. You can try again.",
    )

    // ---- Object3dJob structural invariants -------------------------------------------------

    @Test fun `a well-formed QUEUED job constructs successfully`() {
        val job = textJob()
        assertEquals(Object3dJobState.QUEUED, job.state)
    }

    @Test fun `QUEUED with a downloadUrl is rejected`() {
        try {
            textJob(state = Object3dJobState.QUEUED, downloadUrl = "https://assets.meshy.ai/x.glb")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("QUEUED"))
        }
    }

    @Test fun `DOWNLOADING without a downloadUrl is rejected`() {
        try {
            textJob(state = Object3dJobState.DOWNLOADING)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("DOWNLOADING"))
        }
    }

    @Test fun `DONE without a resultNodeId is rejected`() {
        try {
            textJob(state = Object3dJobState.DONE, downloadUrl = "https://assets.meshy.ai/x.glb")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("resultNodeId"))
        }
    }

    @Test fun `DONE with an errorEnvelope is rejected`() {
        try {
            textJob(state = Object3dJobState.DONE, downloadUrl = "https://assets.meshy.ai/x.glb", resultNodeId = "node-1", errorEnvelope = error())
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("errorEnvelope"))
        }
    }

    @Test fun `FAILED without an errorEnvelope is rejected`() {
        try {
            textJob(state = Object3dJobState.FAILED)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("errorEnvelope"))
        }
    }

    @Test fun `a well-formed DONE job constructs successfully`() {
        val job = textJob(state = Object3dJobState.DONE, downloadUrl = "https://assets.meshy.ai/x.glb", resultNodeId = "node-1")
        assertEquals("node-1", job.resultNodeId)
    }

    @Test fun `IMAGE_TO_3D with a prompt is rejected`() {
        try {
            Object3dJob(
                jobId = "job-2", provider = Object3dProvider.TRIPO, providerJobId = "tp_1",
                mode = Object3dJobMode.IMAGE_TO_3D, prompt = "should not be here", sourceImageRef = "img-1",
                state = Object3dJobState.QUEUED,
                createdAtUtc = Instant.EPOCH, updatedAtUtc = Instant.EPOCH,
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("IMAGE_TO_3D"))
        }
    }

    @Test fun `TEXT_TO_3D with a sourceImageRef is rejected`() {
        try {
            textJob().copy(sourceImageRef = "img-1")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("TEXT_TO_3D"))
        }
    }

    @Test fun `updatedAtUtc before createdAtUtc is rejected`() {
        try {
            textJob().copy(updatedAtUtc = Instant.parse("2026-08-15T12:00:00Z"))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("updatedAtUtc"))
        }
    }

    // ---- Object3dJobMachine: the legal-transition law --------------------------------------

    @Test fun `start begins at QUEUED`() {
        assertEquals(Object3dJobState.QUEUED, Object3dJobMachine.start())
    }

    @Test fun `the full happy path QUEUED to RUNNING to DOWNLOADING to DONE is legal`() {
        var state = Object3dJobMachine.start()
        state = (Object3dJobMachine.apply(state, Object3dJobMachine.Event.StartRunning) as Object3dJobMachine.Result.Advanced).state
        assertEquals(Object3dJobState.RUNNING, state)
        state = (Object3dJobMachine.apply(state, Object3dJobMachine.Event.BeginDownload) as Object3dJobMachine.Result.Advanced).state
        assertEquals(Object3dJobState.DOWNLOADING, state)
        state = (Object3dJobMachine.apply(state, Object3dJobMachine.Event.Complete("node-1")) as Object3dJobMachine.Result.Advanced).state
        assertEquals(Object3dJobState.DONE, state)
    }

    @Test fun `Fail is legal from QUEUED, RUNNING, and DOWNLOADING`() {
        for (state in listOf(Object3dJobState.QUEUED, Object3dJobState.RUNNING, Object3dJobState.DOWNLOADING)) {
            val result = Object3dJobMachine.apply(state, Object3dJobMachine.Event.Fail("boom"))
            assertTrue("Fail should be legal from $state", result is Object3dJobMachine.Result.Advanced)
            assertEquals(Object3dJobState.FAILED, (result as Object3dJobMachine.Result.Advanced).state)
        }
    }

    @Test fun `Fail is rejected from the terminal states DONE and FAILED`() {
        for (state in listOf(Object3dJobState.DONE, Object3dJobState.FAILED)) {
            val result = Object3dJobMachine.apply(state, Object3dJobMachine.Event.Fail("boom again"))
            assertTrue("Fail should be rejected from terminal $state", result is Object3dJobMachine.Result.Rejected)
        }
    }

    @Test fun `skipping straight from QUEUED to DOWNLOADING is rejected`() {
        val result = Object3dJobMachine.apply(Object3dJobState.QUEUED, Object3dJobMachine.Event.BeginDownload)
        assertTrue(result is Object3dJobMachine.Result.Rejected)
    }

    @Test fun `no event is legal once DONE`() {
        val result = Object3dJobMachine.apply(Object3dJobState.DONE, Object3dJobMachine.Event.BeginDownload)
        assertTrue(result is Object3dJobMachine.Result.Rejected)
    }

    // ---- Object3dJobCodec round trips --------------------------------------------------------

    @Test fun `a QUEUED job round-trips through encode and decode`() {
        val original = textJob()
        val decoded = Object3dJobCodec.decode(Object3dJobCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test fun `a FAILED job with an errorEnvelope round-trips`() {
        val original = textJob(state = Object3dJobState.FAILED, errorEnvelope = error())
        val decoded = Object3dJobCodec.decode(Object3dJobCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals("OBJECT3D_PROVIDER_FAILED", decoded.errorEnvelope?.code)
    }

    @Test fun `unknown fields round-trip end to end`() {
        val original = textJob()
        val json = Object3dJobCodec.encode(original)
        json.put("futureField", 7)
        val decoded = Object3dJobCodec.decode(json)
        assertEquals(7, decoded.unknownFields["futureField"])
        val reEncoded = Object3dJobCodec.encode(decoded)
        assertEquals(7, reEncoded.getInt("futureField"))
    }

    // ---- Real fixtures, embedded verbatim ---------------------------------------------------

    // Verbatim copy of fixtures/object3d/valid/object3d-job-done-valid.json
    private val jobDoneValidJson = """
        {
          "schemaVersion": "1.0.0",
          "jobId": "job-meshy-text-0003",
          "provider": "MESHY",
          "providerJobId": "msy_01J9K0000000000000000TXT3",
          "mode": "TEXT_TO_3D",
          "prompt": "a ceramic coffee mug with a leaf pattern",
          "sourceImageRef": null,
          "state": "DONE",
          "progressPercent": 100,
          "downloadUrl": "https://assets.meshy.ai/results/msy_01J9K0000000000000000TXT3/model.glb",
          "resultNodeId": "node-cloud-meshy-0002",
          "errorEnvelope": null,
          "createdAtUtc": "2026-08-15T14:00:00Z",
          "updatedAtUtc": "2026-08-15T14:03:12Z",
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the real object3d-job-done-valid fixture decodes to a legal DONE job`() {
        val decoded = Object3dJobCodec.decode(JSONObject(jobDoneValidJson))
        assertEquals(Object3dJobState.DONE, decoded.state)
        assertEquals("node-cloud-meshy-0002", decoded.resultNodeId)
        // Re-encoding a fixture that already validates against the schema must not change its
        // decoded meaning.
        val roundTripped = Object3dJobCodec.decode(Object3dJobCodec.encode(decoded))
        assertEquals(decoded, roundTripped)
    }

    // Verbatim copy of fixtures/object3d/invalid/object3d-job-done-missing-resultnodeid.invalid.json
    private val jobDoneMissingResultNodeIdJson = """
        {
          "schemaVersion": "1.0.0",
          "jobId": "job-invalid-0001",
          "provider": "MESHY",
          "providerJobId": "msy_01J9K0000000000000000BAD1",
          "mode": "TEXT_TO_3D",
          "prompt": "a garden gnome",
          "sourceImageRef": null,
          "state": "DONE",
          "progressPercent": 100,
          "downloadUrl": "https://assets.meshy.ai/results/msy_01J9K0000000000000000BAD1/model.glb",
          "resultNodeId": null,
          "errorEnvelope": null,
          "createdAtUtc": "2026-08-15T16:00:00Z",
          "updatedAtUtc": "2026-08-15T16:04:00Z",
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the real invalid DONE-without-resultNodeId fixture is rejected by decode, not silently accepted`() {
        try {
            Object3dJobCodec.decode(JSONObject(jobDoneMissingResultNodeIdJson))
            org.junit.Assert.fail("expected IllegalArgumentException for DONE without a resultNodeId")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("resultNodeId"))
        }
    }

    // Verbatim copy of fixtures/object3d/adversarial/object3d-job-download-url-non-provider-host.adversarial.json
    private val jobNonProviderHostJson = """
        {
          "schemaVersion": "1.0.0",
          "jobId": "job-adversarial-0001",
          "provider": "MESHY",
          "providerJobId": "msy_01J9K0000000000000000EVL1",
          "mode": "TEXT_TO_3D",
          "prompt": "a friendly cartoon robot",
          "sourceImageRef": null,
          "state": "DOWNLOADING",
          "progressPercent": 88,
          "downloadUrl": "https://meshy.ai.attacker-controlled.example/results/model.glb",
          "resultNodeId": null,
          "errorEnvelope": null,
          "createdAtUtc": "2026-08-15T18:00:00Z",
          "updatedAtUtc": "2026-08-15T18:05:00Z",
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the adversarial non-provider-host job fixture decodes fine (structurally valid) but its URL must be refused downstream`() {
        // Object3dJobCodec/Object3dJob only check SHAPE (a non-blank https URL) — this decode
        // succeeding is the whole point of the adversarial case; see
        // CloudObject3dContractsTest for the real host check that must refuse this URL.
        val decoded = Object3dJobCodec.decode(JSONObject(jobNonProviderHostJson))
        assertEquals(Object3dJobState.DOWNLOADING, decoded.state)
        val validation = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.MESHY, decoded.downloadUrl!!)
        assertTrue(validation is DownloadUrlValidation.Refused)
    }
}
