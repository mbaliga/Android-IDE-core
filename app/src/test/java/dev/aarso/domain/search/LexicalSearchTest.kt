package dev.aarso.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure on-device lexical search core ([LexicalSearch]).
 *
 * Everything is `nowMillis`-relative and deterministic — a fixed [NOW] stands in for the
 * clock, so recency and tie-breaking are reproducible. We do not assert on script-specific
 * *rendering*; for the multilingual cases we assert that a substring is **found** (non-empty
 * hits / highlights) and that a matching doc ranks above a non-matching one.
 */
class LexicalSearchTest {

    private companion object {
        /** Fixed "now" so age-based scoring is deterministic. 2025-01-01T00:00:00Z-ish. */
        const val NOW = 1_735_689_600_000L
        const val DAY = 24L * 60L * 60L * 1000L
    }

    private fun doc(
        id: String,
        title: String = "",
        snippet: String = "",
        body: String = "",
        ageDays: Long = 0,
        kind: SearchKind = SearchKind.TEXT,
    ) = SearchDoc(
        id = id,
        title = title,
        snippet = snippet,
        body = body,
        lastActivityMillis = NOW - ageDays * DAY,
        kind = kind,
    )

    // ---- normalize ----

    @Test fun `normalize folds case and collapses whitespace`() {
        assertEquals("hello world", LexicalSearch.normalize("  HELLO   World  "))
    }

    @Test fun `normalize keeps non-ascii devanagari intact`() {
        val s = "नमस्ते"
        // Script-aware: not stripped, and stable under normalize.
        assertEquals(LexicalSearch.normalize(s), LexicalSearch.normalize(s))
        assertTrue(LexicalSearch.normalize("  $s  ").contains("नमस्ते"))
    }

    @Test fun `normalize folds compatibility forms (fullwidth digits)`() {
        // NFKC maps full-width digits to ASCII.
        assertEquals("123", LexicalSearch.normalize("１２３"))
    }

    // ---- tokenizeQuery ----

    @Test fun `tokenizeQuery splits and drops blanks`() {
        assertEquals(listOf("alpha", "beta"), LexicalSearch.tokenizeQuery("  Alpha   BETA "))
    }

    @Test fun `tokenizeQuery of blank is empty`() {
        assertTrue(LexicalSearch.tokenizeQuery("    ").isEmpty())
        assertTrue(LexicalSearch.tokenizeQuery("").isEmpty())
    }

    // ---- findMatches ----

    @Test fun `findMatches returns case-insensitive original ranges`() {
        val text = "The Quick Brown Fox"
        val ranges = LexicalSearch.findMatches(text, listOf("quick"))
        assertEquals(1, ranges.size)
        // "Quick" begins at index 4 in the ORIGINAL text.
        assertEquals(4, ranges[0].first)
        assertEquals("Quick", text.substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test fun `findMatches finds devanagari substring`() {
        val text = "मैंने कल नमस्ते कहा"
        val ranges = LexicalSearch.findMatches(text, LexicalSearch.tokenizeQuery("नमस्ते"))
        assertTrue("expected a Devanagari match", ranges.isNotEmpty())
    }

    @Test fun `findMatches finds japanese substring`() {
        val text = "今日はこんにちは世界"
        val ranges = LexicalSearch.findMatches(text, LexicalSearch.tokenizeQuery("こんにちは"))
        assertTrue("expected a Japanese match", ranges.isNotEmpty())
    }

    @Test fun `findMatches merges adjacent ranges`() {
        val ranges = LexicalSearch.findMatches("aaaa", listOf("aa"))
        // Overlapping/adjacent occurrences collapse into one span.
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].first)
        assertEquals(3, ranges[0].last)
    }

    @Test fun `findMatches empty when no match`() {
        assertTrue(LexicalSearch.findMatches("hello", listOf("zzz")).isEmpty())
    }

    // ---- score ----

    @Test fun `score is null when nothing matches`() {
        val d = doc("a", title = "weather", body = "sunny today")
        assertNull(LexicalSearch.score(d, listOf("kotlin"), NOW))
    }

    @Test fun `title match outscores content-only match`() {
        val titled = doc("t", title = "kotlin coroutines", body = "misc", ageDays = 0)
        val contented = doc("c", title = "misc", body = "kotlin coroutines", ageDays = 0)
        val st = LexicalSearch.score(titled, listOf("kotlin"), NOW)!!
        val sc = LexicalSearch.score(contented, listOf("kotlin"), NOW)!!
        assertTrue("title $st should beat content $sc", st > sc)
    }

