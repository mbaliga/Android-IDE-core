package dev.aarso.inference.cloud

import dev.aarso.domain.GeneratedToken
import dev.aarso.domain.MessageNode
import dev.aarso.domain.SamplingParams
import dev.aarso.domain.cloud.CloudProvider
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

    override fun generate(
        messages: List<MessageNode>,
        params: SamplingParams,
        sessionLoadPath: String?,
        sessionSavePath: String?,
    ): Flow<GeneratedToken> = callbackFlow {
        val request = buildRequest(messages, params)
        val usage = UsageAccumulator()
        lastUsage = UsageReport.ZERO
        val listener = object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                runCatching { usageOf(type, data) }.getOrNull()?.let {
                    usage.merge(it); lastUsage = usage.current
                }
                if (isDone(type, data)) {
                    close()
                    return
                }
                val text = runCatching { parseDelta(type, data) }.getOrNull()
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
}
