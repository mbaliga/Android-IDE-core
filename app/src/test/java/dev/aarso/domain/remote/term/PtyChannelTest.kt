package dev.aarso.domain.remote.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyChannelTest {

    @Test fun `output updates the screen and notifies the renderer`() {
        var notifications = 0
        var lastFirstLine = ""
        val renderer = object : TerminalRenderer {
            override fun onScreenChanged(screen: ScreenBuffer) {
                notifications++; lastFirstLine = screen.lineText(0)
            }
        }
        val pty = PtyChannel(rows = 5, cols = 20)
        pty.attach(renderer)                 // attach notifies once
        pty.onOutput("pi@host:~\$ ")
        assertTrue(notifications >= 2)
        assertEquals("pi@host:~\$", lastFirstLine)
    }

    @Test fun `resize flows to the screen`() {
        val pty = PtyChannel(rows = 24, cols = 80)
        pty.resize(10, 40)
        assertEquals(10, pty.screen.rows)
        assertEquals(40, pty.screen.cols)
    }

    @Test fun `detach stops notifications`() {
        var count = 0
        val r = object : TerminalRenderer { override fun onScreenChanged(screen: ScreenBuffer) { count++ } }
        val pty = PtyChannel()
        pty.attach(r); val after = count; pty.detach()
        pty.onOutput("x")
        assertEquals(after, count)
    }
}
