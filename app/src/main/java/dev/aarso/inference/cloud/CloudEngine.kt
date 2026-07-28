package dev.aarso.inference.cloud

import dev.aarso.BuildConfig
import dev.aarso.domain.GeneratedToken
import dev.aarso.domain.MessageNode
import dev.aarso.domain.SamplingParams
import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.cloud.Source
import dev.aarso.domain.cost.UsageAccumulator
import dev.aarso.domain.cost.UsageReport
import dev.aarso.inference.InferenceEngine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Base for cloud-backed engines. Streams tokens over Server-Sent Events; provider
 * specifics (request shape, how a delta is pulled from an event) are the subclass's
 * job.
 *
 * Cloud turns carry **no logprobs** (handoff §3), so [supportsLogprobs] is false
 * and [GeneratedToken.entropy] is always null — the token-internals layer goes
 * dark, which is itself honest data the self-observation tool surfaces (§5c).
 * Token counts are a coarse chars/4 estimate since the provider exposes no
 * tokenizer.
 */
abstract class CloudEngine(
    protected val provider: CloudProvider,
    protected val apiKey: String,
) : InferenceEngine {

    override val tokenizerId: String = "cloud:${provider.model}"
    override val supportsLogprobs: Boolean = false
    override val supportsSamplingParams: Boolean = provider.kind.supportsSampling
    override val isLoaded: Boolean = true

    override suspend fun loadModel(modelPath: String, contextSize: Int) = Unit
    override suspend fun unload() = Unit

    /** Coarse estimate (~4 chars/token); the provider gives no tokenizer. */
    override suspend fun countTokens(text: String): Int =
        ceil(text.length / 4.0).toInt()

    protected val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // streaming: no read timeout
        .build()

    /**
     * The usage reported by the most recent [generate] stream (Cost epic, P1). Provider-reported
     * token counts captured live from the SSE events via [usageOf]; [UsageReport.ZERO] until a
     * turn reports any. Surfacing this into the per-turn Cost display is the runtime follow-on.
     */
    @Volatile
    var lastUsage: UsageReport = UsageReport.ZERO
        private set

    /**
     * Parse provider-reported usage out of one SSE event, or null if it carries none. Subclasses
     * override per their response shape (e.g. `ProviderUsage.fromAnthropic`). Default: no capture.
     */
    protected open fun usageOf(type: String?, data: String): UsageReport? = null

    /**
     * The sources a server-side web search tool surfaced during the most recent [generate]
     * stream (W2). Provider-reported, captured live from the SSE events via [sourcesOf]; empty
     * until a turn reports any, reset at the start of every [generate] call.
     */
    @Volatile
    var lastSources: List<Source> = emptyList()
        private set

    /**
     * Parse zero or more search-result sources out of one SSE event, or null if it carries none
     * (i.e. this event isn't a sources-bearing one at all). Subclasses override per their
     * response shape (e.g. Anthropic's `content_block_start` / `web_search_tool_result`).
     * Default: no capture.
     */
    protected open fun sourcesOf(type: String?, data: String): List<Source>? = null

    /**
     * Whether the most recent [generate] stream was paused by the provider mid-turn (W2 — e.g.
     * Anthropic's server-side search loop hitting its cap, `stop_reason:"pause_turn"`). Reset at
     * the start of every [generate] call; latched true the moment [isPaused] reports it once.
     */
    @Volatile
    var wasPaused: Boolean = false
        private set

    /**
     * True when this SSE event marks a provider-side pause (not a normal end-of-stream).
     * Subclasses override per their response shape. Default: never paused.
     */
    protected open fun isPaused(type: String?, data: String): Boolean = false

    override fun generate(
        messages: List<MessageNode>,
        params: SamplingParams,
        sessionLoadPath: String?,
        sessionSavePath: String?,
    ): Flow<GeneratedToken> = callbackFlow {
        val request = buildRequest(messages, params)
        val usage = UsageAccumulator()
        lastUsage = UsageReport.ZERO
        val sources = mutableListOf<Source>()
        lastSources = emptyList()
        wasPaused = false
        val listener = object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                runCatching { usageOf(type, data) }
                    .onFailure { logSseParseFailure("usageOf", type, it) }
                    .getOrNull()?.let {
                        usage.merge(it); lastUsage = usage.current
                    }
                runCatching { sourcesOf(type, data) }
                    .onFailure { logSseParseFailure("sourcesOf", type, it) }
                    .getOrNull()?.let { found ->
                        mergeSourcesByUrl(sources, found)
                        lastSources = sources.toList()
                    }
                if (runCatching { isPaused(type, data) }
                        .onFailure { logSseParseFailure("isPaused", type, it) }
                        .getOrDefault(false)
                ) {
                    wasPaused = true
                }
                if (isDone(type, data)) {
                    close()
                    return
                }
                val text = runCatching { parseDelta(type, data) }
                    .onFailure { logSseParseFailure("parseDelta", type, it) }
                    .getOrNull()
                if (!text.isNullOrEmpty()) trySend(GeneratedToken(text))
            }

            override fun onClosed(es: EventSource) {
                close()
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                val detail = response?.let { r ->
                    runCatching { r.body?.string() }.getOrNull()?.take(500)
                }
                close(t ?: IllegalStateException("Cloud request failed: ${response?.code} $detail"))
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    protected abstract fun buildRequest(messages: List<MessageNode>, params: SamplingParams): Request

    /** True when this event marks end-of-stream. Default: OpenAI's "[DONE]". */
    protected open fun isDone(type: String?, data: String): Boolean = data.trim() == "[DONE]"

    /** Extract the text chunk from one SSE event, or null if it carries none. */
    protected abstract fun parseDelta(type: String?, data: String): String?

    /**
     * Debug-only visibility into a swallowed SSE parse failure — [usageOf] and [parseDelta]
     * stay tolerant of malformed/unexpected provider events (still return null, still never
     * crash or surface to the user), but silently eating every exception made provider drift
     * indistinguishable from a quiet stream. Release builds stay silent.
     */
    private fun logSseParseFailure(fn: String, type: String?, t: Throwable) {
        if (BuildConfig.DEBUG) {
            android.util.Log.w("Fonebrew", "CloudEngine.$fn failed on event type=$type: $t")
        }
    }
}

/**
 * Append [found] onto [into] in place, skipping any source whose `url` already appears in
 * [into] (from an earlier SSE event on the same turn). A provider (Gemini's grounding metadata
 * is documented as arriving cumulatively across chunks) can re-report the same source on more
 * than one event; without this, [CloudEngine.lastSources] — and the sources footer it feeds —
 * would show duplicate rows for one result. Factored out of [CloudEngine.generate] so the merge
 * behaviour is unit-testable without standing up an SSE stream.
 */
internal fun mergeSourcesByUrl(into: MutableList<Source>, found: List<Source>) {
    val existingUrls = into.mapTo(mutableSetOf()) { it.url }
    for (s in found) {
        if (existingUrls.add(s.url)) into.add(s)
    }
}
