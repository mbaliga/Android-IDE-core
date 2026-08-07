package dev.aarso.domain.language

import dev.aarso.contracts.language.BuiltInLanguagePacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsOwnershipTest {

    private val installed = listOf(BuiltInLanguagePacks.TYPESCRIPT, BuiltInLanguagePacks.PYTHON, BuiltInLanguagePacks.RUST)

    @Test
    fun `an extension claimed by exactly one installed pack resolves to Owned`() {
        val result = DiagnosticsOwnership.resolve(installed, ".py")
        assertTrue(result is DiagnosticsOwnership.Ownership.Owned)
        assertEquals(BuiltInLanguagePacks.PYTHON, (result as DiagnosticsOwnership.Ownership.Owned).pack)
    }

    @Test
    fun `an extension no installed pack claims resolves to NoOwner`() {
        assertTrue(DiagnosticsOwnership.resolve(installed, ".go") is DiagnosticsOwnership.Ownership.NoOwner)
    }

    @Test
    fun `an extension claimed by two installed packs resolves to Conflicted, listing both candidates -- never picks a winner by list order`() {
        val cAndCpp = listOf(BuiltInLanguagePacks.RUST, BuiltInLanguagePacks.CPP) // C++ claims .h; add a C-ish pack claiming the same
        val cLikeC = BuiltInLanguagePacks.CPP.copy(packId = "lang.c-only", languageIds = listOf("c"), fileExtensions = listOf(".h", ".c"))
        val result = DiagnosticsOwnership.resolve(cAndCpp + cLikeC, ".h")
        assertTrue(result is DiagnosticsOwnership.Ownership.Conflicted)
        assertEquals(2, (result as DiagnosticsOwnership.Ownership.Conflicted).candidates.size)
    }

    @Test
    fun `findAllConflicts surfaces every contested extension across an installed set, and nothing else`() {
        // CPP's own fileExtensions already include both .h and .c (BuiltInLanguagePacks.CPP =
        // .cpp/.cc/.cxx/.h/.hpp/.c) -- cLikeC claiming the same two means BOTH are genuinely
        // contested, not just .h. (First draft of this test asserted only {".h"} and failed
        // against the real implementation -- the test's own oversight, not a production bug: see
        // docs/WP9_GATE_REPORT.md §3.)
        val cLikeC = BuiltInLanguagePacks.CPP.copy(packId = "lang.c-only", languageIds = listOf("c"), fileExtensions = listOf(".h", ".c"))
        val conflicts = DiagnosticsOwnership.findAllConflicts(listOf(BuiltInLanguagePacks.CPP, cLikeC, BuiltInLanguagePacks.PYTHON))
        assertEquals(setOf(".h", ".c"), conflicts.keys)
        assertEquals(2, conflicts.getValue(".h").size)
        assertEquals(2, conflicts.getValue(".c").size)
    }

    @Test
    fun `the non-conflicting built-in set (TypeScript, Python, Rust) has zero conflicts among themselves`() {
        assertTrue(DiagnosticsOwnership.findAllConflicts(installed).isEmpty())
    }
}
