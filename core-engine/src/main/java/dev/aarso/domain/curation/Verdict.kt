package dev.aarso.domain.curation

/**
 * A human judgment on one assistant message (STUDIO_UX_SPEC.md §4.2/§5.1). Distinct from any
 * AI self-evaluation — `author` is always the human, never a model. Exactly one verdict lives
 * per message ([msgId] is the natural key): re-rating replaces the prior grade rather than
 * accumulating a history, matching the spec's "one live per message."
 *
 * [grade] is one of the four detents: -2 (wrong/harmful) / -1 (off) / +1 (useful) /
 * +2 (reference-grade). Zero is not a valid grade — "no verdict" is the absence of a row, not a
 * zero row (see [VerdictGrade]).
 *
 * Verdicts are annotations, never filing: rating a message never moves, archives, or deletes it
 * anywhere in the tree. This is a deliberate patent design-around (Dropbox/Mailbox US 9,729,695
 * claims drag-distance-selects-*destination*-among-collections; this is drag-distance-selects-a-
 * *judgment-value*, with no destination and no collection) as well as the cleaner product
 * metaphor — keep it true in every caller, not just here.
 */
data class Verdict(
    val msgId: String,
    val grade: Int,
    val at: Long,
) {
    init {
        require(VerdictGrade.isValid(grade)) { "Invalid verdict grade: $grade (must be one of ${VerdictGrade.VALID_VALUES})" }
    }
}

/** The four verdict detents. [value] is the wire/storage representation ([Verdict.grade]). */
enum class VerdictGrade(val value: Int) {
    WRONG(-2),
    OFF(-1),
    USEFUL(1),
    REFERENCE(2);

    companion object {
        val VALID_VALUES: Set<Int> = entries.map { it.value }.toSet()

        fun isValid(grade: Int): Boolean = grade in VALID_VALUES

        fun fromValue(value: Int): VerdictGrade =
            entries.firstOrNull { it.value == value }
                ?: error("Invalid verdict grade: $value")
    }
}

/** Pure verdict-set operations, kept separate from persistence (mirrors [dev.aarso.domain.tree.Bookmarks]'s "pure algebra here" split). */
object Verdicts {

    /**
     * Last-write-wins merge for sync (STUDIO_UX_SPEC.md §5.1: "verdict = LWW per message").
     * Returns [incoming] if it is newer or equally new (a re-rating at the same instant on the
     * same device overwrites, which is the common single-writer case); otherwise [existing].
     */
    fun mergeLww(existing: Verdict?, incoming: Verdict): Verdict =
        if (existing == null || incoming.at >= existing.at) incoming else existing

    /** True for the two positive detents ([VerdictGrade.USEFUL], [VerdictGrade.REFERENCE]). */
    fun isPositive(verdict: Verdict): Boolean = verdict.grade > 0

    /** True for the two negative detents ([VerdictGrade.WRONG], [VerdictGrade.OFF]). */
    fun isNegative(verdict: Verdict): Boolean = !isPositive(verdict)
}
