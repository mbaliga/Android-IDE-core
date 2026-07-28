package dev.aarso.inference.cloud

import dev.aarso.domain.MessageNode
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
            supportsVision = provider.supportsVision,
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
 *
 * [supportsVision] gates content-block serialization (daily-driver.md W1): a
 * message only becomes an `image_url`+`text` block array when it carries
 * [Conversations.ATTACHMENTS_KEY] metadata *and* [supportsVision] is true —
 * otherwise `content` stays the plain string it always was, so a non-vision
 * provider/model gets a byte-identical, cache-friendly request. Defaults to
 * `false` so any pre-W1 call site keeps building the plain-string shape
 * unchanged.
 */
internal fun buildOpenAiCompatRequestBody(
    messages: List<MessageNode>,
    params: SamplingParams,
    model: String,
    supportsSampling: Boolean,
    supportsVision: Boolean = false,
): JSONObject {
    val msgs = JSONArray()
    for (m in messages) {
        msgs.put(JSONObject().put("role", m.role.wire).put("content", contentFor(m, supportsVision)))
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

/**
 * A message's `content`: the plain string (today's shape) unless [supportsVision]
 * is true and the node carries attachments, in which case it becomes an
 * OpenAI-shape block array — image blocks first, the text block last (same
 * ordering as the Anthropic engine).
 *
 * An attachment whose file can't be read is skipped rather than failing the
 * whole request; if *every* attachment turns out unreadable, this falls back
 * to the plain string too (never sends an image-less block array). The
 * user-facing refusal for a blind model on a *current* send is handled at
 * send time (ChatViewModel) — this fallback only covers older history, e.g.
 * the model changed mid-conversation, where silent omission is correct for a
 * turn that already happened.
 */
private fun contentFor(message: MessageNode, supportsVision: Boolean): Any {
    if (!supportsVision) return message.content
    val attachments = Attachments.decode(message.metadata[Conversations.ATTACHMENTS_KEY])
    if (attachments.isEmpty()) return message.content
    val blocks = JSONArray()
    for (attachment in attachments) {
        val dataUri = dataUriFor(attachment) ?: continue
        blocks.put(
            JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", dataUri)),
        )
    }
    if (blocks.length() == 0) return message.content // every attachment unreadable — fall back
    blocks.put(JSONObject().put("type", "text").put("text", message.content))
    return blocks
}

/** `data:<mime>;base64,<data>` for one attachment, or null if the file can't be read. */
private fun dataUriFor(attachment: Attachments.Attachment): String? {
    val bytes = runCatching { File(attachment.path).readBytes() }.getOrNull() ?: return null
    val b64 = Base64.getEncoder().encodeToString(bytes)
    return "data:${attachment.mime};base64,$b64"
}
