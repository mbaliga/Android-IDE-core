package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.authority.AuthorityDecision
import dev.fonebrew.contracts.authority.AuthorityRung
import dev.fonebrew.contracts.authority.RedactionOrSandboxDetail
import dev.fonebrew.contracts.authority.RedactionOrSandboxMode
import dev.fonebrew.contracts.authority.ResourceKind
import dev.fonebrew.contracts.authority.ResourceScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RunAuthorityGateTest {

    private val scope = ResourceScope(ResourceKind.EXECUTION_TARGET, "ssh:homelab")
    private val now = Instant.parse("2026-08-28T00:00:00Z")

    @Test
    fun `Allow classifies as Allowed, carrying the matched grant id`() {
        val decision = AuthorityDecision.Allow(
            decisionId = "d1", requestObjectId = "r1", requestingPrincipalId = "user.local",
            requestedCapabilityId = "fb.exec.ssh_remote", requestedResourceScope = scope,
            reasonCode = "AUTHORITY_GRANT_MATCHED", policyVersion = "1.0.0", decidedAtUtc = now,
            matchedGrantId = "grant_1",
        )
        val state = RunAuthorityGate.classify(decision)
        assertTrue(state is RunAuthorityUiState.Allowed)
        assertEquals("grant_1", (state as RunAuthorityUiState.Allowed).grantId)
    }

    @Test
    fun `AllowWithRedactionOrSandbox still classifies as Allowed -- a Run panel has no redaction UI of its own`() {
        val decision = AuthorityDecision.AllowWithRedactionOrSandbox(
            decisionId = "d1", requestObjectId = "r1", requestingPrincipalId = "user.local",
            requestedCapabilityId = "fb.exec.ssh_remote", requestedResourceScope = scope,
            reasonCode = "AUTHORITY_ALLOWED_WITH_MITIGATION", policyVersion = "1.0.0", decidedAtUtc = now,
            matchedGrantId = "grant_1",
            redactionOrSandbox = RedactionOrSandboxDetail(RedactionOrSandboxMode.SANDBOX, "sandboxed"),
        )
        val state = RunAuthorityGate.classify(decision)
        assertTrue(state is RunAuthorityUiState.Allowed)
    }

    @Test
    fun `RequireConfirmation carries the grant id and the confirmation prompt ref`() {
        val decision = AuthorityDecision.RequireConfirmation(
            decisionId = "d1", requestObjectId = "r1", requestingPrincipalId = "user.local",
            requestedCapabilityId = "fb.exec.ssh_remote", requestedResourceScope = scope,
            reasonCode = "AUTHORITY_CONFIRMATION_REQUIRED", policyVersion = "1.0.0", decidedAtUtc = now,
            matchedGrantId = "grant_1", confirmationPromptRef = "confirm.fb.exec.ssh_remote.execution_target",
        )
        val state = RunAuthorityGate.classify(decision) as RunAuthorityUiState.NeedsConfirmation
        assertEquals("grant_1", state.grantId)
        assertEquals("confirm.fb.exec.ssh_remote.execution_target", state.promptRef)
    }

    @Test
    fun `Deny -- default deny classifies as NeedsGrant, carrying the reason code`() {
        val decision = AuthorityDecision.Deny(
            decisionId = "d1", requestObjectId = "r1", requestingPrincipalId = "user.local",
            requestedCapabilityId = "fb.exec.ssh_remote", requestedResourceScope = scope,
            reasonCode = "AUTHORITY_DEFAULT_DENY", policyVersion = "1.0.0", decidedAtUtc = now,
        )
        val state = RunAuthorityGate.classify(decision) as RunAuthorityUiState.NeedsGrant
        assertEquals("AUTHORITY_DEFAULT_DENY", state.reasonCode)
    }

    @Test
    fun `Deny -- an expired grant also classifies as NeedsGrant -- re-granting is the Run panel's one remedy`() {
        val decision = AuthorityDecision.Deny(
            decisionId = "d1", requestObjectId = "r1", requestingPrincipalId = "user.local",
            requestedCapabilityId = "fb.exec.ssh_remote", requestedResourceScope = scope,
            reasonCode = "AUTHORITY_GRANT_EXPIRED", policyVersion = "1.0.0", decidedAtUtc = now,
            matchedGrantId = "grant_1",
        )
        assertTrue(RunAuthorityGate.classify(decision) is RunAuthorityUiState.NeedsGrant)
    }

    @Test
    fun `RequireStrongerAuthority classifies as NeedsGrant -- never silently escalated`() {
        val decision = AuthorityDecision.RequireStrongerAuthority(
            decisionId = "d1", requestObjectId = "r1", requestingPrincipalId = "user.local",
            requestedCapabilityId = "fb.exec.ssh_remote", requestedResourceScope = scope,
            reasonCode = "AUTHORITY_ESCALATION_REQUIRES_STRONGER_GRANT", policyVersion = "1.0.0", decidedAtUtc = now,
            requiredRung = AuthorityRung.EXECUTE_DESTRUCTIVE,
        )
        assertTrue(RunAuthorityGate.classify(decision) is RunAuthorityUiState.NeedsGrant)
    }
}
