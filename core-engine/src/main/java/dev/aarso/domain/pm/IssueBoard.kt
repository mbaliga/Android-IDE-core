package dev.aarso.domain.pm

/**
 * Project-management pillar (the "run the whole project from the app" surface): a
 * Kanban board whose cards **are the issues in the user's own Git host repo**, not a
 * separate database. Moving a card = relabelling / closing the issue on the host;
 * the board is a *view* over your repo's issues, the way the message tree is the one
 * spine for chat. Sovereignty by construction — there is no Aarso-side board store.
 *
 * Column convention (legible, host-portable): an open issue's column is the first
 * `status:*` label it carries; an open issue with no status label sits in BACKLOG; a
 * closed issue is DONE. So the board round-trips through plain GitHub/Gitea labels and
 * stays readable in the host's own issue UI.
 *
 * Pure domain (no Android, no network). [IssueBoardApi] builds the REST requests and
 * parses host JSON; this file is the model + the column logic, JVM-tested.
 */
data class BoardCard(
    val id: String,
    /** The host issue number (`#42`) — the stable handle for moves. */
    val number: Int,
    val title: String,
    val body: String,
    val isOpen: Boolean,
    val labels: List<String>,
    val assignees: List<String>,
    /** ISO timestamp from the host, or blank. */
    val updatedAt: String,
    val url: String,
) {
    val column: BoardColumn get() = BoardColumn.of(labels, isOpen)
}

/**
 * The board's columns. [label] is the canonical host label that pins an *open* issue
 * to the column; BACKLOG (no status label) and DONE (closed) are states, not labels.
 */
enum class BoardColumn(val title: String, val label: String?) {
    BACKLOG("Backlog", null),
    TODO("To do", "status:todo"),
    DOING("Doing", "status:doing"),
    REVIEW("Review", "status:review"),
    DONE("Done", null),
    ;

    companion object {
        /** Every label this convention owns — stripped before a move re-tags a card. */
        val STATUS_LABELS: Set<String> = entries.mapNotNull { it.label }.toSet()

        /** Derive the column for an issue from its labels + open/closed state. */
        fun of(labels: List<String>, isOpen: Boolean): BoardColumn {
            if (!isOpen) return DONE
            return entries.firstOrNull { it.label != null && it.label in labels } ?: BACKLOG
        }
    }
}

/** Pure board operations over a flat list of cards. */
object Boards {

    /** Columns in display order, each with its cards (host order preserved). */
    fun group(cards: List<BoardCard>): Map<BoardColumn, List<BoardCard>> =
        BoardColumn.entries.associateWith { col -> cards.filter { it.column == col } }

    /**
     * The label set an issue should carry after moving to [target]: drop every
     * convention-owned `status:*` label, then add the target's label (if it has one).
     * Non-status labels (priority, area, …) are preserved.
     */
    fun labelsForMove(current: List<String>, target: BoardColumn): List<String> {
        val kept = current.filterNot { it in BoardColumn.STATUS_LABELS }
        return if (target.label != null) kept + target.label else kept
    }

    /** A move into DONE closes the issue; any other column means it must be open. */
    fun isOpenAfter(target: BoardColumn): Boolean = target != BoardColumn.DONE

    /** At-a-glance project health for the pending-items / dashboard view. */
    fun summary(cards: List<BoardCard>): BoardSummary {
        val counts = BoardColumn.entries.associateWith { col -> cards.count { it.column == col } }
        fun n(c: BoardColumn) = counts[c] ?: 0
        return BoardSummary(
            counts = counts,
            total = cards.size,
            pending = n(BoardColumn.BACKLOG) + n(BoardColumn.TODO),
            inFlight = n(BoardColumn.DOING) + n(BoardColumn.REVIEW),
            done = n(BoardColumn.DONE),
        )
    }
}

/** Aggregated counts over a board — the pending-items / test-dashboard summary. */
data class BoardSummary(
    val counts: Map<BoardColumn, Int>,
    val total: Int,
    /** Backlog + To do — not started. */
    val pending: Int,
    /** Doing + Review — work in progress. */
    val inFlight: Int,
    val done: Int,
) {
    /** Everything not yet Done. */
    val open: Int get() = total - done
}
