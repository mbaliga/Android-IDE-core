package dev.aarso.domain.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSessionTest {

    // Two separate change regions so there are two independently-decidable hunks.
    private val old = "line1\nline2\nline3\nline4\nline5"
    private val new = "line1\nCHANGED2\nline3\nline4\nCHANGED5"

    @Test fun `a multi-region change splits into multiple hunks`() {
        val rs = ReviewSession("f", old, new)
        assertEquals(2, rs.hunks.size)
        assertFalse(rs.settled) // all pending initially
    }

    @Test fun `approving all yields the new text`() {
        val rs = ReviewSession("f", old, new)
        rs.approveAll()
        assertTrue(rs.settled)
        assertEquals(new, rs.applied())
    }

    @Test fun `rejecting all yields the old text`() {
        val rs = ReviewSession("f", old, new)
        rs.rejectAll()
        assertEquals(old, rs.applied())
    }

    @Test fun `approving only the first hunk applies it but not the second`() {
        val rs = ReviewSession("f", old, new)
        rs.decide(0, Decision.APPROVED)
        rs.decide(1, Decision.REJECTED)
        assertEquals("line1\nCHANGED2\nline3\nline4\nline5", rs.applied())
        assertEquals(listOf(0), rs.approvedIndices())
    }

    @Test fun `approving only the second hunk applies it but not the first`() {
        val rs = ReviewSession("f", old, new)
        rs.decide(0, Decision.REJECTED)
        rs.decide(1, Decision.APPROVED)
        assertEquals("line1\nline2\nline3\nline4\nCHANGED5", rs.applied())
    }

    @Test fun `a pure insertion hunk applies at the right place`() {
        val rs = ReviewSession("f", "a\nb\nc", "a\nNEW\nb\nc")
        rs.approveAll()
        assertEquals("a\nNEW\nb\nc", rs.applied())
    }

    @Test fun `a creation (empty old) is one hunk`() {
        val rs = ReviewSession("f", "", "hello\nworld")
        assertEquals(1, rs.hunks.size)
        rs.approveAll()
        assertEquals("hello\nworld", rs.applied())
        rs.rejectAll()
        assertEquals("", rs.applied())
    }

    @Test fun `approvedChange reduces to only what was approved`() {
        val rs = ReviewSession("src/A.kt", old, new)
        rs.decide(0, Decision.APPROVED)
        rs.decide(1, Decision.REJECTED)
        val fc = rs.approvedChange()
        assertEquals("src/A.kt", fc.path)
        assertEquals(rs.applied(), fc.newText)
        assertEquals(1, fc.stat.added)  // only the first change applied
    }
}
