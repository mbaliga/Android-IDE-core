package dev.fonebrew.data.terminal

import dev.fonebrew.domain.remote.term.ClipboardWriteRequest
import dev.fonebrew.domain.terminal.CloseDecision
import dev.fonebrew.domain.terminal.FgSignal
import dev.fonebrew.domain.terminal.ForegroundProcess
import dev.fonebrew.domain.terminal.Mod
import dev.fonebrew.domain.terminal.KeyEvent
import dev.fonebrew.domain.terminal.NamedKey
import dev.fonebrew.domain.terminal.SessionState
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionManagerTest {
    private class FakeBackend(var fg: ForegroundProcess = ForegroundProcess.Shell) : SessionBackend {
        val written = StringBuilder()
        val signals = ArrayList<FgSignal>()
        var closed = 0
        var size: Pair<Int, Int>? = null
        override suspend fun write(bytes: ByteArray) { written.append(String(bytes, Charsets.UTF_8)) }
        override suspend fun resize(rows: Int, cols: Int) { size = rows to cols }
        override fun close() { closed++ }
        override fun foreground() = fg
        override fun signalForeground(signal: FgSignal): Boolean { signals += signal; return true }
    }

    /** Hands the test the io callbacks so it can play the shell's side. */
    private class FakeLauncher(val backend: FakeBackend = FakeBackend(), val fail: String? = null) : SessionLauncher {
        var io: SessionIo? = null
        var launches = 0
        override suspend fun launch(size: TermSize, io: SessionIo): SessionBackend {
            launches++
            fail?.let { throw IllegalStateException(it) }
            this.io = io
            return backend
        }
        fun say(s: String) { val b = s.toByteArray(); io!!.onOutput(b, b.size) }
    }

    private val counts = ArrayList<Int>()
    private fun manager(history: TerminalHistoryStore? = null) = TerminalSessionManager(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
        history = history,
        onLiveCount = { counts += it },
    )

    @Test fun `a started session runs, shows output and reports live counts`() {
        val m = manager(); val l = FakeLauncher()
        val s = m.create("t", l)
        assertEquals(SessionState.Running, s.info!!.state)
        l.say("hello\r\n")
        assertEquals("hello", s.channel.screen.lineText(0))
        assertEquals(listOf(1), counts)
        assertEquals(1, m.liveCount.value)
    }

    @Test fun `keystrokes and paste reach the backend in order, encoded for the live modes`() {
        val m = manager(); val l = FakeLauncher()
        val s = m.create("t", l)
        l.say("\u001B[?1h\u001B[?2004h")                       // app cursor keys + bracketed paste
        s.sendKey(KeyEvent(key = NamedKey.UP))
        s.sendKey(KeyEvent(text = "c", modifiers = Mod.CTRL))
        s.paste("a\nb\u001B[201~rm")
        assertEquals("\u001BOA\u0003\u001B[200~a\rb[201~rm\u001B[201~", l.backend.written.toString())
    }

    @Test fun `replies to terminal queries go to that session's pty and nowhere else`() {
        val m = manager(); val l1 = FakeLauncher(); val l2 = FakeLauncher()
        val s1 = m.create("one", l1); m.create("two", l2)
        l1.say("\u001B[6n")
        assertEquals("\u001B[1;1R", l1.backend.written.toString())
        assertEquals("", l2.backend.written.toString())
        assertNotNull(s1)
    }

    @Test fun `no reply is sent for plain output`() {
        val m = manager(); val l = FakeLauncher(); m.create("t", l)
        l.say("just text\r\n\u001B[31mred\u001B[0m")
        assertEquals("", l.backend.written.toString())
    }

    @Test fun `title cwd and bell reach the registry, activity only on inactive sessions`() {
        val m = manager(); val l1 = FakeLauncher(); val l2 = FakeLauncher()
        val a = m.create("a", l1); val b = m.create("b", l2)
        l2.say("\u001B]0;vim\u0007\u001B]7;file://h/tmp/x\u0007\u0007x")
        val info = b.info!!
        assertEquals("vim", info.title); assertEquals("/tmp/x", info.cwd)
        assertTrue(info.bell); assertTrue(info.activity)
        assertEquals(1, b.bellCount.value)
        l1.say("x")
        assertFalse(a.info!!.activity)               // a is the active session
        m.select(b.id)
        assertFalse(b.info!!.activity)
    }

    @Test fun `OSC 52 waits for consent and is applied only on accept`() {
        val m = manager(); val l = FakeLauncher(); val s = m.create("t", l)
        l.say("\u001B]52;c;aGk=\u0007")
        assertEquals(ClipboardWriteRequest("c", "hi"), s.pendingClipboard.value)
        assertNull(s.resolveClipboard(false))
        assertNull(s.pendingClipboard.value)
        l.say("\u001B]52;c;aGk=\u0007")
        assertEquals("hi", s.resolveClipboard(true))
    }

    @Test fun `shell exit makes the session exited and restartable, and the transcript says so`() {
        val m = manager(); val l = FakeLauncher(); val s = m.create("t", l)
        l.io!!.onExit(7)
        assertEquals(SessionState.Exited(7), s.info!!.state)
        assertTrue(s.info!!.isRestartable)
        assertEquals(0, m.liveCount.value)
        assertTrue(s.channel.screen.transcriptText().contains("[session exited with status 7]"))
        s.sendText("ignored")                         // dead shells take no input
        assertEquals("", l.backend.written.toString())
        val l2 = FakeLauncher(); s.setLauncher(l2)
        assertTrue(s.restart())
        assertEquals(SessionState.Running, s.info!!.state)
        assertEquals(1, s.info!!.restartCount)
        assertEquals(1, m.liveCount.value)
        assertTrue(s.channel.screen.transcriptText().contains("[session restarted]"))
    }

    @Test fun `a late exit from the previous shell does not kill the restarted one`() {
        val m = manager(); val l = FakeLauncher(); val s = m.create("t", l)
        val oldIo = l.io!!
        oldIo.onExit(0)
        val l2 = FakeLauncher(); s.setLauncher(l2); s.restart()
        oldIo.onExit(1); oldIo.onOutput("ghost".toByteArray(), 5)
        assertEquals(SessionState.Running, s.info!!.state)
        assertFalse(s.channel.screen.transcriptText().contains("ghost"))
    }

    @Test fun `launch failure is reported in the transcript and leaves an exited session`() {
        val m = manager(); val s = m.create("t", FakeLauncher(fail = "no pty"))
        assertEquals(SessionState.Exited(null), s.info!!.state)
        assertTrue(s.channel.screen.transcriptText().contains("could not start a shell: no pty"))
    }

    @Test fun `closing a busy or unknown session needs confirmation, an idle one does not`() {
        val m = manager()
        val busy = FakeLauncher(FakeBackend(ForegroundProcess.Busy(9, "vim")))
        val unknown = FakeLauncher(FakeBackend(ForegroundProcess.Unknown))
        val idle = FakeLauncher(FakeBackend(ForegroundProcess.Shell))
        val b = m.create("b", busy); val u = m.create("u", unknown); val i = m.create("i", idle)
        assertEquals(CloseResult.NeedsConfirm(CloseDecision.ConfirmBusy("vim")), m.close(b.id))
        assertEquals(CloseResult.NeedsConfirm(CloseDecision.ConfirmUnknown), m.close(u.id))
        assertEquals(0, busy.backend.closed)
        assertEquals(CloseResult.Closed, m.close(i.id))
        assertEquals(1, idle.backend.closed)
        assertEquals(CloseResult.Closed, m.close(b.id, confirmed = true))
        assertEquals(1, busy.backend.closed)
        assertNull(m.session(b.id))
        assertEquals(CloseResult.NotFound, m.close(12345))
    }

    @Test fun `closeAll closes everything and the live count returns to zero`() {
        val m = manager()
        repeat(3) { m.create("s$it", FakeLauncher(FakeBackend(ForegroundProcess.Busy(1, null)))) }
        m.closeAll()
        assertEquals(0, m.sessions.value.size)
        assertEquals(0, m.liveCount.value)
        assertEquals(0, counts.last())
    }

    @Test fun `signals go to the backend foreground and resize reaches the shell`() {
        val m = manager(); val l = FakeLauncher(); val s = m.create("t", l)
        assertTrue(s.signalForeground(FgSignal.INT))
        assertEquals(listOf(FgSignal.INT), l.backend.signals)
        s.resize(40, 100)
        assertEquals(40 to 100, l.backend.size)
        assertEquals(40, s.channel.screen.rows)
    }

    @Test fun `closing banks a bounded text snapshot as history, never a live session`() {
        val store = TerminalHistoryStore(Files.createTempDirectory("h").toFile())
        val m = manager(store); val l = FakeLauncher(); val s = m.create("t", l)
        l.say("secret-free output\r\n")
        m.close(s.id, confirmed = true)
        val entry = store.list().single()
        assertTrue(store.read(entry.slot)!!.text.contains("secret-free output"))
        assertEquals("closed", entry.meta.reason)
    }

    @Test fun `a restarted session overwrites its own snapshot slot instead of piling up`() {
        val store = TerminalHistoryStore(Files.createTempDirectory("h").toFile())
        val m = manager(store); val l = FakeLauncher(); val s = m.create("t", l)
        l.say("a\r\n"); m.persistSnapshots("background"); m.persistSnapshots("background")
        assertEquals(1, store.list().size)
    }
}
