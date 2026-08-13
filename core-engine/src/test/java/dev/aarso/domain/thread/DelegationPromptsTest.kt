package dev.aarso.domain.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationPromptsTest {

    @Test fun `system prompt asks for a bare number, no explanation`() {
        val sys = DelegationPrompts.chooseSystemPrompt()
        assertTrue(sys.contains("number"))
        assertTrue(sys.contains("No explanation"))
    }

    @Test fun `user prompt numbers each alternative starting at 1`() {
        val prompt = DelegationPrompts.chooseUserPrompt("obj", listOf("first option", "second option"))
        assertTrue(prompt.contains("1. first option"))
        assertTrue(prompt.contains("2. second option"))
    }

    @Test fun `user prompt omits the conversation header when objective is blank`() {
        val prompt = DelegationPrompts.chooseUserPrompt("", listOf("a"))
        assertTrue(!prompt.contains("Conversation so far"))
    }

    @Test fun `parseChoice reads a bare digit`() {
        assertEquals(0, DelegationPrompts.parseChoice("1", count = 3))
        assertEquals(2, DelegationPrompts.parseChoice("3", count = 3))
    }

    @Test fun `parseChoice reads a digit embedded in prose`() {
        assertEquals(1, DelegationPrompts.parseChoice("I'd go with option 2, it's stronger.", count = 3))
    }

    @Test fun `parseChoice returns null for an out-of-range number`() {
        assertNull(DelegationPrompts.parseChoice("5", count = 3))
        assertNull(DelegationPrompts.parseChoice("0", count = 3))
    }

    @Test fun `parseChoice returns null when there is no digit at all`() {
        assertNull(DelegationPrompts.parseChoice("the first one", count = 3))
    }

    @Test fun `parseChoice returns null for count zero or negative, never divides by it`() {
        assertNull(DelegationPrompts.parseChoice("1", count = 0))
        assertNull(DelegationPrompts.parseChoice("1", count = -1))
    }

    @Test fun `parseChoice takes the first digit run when several are present`() {
        // Honest best-effort, not a hard requirement of the surface — documents actual behavior.
        assertEquals(0, DelegationPrompts.parseChoice("1 or maybe 2", count = 3))
    }
}
