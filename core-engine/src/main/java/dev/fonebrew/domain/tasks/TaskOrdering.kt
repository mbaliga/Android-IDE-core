package dev.fonebrew.domain.tasks

/**
 * Fractional-index ordering for manual drag-reorder: a moved row gets a key strictly
 * between its new neighbours, so reordering never renumbers the rest of the list (no
 * write amplification, no interleaved-write races between two drags).
 */
object TaskOrdering {
    /** Gap used when there's no neighbour on one side, so future inserts on that end
     *  don't immediately need to split. */
    private const val GAP = 1024.0

    /**
     * A key for a row moving between [before] and [after] (both null = the only row).
     * Order is ascending; null [before] = new head, null [after] = new tail.
     */
    fun keyBetween(before: Double?, after: Double?): Double = when {
        before == null && after == null -> 0.0
        before == null -> after!! - GAP
        after == null -> before + GAP
        else -> {
            require(before < after) { "keyBetween requires before < after (got $before, $after)" }
            before + (after - before) / 2.0
        }
    }

    /** The key for a freshly-appended row, given the current tail (null = empty list). */
    fun keyForAppend(currentTail: Double?): Double = keyBetween(currentTail, null)
}
