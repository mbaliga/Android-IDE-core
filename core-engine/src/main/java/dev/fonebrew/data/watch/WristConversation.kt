package dev.fonebrew.data.watch

import dev.fonebrew.data.MessageTreeRepository
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.domain.tree.MessageTree
import dev.fonebrew.domain.tree.Nodes
import dev.fonebrew.domain.tree.TreeFork

/**
 * The dedicated "Wrist" conversation every relayed question lands in (docs/design/watchbrew.md
 * section 3: "a relayed turn lands in the phone's message tree like any other turn").
 *
 * **It is an ordinary conversation**, not a side channel: a parentless root in the one append-only
 * tree, with user and assistant turns appended under it by [WristTurnRunner] through
 * [MessageTreeRepository.insert]. The phone user can open it in Chat, branch it, fork it, compact it,
 * and continue it from the phone; the next wrist question simply continues from the tip
 * ([MessageTree.descendToLeaf], the newest branch) like any "open conversation" does. Nothing is ever
 * edited after insert (append-only), and this object never writes anywhere but through the repository.
 *
 * **Created on first use, named visibly.** The first relayed question creates the root: a SYSTEM node
 * whose text says what the conversation is. Two things make that name visible rather than implied:
 *
 *  - the text itself is the first thing in the transcript, and
 *  - the root carries [Conversations.TITLE_KEY] = [TITLE], which the Conversations list uses as the
 *    conversation's title (instead of the usual "first words of the opening ask").
 *
 * Honest note on influence: because it is a real tree node, this SYSTEM line IS part of the prompt the
 * engine sees on every wrist turn. It states where the conversation comes from and nothing else (no
 * persona, no length or tone instruction), and being a visible node the user can read it, branch away
 * from it, or fork the conversation without it. Same legibility law as the rest of the tree.
 *
 * Which nodes came from the wrist is recorded at insert time as `metadata["via"] = "watchbrew"`
 * ([VIA_KEY]/[VIA_WATCHBREW]); never added retroactively.
 */
object WristConversation {

    /** Marks the conversation's root node. Minted once at insert, never edited. */
    const val ROOT_KEY: String = "wrist.root"

    /** Provenance tag on every node the relay inserts (root, user turn, assistant turn). */
    const val VIA_KEY: String = "via"
    const val VIA_WATCHBREW: String = "watchbrew"

    /** The conversation's visible name in the Conversations list. */
    const val TITLE: String = "Wrist · WatchBrew"

    /** The root's text: says what the conversation is, and nothing that steers the model. */
    const val ROOT_TEXT: String =
        "Wrist conversation: questions asked from the user's watch (WatchBrew) are relayed to this phone and answered here."

    /**
     * The wrist conversation's root in [tree], or `null` if the first question has not been asked yet.
     *
     * A fork copies a root's metadata verbatim (`TreeFork.copySubtree`), so a forked copy of the wrist
     * conversation also carries [ROOT_KEY]; it is told apart by the lineage tag a fork mints on its new
     * root, and is never the relay's target. Among genuine roots the oldest wins (ties broken by id so
     * the choice is deterministic).
     */
    fun rootIn(tree: MessageTree): MessageNode? =
        tree.roots()
            .filter { it.metadata[ROOT_KEY] == "true" && !it.metadata.containsKey(TreeFork.LINEAGE_KIND_KEY) }
            .minWithOrNull(compareBy({ it.createdAt }, { it.id }))

    /**
     * Where the next wrist turn attaches: the tip of the wrist conversation, creating the root first if
     * this is the first use. Returns the node to use as the new user turn's parent.
     */
    suspend fun tipForNextTurn(
        repository: MessageTreeRepository,
        now: Long,
        idGen: () -> String,
    ): MessageNode {
        val tree = repository.tree()
        val root = rootIn(tree)
        if (root == null) {
            val created = Nodes.child(
                parent = null,
                role = Role.SYSTEM,
                content = ROOT_TEXT,
                now = now,
                metadata = mapOf(ROOT_KEY to "true", Conversations.TITLE_KEY to TITLE, VIA_KEY to VIA_WATCHBREW),
                idGen = idGen,
            )
            repository.insert(created)
            return created
        }
        val tipId = tree.descendToLeaf(root.id) ?: root.id
        return tree.node(tipId) ?: root
    }
}
