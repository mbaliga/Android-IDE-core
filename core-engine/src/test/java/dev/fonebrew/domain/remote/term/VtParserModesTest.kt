package dev.fonebrew.domain.remote.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VtParserModesTest {
    private class Rig(rows: Int = 24, cols: Int = 80) {
        val screen = ScreenBuffer(rows, cols)
        val modes = TerminalModes()
        val meta = TerminalMetadata()
        val replies = ArrayList<String>()
        val clip = ArrayList<ClipboardWriteRequest>()
        var bells = 0
        val parser = VtParser(screen, modes, meta, object : TerminalEvents {
            override fun onBell() { bells++ }
            override fun onClipboardWriteRequested(request: ClipboardWriteRequest) { clip.add(request) }
        }, TerminalReplies { replies.add(it) })
        fun feed(s: String) = parser.feed(s)
    }

    @Test fun `input modes set and reset`() {
        val r = Rig()
        r.feed("\u001B[?1h\u001B=\u001B[?2004h\u001B[?1004h\u001B[?1000h\u001B[?1006h")
        assertTrue(r.modes.applicationCursorKeys); assertTrue(r.modes.applicationKeypad)
        assertTrue(r.modes.bracketedPaste); assertTrue(r.modes.focusReporting)
        assertEquals(MouseTracking.CLICK, r.modes.mouseTracking); assertTrue(r.modes.mouseSgr)
        r.feed("\u001B[?1002h"); assertEquals(MouseTracking.DRAG, r.modes.mouseTracking)
        r.feed("\u001B[?1003h"); assertEquals(MouseTracking.ANY, r.modes.mouseTracking)
        r.feed("\u001B[?1000l"); assertEquals("disabling a lower level keeps the current one", MouseTracking.ANY, r.modes.mouseTracking)
        r.feed("\u001B[?1003l"); assertEquals(MouseTracking.NONE, r.modes.mouseTracking)
        r.feed("\u001B[?1l\u001B>\u001B[?2004l\u001B[?1004l\u001B[?1006l")
        assertEquals(false, r.modes.applicationCursorKeys); assertEquals(false, r.modes.applicationKeypad)
        assertEquals(false, r.modes.bracketedPaste); assertEquals(false, r.modes.focusReporting); assertEquals(false, r.modes.mouseSgr)
    }

    @Test fun `RIS resets modes`() {
        val r = Rig(); r.feed("\u001B[?2004h\u001B[?1h\u001Bc")
        assertEquals(false, r.modes.bracketedPaste); assertEquals(false, r.modes.applicationCursorKeys)
    }

    @Test fun `1049 saves the cursor and restores it with the main screen`() {
        val r = Rig(5, 10)
        r.feed("main\u001B[3;3H\u001B[?1049hALT\u001B[?1049l")
        assertEquals(false, r.screen.altScreen)
        assertEquals("main", r.screen.lineText(0))
        assertEquals(2, r.screen.cursor.row); assertEquals(2, r.screen.cursor.col)
    }

    @Test fun `device attributes and status reports are answered`() {
        val r = Rig(24, 80)
        r.feed("\u001B[c"); r.feed("\u001B[0c"); r.feed("\u001B[>c"); r.feed("\u001B[5n")
        r.feed("\u001B[4;7H\u001B[6n"); r.feed("\u001B[?6n"); r.feed("\u001B[18t")
        assertEquals(
            listOf("\u001B[?62;22c", "\u001B[?62;22c", "\u001B[>0;0;0c", "\u001B[0n", "\u001B[4;7R", "\u001B[?4;7R", "\u001B[8;24;80t"),
            r.replies,
        )
    }

    @Test fun `no reply channel means silence`() {
        val s = ScreenBuffer(); val p = VtParser(s)
        p.feed("\u001B[c\u001B[6n\u001B[18t")   // must not throw or print
        assertEquals("", s.lineText(0))
    }

    @Test fun `unknown queries get no invented answer`() {
        val r = Rig()
        r.feed("\u001B[14t\u001B[16t\u001B[?1\$p\u001B[1c\u001B[7n\u001B[>1c\u001B[\$p")
        assertEquals(emptyList<String>(), r.replies)
        assertEquals("", r.screen.lineText(0))
    }

    @Test fun `replies never echo stream text and are capped per feed`() {
        val r = Rig()
        r.feed("\u001B[6n".repeat(100))
        assertEquals(16, r.replies.size)
        r.feed("\u001B[6n")   // the cap is per feed call
        assertEquals(17, r.replies.size)
    }

    @Test fun `title and cwd are captured and sanitised`() {
        val r = Rig()
        r.feed("\u001B]0;my title\u0007")
        assertEquals("my title", r.meta.title)
        r.feed("\u001B]2;two\u001B\\")
        assertEquals("two", r.meta.title)
        r.feed("\u001B]0;bad\u0001\u0002title\u0007")
        assertEquals("badtitle", r.meta.title)
        r.feed("\u001B]7;file://phone/data/user/0/my%20dir\u0007")
        assertEquals("/data/user/0/my dir", r.meta.workingDirectory); assertEquals("phone", r.meta.workingDirectoryHost)
        assertEquals("", r.screen.lineText(0))
    }

    @Test fun `malformed or hostile cwd is ignored`() {
        val r = Rig()
        r.feed("\u001B]7;http://x/y\u0007\u001B]7;file://h/%zz\u0007\u001B]7;file://h/a%0Ab\u0007")
        assertNull(r.meta.workingDirectory)
    }

    @Test fun `osc 52 write is surfaced as an event and never applied, read is ignored`() {
        val r = Rig()
        r.feed("\u001B]52;c;aGVsbG8=\u0007")                   // "hello"
        assertEquals(listOf(ClipboardWriteRequest("c", "hello")), r.clip)
        r.feed("\u001B]52;c;?\u0007")
        assertEquals(1, r.clip.size)
        assertEquals(emptyList<String>(), r.replies)           // a read is never answered
        r.feed("\u001B]52;c;!!notbase64\u0007")
        assertEquals(1, r.clip.size)
        assertEquals("", r.screen.lineText(0))
    }

    @Test fun `oversized osc payloads are dropped not truncated`() {
        val r = Rig()
        r.feed("\u001B]0;" + "x".repeat(200_000) + "\u0007")
        assertNull(r.meta.title)
        r.feed("ok"); assertEquals("ok", r.screen.lineText(0))
    }

    @Test fun `bell fires the event only`() {
        val r = Rig(); r.feed("a\u0007b"); assertEquals(1, r.bells); assertEquals("ab", r.screen.lineText(0))
    }

    @Test fun `supplementary characters split across feed calls are reassembled`() {
        val r = Rig()
        val s = "😀"
        r.feed(s.substring(0, 1)); r.feed(s.substring(1))
        assertEquals(s, r.screen.lineText(0))
    }

    @Test fun `autowrap off overwrites the last column`() {
        val r = Rig(2, 4); r.feed("\u001B[?7labcdef")
        assertEquals("abcf", r.screen.lineText(0))
    }

    @Test fun `CSI 3 J clears scrollback`() {
        val r = Rig(2, 4); r.feed("a\r\nb\r\nc\r\n")
        assertTrue(r.screen.scrollbackSize() > 0)
        r.feed("\u001B[3J"); assertEquals(0, r.screen.scrollbackSize())
    }
}
