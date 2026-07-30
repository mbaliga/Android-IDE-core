package dev.aarso.ui.state

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.markdown.StreamingMarkdown
import dev.aarso.domain.provenance.ProvenanceState
import dev.aarso.domain.state.UiState
import dev.aarso.domain.tree.MessageTree
import dev.aarso.domain.tree.PathView

/**
 * Pure, JVM-testable presenter for the **Chat home thread** (Doc 01 §2.1).
 *
 * The Chat thread renders the **active path** through the append-only message tree —
 * the single root→leaf route currently selected — turning each node into a render-ready
 * [ThreadRow]. Three legibility duties are encoded here, all pure so they are
 * machine-verified rather than render-time afterthoughts:
 *
 *  1. **Active path projection.** Delegates to [PathView.annotate] so the rows are exactly
 *     the chosen route, root-first, deterministic.
 *  2. **Streaming-safe markdown.** Every node's text passes through
 *     [StreamingMarkdown.reconcile] so a *mid-stream* assistant turn (an unclosed ``` fence,
 *     a half-built table) is safe to hand to a strict renderer without the layout thrashing.
 *     The reconcile result's open-fence fact is carried out on [ThreadRow.streamingOpenFence].
 *  3. **Provenance, caller-resolved.** The on-device⇄cloud decision is the caller's (it knows
 *     the routing context), so provenance is supplied as a function; the default is
 *     [ProvenanceState.UNKNOWN] — surfaced honestly rather than guessed (binding rule 2).
 *
 * No Android, no clock, no randomness: a deterministic function of (tree, leafId, provenanceOf).
 */
object ChatThreadPresenter {

    /**
     * One render-ready row of the Chat thread.
     *
     * @property nodeId the source [MessageNode.id].
     * @property role who authored the turn.
     * @property model the model that produced an assistant turn ([MessageNode.modelId]);
     *   null for user/system turns.
     * @property provenance the watched-object receipt for this turn, as resolved by the
     *   caller-supplied function (default [ProvenanceState.UNKNOWN]).
     * @property renderText the node's text passed through [StreamingMarkdown.reconcile] — safe
     *   to render even mid-stream (an open fence is temporarily closed, a torn table row trimmed).
     * @property streamingOpenFence true when the node text ended inside an unclosed code fence,
     *   i.e. the turn is still streaming a code block; lets the UI show a "still streaming" cue.
     * @property branchSiblingCount how many *other* alternatives exist at this node's branch
     *   point — the inline branch marker (Doc 01 §2.1). Derived from the tree as
     *   `siblingsOf(node).size - 1`, so 0 means no fork (this node is the only child of its parent).
     */
    data class ThreadRow(
        val nodeId: String,
        val role: Role,
        val model: String?,
        val provenance: ProvenanceState,
        val renderText: String,
        val streamingOpenFence: Boolean,
        val branchSiblingCount: Int,
    )

    /**
     * The projected Chat thread: the active-path [rows] (root-first) and the [leafId] they
     * terminate at.
     */
    data class ThreadView(
        val rows: List<ThreadRow>,
        val leafId: String,
    )

    /**
     * Project the active path ending at [leafId] into a render-ready [ThreadView].
     *
     * - [UiState.Empty] when the path is empty (unknown leaf) or is just a single root node
     *   with no content — the *useful* empty: there is genuinely nothing to read yet, so the
     *   surface should guide the user to start the conversation rather than paint a blank.
     * - [UiState.Ready] otherwise, holding one [ThreadRow] per step in path order.
     *
     * @param provenanceOf resolves each node's provenance; the caller owns the on-device⇄cloud
     *   decision. Defaults to [ProvenanceState.UNKNOWN] (no claim either way).
     */
    fun present(
        tree: MessageTree,
        leafId: String,
        provenanceOf: (MessageNode) -> ProvenanceState = { ProvenanceState.UNKNOWN },
    ): UiState<ThreadView> {
        val steps = PathView.annotate(tree, leafId)

        // Empty path (unknown leaf) → the useful empty.
        if (steps.isEmpty()) return UiState.Empty

        // A lone, contentless root → nothing to read yet → the useful empty. (A root that
        // already carries content, or any path with more than one node, is genuine thread.)
        if (steps.size == 1 && steps.single().node.content.isBlank()) return UiState.Empty

        val rows = steps.map { step ->
            val node = step.node
            val safe = StreamingMarkdown.reconcile(node.content)
            // Other alternatives at this node's branch point: its sibling set minus itself.
            val siblingCount = (tree.siblingsOf(node.id).size - 1).coerceAtLeast(0)
            ThreadRow(
                nodeId = node.id,
                role = node.role,
                model = node.modelId,
                provenance = provenanceOf(node),
                renderText = safe.text,
                streamingOpenFence = safe.openFence,
                branchSiblingCount = siblingCount,
            )
        }

        return UiState.Ready(ThreadView(rows = rows, leafId = leafId))
    }
}
