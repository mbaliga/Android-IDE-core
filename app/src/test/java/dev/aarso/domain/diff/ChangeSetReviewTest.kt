package dev.aarso.domain.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeSetReviewTest {

    private val changeSet = ChangeSet(
        listOf(
            FileChange("a.kt", "x\ny\nz", "x\nY\nz"),       // one hunk
            FileChange("b.kt", "1\n2\n3\n4\n5", "1\nB\n3\n4\nE"), // two hunks
            FileChange("noop.kt", "same", "same"),          // dropped (no-op)
        ),
    )

    @Test fun `a review covers only the effective files`() {
        val review = ChangeSetReview(changeSet)
        assertEquals(2, review.sessions.size)
        assertEquals(setOf("a.kt", "b.kt"), review.sessions.map { it.path }.toSet())
    }

    @Test fun `approving everything reproduces the full change set`() {
        val review = ChangeSetReview(changeSet)
        review.approveAll()
        assertTrue(review.settled)
        val approved = review.approvedChangeSet()
        assertEquals(2, approved.changes.size)
        assertEquals("x\nY\nz", approved.changes.first { it.path == "a.kt" }.newText)
    }

    @Test fun `rejecting everything yields an empty change set`() {
        val review = ChangeSetReview(changeSet)
        review.rejectAll()
        assertTrue(review.approvedChangeSet().isEmpty)
        assertFalse(review.anyApproved)
    }

    @Test fun `partial approval across files produces a mixed change set`() {
        val review = ChangeSetReview(changeSet)
        // approve a.kt entirely; in b.kt approve only the first hunk
        review.sessions.first { it.path == "a.kt" }.approveAll()
        val b = review.sessions.first { it.path == "b.kt" }
        b.decide(0, Decision.APPROVED); b.decide(1, Decision.REJECTED)

        val approved = review.approvedChangeSet()
        assertEquals(2, approved.changes.size)
        assertEquals("1\nB\n3\n4\n5", approved.changes.first { it.path == "b.kt" }.newText)
    }
}
