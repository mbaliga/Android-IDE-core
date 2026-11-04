package dev.fonebrew.inference

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.curation.CompactionAgent
import dev.fonebrew.domain.curation.MessageFate
import dev.fonebrew.domain.curation.ResolvedFidelity
import kotlinx.coroutines.flow.collect

/**
 * The model-call half of the Compaction Contract — [dev.fonebrew.domain.curation.CompactionEngine]'s
 * own KDoc names this split explicitly: *"generating gist/faithful text is a model call through
 * the existing [InferenceEngine] machinery... never faked here."* Wraps any already-loadable
 * [InferenceEngine] to produce fate-appropriate compacted text for the three fates
 * [dev.fonebrew.domain.curation.CompactionEngine.run] actually calls an agent for.
 *
 * The caller is responsible for [InferenceEngine.loadModel] before running a compaction batch —
 * this class issues one `generate` call per message and never loads/unloads itself, so a caller
 * compacting many messages in one run only pays the load cost once (same division of
 * responsibility [dev.fonebrew.ui.ChatViewModel.runTurn] already uses for the main chat loop).
 */
class EngineCompactionAgent(private val engine: InferenceEngine) : CompactionAgent {

    override suspend fun compact(original: MessageNode, fate: MessageFate, resolution: ResolvedFidelity): String {
        val instruction = instructionFor(fate)
        val now = System.currentTimeMillis()
        val sysId = "compact-sys-${original.id}"
        val messages = listOf(
            MessageNode(sysId, null, Role.SYSTEM, instruction, createdAt = now),
            MessageNode("compact-usr-${original.id}", sysId, Role.USER, original.content, createdAt = now + 1),
        )
        val sb = StringBuilder()
        engine.generate(messages, SamplingParams()).collect { sb.append(it.text) }
        return sb.toString().trim().ifBlank { "$FALLBACK_PREFIX${fate.name}]" }
    }

    /**
     * @throws IllegalStateException for [MessageFate.KEPT_VERBATIM]/[MessageFate.DROPPED] —
     *   [dev.fonebrew.domain.curation.CompactionEngine.run] never calls [compact] for either (it
     *   keeps F3 verbatim itself and never asks for text it's about to discard), so reaching this
     *   branch means a caller bypassed that contract.
     */
    private fun instructionFor(fate: MessageFate): String = when (fate) {
        MessageFate.GIST ->
            "Compress the message below to ONE short line that preserves its point. Output ONLY that line, no preamble."
        MessageFate.FAITHFUL ->
            "Paraphrase the message below, preserving every detail, name, and number exactly. Output ONLY the paraphrase, no preamble."
        MessageFate.TOMBSTONE ->
            "The message below was marked wrong by the user. Output ONLY a one-line failure note " +
                "describing what was tried and that it failed — never a summary that could read as an endorsement."
        MessageFate.KEPT_VERBATIM, MessageFate.DROPPED ->
            error(
                "EngineCompactionAgent.compact must never be called for $fate — " +
                    "CompactionEngine.run handles both directly and never asks the agent.",
            )
    }

    private companion object {
        const val FALLBACK_PREFIX = "[compaction produced no text — fate: "
    }
}
