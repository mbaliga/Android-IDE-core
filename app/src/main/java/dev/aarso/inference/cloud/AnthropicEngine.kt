package dev.aarso.inference.cloud

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.SamplingParams
import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.tree.Attachments
import dev.aarso.domain.tree.Conversations
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Anthropic Messages API (Claude). System turns go in the top-level `system`
 * field; only user/assistant turns go in `messages`. No sampling knobs are sent
 * (Opus 4.7+ rejects them, §3) and no logprobs come back.
 *
 * W1 (vision input): a message whose metadata carries
 * [Conversations.ATTACHMENTS_KEY] gets a content **block array** (images first,
 * one trailing text block) instead of a plain string — but only when
 * [CloudProvider.supportsVision] is true for the provider instance this engine
 * was built for. See [buildAnthropicRequestBody].
 */
class AnthropicEngine(provider: CloudProvider, apiKey: String) :
    CloudEngine(provider, apiKey) {

    override fun buildRequest(messages: List<MessageNode>, params: SamplingParams): Request {
        val body = buildAnthropicRequestBody(messages, params, provider.model, provider.supportsVision)

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
 *
 * [supportsVision] gates the W1 vision content-block shape (see [contentOf]);
 * defaults to `false` so pre-W1 call sites keep building the plain-string shape
 * unchanged.
 */
internal fun buildAnthropicRequestBody(
    messages: List<MessageNode>,
    params: SamplingParams,
    model: String,
    supportsVision: Boolean = false,
): JSONObject {
    val system = messages.filter { it.role == Role.SYSTEM }
        .joinToString("\n\n") { it.content }
    val msgs = JSONArray()
    for (m in messages) {
        if (m.role == Role.SYSTEM) continue
        msgs.put(JSONObject().put("role", m.role.wire).put("content", contentOf(m, supportsVision)))
    }
    val body = JSONObject()
        .put("model", model)
        .put("max_tokens", params.maxTokens)
        .put("stream", true)
        .put("messages", msgs)
    if (system.isNotBlank()) body.put("system", system)
    return body
}

/**
 * A message's Anthropic `content` field (daily-driver.md W1). Plain string,
 * unless [supportsVision] is true and the message carries
 * [Conversations.ATTACHMENTS_KEY] — then a block array: one
 * `{"type":"image","source":{"type":"base64","media_type":...,"data":...}}` per
 * readable attachment (in attachment order), followed by exactly one trailing
 * `{"type":"text","text":...}` block. Byte-identical to the plain-string shape
 * when there's nothing to add — prompt-cache friendliness (plan §W1).
 *
 * A vision-blind model or an attachment whose file can't be read falls back to
 * the plain string rather than being stripped-with-error: the user-facing
 * refusal for a *current* send on a blind model is handled at send time
 * (ChatViewModel), not here. This engine-level fallback only covers older
 * history — e.g. the model changed mid-conversation — where silent omission is
 * the right behaviour for a message that already happened.
 */
private fun contentOf(message: MessageNode, supportsVision: Boolean): Any {
    if (!supportsVision) return message.content
    val attachments = Attachments.decode(message.metadata[Conversations.ATTACHMENTS_KEY])
    if (attachments.isEmpty()) return message.content

    val blocks = JSONArray()
    for (a in attachments) {
        val bytes = runCatching { File(a.path).readBytes() }.getOrNull() ?: continue
        blocks.put(
            JSONObject()
                .put("type", "image")
                .put(
                    "source",
                    JSONObject()
                        .put("type", "base64")
                        .put("media_type", a.mime)
                        .put("data", Base64.getEncoder().encodeToString(bytes)),
                ),
        )
    }
    if (blocks.length() == 0) return message.content // every attachment unreadable — fall back
    blocks.put(JSONObject().put("type", "text").put("text", message.content))
    return blocks
}
