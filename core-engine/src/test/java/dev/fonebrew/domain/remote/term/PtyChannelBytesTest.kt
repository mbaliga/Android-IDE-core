package dev.fonebrew.domain.remote.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyChannelBytesTest {
    @Test fun `a multibyte character split across reads is reassembled on screen`() {
        val bytes = "日本é".toByteArray(Charsets.UTF_8)
        for (cut in 1 until bytes.size) {
            val ch = PtyChannel(2, 10)
            ch.onOutputBytes(bytes.copyOfRange(0, cut))
            ch.onOutputBytes(bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut=$cut", "日本é", ch.screen.lineText(0))
        }
    }

    @Test fun `replies and events flow only through the installed sinks`() {
        val ch = PtyChannel(24, 80)
        ch.onOutput("\u001B[c\u001B[5n")                 // no sink installed: silence, no crash
        val got = ArrayList<String>()
        ch.replies = TerminalReplies { got += it }
        ch.onOutput("\u001B[c\u001B[>c\u001B[5n\u001B[18t")
        assertEquals(listOf("\u001B[?62;22c", "\u001B[>0;0;0c", "\u001B[0n", "\u001B[8;24;80t"), got)
    }

    @Test fun `reset clears modes metadata and a half-received character`() {
        val ch = PtyChannel(2, 10)
        ch.onOutput("\u001B[?2004h\u001B]0;t\u0007")
        ch.onOutputBytes(byteArrayOf(0xE6.toByte()))
        ch.reset()
        assertTrue(!ch.modes.bracketedPaste)
        assertEquals(null, ch.meta.title)
        ch.onOutputBytes("x".toByteArray())
        assertEquals("x", ch.screen.lineText(0))
    }

    @Test fun `flushDecoder turns a dangling lead byte into a replacement character`() {
        val ch = PtyChannel(2, 10)
        ch.onOutputBytes(byteArrayOf('a'.code.toByte(), 0xE6.toByte()))
        ch.flushDecoder()
        assertEquals("a�", ch.screen.lineText(0))
    }
}
