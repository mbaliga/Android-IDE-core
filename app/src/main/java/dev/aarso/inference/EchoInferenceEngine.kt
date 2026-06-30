package dev.aarso.inference

import dev.aarso.domain.GeneratedToken
import dev.aarso.domain.MessageNode
import dev.aarso.domain.SamplingParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A dev-only stand-in [InferenceEngine] used so the Phase 0 chat loop (tree write
 * → stream → tree write → embedding log) is demonstrable WITHOUT the native
 * llama.cpp library, which is not built in this scaffold.
 *
 * HONEST LIMITATION: this is not a model. It streams a canned reply that echoes
 * the last user turn, token-by-token, with no logprobs. The real path is
 * [LlamaCppEngine]; this exists only to exercise the plumbing and is expected to
 * be removed (or kept behind a debug flag) once the native engine runs.
 */
class EchoInferenceEngine : InferenceEngine {

    override val tokenizerId: String = "echo-dev"
    override val supportsLogprobs: Boolean = false
    override val supportsSamplingParams: Boolean = false

    override var isLoaded: Boolean = false
        private set

    override suspend fun loadModel(modelPath: String, contextSize: Int) {
        isLoaded = true
    }

    override suspend fun unload() {
        isLoaded = false
    }

    /** Whitespace tokens — crude, but enough for per-tokenizer count plumbing. */
    override suspend fun countTokens(text: String): Int =
        if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size

    override fun generate(
        messages: List<MessageNode>,
        params: SamplingParams,
        sessionLoadPath: String?,
        sessionSavePath: String?,
    ): Flow<GeneratedToken> = flow {
        val lastUser = messages.lastOrNull()?.content.orEmpty()
        val reply = "echo: $lastUser"
        for (word in reply.split(" ")) {
            delay(40)
            emit(GeneratedToken(text = "$word "))
        }
    }
}
