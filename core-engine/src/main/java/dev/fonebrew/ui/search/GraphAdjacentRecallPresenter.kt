package dev.fonebrew.ui.search

import dev.fonebrew.domain.search.GraphAdjacentRecall
import dev.fonebrew.domain.thread.ThreadGraph

/**
 * Lane A1 — the pure derivation behind the search overlay's **"Related context"** sheet, the
 * first real UI call site for [GraphAdjacentRecall] (see that object's own KDoc for the
 * "test-only until now" history this closes out). No Android, no I/O, no clock: same presenter
 * convention as [SearchResultsPresenter]/[SearchPresenter] — [SearchOverlay] owns the live
 * inputs (the tapped [SearchResultsPresenter.ResultRow], a [ThreadGraph] snapshot obtained
 * through [dev.fonebrew.ui.ChatViewModel.loadThreadGraph] — the exact projector path
 * `ui/graph/GraphRoom.kt` already uses, never a second/diverging projection) and this object
 * does the seed construction and the result-to-row mapping, so both are unit-testable without a
 * device.
 *
 * ### Why [GraphAdjacentRecall.expand] directly, not [dev.fonebrew.domain.scope.ContextAssembly.assembleGraphAdjacent]
 * That entry point exists for **turn-context budgeting** — it needs a [dev.fonebrew.domain.scope.Corpus]
 * of [dev.fonebrew.domain.scope.CorpusPiece]s with real token costs and a
 * [dev.fonebrew.domain.scope.ContextAssembly.ContextBudget], neither of which a "show me what's
 * graph-adjacent to this search hit" browsing sheet has. Inventing a budget/token-cost shape
 * just to satisfy that signature would fabricate numbers this app's sovereignty stance forbids
 * (CLAUDE.md: "the engine never invents numbers") — so this sheet calls the same underlying
 * traversal ([GraphAdjacentRecall.expand]) with none of that baggage.
 * [dev.fonebrew.domain.scope.ContextAssembly.assembleGraphAdjacent] therefore stays exactly as
 * test-only as the lane-C audit found it; this lane gives [GraphAdjacentRecall] itself a real
 * caller, not that wrapper.
 */
object GraphAdjacentRecallPresenter {

    /**
     * The sheet's own recall cap. [GraphAdjacentRecall.expand] takes no default — its own
     * contract leaves the cap entirely to the caller (never a number the domain layer invents on
     * its own) — so this is the UI's honest, named choice, not a domain constant.
     */
    const val DEFAULT_CAP: Int = 12

    /**
     * Builds the single-[GraphAdjacentRecall.Seed] list a "Related context" tap on [row]
     * expands from.
     *
     * [row]'s own [SearchResultsPresenter.ResultRow.convId] and
     * [SearchResultsPresenter.ResultRow.explanation] are exactly the two fields
     * [GraphAdjacentRecall.seedsFrom] reads off a raw [dev.fonebrew.domain.search.SearchHit] —
     * [SearchResultsPresenter.present] already copies both onto the row verbatim from the hit —
     * so this produces the identical [GraphAdjacentRecall.Seed] a direct
     * `GraphAdjacentRecall.seedsFrom(listOf(hit))` call would, read from what the UI already has
     * in hand instead of re-fetching the raw hit. A row with no [SearchResultsPresenter.
     * ResultRow.explanation] (a facet-only query, or a caller that ranked with `explain = false`)
     * honestly contributes an empty term list, same as [GraphAdjacentRecall.seedsFrom] itself.
     *
     * ### Why the tapped row alone, not the whole current result set
     * "Related context" is a **row-level** affordance (a per-hit action, with tap-plus-long-press
     * parity on that one row) — it means "what's graph-adjacent to *this* hit," not "to
     * everything currently on screen." Seeding from every visible hit would silently broaden
     * that meaning without the user asking for it. [GraphAdjacentRecall.seedsFrom] already
     * accepts a multi-hit list, so a future "related to these results" action (seeded from the
     * whole result set) has a real seam to extend into without this one having to pretend to be
     * it.
     */
    fun seedsFor(row: SearchResultsPresenter.ResultRow): List<GraphAdjacentRecall.Seed> =
        listOf(
            GraphAdjacentRecall.Seed(
                nodeId = row.convId,
                matchedQueryTerms = row.explanation?.termContributions?.map { it.term }?.distinct().orEmpty(),
            ),
        )

    /**
     * One recalled node, ready to render.
     *
     * @property label the projector's own [dev.fonebrew.domain.thread.ThreadGraphNode.label]
     *   when [graph] minted one for this node — never fabricated; `null` stays `null` and the
     *   sheet falls back to showing [nodeId] itself.
     * @property jumpRootId the node's own [dev.fonebrew.domain.thread.ThreadGraphNode.rootId] —
     *   the conversation to jump to (see [SearchOverlay]'s `onOpenConversation`) — when the node
     *   is still present in [ThreadGraph]; `null`, honestly, when [GraphAdjacentRecall] recalled
     *   an id [present] can't find in the snapshot it was handed (never a guessed target).
     * @property citations every [GraphAdjacentRecall.Citation] this item carries, passed through
     *   untouched — [present] never rewrites a `because` string, relabels a relation, or drops a
     *   matched term.
     */
    data class RecalledRow(
        val nodeId: String,
        val label: String?,
        val jumpRootId: String?,
        val citations: List<GraphAdjacentRecall.Citation>,
    )

    /**
     * The sheet's whole state: the capped, ordered rows to show, plus the cap surfaced honestly
     * — mirrors [GraphAdjacentRecall.Result]'s own "never a silent truncation" contract.
     */
    data class SheetState(
        val rows: List<RecalledRow>,
        val cap: Int,
        val cutCount: Int,
        val consideredCount: Int,
        val truncated: Boolean,
    )

    /**
     * Maps a real [GraphAdjacentRecall.Result] (already run against [graph]) to what the sheet
     * renders — order preserved exactly as [GraphAdjacentRecall.expand] returned it (hit rank,
     * then relation priority, then neighbour id; see that function's own KDoc).
     */
    fun present(result: GraphAdjacentRecall.Result, graph: ThreadGraph): SheetState {
        val nodesById = graph.nodes.associateBy { it.id }
        val rows = result.included.map { item ->
            val node = nodesById[item.nodeId]
            RecalledRow(
                nodeId = item.nodeId,
                label = node?.label,
                jumpRootId = node?.rootId,
                citations = item.citations,
            )
        }
        return SheetState(
            rows = rows,
            cap = result.cap,
            cutCount = result.cut.size,
            consideredCount = result.consideredCount,
            truncated = result.truncated,
        )
    }
}
