package dev.fonebrew.domain.search.query

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [QueryCompiler.lexicalText] backs two things that must never see facet syntax: relevance
 * re-scoring, and the text handed to a chat's find bar when a search result is opened (S9
 * continuity, WP18).
 */
class LexicalTextTest {

    private fun lexical(query: String) = QueryCompiler.lexicalText(QueryParser.parse(query).root)

    @Test fun `plain terms pass through`() {
        assertEquals("gradle build", lexical("gradle build"))
    }

    @Test fun `facets are stripped`() {
        assertEquals("gradle", lexical("gradle is:starred"))
        assertEquals("gradle", lexical("is:starred gradle -is:archived"))
    }

    @Test fun `a facet-only query has no text to find`() {
        assertEquals("", lexical("is:starred after:-7d"))
    }

    @Test fun `a phrase contributes its words`() {
        assertEquals("build cache", lexical("\"build cache\""))
    }

    @Test fun `regex is stripped — a pattern is not text to highlight`() {
        assertEquals("gradle", lexical("gradle /dr\\w+/"))
        assertEquals("", lexical("/dr\\w+/"))
    }

    @Test fun `a negated term is excluded — it is a thing to avoid, not to find`() {
        assertEquals("gradle", lexical("gradle -maven"))
    }

    @Test fun `semantic text is kept as its plain-lexical fallback`() {
        assertEquals("gradle", lexical("?gradle"))
    }

    @Test fun `OR branches both contribute`() {
        assertEquals("gradle maven", lexical("(gradle OR maven)"))
    }

    @Test fun `an empty query yields empty text`() {
        assertEquals("", lexical("   "))
    }
}
