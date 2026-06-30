package dev.aarso.ui.state

import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.library.ConvFilter
import dev.aarso.domain.library.ConvSort
import dev.aarso.domain.library.ConversationSummary
import dev.aarso.domain.library.Conversations
import dev.aarso.domain.library.FlairSet
import dev.aarso.domain.library.ModelFlairs
import dev.aarso.domain.library.ProjectGroup
import dev.aarso.domain.state.UiState
import java.util.Locale

/**
 * **Conversations Room presenter** (Doc 02): the pure, JVM-testable presentation
 * state-holder behind the left-room list. It derives a single immutable
 * [ConversationsView] from the raw inputs by composing the already-tested domain
 * reducers ([Conversations.filter] / [Conversations.sort] / [Conversations.groupByProject])
 * and the per-row flair derivation ([ModelFlairs.deriveFlairs]).
 *
 * This object is intentionally **free of Android** — no `ViewModel`, no coroutines, no
 * `LiveData`/`StateFlow`, no clock, no I/O. Every input (the conversation list, the
 * per-conversation ledger map, the active filter/sort, the locale, the project-name
 * resolver) is supplied by the caller, so [present] is a deterministic pure function and
 * the thin Android `ViewModel` that wraps it is a separate, compile-only step.
 *
 * Output is wrapped in the [UiState] matrix (Doc 00 §3.8): [UiState.Empty] when the filter
 * matched **zero** rows (the *useful* empty — render guidance), else [UiState.Ready] over the
 * full view. Loading / Partial / Error / Offline / PermissionBlocked are the caller's concern
 * — they need context this pure layer does not have.
 */

/**
 * One rendered Conversations row: the [summary] the row is built from plus the bounded
 * [flairs] strip derived from that chat's usage-ledger entries.
 */
data class ConversationRowView(
    val summary: ConversationSummary,
    val flairs: FlairSet,
)

/**
 * The full, immutable view the Conversations room renders.
 *
 * [flat] is the filter→sort result as a single ordered list (used when `grouped` is false, or
 * by any caller that wants the rows without the project partition). [groups] is the same rows
 * partitioned into [ProjectGroup] buckets (only populated when `grouped` is true). [activeFilter]
 * and [activeSort] echo the inputs so the UI can render the active tab/sort affordance without
 * holding that state twice.
 */
data class ConversationsView(
    val groups: List<Pair<ProjectGroup, List<ConversationRowView>>>,
    val flat: List<ConversationRowView>,
    val activeFilter: ConvFilter,
    val activeSort: ConvSort,
)

/** Pure derivation of the Conversations room view. No Android, no clock, no I/O. */
object ConversationsPresenter {

    /**
     * Derive the [ConversationsView] for the room.
     *
     * Pipeline (fixed, deterministic): **filter → sort → (optional) group**, then attach the
     * per-row flair strip derived from `ledgerByConversation[id]`.
     *
     *  1. [Conversations.filter] keeps only the rows under [filter].
     *  2. [Conversations.sort] orders the survivors by [sort], collating [ConvSort.TITLE] with
     *     [locale]. Every sort ends in an id tie-break, so the order is fully repeatable.
     *  3. Each surviving summary becomes a [ConversationRowView] carrying its [FlairSet]
     *     (derived from its ledger entries, or an empty strip when it has none).
     *  4. When [grouped] is true, the sorted rows are partitioned by project via
     *     [Conversations.groupByProject] (which re-orders groups/rows by most-recent-activity);
     *     the row→flair mapping is reused so flairs stay consistent across [flat] and [groups].
     *     When [grouped] is false, [groups] is empty and only [flat] is populated.
     *
     * Empties: a non-positive filtered result yields [UiState.Empty] (render onboarding /
     * "nothing matches" guidance, per Doc 00 §3.8 — distinct from a Ready-empty list). Anything
     * with at least one row yields [UiState.Ready].
     *
     * @param all every conversation summary the room owns (already loaded by the caller).
     * @param ledgerByConversation per-conversation usage-ledger entries, keyed by
     *   [ConversationSummary.id]; a missing key means "no recorded usage yet" → empty flair strip.
     * @param filter the active filter tab.
     * @param sort the active sort key.
     * @param grouped whether to partition the rows into [ProjectGroup] buckets.
     * @param locale the locale used to collate [ConvSort.TITLE].
     * @param projectName resolves a `projectId` to its display name (or null → fall back to the id).
     */
    fun present(
        all: List<ConversationSummary>,
        ledgerByConversation: Map<String, List<LedgerEntry>>,
        filter: ConvFilter,
        sort: ConvSort,
        grouped: Boolean,
        locale: Locale,
        projectName: (String) -> String?,
    ): UiState<ConversationsView> {
        val filtered = Conversations.filter(all, filter)
        if (filtered.isEmpty()) return UiState.Empty

        val sorted = Conversations.sort(filtered, sort, locale)

        // Derive each row's flair strip once, keyed by conversation id, so flat and grouped
        // views share identical FlairSet instances and stay consistent.
        val rowById: Map<String, ConversationRowView> = sorted.associate { summary ->
            val entries = ledgerByConversation[summary.id].orEmpty()
            summary.id to ConversationRowView(summary, ModelFlairs.deriveFlairs(entries))
        }

        val flat: List<ConversationRowView> = sorted.map { rowById.getValue(it.id) }

        val groups: List<Pair<ProjectGroup, List<ConversationRowView>>> =
            if (!grouped) {
                emptyList()
            } else {
                Conversations.groupByProject(sorted, projectName)
                    .map { (group, rows) ->
                        group to rows.map { rowById.getValue(it.id) }
                    }
            }

        return UiState.Ready(
            ConversationsView(
                groups = groups,
                flat = flat,
                activeFilter = filter,
                activeSort = sort,
            )
        )
    }
}
