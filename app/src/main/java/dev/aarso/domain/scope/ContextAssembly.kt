package dev.aarso.domain.scope

/**
 * Context assembly: the deterministic floor of Doc 03's hybrid context-assembly model.
 *
 * The full model is *hybrid* — a deterministic, rule-based floor plus an embedder-driven
 * semantic [AssemblyMode.Recall] layer on top. This file ships **only the floor**, which
 * needs no embedder and is therefore fully unit-testable today:
 *
 * 1. **Scope** decides which projects are in play ([Scopes.resolveProjects]).
 * 2. The corpus is **filtered** to those projects.
 * 3. If everything fits the budget → [AssemblyMode.Verbatim] (include all, cut nothing).
 * 4. Otherwise → [AssemblyMode.PrioritizedTruncation]: include by an *explicit* priority
 *    (pinned first, then most-recent, id-tie-broken) until the budget is hit; the rest
 *    are cut. **Pinned pieces are always included** even when that exceeds the budget —
 *    the meter then reports the overage honestly via [BudgetMeter.overBudget].
 *
 * The chosen [AssemblyMode] is a **watched object**: it is always part of the result, so
 * the UI can always say *how* this turn's context was built. The `included` / `cut` lists
 * are the legibility surface — exact and attributed, so the user sees precisely what the
 * model saw and what it didn't.
 *
 * Deterministic by construction: no clock, no randomness. Identical inputs always produce
 * an identical [Assembled] — including the *order* of `included` and `cut`. Pure domain
 * (no Android, no IO, no embedder), JVM-tested.
 */
object ContextAssembly {

    /**
     * The token budget for a turn. [total] is the model's whole context window;
     * [reserved] is held back for the live conversation + the model's reply. What remains
     * is [availableForCorpus] — the room the scoped corpus is allowed to fill.
     */
    data class ContextBudget(val total: Int, val reserved: Int) {
        /** Tokens left for corpus pieces after the conversation/reply reservation. Never negative. */
        val availableForCorpus: Int get() = maxOf(0, total - reserved)
    }

    /**
     * How the context for a turn was assembled — always knowable (a watched object).
     *
     * - [Verbatim]              — the entire scoped corpus fit; nothing was cut.
     * - [PrioritizedTruncation] — over budget; included by explicit priority (the
     *   pre-embedder rule), the remainder cut.
     * - [Recall]                — reserved for the embedder-driven semantic-retrieval
     *   layer. **Not implemented here** (the floor ships without an embedder). The case
     *   exists so the type is complete and call sites can switch on it once Recall lands.
     */
    enum class AssemblyMode {
        Verbatim,
        PrioritizedTruncation,

        /**
         * TODO(embedder): embedding-based semantic recall. When a real on-device embedder
         * replaces `PlaceholderEmbedder`, this mode retrieves the most *relevant* pieces
         * (not merely the most recent) within budget. Deliberately unimplemented — the
         * deterministic floor never selects it.
         */
        Recall,
    }

