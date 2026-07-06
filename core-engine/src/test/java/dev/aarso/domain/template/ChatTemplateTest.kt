package dev.aarso.domain.template

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTemplateTest {

    private val path = listOf(
        MessageNode("s", null, Role.SYSTEM, "be brief", createdAt = 0),
        MessageNode("u", "s", Role.USER, "hi", createdAt = 1),
    )

    @Test
    fun chatMl_wrapsEachTurnAndOpensAssistant() {
        val out = ChatTemplates.ChatMl.render(path)
        assertEquals(
            "<|im_start|>system\nbe brief<|im_end|>\n" +
                "<|im_start|>user\nhi<|im_end|>\n" +
                "<|im_start|>assistant\n",
            out,
        )
    }

    @Test
    fun llama3_usesHeadersAndEot() {
        val out = ChatTemplates.Llama3.render(path)
        assertTrue(out.startsWith("<|begin_of_text|>"))
        assertTrue(out.contains("<|start_header_id|>user<|end_header_id|>\n\nhi<|eot_id|>"))
        assertTrue(out.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    @Test
    fun plain_isReadableRoleColonContent() {
        val out = ChatTemplates.Plain.render(path)
        assertEquals("system: be brief\nuser: hi\nassistant: ", out)
    }

    @Test
    fun forId_returnsTheMatchingTemplate() {
        assertEquals(ChatTemplates.ChatMl, ChatTemplates.forId(TemplateId.CHATML))
        assertEquals(ChatTemplates.Llama3, ChatTemplates.forId(TemplateId.LLAMA3))
        assertEquals(ChatTemplates.Plain, ChatTemplates.forId(TemplateId.PLAIN))
    }

    @Test
    fun differentTemplates_yieldDifferentPrompts() {
        // The point of re-rendering on a model switch (§3): same path, different text.
        assertTrue(ChatTemplates.ChatMl.render(path) != ChatTemplates.Llama3.render(path))
    }
}
