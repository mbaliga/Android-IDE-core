package dev.aarso.ui.state

import dev.aarso.domain.ledger.InteractionModel
import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.ledger.Provenance
import dev.aarso.domain.ledger.Status
import dev.aarso.domain.ledger.Tier
import dev.aarso.domain.library.ConvFilter
import dev.aarso.domain.library.ConvKind
import dev.aarso.domain.library.ConvSort
import dev.aarso.domain.library.ConversationSummary
import dev.aarso.domain.library.Flair
import dev.aarso.domain.library.ProjectGroup
import dev.aarso.domain.state.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ConversationsPresenterTest {

    // Fixed epoch base so every timestamp is deterministic — no clock reads.
    private val t0 = 1_700_000_000_000L

    private fun conv(
        id: String,
        title: String = id,
        lastActivity: Long = t0,
        created: Long = t0,
        projectId: String? = null,
        kinds: Set<ConvKind> = setOf(ConvKind.TEXT),
        starred: Boolean = false,
        branchCount: Int = 0,
        useCount: Int = 0,
    ) = ConversationSummary(
        id = id,
        title = title,
        lastActivityMillis = lastActivity,
        createdMillis = created,
        projectId = projectId,
        kinds = kinds,
        starred = starred,
        branchCount = branchCount,
        useCount = useCount,
    )

    private fun entry(
        ts: Long,
        chatId: String,
        model: String,
        provenance: Provenance,
    ) = LedgerEntry(
        timestampMillis = ts,
        projectId = null,
        chatId = chatId,
        nodeId = "$chatId-$model-$ts-${provenance.name}",
        model = model,
        provider = if (provenance == Provenance.LOCAL) "on-device" else "anthropic",
        provenance = provenance,
        interactionModel = InteractionModel.SINGLE,
        councilMemberId = null,
        inputTokens = 10,
        outputTokens = 20,
        estCostMinor = 0,
        latencyMs = 5,
        tier = if (provenance == Provenance.LOCAL) Tier.ON_DEVICE else Tier.CLOUD,
        status = Status.COMPLETE,
        estimated = false,
    )

    private fun present(
        all: List<ConversationSummary>,
        ledger: Map<String, List<LedgerEntry>> = emptyMap(),
        filter: ConvFilter = ConvFilter.ALL,
        sort: ConvSort = ConvSort.RECENT,
        grouped: Boolean = false,
        locale: Locale = Locale.US,
        projectName: (String) -> String? = { null },
    ): UiState<ConversationsView> =
        ConversationsPresenter.present(all, ledger, filter, sort, grouped, locale, projectName)

    private fun ready(state: UiState<ConversationsView>): ConversationsView {
        assertTrue("expected Ready, was $state", state is UiState.Ready)
        return (state as UiState.Ready).value
    }

    // ---- Empty -----------------------------------------------------------------

    @Test fun empty_input_yields_empty_state() {
        assertTrue(present(emptyList()) is UiState.Empty)
    }

    @Test fun filter_matching_nothing_yields_empty_state() {
        // Only a text chat exists; the IMAGE tab matches zero rows → useful Empty.
        val state = present(listOf(conv("a", kinds = setOf(ConvKind.TEXT))), filter = ConvFilter.IMAGE)
        assertTrue("filter matched nothing should be Empty, was $state", state is UiState.Empty)
    }

    @Test fun starred_filter_matching_nothing_yields_empty() {
        val state = present(listOf(conv("a", starred = false)), filter = ConvFilter.STARRED)
        assertTrue(state is UiState.Empty)
    }

    // ---- Filter (mixed Text+Image under ALL / TEXT / IMAGE) --------------------

    @Test fun mixed_chat_shows_under_all() {
        val mixed = conv("m", kinds = setOf(ConvKind.TEXT, ConvKind.IMAGE))
        val view = ready(present(listOf(mixed), filter = ConvFilter.ALL))
        assertEquals(1, view.flat.size)
        assertEquals("m", view.flat[0].summary.id)
        assertEquals(ConvFilter.ALL, view.activeFilter)
    }

    @Test fun mixed_chat_shows_under_text() {
        val mixed = conv("m", kinds = setOf(ConvKind.TEXT, ConvKind.IMAGE))
        val pureImage = conv("i", kinds = setOf(ConvKind.IMAGE))
        val view = ready(present(listOf(mixed, pureImage), filter = ConvFilter.TEXT))
        assertEquals(listOf("m"), view.flat.map { it.summary.id })
    }

    @Test fun mixed_chat_shows_under_image() {
        val mixed = conv("m", kinds = setOf(ConvKind.TEXT, ConvKind.IMAGE))
        val pureText = conv("t", kinds = setOf(ConvKind.TEXT))
        val view = ready(present(listOf(mixed, pureText), filter = ConvFilter.IMAGE))
        assertEquals(listOf("m"), view.flat.map { it.summary.id })
    }

    // ---- Sort ------------------------------------------------------------------

    @Test fun recent_sort_orders_by_last_activity_desc() {
        val a = conv("a", lastActivity = t0 + 100)
        val b = conv("b", lastActivity = t0 + 300)
        val c = conv("c", lastActivity = t0 + 200)
        val view = ready(present(listOf(a, b, c), sort = ConvSort.RECENT))
        assertEquals(listOf("b", "c", "a"), view.flat.map { it.summary.id })
        assertEquals(ConvSort.RECENT, view.activeSort)
    }

    @Test fun most_used_sort_orders_by_use_count_desc() {
        val a = conv("a", useCount = 1)
        val b = conv("b", useCount = 9)
        val c = conv("c", useCount = 4)
        val view = ready(present(listOf(a, b, c), sort = ConvSort.MOST_USED))
        assertEquals(listOf("b", "c", "a"), view.flat.map { it.summary.id })
    }

    @Test fun most_branched_sort_orders_by_branch_count_desc() {
        val a = conv("a", branchCount = 2)
        val b = conv("b", branchCount = 7)
        val view = ready(present(listOf(a, b), sort = ConvSort.MOST_BRANCHED))
        assertEquals(listOf("b", "a"), view.flat.map { it.summary.id })
    }

    @Test fun recent_sort_ties_break_by_id() {
        val z = conv("z", lastActivity = t0)
        val a = conv("a", lastActivity = t0)
        val view = ready(present(listOf(z, a), sort = ConvSort.RECENT))
        assertEquals(listOf("a", "z"), view.flat.map { it.summary.id })
    }

    // ---- Locale-collated title sort -------------------------------------------

    @Test fun title_sort_is_locale_collated_not_codepoint() {
        // 'á' code-points after 'z' in raw UTF-16; a collator puts it near 'a'.
        val z = conv("z", title = "zebra")
        val accented = conv("acc", title = "ábc")
        val b = conv("b", title = "banana")
        val view = ready(present(listOf(z, accented, b), sort = ConvSort.TITLE, locale = Locale.US))
        assertEquals(listOf("acc", "b", "z"), view.flat.map { it.summary.id })
    }

    // ---- Grouping (ProjectGroup incl. NoProject) -------------------------------

    @Test fun grouping_partitions_named_and_no_project() {
        val p1 = conv("p1", projectId = "proj", lastActivity = t0 + 500)
        val loose = conv("loose", projectId = null, lastActivity = t0 + 100)
        val view = ready(
            present(
                listOf(p1, loose),
                grouped = true,
                projectName = { id -> if (id == "proj") "Project One" else null },
            )
        )
        // Two buckets present.
        assertEquals(2, view.groups.size)
        val keys = view.groups.map { it.first }
        assertTrue(keys.any { it is ProjectGroup.Named && it.id == "proj" && it.name == "Project One" })
        assertTrue(keys.contains(ProjectGroup.NoProject))
        // flat still populated alongside groups.
        assertEquals(2, view.flat.size)
    }

    @Test fun grouping_orders_groups_by_most_recent_row() {
        // NoProject has the freshest row, so it should sort ahead of the project bucket.
        val p1 = conv("p1", projectId = "proj", lastActivity = t0 + 100)
        val loose = conv("loose", projectId = null, lastActivity = t0 + 900)
        val view = ready(
            present(listOf(p1, loose), grouped = true, projectName = { "Proj" })
        )
        assertEquals(ProjectGroup.NoProject, view.groups.first().first)
    }

    @Test fun ungrouped_leaves_groups_empty() {
        val a = conv("a", projectId = "proj")
        val view = ready(present(listOf(a), grouped = false))
        assertTrue(view.groups.isEmpty())
        assertEquals(1, view.flat.size)
    }

    @Test fun grouping_resolves_unknown_project_name_to_id_fallback() {
        val a = conv("a", projectId = "unknown")
        val view = ready(present(listOf(a), grouped = true, projectName = { null }))
        val named = view.groups.map { it.first }.filterIsInstance<ProjectGroup.Named>().single()
        assertEquals("unknown", named.id)
        assertEquals("unknown", named.name)
    }

    // ---- Flair derivation wired from the ledger map ----------------------------

    @Test fun flairs_derive_from_ledger_for_each_conversation() {
        val a = conv("a")
        val b = conv("b")
        val ledger = mapOf(
            "a" to listOf(
                entry(t0 + 1, "a", "qwen", Provenance.LOCAL),
                entry(t0 + 2, "a", "claude", Provenance.CLOUD),
            ),
            "b" to listOf(entry(t0 + 1, "b", "gemma", Provenance.LOCAL)),
        )
        val view = ready(present(listOf(a, b), ledger = ledger, sort = ConvSort.TITLE, locale = Locale.US))
        val rowA = view.flat.single { it.summary.id == "a" }
        val rowB = view.flat.single { it.summary.id == "b" }
        // 'a' has two distinct flairs, most-recent (cloud claude) first.
        assertEquals(2, rowA.flairs.flairs.size)
        assertEquals(Flair("claude", Provenance.CLOUD), rowA.flairs.flairs[0])
        // 'b' has exactly one local flair.
        assertEquals(listOf(Flair("gemma", Provenance.LOCAL)), rowB.flairs.flairs)
    }

    @Test fun conversation_with_no_ledger_entry_has_empty_flairs() {
        val a = conv("a")
        val view = ready(present(listOf(a), ledger = emptyMap()))
        val rowA = view.flat.single()
        assertTrue(rowA.flairs.flairs.isEmpty())
        assertEquals(0, rowA.flairs.moreCount)
    }

    @Test fun grouped_rows_carry_same_flairs_as_flat() {
        val a = conv("a", projectId = "proj")
        val ledger = mapOf("a" to listOf(entry(t0, "a", "qwen", Provenance.LOCAL)))
        val view = ready(present(listOf(a), ledger = ledger, grouped = true, projectName = { "P" }))
        val flatRow = view.flat.single()
        val groupedRow = view.groups.single().second.single()
        assertEquals(flatRow.flairs, groupedRow.flairs)
        assertEquals(flatRow.summary.id, groupedRow.summary.id)
    }

    // ---- Determinism + echo ----------------------------------------------------

    @Test fun present_is_deterministic_across_calls() {
        val convs = listOf(
            conv("a", lastActivity = t0 + 2),
            conv("b", lastActivity = t0 + 1),
        )
        val ledger = mapOf("a" to listOf(entry(t0, "a", "qwen", Provenance.LOCAL)))
        val first = ready(present(convs, ledger = ledger, grouped = true, projectName = { "P" }))
        val second = ready(present(convs, ledger = ledger, grouped = true, projectName = { "P" }))
        assertEquals(first, second)
    }

    @Test fun active_filter_and_sort_echoed_into_view() {
        val view = ready(
            present(listOf(conv("a", starred = true)), filter = ConvFilter.STARRED, sort = ConvSort.CREATED)
        )
        assertEquals(ConvFilter.STARRED, view.activeFilter)
        assertEquals(ConvSort.CREATED, view.activeSort)
        assertFalse(view.flat.isEmpty())
    }
}
