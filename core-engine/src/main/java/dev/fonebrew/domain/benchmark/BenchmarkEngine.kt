package dev.fonebrew.domain.benchmark

import kotlinx.coroutines.flow.Flow

/**
 * The slice of an inference engine the benchmark needs, declared in domain/ so the runner has no
 * dependency on inference/ (the host-core JVM lane compiles domain/ alone). Android-side,
 * [dev.fonebrew.data.benchmark.InferenceEngineBenchmarkAdapter] binds it to the real
 * [dev.fonebrew.inference.InferenceEngine]; the benchmark never sees a cloud engine.
 */
interface BenchmarkEngine {
    val tokenizerId: String
    suspend fun loadModel(modelPath: String, contextSize: Int)
    suspend fun unload()

    /** The engine's own token count for [text]; throws when unavailable. */
    suspend fun countTokens(text: String): Int

    /** One token event per emitted token; greedy, fixed [seed], at most [maxTokens]. */
    fun generate(promptText: String, maxTokens: Int, seed: Long): Flow<Unit>
}
