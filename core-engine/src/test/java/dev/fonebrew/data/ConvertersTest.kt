package dev.fonebrew.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for a real crash (SQLiteConstraintException: NOT NULL constraint failed:
 * tasks.dependsOn): the converters used to collapse an empty (but non-null) collection to a
 * `null` stored value, which fails a NOT NULL column on every insert of a task with no
 * dependencies/tags — i.e. every task. Only a genuinely null input should ever convert to null.
 */
class ConvertersTest {

    private val c = Converters()

    @Test
    fun emptyStringListConvertsToEmptyArrayNotNull() {
        assertEquals("[]", c.fromStringList(emptyList()))
    }

    @Test
    fun nullStringListConvertsToNull() {
        assertNull(c.fromStringList(null))
    }

    @Test
    fun stringListRoundTrips() {
        val values = listOf("a", "b", "c")
        assertEquals(values, c.toStringList(c.fromStringList(values)))
    }

    @Test
    fun emptyMetadataConvertsToEmptyObjectNotNull() {
        assertEquals("{}", c.fromMetadata(emptyMap()))
    }

    @Test
    fun nullMetadataConvertsToNull() {
        assertNull(c.fromMetadata(null))
    }

    @Test
    fun metadataRoundTrips() {
        val map = mapOf("k1" to "v1", "k2" to "v2")
        assertEquals(map, c.toMetadata(c.fromMetadata(map)))
    }
}
