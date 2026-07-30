package dev.aarso.data.search

/**
 * Writes [SearchProjector.Row]s into [SearchDatabase], chunked and resumable (Doc
 * `FONEBREW_SEARCH_SPEC.md` §10.5, WP6). No battery/thermal/idle scheduling ladder here — that's
 * explicitly under the self-organization engine (M6), out of scope for this pass; this is a
 * plain chunked writer meant to be driven by a `WorkManager` job or an app-open hook.
 *
 * ### What "resumable" means here
 * The spec's `WHERE rowid > last_indexed_rowid` assumes iterating a stable Room cursor
 * row-by-row. [SearchProjector.project] instead computes the *whole* conversation list in one
 * pass (same as the existing, unwired `ConversationsStore.summaries()` pipeline does for the
 * Chats list) — cheap in-memory Kotlin, not a paginated DB scan. So [reindex] reinterprets
 * `last_indexed_rowid` as **how many rows of the (deterministically-ordered) projected list have
 * been written**, not a literal SQLite rowid. This is safe because [SearchProjector.project] is
 * a pure function of the same [MessageTree][dev.aarso.domain.tree.MessageTree] state: called
 * again with unchanged inputs, it returns the rows in the same order (`Conversations.summarize`
 * sorts by `lastUpdatedAt` over a list built from a deterministic tree walk) — resuming at
 * `rows.drop(processed)` after a kill mid-backfill lands on exactly the rows that weren't
 * written yet.
 *
 * ### Idempotent writes, so resume never double-counts or throws
 * [upsertProjection]/[upsertFacets] (see `Search.sq`) are `ON CONFLICT DO UPDATE`, so re-writing
 * a row a resumed run already wrote (e.g. because a chunk committed but the progress callback
 * fired after the process died) is a harmless overwrite with identical values, not a duplicate
 * or a thrown constraint violation.
 *
 * ### Projection-version bump = fresh rebuild, not silent staleness
 * If `index_state.projection_version` doesn't match [SearchProjector.PROJECTION_VERSION],
 * [reindex] restarts from row 0 rather than resuming — every row gets re-upserted under the new
 * projection logic. Nothing is deleted first; upserts are self-healing (every conv_id present in
 * [rows] gets overwritten with current data either way).
 */
object SearchIndexer {

    const val DEFAULT_CHUNK_SIZE = 300
    const val TOKENIZER_VERSION = 1L
    const val SCHEMA_VERSION = 1L

    data class Progress(val processed: Int, val total: Int)

