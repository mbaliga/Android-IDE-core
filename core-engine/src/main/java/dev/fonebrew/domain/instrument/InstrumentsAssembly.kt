package dev.fonebrew.domain.instrument

import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.inspect.Availability
import dev.fonebrew.domain.inspect.TokenScore
import dev.fonebrew.domain.ledger.LedgerAggregations
import dev.fonebrew.domain.ledger.LedgerEntry
import dev.fonebrew.domain.scope.ContextAssembly
import dev.fonebrew.domain.scope.Corpus
import dev.fonebrew.domain.scope.CorpusPiece
import dev.fonebrew.domain.scope.CorpusSource
import dev.fonebrew.domain.scope.Scope

/**
 * THREAD_TOPOLOGY_PLAN.md WP7 — the pure glue the Instruments panel is built from. Three
 * small, independent conversions, each over facts already computed elsewhere in the chat
 * loop — no Android, no IO, no clock, deterministic and JVM-tested:
 *
 *  1. [assembleConversation] turns the active leaf's effective (compaction-boundary-aware)
 *     path into a [ContextAssembly.Assembled] ledger — wiring Doc 03's context-assembly
 *     floor ([dev.fonebrew.domain.scope.ContextAssembly], previously built but callerless per
 *     the plan's "what already exists" inventory) for the first time. Chat has no
 *     cross-project knowledge corpus yet (that's the file/memory/attachment corpus of
 *     Doc 03 §6, a separate, still-mostly-unmounted surface) — every turn is filed under a
 *     single project bucket, so [Scope] is always pinned to [Scope.ThisProject] and the
 *     ledger degenerates to "does the path fit the model's window, and if not, which turns
 *     would the floor's own pinned-then-recency rule keep". That second reading is a
 *     **legibility preview of the assembly floor's own rule**, not a claim that the app
 *     already truncates chat this way — today an over-window chat is surfaced only as the
 *     existing `ContextCheck` "over by N" warning and left to the user's own Compaction run
 *     (WP3); nothing is silently cut by this ledger.
 *  2. [tokenScores] maps a turn's streamed [GeneratedToken]s to the [TokenScore]s
 *     [TokenInspector] needs, plus the honest [Availability] this turn's source actually
 *     reported — never a fabricated reading (mirrors [TokenInspector]'s own honesty rule).
 *  3. [totalsForChat] folds the on-device usage ledger ([LedgerEntry]) down to the one
 *     conversation the panel is open on, via the already-tested [LedgerAggregations.totals]
 *     — the same fold the "Myself" screen uses over the whole ledger, scoped here to a
 *     single `chatId`.
 */
object InstrumentsAssembly {

    /**
     * One message on the active/effective path, reduced to what [assembleConversation]
     * needs. [label] is the caller's own short, human-facing description (role + excerpt)
     * for the included/cut ledger row. [pinned] is left to the caller to set — this WP does
     * **not** wire `CurationStore`'s per-turn must-include directives into this ledger; a
     * later WP's job, not a pre-build here (forward-pointer, not new invention).
     */
    data class PathTurn(
        val nodeId: String,
        val tokenCount: Int,
        val label: String,
        val pinned: Boolean = false,
    )

    /**
     * Assemble [turns] (oldest first, the order `MessageTreeRepository.path`/
     * `ChatViewModel.effectivePromptPath` already return) into a [ContextAssembly.Assembled]
     * ledger against [budget], filed entirely under [projectId] — see the class KDoc for why
     * chat only ever resolves one project bucket today. The newest turn gets
     * `recencyRank = 0` per [CorpusPiece]'s own contract, so prioritised truncation (only
     * reached when the path is over [budget]) keeps the newest turns first, oldest cut
     * first — mirroring the "most recent" half of the compaction floor's own priority rule.
     */
    fun assembleConversation(
        turns: List<PathTurn>,
        convId: String,
        projectId: String,
        budget: ContextAssembly.ContextBudget,
    ): ContextAssembly.Assembled {
        val lastIndex = turns.size - 1
        val pieces = turns.mapIndexed { index, turn ->
            CorpusPiece(
                id = turn.nodeId,
                source = CorpusSource.Conversation(convId = convId, nodeId = turn.nodeId),
                projectId = projectId,
                tokenCount = turn.tokenCount,
                pinned = turn.pinned,
                recencyRank = lastIndex - index,
                label = turn.label,
            )
        }
        return ContextAssembly.assemble(
            scope = Scope.ThisProject,
            corpus = Corpus(pieces),
            currentProjectId = projectId,
            allProjectIds = setOf(projectId),
            budget = budget,
        )
    }

    /**
     * Map a turn's streamed [GeneratedToken]s to [TokenScore]s + the honest [Availability]
     * this turn's source actually reported.
     *
     * On-device (llama.cpp) turns carry both [GeneratedToken.logprob] and
     * [GeneratedToken.entropy] on every token → [Availability.FULL]. A cloud turn carries
     * neither (no provider wired in this codebase returns per-token logprobs, handoff §3) →
     * [Availability.UNAVAILABLE], with an **empty** score list rather than a fabricated
     * reading. A sequence that is only partially populated (should not happen from a single
     * engine, but never assumed) is treated the same honest way — [Availability.UNAVAILABLE]
     * — rather than rendering a heatmap with silent holes.
     *
     * [Availability.TOPK_ONLY] is never produced here: no engine in this codebase reports a
     * top-k-only shape today ([GeneratedToken] carries no `topK` field). The case is matched
     * so a future top-k-capable cloud provider has a home without a [TokenInspector] /
     * `TokenHeatmap` change.
     */
    fun tokenScores(tokens: List<GeneratedToken>): Pair<List<TokenScore>, Availability> {
        if (tokens.isEmpty()) return emptyList<TokenScore>() to Availability.UNAVAILABLE
        val full = tokens.all { it.logprob != null && it.entropy != null }
        if (!full) return emptyList<TokenScore>() to Availability.UNAVAILABLE
        val scores = tokens.map { t ->
            TokenScore(token = t.text, logprob = t.logprob!!.toDouble(), entropy = t.entropy!!.toDouble())
        }
        return scores to Availability.FULL
    }

    /**
     * [LedgerAggregations.totals] scoped to one conversation ([chatId]) — the panel's
     * input/output card, without folding in every other conversation's usage the way the
     * "Myself" screen's whole-ledger view does.
     */
    fun totalsForChat(entries: List<LedgerEntry>, chatId: String): LedgerAggregations.Totals =
        LedgerAggregations.totals(entries.filter { it.chatId == chatId })
}
