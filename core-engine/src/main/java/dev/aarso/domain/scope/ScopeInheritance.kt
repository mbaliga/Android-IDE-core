package dev.aarso.domain.scope

/**
 * Scope **inheritance chain** (Doc 03 §4.2).
 *
 * Knowledge scope ([Scope]) is never decided in a vacuum: a turn's *active* scope is the
 * outcome of a small, legible chain of defaults that the user can see and override at any
 * layer. The chain, narrowest-overriding-widest:
 *
 * ```
 *   Global default (Settings)
 *     └─▶ Project default (per-project)
 *           └─▶ Conversation override (per-conversation)
 *                 └─▶ the ACTIVE scope for THIS turn  ← always shown
 * ```
 *
 * Resolution walks the chain from the *most specific* layer outward: a per-conversation
 * override wins outright; absent that, the conversation's project default applies (only if
 * the conversation actually belongs to a project *and* that project defined a default);
 * absent both, the global default is the floor and always exists. The result carries a
 * [ScopeSource] so the UI can state *which* layer decided — Doc 03's "always shown" rule:
 * the user should never have to guess whether the active scope came from their own
 * override, their project, or the global setting.
 *
 * This file is pure domain (no Android, no IO, no storage). It builds the inheritance
 * layer *on top of* the committed [Scope] sealed type — it does not redefine scopes. All
 * functions are deterministic and return immutable copies; nothing is mutated in place.
 */

/**
 * The three default layers of the scope chain, each keyed by id.
 *
 * @property global the universal floor — the Settings-level default scope. Always present;
 *   there is no scope state in which *no* default exists, because [global] is the backstop.
 * @property perProject project default scopes, keyed by **projectId**. A project absent from
 *   this map simply has no project-level default and falls through to [global].
 * @property perConversationOverride per-conversation overrides, keyed by **conversationId**.
 *   A conversation absent from this map has no override and falls through to its project
 *   default (or [global]). Presence of a key — even mapping to the same scope as a wider
 *   layer — is meaningful: it pins the conversation so later changes to wider layers do not
 *   silently move it.
 */
data class ScopeDefaults(
    val global: Scope,
    val perProject: Map<String, Scope> = emptyMap(),
    val perConversationOverride: Map<String, Scope> = emptyMap(),
)

/** Which layer of the inheritance chain decided the active scope. Makes resolution legible. */
enum class ScopeSource {
    /** A per-conversation override was set and won. */
    CONVERSATION_OVERRIDE,

    /** No override; the conversation's project supplied a default. */
    PROJECT_DEFAULT,

    /** No override and no applicable project default; the global floor applied. */
    GLOBAL_DEFAULT,
}

/**
 * The resolved active scope for a turn, plus the [source] layer that decided it.
 *
 * @property scope the concrete [Scope] in force for this turn.
 * @property source which inheritance layer the [scope] came from — for the "always shown"
 *   provenance label.
 */
data class ScopeResolution(
    val scope: Scope,
    val source: ScopeSource,
)

/**
 * Resolve the active scope for a single conversation by walking the inheritance chain.
 *
 * Precedence (first match wins):
 * 1. **[ScopeSource.CONVERSATION_OVERRIDE]** — if [defaults] has an override keyed by
 *    [conversationId], it wins regardless of project or global.
 * 2. **[ScopeSource.PROJECT_DEFAULT]** — else, if [conversationProjectId] is non-null *and*
 *    [defaults] has a per-project default for it, that applies.
 * 3. **[ScopeSource.GLOBAL_DEFAULT]** — else the global floor.
 *
 * A conversation with no project ([conversationProjectId] == null) skips the project layer
 * entirely and falls from any override straight to global. Deterministic: the same inputs
 * always yield the same [ScopeResolution], and the lookups are plain map gets (no ordering
 * dependence).
 *
 * @param conversationId the conversation whose turn we are scoping.
 * @param conversationProjectId the project the conversation belongs to, or null if it is a
 *   loose (non-project) conversation.
 * @param defaults the three default layers.
 */
fun resolveActiveScope(
    conversationId: String,
    conversationProjectId: String?,
    defaults: ScopeDefaults,
): ScopeResolution {
    defaults.perConversationOverride[conversationId]?.let { override ->
        return ScopeResolution(override, ScopeSource.CONVERSATION_OVERRIDE)
    }
    if (conversationProjectId != null) {
        defaults.perProject[conversationProjectId]?.let { projectDefault ->
            return ScopeResolution(projectDefault, ScopeSource.PROJECT_DEFAULT)
        }
    }
    return ScopeResolution(defaults.global, ScopeSource.GLOBAL_DEFAULT)
}

/**
 * Return a copy of [defaults] with a per-conversation override set for [conversationId].
 *
 * Immutable: the receiver [defaults] is untouched; a new [ScopeDefaults] is returned with
 * the override map extended (or the existing override for this conversation replaced). After
 * this, [resolveActiveScope] for [conversationId] resolves to [scope] with
 * [ScopeSource.CONVERSATION_OVERRIDE].
 */
fun withOverride(
    defaults: ScopeDefaults,
    conversationId: String,
    scope: Scope,
): ScopeDefaults = defaults.copy(
    perConversationOverride = defaults.perConversationOverride + (conversationId to scope),
)

/**
 * Return a copy of [defaults] with any per-conversation override for [conversationId]
 * removed, so the conversation falls back to its project default (or global).
 *
 * Immutable: the receiver is untouched. Clearing an override that does not exist is a no-op
 * that still returns a fresh (equal) copy — clearing is idempotent.
 */
fun clearOverride(
    defaults: ScopeDefaults,
    conversationId: String,
): ScopeDefaults = defaults.copy(
    perConversationOverride = defaults.perConversationOverride - conversationId,
)