    /**
     * @param rows the full, deterministically-ordered projection (see class KDoc on why this is
     *   safe to recompute and re-pass on every call, including a resume).
     * @param nowMillis stamped into `index_state.updated_at` — no clock read internally, so this
     *   stays testable like the rest of this codebase's pure logic.
     * @param isCancelled polled between chunks (not mid-chunk — a chunk transaction always
     *   completes once started, so a cancel lands on a chunk boundary, never a half-written one).
     */
    fun reindex(
        database: SearchDatabase,
        rows: List<SearchProjector.Row>,
        nowMillis: Long,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onProgress: ((Progress) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ) {
        val state = database.searchQueries.selectIndexState().executeAsOneOrNull()
        val needsFreshRebuild = state == null || state.projection_version != SearchProjector.PROJECTION_VERSION
        var processed = if (needsFreshRebuild) 0 else state!!.last_indexed_rowid.toInt().coerceIn(0, rows.size)

        if (processed >= rows.size) {
            onProgress?.invoke(Progress(rows.size, rows.size))
            return
        }

        rows.drop(processed).chunked(chunkSize.coerceAtLeast(1)).forEach { chunk ->
            if (isCancelled()) return
            database.transaction {
                chunk.forEach { row -> writeRow(database, row) }
                processed += chunk.size
                database.searchQueries.upsertIndexState(
                    last_indexed_rowid = processed.toLong(),
                    projection_version = SearchProjector.PROJECTION_VERSION,
                    tokenizer_version = TOKENIZER_VERSION,
                    schema_version = SCHEMA_VERSION,
                    updated_at = nowMillis,
                )
            }
            onProgress?.invoke(Progress(processed, rows.size))
        }
    }

    /** Removes a conversation from the index entirely (deleted conversation, not merely
     *  archived — archived conversations stay indexed so `is:archived` can find them). Cascades
     *  to `conv_facets` (`ON DELETE CASCADE`) and `conv_fts` (the `conv_projection_ad` trigger). */
    fun remove(database: SearchDatabase, convId: String) {
        database.searchQueries.deleteProjection(convId)
    }

    /** What one [sync] pass actually changed. Both zero means the index was already current —
     *  the common case once the app has been running a while. */
    data class SyncResult(val updated: Int, val removed: Int) {
        val isNoOp: Boolean get() = updated == 0 && removed == 0
    }

    /**
     * Brings an **already-backfilled** index in line with [rows] (WP17): upserts only the
     * conversations whose projected content or facets actually differ from what's indexed, and
     * drops rows for conversations that no longer exist.
     *
     * This is the incremental counterpart to [reindex]. [reindex] is the chunked, resumable
     * cold-start path and deliberately short-circuits once `index_state` says the corpus is
     * fully processed — which is correct for a resume, but means it can never notice *drift*
     * (a new turn, a star, a project assignment). Running [reindex] then [sync] on app start
     * covers both: the first finishes any interrupted backfill, the second catches everything
     * that changed since.
     *
     * Cost is one small stamps query plus one transaction containing only the genuine deltas,
     * so the steady-state case (nothing changed) is a single read and no writes at all.
     */
    fun sync(database: SearchDatabase, rows: List<SearchProjector.Row>, nowMillis: Long): SyncResult {
        val stamps = database.searchQueries.selectIndexStamps().executeAsList().associateBy { it.conv_id }
        val incomingIds = rows.mapTo(HashSet()) { it.convId }

        val changed = rows.filter { row -> stamps[row.convId]?.let { !it.matches(row) } ?: true }
        val removed = stamps.keys.filterNot { it in incomingIds }
        if (changed.isEmpty() && removed.isEmpty()) return SyncResult(0, 0)

        database.transaction {
            changed.forEach { writeRow(database, it) }
            removed.forEach { database.searchQueries.deleteProjection(it) }
            // Everything in `rows` is now written, so the resume checkpoint is the full corpus.
            database.searchQueries.upsertIndexState(
                last_indexed_rowid = rows.size.toLong(),
                projection_version = SearchProjector.PROJECTION_VERSION,
                tokenizer_version = TOKENIZER_VERSION,
                schema_version = SCHEMA_VERSION,
                updated_at = nowMillis,
            )
        }
        return SyncResult(changed.size, removed.size)
    }

    /** Whether the indexed stamp still describes [row] exactly. Any mismatch — text timestamp,
     *  projection version, or any facet value — means re-index. */
    private fun SelectIndexStamps.matches(row: SearchProjector.Row): Boolean =
        updated_at == row.updatedAt &&
            projection_version == row.projectionVersion &&
            starred == row.starred.toSqlBoolean() &&
            archived == row.archived.toSqlBoolean() &&
            project_id == row.projectId &&
            model_ids == row.modelIds &&
            turn_count == row.turnCount &&
            branch_count == row.branchCount &&
            has_image == row.hasImage.toSqlBoolean() &&
            has_code == row.hasCode.toSqlBoolean() &&
            cost_minor == row.costMinor

    private fun writeRow(database: SearchDatabase, row: SearchProjector.Row) {
        database.searchQueries.upsertProjection(
            conv_id = row.convId,
            title = row.title,
            snippet = row.snippet,
            body = row.body,
            title_raw = row.titleRaw,
            snippet_raw = row.snippetRaw,
            body_raw = row.bodyRaw,
            updated_at = row.updatedAt,
            created_at = row.createdAt,
            projection_version = row.projectionVersion,
        )
        database.searchQueries.upsertFacets(
            conv_id = row.convId,
            starred = row.starred.toSqlBoolean(),
            archived = row.archived.toSqlBoolean(),
            project_id = row.projectId,
            model_ids = row.modelIds,
            turn_count = row.turnCount,
            branch_count = row.branchCount,
            has_image = row.hasImage.toSqlBoolean(),
            has_code = row.hasCode.toSqlBoolean(),
            cost_minor = row.costMinor,
            last_used_at = row.lastUsedAt,
        )
    }

    private fun Boolean.toSqlBoolean(): Long = if (this) 1L else 0L
}
