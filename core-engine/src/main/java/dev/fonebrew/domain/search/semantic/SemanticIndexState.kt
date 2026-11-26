package dev.fonebrew.domain.search.semantic

/** The user-visible lifecycle of the semantic index (docs/design/semantic-search.md section 7). */
sealed interface SemanticIndexState {
    data object NotEnabled : SemanticIndexState
    data object NoModel : SemanticIndexState
    data class Building(val done: Int, val total: Int, val paused: Boolean) : SemanticIndexState
    data object Ready : SemanticIndexState
    /** Some indexed docs were made by a different embedder (or changed); [count] need re-embedding. */
    data class Stale(val count: Int) : SemanticIndexState
}

object SemanticIndexStates {

    /**
     * @param modelUsable a model is chosen AND its file is installed.
     * [running] is whether an indexing pass is active: stale docs are only reported as Stale while
     * idle (a running pass is already fixing them, so it reads as Building).
     * @param total docs in the corpus; @param upToDate docs whose vectors match the current embedder and content;
     * @param stale docs that have vectors from a different embedder (needs rebuild, not just "not yet embedded").
     */
    fun derive(enabled: Boolean, modelUsable: Boolean, total: Int, upToDate: Int, stale: Int, paused: Boolean, running: Boolean): SemanticIndexState = when {
        !enabled -> SemanticIndexState.NotEnabled
        !modelUsable -> SemanticIndexState.NoModel
        upToDate >= total -> SemanticIndexState.Ready
        stale > 0 && !paused && !running -> SemanticIndexState.Stale(stale)
        else -> SemanticIndexState.Building(upToDate, total, paused)
    }
}
