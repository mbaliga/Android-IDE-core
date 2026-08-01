package dev.aarso.domain.curation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionContractTest {

    private fun resolve(
        directive: CompactionDirective? = null,
        verdict: Verdict? = null,
        bookmarked: Boolean = false,
        onSpine: Boolean = false,
    ) = CompactionContract.resolve("m1", directive, verdict, bookmarked, onSpine)

    @Test fun `an ordinary message with no signals defaults to F1`() {
        val r = resolve()
        assertEquals(Fidelity.F1, r.fidelity)
        assertEquals(FidelityReason.DEFAULT_ORDINARY, r.reason)
        assertEquals(MessageFate.GIST, Fates.forResolution(r))
    }

    @Test fun `a bookmarked message floors to F2`() {
        val r = resolve(bookmarked = true)
        assertEquals(Fidelity.F2, r.fidelity)
        assertEquals(MessageFate.FAITHFUL, Fates.forResolution(r))
    }

    @Test fun `a message on a version spine floors to F2`() {
        val r = resolve(onSpine = true)
        assertEquals(Fidelity.F2, r.fidelity)
    }

    @Test fun `a plus-2 verdict raises to F3`() {
        val r = resolve(verdict = Verdict("m1", 2, 0L))
        assertEquals(Fidelity.F3, r.fidelity)
        assertEquals(FidelityReason.VERDICT_POSITIVE, r.reason)
        assertEquals(MessageFate.KEPT_VERBATIM, Fates.forResolution(r))
    }

    @Test fun `a plus-1 verdict alone does not raise fidelity above ordinary`() {
        val r = resolve(verdict = Verdict("m1", 1, 0L))
        assertEquals(Fidelity.F1, r.fidelity)
    }

    @Test fun `a minus-2 verdict is a failure tombstone at F1, even when bookmarked`() {
        val r = resolve(verdict = Verdict("m1", -2, 0L), bookmarked = true, onSpine = true)
        assertEquals(Fidelity.F1, r.fidelity)
        assertTrue(r.isFailureTombstone)
        assertEquals(FidelityReason.VERDICT_NEGATIVE_TOMBSTONE, r.reason)
        assertEquals(MessageFate.TOMBSTONE, Fates.forResolution(r))
    }

    @Test fun `a minus-1 verdict alone does not force a tombstone`() {
        val r = resolve(verdict = Verdict("m1", -1, 0L))
        assertFalse(r.isFailureTombstone)
        assertEquals(Fidelity.F1, r.fidelity)
    }

    @Test fun `an explicit user directive wins over every computed signal`() {
        val directive = CompactionDirective("m1", mustInclude = true, fidelity = Fidelity.F0)
        val r = resolve(
            directive = directive,
            verdict = Verdict("m1", 2, 0L), // would otherwise force F3
            bookmarked = true,
            onSpine = true,
        )
        assertEquals(Fidelity.F0, r.fidelity)
        assertTrue(r.mustInclude)
        assertEquals(FidelityReason.USER_SET, r.reason)
        assertFalse(r.isFailureTombstone)
    }

    @Test fun `combining bookmark and version-spine floors still yields F2, not higher`() {
        val r = resolve(bookmarked = true, onSpine = true)
        assertEquals(Fidelity.F2, r.fidelity)
    }

    @Test fun `a reference verdict plus a bookmark takes the higher floor F3`() {
        val r = resolve(verdict = Verdict("m1", 2, 0L), bookmarked = true)
        assertEquals(Fidelity.F3, r.fidelity)
    }

    @Test fun `an unfloored, undirected F0-eligible message with mustInclude gists instead of dropping`() {
        val directive = CompactionDirective("m1", mustInclude = true, fidelity = Fidelity.F0)
        val r = resolve(directive = directive)
        assertEquals(MessageFate.GIST, Fates.forResolution(r))
    }

    @Test fun `an F0 directive without mustInclude drops the message`() {
        val directive = CompactionDirective("m1", mustInclude = false, fidelity = Fidelity.F0)
        val r = resolve(directive = directive)
        assertEquals(MessageFate.DROPPED, Fates.forResolution(r))
    }

    @Test fun `Fidelity ordering is F0 lowest, F3 highest`() {
        assertTrue(Fidelity.F0 < Fidelity.F1)
        assertTrue(Fidelity.F1 < Fidelity.F2)
        assertTrue(Fidelity.F2 < Fidelity.F3)
    }
}
