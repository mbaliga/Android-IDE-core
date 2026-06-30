package dev.aarso.domain

/**
 * One streamed token from a local inference engine.
 *
 * [logprob] and [entropy] are carried from day one even though the Phase 0 UI
 * ignores them: per-token log-probabilities are the entire reason llama.cpp was
 * chosen over Ollama (handoff §3), and they feed the token-quality instrumentation
 * (entropy/confidence coloring, base-vs-instruct diff) in later phases (§5a).
 *
 * Both are nullable because they are unavailable on cloud (Claude) turns — the
 * Anthropic Messages API does not return logprobs (§3) — and may be absent if a
 * local engine is configured not to emit them.
 */
data class GeneratedToken(
    val text: String,
    val logprob: Float? = null,
    val entropy: Float? = null,
)
