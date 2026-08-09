package dev.aarso.domain.search

import dev.aarso.contracts.search.SemanticUnavailableReason

/**
 * WP-6's semantic stage: "provider interface + ASOM-routed implementation behind a flag...
 * implemented as far as room allows, contracts first." No real embedding index exists anywhere
 * in this codebase yet (`dev.aarso.embedding.PlaceholderEmbedder` is exactly that -- a
 * placeholder, per its own file), so this pass ships the interface plus exactly one real,
 * honest implementation: [DisabledSemanticSearchProvider], the disabled-flag default the brief
 * asks for. A real ASOM-routed (`127.0.0.1:11435`, see `CAPABILITY_AUTHORITY_MODEL.md` §10.2)
 * implementation is a follow-up, not invented speculatively here.
 */
interface SemanticSearchProvider {
    val enabled: Boolean

    /** `null` iff [enabled] -- callers check [enabled] first, but this stays defensive either way. */
    suspend fun search(query: String, corpus: List<SearchDoc>): SemanticSearchOutcome
}

sealed interface SemanticSearchOutcome {
    data class Results(val hits: List<SearchHit>) : SemanticSearchOutcome
    data class Unavailable(val reason: SemanticUnavailableReason) : SemanticSearchOutcome
}

/** The real default: semantic search is off. Never silently falls back to a fabricated result set. */
object DisabledSemanticSearchProvider : SemanticSearchProvider {
    override val enabled: Boolean = false

    override suspend fun search(query: String, corpus: List<SearchDoc>): SemanticSearchOutcome =
        SemanticSearchOutcome.Unavailable(SemanticUnavailableReason.FEATURE_DISABLED)
}