    /**
     * A reading of where the budget went, for the meter UI. All counts are in tokens.
     *
     * [fractionUsed] is `usedTokens / total`, clamped to `[0, 1]` — so the meter never
     * shows more than full even when pins push the corpus over budget (the honest
     * over-budget signal lives in [overBudget], not in a >1 fraction).
     */
    data class BudgetMeter(
        /** Tokens actually consumed = corpus tokens included + the conversation reservation. */
        val usedTokens: Int,
        /** Tokens that were available for corpus pieces ([ContextBudget.availableForCorpus]). */
        val availableTokens: Int,
        /** Tokens of the [usedTokens] that came from included corpus pieces. */
        val corpusTokens: Int,
        /** Tokens reserved for the live conversation + reply ([ContextBudget.reserved]). */
        val reservedTokens: Int,
        /** The model's whole context window ([ContextBudget.total]). */
        val totalTokens: Int,
        /**
         * True when guaranteed (pinned) pieces alone exceeded [availableTokens] — i.e. the
         * floor honoured the pins past the corpus budget. The meter stays honest: this flag
         * is the over-budget signal, [fractionUsed] still clamps to 1.0.
         */
        val overBudget: Boolean,
    ) {
        /** `usedTokens / totalTokens`, clamped to `[0, 1]`. Zero when [totalTokens] is 0. */
        val fractionUsed: Double
            get() = if (totalTokens <= 0) 0.0
            else (usedTokens.toDouble() / totalTokens.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * The result of assembling context for a turn — the legibility ledger.
     *
     * [included] and [cut] together partition the scoped corpus and are deterministically
     * ordered (in [AssemblyMode.PrioritizedTruncation], by the same priority used to
     * select: pinned first, then [CorpusPiece.recencyRank] ascending, then id ascending).
     */
    data class Assembled(
        val mode: AssemblyMode,
        val included: List<CorpusPiece>,
        val cut: List<CorpusPiece>,
        val meter: BudgetMeter,
        val scope: Scope,
    )

    /**
     * The deterministic priority comparator for prioritised truncation: pinned pieces
     * first, then most-recent ([CorpusPiece.recencyRank] ascending, 0 = newest), then
     * id ascending as the final tie-breaker so the order is total and reproducible.
     */
    private val PRIORITY: Comparator<CorpusPiece> =
        compareByDescending<CorpusPiece> { it.pinned }
            .thenBy { it.recencyRank }
            .thenBy { it.id }

    /** Token cost of a piece, clamped to `>= 0` (mirrors [Corpus.totalTokens]). */
    private fun cost(piece: CorpusPiece): Int = maxOf(0, piece.tokenCount)

    /**
     * Assemble the context for a turn — the pure entry point.
     *
     * Steps:
     * 1. Resolve [scope] → project ids ([Scopes.resolveProjects]).
     * 2. Filter [corpus] to those projects.
     * 3. If the filtered corpus's total tokens `<=` [ContextBudget.availableForCorpus] →
     *    [AssemblyMode.Verbatim]: everything included, nothing cut.
     * 4. Otherwise → [AssemblyMode.PrioritizedTruncation]: walk the corpus in [PRIORITY]
     *    order; **always** take pinned pieces (even past budget), and take unpinned pieces
     *    greedily while the running corpus total stays `<=` the available budget; cut the
     *    rest. If pins alone exceed the budget the meter's [BudgetMeter.overBudget] flag
     *    is set — pins are a promise the floor keeps, transparently.
     *
     * @return an [Assembled] ledger with a deterministic included/cut split and an honest
     *   budget meter.
     */
    fun assemble(
        scope: Scope,
        corpus: Corpus,
        currentProjectId: String,
        allProjectIds: Set<String>,
        budget: ContextBudget,
    ): Assembled {
        val projects = Scopes.resolveProjects(scope, currentProjectId, allProjectIds)
        val scoped = corpus.filterToProjects(projects)
        val available = budget.availableForCorpus
        val scopedTotal = scoped.totalTokens()

        // Step 3 — the whole scoped corpus fits: Verbatim, in priority order for a stable ledger.
        if (scopedTotal <= available) {
            val included = scoped.pieces.sortedWith(PRIORITY)
            return Assembled(
                mode = AssemblyMode.Verbatim,
                included = included,
                cut = emptyList(),
                meter = meter(
                    corpusTokens = scopedTotal,
                    budget = budget,
                    overBudget = false,
                ),
                scope = scope,
            )
        }

        // Step 4 — over budget: prioritised truncation.
        val ordered = scoped.pieces.sortedWith(PRIORITY)
        val included = ArrayList<CorpusPiece>()
        val cut = ArrayList<CorpusPiece>()
        var includedCost = 0
        for (piece in ordered) {
            val c = cost(piece)
            when {
                // Pins are guaranteed: always included, even if it pushes past the budget.
                piece.pinned -> {
                    included.add(piece)
                    includedCost += c
                }
                // Unpinned: include greedily only while we stay within the budget.
                includedCost + c <= available -> {
                    included.add(piece)
                    includedCost += c
                }
                else -> cut.add(piece)
            }
        }

        return Assembled(
            mode = AssemblyMode.PrioritizedTruncation,
            included = included,
            cut = cut,
            meter = meter(
                corpusTokens = includedCost,
                budget = budget,
                overBudget = includedCost > available,
            ),
            scope = scope,
        )
    }

    /** Build the [BudgetMeter] from the included corpus cost + the budget facts. */
    private fun meter(corpusTokens: Int, budget: ContextBudget, overBudget: Boolean): BudgetMeter =
        BudgetMeter(
            usedTokens = corpusTokens + budget.reserved,
            availableTokens = budget.availableForCorpus,
            corpusTokens = corpusTokens,
            reservedTokens = budget.reserved,
            totalTokens = budget.total,
            overBudget = overBudget,
        )
}
