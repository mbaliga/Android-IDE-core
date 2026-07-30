package dev.aarso.domain.reliability

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.tree.MessageTree
import dev.aarso.domain.tree.Nodes

/**
 * Pure recovery planning over a tree + the [PartialReply] checkpoints found on disk
 * (daily-driver.md W3). Given what `filesDir/outbox` held at launch (one checkpoint file per
 * user turn, named `<userNodeId>.partial.json` — see [dev.aarso.data.PartialReplyStore]), decides
 * what `ChatViewModel.init` must do: insert an assistant node for a checkpoint whose user turn
 * is still genuinely unanswered, or just discard the file. No I/O here — [plan] is deterministic
 * and JVM-testable; the caller performs the actual insert + file delete this describes.
 *
 * Every checkpoint this plans over is expected to be deleted by the caller afterwards
 * regardless of outcome — a checkpoint is a cache, never a permanent store (same rule as
 * [dev.aarso.data.KvCacheStore]).
 */
object PartialReplyRecovery {

    /**
     * One planned recovery outcome for a single checkpoint file.
     *
     * @property checkpoint the [PartialReply] this action was planned from — the caller uses
     *   [PartialReply.userNodeId] to know which file to delete.
     * @property insert non-null when the checkpoint's user turn is genuinely unanswered and
     *   recoverable: the assistant [MessageNode] to insert, already carrying
     *   `metadata["partial"]="true"` and the codebase's existing `metadata["stopped"]="true"`
     *   truncated-reply convention (`ChatScreen`'s "· stopped here" footer). Null means there is
     *   nothing to insert — the file is simply stale litter to clean up.
     */
    data class Action(
        val checkpoint: PartialReply,
        val insert: MessageNode?,
    )

    /**
     * Plans a recovery [Action] for every entry in [checkpoints]. A checkpoint is recoverable
     * only when all of the following hold — anything else means the real insert already
     * happened (a checkpoint that just hadn't been cleaned up yet) or the checkpoint never
     * really got anywhere:
     *  - its `userNodeId` exists in [tree] and is a USER node;
     *  - that node has no reply yet (append-only tree: a child would mean the real turn already
     *    completed and inserted its own assistant node, making this checkpoint stale);
     *  - it captured at least one token (a blank/empty checkpoint has nothing worth surfacing).
     *
     * @param idGen supplies the recovered node's id, given the checkpoint it came from —
     *   defaults to a random UUID; tests pass a deterministic one.
     */
    fun plan(
        tree: MessageTree,
        checkpoints: List<PartialReply>,
        now: Long,
        idGen: (PartialReply) -> String = { java.util.UUID.randomUUID().toString() },
    ): List<Action> = checkpoints.map { checkpoint ->
        val userNode = tree.node(checkpoint.userNodeId)
        val insert = if (
            userNode != null && userNode.role == Role.USER &&
            tree.childrenOf(userNode.id).isEmpty() && checkpoint.text.isNotBlank()
        ) {
            Nodes.child(
                parent = userNode,
                role = Role.ASSISTANT,
                content = checkpoint.text,
                now = now,
                modelId = checkpoint.modelId.ifBlank { null },
                metadata = mapOf("partial" to "true", "stopped" to "true"),
                idGen = { idGen(checkpoint) },
            )
        } else {
            null
        }
        Action(checkpoint = checkpoint, insert = insert)
    }
}
