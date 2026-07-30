package dev.aarso.data.search

import dev.aarso.data.LedgerStore
import dev.aarso.data.MessageTreeRepository
import dev.aarso.data.SessionStore
import dev.aarso.domain.search.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Ties [SearchProjector] (tree → rows), [SearchIndexer] (rows → FTS5 index), and [SearchQuery]
 * (FTS5 index → ranked [SearchHit]s) into the one facade a ViewModel needs, plus the piece none
 * of those three own: actually gathering the current tree/session/ledger snapshot to project.
 *
 * There is still no automatic backfill trigger (no app-start hook, no `WorkManager` job — both
 * explicitly out of scope for this pass, see the build plan). [reindexAll] is what a caller
 * invokes to populate/refresh the index; [SearchViewModel] calls it once on creation, which is
 * enough for a search overlay opened after the app has already loaded its tree, but is not a
 * substitute for real background indexing (M6-deferred territory).
 *
 * All I/O happens off the main thread via [Dispatchers.IO] — real SQLite work, not safe to run
 * synchronously on a Compose call site.
 */
class SearchRepository(
    private val treeRepository: MessageTreeRepository,
    private val sessionStore: SessionStore,
    private val ledgerStore: LedgerStore,
    private val database: SearchDatabase,
) {

    suspend fun reindexAll(nowMillis: Long) = withContext(Dispatchers.IO) {
        val tree = treeRepository.tree()
        val starred = sessionStore.bookmarkedRoots.value
        val archived = sessionStore.archivedRoots.value
        val projects = sessionStore.conversationProjects.value
        val ledgerByConversation = ledgerStore.entries().first().groupBy { it.chatId }
        val rows = SearchProjector.project(tree, starred, archived, projects, ledgerByConversation)
        SearchIndexer.reindex(database, rows, nowMillis)
    }

    suspend fun search(query: String, nowMillis: Long, resultLimit: Int = SearchQuery.DEFAULT_RESULT_LIMIT): List<SearchHit> =
        withContext(Dispatchers.IO) {
            SearchQuery.search(database, query, nowMillis, resultLimit)
        }

    suspend fun indexedCount(): Long = withContext(Dispatchers.IO) {
        database.searchQueries.countProjections().executeAsOne()
    }
}
