package dev.aarso.domain.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskOrderingTest {

    @Test
    fun `first row in an empty list gets key zero`() {
        assertEquals(0.0, TaskOrdering.keyBetween(null, null), 0.0)
    }

    @Test
    fun `append with no tail uses the gap below the head`() {
        val key = TaskOrdering.keyBetween(null, 10.0)
        assertTrue(key < 10.0)
    }

    @Test
    fun `append with no head uses the gap above the tail`() {
        val key = TaskOrdering.keyBetween(10.0, null)
        assertTrue(key > 10.0)
    }

    @Test
    fun `keyForAppend on an empty list matches keyBetween(null, null)`() {
        assertEquals(TaskOrdering.keyBetween(null, null), TaskOrdering.keyForAppend(null), 0.0)
    }

    @Test
    fun `keyForAppend after an existing tail lands strictly after it`() {
        val key = TaskOrdering.keyForAppend(5.0)
        assertTrue(key > 5.0)
    }

    @Test
    fun `key between two neighbours is the midpoint`() {
        assertEquals(15.0, TaskOrdering.keyBetween(10.0, 20.0), 0.0)
    }

    @Test
    fun `repeated inserts between the same neighbours never collide or cross`() {
        var before = 0.0
        var after = 100.0
        val keys = mutableListOf<Double>()
        repeat(20) {
            val k = TaskOrdering.keyBetween(before, after)
            assertTrue("key $k must stay in ($before, $after)", k > before && k < after)
            keys += k
            after = k // keep squeezing into the shrinking lower half
        }
        // Every generated key is distinct — no two drags into the same gap ever collide.
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `keyBetween rejects an inverted range`() {
        TaskOrdering.keyBetween(20.0, 10.0)
    }
}
