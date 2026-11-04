package dev.fonebrew.domain.object3d

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [CloudObject3dContracts] (docs/design/objects-3d.md §5's pure Meshy/Tripo request
 * builders) — pure request-shape assertions, mirroring
 * `dev.fonebrew.domain.git.GitContentsApi`'s own test style, plus [DownloadUrlValidation]
 * coverage including the REAL adversarial fixture
 * (fixtures/object3d/adversarial/object3d-job-download-url-non-provider-host) embedded verbatim.
 */
class CloudObject3dContractsTest {

    // ---- textTo3dCreate -----------------------------------------------------------------------

    @Test fun `textTo3dCreate builds a POST against the provider's own API base with a bearer token`() {
        val req = CloudObject3dContracts.textTo3dCreate(Object3dCloudProvider.MESHY, "sk-test-key", "a red teapot")
        assertEquals("POST", req.method)
        assertEquals("https://api.meshy.ai/openapi/v2/text-to-3d", req.url)
        assertEquals("Bearer sk-test-key", req.headers["Authorization"])
        assertEquals("application/json", req.headers["Content-Type"])
        val body = JSONObject(req.body!!)
        assertEquals("a red teapot", body.getString("prompt"))
    }

    @Test fun `textTo3dCreate against Tripo uses Tripo's own API base`() {
        val req = CloudObject3dContracts.textTo3dCreate(Object3dCloudProvider.TRIPO, "sk-test-key", "a wooden chair")
        assertEquals("https://api.tripo3d.ai/openapi/v2/text-to-3d", req.url)
    }

    @Test fun `textTo3dCreate includes an optional art style when provided`() {
        val req = CloudObject3dContracts.textTo3dCreate(Object3dCloudProvider.MESHY, "sk-test-key", "a fox", artStyle = "low-poly")
        val body = JSONObject(req.body!!)
        assertEquals("low-poly", body.getString("art_style"))
    }

