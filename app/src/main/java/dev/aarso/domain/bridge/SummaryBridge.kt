package dev.aarso.domain.bridge

import dev.aarso.domain.provenance.ProvenanceState

/**
 * Summary-bridge model — the **legible seam at a mid-conversation switch** (Doc 01 §4.3 /
 * Doc 04 §A.6). When the active *model* or *interaction model* changes part-way through a
 * chat, the spine branches: an earlier turn becomes the active leaf's new parent and a fresh
 * branch grows from it. The branch should not start *amnesiac*, but it must also not pretend
 * the switch never happened — so the app inserts a distinct **Summary** node at the fork.
 *
 * That node is a *bridge*: a short carry-forward of prior context that lets the new branch
 * start informed, framed honestly as "Switched from {A} to {B}. Carried forward: …", with a
 * "view full prior context" affordance and its own provenance (which model actually wrote the
 * bridge text). The honesty rule (binding rule 2) applies here too — the summary itself was
 * *computed somewhere*, and that somewhere is a watched object iff it reached cloud.
 *
 * **What is pure here, and what is not.** The summary *prose* is a model call — a real
 * summarizer compresses the prior turns into a paragraph; that is emphatically NOT this file.
 * This file builds the **structural payload** around that call, deterministically and with no
 * I/O, clock, or randomness:
 *  - the deterministic **header** ("what changed" framing) from the [SwitchEvent],
 *  - the **carry-forward selection heuristic** — which prior turns a summarizer should be fed,
 *    chosen by a documented, reproducible rule (recency + the original objective), and
 *  - the assembled [SummaryBridge] receipt, including provenance + author attribution.
 *
 * Keeping selection honest: [SummaryBridges.selectCarryForward] does **not** fabricate a
 * summary. It selects *source* bullets — verbatim slices of the user's own turns — that a
 * downstream summarizer would compress. The bridge therefore degrades gracefully: even with
 * no model available, the carry-forward bullets are truthful excerpts, never invented claims.
 *
 * Pure domain (no Android, no network). Reuses the committed
 * [dev.aarso.domain.provenance.ProvenanceState] so the bridge's provenance is the same legible
 * "watched object" model used everywhere else. JVM-tested.
 */

/** Which axis of the conversation switched, forcing the branch + bridge. */
enum class SwitchKind {
    /** The active inference *model* changed (e.g. a local model → a cloud model, or vice-versa). */
    MODEL,

    /**
     * The *interaction model* changed (single ⇄ council, council shape, …). This is the
     * "immutable once a chat starts" rule's escape hatch — changing it branches with a summary.
     */
    INTERACTION_MODEL,
}

/**
 * A single switch on the spine. [from] and [to] are human display names (a model id, a council
 * preset name) — opaque strings to this layer, rendered verbatim into the [SummaryBridge.header].
 */
data class SwitchEvent(
    val kind: SwitchKind,
    val from: String,
    val to: String,
)

/**
 * A minimal, self-contained view of one prior message — just what the selection heuristic needs.
 * Deliberately *not* the full tree node type: the bridge layer stays decoupled from the spine, so
 * a caller projects whatever node shape it has down to this trio.
 *
 * @param role the speaker, e.g. `"user"` or `"assistant"` (free-form; the heuristic only special-
 *   cases `"user"` to find the original objective).
 * @param text the message body, verbatim.
 * @param tokenEstimate a caller-supplied rough token count for [text], used purely to honour the
 *   carry-forward token budget. Non-negative; the heuristic treats negatives as 0.
 */
data class PriorTurn(
    val role: String,
    val text: String,
    val tokenEstimate: Int,
)

/**
 * The structural payload inserted at the fork — the "Summary" message's data, minus the prose.
 *
 * @param header the deterministic "what changed" line built from the [SwitchEvent]
 *   (see [SummaryBridges.header]).
 * @param carriedForward the selected source bullets a summarizer compresses — verbatim excerpts,
 *   not invented summary. Empty when there was no prior context.
 * @param fullPriorAvailable true whenever there was *any* prior context — drives the
 *   "view full prior context" affordance. False only for an empty prior history.
 * @param authorModel the model that produced (or will produce) the bridge prose, for attribution;
 *   null when no model authored it (e.g. a structural-only bridge with no summary call yet).
 * @param authorProvenance the provenance of that authoring — the same watched-object receipt
 *   used across the app, so a cloud-written bridge is honestly flagged.
 */
data class SummaryBridge(
    val header: String,
    val carriedForward: List<String>,
    val fullPriorAvailable: Boolean,
    val authorModel: String?,
    val authorProvenance: ProvenanceState,
)

/**
 * Pure operations that construct a [SummaryBridge]. No Android, no I/O, no clock, no random —
 * every output is a deterministic function of its inputs, which is the whole point of putting the
 * structural bridge logic here rather than in a render or a model wrapper.
 */
object SummaryBridges {

    /** Max characters of a turn's text kept in a carry-forward bullet before the ellipsis. */
    private const val BULLET_TEXT_CHARS = 80

