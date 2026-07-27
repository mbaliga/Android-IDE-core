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
 * Anthropic Messages API (Claude). System turns go in the top-level `system`
 * field; only user/assistant turns go in `messages`. No sampling knobs are sent
 * (Opus 4.7+ rejects them, §3) and no logprobs come back.
 */
class AnthropicEngine(provider: CloudProvider, apiKey: String) :
    CloudEngine(provider, apiKey) {

    override fun buildRequest(messages: List<MessageNode>, params: SamplingParams): Request {
        val body = buildAnthropicRequestBody(messages, params, provider.model)

        return Request.Builder()
            .url(provider.baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    override fun isDone(type: String?, data: String): Boolean = type == "message_stop"

    override fun parseDelta(type: String?, data: String): String? {
        if (type != "content_block_delta") return null
        val delta = JSONObject(data).optJSONObject("delta") ?: return null
        return delta.optString("text").orEmptyToNull()
    }

    // input_tokens arrive on message_start, output_tokens on message_delta.
    override fun usageOf(type: String?, data: String): dev.aarso.domain.cost.UsageReport? =
        dev.aarso.domain.cost.ProviderUsage.fromAnthropic(data)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Pure JSON-body builder for the Anthropic Messages API request, factored out of
 * [AnthropicEngine.buildRequest] so it's unit-testable without the OkHttp [Request]
 * wrapper (`buildRequest` is `protected` on [CloudEngine] and unreachable from a
 * JVM test that isn't a subclass). System turns are joined into the top-level
 * `system` field; only user/assistant turns go in `messages`. `max_tokens` is
 * always sent — the Messages API rejects a request without it.
 */
internal fun buildAnthropicRequestBody(
    messages: List<MessageNode>,
    params: SamplingParams,
    model: String,
): JSONObject {
    val system = messages.filter { it.role == Role.SYSTEM }
        .joinToString("\n\n") { it.content }
    val msgs = JSONArray()
    for (m in messages) {
        if (m.role == Role.SYSTEM) continue
        msgs.put(JSONObject().put("role", m.role.wire).put("content", m.content))
    }
    val body = JSONObject()
        .put("model", model)
        .put("max_tokens", params.maxTokens)
        .put("stream", true)
        .put("messages", msgs)
    if (system.isNotBlank()) body.put("system", system)
    return body
}
