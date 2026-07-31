package dev.aarso.ui.search

import dev.aarso.ui.search.InChatFindPresenter.FindOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InChatFindPresenterTest {

    private val rows = listOf(
        "n1" to "gradle build failed",
        "n2" to "try Gradle again",
        "n3" to "no relation here",
    )

    @Test fun `blank query finds nothing and is not an error`() {
        val result = InChatFindPresenter.find(rows, "", FindOptions())
        assertTrue(result.hits.isEmpty())
        assertNull(result.error)
    }

    @Test fun `default search is case-insensitive and matches across rows`() {
        val result = InChatFindPresenter.find(rows, "gradle", FindOptions())
        assertEquals(2, result.hits.size)
        assertEquals(listOf("n1", "n2"), result.hits.map { it.nodeId })
    }

    @Test fun `case-sensitive toggle narrows to the exact case`() {
        val result = InChatFindPresenter.find(rows, "Gradle", FindOptions(caseSensitive = true))
        assertEquals(1, result.hits.size)
        assertEquals("n2", result.hits.single().nodeId)
    }

    @Test fun `whole-word toggle excludes substring-only matches`() {
        val withWord = listOf("n1" to "build failed", "n2" to "rebuild everything")
        val result = InChatFindPresenter.find(withWord, "build", FindOptions(wholeWord = true))
        assertEquals(1, result.hits.size)
        assertEquals("n1", result.hits.single().nodeId)
    }

    @Test fun `regex toggle runs a real pattern`() {
        val result = InChatFindPresenter.find(rows, "grad\\w+", FindOptions(useRegex = true))
        assertEquals(2, result.hits.size)
    }

    @Test fun `an invalid regex degrades to an error, never throws`() {
        val result = InChatFindPresenter.find(rows, "grad(", FindOptions(useRegex = true))
        assertTrue(result.hits.isEmpty())
        assertNotNull(result.error)
    }

    @Test fun `hit ranges point at the exact matched substring`() {
        val result = InChatFindPresenter.find(rows, "build", FindOptions())
        val hit = result.hits.single()
        val (_, text) = rows.single { it.first == hit.nodeId }
        assertEquals("build", text.substring(hit.range.first, hit.range.last + 1))
    }

    @Test fun `highlightsByNode groups ranges per row`() {
        val result = InChatFindPresenter.find(rows, "gradle", FindOptions())
        assertEquals(setOf("n1", "n2"), result.highlightsByNode.keys)
        assertEquals(1, result.highlightsByNode.getValue("n1").size)
    }

    @Test fun `step forward wraps past the last hit`() {
        assertEquals(0, InChatFindPresenter.step(hitCount = 3, currentIndex = 2, forward = true))
    }

    @Test fun `step backward wraps past the first hit`() {
        assertEquals(2, InChatFindPresenter.step(hitCount = 3, currentIndex = 0, forward = false))
    }

    @Test fun `step with no hits stays at -1`() {
        assertEquals(-1, InChatFindPresenter.step(hitCount = 0, currentIndex = -1, forward = true))
    }
}
