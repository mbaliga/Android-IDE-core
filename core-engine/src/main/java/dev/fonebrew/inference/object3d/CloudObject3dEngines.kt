package dev.fonebrew.inference.object3d

import dev.fonebrew.domain.object3d.CloudObject3dContracts
import dev.fonebrew.domain.object3d.DownloadUrlValidation
import dev.fonebrew.domain.object3d.Object3dCloudProvider
import dev.fonebrew.domain.object3d.Object3dHttpRequest
import dev.fonebrew.domain.object3d.Object3dJobMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val httpJson = "application/json; charset=utf-8".toMediaType()

private val object3dClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

/** §5 poll cadence — generous but bounded so a stuck job fails honestly instead of hanging the
 *  coroutine forever (~10 minutes total at this interval/count). */
private const val POLL_INTERVAL_MS = 4_000L
private const val MAX_POLLS = 150

/**
 * docs/design/objects-3d.md §5's provider-generic seam: create (text-to-3D or image-to-3D) ->
 * poll -> download a validated GLB. Every implementation is, by construction, a watched-cloud
 * call (binding rule 2) — a caller must only ever invoke this after the user's per-use opt-in
 * (§1's mini-chooser), never as a silent fallback from
 * [dev.fonebrew.inference.object3d.ProceduralObjectEngine]'s on-device path.
 *
 * Network execution is owner-verified (CLAUDE.md "Environment honesty" — no host in CI, and the
 * real Meshy/Tripo response shapes below are this file's best-effort reading of each vendor's
 * public API docs, not something this container can exercise against the network). The request
 * building and the download-host check this class calls into
 * ([CloudObject3dContracts.textTo3dCreate]/[CloudObject3dContracts.imageTo3dCreate]/
 * [CloudObject3dContracts.pollJob]/[CloudObject3dContracts.validateDownloadUrl]) are pure and
 * JVM-tested; this class is the thin OkHttp adapter §5 asks for. So are the per-provider response
 * readers below ([MeshyResponses]/[TripoResponses]) — deliberately split out as pure
 * `JSONObject -> value` functions so their field-extraction logic is unit-testable without a
 * network call, even though the engines themselves are not.
 */
interface CloudObject3dEngine {
    val provider: Object3dCloudProvider

    /** Text-to-3D: create -> poll -> download. Returns the downloaded GLB bytes. */
    suspend fun textTo3d(prompt: String, artStyle: String? = null): ByteArray

    /** Image-to-3D: same lifecycle, from a source image (URL or `data:` URI). */
    suspend fun imageTo3d(imageUrlOrDataUri: String): ByteArray
}

/** §5/§6's job status, folded down to the three outcomes the poll loop below acts on. */
internal enum class PollStatus { PENDING, DONE, FAILED }

/** Pure per-provider response reading — no network, no Android. Split out from the engine
 *  classes below purely so it is directly JVM-testable against a hand-built [JSONObject]. */
internal interface CloudObject3dResponses {
    fun providerJobId(createResponse: JSONObject): String?
    fun status(pollResponse: JSONObject): PollStatus
    fun downloadUrl(pollResponse: JSONObject): String?
    fun error(pollResponse: JSONObject): String?
}

/**
 * Meshy's Text-to-3D/Image-to-3D API (v2): create returns `{"result": "<job-id>"}`; poll returns
 * `{"id":..., "status": "PENDING"|"IN_PROGRESS"|"SUCCEEDED"|"FAILED"|"EXPIRED",
 * "model_urls": {"glb": "https://...", ...}, "task_error": {"message": "..."}}`. Best-effort per
 * this file's own class KDoc — field names read defensively (a couple of plausible fallbacks)
 * rather than asserting one exact shape, so a minor vendor drift degrades to an honest "no
 * download URL" error instead of a crash.
 */
internal object MeshyResponses : CloudObject3dResponses {
    override fun providerJobId(createResponse: JSONObject): String? =
        createResponse.optNonBlank("result") ?: createResponse.optNonBlank("id") ?: createResponse.optNonBlank("task_id")

    override fun status(pollResponse: JSONObject): PollStatus =
        when (pollResponse.optString("status").uppercase()) {
            "SUCCEEDED", "SUCCESS", "COMPLETE", "COMPLETED" -> PollStatus.DONE
            "FAILED", "ERROR", "EXPIRED", "CANCELED", "CANCELLED" -> PollStatus.FAILED
            else -> PollStatus.PENDING
        }

    override fun downloadUrl(pollResponse: JSONObject): String? {
        val urls = pollResponse.optJSONObject("model_urls")
        return urls?.optNonBlank("glb") ?: pollResponse.optNonBlank("model_url") ?: pollResponse.optNonBlank("downloadUrl")
    }

    override fun error(pollResponse: JSONObject): String? =
        pollResponse.optJSONObject("task_error")?.optNonBlank("message") ?: pollResponse.optNonBlank("message")
}

/**
 * Tripo's Text-to-3D/Image-to-3D API (v2): both create and poll responses nest their payload
 * under `"data"` — create: `{"code":0,"data":{"task_id":"..."}}`; poll:
 * `{"code":0,"data":{"task_id":..., "status": "queued"|"running"|"success"|"failed",
 * "output": {"pbr_model": "https://...", "model": "https://..."}}}`. Same best-effort/defensive
 * reading discipline as [MeshyResponses] — see this file's class KDoc.
 */
