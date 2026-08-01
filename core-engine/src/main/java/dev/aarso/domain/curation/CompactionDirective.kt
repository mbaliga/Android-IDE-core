package dev.aarso.domain.curation

/**
 * A user-set compaction instruction for one message (STUDIO_UX_SPEC.md §5.2, the "Compaction
 * Contract"). At most one directive lives per message ([msgId] is the natural key) — setting a
 * new fidelity replaces the prior one. Absence of a directive means "use the default fidelity"
 * (see [CompactionDefaults]), not F0.
 *
 * @property mustInclude the curation sheet's separate "Must-include" toggle (STUDIO_UX_SPEC.md
 *   §4.6 item 3) — independent of [fidelity]: a must-include message is never dropped by a
 *   compaction run regardless of its fidelity dial, but its *fidelity* still governs how much of
 *   it survives (a must-include F1 message still compacts to a one-line gist, it just can't
 *   vanish to F0).
 */
data class CompactionDirective(
    val msgId: String,
    val mustInclude: Boolean,
    val fidelity: Fidelity,
)

/**
 * The four fidelity levels a compaction agent must honor. Ordered worst-to-best-preserved so
 * `Fidelity.F2 >= Fidelity.F1` etc. reads naturally via [Comparable].
 */
enum class Fidelity {
    /** May vanish entirely. */
    F0,

    /** One line preserving the point. */
    F1,

    /** Details, names, numbers preserved; paraphrase allowed. */
    F2,

    /** Reproduced exactly; the compaction agent may not touch it. Mechanically verified — see [dev.aarso.domain.curation.CompactionVerifier]. */
    F3,
}

/** Pure directive-set operations, kept separate from persistence. */
object CompactionDirectives {

    /** Last-write-wins merge for sync — one directive per message, same shape as [Verdicts.mergeLww] but directives don't carry their own timestamp, so callers merge by "the directive from the newer sync batch wins," tracked by the caller's own clock, not encoded here. */
    fun byMessage(directives: List<CompactionDirective>): Map<String, CompactionDirective> =
        directives.associateBy { it.msgId }
}
