package dev.fonebrew.domain.terminal

import dev.fonebrew.domain.remote.term.TerminalModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Expected strings are written out literally from the public xterm control-sequence tables,
 *  not computed with the encoder's own helpers, so a wrong encoder cannot agree with them. */
class KeyEncoderTest {
    private val normal = TerminalModes()
    private val app = TerminalModes().also { it.applicationCursorKeys = true; it.applicationKeypad = true }
    private fun enc(k: NamedKey, mods: Int = 0, m: TerminalModes = normal) =
        KeyEncoder.encode(KeyEvent(key = k, modifiers = mods), m)!!.toString(Charsets.UTF_8)
    private fun txt(t: String, mods: Int = 0) = KeyEncoder.encode(KeyEvent(text = t, modifiers = mods), normal)!!.toString(Charsets.UTF_8)

    @Test fun `cursor keys normal and application`() {
        assertEquals("\u001B[A", enc(NamedKey.UP)); assertEquals("\u001B[B", enc(NamedKey.DOWN))
        assertEquals("\u001B[C", enc(NamedKey.RIGHT)); assertEquals("\u001B[D", enc(NamedKey.LEFT))
        assertEquals("\u001B[H", enc(NamedKey.HOME)); assertEquals("\u001B[F", enc(NamedKey.END))
        assertEquals("\u001BOA", enc(NamedKey.UP, m = app)); assertEquals("\u001BOD", enc(NamedKey.LEFT, m = app))
        assertEquals("\u001BOH", enc(NamedKey.HOME, m = app)); assertEquals("\u001BOF", enc(NamedKey.END, m = app))
    }

    @Test fun `modified cursor keys use the CSI 1 m form even in application mode`() {
        assertEquals("\u001B[1;5A", enc(NamedKey.UP, Mod.CTRL))
        assertEquals("\u001B[1;2B", enc(NamedKey.DOWN, Mod.SHIFT))
        assertEquals("\u001B[1;3C", enc(NamedKey.RIGHT, Mod.ALT))
        assertEquals("\u001B[1;4D", enc(NamedKey.LEFT, Mod.SHIFT or Mod.ALT))
        assertEquals("\u001B[1;6H", enc(NamedKey.HOME, Mod.SHIFT or Mod.CTRL, app))
        assertEquals("\u001B[1;9F", enc(NamedKey.END, Mod.META))
        assertEquals("\u001B[1;8A", enc(NamedKey.UP, Mod.SHIFT or Mod.ALT or Mod.CTRL))
    }

    @Test fun `editing keys`() {
        assertEquals("\u001B[2~", enc(NamedKey.INSERT)); assertEquals("\u001B[3~", enc(NamedKey.DELETE))
        assertEquals("\u001B[5~", enc(NamedKey.PAGE_UP)); assertEquals("\u001B[6~", enc(NamedKey.PAGE_DOWN))
        assertEquals("\u001B[3;5~", enc(NamedKey.DELETE, Mod.CTRL))
        assertEquals("\u001B[5;2~", enc(NamedKey.PAGE_UP, Mod.SHIFT))
    }

    @Test fun `function keys F1 to F12`() {
        val expect = listOf("\u001BOP", "\u001BOQ", "\u001BOR", "\u001BOS", "\u001B[15~", "\u001B[17~", "\u001B[18~",
            "\u001B[19~", "\u001B[20~", "\u001B[21~", "\u001B[23~", "\u001B[24~")
        val keys = listOf(NamedKey.F1, NamedKey.F2, NamedKey.F3, NamedKey.F4, NamedKey.F5, NamedKey.F6, NamedKey.F7,
            NamedKey.F8, NamedKey.F9, NamedKey.F10, NamedKey.F11, NamedKey.F12)
        keys.forEachIndexed { i, k -> assertEquals(k.name, expect[i], enc(k)) }
        assertEquals("\u001B[1;5P", enc(NamedKey.F1, Mod.CTRL))
        assertEquals("\u001B[1;2S", enc(NamedKey.F4, Mod.SHIFT))
        assertEquals("\u001B[15;3~", enc(NamedKey.F5, Mod.ALT))
        assertEquals("\u001B[24;6~", enc(NamedKey.F12, Mod.SHIFT or Mod.CTRL))
    }

    @Test fun `enter tab backspace escape`() {
        assertEquals("\r", enc(NamedKey.ENTER)); assertEquals("\t", enc(NamedKey.TAB))
        assertEquals("\u001B[Z", enc(NamedKey.TAB, Mod.SHIFT))
        assertEquals("\u007F", enc(NamedKey.BACKSPACE)); assertEquals("\b", enc(NamedKey.BACKSPACE, Mod.CTRL))
        assertEquals("\u001B", enc(NamedKey.ESCAPE))
        assertEquals("\u001B\r", enc(NamedKey.ENTER, Mod.ALT))
        assertEquals("\u001B\u007F", enc(NamedKey.BACKSPACE, Mod.ALT))
    }

    @Test fun `ctrl letters and punctuation`() {
        assertEquals("\u0003", txt("c", Mod.CTRL)); assertEquals("\u0003", txt("C", Mod.CTRL))
        assertEquals("\u0001", txt("a", Mod.CTRL)); assertEquals("\u001A", txt("z", Mod.CTRL))
        assertEquals("\u0000", txt(" ", Mod.CTRL)); assertEquals("\u001B", txt("[", Mod.CTRL))
        assertEquals("\u001C", txt("\\", Mod.CTRL)); assertEquals("\u001D", txt("]", Mod.CTRL))
        assertEquals("\u007F", txt("?", Mod.CTRL))
        assertEquals("5", txt("5", 0))
    }

    @Test fun `alt is an ESC prefix and ctrl-alt combines`() {
        assertEquals("\u001Bx", txt("x", Mod.ALT))
        assertEquals("\u001B\u0003", txt("c", Mod.ALT or Mod.CTRL))
    }

    @Test fun `printable text including non-ascii passes through as UTF-8`() {
        assertEquals("h", txt("h")); assertEquals("\u00e9", txt("\u00e9")); assertEquals("\uD83D\uDE00", txt("\uD83D\uDE00"))
        assertNull(KeyEncoder.encode(KeyEvent(text = ""), normal))
    }

    @Test fun `keypad plain and application`() {
        assertEquals("5", enc(NamedKey.KP_5)); assertEquals("\r", enc(NamedKey.KP_ENTER))
        assertEquals("\u001BOu", enc(NamedKey.KP_5, m = app)); assertEquals("\u001BOM", enc(NamedKey.KP_ENTER, m = app))
        assertEquals("\u001BOp", enc(NamedKey.KP_0, m = app)); assertEquals("\u001BOy", enc(NamedKey.KP_9, m = app))
        assertEquals("\u001BOk", enc(NamedKey.KP_PLUS, m = app)); assertEquals("\u001BOm", enc(NamedKey.KP_MINUS, m = app))
    }
}
