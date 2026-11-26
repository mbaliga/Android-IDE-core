package dev.fonebrew.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryTest {
    private fun reg() = SessionRegistry()

    @Test fun `ids are stable and never reused after removal`() {
        val r = reg()
        val a = r.create("a"); val b = r.create("b")
        r.remove(a.id)
        val c = r.create("c")
        assertEquals(listOf(b.id, c.id), r.sessions.map { it.id })
        assertTrue(c.id > b.id)
    }

    @Test fun `first session is active and removing it moves to a neighbour`() {
        val r = reg()
        val a = r.create("a"); val b = r.create("b")
        assertEquals(a.id, r.activeId)
        r.remove(a.id)
        assertEquals(b.id, r.activeId)
        r.remove(b.id)
        assertNull(r.activeId)
    }

    @Test fun `output and bell on an inactive session set flags that select clears`() {
        val r = reg()
        val a = r.create("a"); val b = r.create("b")
        r.noteOutput(a.id); r.noteBell(a.id)           // active: nothing
        assertFalse(r.get(a.id)!!.activity); assertFalse(r.get(a.id)!!.bell)
        r.noteOutput(b.id); r.noteBell(b.id)
        assertTrue(r.get(b.id)!!.activity); assertTrue(r.get(b.id)!!.bell)
        r.select(b.id)
        assertFalse(r.get(b.id)!!.activity); assertFalse(r.get(b.id)!!.bell)
    }

    @Test fun `exited sessions are not live and are restartable once`() {
        val r = reg()
        val a = r.create("a")
        r.markRunning(a.id)
        assertEquals(1, r.liveCount)
        r.markExited(a.id, 3)
        assertEquals(SessionState.Exited(3), r.get(a.id)!!.state)
        assertEquals(0, r.liveCount)
        assertTrue(r.get(a.id)!!.isRestartable)
        assertTrue(r.beginRestart(a.id))
        assertEquals(SessionState.Starting, r.get(a.id)!!.state)
        assertEquals(1, r.get(a.id)!!.restartCount)
        assertFalse(r.beginRestart(a.id)) // already starting: not restartable
    }

    @Test fun `a late markRunning cannot resurrect an exited session`() {
        val r = reg()
        val a = r.create("a")
        r.markExited(a.id, null)
        r.markRunning(a.id)
        assertEquals(SessionState.Exited(null), r.get(a.id)!!.state)
    }

    @Test fun `close decision follows the foreground and never guesses`() {
        val r = reg()
        val a = r.create("a")
        assertEquals(CloseDecision.CloseNow, r.closeDecision(a.id)) // starting: no shell yet
        r.markRunning(a.id)
        assertEquals(CloseDecision.ConfirmUnknown, r.closeDecision(a.id)) // unknown is not "idle"
        r.setForeground(a.id, ForegroundProcess.Shell)
        assertEquals(CloseDecision.CloseNow, r.closeDecision(a.id))
        r.setForeground(a.id, ForegroundProcess.Busy(42, "vim"))
        assertEquals(CloseDecision.ConfirmBusy("vim"), r.closeDecision(a.id))
        r.markExited(a.id, 0)
        assertEquals(CloseDecision.CloseNow, r.closeDecision(a.id))
        assertEquals(CloseDecision.NotFound, r.closeDecision(999))
    }

    @Test fun `foreground is cleared on exit and ignored for dead sessions`() {
        val r = reg()
        val a = r.create("a"); r.markRunning(a.id)
        r.setForeground(a.id, ForegroundProcess.Busy(7, null))
        r.markExited(a.id, 0)
        assertEquals(ForegroundProcess.Unknown, r.get(a.id)!!.foreground)
        r.setForeground(a.id, ForegroundProcess.Shell)
        assertEquals(ForegroundProcess.Unknown, r.get(a.id)!!.foreground)
    }

    @Test fun `blank title falls back to the default and cwd is recorded`() {
        val r = reg()
        val a = r.create("This phone")
        r.setTitle(a.id, "vim x.txt"); assertEquals("vim x.txt", r.get(a.id)!!.title)
        r.setTitle(a.id, "  "); assertEquals("This phone", r.get(a.id)!!.title)
        r.setCwd(a.id, "/data/x", "h"); assertEquals("/data/x", r.get(a.id)!!.cwd)
    }

    @Test fun `signal numbers are the Linux ones the native whitelist expects`() {
        assertEquals(listOf(1, 2, 3, 20), FgSignal.values().map { it.number }.sorted())
    }
}
