package dev.fonebrew.domain.search.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryParserTest {

    private fun parse(text: String) = QueryParser.parse(text)

    // ---- basics ----

    @Test fun `blank query parses to an empty, non-crashing result`() {
        val q = parse("   ")
        assertNull(q.root)
        assertNull(q.ftsExpression)
        assertTrue(q.facets.isEmpty())
        assertTrue(q.chips.isEmpty())
        assertTrue(q.diagnostics.isEmpty())
    }

    @Test fun `single term parses and compiles to a quoted prefix fts match`() {
        val q = parse("gradle")
        assertEquals(QueryNode.Term("gradle", false), q.root)
        assertEquals("\"gradle\"", q.ftsExpression)
    }

    @Test fun `juxtaposed terms are an implicit AND`() {
        val q = parse("gradle cache")
        assertEquals(QueryNode.And(listOf(QueryNode.Term("gradle", false), QueryNode.Term("cache", false))), q.root)
    }

    @Test fun `explicit AND keyword behaves the same as juxtaposition`() {
        assertEquals(parse("gradle cache").root, parse("gradle AND cache").root)
    }

    @Test fun `trailing star marks a term as an explicit prefix`() {
        val q = parse("grad*")
        assertEquals(QueryNode.Term("grad", true), q.root)
    }

    @Test fun `OR and pipe both parse to Or`() {
        assertEquals(parse("gradle OR maven").root, parse("gradle | maven").root)
        assertTrue(parse("gradle OR maven").root is QueryNode.Or)
    }

    @Test fun `leading dash negates a term`() {
        assertEquals(QueryNode.Not(QueryNode.Term("image", false)), parse("-image").root)
    }

    @Test fun `NOT keyword negates the following primary`() {
        assertEquals(QueryNode.Not(QueryNode.Term("image", false)), parse("NOT image").root)
    }

    @Test fun `parentheses group an OR inside a larger AND`() {
        val q = parse("is:starred (gradle OR maven) -has:image")
        val expected = QueryNode.And(
            listOf(
                QueryNode.Facet(Field.IS, Op.EQ, "starred"),
                QueryNode.Or(listOf(QueryNode.Term("gradle", false), QueryNode.Term("maven", false))),
                QueryNode.Not(QueryNode.Facet(Field.HAS, Op.EQ, "image")),
            ),
        )
        assertEquals(expected, q.root)
    }

    @Test fun `quoted phrase is a Phrase node and an fts phrase match`() {
        val q = parse("\"build cache\"")
        assertEquals(QueryNode.Phrase("build cache"), q.root)
        assertEquals("\"build cache\"", q.ftsExpression)
    }

    @Test fun `regex literal parses with the trailing i flag`() {
        assertEquals(QueryNode.Regex("use.*State", true), parse("/use.*State/i").root)
        assertEquals(QueryNode.Regex("use.*State", false), parse("/use.*State/").root)
    }

    @Test fun `semantic operator captures a bare word`() {
        assertEquals(QueryNode.Semantic("gradle"), parse("?gradle").root)
    }

    @Test fun `semantic operator captures a quoted phrase`() {
        assertEquals(QueryNode.Semantic("gradle build cache"), parse("?\"gradle build cache\"").root)
    }

    @Test fun `semantic text is surfaced separately for the AI-unavailable degrade path`() {
        val q = parse("?gradle")
        assertEquals("gradle", q.semanticText)
        // Hard rule 1: the raw query still runs lexically even when semantic search can't.
        assertNotNull(q.ftsExpression)
    }

    // ---- facets: every field, every operator ----

    @Test fun `every field key parses to its Field enum value`() {
        val fixtures = mapOf(
            "in:trash" to Field.IN, "is:starred" to Field.IS, "has:image" to Field.HAS,
            "project:Aarso" to Field.PROJECT, "model:opus" to Field.MODEL, "tag:idea" to Field.TAG,
            "room:chats" to Field.ROOM, "file:build.gradle.kts" to Field.FILE, "tool:bash" to Field.TOOL,
            "build:fail" to Field.BUILD, "branch:>3" to Field.BRANCH, "turns:>10" to Field.TURNS,
            "cost:>0.50" to Field.COST, "before:2026-01-01" to Field.BEFORE, "after:-7d" to Field.AFTER,
            "during:last-week" to Field.DURING, "lang:hi" to Field.LANG, "loop:abc123" to Field.LOOP,
        )
        fixtures.forEach { (text, field) ->
            val facet = parse(text).root as? QueryNode.Facet
            assertNotNull("expected a Facet for '$text'", facet)
            assertEquals(text, field, facet!!.field)
        }
    }

    @Test fun `comparison operators parse and strip from the value`() {
        assertEquals(QueryNode.Facet(Field.COST, Op.GT, "0.50"), parse("cost:>0.50").root)
        assertEquals(QueryNode.Facet(Field.COST, Op.GTE, "0.50"), parse("cost:>=0.50").root)
        assertEquals(QueryNode.Facet(Field.TURNS, Op.LT, "5"), parse("turns:<5").root)
        assertEquals(QueryNode.Facet(Field.TURNS, Op.LTE, "5"), parse("turns:<=5").root)
    }

    @Test fun `range operator is detected`() {
        val facet = parse("cost:0.10..1.00").root as QueryNode.Facet
        assertEquals(Op.RANGE, facet.op)
        assertEquals("0.10..1.00", facet.value)
    }

    @Test fun `quoted facet value is unwrapped`() {
        assertEquals(QueryNode.Facet(Field.PROJECT, Op.EQ, "my project"), parse("project:\"my project\"").root)
    }

    @Test fun `negated facet wraps in Not`() {
        assertEquals(QueryNode.Not(QueryNode.Facet(Field.HAS, Op.EQ, "image")), parse("-has:image").root)
    }

    // ---- diagnostics: degrade, never throw ----

    @Test fun `unknown field degrades to a plain term with a did-you-mean diagnostic`() {
        val q = parse("mdel:opus")
        assertEquals(QueryNode.Term("mdel:opus", false), q.root)
        val diag = q.diagnostics.filterIsInstance<Diagnostic.UnknownField>().single()
        assertEquals("mdel", diag.typed)
        assertEquals("model", diag.suggestion)
    }

    @Test fun `unindexed facet still parses but is flagged`() {
        val q = parse("tool:bash")
        assertEquals(QueryNode.Facet(Field.TOOL, Op.EQ, "bash"), q.root)
        val diag = q.diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().single()
        assertEquals(Field.TOOL, diag.field)
    }

    @Test fun `backed facet value produces no unindexed diagnostic`() {
        assertTrue(parse("is:starred").diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().isEmpty())
    }

    @Test fun `is field splits backed and unindexed by value`() {
        assertTrue(parse("is:starred").diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().isEmpty())
        assertFalse(parse("is:unread").diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().isEmpty())
    }

    @Test fun `is archived is flagged even though its predicate is real`() {
        // The one facet that used to fall through the honesty net: isBacked says yes (the column
        // and the predicate exist and are correct), nothing ever writes the column, so the user
        // got a silent zero with no explanation. The diagnostic now comes from unbackedReason.
        val diag = parse("is:archived").diagnostics.filterIsInstance<Diagnostic.UnindexedFacet>().single()
        assertEquals(Field.IS, diag.field)
        assertEquals("archived", diag.value)
    }

    @Test fun `is archived still evaluates - the diagnostic does not disable the filter`() {
        // Flagging it must not turn -is:archived into "no constraint": that would be a wrong
        // answer the day an archive action exists. See FacetEvaluatorTest for the semantics.
        assertEquals(QueryNode.Facet(Field.IS, Op.EQ, "archived"), parse("is:archived").root)
        assertTrue(isBacked(Field.IS, "archived"))
    }

    @Test fun `semantic search reports that it is unavailable`() {
        // Diagnostic.SemanticUnavailable and its UI copy both existed from the start; nothing
        // emitted it, so `?foo` silently degraded to a plain lexical search and looked like
        // semantic search working.
        val diag = parse("?migrations").diagnostics.filterIsInstance<Diagnostic.SemanticUnavailable>().single()
        assertEquals("migrations", diag.text)
    }

    @Test fun `a quoted semantic phrase reports the whole phrase`() {
        assertEquals(
            "gradle build cache",
            parse("?\"gradle build cache\"").diagnostics
                .filterIsInstance<Diagnostic.SemanticUnavailable>().single().text,
        )
    }

    @Test fun `a query with no semantic operator reports nothing about semantics`() {
        assertTrue(parse("gradle is:starred").diagnostics.filterIsInstance<Diagnostic.SemanticUnavailable>().isEmpty())
    }

    @Test fun `unterminated quote is treated as closed at end of input`() {
        val q = parse("\"build cache")
        assertEquals(QueryNode.Phrase("build cache"), q.root)
        assertTrue(q.diagnostics.any { it is Diagnostic.UnterminatedQuote })
    }

    @Test fun `unterminated regex degrades to a plain term`() {
        val q = parse("/use.*State")
        assertTrue(q.root is QueryNode.Term)
        assertTrue(q.diagnostics.any { it is Diagnostic.UnterminatedRegex })
    }

    @Test fun `invalid date value is flagged but still parses as a facet`() {
        val q = parse("before:not-a-real-date")
        assertEquals(QueryNode.Facet(Field.BEFORE, Op.EQ, "not-a-real-date"), q.root)
        assertTrue(q.diagnostics.any { it is Diagnostic.InvalidDate })
    }

    @Test fun `valid relative date produces no InvalidDate diagnostic`() {
        assertTrue(parse("after:-7d").diagnostics.filterIsInstance<Diagnostic.InvalidDate>().isEmpty())
        assertTrue(parse("before:2026-01-01").diagnostics.filterIsInstance<Diagnostic.InvalidDate>().isEmpty())
        assertTrue(parse("during:last-week").diagnostics.filterIsInstance<Diagnostic.InvalidDate>().isEmpty())
    }

    // ---- Doc §13 test 8: 25-case malformed-input corpus, never throws ----

    @Test fun `malformed input corpus never throws and always returns a result`() {
        val malformed = listOf(
            "", "   ", "\t\n", "(", ")", "((()))", "\"", "\"\"\"", "/", "//", "///",
            "?", "?\"", "AND", "OR", "NOT", "OR OR OR", "-", "--", "---foo",
            "field:", ":value", "mdel:opus", "cost:>", "before:", "gradle OR",
            "OR gradle", "((gradle)", "gradle))", "is:", "has::image", "?????",
            "😀 emoji query", "a".repeat(5000),
        )
        for (input in malformed) {
            val result = try {
                QueryParser.parse(input)
            } catch (t: Throwable) {
                throw AssertionError("parse threw for input: ${input.take(50)}", t)
            }
            assertNotNull(result)
            assertNotNull(result.chips)
            assertNotNull(result.diagnostics)
        }
    }

    // ---- Doc §13 test 9: boolean facet composition ----

    @Test fun `facets compose with boolean structure correctly`() {
        val q = parse("is:starred (gradle OR maven) -has:image")
        val root = q.root as QueryNode.And
        assertEquals(3, root.children.size)
        assertTrue(root.children[0] is QueryNode.Facet)
        assertTrue(root.children[1] is QueryNode.Or)
        assertTrue(root.children[2].let { it is QueryNode.Not && it.child is QueryNode.Facet })
        // facets are flattened out of the tree regardless of nesting depth
        assertEquals(2, q.facets.size)
        assertTrue(q.facets.any { it.field == Field.IS })
        assertTrue(q.facets.any { it.field == Field.HAS })
    }

    @Test fun `deeply nested boolean composition still flattens facets correctly`() {
        val q = parse("(is:starred OR is:archived) AND (project:Aarso OR model:opus) gradle")
        assertEquals(4, q.facets.size)
    }

    // ---- Doc §13 test 7: 40-fixture chip round-trip ----

    @Test fun `chip round-trip is stable across every fixture`() {
        val fixtures = listOf(
            "gradle", "gradle cache", "gradle AND cache", "gradle OR maven", "gradle | maven",
            "-gradle", "NOT gradle", "grad*", "\"build cache\"", "\"exact phrase here\"",
            "/use.*State/", "/use.*State/i", "?gradle", "?\"gradle build\"",
            "is:starred", "is:archived", "is:orphan", "is:unread", "has:image", "has:code",
            "has:attachment", "in:trash", "project:Aarso", "project:\"my project\"", "model:opus",
            "tag:idea", "room:chats", "file:build.gradle.kts", "tool:bash", "build:fail",
            "branch:3", "branch:>3", "turns:10", "turns:<=5", "cost:0.50", "cost:>0.50",
            "cost:0.10..1.00", "before:2026-01-01", "after:-7d", "during:last-week", "lang:hi",
            "loop:abc123", "is:starred gradle", "-has:image gradle", "is:starred (gradle OR maven)",
            "is:starred (gradle OR maven) -has:image", "(a OR b) (c OR d)",
        )
        assertTrue("need at least 40 fixtures, have ${fixtures.size}", fixtures.size >= 40)

        for (fixture in fixtures) {
            val first = parse(fixture)
            val reconstructed = first.chips.toQueryText()
            val second = parse(reconstructed)
            assertEquals(
                "round-trip unstable for fixture '$fixture' (reconstructed as '$reconstructed')",
                first.chips,
                second.chips,
            )
        }
    }

    @Test fun `chips are removable and independent`() {
        val q = parse("is:starred gradle has:image")
        assertEquals(3, q.chips.size)
        val withoutMiddle = q.chips.filterIndexed { i, _ -> i != 1 }
        assertEquals("is:starred has:image", withoutMiddle.toQueryText())
    }

    // ---- fts compilation shape ----

    @Test fun `facets never appear in the fts expression`() {
        val q = parse("is:starred gradle has:image cache")
        assertFalse(q.ftsExpression!!.contains("starred"))
        assertFalse(q.ftsExpression!!.contains("image"))
        assertTrue(q.ftsExpression!!.contains("gradle"))
        assertTrue(q.ftsExpression!!.contains("cache"))
    }

    @Test fun `pure facet query has no fts expression`() {
        assertNull(parse("is:starred").ftsExpression)
    }

    @Test fun `query syntax characters inside a quoted phrase are escaped for fts`() {
        val q = parse("\"say \"\"hello\"\"\"")
        // whatever the phrase parses to, compiling it must not produce an unescaped bare quote
        assertNotNull(q.ftsExpression)
    }
}
