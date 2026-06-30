package dev.aarso.inference.image

import android.util.Base64
import dev.aarso.data.ImageStore
import dev.aarso.domain.image.ImageEngine
import dev.aarso.domain.image.ImageParams
import dev.aarso.domain.image.ImageProvider
import dev.aarso.domain.image.ImageProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val httpJson = "application/json; charset=utf-8".toMediaType()

private val imageClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS) // image generation can be slow
    .build()

/** OpenAI Images API (gpt-image-1) — returns base64 PNG. */
class OpenAiImageEngine(
    private val provider: ImageProvider,
    private val apiKey: String,
    private val store: ImageStore,
) : ImageEngine {
    override val id = "img:${provider.id}"
    override val displayName = provider.displayName
    override val onDevice = false

    override suspend fun generate(prompt: String, params: ImageParams): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", provider.model)
            .put("prompt", prompt)
            .put("size", "${params.size}x${params.size}")
            .put("n", 1)
            .toString().toRequestBody(httpJson)
        val req = Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + "/images/generations")
            .header("Authorization", "Bearer $apiKey")
            .post(body).build()
        imageClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("OpenAI ${resp.code}: ${text.take(300)}")
            val b64 = JSONObject(text).getJSONArray("data").getJSONObject(0).optString("b64_json")
            require(b64.isNotEmpty()) { "no image in response" }
            store.save(Base64.decode(b64, Base64.DEFAULT))
        }
    }
}

/** Stability AI Stable Image — multipart in, raw PNG bytes out. */
class StabilityImageEngine(
    private val provider: ImageProvider,
    private val apiKey: String,
    private val store: ImageStore,
) : ImageEngine {
    override val id = "img:${provider.id}"
    override val displayName = provider.displayName
    override val onDevice = false

    override suspend fun generate(prompt: String, params: ImageParams): String = withContext(Dispatchers.IO) {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("model", provider.model)
            .addFormDataPart("output_format", "png")
            .addFormDataPart("aspect_ratio", "1:1")
            .apply { params.negativePrompt?.let { addFormDataPart("negative_prompt", it) } }
            .build()
        val req = Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + "/v2beta/stable-image/generate/sd3")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "image/*")
            .post(form).build()
        imageClient.newCall(req).execute().use { resp ->
            val ct = resp.header("Content-Type").orEmpty()
            if (!resp.isSuccessful || ct.startsWith("application/json")) {
                error("Stability ${resp.code}: ${resp.body?.string()?.take(300)}")
            }
            store.save(resp.body!!.bytes())
        }
    }
}

/** Google Imagen (Generative Language predict) — base64 PNG. */
class GeminiImageEngine(
    private val provider: ImageProvider,
    private val apiKey: String,
    private val store: ImageStore,
) : ImageEngine {
    override val id = "img:${provider.id}"
    override val displayName = provider.displayName
    override val onDevice = false

    override suspend fun generate(prompt: String, params: ImageParams): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
            .put("parameters", JSONObject().put("sampleCount", 1))
            .toString().toRequestBody(httpJson)
        val req = Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + "/v1beta/models/${provider.model}:predict")
            .header("x-goog-api-key", apiKey)
            .post(body).build()
        imageClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Imagen ${resp.code}: ${text.take(300)}")
            val b64 = JSONObject(text).getJSONArray("predictions").getJSONObject(0)
                .optString("bytesBase64Encoded")
            require(b64.isNotEmpty()) { "no image in response" }
            store.save(Base64.decode(b64, Base64.DEFAULT))
        }
    }
}

object ImageEngineFactory {
    fun create(provider: ImageProvider, apiKey: String, store: ImageStore): ImageEngine =
        when (provider.kind) {
            ImageProviderKind.OPENAI_IMAGE -> OpenAiImageEngine(provider, apiKey, store)
            ImageProviderKind.STABILITY -> StabilityImageEngine(provider, apiKey, store)
            ImageProviderKind.GEMINI_IMAGE -> GeminiImageEngine(provider, apiKey, store)
        }
}
