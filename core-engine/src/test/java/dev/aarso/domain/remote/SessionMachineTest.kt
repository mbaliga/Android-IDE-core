package dev.aarso.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMachineTest {

    private val key = HostKey("ssh-ed25519", "SHA256:AAAA")

    @Test fun `the happy path connect through ready through close is legal`() {
        var s: SessionState = SessionState.Disconnected
        s = SessionMachine.advance(s, SessionState.Connecting)
        s = SessionMachine.advance(s, SessionState.TrustCheck(key, Trust.Unknown(key)))
        s = SessionMachine.advance(s, SessionState.Authenticating)
        s = SessionMachine.advance(s, SessionState.Ready)
        s = SessionMachine.advance(s, SessionState.Running)
        s = SessionMachine.advance(s, SessionState.Ready)
        s = SessionMachine.advance(s, SessionState.Closed)
        assertEquals(SessionState.Closed, s)
        assertTrue(SessionMachine.isTerminal(s))
    }

    @Test fun `a rejected trust check goes to Closed`() {
        val tc = SessionState.TrustCheck(key, Trust.Changed(key, key.copy(fingerprint = "SHA256:BBBB")))
        assertTrue(SessionMachine.canTransition(tc, SessionState.Closed))
        assertTrue(SessionMachine.canTransition(tc, SessionState.Authenticating))
    }

    @Test fun `illegal jumps throw`() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionMachine.advance(SessionState.Ready, SessionState.TrustCheck(key, Trust.Vetted))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionMachine.advance(SessionState.Disconnected, SessionState.Ready)
        }
    }

    @Test fun `terminal states allow no transitions`() {
        assertTrue(SessionMachine.isTerminal(SessionState.Closed))
        assertTrue(SessionMachine.isTerminal(SessionState.Failed("nope")))
        assertFalse(SessionMachine.canTransition(SessionState.Closed, SessionState.Connecting))
        assertFalse(SessionMachine.canTransition(SessionState.Failed("x"), SessionState.Ready))
    }

    @Test fun `failure is reachable from every live state`() {
        val live = listOf(
            SessionState.Connecting,
            SessionState.TrustCheck(key, Trust.Vetted),
            SessionState.Authenticating,
            SessionState.Ready,
            SessionState.Running,
        )
        for (s in live) assertTrue("$s -> Failed", SessionMachine.canTransition(s, SessionState.Failed("boom")))
    }
}
