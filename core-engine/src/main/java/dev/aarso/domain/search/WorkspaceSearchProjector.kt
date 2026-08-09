package dev.aarso.domain.search

import dev.aarso.contracts.search.IndexFreshness
import dev.aarso.contracts.workspace.BufferSnapshotEntry
import java.time.Instant

/**
 * WP-6: projects a Workspace Kernel [BufferSnapshotEntry] (WP-3 -- the disposable, reconstitutable
 * per-buffer state a `RecoverySnapshot` carries) into a [SearchDoc], the same wire shape
 * `LexicalSearch`'s real, already-shipped ranking engine already consumes for conversation search
 * (WP0_SURVEY.md §1(g)). This is the literal "wire existing LexicalSearch.kt... into the workspace
 * kernel" instruction: no new ranking/scoring code, the existing engine indexes a new kind of
 * document.
 *
 * `SearchDoc.kind` is a deliberate stretch of that enum's originally conversation-only meaning
 * ([SearchKind.TEXT] is the closest fit for a workspace text buffer) -- flagged here rather than
 * silently repurposed, since `SearchKind`'s own doc comment still frames it as "mirrors the
 * Conversations filter tabs."
 */
object WorkspaceSearchProjector {

    /**
     * @param content the buffer's current materialized text (e.g. from
     *   [dev.aarso.domain.workspace.BufferReplay.materialize]) -- this projector does not itself
     *   read a journal; it is handed content the caller already reconstituted.
     * @param displayPath used as both [SearchDoc.title] and the basis of [SearchDoc.snippet] --
     *   a file has no separate "title" the way a conversation does, so its path stands in.
     */
    fun project(entry: BufferSnapshotEntry, content: String, displayPath: String, lastActivityMillis: Long): SearchDoc =
        SearchDoc(
            id = entry.bufferId,
            title = displayPath,
            snippet = content.take(SNIPPET_LENGTH).replace('\n', ' '),
            body = content,
            lastActivityMillis = lastActivityMillis,
            kind = SearchKind.TEXT,
        )

    /** The [dev.aarso.contracts.search.ResultProvenance]-shaped freshness for a just-indexed buffer. */
    fun freshnessOf(entry: BufferSnapshotEntry, indexedAtUtc: Instant): IndexFreshness =
        IndexFreshness(
            sourceRevisionOrSequence = entry.journalSequence.toString(),
            indexedAtUtc = indexedAtUtc,
            isStale = false,
        )

    private const val SNIPPET_LENGTH = 240
}
