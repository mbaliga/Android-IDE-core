package dev.aarso.domain.library

import java.text.Collator
import java.util.Locale

/**
 * **Conversations Room reducers** (Doc 02): the pure filter / sort / group logic behind
 * the left-room list. Everything here is a deterministic function over a flat
 * `List<ConversationSummary>` — no Android, no clock, no storage. The room is a thin
 * reader over these.
 *
 * Composition order is fixed and documented so the UI never has to guess: **filter first,
 * then group, then sort within each group.** [groupByProject] orders both the groups and
 * the rows inside them by most-recent-activity; if a caller wants a different in-group
 * order it can [sort] each bucket after grouping. Filters and grouping compose cleanly —
 * filtering removes rows, grouping partitions whatever rows survive.
 *
 * Determinism rule (binding): every ordering ends in an `id` tie-break, so two rows that
 * are otherwise equal under the sort key always land in the same, repeatable order. No
 * sort here is "stable by accident".
 */

/**
 * The room's filter tabs. Semantics are **contains**, not exclusive: a chat that holds
 * both text and image turns satisfies [TEXT] *and* [IMAGE] *and* [ALL]. Only [STARRED]
 * is a flag rather than a kind.
 */
enum class ConvFilter {
    /** Every conversation, unfiltered. */
    ALL,
    /** Conversations whose [ConversationSummary.kinds] contains [ConvKind.TEXT]. */
    TEXT,
    /** Conversations whose [ConversationSummary.kinds] contains [ConvKind.IMAGE]. */
    IMAGE,
    /** Conversations the user pinned ([ConversationSummary.starred]). */
    STARRED,
    ;

    /** Does this conversation belong under the tab? */
    fun matches(c: ConversationSummary): Boolean = when (this) {
        ALL -> true
        TEXT -> ConvKind.TEXT in c.kinds
        IMAGE -> ConvKind.IMAGE in c.kinds
        STARRED -> c.starred
    }
}

/**
 * The room's sort keys. [TITLE] uses locale-correct collation (never raw code-point
 * order); the rest are numeric/temporal. Every key is tie-broken by [ConversationSummary.id]
 * for determinism.
 */
enum class ConvSort {
    /** Most-recent-activity first. */
    RECENT,
    /** Newest created first. */
    CREATED,
    /** Title A→Z, locale-collated. */
    TITLE,
    /** Most-used first ([ConversationSummary.useCount]). */
    MOST_USED,
    /** Most-branched first ([ConversationSummary.branchCount]). */
    MOST_BRANCHED,
}

/** A grouping bucket: a named project, or the catch-all for chats with no project. */
sealed interface ProjectGroup {
    /** A real project bucket. [name] is the resolved display name, [id] the stable key. */
    data class Named(val id: String, val name: String) : ProjectGroup
    /** The bucket for conversations whose [ConversationSummary.projectId] is `null`. */
    data object NoProject : ProjectGroup
}

/** Pure reducers over a flat list of [ConversationSummary]. */
object Conversations {

    /** Keep only the rows that belong under [tab]. Order is preserved. */
    fun filter(list: List<ConversationSummary>, tab: ConvFilter): List<ConversationSummary> =
        list.filter { tab.matches(it) }

    /**
     * Sort by [key]. [TITLE][ConvSort.TITLE] collates with [Collator] for [locale] so
     * accented/locale-specific ordering is correct rather than code-point order; all
     * other keys are numeric/temporal. Every comparison ends in an [id][ConversationSummary.id]
     * tie-break, so the result is fully deterministic.
     */
    fun sort(
        list: List<ConversationSummary>,
        key: ConvSort,
        locale: Locale = Locale.getDefault(),
    ): List<ConversationSummary> {
        val byId = compareBy<ConversationSummary> { it.id }
        val comparator: Comparator<ConversationSummary> = when (key) {
            ConvSort.RECENT -> compareByDescending<ConversationSummary> { it.lastActivityMillis }.then(byId)
            ConvSort.CREATED -> compareByDescending<ConversationSummary> { it.createdMillis }.then(byId)
            ConvSort.MOST_USED -> compareByDescending<ConversationSummary> { it.useCount }.then(byId)
            ConvSort.MOST_BRANCHED -> compareByDescending<ConversationSummary> { it.branchCount }.then(byId)
            ConvSort.TITLE -> {
                val collator = Collator.getInstance(locale)
                Comparator<ConversationSummary> { a, b -> collator.compare(a.title, b.title) }.then(byId)
            }
        }
        return list.sortedWith(comparator)
    }

    /**
     * Partition [list] into project buckets. [projectName] resolves a `projectId` to its
     * display name (or `null` if the project is unknown — the row still groups under its
     * id with the id as fallback name). Conversations with a `null`
     * [ConversationSummary.projectId] land in [ProjectGroup.NoProject].
     *
     * Both the groups and the rows within each group are ordered by **most-recent-activity**
     * (then [id][ConversationSummary.id] tie-break), so the map is deterministic.
     * [ProjectGroup.NoProject] sorts among the named groups by its own most-recent row, so
     * an active loose chat is not buried beneath a stale project.
     *
     * Composes with [filter]: filter first, then group the survivors.
     */
    fun groupByProject(
        list: List<ConversationSummary>,
        projectName: (String) -> String?,
    ): Map<ProjectGroup, List<ConversationSummary>> {
        val rowOrder = compareByDescending<ConversationSummary> { it.lastActivityMillis }
            .thenBy { it.id }

        val buckets: Map<ProjectGroup, List<ConversationSummary>> = list
            .groupBy { row ->
                val pid = row.projectId
                if (pid == null) ProjectGroup.NoProject
                else ProjectGroup.Named(pid, projectName(pid) ?: pid)
            }
            .mapValues { (_, rows) -> rows.sortedWith(rowOrder) }

        // Order the groups by their most-recent row, then id tie-break (NoProject id = "").
        val groupOrder = Comparator<Map.Entry<ProjectGroup, List<ConversationSummary>>> { a, b ->
            val aRecent = a.value.maxOf { it.lastActivityMillis }
            val bRecent = b.value.maxOf { it.lastActivityMillis }
            if (aRecent != bRecent) bRecent.compareTo(aRecent) // most-recent first
            else groupKey(a.key).compareTo(groupKey(b.key))
        }

        return buckets.entries
            .sortedWith(groupOrder)
            .associateTo(LinkedHashMap()) { it.key to it.value }
    }

    /** Stable secondary key for group ordering; [ProjectGroup.NoProject] uses "". */
    private fun groupKey(group: ProjectGroup): String = when (group) {
        is ProjectGroup.Named -> group.id
        ProjectGroup.NoProject -> ""
    }
}
