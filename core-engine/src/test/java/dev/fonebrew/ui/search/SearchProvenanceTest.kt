package dev.fonebrew.ui.search

import dev.fonebrew.domain.search.MatchedIn
import dev.fonebrew.domain.search.SearchHit
import dev.fonebrew.domain.search.query.QueryParser
import dev.fonebrew.domain.search.semantic.MatchSource
import dev.fonebrew.domain.search.semantic.SemanticFusion
import dev.fonebrew.domain.search.semantic.doc
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProvenanceTest {
    private val zone = ZoneId.of("UTC")
    private fun hit(id: String) = SearchHit(doc(id), 1.0, MatchedIn.TITLE, listOf(0..1))
    private fun rows(hits: List<SearchHit>, prov: Map<String, MatchSource>? = null) =
        if (prov == null) SearchResultsPresenter.present(hits, 5_000L, zone, Locale.ROOT, "t")
        else SearchResultsPresenter.present(hits, 5_000L, zone, Locale.ROOT, "t", prov)

    @Test fun `semantic off - rows carry no provenance and equal the pre-semantic rows exactly`() {
        val hits = listOf(hit("a"), hit("b"))
        val off = rows(hits)
        assertTrue(off.all { it.matchedBy == null && it.provenanceChip == null })
        assertEquals(off, rows(hits, emptyMap()))
        // Whole-screen state is identical too, whether or not the provenance argument is passed.
        val p = QueryParser.parse("t")
        val a = SearchPresenter.present(p, hits, false, 2, emptyList(), null, 5_000L, zone, Locale.ROOT)
        val b = SearchPresenter.present(p, hits, false, 2, emptyList(), null, 5_000L, zone, Locale.ROOT, provenance = emptyMap())
        assertEquals(a, b)
    }

    @Test fun `semantic on - each row gets a glyph plus label chip`() {
        val r = rows(listOf(hit("a"), hit("b"), hit("c")), mapOf("a" to MatchSource.WORDS, "b" to MatchSource.MEANING, "c" to MatchSource.BOTH))
        val chips = r.map { it.provenanceChip!! }
        assertEquals("Matched by words", chips[0].label)
        assertEquals("Matched by meaning", chips[1].label)
        assertEquals("Words and meaning", chips[2].label)
        assertEquals("≈", chips[1].glyph)
        assertEquals(3, chips.map { it.glyph }.toSet().size)
        assertTrue(chips.all { it.glyph.isNotBlank() && it.label.isNotBlank() })
    }

    @Test fun `a row missing from the provenance map has no chip`() {
        val r = rows(listOf(hit("a"), hit("b")), mapOf("a" to MatchSource.MEANING))
        assertNull(r[1].provenanceChip)
    }

    @Test fun `only plain-words queries are eligible for the semantic stage`() {
        assertEquals("cat food", SemanticFusion.eligibleText(QueryParser.parse("cat food")))
        assertEquals("a b", SemanticFusion.eligibleText(QueryParser.parse("\"a b\"")))
        assertNull(SemanticFusion.eligibleText(QueryParser.parse("cat is:starred")))
        assertNull(SemanticFusion.eligibleText(QueryParser.parse("is:starred")))
        assertNull(SemanticFusion.eligibleText(QueryParser.parse("cat -dog")))
        assertNull(SemanticFusion.eligibleText(QueryParser.parse("/cat.*/")))
        assertNull(SemanticFusion.eligibleText(QueryParser.parse("")))
    }
}
