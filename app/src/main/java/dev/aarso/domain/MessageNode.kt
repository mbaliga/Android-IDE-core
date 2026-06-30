package dev.aarso.domain

/**
 * One message in the append-only message tree — the spine of the whole app
 * (handoff §2). Everything (branching, checkpoint/restore, model switching, the
 * council's lateral axis) is an operation or a view over this one structure.
 *
 * This is the pure domain model: deliberately free of Android/Room types so the
 * tree algorithms ([dev.aarso.domain.tree]) stay unit-testable on
 * the JVM. The Room representation lives separately in the data layer.
 *
 * Append-only means nodes are never mutated in place. "Restore to a touchpoint
 * and try a different route" is simply adding a new child to an earlier node.
 *
 * @property modelId which model produced an assistant node; null for user/system.
 * @property tokenCounts per-tokenizer token counts. Counts differ across models
 *   (§3), so each is tagged with the tokenizer that produced it.
 * @property metadata free-form tags / user-applied outcome labels / panel
 *   (council) membership. Persisted as JSON in the data layer.
 */
data class MessageNode(
    val id: String,
    val parentId: String?,
    val role: Role,
    val content: String,
    val modelId: String? = null,
    val createdAt: Long,
    val tokenCounts: Map<String, Int> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
) {
    val isRoot: Boolean get() = parentId == null
}
