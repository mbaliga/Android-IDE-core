package dev.aarso.domain.thread

/**
 * THREAD_TOPOLOGY_PLAN.md WP8: resolves a still-`PENDING` [DelegationEvent] to `KEPT` or
 * `REVERTED` (or leaves it `PENDING` — not enough evidence yet) from what happened to its chosen
 * alternative afterwards. Purely descriptive (binding constraint 3 / CLAUDE.md rule 4 — no
 * §5b/§5c interpretation, Issue #2): this is a three-way classification of *what the tree shows*,
 * never a judgment about *why*.
 *
 * "Reverted" means the delegated choice was rewound or switched away from *within*
 * [REVERT_WINDOW_TURNS] turns of being made. A choice that has stayed on the active path for at
 * least that many turns is `KEPT` for good — a later navigation away to compare or explore a
 * sibling branch ([dev.aarso.ui.ChatViewModel.switchAlternative] / `rewindFrom`, ordinary use of
 * controls that already exist) is not an undoing of the "choose for me" verdict, so this must
 * never flip an already-resolved `KEPT` back to `REVERTED`. The wiring caller
 * ([dev.aarso.ui.ChatViewModel]'s delegation-outcome recompute) only ever calls [correlate] for
 * delegations still `PENDING`, so that invariant holds by construction — this object has no
 * mutable state and does not itself need to re-check a prior resolution.
 *
 * Pure and JVM-testable, same shape as [dev.aarso.domain.curation.CompactionContract.resolve] —
 * every input the truth table depends on is passed in explicitly, no tree/store access here.
 */
object DelegationOutcomes {

    /** Turns a delegated choice must survive on the active path before it's considered `KEPT`. */
    const val REVERT_WINDOW_TURNS = 5

    /**
     * Whether [DelegationEvent.chosenRef] is still where the conversation's active path actually
     * goes ([STILL_ACTIVE]), or the path now runs through a different sibling — or no longer even
     * reaches the branch point — instead ([SWITCHED_OFF]).
     */
    enum class ChoiceStatus { STILL_ACTIVE, SWITCHED_OFF }

    /**
     * @param status see [ChoiceStatus].
     * @param turnsSinceChoice turns elapsed since the choice was made. While [status] is
     *   `STILL_ACTIVE` this is turns *forward* along the active path, past the chosen node, up to
     *   the current leaf. While `SWITCHED_OFF`, the caller passes `0` — a `PENDING` delegation
     *   found switched off has, by construction (see the class KDoc), not yet crossed
     *   [REVERT_WINDOW_TURNS] turns while active, since an earlier recompute would already have
     *   resolved it to `KEPT` otherwise; the exact turn count at the moment of the switch isn't
     *   recoverable from a point-in-time tree snapshot, and isn't needed given that invariant.
     *   Never negative.
     */
    fun correlate(status: ChoiceStatus, turnsSinceChoice: Int): DelegationOutcome {
        require(turnsSinceChoice >= 0) { "turnsSinceChoice must be >= 0, got $turnsSinceChoice" }
        return when {
            status == ChoiceStatus.SWITCHED_OFF && turnsSinceChoice < REVERT_WINDOW_TURNS -> DelegationOutcome.REVERTED
            status == ChoiceStatus.SWITCHED_OFF -> DelegationOutcome.KEPT
            turnsSinceChoice >= REVERT_WINDOW_TURNS -> DelegationOutcome.KEPT
            else -> DelegationOutcome.PENDING
        }
    }
}
