// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.object3d

import org.json.JSONObject
import java.net.URI

/**
 * A built HTTP request — pure data, mirrors [dev.fonebrew.domain.git.GitContentsApi]'s
 * `GitRequest`, so request construction is JVM-testable and the network execution (a future
 * `inference/object3d/CloudObject3dEngines.kt`, §5, out of scope for this pure package) stays a
 * thin OkHttp adapter.
 */
data class Object3dHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

/**
 * §5: "provider-generic seam (CloudObject3dEngine), adapters for Meshy and Tripo." Every job
 * against either provider is, by binding rule 2, a **watched object** — dispatched only when the
 * user opted in per-use (§1's "Generate 3D..." mini-chooser), never a silent fallback from the
 * on-device path.
 *
 * @param allowedDownloadHostSuffixes Hostname suffixes this provider's OWN infrastructure may
 *   serve a download from — an exact match, or any subdomain of one of these. Anything else is
 *   refused by [CloudObject3dContracts.validateDownloadUrl]. Two suffixes per provider
 *   (API host + a plausible asset/CDN host) because a real vendor frequently serves generated
 *   assets from a different subdomain than its API — see this schema's own $comment for why a
 *   JSON Schema `format: uri` check alone cannot express this.
 */
enum class Object3dCloudProvider(
    val label: String,
    val apiBaseUrl: String,
    val allowedDownloadHostSuffixes: List<String>,
) {
    MESHY("Meshy", "https://api.meshy.ai", listOf("meshy.ai")),
    TRIPO("Tripo", "https://api.tripo3d.ai", listOf("tripo3d.ai")),
}

sealed class DownloadUrlValidation {
    data class Allowed(val host: String) : DownloadUrlValidation()
    data class Refused(val reason: String) : DownloadUrlValidation()
}

/**
 * Pure request builders for the two cloud 3D-generation adapters §5 names: text-to-3D create,
 * image-to-3D create, job poll, and the download-URL provider-host check. No network, no
 * Android — the API key is passed in by the caller (never stored here; it lives encrypted via
 * `dev.fonebrew.security.KeystoreSecret`, binding rule 5) and never appears in any returned
 * [Object3dHttpRequest.url] (only in the `Authorization` header), mirroring
 * `dev.fonebrew.domain.git.GitContentsApi`'s identical token-never-in-the-URL discipline.
 *
 * [validateDownloadUrl] is this file's answer to §6's third minimum adversarial case: "a job
 * response pointing the download at a non-provider host — refused, per the loop-release
 * precedent" (see schemas/loops/loop-release.schema.json's own `releaseAssetUrl` $comment, which
 * documents the identical shape-only-vs-host-checked split). A provider's poll response is
 * attacker-influenced input the moment the provider account, DNS, or transport is compromised —
 * this check is the last line of defense before a downloaded blob is ever written to app storage
 * and minted as an Object3dNode. See fixtures/object3d/adversarial/ for the fixture that is
 * structurally schema-valid but MUST be refused here.
 */
object CloudObject3dContracts {

    fun textTo3dCreate(
        provider: Object3dCloudProvider,
        apiKey: String,
        prompt: String,
        artStyle: String? = null,
    ): Object3dHttpRequest {
        require(apiKey.isNotBlank()) { "CloudObject3dContracts.textTo3dCreate: apiKey must be non-blank." }
        require(prompt.isNotBlank()) { "CloudObject3dContracts.textTo3dCreate: prompt must be non-blank." }
        val body = JSONObject().apply {
            put("mode", "preview")
            put("prompt", prompt)
            artStyle?.let { put("art_style", it) }
        }
        return Object3dHttpRequest(
            method = "POST",
            url = "${apiBase(provider)}/openapi/v2/text-to-3d",
            headers = authHeaders(apiKey),
            body = body.toString(),
        )
    }

    fun imageTo3dCreate(
        provider: Object3dCloudProvider,
        apiKey: String,
        imageUrlOrDataUri: String,
    ): Object3dHttpRequest {
        require(apiKey.isNotBlank()) { "CloudObject3dContracts.imageTo3dCreate: apiKey must be non-blank." }
        require(imageUrlOrDataUri.isNotBlank()) { "CloudObject3dContracts.imageTo3dCreate: imageUrlOrDataUri must be non-blank." }
        val body = JSONObject().apply {
            put("mode", "preview")
            put("image_url", imageUrlOrDataUri)
        }
        return Object3dHttpRequest(
            method = "POST",
            url = "${apiBase(provider)}/openapi/v2/image-to-3d",
            headers = authHeaders(apiKey),
            body = body.toString(),
        )
    }

    /** §5: "polling/download in the engine with OkHttp" — this builds the poll request; the
     *  caller (that future engine) owns the actual polling loop/backoff. */
    fun pollJob(
        provider: Object3dCloudProvider,
        apiKey: String,
        providerJobId: String,
        mode: Object3dJobMode,
    ): Object3dHttpRequest {
        require(apiKey.isNotBlank()) { "CloudObject3dContracts.pollJob: apiKey must be non-blank." }
        require(providerJobId.isNotBlank()) { "CloudObject3dContracts.pollJob: providerJobId must be non-blank." }
        val kindSegment = if (mode == Object3dJobMode.TEXT_TO_3D) "text-to-3d" else "image-to-3d"
        return Object3dHttpRequest(
            method = "GET",
            url = "${apiBase(provider)}/openapi/v2/$kindSegment/$providerJobId",
            headers = authHeaders(apiKey),
            body = null,
        )
    }

    /**
     * Refuses a provider-reported `downloadUrl` whose host is not the declared [provider]'s own
     * infrastructure. Checks, in order: the URL parses at all; the scheme is `https`; the host is
     * present; the host equals (or is a subdomain of) one of [Object3dCloudProvider.allowedDownloadHostSuffixes].
     * A caller MUST run every provider-returned `downloadUrl` through this BEFORE fetching it or
     * persisting it into an [Object3dJob].
     */
    fun validateDownloadUrl(provider: Object3dCloudProvider, url: String): DownloadUrlValidation {
        val parsed = try {
            URI(url)
        } catch (e: Exception) {
            return DownloadUrlValidation.Refused("downloadUrl '$url' is not a well-formed URI: ${e.message}")
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https") {
            return DownloadUrlValidation.Refused("downloadUrl '$url' must use https (got scheme '${parsed.scheme}')")
        }
        val host = parsed.host?.lowercase()
        if (host.isNullOrBlank()) {
            return DownloadUrlValidation.Refused("downloadUrl '$url' has no host")
        }
        val allowed = provider.allowedDownloadHostSuffixes.any { suffix -> host == suffix || host.endsWith(".$suffix") }
        return if (allowed) {
            DownloadUrlValidation.Allowed(host)
        } else {
            DownloadUrlValidation.Refused(
                "downloadUrl host '$host' is not ${provider.label}'s own infrastructure " +
                    "(expected exactly, or a subdomain of, one of ${provider.allowedDownloadHostSuffixes})"
            )
        }
    }

    private fun apiBase(provider: Object3dCloudProvider): String = provider.apiBaseUrl.trimEnd('/')

    private fun authHeaders(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    )
}
