package dev.fonebrew.domain.tree

import dev.fonebrew.domain.cloud.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {

    @Test
    fun emptyListRoundTrips() {
        val encoded = Sources.encode(emptyList())
        assertEquals("[]", encoded)
        assertEquals(emptyList<Source>(), Sources.decode(encoded))
    }

    @Test
    fun oneItemRoundTrips() {
        val original = listOf(Source(title = "F1 news", url = "https://example.com/f1"))
        val decoded = Sources.decode(Sources.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun multipleItemsRoundTripInOrder() {
        val original = listOf(
            Source(title = "One", url = "https://example.com/1"),
            Source(title = "Two", url = "https://example.com/2"),
            Source(title = "Three", url = "https://example.com/3"),
        )
        val decoded = Sources.decode(Sources.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun nullOrBlankDecodesToEmpty() {
        assertEquals(emptyList<Source>(), Sources.decode(null))
        assertEquals(emptyList<Source>(), Sources.decode(""))
        assertEquals(emptyList<Source>(), Sources.decode("   "))
    }

    @Test
    fun malformedJsonDecodesToEmptyRatherThanThrowing() {
        assertEquals(emptyList<Source>(), Sources.decode("not json"))
        assertEquals(emptyList<Source>(), Sources.decode("{broken"))
        assertEquals(emptyList<Source>(), Sources.decode("""{"url":"a"}""")) // object, not array
    }

    @Test
    fun malformedElementsInAnOtherwiseValidArrayAreSkippedNotFatal() {
        val json = """[{"title":"A","url":"https://a"}, "garbage", 42, {"title":"no url"}, {}]"""
        val decoded = Sources.decode(json)
        assertEquals(listOf(Source(title = "A", url = "https://a")), decoded)
    }

    @Test
    fun decodeFallsBackToUrlAsTitleWhenTitleMissingOrEmpty() {
        assertEquals(
            listOf(Source(title = "https://a", url = "https://a")),
            Sources.decode("""[{"url":"https://a"}]"""),
        )
        assertEquals(
            listOf(Source(title = "https://a", url = "https://a")),
            Sources.decode("""[{"title":"","url":"https://a"}]"""),
        )
    }

    @Test
    fun encodePreservesInsertionOrder() {
        val original = listOf(
            Source("Z", "https://z"),
            Source("A", "https://a"),
        )
        val encoded = Sources.encode(original)
        assertTrue(encoded.indexOf("https://z") < encoded.indexOf("https://a"))
    }
}
