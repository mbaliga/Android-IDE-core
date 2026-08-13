package dev.aarso.data.search

import dev.aarso.data.LedgerStore
import dev.aarso.data.MessageTreeRepository
import dev.aarso.data.SessionStore
import dev.aarso.data.ThreadMarkerStore
import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.search.SearchHit
import dev.aarso.domain.search.query.ParsedQuery
import dev.aarso.domain.tree.MessageTree
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Ties [SearchProjector] (tree → rows), [SearchIndexer] (rows → FTS5 index), and [SearchQuery]
 * (FTS5 index → ranked [SearchHit]s) into the one facade a ViewModel needs, plus the piece none
 * of those three own: actually gathering the current tree/session/ledger snapshot to project.
 *
 * [keepIndexFresh] is the entry point a long-lived caller wants: it backfills once, then keeps
 * the index in step with the tree/session/ledger for as long as its coroutine lives, so a
 * conversation edited while the app is running is searchable without reopening anything.
 * [reindexAll] remains available for a one-shot rebuild.
 *
 * Still deliberately out of scope (M6 territory, per the build plan): running when the app
 * *isn't* — no `WorkManager` job, no battery/thermal/idle scheduling ladder. Indexing here
 * happens only while something is actually collecting [keepIndexFresh].
 *
 * All I/O happens off the main thread via [Dispatchers.IO] — real SQLite work, not safe to run
 * synchronously on a Compose call site.
 */
@OptIn(FlowPreview::class)
class SearchRepository(
    private val treeRepository: MessageTreeRepository,
    private val sessionStore: SessionStore,
    private val ledgerStore: LedgerStore,
    /** THREAD_TOPOLOGY_PLAN.md WP6: source of the `lineage_parent`/`lineage_kind`/
     *  `chapter_count`/`compaction_count` facet columns — the same [ThreadMarkerStore]
     *  [dev.aarso.ui.ChatViewModel] already reads for [dev.aarso.domain.thread.ThreadChains]. */
    private val threadMarkerStore: ThreadMarkerStore,
    private val database: SearchDatabase,
) {

    suspend fun reindexAll(nowMillis: Long) = withContext(Dispatchers.IO) {
        val tree = treeRepository.tree()
        val starred = sessionStore.bookmarkedRoots.value
        val archived = sessionStore.archivedRoots.value
        val projects = sessionStore.conversationProjects.value
        val ledgerByConversation = ledgerStore.entries().first().groupBy { it.chatId }
        val markersByRoot = threadMarkerStore.markers.first().groupBy { it.rootId }
        val rows = SearchProjector.project(tree, starred, archived, projects, ledgerByConversation, markersByRoot)
        SearchIndexer.reindex(database, rows, nowMillis)
    }

    /**
     * Backfills once, then keeps indexing for as long as the calling coroutine lives (WP17).
     *
     * Suspends forever by design — the caller scopes its lifetime (`viewModelScope`), and
     * cancelling the scope stops indexing. [onIndexed] reports the row count after each pass so
     * a UI can show index health without querying for it.
     *
     * Every input that can change what a conversation matches is watched, not just the tree:
     * starring, archiving and project assignment all change facet values without touching a
     * conversation's last-activity time, the ledger changes its `cost:`, and (THREAD_TOPOLOGY_PLAN.md
     * WP6) a fork/spawn or a chapter/compaction-run marker changes `lineage_parent`/`lineage_kind`/
     * `chapter_count`/`compaction_count` the exact same way.
     *
     * The [DEBOUNCE_MS] window matters more than it looks: a generating turn writes to the tree
     * repeatedly, and re-projecting the whole corpus per write would be pure waste. Debouncing
     * coalesces a burst into one pass once things settle. [SearchIndexer.sync] then writes only
     * genuine deltas, so a pass over an unchanged corpus costs one read and no writes.
     */
    suspend fun keepIndexFresh(
        nowMillis: () -> Long = { System.currentTimeMillis() },
        onIndexed: (Long) -> Unit = {},
    ): Nothing = withContext(Dispatchers.IO) {
        // Finish any interrupted cold-start backfill first: reindex() is the chunked, resumable
        // path, and it short-circuits cheaply when the corpus is already fully processed.
        reindexAll(nowMillis())
        onIndexed(database.searchQueries.countProjections().executeAsOne())

        // kotlinx.coroutines' typed `combine` tops out at 5 flows; nested rather than switching to
        // the untyped vararg form (which would trade this file's compile-time column safety for a
        // same-shape `Array<Any?>` cast) to fold in the 6th (thread markers, WP6).
        combine(
            combine(
                treeRepository.observeTree(),
                sessionStore.bookmarkedRoots,
                sessionStore.archivedRoots,
                sessionStore.conversationProjects,
                ledgerStore.entries(),
            ) { tree, starred, archived, projects, ledger -> ProjectionInputs(tree, starred, archived, projects, ledger) },
            threadMarkerStore.markers,
        ) { inputs, markers ->
            SearchProjector.project(
                inputs.tree, inputs.starred, inputs.archived, inputs.projects,
                inputs.ledger.groupBy { it.chatId }, markers.groupBy { it.rootId },
            )
        }
            .debounce(DEBOUNCE_MS)
            .collect { rows ->
                val result = SearchIndexer.sync(database, rows, nowMillis())
                if (!result.isNoOp) onIndexed(database.searchQueries.countProjections().executeAsOne())
            }

        error("keepIndexFresh collects forever; reaching here means the source flow completed")
    }

    /** The pre-WP6 5-flow [combine]'s output, named so the outer 2-flow [combine] (adding
     *  [ThreadMarkerStore.markers]) reads as a plain pair rather than an anonymous [Pair] of
     *  five things. */
    private data class ProjectionInputs(
        val tree: MessageTree,
        val starred: Set<String>,
        val archived: Set<String>,
        val projects: Map<String, String>,
        val ledger: List<LedgerEntry>,
    )

    /** Takes an already-[ParsedQuery] rather than raw text: the search overlay parses on every
     *  keystroke to paint chips/diagnostics, so re-parsing here would be waste, and a second
     *  parse could in principle disagree with the one the user is looking at. */
    suspend fun search(
        parsed: ParsedQuery,
        nowMillis: Long,
        resultLimit: Int = SearchQuery.DEFAULT_RESULT_LIMIT,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        SearchQuery.search(database, parsed, nowMillis, resultLimit, zone)
    }

    suspend fun indexedCount(): Long = withContext(Dispatchers.IO) {
        database.searchQueries.countProjections().executeAsOne()
    }

    private companion object {
        /** Coalesces a burst of tree writes (a turn being generated) into one indexing pass. */
        const val DEBOUNCE_MS = 1_500L
    }
}
