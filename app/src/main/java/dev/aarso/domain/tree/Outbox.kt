package dev.aarso.domain.tree

import dev.aarso.domain.Role

/**
 * The "outbox" (daily-driver.md W3 — Gmail-outbox bar): user turns with no reply at all,
 * derived straight from tree shape rather than tracked in any store of its own. A
 * [dev.aarso.domain.MessageNode] with role USER and zero children is, by construction, a send
 * that never got (or hasn't yet finished getting) an answer — the append-only tree already
 * remembers this, so the derivation is correct again the instant the app relaunches after a
 * crash, with no special-case recovery bookkeeping needed.
 *
 * Scope note (W3): a childless USER node can't tell a genuinely-unanswered single-model turn
 * apart from a council turn that crashed before its *first* voice replied — both look identical
 * from tree shape alone (a council turn only gains its agent-tagged children one at a time, as
 * each voice finishes; see `ChatViewModel.sendCouncil`). [unanswered] does not attempt to
 * distinguish the two — this mirrors the pre-existing `canRegenerate` guard in `ChatViewModel`,
 * which has the same gap. W3 ships this as a single-chat mechanism; a council turn that already
 * has at least one reply is correctly excluded (it has a child), but one that crashed before any
 * reply landed is not. Council crash-recovery (resuming the fan-out itself) is out of scope here
 * — see docs/design/daily-driver.md's W3 section and the implementing session's report.
 */
object Outbox {

    data class UnansweredTurn(
        val userNodeId: String,
        val rootId: String,
        val createdAt: Long,
    )

    /**
     * Every user turn anywhere in [tree] with no reply at all, oldest first. [excludeNodeId] is
     * the user node of a generation currently in flight, if any — it has no assistant child yet
     * either (the reply streams before the node is inserted), but it is mid-flight, not stuck,
     * so it must never be reported as "not answered". At most one generation ever runs at a
     * time in this app, so this is always at most one id.
     */
    fun unanswered(tree: MessageTree, excludeNodeId: String? = null): List<UnansweredTurn> =
        tree.allNodes()
            .asSequence()
            .filter { it.role == Role.USER && it.id != excludeNodeId }
            .filter { tree.childrenOf(it.id).isEmpty() }
            .mapNotNull { user ->
                Conversations.rootOf(tree, user.id)?.let { root ->
                    UnansweredTurn(userNodeId = user.id, rootId = root, createdAt = user.createdAt)
                }
            }
            .sortedBy { it.createdAt }
            .toList()

    /**
     * The single unanswered turn sitting at the tail of the active path ending at [leafId], or
     * null when that path is fully answered. A childless user node can only ever be a leaf (a
     * node with children isn't unanswered, and one is unanswered only by having none), so a
     * path's tail is the only place one can appear.
     */
    fun unansweredOnPath(tree: MessageTree, leafId: String, excludeNodeId: String? = null): UnansweredTurn? =
        unanswered(tree, excludeNodeId).firstOrNull { it.userNodeId == leafId }
}
