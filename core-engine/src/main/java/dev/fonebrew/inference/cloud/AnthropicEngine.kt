package dev.fonebrew.inference.cloud

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.cloud.CloudProvider
import dev.fonebrew.domain.cloud.Source
import dev.fonebrew.domain.tree.Attachments
import dev.fonebrew.domain.tree.Conversations
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
 *
 * W2 (web search): [webSearchEnabled] is a **per-turn** opt-in (the composer's
 * globe chip — `daily-driver.md` W2), not a provider-instance property like
 * [CloudProvider.supportsVision], so it's a constructor param here rather than
 * read off [provider]. Defaults to `false` so every existing construction site
 * (`CloudEngineFactory`) keeps building byte-identical requests unless a caller
 * explicitly opts a turn in.
 */
class AnthropicEngine(
    provider: CloudProvider,
    apiKey: String,
    private val webSearchEnabled: Boolean = false,
) : CloudEngine(provider, apiKey) {

    override fun buildRequest(messages: List<MessageNode>, params: SamplingParams): Request {
        val body = buildAnthropicRequestBody(
            messages,
            params,
            provider.model,
            provider.supportsVision,
            webSearchEnabled,
        )

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
    override fun usageOf(type: String?, data: String): dev.fonebrew.domain.cost.UsageReport? =
        dev.fonebrew.domain.cost.ProviderUsage.fromAnthropic(data)

    // Delegate to the top-level pure parsers below — same reason buildRequest delegates to
    // buildAnthropicRequestBody: these overrides are `protected` on CloudEngine and unreachable
    // from a JVM test that isn't a subclass; the free functions are unit-testable directly.
    override fun sourcesOf(type: String?, data: String): List<Source>? = anthropicSourcesOf(type, data)

    override fun isPaused(type: String?, data: String): Boolean = anthropicIsPaused(type, data)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Anthropic's server-side web search tool type (W2, `daily-driver.md` §Decision register 5).
 * Verified against Anthropic's own docs (`platform.claude.com/docs/en/agents-and-tools/tool-use/
 * web-search-tool`, fetched during the W2 audit): three real, currently-live tool versions exist
 * — `web_search_20250305` (basic), `web_search_20260209` (adds dynamic filtering, used here),
 * and a newer `web_search_20260318` (adds `response_inclusion` for agentic workflows, not wired
 * up — switching would mean picking up a request-shape change this pass doesn't need). Older
 * user-typed model ids (`SettingsRoom.kt` `ProviderForm`) may still 400 on any of these — that
 * surfaces through the existing error banner rather than being silently downgraded (binding
 * rule: cloud errors are visible, never swallowed). [ANTHROPIC_WEB_SEARCH_TOOL_LEGACY] is
 * documented here for the record; nothing wires it up yet.
 */
internal const val ANTHROPIC_WEB_SEARCH_TOOL = "web_search_20260209"
internal const val ANTHROPIC_WEB_SEARCH_TOOL_LEGACY = "web_search_20250305"

/**
 * Pure parser for server-side web search results (W2), factored out of
 * [AnthropicEngine.sourcesOf] for the same testability reason as
 * [buildAnthropicRequestBody]. Anthropic delivers them on a `content_block_start` event whose
 * `content_block.type` is `"web_search_tool_result"`; that block nests a `content` array of
 * items each carrying `url`/`title` (plus provider extras we don't model, e.g.
 * `encrypted_content`, `page_age`). Defensive throughout: any event that isn't this exact shape
 * returns `null` ("not a sources event"); a matching block whose nested `content` is
 * missing/malformed returns an **empty list** rather than throwing — [CloudEngine]'s own
 * `runCatching` around this call is a last-resort net, not the first line of defense.
 */
internal fun anthropicSourcesOf(type: String?, data: String): List<Source>? {
    if (type != "content_block_start") return null
    val block = JSONObject(data).optJSONObject("content_block") ?: return null
    if (block.optString("type") != "web_search_tool_result") return null

    val items = block.optJSONArray("content") ?: return emptyList()
    val results = mutableListOf<Source>()
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val url = item.optString("url").orEmptyToNull() ?: continue
        val title = item.optString("title").orEmptyToNull() ?: url
        results.add(Source(title = title, url = url))
    }
    return results
}

/**
 * Pure parser for the W2 server-side-search pause signal, factored out of
 * [AnthropicEngine.isPaused] for the same testability reason as [buildAnthropicRequestBody]. The
 * search loop hitting its cap mid-turn is reported on a `message_delta` event via
 * `delta.stop_reason == "pause_turn"` — distinct from a normal `"end_turn"`/`"max_tokens"` stop,
 * which is *not* a pause.
 */
internal fun anthropicIsPaused(type: String?, data: String): Boolean {
    if (type != "message_delta") return false
    val stopReason = JSONObject(data).optJSONObject("delta")?.optString("stop_reason")
    return stopReason == "pause_turn"
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
 * unchanged. [webSearchEnabled] gates the W2 server-side search tool the same
 * way — defaults to `false` so pre-W2 call sites (and any turn that didn't opt
 * in) build a request byte-identical to today's, `tools` field and all, which
 * matters for prompt-cache friendliness (same principle as the W1 vision
 * fallback).
 */
internal fun buildAnthropicRequestBody(
    messages: List<MessageNode>,
    params: SamplingParams,
    model: String,
    supportsVision: Boolean = false,
    webSearchEnabled: Boolean = false,
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
    if (webSearchEnabled) {
        body.put(
            "tools",
            JSONArray().put(
                JSONObject()
                    .put("type", ANTHROPIC_WEB_SEARCH_TOOL)
                    .put("name", "web_search")
                    .put("max_uses", 3),
            ),
        )
    }
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
