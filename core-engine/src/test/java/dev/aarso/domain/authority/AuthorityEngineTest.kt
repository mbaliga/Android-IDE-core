package dev.aarso.domain.authority

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
import dev.aarso.contracts.authority.RedactionOrSandboxMode
import dev.aarso.contracts.authority.ResourceKind
import dev.aarso.contracts.authority.ResourceScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The privilege-escalation fixture pack CAPABILITY_AUTHORITY_MODEL.md §6/§9 names as required,
 * as real engine tests rather than static JSON fixtures -- the doc's own point is that these
 * scenarios are schema-valid on their face and only a real policy engine can prove the refusal.
 */
class AuthorityEngineTest {

    private val fixedNow = Instant.parse("2026-08-07T12:00:00Z")
    private val future = fixedNow.plusSeconds(3600)
    private val past = fixedNow.minusSeconds(3600)

    private fun engine(grants: InMemoryGrantStore = InMemoryGrantStore(), principals: InMemoryPrincipalStore = InMemoryPrincipalStore()) =
        AuthorityEngine(grants, principals, policyVersion = "1.0.0", now = { fixedNow }, idGenerator = { "dec_fixed" })

    private fun user(id: String = "user-1") = Principal(
        principalId = id, kind = PrincipalKind.USER, displayName = "Owner", parentPrincipalId = null,
        status = PrincipalStatus.ACTIVE, createdAtUtc = past
    )

    private fun grant(
        principalId: String, capabilityId: String, rung: AuthorityRung, scope: ResourceScope,
        purpose: String? = null, expiresAtUtc: Instant = future, mode: ConfirmationMode = ConfirmationMode.NEVER_REQUIRED,
        additionalConstraints: Map<String, Any?> = emptyMap(),
    ) = Grant(
        grantId = "grant_${principalId}_$capabilityId", principalId = principalId, capabilityIds = listOf(capabilityId),
        authorityRung = rung, resourceScope = scope, constraints = GrantConstraints(purpose = purpose, additionalConstraints = additionalConstraints),
        expiresAtUtc = expiresAtUtc, confirmationPolicy = ConfirmationPolicy(mode = mode),
        delegationRule = DelegationRule(delegable = false, maxDelegatedRung = null)
    )

    private val repoScope = ResourceScope(ResourceKind.REPOSITORY, "org/repo")

    @Test
    fun `default deny -- a completely novel triple with no grant anywhere resolves to AUTHORITY_DEFAULT_DENY`() {
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        val decision = engine(principals = principals).evaluate("req1", "user-1", "fb.repo.read", repoScope)
        assertTrue(decision is AuthorityDecision.Deny)
        assertEquals("AUTHORITY_DEFAULT_DENY", (decision as AuthorityDecision.Deny).reasonCode)
        assertEquals(null, decision.matchedGrantId)
    }

    @Test
    fun `allow -- a matching, unexpired, non-purpose-bound grant resolves to Allow naming that grant`() {
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        val grants = InMemoryGrantStore().apply { add(grant("user-1", "fb.repo.read", AuthorityRung.READ, repoScope)) }
        val decision = engine(grants, principals).evaluate("req1", "user-1", "fb.repo.read", repoScope)
        assertTrue(decision is AuthorityDecision.Allow)
        assertEquals("grant_user-1_fb.repo.read", (decision as AuthorityDecision.Allow).matchedGrantId)
    }

    @Test
    fun `expired grant -- a request evaluated after expiresAtUtc denies, never silently renews`() {
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        val grants = InMemoryGrantStore().apply {
            add(grant("user-1", "fb.repo.read", AuthorityRung.READ, repoScope, expiresAtUtc = past))
        }
        val decision = engine(grants, principals).evaluate("req1", "user-1", "fb.repo.read", repoScope)
        assertTrue(decision is AuthorityDecision.Deny)
        val deny = decision as AuthorityDecision.Deny
        assertEquals("AUTHORITY_GRANT_EXPIRED", deny.reasonCode)
        assertEquals("grant_user-1_fb.repo.read", deny.matchedGrantId)
    }

    @Test
    fun `child exceeds parent -- neither the child LOOP_RUN nor its USER parent holds a grant reaching the rung`() {
        val principals = InMemoryPrincipalStore().apply {
            add(user())
            add(Principal("loop-1", PrincipalKind.LOOP_RUN, "a loop run", parentPrincipalId = "user-1", status = PrincipalStatus.ACTIVE, createdAtUtc = past))
        }
        val decision = engine(principals = principals).evaluate(
            "req1", "loop-1", "fb.device.flash", ResourceScope(ResourceKind.DEVICE, "esp32-1")
        )
        assertTrue(decision is AuthorityDecision.Deny)
        assertEquals("AUTHORITY_DEFAULT_DENY", (decision as AuthorityDecision.Deny).reasonCode)
    }

