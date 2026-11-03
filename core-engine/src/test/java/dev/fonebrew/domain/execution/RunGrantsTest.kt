package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.authority.AuthorityRung
import dev.fonebrew.contracts.authority.ConfirmationMode
import dev.fonebrew.contracts.authority.ResourceKind
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import dev.fonebrew.domain.remote.RemoteHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RunGrantsTest {

    private val now = Instant.parse("2026-08-28T00:00:00Z")
    private val sshTarget = RunTarget.Ssh(RemoteHost(alias = "homelab", hostname = "10.0.0.5", username = "pi"))
    private val ciTarget = RunTarget.Ci(
        GitHost(id = "h1", displayName = "d", kind = GitHostKind.GITHUB, baseUrl = "", owner = "me", repo = "r", branch = "main", authorName = "me", authorEmail = "m@e.com"),
    )

    @Test
    fun `local target -- EXECUTE_REVERSIBLE rung, no purpose binding, never re-confirms`() {
        val grant = RunGrants.defaultGrantFor(RunTarget.Local, "user.local", now)
        assertEquals(AuthorityRung.EXECUTE_REVERSIBLE, grant.authorityRung)
        assertNull(grant.constraints.purpose)
        assertEquals(ConfirmationMode.NEVER_REQUIRED, grant.confirmationPolicy.mode)
    }

    @Test
    fun `ssh target -- EXECUTE_EXTERNAL rung, purpose-bound, always confirms`() {
        val grant = RunGrants.defaultGrantFor(sshTarget, "user.local", now)
        assertEquals(AuthorityRung.EXECUTE_EXTERNAL, grant.authorityRung)
        assertEquals(RunGrants.PURPOSE, grant.constraints.purpose)
        assertEquals(ConfirmationMode.ALWAYS_REQUIRED, grant.confirmationPolicy.mode)
    }

    @Test
    fun `ci target -- same watched shape as ssh (EXECUTE_EXTERNAL, purpose-bound, always confirms)`() {
        val grant = RunGrants.defaultGrantFor(ciTarget, "user.local", now)
        assertEquals(AuthorityRung.EXECUTE_EXTERNAL, grant.authorityRung)
        assertEquals(RunGrants.PURPOSE, grant.constraints.purpose)
        assertEquals(ConfirmationMode.ALWAYS_REQUIRED, grant.confirmationPolicy.mode)
    }

    @Test
    fun `scope locks to exactly this target -- never a blanket grant`() {
        val grant = RunGrants.defaultGrantFor(sshTarget, "user.local", now)
        assertEquals(ResourceKind.EXECUTION_TARGET, grant.resourceScope.kind)
        assertEquals(sshTarget.targetId, grant.resourceScope.locator)
        assertEquals(listOf(sshTarget.capabilityId), grant.capabilityIds)
    }

    @Test
    fun `never delegable -- FB-RAT-AUTH-004`() {
        val grant = RunGrants.defaultGrantFor(sshTarget, "user.local", now)
        assertFalse(grant.delegationRule.delegable)
        assertNull(grant.delegationRule.maxDelegatedRung)
    }

    @Test
    fun `expires after the default TTL, not never`() {
        val grant = RunGrants.defaultGrantFor(sshTarget, "user.local", now)
        assertEquals(now.plus(RunGrants.DEFAULT_TTL), grant.expiresAtUtc)
        assertTrue(grant.expiresAtUtc.isAfter(now))
    }
}
