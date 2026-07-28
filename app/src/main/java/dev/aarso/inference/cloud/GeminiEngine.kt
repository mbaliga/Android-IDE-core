package dev.aarso.inference.cloud

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.SamplingParams
import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.cloud.Source
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
 * Google Gemini (Generative Language API). Roles map user→"user",
 * assistant→"model"; system turns become `systemInstruction`. Streaming uses
 * `:streamGenerateContent?alt=sse`, which ends by closing the stream (no
 * sentinel event).
 *
 * W1 (vision input): a message whose metadata carries
 * [Conversations.ATTACHMENTS_KEY] gets an `inline_data`-then-`text` `parts`
 * array instead of the plain single-text-part shape — but only when
 * [CloudProvider.supportsVision] is true for the provider instance this engine
 * was built for. See [partsFor].
 *
 * W2 (web search): [webSearchEnabled] is a **per-turn** opt-in (the composer's
 * globe chip — `daily-driver.md` W2), not a provider-instance property like
 * [CloudProvider.supportsVision], so it's a constructor param here rather than
 * read off [provider] — same precedent as `AnthropicEngine`. Defaults to
 * `false` so the existing `CloudEngineFactory` call site keeps building
 * byte-identical requests unless a caller explicitly opts a turn in.
 */
class GeminiEngine(
    provider: CloudProvider,
    apiKey: String,
    private val webSearchEnabled: Boolean = false,
) : CloudEngine(provider, apiKey) {

    override fun buildRequest(messages: List<MessageNode>, params: SamplingParams): Request {
        val body = buildGeminiRequestBody(
            messages,
            params,
            supportsSampling = provider.kind.supportsSampling,
            supportsVision = provider.supportsVision,
            webSearchEnabled = webSearchEnabled,
        )

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

    // Delegate to the top-level pure parser below — same reason buildRequest delegates to
    // buildGeminiRequestBody: this override is `protected` on CloudEngine and unreachable from a
    // JVM test that isn't a subclass; the free function is unit-testable directly. Gemini has no
    // Anthropic-style `pause_turn` concept for search, so [isPaused] stays at CloudEngine's
    // never-paused default — not overridden here.
    override fun sourcesOf(type: String?, data: String): List<Source>? = geminiSourcesOf(type, data)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Pure parser for server-side `google_search` grounding results (W2), factored out of
 * [GeminiEngine.sourcesOf] for the same testability reason as [buildGeminiRequestBody]. Unlike
 * Anthropic's block-based streaming, Gemini's `generateContent` SSE carries grounding info on
 * `candidates[0].groundingMetadata` — often only on a later or final chunk, not every chunk — as
 * either a `groundingChunks` array (current API) or the older `groundingAttributions` array
 * (earlier API versions this app may still be pointed at, since `CloudProvider.baseUrl`/model are
 * user-typed free text), each item nesting a `web` object with `uri`/`title` (some versions use
 * `url` instead of `uri` — checked defensively). Any chunk without a `groundingMetadata` object at
 * all returns `null` ("not a sources event"); a `groundingMetadata` object present but whose
 * chunks/attributions are missing or malformed returns an **empty list** rather than throwing —
 * [CloudEngine]'s own `runCatching` around this call is a last-resort net, not the first line of
 * defense.
 */
internal fun geminiSourcesOf(type: String?, data: String): List<Source>? {
    val candidates = JSONObject(data).optJSONArray("candidates") ?: return null
    if (candidates.length() == 0) return null
    val grounding = candidates.optJSONObject(0)?.optJSONObject("groundingMetadata") ?: return null

    val items = grounding.optJSONArray("groundingChunks")
        ?: grounding.optJSONArray("groundingAttributions")
        ?: return emptyList()

    val results = mutableListOf<Source>()
    for (i in 0 until items.length()) {
        val web = items.optJSONObject(i)?.optJSONObject("web") ?: continue
        val url = web.optString("uri").orEmptyToNull() ?: web.optString("url").orEmptyToNull() ?: continue
        val title = web.optString("title").orEmptyToNull() ?: url
        results.add(Source(title = title, url = url))
    }
    return results
}

/**
 * Pure JSON-body builder for the Gemini `generateContent` request, factored out
 * of [GeminiEngine.buildRequest] so it's unit-testable without the OkHttp
 * [Request] wrapper (`buildRequest` is `protected` on [CloudEngine] and
 * unreachable from a JVM test that isn't a subclass). `generationConfig` is
 * always sent with `maxOutputTokens` set — previously the body omitted any
 * output-token cap entirely and every reply relied on the server default;
 * `temperature`/`topP` are added into that same object (not a duplicate one)
 * only when the provider kind supports sampling knobs.
 *
 * [supportsVision] gates the W1 vision `parts` shape (see [partsFor]); defaults
 * to `false` so pre-W1 call sites keep building the single-text-part shape
 * unchanged. [webSearchEnabled] gates the W2 server-side `google_search`
 * grounding tool the same way — defaults to `false` so pre-W2 call sites (and
 * any turn that didn't opt in) build a request byte-identical to today's,
 * `tools` field and all.
 */
internal fun buildGeminiRequestBody(
    messages: List<MessageNode>,
    params: SamplingParams,
    supportsSampling: Boolean,
    supportsVision: Boolean = false,
    webSearchEnabled: Boolean = false,
): JSONObject {
    val contents = JSONArray()
    for (m in messages) {
        if (m.role == Role.SYSTEM) continue
        val role = if (m.role == Role.ASSISTANT) "model" else "user"
        contents.put(
            JSONObject()
                .put("role", role)
                .put("parts", partsFor(m, supportsVision)),
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

    val generationConfig = JSONObject().put("maxOutputTokens", params.maxTokens)
    if (supportsSampling) {
        generationConfig
            .put("temperature", params.temperature.toDouble())
            .put("topP", params.topP.toDouble())
    }
    body.put("generationConfig", generationConfig)

    if (webSearchEnabled) {
        body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
    }

    return body
}

/**
 * A message's Gemini `parts` array (daily-driver.md W1). A single
 * `{"text":...}` part — today's shape — unless [supportsVision] is true and
 * the message carries [Conversations.ATTACHMENTS_KEY]: then `inline_data`
 * parts (one per readable attachment, in attachment order) precede exactly one
 * trailing `{"text":...}` part. Byte-identical to the single-text-part shape
 * when there's nothing to add — prompt-cache friendliness (plan §W1).
 *
 * A vision-blind model or an attachment whose file can't be read falls back to
 * the single-text-part shape rather than being stripped-with-error: the
 * user-facing refusal for a *current* send on a blind model is handled at send
 * time (ChatViewModel), not here. This engine-level fallback only covers older
 * history — e.g. the model changed mid-conversation — where silent omission is
 * the right behaviour for a message that already happened.
 */
private fun partsFor(message: MessageNode, supportsVision: Boolean): JSONArray {
    val textOnly = JSONArray().put(JSONObject().put("text", message.content))
    if (!supportsVision) return textOnly
    val attachments = Attachments.decode(message.metadata[Conversations.ATTACHMENTS_KEY])
    if (attachments.isEmpty()) return textOnly

    val parts = JSONArray()
    for (a in attachments) {
        val bytes = runCatching { File(a.path).readBytes() }.getOrNull() ?: continue
        parts.put(
            JSONObject().put(
                "inline_data",
                JSONObject()
                    .put("mime_type", a.mime)
                    .put("data", Base64.getEncoder().encodeToString(bytes)),
            ),
        )
    }
    if (parts.length() == 0) return textOnly // every attachment unreadable — fall back
    parts.put(JSONObject().put("text", message.content))
    return parts
}
