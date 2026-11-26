package dev.fonebrew.domain.search.semantic

import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticIndexStateTest {
    private fun d(enabled: Boolean = true, usable: Boolean = true, total: Int = 10, up: Int = 10, stale: Int = 0, paused: Boolean = false, running: Boolean = false) =
        SemanticIndexStates.derive(enabled, usable, total, up, stale, paused, running)

    @Test fun `off beats everything`() = assertEquals(SemanticIndexState.NotEnabled, d(enabled = false, usable = false))
    @Test fun `enabled without a usable model`() = assertEquals(SemanticIndexState.NoModel, d(usable = false))
    @Test fun `everything embedded is ready, including an empty corpus`() {
        assertEquals(SemanticIndexState.Ready, d())
        assertEquals(SemanticIndexState.Ready, d(total = 0, up = 0))
    }
    @Test fun `partial is building with counts`() = assertEquals(SemanticIndexState.Building(3, 10, false), d(up = 3, running = true))
    @Test fun `paused is building with the paused flag`() = assertEquals(SemanticIndexState.Building(3, 10, true), d(up = 3, paused = true))
    @Test fun `stale only when idle`() {
        assertEquals(SemanticIndexState.Stale(7), d(up = 3, stale = 7))
        assertEquals(SemanticIndexState.Building(3, 10, false), d(up = 3, stale = 7, running = true))
        assertEquals(SemanticIndexState.Building(3, 10, true), d(up = 3, stale = 7, paused = true))
    }
}
