package dev.fonebrew.ui.chatharness

import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.ui.ChatEngines
import dev.fonebrew.inference.InferenceEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scripted [InferenceEngine]: emits [script] token by token (logprob/entropy intact), can
 * suspend after [pauseAfter] tokens until [release] (so a test can Stop mid-stream with no
 * sleeping), and can fail on load or mid-stream. It records every call so a test can assert what
 * the real view-model actually handed the engine.
 */
internal class ScriptedEngine(
    override val tokenizerId: String = "scripted:tok",
    var script: List<GeneratedToken> = emptyList(),
    var failLoad: Throwable? = null,
    var failAfterScript: Throwable? = null,
    var pauseAfter: Int? = null,
) : InferenceEngine {
    override val supportsLogprobs = true
    override val supportsSamplingParams = true
    override var isLoaded = false
        private set

    private var gate = CompletableDeferred<Unit>()

    val loads = mutableListOf<Pair<String, Int>>()
    val prompts = mutableListOf<List<MessageNode>>()
    var generateCalls = 0
        private set

    /** Let a stream paused by [pauseAfter] continue. */
    fun release() { gate.complete(Unit) }

    override suspend fun loadModel(modelPath: String, contextSize: Int) {
        loads += modelPath to contextSize
        failLoad?.let { throw it }
        isLoaded = true
    }

    override suspend fun unload() { isLoaded = false }

    /** One token per 4 chars, rounded up — deterministic and tokenizer-independent. */
    override suspend fun countTokens(text: String): Int = (text.length + 3) / 4

    override fun generate(
        messages: List<MessageNode>,
        params: SamplingParams,
        sessionLoadPath: String?,
        sessionSavePath: String?,
    ): Flow<GeneratedToken> = flow {
        generateCalls++
        prompts += messages
        script.forEachIndexed { index, token ->
            if (pauseAfter == index) gate.await()
            emit(token)
        }
        failAfterScript?.let { throw it }
    }
}

/** Test double for the [ChatEngines] seam: maps spec ids to engines; unmapped specs are "not runnable". */
internal class FakeChatEngines : ChatEngines {
    val bySpecId = mutableMapOf<String, InferenceEngine>()
    val unavailableReasons = mutableMapOf<String, String>()
    val requestedSpecIds = mutableListOf<String>()

    override fun engineFor(spec: ModelSpec, webSearchEnabled: Boolean): InferenceEngine? {
        requestedSpecIds += spec.id
        return bySpecId[spec.id]
    }

    override fun isRunnable(spec: ModelSpec): Boolean = bySpecId.containsKey(spec.id)
    override fun unavailableReason(spec: ModelSpec): String? =
        if (isRunnable(spec)) null else unavailableReasons[spec.id] ?: "not runnable"

    override suspend fun unloadLocalModel() {}
}

internal fun toks(vararg texts: String): List<GeneratedToken> = texts.map { GeneratedToken(it) }
