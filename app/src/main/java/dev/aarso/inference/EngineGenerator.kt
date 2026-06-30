package dev.aarso.inference

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.SamplingParams
import dev.aarso.domain.council.Generator
import kotlinx.coroutines.flow.collect
import java.util.UUID

/**
 * Adapts an [InferenceEngine] (on-device llama.cpp or a watched cloud provider) to
 * the workflow engine's [Generator] — the bridge that lets the council/workflow loop
 * (docs/design/council-workflows.md) run on real models. A node = {system, user};
 * we collect the streamed tokens into the completion text.
 *
 * [modelPath] is the GGUF path for a local engine (ignored by cloud/echo). Loading is
 * idempotent (one resident local model per device).
 */
class EngineGenerator(
    private val engine: InferenceEngine,
    private val modelPath: String?,
) : Generator {

    override suspend fun complete(system: String, user: String): String {
        if (!engine.isLoaded) engine.loadModel(modelPath ?: "")
        val messages = buildList {
            if (system.isNotBlank()) add(node(Role.SYSTEM, system))
            add(node(Role.USER, user))
        }
        val sb = StringBuilder()
        engine.generate(messages, SamplingParams()).collect { sb.append(it.text) }
        return sb.toString().trim()
    }

    private fun node(role: Role, content: String) = MessageNode(
        id = UUID.randomUUID().toString(),
        parentId = null,
        role = role,
        content = content,
        modelId = null,
        createdAt = System.currentTimeMillis(),
    )
}
