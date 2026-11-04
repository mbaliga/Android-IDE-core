package dev.fonebrew.domain.thread

import dev.fonebrew.domain.thread.DelegationOutcomes.ChoiceStatus.STILL_ACTIVE
import dev.fonebrew.domain.thread.DelegationOutcomes.ChoiceStatus.SWITCHED_OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DelegationOutcomesTest {

    // ---- Truth table -------------------------------------------------------------------

    @Test fun `still active, well below the window - PENDING`() {
        assertEquals(DelegationOutcome.PENDING, DelegationOutcomes.correlate(STILL_ACTIVE, 0))
    }

    @Test fun `still active, one turn short of the window - PENDING`() {
        assertEquals(
            DelegationOutcome.PENDING,
            DelegationOutcomes.correlate(STILL_ACTIVE, DelegationOutcomes.REVERT_WINDOW_TURNS - 1),
        )
    }

    @Test fun `still active, exactly at the window - KEPT`() {
        assertEquals(
            DelegationOutcome.KEPT,
            DelegationOutcomes.correlate(STILL_ACTIVE, DelegationOutcomes.REVERT_WINDOW_TURNS),
        )
    }

    @Test fun `still active, well past the window - KEPT`() {
        assertEquals(DelegationOutcome.KEPT, DelegationOutcomes.correlate(STILL_ACTIVE, 500))
    }

    @Test fun `switched off immediately - REVERTED`() {
        assertEquals(DelegationOutcome.REVERTED, DelegationOutcomes.correlate(SWITCHED_OFF, 0))
    }

    @Test fun `switched off one turn short of the window - REVERTED`() {
        assertEquals(
            DelegationOutcome.REVERTED,
            DelegationOutcomes.correlate(SWITCHED_OFF, DelegationOutcomes.REVERT_WINDOW_TURNS - 1),
        )
    }

    @Test fun `switched off exactly at the window - KEPT (already locked in, not a revert)`() {
        assertEquals(
            DelegationOutcome.KEPT,
            DelegationOutcomes.correlate(SWITCHED_OFF, DelegationOutcomes.REVERT_WINDOW_TURNS),
        )
    }

    @Test fun `switched off well past the window - KEPT`() {
        assertEquals(DelegationOutcome.KEPT, DelegationOutcomes.correlate(SWITCHED_OFF, 500))
    }

    // ---- Guardrails ----------------------------------------------------------------------

    @Test fun `negative turnsSinceChoice is rejected, never silently clamped`() {
        assertThrows(IllegalArgumentException::class.java) {
            DelegationOutcomes.correlate(STILL_ACTIVE, -1)
        }
    }

    @Test fun `the window constant is exactly five turns, matching the plan's wording`() {
        assertEquals(5, DelegationOutcomes.REVERT_WINDOW_TURNS)
    }
}
