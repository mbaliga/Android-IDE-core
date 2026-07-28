package dev.aarso.domain.template

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role

/**
 * Renders a conversation path into the flat prompt string a model expects. This
 * is the per-model chat template that must be re-applied when switching models
 * mid-conversation (handoff §3): the same tree path produces different prompt
 * text under different templates. Pure, so it is unit-testable on the JVM.
 */
fun interface ChatTemplate {
    fun render(messages: List<MessageNode>): String
}

enum class TemplateId { CHATML, LLAMA3, PLAIN }

object ChatTemplates {

    fun forId(id: TemplateId): ChatTemplate = when (id) {
        TemplateId.CHATML -> ChatMl
        TemplateId.LLAMA3 -> Llama3
        TemplateId.PLAIN -> Plain
    }

    /** ChatML (Qwen, many others): <|im_start|>role\n…<|im_end|>. */
    val ChatMl = ChatTemplate { messages ->
        buildString {
            for (m in messages) {
                append("<|im_start|>").append(m.role.wire).append('\n')
                append(m.content).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    /** Llama 3 header format. */
    val Llama3 = ChatTemplate { messages ->
        buildString {
            append("<|begin_of_text|>")
            for (m in messages) {
                append("<|start_header_id|>").append(m.role.wire).append("<|end_header_id|>\n\n")
                append(m.content).append("<|eot_id|>")
            }
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }
    }

    /** Plain "role: content" — a readable fallback. */
    val Plain = ChatTemplate { messages ->
        buildString {
            for (m in messages) append(m.role.wire).append(": ").append(m.content).append('\n')
            append(Role.ASSISTANT.wire).append(": ")
        }
    }
}
