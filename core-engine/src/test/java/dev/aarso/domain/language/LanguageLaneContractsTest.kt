package dev.aarso.domain.language

import dev.aarso.contracts.language.BuiltInLanguagePacks
import dev.aarso.contracts.language.LspCapability
import dev.aarso.contracts.language.ToolchainCapsuleManifest
import dev.aarso.contracts.language.ToolchainDeliveryMechanism
import dev.aarso.contracts.language.LanguagePackManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proves the `contracts/kotlin/LanguageLaneContracts.kt` init{} invariants do real work -- the same "contract's own strictness is a correctness check" pattern every prior WP's gate report has documented for its own domain. */
class LanguageLaneContractsTest {

    @Test
    fun `BUNDLED_JNILIBS requires a non-empty targetAbis list`() {
        try {
            ToolchainCapsuleManifest(
                capsuleId = "capsule.x", languageId = "rust",
                deliveryMechanism = ToolchainDeliveryMechanism.BUNDLED_JNILIBS,
                semanticVersion = "1.0.0", targetAbis = emptyList(),
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `REMOTE does not require targetAbis -- no local ABI is involved`() {
        val manifest = ToolchainCapsuleManifest(
            capsuleId = "capsule.rust-remote", languageId = "rust",
            deliveryMechanism = ToolchainDeliveryMechanism.REMOTE, semanticVersion = "1.0.0",
        )
        assertTrue(manifest.targetAbis.isEmpty())
    }

    @Test
    fun `a LanguagePackManifest declaring LSP capabilities without an lspCapsuleId is rejected`() {
        try {
            LanguagePackManifest(
                packId = "lang.bad", displayName = "Bad", languageIds = listOf("bad"),
                fileExtensions = listOf(".bad"), semanticVersion = "1.0.0",
                lspCapsuleId = null, dapCapsuleId = null,
                declaredLspCapabilities = setOf(LspCapability.HOVER),
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `a file extension not starting with a dot is rejected`() {
        try {
            LanguagePackManifest(
                packId = "lang.bad2", displayName = "Bad", languageIds = listOf("bad"),
                fileExtensions = listOf("bad"), semanticVersion = "1.0.0",
                lspCapsuleId = null, dapCapsuleId = null,
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `the built-in TypeScript and Python packs construct cleanly and declare distinct, non-overlapping file extensions`() {
        val ts = BuiltInLanguagePacks.TYPESCRIPT
        val py = BuiltInLanguagePacks.PYTHON
        assertEquals("lang.typescript", ts.packId)
        assertEquals("lang.python", py.packId)
        assertTrue(ts.fileExtensions.none { it in py.fileExtensions })
    }

    @Test
    fun `Rust and C++ built-in packs have no local LSP or DAP capsule reference -- REMOTE-only this pass`() {
        assertEquals(null, BuiltInLanguagePacks.RUST.lspCapsuleId)
        assertEquals(null, BuiltInLanguagePacks.RUST.dapCapsuleId)
        assertEquals(null, BuiltInLanguagePacks.CPP.lspCapsuleId)
        assertEquals(null, BuiltInLanguagePacks.CPP.dapCapsuleId)
    }
}
