package dev.aarso.ui.state

import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.library.ConversationSummary
import kotlinx.coroutines.flow.Flow

/**
 * **The data-source seam** for the state-holding ViewModels (Doc 00 §3.1 — "repositories are
 * the boundary").
 *
 * These interfaces are the *clean boundary* between the pure, JVM-tested presenters
 * ([MyselfPresenter] / [ConversationsPresenter]) and the Android data layer. The ViewModels in
 * this package depend only on these abstractions — never on Room, the DAO, or any concrete
 * store — so the presentation logic stays decoupled from persistence and the surface can be
 * driven from a fake source in any host harness.
 *
 * The **open core ships only the interfaces**: the real implementations live in the `data/`
 * (Room) layer, which folds the append-only message tree + usage ledger into these flows. An
 * empty/default implementation is deliberately *omitted* from the open core — a concrete
 * source is a data-layer concern, supplied by the host at wiring time. Keeping the seam this
 * thin means the boundary is the only thing the UI and the data layer have to agree on.
 *
 * On-thesis: every source here is an **on-device** read (binding rule — no telemetry, no server
 * rollup). The ledger and conversation summaries are derived locally from the user's own tree.
 */

/**
 * Streams the raw usage ledger as it grows. One [LedgerEntry] per turn (and one per Council
 * member on a fan-out), append-only — see [LedgerEntry]'s contract. The "Myself" surface
 * ([MyselfViewModel]) observes this and folds it through [MyselfPresenter].
 */
interface LedgerSource {
    /** The full ledger, re-emitted whenever an entry is appended. */
    fun entries(): Flow<List<LedgerEntry>>
}

/**
 * Streams the inputs the Conversations room ([ConversationsViewModel]) needs: the per-chat
 * summaries, the per-conversation ledger slices used for the flair strips, and a synchronous
 * project-name resolver. The two flows are combined with the room's live filter/sort/grouped
 * state and handed to [ConversationsPresenter].
 */
interface ConversationsSource {
    /** Every conversation summary the room owns, re-emitted on any change. */
    fun summaries(): Flow<List<ConversationSummary>>

    /**
     * Per-conversation usage-ledger entries keyed by [ConversationSummary.id]. A missing key
     * means "no recorded usage yet" → an empty flair strip for that row.
     */
    fun ledgerByConversation(): Flow<Map<String, List<LedgerEntry>>>

    /**
     * Resolve a `projectId` to its display name, or `null` if the project is unknown (the row
     * still groups under its id, with the id as the fallback name). Synchronous because the
     * project registry is a small on-device lookup.
     */
    fun projectName(id: String): String?
}
