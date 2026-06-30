package dev.aarso.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownHostsTest {

    private val ed25519 = HostKey("ssh-ed25519", "SHA256:AAAA1111")
    private val rotated = HostKey("ssh-ed25519", "SHA256:BBBB2222")
    private val pi = RemoteHost(alias = "pi", hostname = "192.168.1.10", username = "pi")

    @Test fun `an unseen endpoint is Unknown - trust on first use is the user's call`() {
        val verdict = KnownHosts().classify(pi, ed25519)
        assertTrue(verdict is Trust.Unknown)
        assertEquals(ed25519, (verdict as Trust.Unknown).presented)
    }

    @Test fun `a pinned matching key is Vetted`() {
        val kh = KnownHosts().pin(pi.endpoint, ed25519)
        assertEquals(Trust.Vetted, kh.classify(pi, ed25519))
        assertTrue(kh.isVetted(pi.endpoint, ed25519))
    }

    @Test fun `a changed key is Changed and never silently accepted`() {
        val kh = KnownHosts().pin(pi.endpoint, ed25519)
        val verdict = kh.classify(pi, rotated)
        assertTrue(verdict is Trust.Changed)
        verdict as Trust.Changed
        assertEquals(ed25519, verdict.pinned)
        assertEquals(rotated, verdict.presented)
        assertFalse(kh.isVetted(pi.endpoint, rotated))
    }

    @Test fun `re-pinning after an accepted change makes the new key Vetted`() {
        val kh = KnownHosts().pin(pi.endpoint, ed25519).pin(pi.endpoint, rotated)
        assertEquals(Trust.Vetted, kh.classify(pi, rotated))
        assertEquals(rotated, kh.pinned(pi.endpoint))
    }

    @Test fun `unpin forgets the endpoint`() {
        val kh = KnownHosts().pin(pi.endpoint, ed25519).unpin(pi.endpoint)
        assertTrue(kh.classify(pi, ed25519) is Trust.Unknown)
        assertTrue(kh.all().isEmpty())
    }
}