    @Test
    fun `transitive delegation rejected -- a three-hop descendant relies on an ancestor's grant, denied with the specific reason code, matchedGrantId null`() {
        val deviceScope = ResourceScope(ResourceKind.REPOSITORY, "org/repo")
        val principals = InMemoryPrincipalStore().apply {
            add(user())
            add(Principal("agent-1", PrincipalKind.LOCAL_AGENT_PERSONA, "agent", parentPrincipalId = "user-1", status = PrincipalStatus.ACTIVE, createdAtUtc = past))
            add(Principal("loop-1", PrincipalKind.LOOP_RUN, "loop", parentPrincipalId = "agent-1", status = PrincipalStatus.ACTIVE, createdAtUtc = past))
            add(Principal("workflow-1", PrincipalKind.STUDIO_WORKFLOW, "workflow", parentPrincipalId = "loop-1", status = PrincipalStatus.ACTIVE, createdAtUtc = past))
        }
        // The USER ancestor DOES hold a covering grant -- workflow-1 (three hops down) has none of its own.
        val grants = InMemoryGrantStore().apply { add(grant("user-1", "fb.repo.push_remote", AuthorityRung.EXECUTE_EXTERNAL, deviceScope, purpose = "release")) }

        val decision = engine(grants, principals).evaluate("req1", "workflow-1", "fb.repo.push_remote", deviceScope, purpose = "release")
        assertTrue(decision is AuthorityDecision.Deny)
        val deny = decision as AuthorityDecision.Deny
        assertEquals("AUTHORITY_TRANSITIVE_DELEGATION_REJECTED", deny.reasonCode)
        assertEquals(null, deny.matchedGrantId)
    }

    @Test
    fun `purpose crossing -- a secret-use grant purpose-bound to one destination MUST NOT authorize an unrelated purpose`() {
        val secretScope = ResourceScope(ResourceKind.NETWORK_DOMAIN, "127.0.0.1:11435")
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        val grants = InMemoryGrantStore().apply {
            add(grant("user-1", "fb.secret.use", AuthorityRung.EXECUTE_EXTERNAL, secretScope, purpose = "asom-model-provider-authentication"))
        }
        val decision = engine(grants, principals).evaluate("req1", "user-1", "fb.secret.use", secretScope, purpose = "unrelated-shell-operation")
        assertTrue(decision is AuthorityDecision.Deny)
        assertEquals("AUTHORITY_PURPOSE_MISMATCH", (decision as AuthorityDecision.Deny).reasonCode)
    }

    @Test
    fun `purpose-bound grant with the matching purpose allows`() {
        val secretScope = ResourceScope(ResourceKind.NETWORK_DOMAIN, "127.0.0.1:11435")
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        val grants = InMemoryGrantStore().apply {
            add(grant("user-1", "fb.secret.use", AuthorityRung.EXECUTE_EXTERNAL, secretScope, purpose = "asom-model-provider-authentication"))
        }
        val decision = engine(grants, principals).evaluate("req1", "user-1", "fb.secret.use", secretScope, purpose = "asom-model-provider-authentication")
        assertTrue(decision is AuthorityDecision.Allow)
    }

    @Test
    fun `resource scope mismatch -- a grant for one target does not cover a different target, even same principal and capability`() {
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        val grants = InMemoryGrantStore().apply {
            add(grant("user-1", "fb.repo.read", AuthorityRung.READ, ResourceScope(ResourceKind.REPOSITORY, "org/repo-A")))
        }
        val decision = engine(grants, principals).evaluate("req1", "user-1", "fb.repo.read", ResourceScope(ResourceKind.REPOSITORY, "org/repo-B"))
        assertTrue(decision is AuthorityDecision.Deny)
        assertEquals("AUTHORITY_RESOURCE_SCOPE_MISMATCH", (decision as AuthorityDecision.Deny).reasonCode)
    }

    @Test
    fun `grant-rung-capability mismatch -- a malformed grant declaring the wrong rung for its own capability is not usable authority`() {
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        // fb.device.flash is EXECUTE_DESTRUCTIVE per the registry; this grant wrongly declares EXECUTE_REVERSIBLE.
        val grants = InMemoryGrantStore().apply {
            add(grant("user-1", "fb.device.flash", AuthorityRung.EXECUTE_REVERSIBLE, ResourceScope(ResourceKind.DEVICE, "esp32-1")))
        }
        val decision = engine(grants, principals).evaluate("req1", "user-1", "fb.device.flash", ResourceScope(ResourceKind.DEVICE, "esp32-1"))
        assertTrue(decision is AuthorityDecision.Deny)
        assertEquals("AUTHORITY_GRANT_RUNG_CAPABILITY_MISMATCH", (decision as AuthorityDecision.Deny).reasonCode)
    }

