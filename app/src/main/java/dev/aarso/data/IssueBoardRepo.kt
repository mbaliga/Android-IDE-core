package dev.aarso.data

import dev.aarso.domain.git.GitHost
import dev.aarso.domain.pm.BoardCard
import dev.aarso.domain.pm.BoardColumn
import dev.aarso.domain.pm.Boards
import dev.aarso.domain.pm.IssueBoardApi

/**
 * Data-layer facade over [IssueBoardApi]: reads the user's connected Git host issues
 * as a Kanban board and writes card moves/creations back as label/state edits. Same
 * pattern as [BuildsRepo] — injects [GitTransport] + a token provider, returns
 * empty/false when no host is wired, and talks ONLY to the user's own host.
 *
 * There is no Aarso-side task store: the board *is* the repo's issues
 * (docs/design/project-management.md). Network is owner-verified — no host in CI.
 *
 * @param tokenProvider decrypted token for a host id, or null. Production: `hostStore::token`.
 */
class IssueBoardRepo(
    private val transport: GitTransport,
    private val tokenProvider: (hostId: String) -> String?,
) {
    constructor(transport: GitTransport, hostStore: GitHostStore) : this(transport, hostStore::token)

    /** All cards on [host], or empty when no token / on any error. */
    suspend fun listCards(host: GitHost): List<BoardCard> {
        val token = tokenProvider(host.id) ?: return emptyList()
        val r = runCatching { transport.execute(IssueBoardApi.listIssues(host, token)) }.getOrNull()
            ?: return emptyList()
        if (r.code !in 200..299) return emptyList()
        return runCatching { IssueBoardApi.parseIssues(r.body) }.getOrDefault(emptyList())
    }

    /** The board grouped into ordered columns (every column present, possibly empty). */
    suspend fun loadBoard(host: GitHost): Map<BoardColumn, List<BoardCard>> =
        Boards.group(listCards(host))

    /**
     * Move [card] to [target] (relabel + open/close on the host). Returns true on a 2xx,
     * false on no-token / error — the caller can re-fetch to confirm the new state.
     */
    suspend fun move(host: GitHost, card: BoardCard, target: BoardColumn): Boolean {
        val token = tokenProvider(host.id) ?: return false
        val req = IssueBoardApi.moveCard(host, card.number, target, card.labels, token)
        val r = runCatching { transport.execute(req) }.getOrNull() ?: return false
        return r.code in 200..299
    }

    /** Create a new card (issue) in [column]. Returns true on a 2xx. */
    suspend fun create(host: GitHost, title: String, body: String, column: BoardColumn): Boolean {
        val token = tokenProvider(host.id) ?: return false
        val req = IssueBoardApi.createIssue(host, title, body, column, token)
        val r = runCatching { transport.execute(req) }.getOrNull() ?: return false
        return r.code in 200..299
    }
}
