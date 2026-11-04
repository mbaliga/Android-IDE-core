package dev.fonebrew.domain.thread

/**
 * THREAD_TOPOLOGY_PLAN.md WP8's descriptive rollup for the Instruments panel's
 * "delegated 12 · kept 9 · reverted 3" card — three counts, nothing interpreted (binding
 * constraint 3 / CLAUDE.md rule 4: §5b/§5c interpretation stays blocked on Issue #2). [kept] +
 * [reverted] + [pending] always sums to [total].
 */
object DelegationCounts {

    data class Counts(val total: Int, val kept: Int, val reverted: Int, val pending: Int)

    val EMPTY = Counts(total = 0, kept = 0, reverted = 0, pending = 0)

    fun summarize(events: List<DelegationEvent>): Counts = Counts(
        total = events.size,
        kept = events.count { it.outcome == DelegationOutcome.KEPT },
        reverted = events.count { it.outcome == DelegationOutcome.REVERTED },
        pending = events.count { it.outcome == DelegationOutcome.PENDING },
    )
}
