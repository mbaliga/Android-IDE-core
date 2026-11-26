package dev.fonebrew.domain.search.semantic

import dev.fonebrew.domain.search.MatchedIn
import dev.fonebrew.domain.search.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankFusionTest {

    @Test fun `provenance is words, meaning or both`() {
        val f = RankFusion.fuse(listOf("a", "b"), listOf("b", "c"))
        val by = f.associateBy { it.docId }
        assertEquals(MatchSource.WORDS, by.getValue("a").source)
        assertEquals(MatchSource.BOTH, by.getValue("b").source)
        assertEquals(MatchSource.MEANING, by.getValue("c").source)
        assertNull(by.getValue("a").semanticRank)
        assertNull(by.getValue("c").lexicalRank)
    }

    @Test fun `a doc found by both outranks one found by one list`() {
        val f = RankFusion.fuse(listOf("a", "b"), listOf("b", "c"))
        assertEquals("b", f.first().docId)
    }

    @Test fun `lexical order is preserved among words-only hits`() {
        val f = RankFusion.fuse(listOf("x", "y", "z"), emptyList())
        assertEquals(listOf("x", "y", "z"), f.map { it.docId })
        assertTrue(f.all { it.source == MatchSource.WORDS })
    }

    @Test fun `semantic-only additions are capped`() {
        val sem = (1..30).map { "s$it" }
        val f = RankFusion.fuse(listOf("a"), sem, semanticOnlyCap = 4)
        assertEquals(1 + 4, f.size)
    }

    @Test fun `both empty is empty and duplicates collapse`() {
        assertTrue(RankFusion.fuse(emptyList(), emptyList()).isEmpty())
        assertEquals(1, RankFusion.fuse(listOf("a", "a"), listOf("a", "a")).size)
    }

    @Test fun `fusion is deterministic on ties`() {
        val a = RankFusion.fuse(listOf("p"), listOf("q"))
        val b = RankFusion.fuse(listOf("p"), listOf("q"))
        assertEquals(a, b)
        assertEquals("p", a.first().docId) // lexical rank 1 ties semantic rank 1 -> lexical first
    }

    // --- SemanticFusion keeps lexical hits intact and drops unknown semantic ids ---
    private fun hit(id: String) = SearchHit(doc(id), 1.0, MatchedIn.TITLE, listOf(0..1))

    @Test fun `fuse keeps the lexical SearchHit objects and builds semantic-only hits from the corpus`() {
        val lex = listOf(hit("a"), hit("b"))
        val docs = mapOf("c" to doc("c"), "a" to doc("a"))
        val r = SemanticFusion.fuse(lex, listOf(DocSimilarity("c", 0.9f, 0), DocSimilarity("ghost", 0.8f, 0), DocSimilarity("a", 0.7f, 0)), docs)
        assertTrue(r.hits.any { it === lex[0] })
        assertEquals(setOf("a", "b", "c"), r.hits.map { it.doc.id }.toSet())
        assertEquals(MatchSource.BOTH, r.provenance["a"])
        assertEquals(MatchSource.WORDS, r.provenance["b"])
        assertEquals(MatchSource.MEANING, r.provenance["c"])
        assertNull(r.provenance["ghost"])
    }
}