    @Test
    fun `unknown capability id denies rather than falling through to an ambient allow`() {
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        val decision = engine(principals = principals).evaluate("req1", "user-1", "fb.not.a.real.capability", repoScope)
        assertTrue(decision is AuthorityDecision.Deny)
        assertEquals("AUTHORITY_UNKNOWN_CAPABILITY", (decision as AuthorityDecision.Deny).reasonCode)
    }

    @Test
    fun `a REVOKED principal's grants are unusable without individually revoking each one`() {
        val principals = InMemoryPrincipalStore().apply {
            add(Principal("user-1", PrincipalKind.USER, "Owner", null, PrincipalStatus.REVOKED, past))
        }
        val grants = InMemoryGrantStore().apply { add(grant("user-1", "fb.repo.read", AuthorityRung.READ, repoScope)) }
        val decision = engine(grants, principals).evaluate("req1", "user-1", "fb.repo.read", repoScope)
        assertTrue(decision is AuthorityDecision.Deny)
        assertEquals("AUTHORITY_PRINCIPAL_NOT_ACTIVE", (decision as AuthorityDecision.Deny).reasonCode)
    }

    @Test
    fun `a principal not in the store at all denies`() {
        val decision = engine().evaluate("req1", "nobody", "fb.repo.read", repoScope)
        assertTrue(decision is AuthorityDecision.Deny)
        assertEquals("AUTHORITY_PRINCIPAL_NOT_FOUND", (decision as AuthorityDecision.Deny).reasonCode)
    }

    @Test
    fun `ALWAYS_REQUIRED confirmation policy yields RequireConfirmation with a non-blank prompt ref`() {
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        val grants = InMemoryGrantStore().apply {
            add(grant("user-1", "fb.repo.commit", AuthorityRung.EXECUTE_REVERSIBLE, repoScope, mode = ConfirmationMode.ALWAYS_REQUIRED))
        }
        val decision = engine(grants, principals).evaluate("req1", "user-1", "fb.repo.commit", repoScope)
        assertTrue(decision is AuthorityDecision.RequireConfirmation)
        assertTrue((decision as AuthorityDecision.RequireConfirmation).confirmationPromptRef.isNotBlank())
    }

    @Test
    fun `a grant carrying a mitigation constraint yields AllowWithRedactionOrSandbox, not a bare Allow`() {
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        val grants = InMemoryGrantStore().apply {
            add(grant("user-1", "fb.repo.read", AuthorityRung.READ, repoScope, additionalConstraints = mapOf("mitigation" to "SANDBOX", "mitigationDetail" to "first activation")))
        }
        val decision = engine(grants, principals).evaluate("req1", "user-1", "fb.repo.read", repoScope)
        assertTrue(decision is AuthorityDecision.AllowWithRedactionOrSandbox)
        assertEquals(RedactionOrSandboxMode.SANDBOX, (decision as AuthorityDecision.AllowWithRedactionOrSandbox).redactionOrSandbox.mode)
    }

    @Test
    fun `escalateToRung above the capability's nominal rung always resolves to RequireStrongerAuthority`() {
        val principals = InMemoryPrincipalStore().apply { add(user()) }
        val grants = InMemoryGrantStore().apply {
            add(grant("user-1", "fb.repo.commit", AuthorityRung.EXECUTE_REVERSIBLE, repoScope))
        }
        val decision = engine(grants, principals).evaluate(
            "req1", "user-1", "fb.repo.commit", repoScope, escalateToRung = AuthorityRung.EXECUTE_DESTRUCTIVE
        )
        assertTrue(decision is AuthorityDecision.RequireStrongerAuthority)
        val rsa = decision as AuthorityDecision.RequireStrongerAuthority
        assertEquals(AuthorityRung.EXECUTE_DESTRUCTIVE, rsa.requiredRung)
        assertEquals("grant_user-1_fb.repo.commit", rsa.matchedGrantId) // the base grant, cited as a courtesy, not as sufficient
    }

    @Test
    fun `no implicit privilege expansion -- a grant's delegationRule can never widen beyond its own rung (compile-time construction check)`() {
        try {
            Grant(
                grantId = "g1", principalId = "user-1", capabilityIds = listOf("fb.repo.commit"),
                authorityRung = AuthorityRung.EXECUTE_REVERSIBLE, resourceScope = repoScope,
                constraints = GrantConstraints(), expiresAtUtc = future,
                confirmationPolicy = ConfirmationPolicy(ConfirmationMode.NEVER_REQUIRED),
                delegationRule = DelegationRule(delegable = true, maxDelegatedRung = AuthorityRung.EXECUTE_DESTRUCTIVE)
            )
            org.junit.Assert.fail("expected IllegalArgumentException -- delegation may only narrow, never widen")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("narrow"))
        }
    }
}
