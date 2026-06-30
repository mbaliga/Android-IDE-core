package dev.aarso.domain.scope

import dev.aarso.domain.scope.ContextAssembly.AssemblyMode
import dev.aarso.domain.scope.ContextAssembly.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deterministic floor of context assembly: scope filtering, Verbatim vs prioritised
 * truncation, pin guarantees, the over-budget honesty flag, and the budget meter.
 */
class ContextAssemblyTest {

    private val all = setOf("p1", "p2")

    private fun piece(
        id: String,
        projectId: String = "p1",
        tokens: Int,
        pinned: Boolean = false,
        recency: Int = 0,
        source: CorpusSource = CorpusSource.RepoFile("$id.kt"),
    ) = CorpusPiece(
        id = id,
        source = source,
        projectId = projectId,
        tokenCount = tokens,
        pinned = pinned,
        recencyRank = recency,
        label = "label-$id",
    )

    private fun assemble(
        corpus: Corpus,
        budget: ContextBudget,
        scope: Scope = Scope.ThisProject,
        current: String = "p1",
    ) = ContextAssembly.assemble(scope, corpus, current, all, budget)

    // ---- Verbatim ------------------------------------------------------------------

    @Test
    fun verbatim_whenEverythingFits() {
        val corpus = Corpus(listOf(piece("a", tokens = 100), piece("b", tokens = 200)))
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 200))
        assertEquals(AssemblyMode.Verbatim, r.mode)
        assertEquals(2, r.included.size)
        assertTrue(r.cut.isEmpty())
    }

    @Test
    fun verbatim_meterHasCorrectHeadroom() {
        val corpus = Corpus(listOf(piece("a", tokens = 100), piece("b", tokens = 200)))
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 200))
        // available = 1000 - 200 = 800; corpus used = 300; total used = 300 + 200 = 500.
        assertEquals(800, r.meter.availableTokens)
        assertEquals(300, r.meter.corpusTokens)
        assertEquals(200, r.meter.reservedTokens)
        assertEquals(500, r.meter.usedTokens)
        assertEquals(1000, r.meter.totalTokens)
        assertFalse(r.meter.overBudget)
        assertEquals(0.5, r.meter.fractionUsed, 1e-9)
    }

    @Test
    fun verbatim_exactlyAtBudgetStillFits() {
        // scopedTotal == available is Verbatim (<=, not <).
        val corpus = Corpus(listOf(piece("a", tokens = 800)))
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 200))
        assertEquals(AssemblyMode.Verbatim, r.mode)
        assertEquals(1, r.included.size)
        assertFalse(r.meter.overBudget)
    }

    // ---- Scope filtering -----------------------------------------------------------

    @Test
    fun scopeFiltering_dropsOtherProjects() {
        val corpus = Corpus(
            listOf(
                piece("a", projectId = "p1", tokens = 100),
                piece("b", projectId = "p2", tokens = 100),
            ),
        )
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 0), scope = Scope.ThisProject)
        assertEquals(listOf("a"), r.included.map { it.id })
        assertTrue(r.cut.isEmpty())
    }

    @Test
    fun scopeFiltering_allProjectsIncludesEverything() {
        val corpus = Corpus(
            listOf(
                piece("a", projectId = "p1", tokens = 100),
                piece("b", projectId = "p2", tokens = 100),
            ),
        )
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 0), scope = Scope.AllProjects)
        assertEquals(setOf("a", "b"), r.included.map { it.id }.toSet())
    }

    // ---- Prioritized truncation ----------------------------------------------------

    @Test
    fun truncation_modeWhenOverBudget() {
        val corpus = Corpus(listOf(piece("a", tokens = 600), piece("b", tokens = 600)))
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 200)) // available = 800
        assertEquals(AssemblyMode.PrioritizedTruncation, r.mode)
    }

    @Test
    fun truncation_includesByRecencyThenCutsRest() {
        // available = 800. recency 0 newest. a(500,r0) + b(300,r1) = 800 fits; c(300,r2) cut.
        val corpus = Corpus(
            listOf(
                piece("c", tokens = 300, recency = 2),
                piece("a", tokens = 500, recency = 0),
                piece("b", tokens = 300, recency = 1),
            ),
        )
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 200))
        assertEquals(listOf("a", "b"), r.included.map { it.id })
        assertEquals(listOf("c"), r.cut.map { it.id })
        assertEquals(800, r.meter.corpusTokens)
        assertFalse(r.meter.overBudget)
    }

    @Test
    fun truncation_includedAndCutPartitionTheScopedCorpus() {
        val corpus = Corpus(
            listOf(
                piece("a", tokens = 500, recency = 0),
                piece("b", tokens = 300, recency = 1),
                piece("c", tokens = 300, recency = 2),
            ),
        )
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 200))
        val seen = (r.included + r.cut).map { it.id }.toSet()
        assertEquals(setOf("a", "b", "c"), seen)
        assertEquals(3, r.included.size + r.cut.size)
    }

    @Test
    fun truncation_isDeterministic_idTieBreak() {
        // Same pin + same recency → id ascending decides order and which one fits.
        // available = 500; two pieces of 300 each at recency 0; "x" < "y" so x first.
        val corpus = Corpus(
            listOf(
                piece("y", tokens = 300, recency = 0),
                piece("x", tokens = 300, recency = 0),
            ),
        )
        val budget = ContextBudget(total = 700, reserved = 200) // available 500
        val r1 = assemble(corpus, budget)
        val r2 = assemble(corpus, budget)
        assertEquals(listOf("x"), r1.included.map { it.id })
        assertEquals(listOf("y"), r1.cut.map { it.id })
        assertEquals(r1.included.map { it.id }, r2.included.map { it.id })
        assertEquals(r1.cut.map { it.id }, r2.cut.map { it.id })
    }

    // ---- Pins ----------------------------------------------------------------------

    @Test
    fun pins_surviveTruncationBeforeFitterUnpinnedPieces() {
        // available = 500. pinned big(450,r5) survives; recent small unpinned(300,r0) cut
        // because 450 + 300 > 500. The pin is honoured over the fresher-but-unpinned piece.
        val corpus = Corpus(
            listOf(
                piece("big", tokens = 450, pinned = true, recency = 5),
                piece("fresh", tokens = 300, pinned = false, recency = 0),
            ),
        )
        val r = assemble(corpus, ContextBudget(total = 700, reserved = 200))
        assertTrue("pin must be included", r.included.any { it.id == "big" })
        assertEquals(listOf("fresh"), r.cut.map { it.id })
    }

    @Test
    fun pins_orderedFirstInLedger() {
        val corpus = Corpus(
            listOf(
                piece("u", tokens = 100, pinned = false, recency = 0),
                piece("p", tokens = 100, pinned = true, recency = 9),
            ),
        )
        // over budget so we exercise the priority ordering path
        val r = assemble(corpus, ContextBudget(total = 150, reserved = 0)) // available 150
        assertEquals("p", r.included.first().id)
    }

    @Test
    fun pins_overBudget_honestyFlagSet_andClampedFraction() {
        // A single pin larger than the whole corpus budget: still included, overBudget true.
        val corpus = Corpus(listOf(piece("huge", tokens = 5000, pinned = true)))
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 200)) // available 800
        assertEquals(AssemblyMode.PrioritizedTruncation, r.mode)
        assertEquals(listOf("huge"), r.included.map { it.id })
        assertTrue(r.cut.isEmpty())
        assertTrue("pins past budget must report overBudget", r.meter.overBudget)
        assertEquals(5000, r.meter.corpusTokens)
        // usedTokens 5200 > total 1000, but fraction clamps to 1.0.
        assertEquals(1.0, r.meter.fractionUsed, 1e-9)
    }

    @Test
    fun manyPins_allKept_evenWhenCollectivelyOverBudget() {
        val corpus = Corpus(
            listOf(
                piece("p1", tokens = 400, pinned = true, recency = 3),
                piece("p2", tokens = 400, pinned = true, recency = 2),
                piece("p3", tokens = 400, pinned = true, recency = 1),
            ),
        )
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 200)) // available 800
        assertEquals(3, r.included.size)
        assertTrue(r.cut.isEmpty())
        assertTrue(r.meter.overBudget)
        assertEquals(1200, r.meter.corpusTokens)
    }

    @Test
    fun pinsUnderBudget_leaveRoomForUnpinned() {
        // available = 800. pin(300) + then unpinned by recency: a(300,r0) fits (600), b(300,r1)
        // fits (900 > 800? no: 600+300=900 > 800) so b cut.
        val corpus = Corpus(
            listOf(
                piece("pin", tokens = 300, pinned = true, recency = 9),
                piece("a", tokens = 300, recency = 0),
                piece("b", tokens = 300, recency = 1),
            ),
        )
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 200))
        assertEquals(setOf("pin", "a"), r.included.map { it.id }.toSet())
        assertEquals(listOf("b"), r.cut.map { it.id })
        assertFalse(r.meter.overBudget)
        assertEquals(600, r.meter.corpusTokens)
    }

    // ---- Budget meter / edge cases -------------------------------------------------

    @Test
    fun budget_availableForCorpusNeverNegative() {
        val budget = ContextBudget(total = 100, reserved = 500)
        assertEquals(0, budget.availableForCorpus)
    }

    @Test
    fun budget_zeroAvailable_cutsAllUnpinned() {
        val corpus = Corpus(listOf(piece("a", tokens = 10), piece("b", tokens = 10)))
        val r = assemble(corpus, ContextBudget(total = 100, reserved = 100)) // available 0
        assertEquals(AssemblyMode.PrioritizedTruncation, r.mode)
        assertTrue(r.included.isEmpty())
        assertEquals(2, r.cut.size)
        assertEquals(0, r.meter.corpusTokens)
    }

    @Test
    fun meter_fractionZeroWhenTotalZero() {
        val corpus = Corpus(emptyList())
        val r = assemble(corpus, ContextBudget(total = 0, reserved = 0))
        assertEquals(0.0, r.meter.fractionUsed, 1e-9)
        assertEquals(AssemblyMode.Verbatim, r.mode)
    }

    @Test
    fun emptyScopeResult_isVerbatimEmpty() {
        val corpus = Corpus(listOf(piece("a", projectId = "p2", tokens = 100)))
        // scope = ThisProject (p1) but the only piece is p2 → empty scoped corpus.
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 0))
        assertEquals(AssemblyMode.Verbatim, r.mode)
        assertTrue(r.included.isEmpty())
        assertTrue(r.cut.isEmpty())
    }

    @Test
    fun scopeIsCarriedThroughTheResult() {
        val corpus = Corpus(listOf(piece("a", tokens = 10)))
        val scope = Scope.SelectedProjects(setOf("p1"))
        val r = ContextAssembly.assemble(scope, corpus, "p1", all, ContextBudget(1000, 0))
        assertEquals(scope, r.scope)
    }

    @Test
    fun attribution_isPreservedOnIncludedPieces() {
        val corpus = Corpus(
            listOf(piece("a", tokens = 10, source = CorpusSource.Memory("m1"))),
        )
        val r = assemble(corpus, ContextBudget(total = 1000, reserved = 0))
        assertEquals(CorpusSource.Memory("m1"), r.included.single().source)
    }

    @Test
    fun negativeTokenCounts_clampToZero() {
        val corpus = Corpus(listOf(piece("a", tokens = -50)))
        assertEquals(0, corpus.totalTokens())
        val r = assemble(corpus, ContextBudget(total = 10, reserved = 0))
        assertEquals(AssemblyMode.Verbatim, r.mode)
        assertEquals(0, r.meter.corpusTokens)
    }
}
