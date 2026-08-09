package dev.aarso.domain.contracts

import dev.aarso.contracts.authority.AuthorityDecision
import dev.aarso.contracts.authority.AuthorityRung
import dev.aarso.contracts.authority.ConfirmationMode
import dev.aarso.contracts.authority.ConfirmationPolicy
import dev.aarso.contracts.authority.DelegationRule
import dev.aarso.contracts.authority.Grant
import dev.aarso.contracts.authority.GrantConstraints
import dev.aarso.contracts.authority.Principal
import dev.aarso.contracts.authority.PrincipalKind
import dev.aarso.contracts.authority.PrincipalStatus
import dev.aarso.contracts.authority.RedactionOrSandboxDetail
import dev.aarso.contracts.authority.RedactionOrSandboxMode
import dev.aarso.contracts.authority.ResourceKind
import dev.aarso.contracts.authority.ResourceScope
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AuthorityCodecTest {

    private val t0 = Instant.parse("2026-08-07T12:00:00Z")
    private val scope = ResourceScope(ResourceKind.REPOSITORY, "org/repo")

    @Test
    fun `USER Principal round-trips with a null parentPrincipalId`() {
        val p = Principal("user-1", PrincipalKind.USER, "Owner", null, PrincipalStatus.ACTIVE, t0)
        assertEquals(p, AuthorityCodec.decodePrincipal(AuthorityCodec.encodePrincipal(p)))
    }

    @Test
    fun `non-USER Principal round-trips with a non-null parentPrincipalId and rootUserPrincipalId`() {
        val p = Principal("loop-1", PrincipalKind.LOOP_RUN, "a loop", "user-1", PrincipalStatus.ACTIVE, t0, rootUserPrincipalId = "user-1")
        assertEquals(p, AuthorityCodec.decodePrincipal(AuthorityCodec.encodePrincipal(p)))
    }

    @Test
    fun `Grant round-trips including its additionalConstraints open bag`() {
        val g = Grant(
            grantId = "g1", principalId = "user-1", capabilityIds = listOf("fb.repo.read"),
            authorityRung = AuthorityRung.READ, resourceScope = scope,
            constraints = GrantConstraints(purpose = "review", additionalConstraints = mapOf("mitigation" to "SANDBOX", "count" to 3L)),
            expiresAtUtc = t0.plusSeconds(3600), confirmationPolicy = ConfirmationPolicy(ConfirmationMode.REQUIRED_ABOVE_RUNG, AuthorityRung.EXECUTE_EXTERNAL),
            delegationRule = DelegationRule(delegable = true, maxDelegatedRung = AuthorityRung.READ)
        )
        val decoded = AuthorityCodec.decodeGrant(AuthorityCodec.encodeGrant(g))
        assertEquals(g.grantId, decoded.grantId)
        assertEquals(g.constraints.purpose, decoded.constraints.purpose)
        assertEquals("SANDBOX", decoded.constraints.additionalConstraints["mitigation"])
        assertEquals(g.confirmationPolicy, decoded.confirmationPolicy)
        assertEquals(g.delegationRule, decoded.delegationRule)
    }

    @Test
    fun `Allow decision round-trips`() {
        val d = AuthorityDecision.Allow("dec1", "req1", "user-1", "fb.repo.read", scope, "AUTHORITY_GRANT_MATCHED", "1.0.0", t0, "g1")
        assertEquals(d, AuthorityCodec.decodeAuthorityDecision(AuthorityCodec.encodeAuthorityDecision(d)))
    }

    @Test
    fun `Deny decision with a null matchedGrantId round-trips`() {
        val d = AuthorityDecision.Deny("dec1", "req1", "user-1", "fb.repo.read", scope, "AUTHORITY_DEFAULT_DENY", "1.0.0", t0, null)
        assertEquals(d, AuthorityCodec.decodeAuthorityDecision(AuthorityCodec.encodeAuthorityDecision(d)))
    }

    @Test
    fun `RequireConfirmation decision round-trips`() {
        val d = AuthorityDecision.RequireConfirmation("dec1", "req1", "user-1", "fb.repo.commit", scope, "AUTHORITY_CONFIRMATION_REQUIRED", "1.0.0", t0, "g1", "confirm.commit")
        assertEquals(d, AuthorityCodec.decodeAuthorityDecision(AuthorityCodec.encodeAuthorityDecision(d)))
    }

    @Test
    fun `RequireStrongerAuthority decision round-trips with a null matchedGrantId`() {
        val d = AuthorityDecision.RequireStrongerAuthority("dec1", "req1", "user-1", "fb.device.flash", scope, "AUTHORITY_DEFAULT_DENY", "1.0.0", t0, AuthorityRung.EXECUTE_DESTRUCTIVE, null)
        assertEquals(d, AuthorityCodec.decodeAuthorityDecision(AuthorityCodec.encodeAuthorityDecision(d)))
    }

    @Test
    fun `AllowWithRedactionOrSandbox decision round-trips`() {
        val d = AuthorityDecision.AllowWithRedactionOrSandbox(
            "dec1", "req1", "user-1", "fb.repo.read", scope, "AUTHORITY_ALLOWED_WITH_MITIGATION", "1.0.0", t0, "g1",
            RedactionOrSandboxDetail(RedactionOrSandboxMode.REDACTION, "PII stripped from output")
        )
        assertEquals(d, AuthorityCodec.decodeAuthorityDecision(AuthorityCodec.encodeAuthorityDecision(d)))
    }
}
