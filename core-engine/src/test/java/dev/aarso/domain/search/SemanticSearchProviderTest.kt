package dev.aarso.domain.search

import dev.aarso.contracts.search.SemanticUnavailableReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSearchProviderTest {

    @Test
    fun `DisabledSemanticSearchProvider is enabled=false and never fabricates a result set`() = runTest {
        assertFalse(DisabledSemanticSearchProvider.enabled)
        val outcome = DisabledSemanticSearchProvider.search("anything", emptyList())
        assertTrue(outcome is SemanticSearchOutcome.Unavailable)
        assertEquals(SemanticUnavailableReason.FEATURE_DISABLED, (outcome as SemanticSearchOutcome.Unavailable).reason)
    }
}
