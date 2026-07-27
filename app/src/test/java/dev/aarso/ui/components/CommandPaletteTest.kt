package dev.aarso.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPaletteTest {

    private val commands = listOf(
        SlashCommand("/clear", "Clear the screen") {},
        SlashCommand("/connect host", "Switch to host") {},
    )
    private val targets = listOf(
        MentionTarget("Skeptic", "council participant", "@Skeptic "),
        MentionTarget("Optimist", "council participant", "@Optimist "),
    )

    @Test
    fun `slash matches only when input is a whole-message command token`() {
        assertEquals(listOf(commands[0]), matchSlashCommands("/cl", commands))
        assertTrue(matchSlashCommands("just chatting about /clear", commands).isEmpty())
        assertTrue(matchSlashCommands("", commands).isEmpty())
    }

    @Test
    fun `mention matches the in-progress token after the last unspaced @`() {
        assertEquals(listOf(targets[0]), matchMentions("hey @Sk", targets))
        assertTrue(matchMentions("hey @Skeptic, thanks", targets).isEmpty()) // trailing space closes it
        assertTrue(matchMentions("no at sign here", targets).isEmpty())
    }

    @Test
    fun `applyMention replaces the in-progress token with the full insertion`() {
        assertEquals("hey @Skeptic ", applyMention("hey @Sk", targets[0]))
        assertEquals("no change", applyMention("no change", targets[0])) // no @ to replace
    }

    @Test
    fun `shell escape requires a leading bang and non-empty command`() {
        assertTrue(isShellEscape("!ls -la"))
        assertTrue(isShellEscape("  !ls -la")) // leading whitespace tolerated
        assertFalse(isShellEscape("echo ! hello")) // mid-message ! is not an escape
        assertEquals("ls -la", shellEscapeCommand("!ls -la"))
        assertEquals("ls -la", shellEscapeCommand("  !ls -la"))
        assertNull(shellEscapeCommand("!"))
        assertNull(shellEscapeCommand("!   "))
        assertNull(shellEscapeCommand("not a shell command"))
    }
}
