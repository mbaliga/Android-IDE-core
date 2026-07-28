package dev.aarso.domain

/**
 * Sampling controls for a generation request.
 *
 * These apply to **local models only** by design (handoff §3): temperature,
 * top_p and top_k are rejected (HTTP 400) by Claude Opus 4.7+ via the Anthropic
 * Messages API, so the UI must never surface these knobs for cloud turns. Cloud
 * turns are steered by prompt, not by sliders.
 */
data class SamplingParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.8f,
    val topK: Int = 20,
    val minP: Float = 0.0f,
    val repeatPenalty: Float = 1.1f,
    val maxTokens: Int = 1024,
    val seed: Long? = null,
)