    @Test fun `textTo3dCreate rejects a blank apiKey`() {
        try {
            CloudObject3dContracts.textTo3dCreate(Object3dCloudProvider.MESHY, "  ", "a fox")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("apiKey"))
        }
    }

    @Test fun `textTo3dCreate rejects a blank prompt`() {
        try {
            CloudObject3dContracts.textTo3dCreate(Object3dCloudProvider.MESHY, "sk-test-key", "   ")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("prompt"))
        }
    }

    // ---- imageTo3dCreate -----------------------------------------------------------------------

    @Test fun `imageTo3dCreate builds a POST carrying the image reference`() {
        val req = CloudObject3dContracts.imageTo3dCreate(Object3dCloudProvider.TRIPO, "sk-test-key", "https://example.com/photo.png")
        assertEquals("POST", req.method)
        assertEquals("https://api.tripo3d.ai/openapi/v2/image-to-3d", req.url)
        val body = JSONObject(req.body!!)
        assertEquals("https://example.com/photo.png", body.getString("image_url"))
    }

    // ---- pollJob --------------------------------------------------------------------------------

    @Test fun `pollJob builds a GET at the mode-appropriate path with no body`() {
        val req = CloudObject3dContracts.pollJob(Object3dCloudProvider.MESHY, "sk-test-key", "msy_123", Object3dJobMode.TEXT_TO_3D)
        assertEquals("GET", req.method)
        assertEquals("https://api.meshy.ai/openapi/v2/text-to-3d/msy_123", req.url)
        assertEquals(null, req.body)
    }

    @Test fun `pollJob for IMAGE_TO_3D uses the image-to-3d path segment`() {
        val req = CloudObject3dContracts.pollJob(Object3dCloudProvider.TRIPO, "sk-test-key", "tp_456", Object3dJobMode.IMAGE_TO_3D)
        assertEquals("https://api.tripo3d.ai/openapi/v2/image-to-3d/tp_456", req.url)
    }

    @Test fun `no request builder ever puts the api key in the URL`() {
        val apiKey = "sk-secret-do-not-leak"
        val urls = listOf(
            CloudObject3dContracts.textTo3dCreate(Object3dCloudProvider.MESHY, apiKey, "a fox").url,
            CloudObject3dContracts.imageTo3dCreate(Object3dCloudProvider.MESHY, apiKey, "https://x/y.png").url,
            CloudObject3dContracts.pollJob(Object3dCloudProvider.MESHY, apiKey, "msy_1", Object3dJobMode.TEXT_TO_3D).url,
        )
        for (url in urls) assertTrue("URL must not embed the API key: $url", !url.contains(apiKey))
    }

    // ---- validateDownloadUrl: happy path ---------------------------------------------------

    @Test fun `an https URL on the provider's own host is allowed`() {
        val result = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.MESHY, "https://api.meshy.ai/results/x.glb")
        assertTrue(result is DownloadUrlValidation.Allowed)
    }

    @Test fun `a subdomain of the provider's own host is allowed`() {
        val result = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.MESHY, "https://assets.meshy.ai/results/x.glb")
        assertTrue(result is DownloadUrlValidation.Allowed)
        assertEquals("assets.meshy.ai", (result as DownloadUrlValidation.Allowed).host)
    }

    @Test fun `Tripo's own asset subdomain is allowed`() {
        val result = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.TRIPO, "https://cdn.tripo3d.ai/results/x.glb")
        assertTrue(result is DownloadUrlValidation.Allowed)
    }

    // ---- validateDownloadUrl: refusals ------------------------------------------------------

    @Test fun `a completely unrelated host is refused`() {
        val result = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.MESHY, "https://evil.example/x.glb")
        assertTrue(result is DownloadUrlValidation.Refused)
    }

    @Test fun `a similarly-spelled but different registrable domain is refused`() {
        val result = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.MESHY, "https://notmeshy.ai/x.glb")
        assertTrue(result is DownloadUrlValidation.Refused)
    }

    @Test fun `a look-alike host that merely starts with the provider name is refused`() {
        val result = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.MESHY, "https://meshy.ai.attacker-controlled.example/x.glb")
        assertTrue(result is DownloadUrlValidation.Refused)
    }

    @Test fun `Meshy's host does not validate against Tripo's allowlist and vice versa`() {
        val meshyUrlAgainstTripo = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.TRIPO, "https://assets.meshy.ai/x.glb")
        assertTrue(meshyUrlAgainstTripo is DownloadUrlValidation.Refused)
        val tripoUrlAgainstMeshy = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.MESHY, "https://assets.tripo3d.ai/x.glb")
        assertTrue(tripoUrlAgainstMeshy is DownloadUrlValidation.Refused)
    }

    @Test fun `a non-https scheme is refused even on the provider's own host`() {
        val result = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.MESHY, "http://api.meshy.ai/results/x.glb")
        assertTrue(result is DownloadUrlValidation.Refused)
    }

    @Test fun `a malformed URL is refused, not thrown`() {
        val result = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.MESHY, "not a url at all ://")
        assertTrue(result is DownloadUrlValidation.Refused)
    }

    // Verbatim copy of the downloadUrl in
    // fixtures/object3d/adversarial/object3d-job-download-url-non-provider-host.adversarial.json
    private val adversarialDownloadUrl = "https://meshy.ai.attacker-controlled.example/results/model.glb"

    @Test fun `refuses the real look-alike Meshy host from the adversarial job fixture`() {
        val result = CloudObject3dContracts.validateDownloadUrl(Object3dCloudProvider.MESHY, adversarialDownloadUrl)
        assertTrue("expected the look-alike host to be refused", result is DownloadUrlValidation.Refused)
        val reason = (result as DownloadUrlValidation.Refused).reason
        assertTrue(reason.contains("meshy.ai.attacker-controlled.example"))
        assertTrue(reason.contains("Meshy"))
    }
}
