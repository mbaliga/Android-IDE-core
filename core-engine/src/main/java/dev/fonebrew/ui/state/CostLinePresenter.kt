package dev.fonebrew.ui.state

import dev.fonebrew.domain.format.LocaleFormat
import java.util.Locale

/**
 * Where a chat turn's reply actually came from, for the one purpose this presenter cares about:
 * whether a cost line could ever apply to it. Deliberately **not**
 * [dev.fonebrew.domain.ledger.Provenance] — that type also has `MIXED`, which describes a
 * *period* rollup across many turns and has no meaning for a single turn.
 */
enum class TurnProvenance {
    /** Local generation. Free by construction — never priced, never shown a cost line, no
     *  matter what a caller passes as [TurnCostMetadata] (defence in depth, not just economy of
     *  signals — see [CostLinePresenter.resolve]). */
    ON_DEVICE,

    /** A watched cloud provider produced this turn (binding rule 2) — the only provenance a
     *  cost line can ever apply to, and only once it also carries real metadata. */
    CLOUD,
}

/**
 * A turn's recorded cost (Cost epic G1): [costMinor] in the user's own display-currency minor
 * units, [tokensIn]/[tokensOut] the provider-reported counts — exactly the numbers
 * `ChatViewModel.kt` (around :963-980) wrote into the assistant node's metadata at pricing time,
 * never a number computed fresh here. Absent (`null`, via [from]) whenever the engine never
 * recorded a cost for this turn — `docs/design/cost.md`'s binding sovereignty stance: **the
 * engine never invents the numbers**, so a render-time guess is not an option this type allows.
 */
data class TurnCostMetadata(val costMinor: Long, val tokensIn: Long, val tokensOut: Long) {
    companion object {
        /**
         * Parses the raw `metadata["costMinor"]` / `["tokensIn"]` / `["tokensOut"]` strings a
         * [dev.fonebrew.domain.tree.MessageNode] carries into a [TurnCostMetadata], or `null` if
         * any one of the three is missing or fails to parse. Deliberately all-or-nothing: a
         * partially-written record (which the engine never produces, but this stays honest even
         * if one ever showed up corrupted) is treated the same as no record at all, rather than
         * rendering a line with a placeholder in it.
         */
        fun from(costMinorRaw: String?, tokensInRaw: String?, tokensOutRaw: String?): TurnCostMetadata? {
            val costMinor = costMinorRaw?.toLongOrNull() ?: return null
            val tokensIn = tokensInRaw?.toLongOrNull() ?: return null
            val tokensOut = tokensOutRaw?.toLongOrNull() ?: return null
            return TurnCostMetadata(costMinor, tokensIn, tokensOut)
        }
    }
}

/**
 * Pure presenter for **Lane G / owner ruling 2026-09-06** on `docs/design/open-ux-decisions.md`
 * item G (**G1-MODIFIED**): the per-turn inline cost line is real, but it only ever renders
 * behind an intentional, default-OFF Settings toggle (owner's words: *"Cost always visible has
 * to be a toggle turned on intentionally as it takes up screen space and will make the interface
 * look more cluttered"*). [dev.fonebrew.ui.ChatScreen]'s `MessageBubble`/`MessageTurn` composables
 * call [resolve] once per turn and render exactly what comes back (a line of text, or nothing) —
 * they never re-derive any part of this decision themselves, which is what keeps them thin.
 *
 * The decision is the product of three independent inputs, so every combination is enumerable
 * and (see the test) exhaustively covered:
 *  - **toggle state** — the user's own Settings choice ([showPerTurnCost]). Off means silence,
 *    full stop, regardless of the other two: the owner's screen-space ask is unconditional.
 *  - **provenance** ([TurnProvenance]) — on-device turns cost nothing, so there is never a line
 *    for one even if (defensively) some metadata leaked onto the node; only a `CLOUD` turn can
 *    ever show a line.
 *  - **metadata presence** ([TurnCostMetadata]`?`) — a `CLOUD` turn with no recorded cost (the
 *    provider never reported usage, or the record didn't parse) renders nothing rather than an
 *    estimate: `docs/design/cost.md`'s binding sovereignty stance, "the engine never invents the
 *    numbers," applies at render time exactly as much as it does at pricing time.
 *
 * Only when all three line up — toggle on, `CLOUD`, metadata present — does this return text,
 * and even then the text is built purely from the recorded [TurnCostMetadata] via the existing
 * currency/locale formatting ([LocaleFormat]), never a fresh computation.
 */
object CostLinePresenter {

    /**
     * @param showPerTurnCost the user's Settings toggle ([dev.fonebrew.data.SessionStore.perTurnCostInChat]).
     * @param provenance where this turn's reply came from.
     * @param metadata this turn's recorded cost, or `null` if none was ever recorded ([TurnCostMetadata.from]).
     * @param currencyCode the user's display-currency preference (an ISO-4217 code —
     *   [dev.fonebrew.data.PricingStore.currencyCode]), used only to *label* [metadata]'s
     *   already-recorded [TurnCostMetadata.costMinor]; this never fetches or assumes an exchange
     *   rate (binding rule 1).
     * @param locale formats the amount and token counts (Doc 00 §3.3 — every number is
     *   locale-aware, never a bare `toString()`).
     * @return the line to render verbatim, or `null` to render nothing at all.
     */
    fun resolve(
        showPerTurnCost: Boolean,
        provenance: TurnProvenance,
        metadata: TurnCostMetadata?,
        currencyCode: String,
        locale: Locale,
    ): String? {
        if (!showPerTurnCost) return null
        if (provenance != TurnProvenance.CLOUD) return null
        val meta = metadata ?: return null
        val amount = LocaleFormat.currencyMinor(meta.costMinor, currencyCode, locale)
        val tokensIn = LocaleFormat.tokens(meta.tokensIn, locale)
        val tokensOut = LocaleFormat.tokens(meta.tokensOut, locale)
        return "≈ $amount · in $tokensIn / out $tokensOut tok"
    }
}
