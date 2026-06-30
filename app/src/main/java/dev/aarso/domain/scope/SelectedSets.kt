package dev.aarso.domain.scope

/**
 * Saved **`selected-projects` sets** (Doc 03 §4.2).
 *
 * When the active scope is [Scope.SelectedProjects], something has to supply the concrete
 * ids. Rather than make the user re-pick the same handful of projects every turn, the chain
 * remembers a chosen *set* at two layers, mirroring [ScopeDefaults]:
 *
 * - a **project** can define a default selected-set (the set its conversations start from);
 * - a **conversation** remembers the set it last chose, which pins it independently of the
 *   project default.
 *
 * [resolveSelectedProjects] walks that small chain to find the set in force, returning null
 * when neither layer has one saved (honest absence — the caller then has nothing pre-filled
 * and the user must choose). This is *only* the id-set memory; whether [Scope.SelectedProjects]
 * is even the active scope is decided separately by [resolveActiveScope]. Combining the two is
 * the caller's job: if the active scope is [Scope.SelectedProjects] with an empty/placeholder
 * id set, fill it from here.
 *
 * Pure domain — no Android, no IO, no storage. Deterministic; helpers return immutable copies.
 */

/**
 * The saved selected-project sets at the two layers, each keyed by id.
 *
 * @property perProjectDefaultSet a project's default selected-set, keyed by **projectId**.
 *   Absent ⇒ the project has no default set.
 * @property perConversationSet a conversation's remembered selected-set, keyed by
 *   **conversationId**. Absent ⇒ the conversation has not chosen its own set and inherits
 *   the project default (if any). An *empty* set is meaningful and distinct from absence: it
 *   is a deliberate "no projects selected" choice and is returned as-is, not treated as null.
 */
data class SavedSelectedSets(
    val perProjectDefaultSet: Map<String, Set<String>> = emptyMap(),
    val perConversationSet: Map<String, Set<String>> = emptyMap(),
)

/**
 * Resolve the saved selected-project id set in force for a conversation.
 *
 * Precedence (first match wins):
 * 1. the conversation's own remembered set ([SavedSelectedSets.perConversationSet]) — even
 *    if empty;
 * 2. else the conversation's project default set ([SavedSelectedSets.perProjectDefaultSet]),
 *    only when [conversationProjectId] is non-null and a default exists;
 * 3. else `null` — nothing saved at either layer.
 *
 * The distinction between an **empty set** and **null** is deliberate: empty means "saved,
 * and the choice is no projects"; null means "no saved set at all" so the caller knows to
 * prompt rather than to apply an empty selection. Deterministic.
 *
 * @param conversationId the conversation whose saved set we want.
 * @param conversationProjectId the conversation's project, or null if it is a loose
 *   conversation (which then skips the project-default layer).
 * @param sets the saved sets at both layers.
 */
fun resolveSelectedProjects(
    conversationId: String,
    conversationProjectId: String?,
    sets: SavedSelectedSets,
): Set<String>? {
    sets.perConversationSet[conversationId]?.let { return it }
    if (conversationProjectId != null) {
        sets.perProjectDefaultSet[conversationProjectId]?.let { return it }
    }
    return null
}

/**
 * Return a copy of [sets] with [conversationId]'s remembered selected-set set to [ids].
 *
 * Immutable: the receiver is untouched. After this, [resolveSelectedProjects] for
 * [conversationId] returns exactly [ids] (including when [ids] is empty).
 */
fun withConversationSet(
    sets: SavedSelectedSets,
    conversationId: String,
    ids: Set<String>,
): SavedSelectedSets = sets.copy(
    perConversationSet = sets.perConversationSet + (conversationId to ids),
)

/**
 * Return a copy of [sets] with [projectId]'s default selected-set set to [ids].
 *
 * Immutable: the receiver is untouched. This affects every conversation in [projectId] that
 * has no remembered set of its own.
 */
fun withProjectDefaultSet(
    sets: SavedSelectedSets,
    projectId: String,
    ids: Set<String>,
): SavedSelectedSets = sets.copy(
    perProjectDefaultSet = sets.perProjectDefaultSet + (projectId to ids),
)
