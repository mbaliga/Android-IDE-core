package dev.aarso.inference.cloud

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.SamplingParams
import dev.aarso.domain.cloud.CloudProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini (Generative Language API). Roles map user→"user",
 * assistant→"model"; system turns become `systemInstruction`. Streaming uses
 * `:streamGenerateContent?alt=sse`, which ends by closing the stream (no
 * sentinel event).
 */
class GeminiEngine(provider: CloudProvider, apiKey: String) :
    CloudEngine(provider, apiKey) {

    override fun buildRequest(messages: List<MessageNode>, params: SamplingParams): Request {
        val contents = JSONArray()
        for (m in messages) {
            if (m.role == Role.SYSTEM) continue
            val role = if (m.role == Role.ASSISTANT) "model" else "user"
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", m.content))),
            )
        }
        val body = JSONObject().put("contents", contents)

        val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        if (system.isNotBlank()) {
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
            )
        }
        if (provider.kind.supportsSampling) {
            body.put(
                "generationConfig",
                JSONObject()
                    .put("temperature", params.temperature.toDouble())
                    .put("topP", params.topP.toDouble()),
            )
        }

        val url = provider.baseUrl.trimEnd('/') +
            "/v1beta/models/${provider.model}:streamGenerateContent?alt=sse"
        return Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    override fun parseDelta(type: String?, data: String): String? {
        val candidates = JSONObject(data).optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString().orEmptyToNull()
    }

    // Gemini sends usageMetadata per chunk with cumulative counts; the accumulator keeps the max.
    override fun usageOf(type: String?, data: String): dev.aarso.domain.cost.UsageReport? =
        dev.aarso.domain.cost.ProviderUsage.fromGemini(data)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
