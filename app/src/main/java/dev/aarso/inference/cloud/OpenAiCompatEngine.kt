package dev.aarso.inference.cloud

import dev.aarso.domain.MessageNode
import dev.aarso.domain.SamplingParams
import dev.aarso.domain.cloud.CloudProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI chat-completions format — covers OpenAI, DeepSeek, Alibaba/Qwen
 * compatible-mode, and self-hosted OpenAI-compatible servers. [CloudProvider.baseUrl]
 * is expected to include the version segment (e.g. ".../v1").
 */
class OpenAiCompatEngine(provider: CloudProvider, apiKey: String) :
    CloudEngine(provider, apiKey) {

    override fun buildRequest(messages: List<MessageNode>, params: SamplingParams): Request {
        val msgs = JSONArray()
        for (m in messages) {
            msgs.put(JSONObject().put("role", m.role.wire).put("content", m.content))
        }
        val body = JSONObject()
            .put("model", provider.model)
            .put("messages", msgs)
            .put("stream", true)
        if (provider.kind.supportsSampling) {
            body.put("temperature", params.temperature.toDouble())
            body.put("top_p", params.topP.toDouble())
        }
        return Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    override fun parseDelta(type: String?, data: String): String? {
        val obj = JSONObject(data)
        val choices = obj.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        return choices.getJSONObject(0).optJSONObject("delta")?.optString("content").orEmptyToNull()
    }

    // OpenAI streams a single `usage` block near the end (requires stream_options.include_usage).
    override fun usageOf(type: String?, data: String): dev.aarso.domain.cost.UsageReport? =
        dev.aarso.domain.cost.ProviderUsage.fromOpenAi(data)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun String?.orEmptyToNull(): String? = if (this.isNullOrEmpty()) null else this
