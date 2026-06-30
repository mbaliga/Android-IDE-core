package dev.aarso.data

import dev.aarso.domain.ledger.LedgerEntry
import dev.aarso.domain.library.ConversationProjection
import dev.aarso.domain.library.ConversationSummary
import dev.aarso.domain.tree.Conversations
import dev.aarso.ui.state.ConversationsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Data-layer implementation of the [ConversationsSource] seam (Doc 02 — the Conversations room).
 *
 * Folds the append-only message tree + the on-device session facts (stars, project labels, open
 * counts) into the room's row model, and slices the usage ledger per chat for the flair strips.
 * The pure mapping lives in the JVM-tested [ConversationProjection]; this class only plumbs the
 * live flows together, so there is no untested logic here.
 *
 * On-thesis: every read is **on-device** (binding rule 1) — the summaries come from the user's
 * own tree, the stars/projects/opens from their session store, the ledger from the local
 * append-only usage log. Nothing is rolled up on a server, nothing leaves the device.
 */
class ConversationsStore(
    private val repository: MessageTreeRepository,
    private val session: SessionStore,
    private val ledgerStore: LedgerStore,
) : ConversationsSource {

    /** Tree summaries + session facts → the room's row models. Re-emits on any change to either. */
    override fun summaries(): Flow<List<ConversationSummary>> =
        combine(
            repository.observeTree().map { Conversations.summarize(it) },
            session.bookmarkedRoots,
            session.conversationProjects,
            session.conversationOpens,
        ) { summaries, stars, projects, opens ->
            ConversationProjection.fromAll(summaries, stars, projects, opens)
        }

    /**
     * The usage ledger grouped by [LedgerEntry.chatId] — the per-conversation slices the room
     * folds into model-flair strips. Empty until the per-turn capture writer lands (the ledger
     * is append-only and starts empty); a chat with no entries simply gets no flairs.
     */
    override fun ledgerByConversation(): Flow<Map<String, List<LedgerEntry>>> =
        ledgerStore.entries().map { entries -> entries.groupBy { it.chatId } }

    /**
     * Projects in this app are free-text labels assigned per chat (not entities with a separate
     * id), so the label *is* its display name — the identity resolver is honest here. Returns the
     * id unchanged; the room falls back to the id as the group name regardless.
     */
    override fun projectName(id: String): String? = id
}
