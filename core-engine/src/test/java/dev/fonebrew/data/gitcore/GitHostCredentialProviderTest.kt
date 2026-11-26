package dev.fonebrew.data.gitcore

import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import dev.fonebrew.domain.gitcore.transport.CredentialRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GitHostCredentialProviderTest {
    private fun host(id: String, kind: GitHostKind, base: String, owner: String = "octo") = GitHost(id, id, kind, base, owner, "repo", "main", "A", "a@x")
    private val tokens = mapOf("gh" to "ghp_GITHUBTOKEN", "gt" to "GITEATOKEN")
    private val hosts = listOf(host("gh", GitHostKind.GITHUB, ""), host("gt", GitHostKind.GITEA, "https://git.example.org", "carol"))
    private val p = GitHostCredentialProvider({ hosts }, { tokens[it] })
    private fun req(host: String, hint: String? = null) = CredentialRequest("https", host, 443, hint)

    @Test fun eachTokenIsOnlyEverReturnedForItsOwnHost() {
        assertEquals("ghp_GITHUBTOKEN", p.credentialFor(req("github.com"))!!.password())
        assertEquals("GITEATOKEN", p.credentialFor(req("git.example.org"))!!.password())
        assertNull(p.credentialFor(req("evil.example.net")))
        assertNull(p.credentialFor(req("gist.github.com")))
        assertNull(p.credentialFor(req("github.com.evil.net")))
    }

    @Test fun usernameFollowsTheHostConventionAndTheHint() {
        assertEquals("octo", p.credentialFor(req("github.com"))!!.username)
        assertEquals("alice", p.credentialFor(req("github.com", "alice"))!!.username)
        assertEquals("carol", p.credentialFor(req("git.example.org"))!!.username)
    }

    @Test fun blankOrMissingTokenGivesNothingAndNothingPrints() {
        assertNull(GitHostCredentialProvider({ hosts }, { null }).credentialFor(req("github.com")))
        assertNull(GitHostCredentialProvider({ hosts }, { " " }).credentialFor(req("github.com")))
        val c = p.credentialFor(req("github.com"))
        assertNotNull(c); assertFalse(c.toString().contains("GITHUBTOKEN"))
    }
}
