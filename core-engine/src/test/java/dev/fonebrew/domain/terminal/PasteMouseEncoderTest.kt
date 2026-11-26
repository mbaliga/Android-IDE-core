package dev.fonebrew.domain.terminal

import dev.fonebrew.domain.remote.term.MouseTracking
import dev.fonebrew.domain.remote.term.TerminalModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PasteMouseEncoderTest {
    private val off = TerminalModes()
    private val bp = TerminalModes().also { it.bracketedPaste = true }
    private fun s(b: ByteArray) = b.toString(Charsets.UTF_8)

    @Test fun `plain paste normalises newlines to CR`() {
        assertEquals("a\rb\rc\rd", s(PasteEncoder.encode("a\r\nb\nc\rd", off)))
    }

    @Test fun `bracketed paste wraps`() {
        assertEquals("\u001B[200~ls\r\u001B[201~", s(PasteEncoder.encode("ls\n", bp)))
    }

    /** Critical: pasted text must not be able to close the bracket early. */
    @Test fun `an embedded paste-end marker cannot break out`() {
        val evil = "echo hi\u001B[201~; rm -rf ~\n"
        val out = s(PasteEncoder.encode(evil, bp))
        assertEquals("\u001B[200~echo hi[201~; rm -rf ~\r\u001B[201~", out)
        // exactly one end marker, and it is the final bytes
        assertEquals(1, Regex("\u001B\\[201~").findAll(out).count())
        assertEquals(out.length - 6, out.indexOf("\u001B[201~"))
    }

    @Test fun `a marker split by a removable control cannot reassemble`() {
        // ESC, then a Ctrl-C, then "[201~": removal of ESC alone is enough, nothing left to join.
        val out = PasteEncoder.sanitize("\u001B\u0003[201~")
        assertFalse(out.contains('\u001B'))
        assertEquals("[201~", out)
    }

    @Test fun `controls C1 and DEL are stripped, tab kept`() {
        assertEquals("a\tb", PasteEncoder.sanitize("a\u0003\u0004\tb\u007F\u009B"))
    }

    private val sgr = TerminalModes().also { it.mouseTracking = MouseTracking.ANY; it.mouseSgr = true }
    private val x10 = TerminalModes().also { it.mouseTracking = MouseTracking.CLICK }
    private fun ev(a: MouseAction, b: MouseButton, c: Int, r: Int, m: Int = 0) = MouseEvent(a, b, c, r, m)

    @Test fun `mouse off reports nothing`() {
        assertNull(MouseEncoder.encode(ev(MouseAction.PRESS, MouseButton.LEFT, 0, 0), off))
    }

    @Test fun `sgr press release wheel and modifiers`() {
        assertEquals("\u001B[<0;1;1M", s(MouseEncoder.encode(ev(MouseAction.PRESS, MouseButton.LEFT, 0, 0), sgr)!!))
        assertEquals("\u001B[<0;10;5m", s(MouseEncoder.encode(ev(MouseAction.RELEASE, MouseButton.LEFT, 9, 4), sgr)!!))
        assertEquals("\u001B[<2;3;4M", s(MouseEncoder.encode(ev(MouseAction.PRESS, MouseButton.RIGHT, 2, 3), sgr)!!))
        assertEquals("\u001B[<64;1;1M", s(MouseEncoder.encode(ev(MouseAction.PRESS, MouseButton.WHEEL_UP, 0, 0), sgr)!!))
        assertEquals("\u001B[<65;1;1M", s(MouseEncoder.encode(ev(MouseAction.PRESS, MouseButton.WHEEL_DOWN, 0, 0), sgr)!!))
        assertEquals("\u001B[<20;1;1M", s(MouseEncoder.encode(ev(MouseAction.PRESS, MouseButton.LEFT, 0, 0, Mod.SHIFT or Mod.CTRL), sgr)!!))
        assertEquals("\u001B[<35;2;2M", s(MouseEncoder.encode(ev(MouseAction.MOVE, MouseButton.NONE, 1, 1), sgr)!!))
        assertNull(MouseEncoder.encode(ev(MouseAction.RELEASE, MouseButton.WHEEL_UP, 0, 0), sgr))
    }

    @Test fun `click mode drops motion and drag mode needs a button`() {
        assertNull(MouseEncoder.encode(ev(MouseAction.MOVE, MouseButton.LEFT, 1, 1), x10))
        val drag = TerminalModes().also { it.mouseTracking = MouseTracking.DRAG; it.mouseSgr = true }
        assertNull(MouseEncoder.encode(ev(MouseAction.MOVE, MouseButton.NONE, 1, 1), drag))
        assertEquals("\u001B[<32;2;2M", s(MouseEncoder.encode(ev(MouseAction.MOVE, MouseButton.LEFT, 1, 1), drag)!!))
    }

    @Test fun `x10 encoding bytes and release and range limit`() {
        assertEquals(listOf<Byte>(0x1B, '['.code.toByte(), 'M'.code.toByte(), 32, 33, 33),
            MouseEncoder.encode(ev(MouseAction.PRESS, MouseButton.LEFT, 0, 0), x10)!!.toList())
        assertEquals(listOf<Byte>(0x1B, '['.code.toByte(), 'M'.code.toByte(), 35, 38, 40),
            MouseEncoder.encode(ev(MouseAction.RELEASE, MouseButton.LEFT, 5, 7), x10)!!.toList())
        assertNull(MouseEncoder.encode(ev(MouseAction.PRESS, MouseButton.LEFT, 300, 0), x10))
    }

    @Test fun `focus reports only when enabled`() {
        assertNull(MouseEncoder.encodeFocus(true, off))
        val f = TerminalModes().also { it.focusReporting = true }
        assertEquals("\u001B[I", s(MouseEncoder.encodeFocus(true, f)!!))
        assertEquals("\u001B[O", s(MouseEncoder.encodeFocus(false, f)!!))
    }
}
