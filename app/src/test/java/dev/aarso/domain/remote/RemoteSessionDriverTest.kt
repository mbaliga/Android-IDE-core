package dev.aarso.domain.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full session lifecycle over a fake transport — connect → trust → auth → exec → close —
 * with no socket. The fake records calls and replays scripted output so we can assert the
 * machine drove correctly and the remote's raw voice passed through verbatim.
 */
class RemoteSessionDriverTest {

    private val pi = RemoteHost("pi", "192.168.1.10", 22, "pi")
    private val key = HostKey("ssh-ed25519", "SHA256:AAAA")
    private val id = Identity.PublicKey(keyId = "pi-key")

    private class FakeTransport(
        val present: HostKey,
        val output: List<ExecChunk> = emptyList(),
        val exit: Int = 0,
        val authFails: Boolean = false,
    ) : RemoteTransport {
        val calls = mutableListOf<String>()
        var closed = false
        override suspend fun connect(host: RemoteHost): HostKey { calls += "connect:${host.endpoint}"; return present }
        override suspend fun authenticate(identity: Identity) {
            calls += "auth"
            if (authFails) error("bad credentials")
        }
        override suspend fun exec(request: ExecRequest, onChunk: (ExecChunk) -> Unit): ExecResult {
            calls += "exec:${request.command}"
            output.forEach(onChunk)
            return ExecResult(exit)
        }
        override suspend fun sftp(op: SftpOp): List<SftpEntry> { calls += "sftp"; return emptyList() }
        override suspend fun close() { calls += "close"; closed = true }
    }

    @Test fun `unknown host prompts trust, pins on accept, then runs to completion`() = runTest {
        val out = listOf(
            ExecChunk(ExecChunk.StdStream.OUT, "Linux pi 6.1\n".toByteArray()),
            ExecChunk(ExecChunk.StdStream.ERR, "warning: locale\n".toByteArray()),
        )
        val t = FakeTransport(present = key, output = out, exit = 0)
        val driver = RemoteSessionDriver(t, KnownHosts())

        var prompted: Trust? = null
        driver.open(pi, id) { trust -> prompted = trust; true }

        assertTrue(prompted is Trust.Unknown)
        assertEquals(SessionState.Ready, driver.state)
        assertEquals(Trust.Vetted, driver.knownHosts.classify(pi, key)) // pinned

        val received = mutableListOf<ExecChunk>()
        val result = driver.exec(ExecRequest("uname -a")) { received += it }
        assertTrue(result.ok)
        assertEquals(out, received) // raw voice, verbatim & in order
        assertEquals("Linux pi 6.1\n", String(received[0].bytes))

        driver.close()
        assertTrue(t.closed)
        assertEquals(listOf("connect:192.168.1.10:22", "auth", "exec:uname -a", "close"), t.calls)
    }

    @Test fun `a vetted host does not prompt`() = runTest {
        val t = FakeTransport(present = key)
        val driver = RemoteSessionDriver(t, KnownHosts().pin(pi.endpoint, key))
        var prompted = false
        driver.open(pi, id) { prompted = true; true }
        assertFalse(prompted)
        assertEquals(SessionState.Ready, driver.state)
    }

    @Test fun `rejecting a changed key aborts without authenticating`() = runTest {
        val changed = key.copy(fingerprint = "SHA256:EVIL")
        val t = FakeTransport(present = changed)
        val driver = RemoteSessionDriver(t, KnownHosts().pin(pi.endpoint, key))

        var sawChange = false
        driver.open(pi, id) { trust -> sawChange = trust is Trust.Changed; false }

        assertTrue(sawChange)
        assertEquals(SessionState.Closed, driver.state)
        assertTrue(t.closed)
        assertFalse(t.calls.contains("auth")) // never authenticated to a possible MITM
    }

    @Test fun `auth failure lands in Failed with the reason`() = runTest {
        val t = FakeTransport(present = key, authFails = true)
        val driver = RemoteSessionDriver(t, KnownHosts().pin(pi.endpoint, key))
        try { driver.open(pi, id) } catch (_: Exception) {}
        val s = driver.state
        assertTrue(s is SessionState.Failed)
        assertEquals("bad credentials", (s as SessionState.Failed).reason)
    }

    @Test fun `a nonzero exit code is reported honestly`() = runTest {
        val t = FakeTransport(present = key, exit = 127)
        val driver = RemoteSessionDriver(t, KnownHosts().pin(pi.endpoint, key))
        driver.open(pi, id)
        val r = driver.exec(ExecRequest("nope")) {}
        assertFalse(r.ok)
        assertEquals(127, r.exitCode)
        assertEquals(SessionState.Ready, driver.state) // back to ready, not failed
    }
}
