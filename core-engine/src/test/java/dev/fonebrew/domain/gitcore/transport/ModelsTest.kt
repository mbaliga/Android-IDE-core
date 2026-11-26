package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.GitErrorKind
import dev.fonebrew.domain.gitcore.GitException
import dev.fonebrew.domain.gitcore.client.Refspec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelsTest {
    // ---- RemoteUrl
    @Test fun httpsUrlParsesAndNormalises() {
        val u = RemoteUrl.parse("https://GitHub.com/owner/repo.git/") as RemoteUrl.Http
        assertEquals("https://github.com/owner/repo.git", u.display)
        assertEquals(443, u.port)
        assertEquals("http://127.0.0.1:8080/r", RemoteUrl.parse("http://127.0.0.1:8080/r").display)
    }

    @Test fun passwordInUrlIsRefusedAndNeverEchoed() {
        try { RemoteUrl.parse("https://alice:s3cretTOKEN@host/r.git"); fail() } catch (e: GitException) {
            assertEquals(GitErrorKind.INVALID_ARGUMENT, e.kind)
            assertFalse(e.message!!.contains("s3cretTOKEN"))
        }
        val hinted = RemoteUrl.parse("https://alice@host/r.git") as RemoteUrl.Http
        assertEquals("alice", hinted.usernameHint)
        assertFalse(hinted.display.contains("alice"))
        try { RemoteUrl.parse("ssh://u:pw@host/r"); fail() } catch (_: GitException) {}
    }

    @Test fun sshAndScpFormsParse() {
        assertEquals(RemoteUrl.Ssh("example.com", 2222, "git", "/r/x.git"), RemoteUrl.parse("ssh://git@example.com:2222/r/x.git"))
        assertEquals(RemoteUrl.Ssh("github.com", 22, "git", "owner/repo.git"), RemoteUrl.parse("git@github.com:owner/repo.git"))
    }

    @Test fun unsupportedAndMalformedAddressesAreTyped() {
        for (bad in listOf("git://host/r", "file:///tmp/r", "/tmp/r", "../r")) try { RemoteUrl.parse(bad); fail(bad) } catch (e: TransportException) { assertEquals(TransportErrorKind.UNSUPPORTED, e.kind) }
        for (bad in listOf("", "https://host/r with space", "https://host/r?x=1", "nonsense")) try { RemoteUrl.parse(bad); fail(bad) } catch (e: GitException) { assertEquals(GitErrorKind.INVALID_ARGUMENT, e.kind) }
    }

    // ---- host flavor
    @Test fun flavorMapsTokenUsernameConventions() {
        assertEquals(GitHostFlavor.GITHUB, GitHostFlavor.fromHost("github.com"))
        assertEquals(GitHostFlavor.GITLAB, GitHostFlavor.fromHost("gitlab.example.org"))
        assertEquals(GitHostFlavor.BITBUCKET, GitHostFlavor.fromHost("bitbucket.org"))
        assertEquals(GitHostFlavor.GITEA, GitHostFlavor.fromHost("codeberg.org"))
        assertEquals(GitHostFlavor.OTHER, GitHostFlavor.fromHost("git.mycorp.internal"))
        assertEquals("x-access-token", GitHostFlavor.GITHUB.tokenUsername(null))
        assertEquals("octo", GitHostFlavor.GITHUB.tokenUsername("octo"))
        assertEquals("oauth2", GitHostFlavor.GITLAB.tokenUsername(" "))
        assertEquals("x-token-auth", GitHostFlavor.BITBUCKET.tokenUsername(null))
        assertEquals("git", GitHostFlavor.OTHER.tokenUsername(null))
    }

    // ---- redaction
    @Test fun credentialAndKeyMaterialNeverPrint() {
        val c = Credential("alice", "ghp_SECRET123")
        assertFalse(c.toString().contains("SECRET")); assertFalse(c.toString().contains("alice"))
        val k = SshKeyMaterial("-----BEGIN OPENSSH PRIVATE KEY-----XYZ", "pass123")
        assertFalse(k.toString().contains("XYZ")); assertFalse(k.toString().contains("pass123"))
        assertFalse(CredentialRequest("https", "h", 443, "alice").toString().contains("SECRET"))
    }

    // ---- refspec
    @Test fun refspecMapsWildcardAndSingle() {
        val w = Refspec.parse("+refs/heads/*:refs/remotes/origin/*")
        assertEquals("refs/remotes/origin/feat/x", w.map("refs/heads/feat/x"))
        assertNull(w.map("refs/tags/v1")); assertNull(w.map("refs/heads/"))
        val s = Refspec.parse("+refs/heads/main:refs/remotes/origin/main")
        assertEquals("refs/remotes/origin/main", s.map("refs/heads/main")); assertNull(s.map("refs/heads/other"))
        assertEquals("+refs/heads/main:refs/remotes/origin/main", s.text())
        for (bad in listOf("refs/heads/*:refs/x", "garbage", "refs/he*ads/x:refs/y/x", "refs/heads/a:notrefs/x")) try { Refspec.parse(bad); fail(bad) } catch (e: GitException) { assertEquals(GitErrorKind.UNSUPPORTED, e.kind) }
        assertTrue(Refspec.defaultFor("o").wildcard)
    }

    @Test fun cancelTokenClosesRegisteredAndLateRegistrations() {
        val t = CancelToken(); var closed = 0
        t.register(AutoCloseable { closed++ }); t.cancel(); assertEquals(1, closed)
        t.register(AutoCloseable { closed++ }); assertEquals(2, closed)
        try { t.check(); fail() } catch (e: TransportException) { assertEquals(TransportErrorKind.CANCELLED, e.kind) }
    }
}
