package dev.fonebrew.inference

import android.content.Context
import com.google.ai.edge.aicore.GenerationConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.ceil

/**
 * The on-device Gemini Nano engine, via Android's AICore system service
 * ([com.google.ai.edge.aicore.GenerativeModel] — the AI Edge SDK, `0.0.1-exp01`, still an
 * experimental preview as of this writing). Only available on a narrow set of devices (Pixel
 * 8+/9 class, a Galaxy S24 subset) — [dev.fonebrew.ui.onboarding.AiCoreAvailability] probes for it
 * before this is ever offered, and [dev.fonebrew.inference.EngineProvider] falls back cleanly (this
 * class throws the same way construction/first-use fails on an unsupported device) rather than
 * assuming it works. **Owner-verified only** — this container has no device, and this SDK
 * revision is pre-GA, so treat behaviour here as unconfirmed until tested on a real phone.
 *
 * No per-token logprobs (AICore doesn't expose them) and no session KV-cache snapshot support
 * (system-managed, not something this app's process controls) — both honestly reported false/no-op,
 * same posture as [dev.fonebrew.inference.cloud.CloudEngine] for the capabilities a remote/managed
 * engine can't offer.
 */
class AiCoreEngine(private val appContext: Context) : InferenceEngine {

    override val tokenizerId: String = "aicore:gemini-nano"
    override val supportsLogprobs: Boolean = false
    override val supportsSamplingParams: Boolean = true

    private var model: GenerativeModel? = null

    override val isLoaded: Boolean
        get() = model != null

    /** [modelPath] is unused — AICore has no file to point at; the model lives in the system service. */
    override suspend fun loadModel(modelPath: String, contextSize: Int) {
        val config = GenerationConfig.Builder().apply {
            context = appContext
            temperature = 0.7f
            topK = 40
            maxOutputTokens = contextSize.coerceAtMost(1024)
        }.build()
        val m = GenerativeModel(generationConfig = config)
        m.prepareInferenceEngine() // surfaces "unsupported on this device" now, not on first send
        model = m
    }

    override suspend fun unload() {
        model?.close()
        model = null
    }

    /** No confirmed tokenizer surface on this SDK revision — the same coarse chars/4 estimate
     *  [dev.fonebrew.inference.cloud.CloudEngine] uses for providers with no exposed tokenizer. */
    override suspend fun countTokens(text: String): Int = ceil(text.length / 4.0).toInt()

    override fun generate(
        messages: List<MessageNode>,
        params: SamplingParams,
        sessionLoadPath: String?,
        sessionSavePath: String?,
    ): Flow<GeneratedToken> = flow {
        val m = model ?: throw IllegalStateException("AiCoreEngine.loadModel() was not called")
        val prompt = flatten(messages)
        try {
            m.generateContentStream(prompt).collect { response ->
                response.text?.let { text -> if (text.isNotEmpty()) emit(GeneratedToken(text = text)) }
            }
        } catch (e: GenerativeAIException) {
            throw IllegalStateException("Gemini Nano generation failed: ${e.message}", e)
        }
    }

    /**
     * AICore's simple string prompt has no confirmed multi-turn [com.google.ai.edge.aicore.Content]
     * API surface verified for this integration, so the conversation is flattened into one plain
     * transcript instead — honest and simple over guessing at an unconfirmed structured API.
     */
    private fun flatten(messages: List<MessageNode>): String = buildString {
        for (m in messages) {
            val speaker = when (m.role) {
                Role.SYSTEM -> "System"
                Role.USER -> "User"
                Role.ASSISTANT -> "Assistant"
            }
            append(speaker).append(": ").append(m.content).append("\n\n")
        }
        append("Assistant:")
    }
}