    @Test fun `more term coverage scores higher`() {
        val both = doc("b", body = "alpha beta together", ageDays = 0)
        val one = doc("o", body = "alpha only", ageDays = 0)
        val sBoth = LexicalSearch.score(both, listOf("alpha", "beta"), NOW)!!
        val sOne = LexicalSearch.score(one, listOf("alpha", "beta"), NOW)!!
        assertTrue("full coverage $sBoth should beat partial $sOne", sBoth > sOne)
    }

    @Test fun `recency boosts an equal text match`() {
        val fresh = doc("f", body = "alpha", ageDays = 0)
        val stale = doc("s", body = "alpha", ageDays = 365)
        val sFresh = LexicalSearch.score(fresh, listOf("alpha"), NOW)!!
        val sStale = LexicalSearch.score(stale, listOf("alpha"), NOW)!!
        assertTrue("fresh $sFresh should beat stale $sStale", sFresh > sStale)
    }

    @Test fun `future timestamp clamps to age zero`() {
        val future = doc("fut", body = "alpha", ageDays = -10) // lastActivity in the future
        val nowDoc = doc("now", body = "alpha", ageDays = 0)
        assertEquals(
            LexicalSearch.score(nowDoc, listOf("alpha"), NOW)!!,
            LexicalSearch.score(future, listOf("alpha"), NOW)!!,
            1e-9,
        )
    }

    @Test fun `mixed case and diacritic query still matches`() {
        val d = doc("m", title = "Café RÉSUMÉ", body = "x")
        val s = LexicalSearch.score(d, LexicalSearch.tokenizeQuery("café résumé"), NOW)
        assertNotNull(s)
    }

    // ---- search ----

    @Test fun `empty query returns empty`() {
        val docs = listOf(doc("a", title = "alpha"))
        assertTrue(LexicalSearch.search(docs, "   ", NOW).isEmpty())
    }

    @Test fun `no match returns empty`() {
        val docs = listOf(doc("a", title = "alpha", body = "beta"))
        assertTrue(LexicalSearch.search(docs, "gamma", NOW).isEmpty())
    }

    @Test fun `search ranks title hit first and reports matchedIn TITLE`() {
        val docs = listOf(
            doc("content", title = "notes", body = "kotlin flow"),
            doc("title", title = "kotlin flow", body = "notes"),
        )
        val hits = LexicalSearch.search(docs, "kotlin", NOW)
        assertEquals(2, hits.size)
        assertEquals("title", hits[0].doc.id)
        assertEquals(MatchedIn.TITLE, hits[0].matchedIn)
        assertTrue(hits[0].highlights.isNotEmpty())
        assertEquals(MatchedIn.CONTENT, hits[1].matchedIn)
    }

    @Test fun `search orders by recency for equal text matches`() {
        val docs = listOf(
            doc("old", body = "alpha", ageDays = 30),
            doc("new", body = "alpha", ageDays = 0),
        )
        val hits = LexicalSearch.search(docs, "alpha", NOW)
        assertEquals("new", hits[0].doc.id)
        assertEquals("old", hits[1].doc.id)
    }

    @Test fun `search devanagari query ranks matching doc above non-matching`() {
        val docs = listOf(
            doc("hi", title = "नमस्ते दुनिया", body = "बातचीत"),
            doc("en", title = "hello world", body = "conversation"),
        )
        val hits = LexicalSearch.search(docs, "नमस्ते", NOW)
        assertTrue("expected at least one hit", hits.isNotEmpty())
        assertEquals("hi", hits[0].doc.id)
        // The non-matching English doc must not appear.
        assertTrue(hits.none { it.doc.id == "en" })
    }

    @Test fun `search japanese query finds the matching doc`() {
        val docs = listOf(
            doc("jp", title = "こんにちは世界", body = "テスト"),
            doc("en", title = "goodbye", body = "test"),
        )
        val hits = LexicalSearch.search(docs, "こんにちは", NOW)
        assertEquals(1, hits.size)
        assertEquals("jp", hits[0].doc.id)
    }

    @Test fun `tie-breaking is deterministic by id when score and recency equal`() {
        val docs = listOf(
            doc("c", body = "alpha", ageDays = 5),
            doc("a", body = "alpha", ageDays = 5),
            doc("b", body = "alpha", ageDays = 5),
        )
        val hits = LexicalSearch.search(docs, "alpha", NOW)
        assertEquals(listOf("a", "b", "c"), hits.map { it.doc.id })
    }

    @Test fun `multi-term query coverage orders results`() {
        val docs = listOf(
            doc("full", body = "alpha beta", ageDays = 0),
            doc("half", body = "alpha gamma", ageDays = 0),
        )
        val hits = LexicalSearch.search(docs, "alpha beta", NOW)
        assertEquals("full", hits[0].doc.id)
        assertTrue(hits[0].score > hits[1].score)
    }
}