internal object TripoResponses : CloudObject3dResponses {
    override fun providerJobId(createResponse: JSONObject): String? =
        (createResponse.optJSONObject("data") ?: createResponse).optNonBlank("task_id")

    override fun status(pollResponse: JSONObject): PollStatus {
        val data = pollResponse.optJSONObject("data") ?: pollResponse
        return when (data.optString("status").lowercase()) {
            "success", "succeeded", "completed" -> PollStatus.DONE
            "failed", "error", "cancelled", "canceled", "banned" -> PollStatus.FAILED
            else -> PollStatus.PENDING
        }
    }

    override fun downloadUrl(pollResponse: JSONObject): String? {
        val data = pollResponse.optJSONObject("data") ?: pollResponse
        val output = data.optJSONObject("output")
        return output?.optNonBlank("pbr_model") ?: output?.optNonBlank("model") ?: data.optNonBlank("model_url")
    }

    override fun error(pollResponse: JSONObject): String? {
        val data = pollResponse.optJSONObject("data") ?: pollResponse
        return data.optNonBlank("error") ?: pollResponse.optNonBlank("message")
    }
}

private fun JSONObject.optNonBlank(key: String): String? = optString(key, "").ifBlank { null }

/** Shared create/poll/download lifecycle (§5). Provider-specific only in [responses] and the
 *  request builders it calls into via [CloudObject3dContracts]. */
private class GenericCloudObject3dEngine(
    override val provider: Object3dCloudProvider,
    private val apiKey: String,
    private val responses: CloudObject3dResponses,
    /** §1's "custom base URL escape hatch": when set and different from the provider's own API
     *  host, every create/poll request is redirected there (e.g. a self-hosted gateway) — but
     *  the DOWNLOAD host check ([CloudObject3dContracts.validateDownloadUrl]) is never relaxed by
     *  this, on purpose: a truly third-party download host stays refused regardless of this
     *  setting, per binding rule 2's watched-object floor. */
    private val baseUrlOverride: String? = null,
) : CloudObject3dEngine {

    override suspend fun textTo3d(prompt: String, artStyle: String?): ByteArray = withContext(Dispatchers.IO) {
        runLifecycle(CloudObject3dContracts.textTo3dCreate(provider, apiKey, prompt, artStyle), Object3dJobMode.TEXT_TO_3D)
    }

    override suspend fun imageTo3d(imageUrlOrDataUri: String): ByteArray = withContext(Dispatchers.IO) {
        runLifecycle(CloudObject3dContracts.imageTo3dCreate(provider, apiKey, imageUrlOrDataUri), Object3dJobMode.IMAGE_TO_3D)
    }

    private fun runLifecycle(createRequest: Object3dHttpRequest, mode: Object3dJobMode): ByteArray {
        val createBody = execute(createRequest)
        val providerJobId = responses.providerJobId(createBody)
            ?: error("${provider.label}: create response carried no job id")

        repeat(MAX_POLLS) {
            val pollBody = execute(CloudObject3dContracts.pollJob(provider, apiKey, providerJobId, mode))
            when (responses.status(pollBody)) {
                PollStatus.DONE -> {
                    val url = responses.downloadUrl(pollBody)
                        ?: error("${provider.label}: job reported done with no download URL")
                    return download(url)
                }
                PollStatus.FAILED -> error("${provider.label} job failed: ${responses.error(pollBody) ?: "no error detail"}")
                PollStatus.PENDING -> Thread.sleep(POLL_INTERVAL_MS)
            }
        }
        error("${provider.label}: job did not complete after $MAX_POLLS polls")
    }

    private fun download(url: String): ByteArray {
        when (val validation = CloudObject3dContracts.validateDownloadUrl(provider, url)) {
            is DownloadUrlValidation.Refused -> error("${provider.label}: refusing download — ${validation.reason}")
            is DownloadUrlValidation.Allowed -> Unit
        }
        object3dClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) error("${provider.label}: download HTTP ${resp.code}")
            return resp.body?.bytes() ?: error("${provider.label}: empty download body")
        }
    }

    private fun execute(req: Object3dHttpRequest): JSONObject {
        val builder = Request.Builder().url(rewriteHost(req.url))
        req.headers.forEach { (k, v) -> builder.header(k, v) }
        val body = req.body
        builder.method(req.method, if (body != null) body.toRequestBody(httpJson) else null)
        object3dClient.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("${provider.label} ${resp.code}: ${text.take(300)}")
            return runCatching { JSONObject(text) }.getOrElse {
                error("${provider.label}: non-JSON response — ${text.take(200)}")
            }
        }
    }

    private fun rewriteHost(url: String): String {
        val override = baseUrlOverride?.trim()?.trimEnd('/')
        if (override.isNullOrBlank() || override == provider.apiBaseUrl.trimEnd('/')) return url
        return if (url.startsWith(provider.apiBaseUrl)) override + url.removePrefix(provider.apiBaseUrl.trimEnd('/')) else url
    }
}

/** Mirrors [dev.fonebrew.inference.image.ImageEngineFactory]'s shape. */
object CloudObject3dEngineFactory {
    fun create(provider: Object3dCloudProvider, apiKey: String, baseUrl: String? = null): CloudObject3dEngine =
        when (provider) {
            Object3dCloudProvider.MESHY -> GenericCloudObject3dEngine(provider, apiKey, MeshyResponses, baseUrl)
            Object3dCloudProvider.TRIPO -> GenericCloudObject3dEngine(provider, apiKey, TripoResponses, baseUrl)
        }
}
