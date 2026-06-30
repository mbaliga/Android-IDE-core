package dev.aarso.inference

import dev.aarso.domain.GeneratedToken
import dev.aarso.domain.MessageNode
import dev.aarso.domain.SamplingParams
import kotlinx.coroutines.flow.Flow

/**
 * A loadable, streaming text model. The conversation is just text on a path, so
 * any engine can continue from any node (handoff §3) — which is what makes
 * mid-conversation model switching possible in later phases.
 *
 * The abstraction admits both local engines (llama.cpp, with logprobs) and, in
 * Phase 4, the cloud Claude voice (no logprobs, no sampling knobs). Capabilities
 * that not every engine has are surfaced as flags rather than assumed.
 */
interface InferenceEngine {

    /** Stable id of this engine's tokenizer, for per-tokenizer counts (§2). */
    val tokenizerId: String

    /** Whether per-token logprobs/entropy are available (local engines: yes). */
    val supportsLogprobs: Boolean

    /** Whether sampling params apply (Claude rejects them — see §3). */
    val supportsSamplingParams: Boolean

    val isLoaded: Boolean

    suspend fun loadModel(modelPath: String, contextSize: Int = DEFAULT_CONTEXT)

    suspend fun unload()

    /**
     * Count tokens for [text] under this engine's tokenizer. Counts differ
     * across models, hence per-tokenizer storage (§2).
     */
    suspend fun countTokens(text: String): Int

    /**
     * Stream a completion for the given conversation path. Implementations
     * re-render [messages] with the model's own chat template before generating
     * (§3). [GeneratedToken.logprob]/[GeneratedToken.entropy] are populated when
     * [supportsLogprobs] is true.
     */
    fun generate(
        messages: List<MessageNode>,
        params: SamplingParams,
        /** KV-cache snapshots (§8.3): restore from / save to these session files.
         *  Honoured only by the local engine; others ignore them. */
        sessionLoadPath: String? = null,
        sessionSavePath: String? = null,
    ): Flow<GeneratedToken>

    companion object {
        const val DEFAULT_CONTEXT = 4096
    }
}
