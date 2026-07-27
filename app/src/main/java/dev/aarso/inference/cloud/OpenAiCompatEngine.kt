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
        val body = buildOpenAiCompatRequestBody(
            messages,
            params,
            model = provider.model,
            supportsSampling = provider.kind.supportsSampling,
        )
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

/**
 * Pure JSON-body builder for the OpenAI-compatible chat-completions request,
 * factored out of [OpenAiCompatEngine.buildRequest] so it's unit-testable
 * without the OkHttp [Request] wrapper (`buildRequest` is `protected` on
 * [CloudEngine] and unreachable from a JVM test that isn't a subclass).
 * `max_tokens` is the field name across OpenAI-compatible servers (some newer
 * OpenAI-only servers accept `max_completion_tokens` instead, but `max_tokens`
 * stays the widely-supported, provider-generic choice — CLAUDE.md rule 2) and
 * is always sent so replies stop silently truncating at server defaults.
 */
internal fun buildOpenAiCompatRequestBody(
    messages: List<MessageNode>,
    params: SamplingParams,
    model: String,
    supportsSampling: Boolean,
): JSONObject {
    val msgs = JSONArray()
    for (m in messages) {
        msgs.put(JSONObject().put("role", m.role.wire).put("content", m.content))
    }
    val body = JSONObject()
        .put("model", model)
        .put("messages", msgs)
        .put("stream", true)
        .put("max_tokens", params.maxTokens)
    if (supportsSampling) {
        body.put("temperature", params.temperature.toDouble())
        body.put("top_p", params.topP.toDouble())
    }
    return body
}
