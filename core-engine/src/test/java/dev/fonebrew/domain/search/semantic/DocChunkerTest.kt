package dev.fonebrew.domain.search.semantic

import dev.fonebrew.domain.search.Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocChunkerTest {
    private val jdk = Segmenter.JavaTextBoundarySource

    private fun words(n: Int) = (1..n).joinToString(" ") { "w$it" }

    @Test fun `short doc is one chunk with the title prefixed`() {
        val c = DocChunker.chunk(doc("a", title = "Trip plan", body = "book flights to Lisbon"), jdk)
        assertEquals(1, c.chunks.size)
        assertEquals("Trip plan\nbook flights to Lisbon", c.chunks[0].text)
        assertFalse(c.truncated)
    }

    @Test fun `long doc splits with overlap and every chunk is a real substring`() {
        val body = words(400)
        val c = DocChunker.chunk(doc("a", title = "T", body = body), jdk)
        assertTrue(c.chunks.size > 2)
        c.chunks.forEach { assertTrue(body.contains(it.text.removePrefix("T\n"))) }
        // overlap: the second chunk starts with words that ended the first
        val first = c.chunks[0].text.removePrefix("T\n").split(" ")
        val second = c.chunks[1].text.removePrefix("T\n").split(" ")
        assertEquals(first.takeLast(DocChunker.OVERLAP_WORDS), second.take(DocChunker.OVERLAP_WORDS))
        assertEquals(c.chunks.indices.toList(), c.chunks.map { it.index })
    }

    @Test fun `chunk count is capped and flagged truncated`() {
        val c = DocChunker.chunk(doc("a", body = words(200_000)), jdk)
        assertEquals(DocChunker.MAX_CHUNKS, c.chunks.size)
        assertTrue(c.truncated)
    }

    @Test fun `empty body still embeds the title, fully empty doc has no chunks`() {
        assertEquals(1, DocChunker.chunk(doc("a", title = "Only title", body = ""), jdk).chunks.size)
        assertTrue(DocChunker.chunk(doc("a", title = "", body = "", snippet = ""), jdk).chunks.isEmpty())
    }

    @Test fun `content hash changes with content and is stable otherwise`() {
        val a = doc("a", body = "one")
        assertEquals(DocChunker.contentHash(a), DocChunker.contentHash(a.copy()))
        assertNotEquals(DocChunker.contentHash(a), DocChunker.contentHash(a.copy(body = "two")))
        assertNotEquals(DocChunker.contentHash(a), DocChunker.contentHash(a.copy(title = "other")))
    }
}
