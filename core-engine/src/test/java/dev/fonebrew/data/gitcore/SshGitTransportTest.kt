package dev.fonebrew.data.gitcore

import dev.fonebrew.domain.gitcore.transport.CancelToken
import dev.fonebrew.domain.gitcore.transport.GitService
import dev.fonebrew.domain.gitcore.transport.HostKeyPolicy
import dev.fonebrew.domain.gitcore.transport.RemoteUrl
import dev.fonebrew.domain.gitcore.transport.SshKeyProvider
import dev.fonebrew.domain.gitcore.transport.TransportErrorKind
import dev.fonebrew.domain.gitcore.transport.TransportException
import dev.fonebrew.domain.remote.HostKey
import dev.fonebrew.domain.remote.KnownHosts
import dev.fonebrew.domain.remote.Trust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * The pure parts of the SSH transport. There is NO sshd in the build container, so the live
 * exec-channel path is compile-verified only and is on the owner-verified list; the interop test
 * below documents the skip instead of pretending.
 */
class SshGitTransportTest {
    @Test fun remotePathsAreShellQuotedLikeGitDoes() {
        assertEquals("'/srv/git/repo.git'", SshGitTransport.quote("/srv/git/repo.git"))
        assertEquals("'it'\\''s'", SshGitTransport.quote("it's"))
        assertEquals("'a b;rm -rf'", SshGitTransport.quote("a b;rm -rf"))
    }

    @Test fun fingerprintIsOpenSshStyleAndStable() {
        val kp = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048) }.generateKeyPair()
        val f = SshGitTransport.sha256Fingerprint(kp.public)
        assertTrue(f.startsWith("SHA256:")); assertFalse(f.endsWith("=")); assertEquals(f, SshGitTransport.sha256Fingerprint(kp.public))
    }

    @Test fun defaultPolicyAcceptsOnlyAnAlreadyPinnedKey() {
        val k = HostKey("ssh-ed25519", "SHA256:abc")
        val policy = HostKeyPolicy.vettedOnly { KnownHosts() }
        assertTrue(policy.accept("h:22", Trust.Vetted))
        assertFalse(policy.accept("h:22", Trust.Unknown(k)))
        assertFalse(policy.accept("h:22", Trust.Changed(k, HostKey("ssh-ed25519", "SHA256:other"))))
    }

    @Test fun anUnreachableHostIsATypedNetworkErrorAndNoKeyIsEverRequested() {
        var asked = false
        val t = SshGitTransport(RemoteUrl.Ssh("127.0.0.1", 1, "git", "/r.git"), SshKeyProvider { _, _, _ -> asked = true; null }, HostKeyPolicy { _, _ -> true }, { KnownHosts() })
        try { t.open(GitService.UPLOAD_PACK, true, CancelToken()); fail() } catch (e: TransportException) { assertEquals(TransportErrorKind.NETWORK, e.kind) }
        assertFalse("no secret may be requested before a host key is trusted", asked)
    }

    @Test fun liveSshInteropIsSkippedWithoutASshd() {
        val sshd = listOf("/usr/sbin/sshd", "/usr/bin/sshd", "/sbin/sshd").any { java.io.File(it).canExecute() }
        assumeTrue("SKIPPED: no sshd in this environment, so SSH transport is owner-verified only", sshd)
        // With an sshd present this is where a throwaway-key, loopback-only server would be started.
        // It is deliberately not auto-configured here: it would need privileged setup in the container.
    }
}
