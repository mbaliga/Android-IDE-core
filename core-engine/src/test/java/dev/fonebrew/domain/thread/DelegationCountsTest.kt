package dev.fonebrew.domain.thread

import org.junit.Assert.assertEquals
import org.junit.Test

class DelegationCountsTest {

    private fun event(outcome: DelegationOutcome, id: String = "d-$outcome-${System.nanoTime()}") = DelegationEvent(
        id = id,
        at = 1L,
        kind = DelegationKind.MODEL_PICK_BRANCH,
        outcome = outcome,
    )

    @Test fun `an empty list summarizes to all zeros`() {
        assertEquals(DelegationCounts.EMPTY, DelegationCounts.summarize(emptyList()))
    }

    @Test fun `counts partition by outcome and sum to total`() {
        val events = listOf(
            event(DelegationOutcome.KEPT),
            event(DelegationOutcome.KEPT),
            event(DelegationOutcome.REVERTED),
            event(DelegationOutcome.PENDING),
            event(DelegationOutcome.PENDING),
            event(DelegationOutcome.PENDING),
        )
        val counts = DelegationCounts.summarize(events)
        assertEquals(6, counts.total)
        assertEquals(2, counts.kept)
        assertEquals(1, counts.reverted)
        assertEquals(3, counts.pending)
        assertEquals(counts.total, counts.kept + counts.reverted + counts.pending)
    }

    @Test fun `all-kept list has zero reverted and zero pending`() {
        val counts = DelegationCounts.summarize(listOf(event(DelegationOutcome.KEPT), event(DelegationOutcome.KEPT)))
        assertEquals(2, counts.total)
        assertEquals(2, counts.kept)
        assertEquals(0, counts.reverted)
        assertEquals(0, counts.pending)
    }
}
