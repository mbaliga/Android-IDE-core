package dev.fonebrew.domain.search.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuerySuggestionsTest {

    private val values = QuerySuggestions.IndexedValues(
        projects = listOf("aarso", "hyle"),
        models = listOf("cloud:anthropic/claude-x", "local:qwen3"),
    )

    // ---- the quiet-by-default contract (the whole point of the owner's ask) ----

    @Test fun `an empty box suggests nothing`() {
        assertTrue(QuerySuggestions.suggest("").isEmpty())
    }

    @Test fun `a finished token suggests nothing`() {
        // Trailing space = nothing is being typed. Offering the field list here would just be the
        // old always-on cheat-sheet in a popup.
        assertTrue(QuerySuggestions.suggest("gradle ").isEmpty())
    }

    @Test fun `a bare word that matches no field suggests nothing`() {
        assertTrue(QuerySuggestions.suggest("gradle").isEmpty())
    }

    // ---- field completion ----

    @Test fun `a field prefix completes to field keys`() {
        val out = QuerySuggestions.suggest("pro")
        assertEquals(listOf("project:"), out.map { it.insertText })
        assertEquals(QuerySuggestions.Kind.FIELD, out.single().kind)
    }

    @Test fun `an exact field name still completes to its colon form`() {
        assertTrue(QuerySuggestions.suggest("is").any { it.insertText == "is:" })
    }

    @Test fun `field completion leaves the caret ready for a value`() {
        val s = QuerySuggestions.suggest("pro").single()
        assertEquals("project:", QuerySuggestions.apply("pro", s))
    }

    // ---- value completion: the half that used to be silence ----

    @Test fun `is offers its real values`() {
        val out = QuerySuggestions.suggest("is:").map { it.insertText }
        assertTrue(out.contains("is:starred"))
        assertTrue(out.contains("is:orphan"))
    }

    @Test fun `is never offers a value that cannot match today`() {
        val out = QuerySuggestions.suggest("is:").map { it.insertText }
        // archived has a working predicate and a column nothing writes (unbackedReason); unread
        // has nothing at all. Completing into either is a guaranteed empty result.
        assertTrue(out.none { it.contains("archived") })
        assertTrue(out.none { it.contains("unread") })
    }

    @Test fun `model offers ids actually present in the index`() {
        val out = QuerySuggestions.suggest("model:cl", values)
        assertEquals(listOf("model:cloud:anthropic/claude-x"), out.map { it.insertText })
    }

    @Test fun `project offers projects actually present in the index`() {
        assertEquals(listOf("project:hyle"), QuerySuggestions.suggest("project:h", values).map { it.insertText })
    }

    @Test fun `an empty index offers no project values rather than inventing some`() {
        assertTrue(QuerySuggestions.suggest("project:").isEmpty())
    }

    @Test fun `date fields offer expressions the parser actually resolves`() {
        val out = QuerySuggestions.suggest("after:")
        assertTrue(out.isNotEmpty())
        out.forEach { s ->
            assertTrue(
                "completion '${s.insertText}' produced a diagnostic",
                QueryParser.parse(s.insertText).diagnostics.isEmpty(),
            )
        }
    }

    @Test fun `a comparison operator has no closed value set to offer`() {
        assertTrue(QuerySuggestions.suggest("turns:>").isEmpty())
    }

    @Test fun `an unknown field offers nothing`() {
        assertTrue(QuerySuggestions.suggest("zzz:").isEmpty())
    }

    @Test fun `an unindexed field offers nothing`() {
        assertTrue(QuerySuggestions.suggest("tool:").isEmpty())
    }

    // ---- negation + insertion mechanics ----

    @Test fun `a negated token keeps its dash through completion`() {
        assertEquals(listOf("-is:starred"), QuerySuggestions.suggest("-is:sta").map { it.insertText })
    }

    @Test fun `applying a completion replaces only the token being typed`() {
        val s = QuerySuggestions.suggest("gradle is:sta").single()
        assertEquals("gradle is:starred ", QuerySuggestions.apply("gradle is:sta", s))
    }

    @Test fun `a value containing spaces is quoted so it reparses`() {
        val spaced = QuerySuggestions.IndexedValues(projects = listOf("my project"))
        val s = QuerySuggestions.suggest("project:my", spaced).single()
        assertEquals("project:\"my project\"", s.insertText)
        val facet = QueryParser.parse(s.insertText).root as QueryNode.Facet
        assertEquals("my project", facet.value)
    }

    @Test fun `the list is capped`() {
        assertTrue(QuerySuggestions.suggest("", values, limit = 2).size <= 2)
        assertTrue(QuerySuggestions.suggest("b", values, limit = 1).size <= 1)
    }

    @Test fun `every completion reparses to the field it advertises`() {
        listOf("is:", "has:", "before:", "model:c").forEach { typed ->
            QuerySuggestions.suggest(typed, values)
                .filter { it.kind == QuerySuggestions.Kind.VALUE }
                .forEach { s ->
                    val facet = QueryParser.parse(s.insertText).root
                    assertTrue("'${s.insertText}' did not parse to a facet", facet is QueryNode.Facet)
                }
        }
    }
}
