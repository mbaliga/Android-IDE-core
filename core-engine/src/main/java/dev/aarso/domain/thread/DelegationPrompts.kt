package dev.aarso.domain.thread

/**
 * THREAD_TOPOLOGY_PLAN.md WP8's "model-picks-at-branch-point" prompt — owner decision 2's one
 * genuinely *new* "choose for me" surface (the other three — council auto-merge acceptance, loop
 * gateway auto-choice, accepted auto-defaults — reuse machinery that already exists). Mirrors
 * [dev.aarso.domain.council.Council]'s aggregator-prompt shape (short system framing + a user turn
 * laying out the material), but asks for a single index rather than a synthesis: branch
 * alternatives are mutually exclusive continuations to *pick one of*, not voices to merge — this
 * app's human-as-aggregator thesis stays the default (see [dev.aarso.domain.council.Council]'s own
 * KDoc); "choose for me" is the explicit, optional exception at a branch point, same as
 * `autoMergeCouncil` is for the council panel.
 *
 * Pure string-building + parsing, no Android/engine dependency — [dev.aarso.ui.ChatViewModel.chooseForMe]
 * does the actual model call and feeds the raw reply back through [parseChoice].
 */
object DelegationPrompts {

    fun chooseSystemPrompt(): String =
        "You are choosing between several already-written continuations of a conversation. " +
            "Reply with ONLY the number of the one continuation you'd keep — a single digit, " +
            "nothing else. No explanation."

    /** [alternatives] are the previews shown to the model, in the same order the alternatives'
     *  1-based reply numbers refer to — same order [parseChoice]'s caller must pass to [alternatives]. */
    fun chooseUserPrompt(objective: String, alternatives: List<String>): String = buildString {
        if (objective.isNotBlank()) append("Conversation so far:\n").append(objective).append("\n\n")
        append("Which continuation is strongest?\n\n")
        alternatives.forEachIndexed { i, text -> append(i + 1).append(". ").append(text).append("\n\n") }
        append("Reply with just the number.")
    }

    /**
     * Parses the model's raw reply into a 0-based index into [count] alternatives, or `null` when
     * the reply doesn't contain a single in-range digit. An unparseable or out-of-range reply is
     * never silently defaulted to alternative 0 — that would fabricate a delegation the model
     * didn't actually make (the "honest failure, never invented" discipline this codebase applies
     * throughout, e.g. [dev.aarso.domain.bridge.SummaryBridges]' "excerpt, not invention").
     */
    fun parseChoice(raw: String, count: Int): Int? {
        if (count <= 0) return null
        val n = Regex("\\d+").find(raw)?.value?.toIntOrNull() ?: return null
        val idx = n - 1
        return idx.takeIf { it in 0 until count }
    }
}
