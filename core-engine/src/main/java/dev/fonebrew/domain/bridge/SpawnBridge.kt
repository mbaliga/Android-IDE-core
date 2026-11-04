package dev.fonebrew.domain.bridge

import dev.fonebrew.domain.provenance.ProvenanceState

/**
 * **Spawn** (THREAD_TOPOLOGY_PLAN.md WP2) — the condensed half of the "spin off an independent
 * conversation" pair, the other half being [dev.fonebrew.domain.tree.TreeFork] ("Fork"). Where Fork
 * duplicates the full message history verbatim into a new conversation, Spawn instead opens the
 * new conversation with a single **bridge node**: the same "legible seam" idea
 * [SummaryBridge]/[SummaryBridges] already built for a mid-conversation model switch, reused here
 * wholesale rather than reinvented, because a spawn is structurally the same problem — *"a new
 * branch starts, informed but honest that it isn't the full history."*
 *
 * [SpawnBridge] therefore doesn't duplicate [SummaryBridge]'s fields — it wraps one, so the
 * render layer ([dev.fonebrew.ui.components.SummaryNodeCard]) mounts a spawn bridge exactly the way
 * it mounts an interaction-model-switch bridge, and just adds the two lineage pointers a spawned
 * *root* needs that a same-tree switch never did ([srcRootId]/[srcNodeId] — which conversation and
 * message this new one came from, for the card's "view full prior context" to jump back to via
 * [dev.fonebrew.ui.ChatViewModel.openConversation]).
 *
 * Pure domain (no Android, no network, no clock) — [SpawnBridges.build] composes deterministic
 * inputs exactly like [SummaryBridges.build] does; the caller supplies the already-computed prose
 * (via the `summarizeActivePath` pattern — a real model call, kept firmly out of this file) and the
 * carry-forward source turns.
 */
data class SpawnBridge(
    val summary: SummaryBridge,
    val srcRootId: String,
    val srcNodeId: String,
)

/** Pure operations that construct a [SpawnBridge] — the Spawn-side counterpart to [SummaryBridges]. */
object SpawnBridges {

    /** Characters of the anchor message's content kept in the header excerpt before the ellipsis. */
    private const val HEADER_EXCERPT_CHARS = 60
    private const val ELLIPSIS = "…"

    /** The deterministic "spawned from" framing line, built from a verbatim excerpt of the
     *  message the new conversation was spawned from — never a fabricated description. */
    fun header(srcExcerpt: String): String {
        val collapsed = srcExcerpt.replace(Regex("\\s+"), " ").trim()
        val excerpt = if (collapsed.length > HEADER_EXCERPT_CHARS) {
            collapsed.substring(0, HEADER_EXCERPT_CHARS) + ELLIPSIS
        } else {
            collapsed
        }
        return if (excerpt.isEmpty()) "Spawned from a prior conversation" else "Spawned from: $excerpt"
    }

    /**
     * Assemble the full [SpawnBridge] payload: the deterministic header (from a verbatim excerpt
     * of the spawn point, [srcExcerpt]), the carry-forward bullets (reusing
     * [SummaryBridges.selectCarryForward] — the same honest "excerpt, not invention" heuristic),
     * and the lineage pointer back to where this conversation came from.
     */
    fun build(
        srcRootId: String,
        srcNodeId: String,
        srcExcerpt: String,
        priorTurns: List<PriorTurn>,
        authorModel: String?,
        authorProvenance: ProvenanceState,
        maxBullets: Int = 5,
        maxTokens: Int = 400,
    ): SpawnBridge = SpawnBridge(
        summary = SummaryBridge(
            header = header(srcExcerpt),
            carriedForward = SummaryBridges.selectCarryForward(priorTurns, maxBullets, maxTokens),
            fullPriorAvailable = priorTurns.isNotEmpty(),
            authorModel = authorModel,
            authorProvenance = authorProvenance,
        ),
        srcRootId = srcRootId,
        srcNodeId = srcNodeId,
    )
}
