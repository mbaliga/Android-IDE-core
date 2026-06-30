package dev.aarso.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkSelectionTest {

    // --- BulkSelection: toggle / count / isEmpty ---

    @Test
    fun emptySelectionByDefault() {
        val sel = BulkSelection()
        assertTrue(sel.isEmpty)
        assertEquals(0, sel.count)
    }

    @Test
    fun toggleAddsThenRemoves() {
        val once = BulkSelection().toggle("a")
        assertFalse(once.isEmpty)
        assertEquals(1, once.count)
        assertTrue("a" in once.selected)

        val twice = once.toggle("a")
        assertTrue(twice.isEmpty)
        assertEquals(0, twice.count)
        assertFalse("a" in twice.selected)
    }

    @Test
    fun toggleIsImmutable() {
        val base = BulkSelection()
        base.toggle("a")
        assertTrue("original unchanged", base.isEmpty)
    }

    @Test
    fun toggleMultipleDistinctIds() {
        val sel = BulkSelection().toggle("a").toggle("b").toggle("c")
        assertEquals(3, sel.count)
        assertEquals(setOf("a", "b", "c"), sel.selected)
    }

    @Test
    fun selectAllScopedToView() {
        // Pre-existing selection from "another view" must not survive a scoped select-all.
        val prior = BulkSelection().toggle("x").toggle("y")
        val view = listOf("a", "b", "c")
        val all = prior.selectAll(view)
        assertEquals(setOf("a", "b", "c"), all.selected)
        assertFalse("off-view id not silently kept", "x" in all.selected)
        assertEquals(3, all.count)
    }

    @Test
    fun selectAllEmptyViewSelectsNothing() {
        val all = BulkSelection().toggle("a").selectAll(emptyList())
        assertTrue(all.isEmpty)
    }

    @Test
    fun clearEmptiesSelection() {
        val cleared = BulkSelection().toggle("a").toggle("b").clear()
        assertTrue(cleared.isEmpty)
        assertEquals(0, cleared.count)
    }

    // --- BulkAction: destructive flag ---

    @Test
    fun onlyDeleteIsDestructive() {
        assertTrue(BulkAction.DELETE.destructive)
        assertFalse(BulkAction.STAR.destructive)
        assertFalse(BulkAction.UNSTAR.destructive)
        assertFalse(BulkAction.MOVE_TO_PROJECT.destructive)
        assertFalse(BulkAction.EXPORT.destructive)
        assertFalse(BulkAction.ARCHIVE.destructive)
    }

    // --- BulkResult: partial failure + summary ---

    @Test
    fun fullSuccessResult() {
        val r = BulkResult(attempted = 48, succeeded = 48)
        assertFalse(r.partialFailure)
        assertEquals(0, r.failed)
        assertEquals("48 done", r.summary())
    }

    @Test
    fun partialFailureResult() {
        val r = BulkResult(attempted = 48, succeeded = 46, failedIds = listOf("p", "q"))
        assertTrue(r.partialFailure)
        assertEquals(2, r.failed)
        assertEquals("46 done, 2 failed", r.summary())
    }

    @Test
    fun emptyAttemptResult() {
        val r = BulkResult(attempted = 0, succeeded = 0)
        assertFalse(r.partialFailure)
        assertEquals("Nothing to do", r.summary())
    }

    @Test
    fun failedIdsCarriedForRetry() {
        val r = BulkResult(attempted = 3, succeeded = 1, failedIds = listOf("a", "b"))
        assertEquals(listOf("a", "b"), r.failedIds)
    }

    // --- UndoWindow: open/closed by nowMillis, destructive ---

    @Test
    fun undoWindowOpenBeforeDeadline() {
        val w = UndoWindow(BulkAction.DELETE, listOf("a", "b"), deadlineMillis = 1_000L)
        assertTrue(w.isOpen(0L))
        assertTrue(w.isOpen(999L))
    }

    @Test
    fun undoWindowClosedAtAndAfterDeadline() {
        val w = UndoWindow(BulkAction.DELETE, listOf("a"), deadlineMillis = 1_000L)
        assertFalse(w.isOpen(1_000L))
        assertFalse(w.isOpen(1_001L))
    }

    @Test
    fun undoWindowCarriesActionAndIds() {
        val w = UndoWindow(BulkAction.DELETE, listOf("a", "b", "c"), deadlineMillis = 5L)
        assertEquals(BulkAction.DELETE, w.action)
        assertTrue(w.action.destructive)
        assertEquals(listOf("a", "b", "c"), w.affectedIds)
    }
}
