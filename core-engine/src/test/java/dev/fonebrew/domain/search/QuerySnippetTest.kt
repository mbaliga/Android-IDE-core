package dev.fonebrew.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuerySnippetTest {

    private val lead = "Opening chatter with nothing to do with it. ".repeat(6)
    private val hit = "The gradle build cache was invalidated by the annotation processor. "
    private val tail = "More trailing chatter. ".repeat(6)
    private val body = lead + hit + tail

    private fun terms(query: String) = LexicalSearch.tokenizeQuery(query)

    @Test fun `the preview is centred on the match, not on the start of the conversation`() {
        val snippet = QuerySnippet.centered(body, projected = lead.take(220), ftsSnippet = null, queryTerms = terms("gradle"))
        assertTrue(snippet, snippet.contains("gradle"))
    }

    @Test fun `the preview stays within one window`() {
        val snippet = QuerySnippet.centered(body, "", null, terms("gradle"))
        // + the two ellipsis characters this window is allowed to add.
        assertTrue(snippet.length.toString(), snippet.length <= QuerySnippet.WINDOW_CHARS + 2)
    }

    @Test fun `a cut on either side is marked`() {
        val snippet = QuerySnippet.centered(body, "", null, terms("gradle"))
        assertTrue(snippet, snippet.startsWith("…") && snippet.endsWith("…"))
    }

    @Test fun `nothing is marked as cut when nothing was cut`() {
        assertEquals("gradle only", QuerySnippet.centered("gradle only", "", null, terms("gradle")))
    }

    @Test fun `the highlighter finds the term in the text actually drawn`() {
        // This is the property the result row depends on: SearchResultsPresenter recomputes
        // highlights over this string, so a window the term isn't inside would render unmarked.
        val snippet = QuerySnippet.centered(body, "", null, terms("gradle"))
        assertTrue(LexicalSearch.findMatches(snippet, terms("gradle")).isNotEmpty())
    }

    @Test fun `a match at the very end of a long body is still shown`() {
        val snippet = QuerySnippet.centered("x".repeat(500) + " gradle", "", null, terms("gradle"))
        assertTrue(snippet, snippet.contains("gradle"))
    }

    @Test fun `turn separators are collapsed so the row reads as one line`() {
        val snippet = QuerySnippet.centered("first turn\n\nsecond gradle turn", "", null, terms("gradle"))
        assertFalse(snippet, snippet.contains("\n"))
    }

    @Test fun `a prefix query still centres on the word it prefixed`() {
        val snippet = QuerySnippet.centered(body, "", null, terms("gradl"))
        assertTrue(snippet, snippet.contains("gradle"))
    }

    @Test fun `a facet-only query keeps the index-time snippet`() {
        // No term to centre on — inventing a window would be worse than the honest projection.
        assertEquals("projected line", QuerySnippet.centered(body, "projected line", null, emptyList()))
    }

    @Test fun `FTS5's own snippet is the fallback when a raw scan cannot find the term`() {
        // The ligature case: the index stores NFKC-normalized text, so this conversation is
        // indexed and matched as "office" — a string that does not occur in the raw body at all,
        // which is why a Kotlin indexOf over the original text cannot find it and FTS5 can.
        val snippet = QuerySnippet.centered(
            bodyRaw = "the oﬃce build notes",
            projected = "projected",
            ftsSnippet = "the office build notes",
            queryTerms = terms("oﬃce"),
        )
        assertEquals("the office build notes", snippet)
    }

    @Test fun `a CJK term is found in the raw body without needing the FTS5 fallback`() {
        // Segmentation decides which tokens the index matched, but the matched token is still a
        // literal substring of the conversation, so the raw window handles it — no special case.
        assertEquals("東京駅で会った", QuerySnippet.centered("東京駅で会った", "projected", null, terms("駅")))
    }

    @Test fun `an FTS5 snippet that does not contain the term is not used`() {
        // A title-only match: snippet() returns the body column's leading text, which is a
        // lower-cased, punctuation-stripped version of what the projected snippet says better.
        assertEquals(
            "Projected, with punctuation.",
            QuerySnippet.centered(
                bodyRaw = "unrelated body text",
                projected = "Projected, with punctuation.",
                ftsSnippet = "unrelated body text",
                queryTerms = terms("gradle"),
            ),
        )
    }

    @Test fun `the projected snippet is the last resort, never an empty row`() {
        assertEquals(
            "projected",
            QuerySnippet.centered("unrelated body", "projected", ftsSnippet = null, queryTerms = listOf("zzz")),
        )
    }

    @Test fun `an empty everything degrades to a clipped body rather than blank`() {
        assertEquals("unrelated body", QuerySnippet.centered("unrelated body", "", null, listOf("zzz")))
    }
}
