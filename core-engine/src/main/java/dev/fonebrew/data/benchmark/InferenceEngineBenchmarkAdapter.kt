package dev.fonebrew.data.benchmark

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.benchmark.BenchmarkEngine
import dev.fonebrew.inference.InferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Binds the pure runner to a real engine. Fixed seed and zero temperature: same work every run. */
class InferenceEngineBenchmarkAdapter(private val engine: InferenceEngine) : BenchmarkEngine {
    override val tokenizerId: String get() = engine.tokenizerId
    override suspend fun loadModel(modelPath: String, contextSize: Int) = engine.loadModel(modelPath, contextSize)
    override suspend fun unload() = engine.unload()
    override suspend fun countTokens(text: String): Int = engine.countTokens(text)

    override fun generate(promptText: String, maxTokens: Int, seed: Long): Flow<Unit> {
        val messages = listOf(
            MessageNode(id = "bench", parentId = null, role = Role.USER, content = promptText, createdAt = 0L),
        )
        val params = SamplingParams(temperature = 0f, topK = 1, maxTokens = maxTokens, seed = seed)
        return engine.generate(messages, params).map { }
    }
}
