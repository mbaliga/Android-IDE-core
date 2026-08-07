// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// SearchContracts.kt — the "search" domain's shared shapes (WP-6). Unlike every other domain
// this handoff-pack build-out has contracted (common/workspace/execution/authority/devices/
// integrations, all WP-1), no ratified spec doc or schema corpus for search existed before this
// pass -- per docs/WP0_SURVEY.md §1(g), the real lexical search engine (domain/search/
// LexicalSearch.kt, Segmenter.kt, query/*, data/search/SearchIndexer.kt et al.) is "already
// exists, already wired" into the conversation-search UI, but nothing had ever named its index
// source / freshness / cancellation / result-provenance / embedder-provider vocabulary as a
// formal contract, the way WP-1 did for every other domain. This file is that contract, written
// against the real, already-shipped engine rather than a speculative one.
//
// Sovereignty constraint (binding rule 2, applied to this domain): search is on-device by
// default (LexicalSearch is 100% local, no network, no embeddings -- see SearchIndexables.kt's
// own header). A semantic/embedding stage is opt-in, provider-generic, and -- per that binding
// rule -- any cloud embedding provider is a "watched object," never a hidden fallback. This file
// does not invent a new Embedder interface: `dev.aarso.embedding.Embedder` already exists and
// already satisfies "embedder provider interface" from the WP-6 brief; SemanticSearchProvider
// below is the missing piece one layer up (turning embeddings into ranked results), not a
// replacement for it.
//
// Toolchain constraint (binding, matching every other domain's contract file): stdlib +
// java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no Android imports, no
// kotlinx-coroutines Flow (this domain's provider interfaces are single-shot request/response,
// like the authority domain's, not an observed stream).
//
// COMPILATION STATUS: VERIFIED — compiled as part of core-engine's real Gradle build
// (:core-engine:compileFullDebugKotlin / :core-engine:testFullDebugUnitTest) during WP-6
// (2026-08-07), unlike WP-1's contract files (which predate this session's real-toolchain
// discovery and were written before a compiler was available).

package dev.aarso.contracts.search

import java.time.Instant

/** Which real subsystem a search result was projected from — the two the WP-6 brief names by name. */
enum class IndexSourceKind {
    /** `data/search/SearchIndexer.kt`'s existing SQLDelight FTS5 index over the message tree. */
    CONVERSATION,

    /** WP-3's Workspace Kernel — a `DocumentBuffer`/journal-derived projection, new this pass. */
    WORKSPACE_BUFFER,
}

/**
 * How current an indexed projection is against its authoritative source (FB-RAT-WS-006: indexes
 * are disposable projections, buffers/journals are authoritative — this is that invariant's
 * search-domain instance). `sourceRevisionOrSequence` is source-kind-specific: a message-tree
 * node id/revision for [IndexSourceKind.CONVERSATION], a `BufferJournalEntry.sequence` (as a
 * string) for [IndexSourceKind.WORKSPACE_BUFFER].
 */
data class IndexFreshness(
    val sourceRevisionOrSequence: String,
    val indexedAtUtc: Instant,
    val isStale: Boolean,
) {
    init {
        require(sourceRevisionOrSequence.isNotBlank()) { "IndexFreshness.sourceRevisionOrSequence must be non-blank." }
    }
}

/**
 * A cancellation signal a long-running search/index pass polls, mirroring
 * `dev.aarso.contracts.execution.CancelMode`'s spirit for this domain's own single-shot
 * operations (index a corpus, run a semantic query) without pulling in that domain's
 * request/handle machinery this domain doesn't need.
 */
fun interface SearchCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NEVER: SearchCancellation = SearchCancellation { false }
    }
}

/**
 * Where one [dev.aarso.domain.search.SearchHit]-equivalent result actually came from, so a UI
 * can show "from this file, indexed 2 minutes ago" rather than presenting every result as
 * equally fresh/authoritative. `sourceId` is a `bufferId` or conversation id, source-kind-specific.
 */
data class ResultProvenance(
    val sourceKind: IndexSourceKind,
    val sourceId: String,
    val freshness: IndexFreshness,
) {
    init {
        require(sourceId.isNotBlank()) { "ResultProvenance.sourceId must be non-blank." }
    }
}

/**
 * Query grammar reference (WP-6 brief: "query grammar"). NOT redefined here — the real grammar
 * already exists and is already tested at `domain/search/query/QueryParser.kt` (free text +
 * `field:value` facets + relative dates, `query/ConversationFacetFilter.kt`/`FacetEvaluator.kt`/
 * `ScopeStack.kt`) and `domain/search/query/QueryCompiler.kt`. This object exists only so the
 * grammar has a citable name in this contract file, per the same "cross-reference, don't
 * redefine" instruction WP-1's domains followed for shapes owned elsewhere.
 */
object QueryGrammarRef {
    const val DESCRIPTION = "domain/search/query/QueryParser.kt -- free text + field:value facets + relative dates"
}

/**
 * FB-RAT-COM-001-style closed vocabulary for why a semantic search call did not run, when it
 * doesn't: [SemanticSearchProvider] is a contract this pass ships with exactly one real
 * implementation ([dev.aarso.domain.search.DisabledSemanticSearchProvider]) — "unimplemented
 * semantic feature exists as contract + disabled flag + TODO ledger," per the WP-6 brief's own
 * resume-seam instruction, not a speculative embedding pipeline this pass has no way to verify.
 */
enum class SemanticUnavailableReason {
    FEATURE_DISABLED,
    NO_EMBEDDER_CONFIGURED,
    PROVIDER_ERROR,
}
