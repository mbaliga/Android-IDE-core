package dev.fonebrew.data.search.semantic

import dev.fonebrew.data.search.SearchDatabase
import dev.fonebrew.domain.search.SearchDoc
import dev.fonebrew.domain.search.SearchKind

/** What gets embedded: the same documents the lexical index holds. */
fun interface SemanticCorpus {
    suspend fun docs(): List<SearchDoc>
}

/**
 * Reads the existing FTS projections read-only, through queries that already exist in
 * `Search.sq` (the facet-only "most recent N" selects, with an unbounded limit). No new SQL, no
 * change to the index.
 */
class SearchProjectionCorpus(private val database: SearchDatabase) : SemanticCorpus {
    override suspend fun docs(): List<SearchDoc> {
        val q = database.searchQueries
        val all = Long.MAX_VALUE
        val conv = q.selectRecentWithFacets(all).executeAsList().map {
            SearchDoc(it.conv_id, it.title_raw, it.snippet_raw, it.body_raw, it.updated_at, SearchKind.TEXT)
        }
        val loops = q.selectRecentLoops(all).executeAsList().map {
            SearchDoc(it.loop_id, it.title_raw, "", it.body_raw, it.updated_at, SearchKind.LOOP)
        }
        val tasks = q.selectRecentTasks(all).executeAsList().map {
            SearchDoc(it.task_id, it.title_raw, "", it.body_raw, it.updated_at, SearchKind.TASK)
        }
        return conv + loops + tasks
    }
}
