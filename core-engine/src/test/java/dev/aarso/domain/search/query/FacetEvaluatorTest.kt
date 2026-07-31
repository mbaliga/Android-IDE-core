package dev.aarso.domain.search.query

import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacetEvaluatorTest {

    private val zone = ZoneId.of("UTC")

    /** 2026-07-30T00:00:00Z. */
    private val now = 1_785_369_600_000L
    private val day = 86_400_000L

    private fun subject(
        starred: Boolean = false,
        archived: Boolean = false,
        projectId: String? = null,
        modelIds: List<String> = emptyList(),
        turnCount: Long = 0,
        branchCount: Long = 0,
        hasImage: Boolean = false,
        hasCode: Boolean = false,
        costMinor: Long = 0,
        updatedAtMillis: Long = now,
    ) = FacetSubject(starred, archived, projectId, modelIds, turnCount, branchCount, hasImage, hasCode, costMinor, updatedAtMillis)

    private fun matches(query: String, subject: FacetSubject) =
        FacetEvaluator.matches(subject, QueryParser.parse(query, now, zone).root, now, zone)

    // ---- is: / has: ----

    @Test fun `is starred`() {
        assertTrue(matches("is:starred", subject(starred = true)))
        assertFalse(matches("is:starred", subject(starred = false)))
    }

    @Test fun `is archived`() {
        assertTrue(matches("is:archived", subject(archived = true)))
        assertFalse(matches("is:archived", subject(archived = false)))
    }

    @Test fun `is orphan means no project`() {
        assertTrue(matches("is:orphan", subject(projectId = null)))
        assertTrue(matches("is:orphan", subject(projectId = "  ")))
        assertFalse(matches("is:orphan", subject(projectId = "Aarso")))
    }

    @Test fun `has image and has code`() {
        assertTrue(matches("has:image", subject(hasImage = true)))
        assertFalse(matches("has:image", subject(hasCode = true)))
        assertTrue(matches("has:code", subject(hasCode = true)))
    }

    @Test fun `an unbacked facet matches nothing rather than everything`() {
        assertFalse(matches("tool:bash", subject(starred = true)))
        assertFalse(matches("is:unread", subject()))
        assertFalse(matches("has:artifact", subject()))
    }

    // ---- project / model ----

    @Test fun `project matches case-insensitively`() {
        assertTrue(matches("project:Aarso", subject(projectId = "aarso")))
        assertFalse(matches("project:Aarso", subject(projectId = "Hyle")))
    }

    @Test fun `in is an alias for project`() {
        assertTrue(matches("in:Hyle", subject(projectId = "Hyle")))
    }

    @Test fun `model matches a substring of a namespaced model id`() {
        val s = subject(modelIds = listOf("cloud:anthropic/claude-opus", "local:qwen-7b"))
        assertTrue(matches("model:claude", s))
        assertTrue(matches("model:qwen", s))
        assertFalse(matches("model:gemini", s))
    }

    // ---- dates ----

    @Test fun `after includes today, before excludes it`() {
        val today = subject(updatedAtMillis = now + 3_600_000L)
        assertTrue(matches("after:today", today))
        assertFalse(matches("before:today", today))
    }

    @Test fun `before matches an older conversation`() {
        assertTrue(matches("before:today", subject(updatedAtMillis = now - day)))
    }

    @Test fun `after with a relative offset spans the whole window`() {
        assertTrue(matches("after:-7d", subject(updatedAtMillis = now - 3 * day)))
        assertFalse(matches("after:-7d", subject(updatedAtMillis = now - 30 * day)))
    }

    @Test fun `during matches only the named day`() {
        assertTrue(matches("during:yesterday", subject(updatedAtMillis = now - day)))
        assertFalse(matches("during:yesterday", subject(updatedAtMillis = now)))
    }

    @Test fun `an unparseable date matches nothing rather than throwing`() {
        assertFalse(matches("before:someday", subject()))
    }

    // ---- numeric comparisons ----

    @Test fun `turns with comparison operators`() {
        assertTrue(matches("turns:>5", subject(turnCount = 6)))
        assertFalse(matches("turns:>5", subject(turnCount = 5)))
        assertTrue(matches("turns:>=5", subject(turnCount = 5)))
        assertTrue(matches("turns:<5", subject(turnCount = 4)))
        assertTrue(matches("turns:<=5", subject(turnCount = 5)))
        assertTrue(matches("turns:5", subject(turnCount = 5)))
    }

    @Test fun `cost range is inclusive on both ends`() {
        assertTrue(matches("cost:10..50", subject(costMinor = 10)))
        assertTrue(matches("cost:10..50", subject(costMinor = 50)))
        assertFalse(matches("cost:10..50", subject(costMinor = 51)))
    }

    @Test fun `branch count compares like the other numerics`() {
        assertTrue(matches("branch:>0", subject(branchCount = 2)))
        assertFalse(matches("branch:>0", subject(branchCount = 0)))
    }

    @Test fun `a non-numeric value matches nothing rather than throwing`() {
        assertFalse(matches("turns:many", subject(turnCount = 9)))
    }

    // ---- boolean composition ----

    @Test fun `lexical leaves are neutral so text plus facet is just the facet here`() {
        assertTrue(matches("gradle is:starred", subject(starred = true)))
        assertFalse(matches("gradle is:starred", subject(starred = false)))
        assertTrue(matches("gradle", subject()))
    }

    @Test fun `negated facet excludes`() {
        assertTrue(matches("-is:archived", subject(archived = false)))
        assertFalse(matches("-is:archived", subject(archived = true)))
    }

    @Test fun `a negated term is neutral here, not a rejection of everything`() {
        // Regression: negation must only apply to subtrees this evaluator has an opinion about.
        // `-maven` is FTS5's job; treating it as `!true` here rejected every candidate.
        assertTrue(matches("-maven", subject()))
        assertTrue(matches("gradle -maven", subject()))
        assertTrue(matches("gradle -maven is:starred", subject(starred = true)))
        assertFalse(matches("gradle -maven is:starred", subject(starred = false)))
    }

    @Test fun `a negated phrase is likewise neutral`() {
        assertTrue(matches("-\"build cache\"", subject()))
    }

    @Test fun `AND of facets requires both`() {
        assertTrue(matches("is:starred has:code", subject(starred = true, hasCode = true)))
        assertFalse(matches("is:starred has:code", subject(starred = true, hasCode = false)))
    }

    @Test fun `OR of facets requires either`() {
        assertTrue(matches("(is:starred OR has:code)", subject(hasCode = true)))
        assertTrue(matches("(is:starred OR has:code)", subject(starred = true)))
        assertFalse(matches("(is:starred OR has:code)", subject()))
    }

    @Test fun `nested composition evaluates exactly`() {
        val q = "is:starred (has:code OR has:image) -is:archived"
        assertTrue(matches(q, subject(starred = true, hasCode = true)))
        assertTrue(matches(q, subject(starred = true, hasImage = true)))
        assertFalse(matches(q, subject(starred = true, hasCode = true, archived = true)))
        assertFalse(matches(q, subject(starred = false, hasCode = true)))
    }

    @Test fun `a null root matches everything`() {
        assertTrue(FacetEvaluator.matches(subject(), null, now, zone))
    }

    // ---- the one documented lossy shape ----

    @Test fun `a facet OR'd with text is flagged as lossy`() {
        assertTrue(FacetEvaluator.hasLossyDisjunction(QueryParser.parse("(gradle OR is:starred)", now, zone).root))
    }

    @Test fun `a facet AND'd with text is not lossy`() {
        assertFalse(FacetEvaluator.hasLossyDisjunction(QueryParser.parse("gradle is:starred", now, zone).root))
    }

    @Test fun `an all-facet OR is not lossy`() {
        assertFalse(FacetEvaluator.hasLossyDisjunction(QueryParser.parse("(is:starred OR has:code)", now, zone).root))
    }

    @Test fun `an all-text OR is not lossy`() {
        assertFalse(FacetEvaluator.hasLossyDisjunction(QueryParser.parse("(gradle OR maven)", now, zone).root))
    }

    @Test fun `the documented lossy case evaluates to the facet-neutral answer`() {
        // Pinned, not accidental: text-side TRUE makes the whole OR true, so the facet does not
        // narrow. Too-few rows (the FTS side still constrains), never wrong ones.
        assertTrue(matches("(gradle OR is:starred)", subject(starred = false)))
    }
}
