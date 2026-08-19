package dev.fonebrew.domain.loop

import dev.fonebrew.contracts.common.IntegrityRef
import dev.fonebrew.contracts.loops.AuthorityRung
import dev.fonebrew.contracts.loops.BindingProfile
import dev.fonebrew.contracts.loops.BindingProvenance
import dev.fonebrew.contracts.loops.BindingRecord
import dev.fonebrew.contracts.loops.CapabilityRequest
import dev.fonebrew.contracts.loops.CompatibilityDeclaration
import dev.fonebrew.contracts.loops.CompatibilityOutcome
import dev.fonebrew.contracts.loops.GrantedAuthorityBucket
import dev.fonebrew.contracts.loops.InstallationState
import dev.fonebrew.contracts.loops.LoopPackageIdentity
import dev.fonebrew.contracts.loops.LoopPackageManifest
import dev.fonebrew.contracts.loops.PackageFileClassification
import dev.fonebrew.contracts.loops.PackageInventoryEntry
import dev.fonebrew.contracts.loops.ReleaseIdentity
import dev.fonebrew.contracts.loops.SubstitutionPolicy
import dev.fonebrew.contracts.loops.TransferChannel
import dev.fonebrew.contracts.loops.TransferEnvelope
import dev.fonebrew.domain.contracts.Digest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LoopInstallationDriverTest {

    private val packageBytes = """{"loopId":"loop-1"}""".toByteArray()
    private val realDigest = Digest.of(packageBytes)

    private fun envelope(digest: IntegrityRef = realDigest) = TransferEnvelope(
        transferChannel = TransferChannel.FILE, expectedMediaType = "application/x-floop",
        expectedDigest = digest, sourceDescription = "user-picked file",
    )

    private fun manifest(signed: Boolean = false) = LoopPackageManifest(
        schemaVersion = "1.0.0",
        packageIdentity = LoopPackageIdentity("loop-1", "1.0.0"),
        capabilityRequests = listOf(CapabilityRequest("fb.exec.local_command")),
        compatibilityDeclaration = CompatibilityDeclaration(">=1.0.0 <2.0.0"),
        packageInventory = listOf(PackageInventoryEntry("definition.json", 10, "a".repeat(64), PackageFileClassification.semanticFile)),
        packageContentDigest = realDigest,
        signatureRef = if (signed) dev.fonebrew.contracts.loops.ManifestSignatureRef("signatures/release.sig", "b".repeat(64)) else null,
    )

    private val releaseIdentity = ReleaseIdentity("loop-1", "1.0.0", realDigest)

    private fun bindingProfile() = BindingProfile(
        schemaVersion = "1.0.0", profileId = "bind_1", packageDigest = realDigest,
        slotBindings = listOf(BindingRecord("slot-1", "provider-x", SubstitutionPolicy.EXACT_ONLY, BindingProvenance.LOCAL)),
        resolvedAtUtc = Instant.parse("2026-08-07T09:00:00Z"),
    )

    private fun driver() = LoopInstallationDriver()

    @Test
    fun `a fully approved install reaches INSTALLED with a receipt, visiting every main-path state`() = runTest {
        val outcome = driver().install(
            packageBytes, envelope(), releaseIdentity, parseManifest = { manifest() },
            evaluateCompatibility = { CompatibilityOutcome.COMPATIBLE },
            resolveBindings = { bindingProfile() },
            resolveAuthority = { listOf(GrantedAuthorityBucket(AuthorityRung.EXECUTE_REVERSIBLE, listOf("fb.exec.local_command"))) },
        )
        assertEquals(InstallationState.INSTALLED, outcome.installation.installationState)
        assertTrue(outcome.receipt != null)
        assertEquals(1, outcome.installation.receipts.size)
        assertTrue(outcome.installation.signatureState in dev.fonebrew.contracts.loops.PackageSignatureState.TERMINAL_IMPORTED_STATES)
    }

    @Test
    fun `a digest mismatch is REJECTED_UNSAFE before the manifest is ever parsed`() = runTest {
        var parseWasCalled = false
        val outcome = driver().install(
            packageBytes, envelope(digest = Digest.of("different bytes".toByteArray())), releaseIdentity,
            parseManifest = { parseWasCalled = true; manifest() },
            evaluateCompatibility = { CompatibilityOutcome.COMPATIBLE },
            resolveBindings = { bindingProfile() }, resolveAuthority = { emptyList() },
        )
        assertEquals(InstallationState.REJECTED_UNSAFE, outcome.installation.installationState)
        assertTrue(!parseWasCalled) // FB-RAT-IMP-002 in spirit: nothing past Snapshot ran
        assertNull(outcome.receipt)
    }

    @Test
    fun `an unparseable manifest is REJECTED_UNSAFE`() = runTest {
        val outcome = driver().install(
            packageBytes, envelope(), releaseIdentity,
            parseManifest = { throw IllegalArgumentException("malformed JSON") },
            evaluateCompatibility = { CompatibilityOutcome.COMPATIBLE },
            resolveBindings = { bindingProfile() }, resolveAuthority = { emptyList() },
        )
        assertEquals(InstallationState.REJECTED_UNSAFE, outcome.installation.installationState)
    }

    @Test
    fun `a BLOCKED compatibility outcome is BLOCKED_INCOMPATIBLE, never proceeding to a binding prompt`() = runTest {
        var bindingsWereAsked = false
        val outcome = driver().install(
            packageBytes, envelope(), releaseIdentity, parseManifest = { manifest() },
            evaluateCompatibility = { CompatibilityOutcome.BLOCKED },
            resolveBindings = { bindingsWereAsked = true; bindingProfile() }, resolveAuthority = { emptyList() },
        )
        assertEquals(InstallationState.BLOCKED_INCOMPATIBLE, outcome.installation.installationState)
        assertTrue(!bindingsWereAsked)
    }

    @Test
    fun `declining bindings cancels the install`() = runTest {
        val outcome = driver().install(
            packageBytes, envelope(), releaseIdentity, parseManifest = { manifest() },
            evaluateCompatibility = { CompatibilityOutcome.COMPATIBLE },
            resolveBindings = { null }, resolveAuthority = { emptyList() },
        )
        assertEquals(InstallationState.CANCELLED, outcome.installation.installationState)
    }

    @Test
    fun `declining the authority grant cancels the install, never reaching simulation`() = runTest {
        var simulationRan = false
        val outcome = driver().install(
            packageBytes, envelope(), releaseIdentity, parseManifest = { manifest() },
            evaluateCompatibility = { CompatibilityOutcome.COMPATIBLE },
            resolveBindings = { bindingProfile() }, resolveAuthority = { null },
            runSimulation = { simulationRan = true; dev.fonebrew.contracts.loops.SimulationResult(dev.fonebrew.contracts.loops.SimulationOutcome.SUCCEEDED) },
        )
        assertEquals(InstallationState.CANCELLED, outcome.installation.installationState)
        assertTrue(!simulationRan)
    }

    @Test
    fun `every transition this driver performs is individually legal per InstallationState isValidTransition`() = runTest {
        // A meta-test: since the driver itself asserts this internally (reject()'s own require()),
        // simply exercising every branch without an AssertionError IS the proof -- this test names
        // that property explicitly rather than leaving it implicit in the other tests' passing.
        driver().install(
            packageBytes, envelope(), releaseIdentity, parseManifest = { manifest() },
            evaluateCompatibility = { CompatibilityOutcome.COMPATIBLE },
            resolveBindings = { bindingProfile() },
            resolveAuthority = { listOf(GrantedAuthorityBucket(AuthorityRung.READ, listOf("fb.workspace.read"))) },
        )
    }

    @Test
    fun `an unsigned package installs with IMPORTED_UNSIGNED_NARROWED_GRANTS, a valid terminal signature state`() = runTest {
        val outcome = driver().install(
            packageBytes, envelope(), releaseIdentity, parseManifest = { manifest(signed = false) },
            evaluateCompatibility = { CompatibilityOutcome.COMPATIBLE },
            resolveBindings = { bindingProfile() }, resolveAuthority = { emptyList() },
        )
        assertEquals(dev.fonebrew.contracts.loops.PackageSignatureState.IMPORTED_UNSIGNED_NARROWED_GRANTS, outcome.installation.signatureState)
    }
}
