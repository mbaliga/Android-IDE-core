package dev.fonebrew.domain.curation

/**
 * A named milestone on a branch (STUDIO_UX_SPEC.md §4.4/§5.1) — "mark this branch tip as
 * *auth-flow v2*." [branchTipMsgId] is the node id of the message at the branch's tip when the
 * version was marked; the branch's full history is reachable from it via the existing
 * [dev.fonebrew.domain.tree.MessageTree.pathToRoot].
 *
 * @property proofRef optional citation of a loop run or build that proves this version works —
 *   the brief's "proving reference" pattern, applied in reverse (a Version *may* cite proof,
 *   rather than requiring one the way an Incident's "resolved" does).
 * @property suggestedBy null for a manually marked version; otherwise identifies what proposed
 *   it (e.g. "on-device-heuristic") — the suggestion-engine seam is Studio-side
 *   ([VersionSuggestSlot]), core only records provenance if a suggestion was accepted.
 */
data class Version(
    val id: String,
    val branchTipMsgId: String,
    val name: String,
    val note: String? = null,
    val proofRef: String? = null,
    val at: Long,
    val suggestedBy: String? = null,
)

/** Pure version-set operations, kept separate from persistence. */
object Versions {

    /** Add-wins merge for sync, same shape as [MessageBookmarks.mergeAddWins]. */
    fun mergeAddWins(existing: List<Version>, incoming: List<Version>): List<Version> {
        val byId = LinkedHashMap<String, Version>()
        for (v in existing) byId[v.id] = v
        for (v in incoming) {
            val prior = byId[v.id]
            if (prior == null || v.at >= prior.at) byId[v.id] = v
        }
        return byId.values.toList()
    }

    /** The version (if any) marked at this exact branch tip. */
    fun atTip(versions: List<Version>, msgId: String): Version? =
        versions.filter { it.branchTipMsgId == msgId }.maxByOrNull { it.at }
}