    /** The single-character ellipsis appended when a bullet's source text was truncated. */
    private const val ELLIPSIS = "…"

    /**
     * The deterministic "what changed" framing line. Phrased per axis so the user can tell a
     * model swap from an interaction-model swap at a glance:
     *  - [SwitchKind.MODEL]             → `"Switched model: {from} → {to}"`
     *  - [SwitchKind.INTERACTION_MODEL] → `"Switched interaction model: {from} → {to}"`
     *
     * Pure string assembly — [SwitchEvent.from] / [SwitchEvent.to] are rendered verbatim.
     */
    fun header(event: SwitchEvent): String {
        val noun = when (event.kind) {
            SwitchKind.MODEL -> "model"
            SwitchKind.INTERACTION_MODEL -> "interaction model"
        }
        return "Switched $noun: ${event.from} → ${event.to}"
    }

    /**
     * Pick the prior turns most worth carrying across the fork — the *input selection* a real
     * summarizer would then compress. Deterministic and documented; it never invents content.
     *
     * The heuristic, in order:
     * 1. **Always anchor the original objective.** If any `"user"` turn exists, the *first* one
     *    is included — it is the conversation's stated goal and is the context most often lost at
     *    a switch. It is reserved a slot up front so a long tail of recent turns can never crowd
     *    it out.
     * 2. **Then prefer recency.** Remaining turns are taken newest-first (they are the live
     *    context the new branch continues from).
     * 3. **Render in chronological order.** The anchor + chosen recent turns are emitted oldest-
     *    first so the bullets read like the conversation did.
     * 4. **Respect both caps.** At most [maxBullets] bullets, and the cumulative
     *    [PriorTurn.tokenEstimate] of the chosen turns may not exceed [maxTokens]. The objective
     *    anchor is admitted first (so it wins the budget if anything does); each further turn is
     *    added only if it fits the remaining budget *and* a bullet slot remains. A single oversized
     *    turn does not abort the scan — later, smaller turns may still fit.
     *
     * Each bullet is `"{role}: {first ~80 chars of text}{… if truncated}"`, with the text
     * collapsed to single spaces so a multi-line turn stays one tidy line. These are verbatim
     * excerpts of source turns, not a summary — honest input for the model call to come.
     *
     * @return chronological source bullets; empty when [priorTurns] is empty or both caps are 0.
     */
    fun selectCarryForward(
        priorTurns: List<PriorTurn>,
        maxBullets: Int = 5,
        maxTokens: Int = 400,
    ): List<String> {
        if (priorTurns.isEmpty() || maxBullets <= 0 || maxTokens <= 0) return emptyList()

        // Index by original position so we can both prioritise and later restore chronology.
        val firstUserIndex = priorTurns.indexOfFirst { it.role == "user" }

        // Build a priority order of *indices*: the original objective first (if any), then the
        // rest newest-first, skipping the objective so it is never considered twice.
        val priorityIndices = buildList {
            if (firstUserIndex >= 0) add(firstUserIndex)
            for (i in priorTurns.indices.reversed()) {
                if (i != firstUserIndex) add(i)
            }
        }

        // Admit in priority order under both caps; a too-big turn is skipped, not fatal.
        val chosen = mutableListOf<Int>()
        var tokenBudget = maxTokens
        for (i in priorityIndices) {
            if (chosen.size >= maxBullets) break
            val cost = priorTurns[i].tokenEstimate.coerceAtLeast(0)
            if (cost > tokenBudget) continue
            tokenBudget -= cost
            chosen.add(i)
        }

        // Emit chronologically so the bullets read in conversation order.
        return chosen.sorted().map { bullet(priorTurns[it]) }
    }

    /** Format one turn as a verbatim, single-line, truncated source bullet. */
    private fun bullet(turn: PriorTurn): String {
        val collapsed = turn.text.replace(Regex("\\s+"), " ").trim()
        val body =
            if (collapsed.length > BULLET_TEXT_CHARS) {
                collapsed.substring(0, BULLET_TEXT_CHARS) + ELLIPSIS
            } else {
                collapsed
            }
        return "${turn.role}: $body"
    }

    /**
     * Assemble the full [SummaryBridge] payload for a switch: deterministic [header], selected
     * carry-forward bullets, the "view full prior context" flag (true iff there was *any* prior
     * context), and the author attribution + provenance threaded straight through.
     *
     * The [authorModel] / [authorProvenance] describe whoever produced the bridge prose; this
     * function makes no claim about that text, it only records the receipt.
     */
    fun build(
        event: SwitchEvent,
        priorTurns: List<PriorTurn>,
        authorModel: String?,
        authorProvenance: ProvenanceState,
        maxBullets: Int = 5,
        maxTokens: Int = 400,
    ): SummaryBridge = SummaryBridge(
        header = header(event),
        carriedForward = selectCarryForward(priorTurns, maxBullets, maxTokens),
        fullPriorAvailable = priorTurns.isNotEmpty(),
        authorModel = authorModel,
        authorProvenance = authorProvenance,
    )
}
