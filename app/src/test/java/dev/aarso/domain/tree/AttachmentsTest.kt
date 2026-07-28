package dev.aarso.domain.tree

import dev.aarso.domain.tree.Attachments.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {

    @Test
    fun emptyListRoundTrips() {
        val encoded = Attachments.encode(emptyList())
        assertEquals("[]", encoded)
        assertEquals(emptyList<Attachment>(), Attachments.decode(encoded))
    }

    @Test
    fun oneItemRoundTrips() {
        val original = listOf(Attachment(path = "/data/attachments/a.jpg", mime = "image/jpeg"))
        val decoded = Attachments.decode(Attachments.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun multipleItemsRoundTripInOrder() {
        val original = listOf(
            Attachment(path = "/data/attachments/a.jpg", mime = "image/jpeg"),
            Attachment(path = "/data/attachments/b.png", mime = "image/png"),
            Attachment(path = "/data/attachments/c.webp", mime = "image/webp"),
        )
        val decoded = Attachments.decode(Attachments.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun nullOrBlankDecodesToEmpty() {
        assertEquals(emptyList<Attachment>(), Attachments.decode(null))
        assertEquals(emptyList<Attachment>(), Attachments.decode(""))
        assertEquals(emptyList<Attachment>(), Attachments.decode("   "))
    }

    @Test
    fun malformedJsonDecodesToEmptyRatherThanThrowing() {
        assertEquals(emptyList<Attachment>(), Attachments.decode("not json"))
        assertEquals(emptyList<Attachment>(), Attachments.decode("{broken"))
        assertEquals(emptyList<Attachment>(), Attachments.decode("{\"path\":\"a\"}")) // object, not array
    }

    @Test
    fun malformedElementsInAnOtherwiseValidArrayAreSkippedNotFatal() {
        val json = """[{"path":"/a.jpg","mime":"image/jpeg"}, "garbage", 42, {"mime":"image/png"}, {}]"""
        val decoded = Attachments.decode(json)
        // Only the first element has a usable non-empty "path"; the string/number entries and
        // the path-less objects are tolerated (skipped), not fatal.
        assertEquals(listOf(Attachment(path = "/a.jpg", mime = "image/jpeg")), decoded)
    }

    @Test
    fun decodeToleratesMissingMimeAsEmptyString() {
        val decoded = Attachments.decode("""[{"path":"/a.jpg"}]""")
        assertEquals(listOf(Attachment(path = "/a.jpg", mime = "")), decoded)
    }

    @Test
    fun encodePreservesInsertionOrder() {
        val original = listOf(
            Attachment("/z.jpg", "image/jpeg"),
            Attachment("/a.jpg", "image/jpeg"),
        )
        val encoded = Attachments.encode(original)
        assertTrue(encoded.indexOf("/z.jpg") < encoded.indexOf("/a.jpg"))
    }
}
