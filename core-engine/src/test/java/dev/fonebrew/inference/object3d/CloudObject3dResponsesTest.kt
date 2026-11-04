package dev.fonebrew.inference.object3d

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure field-extraction tests against hand-built JSON mirroring each vendor's documented
 *  response shape (see [MeshyResponses]/[TripoResponses]'s own KDoc for the source and the
 *  "best-effort, owner-verified against the real network" caveat). No network involved — these
 *  assert exactly what the code does with a given JSON shape, which is what's actually testable
 *  from this container. */
class CloudObject3dResponsesTest {

    @Test fun `meshy job id comes from the create response's result field`() {
        val json = JSONObject().put("result", "018a210d-job")
        assertEquals("018a210d-job", MeshyResponses.providerJobId(json))
    }

    @Test fun `meshy status maps SUCCEEDED to DONE and FAILED to FAILED`() {
        assertEquals(PollStatus.DONE, MeshyResponses.status(JSONObject().put("status", "SUCCEEDED")))
        assertEquals(PollStatus.FAILED, MeshyResponses.status(JSONObject().put("status", "FAILED")))
        assertEquals(PollStatus.PENDING, MeshyResponses.status(JSONObject().put("status", "IN_PROGRESS")))
        assertEquals(PollStatus.PENDING, MeshyResponses.status(JSONObject()))
    }

    @Test fun `meshy download url reads model_urls glb`() {
        val json = JSONObject().put("model_urls", JSONObject().put("glb", "https://assets.meshy.ai/x.glb"))
        assertEquals("https://assets.meshy.ai/x.glb", MeshyResponses.downloadUrl(json))
    }

    @Test fun `meshy error reads task_error message`() {
        val json = JSONObject().put("task_error", JSONObject().put("message", "prompt rejected"))
        assertEquals("prompt rejected", MeshyResponses.error(json))
    }

    @Test fun `tripo job id comes from the nested data task_id`() {
        val json = JSONObject().put("data", JSONObject().put("task_id", "t-123"))
        assertEquals("t-123", TripoResponses.providerJobId(json))
    }

    @Test fun `tripo status maps nested success and failed`() {
        val done = JSONObject().put("data", JSONObject().put("status", "success"))
        val failed = JSONObject().put("data", JSONObject().put("status", "failed"))
        val running = JSONObject().put("data", JSONObject().put("status", "running"))
        assertEquals(PollStatus.DONE, TripoResponses.status(done))
        assertEquals(PollStatus.FAILED, TripoResponses.status(failed))
        assertEquals(PollStatus.PENDING, TripoResponses.status(running))
    }

    @Test fun `tripo download url reads nested output pbr_model`() {
        val json = JSONObject().put(
            "data",
            JSONObject().put("output", JSONObject().put("pbr_model", "https://tripo3d.ai/x.glb")),
        )
        assertEquals("https://tripo3d.ai/x.glb", TripoResponses.downloadUrl(json))
    }

    @Test fun `unresolvable fields return null rather than throwing`() {
        val empty = JSONObject()
        assertNull(MeshyResponses.providerJobId(empty))
        assertNull(MeshyResponses.downloadUrl(empty))
        assertNull(TripoResponses.providerJobId(empty))
        assertNull(TripoResponses.downloadUrl(empty))
    }
}
