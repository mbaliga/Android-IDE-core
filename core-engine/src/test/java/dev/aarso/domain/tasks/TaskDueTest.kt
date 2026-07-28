package dev.aarso.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDueTest {

    private val now = 1_752_192_000_000L // fixed clock — 2026-07-11T00:00:00Z-ish

    @Test
    fun `a past due date is overdue, never asserts a day count`() {
        val label = TaskDue.label(now - 60_000, now)
        assertTrue(label.overdue)
        assertEquals("overdue", label.text)
    }

    @Test
    fun `due within the hour reads as due soon, not overdue`() {
        val label = TaskDue.label(now + 30 * 60_000, now)
        assertFalse(label.overdue)
        assertEquals("due soon", label.text)
    }

    @Test
    fun `due later today reads as today`() {
        val label = TaskDue.label(now + 5 * 60 * 60_000, now)
        assertFalse(label.overdue)
        assertEquals("today", label.text)
    }

    @Test
    fun `due in three days reads as in 3d`() {
        val label = TaskDue.label(now + 3 * 24 * 60 * 60_000L, now)
        assertFalse(label.overdue)
        assertEquals("in 3d", label.text)
    }

    @Test
    fun `exactly on the clock is not overdue`() {
        val label = TaskDue.label(now, now)
        assertFalse(label.overdue)
    }
}
