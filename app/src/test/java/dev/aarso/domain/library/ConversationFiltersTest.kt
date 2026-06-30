package dev.aarso.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ConversationFiltersTest {

    // Fixed millis so ordering is deterministic and readable.
    private val t0 = 1_700_000_000_000L

    private fun conv(
        id: String,
        title: String = id,
        last: Long = t0,
        created: Long = t0,
        project: String? = null,
        kinds: Set<ConvKind> = setOf(ConvKind.TEXT),
        starred: Boolean = false,
        branches: Int = 0,
        uses: Int = 0,
    ) = ConversationSummary(
        id = id, title = title, lastActivityMillis = last, createdMillis = created,
        projectId = project, kinds = kinds, starred = starred,
        branchCount = branches, useCount = uses,
    )

    // ---- filter tabs: contains semantics ----

    private val textOnly = conv("text", kinds = setOf(ConvKind.TEXT))
    private val imageOnly = conv("image", kinds = setOf(ConvKind.IMAGE))
    private val mixed = conv("mixed", kinds = setOf(ConvKind.TEXT, ConvKind.IMAGE))
    private val starredMixed = conv("starredMixed", kinds = setOf(ConvKind.TEXT, ConvKind.IMAGE), starred = true)

    private val all = listOf(textOnly, imageOnly, mixed, starredMixed)

    @Test fun all_tab_keeps_everything() {
        assertEquals(all, Conversations.filter(all, ConvFilter.ALL))
    }

    @Test fun text_tab_includes_text_and_mixed_not_image() {
        val r = Conversations.filter(all, ConvFilter.TEXT)
        assertTrue(textOnly in r)
        assertTrue(mixed in r)
        assertTrue(starredMixed in r)
        assertFalse(imageOnly in r)
    }

    @Test fun image_tab_includes_image_and_mixed_not_text() {
        val r = Conversations.filter(all, ConvFilter.IMAGE)
        assertTrue(imageOnly in r)
        assertTrue(mixed in r)
        assertTrue(starredMixed in r)
        assertFalse(textOnly in r)
    }

    @Test fun mixed_chat_appears_under_all_text_and_image() {
        assertTrue(mixed in Conversations.filter(all, ConvFilter.ALL))
        assertTrue(mixed in Conversations.filter(all, ConvFilter.TEXT))
        assertTrue(mixed in Conversations.filter(all, ConvFilter.IMAGE))
    }

    @Test fun starred_tab_only_starred() {
        val r = Conversations.filter(all, ConvFilter.STARRED)
        assertEquals(listOf(starredMixed), r)
    }

    @Test fun unstarred_mixed_chat_not_in_starred_tab() {
        assertFalse(mixed in Conversations.filter(all, ConvFilter.STARRED))
    }

    @Test fun filter_predicate_matches_directly() {
        assertTrue(ConvFilter.TEXT.matches(mixed))
        assertTrue(ConvFilter.IMAGE.matches(mixed))
        assertFalse(ConvFilter.STARRED.matches(mixed))
        assertTrue(ConvFilter.STARRED.matches(starredMixed))
        assertTrue(ConvFilter.ALL.matches(imageOnly))
    }

    // ---- sort ----

    @Test fun sort_recent_descending_with_id_tiebreak() {
        val a = conv("a", last = t0 + 100)
        val b = conv("b", last = t0 + 200)
        val c = conv("c", last = t0 + 100) // ties with a -> id tie-break a before c
        val sorted = Conversations.sort(listOf(a, c, b), ConvSort.RECENT, Locale.US)
        assertEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    @Test fun sort_created_descending() {
        val a = conv("a", created = t0 + 1)
        val b = conv("b", created = t0 + 9)
        val sorted = Conversations.sort(listOf(a, b), ConvSort.CREATED, Locale.US)
        assertEquals(listOf("b", "a"), sorted.map { it.id })
    }

    @Test fun sort_most_used_descending() {
        val a = conv("a", uses = 3)
        val b = conv("b", uses = 10)
        val c = conv("c", uses = 10) // tie -> id tie-break
        val sorted = Conversations.sort(listOf(a, c, b), ConvSort.MOST_USED, Locale.US)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test fun sort_most_branched_descending() {
        val a = conv("a", branches = 1)
        val b = conv("b", branches = 5)
        val sorted = Conversations.sort(listOf(a, b), ConvSort.MOST_BRANCHED, Locale.US)
        assertEquals(listOf("b", "a"), sorted.map { it.id })
    }

    @Test fun sort_title_is_locale_collated_not_codepoint() {
        // Code-point order would put 'Z' (90) before 'a' (97) before 'á' (225).
        // A collator for English orders a < á < z case-insensitively-ish; assert that
        // 'á' is grouped with the a's and NOT after 'z', which is the code-point result.
        val apple = conv("apple", title = "apple")
        val accent = conv("accent", title = "ápple")
        val zebra = conv("zebra", title = "Zebra")
        val sorted = Conversations.sort(listOf(zebra, accent, apple), ConvSort.TITLE, Locale.ENGLISH)
        val ids = sorted.map { it.id }
        // 'ápple' must come before 'Zebra' under collation; code-point order would reverse it.
        assertTrue(ids.indexOf("accent") < ids.indexOf("zebra"))
        assertTrue(ids.indexOf("apple") < ids.indexOf("zebra"))
    }

    @Test fun sort_title_tiebreaks_equal_titles_by_id() {
        val a = conv("a", title = "same")
        val b = conv("b", title = "same")
        val sorted = Conversations.sort(listOf(b, a), ConvSort.TITLE, Locale.US)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test fun sort_does_not_mutate_input() {
        val input = listOf(conv("b", last = t0 + 1), conv("a", last = t0 + 2))
        val copy = input.toList()
        Conversations.sort(input, ConvSort.RECENT, Locale.US)
        assertEquals(copy, input)
    }

    // ---- groupByProject ----

    private fun names(map: Map<ProjectGroup, List<ConversationSummary>>) =
        map.keys.map { g ->
            when (g) {
                is ProjectGroup.Named -> g.name
                ProjectGroup.NoProject -> "<none>"
            }
        }

    @Test fun group_splits_named_and_no_project_bucket() {
        val rows = listOf(
            conv("p1a", project = "p1", last = t0 + 5),
            conv("loose", project = null, last = t0 + 1),
            conv("p2a", project = "p2", last = t0 + 3),
        )
        val grouped = Conversations.groupByProject(rows) { id -> mapOf("p1" to "Alpha", "p2" to "Beta")[id] }
        // Three buckets present.
        assertEquals(3, grouped.size)
        assertTrue(grouped.keys.contains(ProjectGroup.NoProject))
        // No-project bucket holds the loose chat.
        assertEquals(listOf("loose"), grouped[ProjectGroup.NoProject]!!.map { it.id })
    }

    @Test fun group_orders_groups_by_most_recent_activity() {
        val rows = listOf(
            conv("p1a", project = "p1", last = t0 + 10),  // p1 most recent = 10
            conv("p2a", project = "p2", last = t0 + 50),  // p2 most recent = 50
            conv("loose", project = null, last = t0 + 30), // none most recent = 30
        )
        val grouped = Conversations.groupByProject(rows) { id -> "Name-$id" }
        // Order should be p2 (50), none (30), p1 (10).
        val order = grouped.keys.map {
            when (it) {
                is ProjectGroup.Named -> it.id
                ProjectGroup.NoProject -> "none"
            }
        }
        assertEquals(listOf("p2", "none", "p1"), order)
    }

    @Test fun group_orders_rows_within_a_group_by_recent_then_id() {
        val rows = listOf(
            conv("c", project = "p1", last = t0 + 1),
            conv("a", project = "p1", last = t0 + 9),
            conv("b", project = "p1", last = t0 + 9), // ties a -> id tie-break a before b
        )
        val grouped = Conversations.groupByProject(rows) { "P1" }
        val g = grouped[ProjectGroup.Named("p1", "P1")]!!
        assertEquals(listOf("a", "b", "c"), g.map { it.id })
    }

    @Test fun group_uses_id_as_fallback_name_when_resolver_returns_null() {
        val rows = listOf(conv("x", project = "p9", last = t0))
        val grouped = Conversations.groupByProject(rows) { null }
        val key = grouped.keys.filterIsInstance<ProjectGroup.Named>().single()
        assertEquals("p9", key.id)
        assertEquals("p9", key.name)
    }

    @Test fun group_is_deterministic_across_runs() {
        val rows = listOf(
            conv("a", project = "p1", last = t0 + 5),
            conv("b", project = "p2", last = t0 + 5), // group tie -> id tie-break p1 before p2
        )
        val r1 = Conversations.groupByProject(rows) { it }
        val r2 = Conversations.groupByProject(rows) { it }
        assertEquals(r1.keys.toList(), r2.keys.toList())
        val ids = r1.keys.filterIsInstance<ProjectGroup.Named>().map { it.id }
        assertEquals(listOf("p1", "p2"), ids)
    }

    @Test fun filter_then_group_composes() {
        val rows = listOf(
            conv("t", project = "p1", kinds = setOf(ConvKind.TEXT), last = t0 + 2),
            conv("i", project = "p1", kinds = setOf(ConvKind.IMAGE), last = t0 + 1),
        )
        val filtered = Conversations.filter(rows, ConvFilter.IMAGE)
        val grouped = Conversations.groupByProject(filtered) { "P1" }
        assertEquals(1, grouped.size)
        assertEquals(listOf("i"), grouped.values.single().map { it.id })
    }

    @Test fun empty_input_yields_empty_results() {
        assertTrue(Conversations.filter(emptyList(), ConvFilter.ALL).isEmpty())
        assertTrue(Conversations.sort(emptyList(), ConvSort.RECENT, Locale.US).isEmpty())
        assertTrue(Conversations.groupByProject(emptyList()) { it }.isEmpty())
    }
}
