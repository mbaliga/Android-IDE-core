package dev.aarso.domain.curation

/**
 * Resolves the fidelity a compaction run must honor for one message, from the defaults in
 * STUDIO_UX_SPEC.md §5.2: *"ordinary messages F1; bookmarked ≥F2; verdict +2 → F3-suggested;
 * verdict −2 → F1 tombstone with failure note; Version spines ≥F2; user dial always wins."*
 *
 * **Precedence (documented here since the spec states the rules but not their interaction when
 * several apply to the same message at once):**
 * 1. An explicit user [CompactionDirective] on the message wins outright — no further
 *    computation, honoring "user dial always wins" literally.
 * 2. A −2 verdict ([VerdictGrade.WRONG]) resolves to an **F1 failure tombstone**, regardless of
 *    any bookmark or version-spine membership that would otherwise floor the fidelity higher.
 *    This is deliberate: a −2 verdict is the most specific, most recently-asserted human signal
 *    ("this approach was wrong") and failure memory is a stated feature ("the agent must not
 *    re-propose buried paths") — a message shouldn't get preserved at higher fidelity just
 *    because it happens to sit on an ancestor path of some other, later-marked version.
 * 3. Otherwise, fidelity is the **maximum** of every applicable floor: [Fidelity.F3] if the
 *    verdict is +2 ([VerdictGrade.REFERENCE]), [Fidelity.F2] if bookmarked, [Fidelity.F2] if on
 *    a version spine, else the [Fidelity.F1] ordinary default. Floors only ever raise fidelity,
 *    never lower it, so combining signals is always safe.
 */
object CompactionContract {

    /**
     * @param msgId the message being resolved.
     * @param directive an explicit user directive for [msgId], if one has been set.
     * @param verdict the human verdict on [msgId], if any.
     * @param isBookmarked true if any [MessageBookmark] (whole-message or block-level) points at
     *   [msgId].
     * @param isOnVersionSpine true if [msgId] lies on the ancestor path of any [Version]'s
     *   branch tip — callers compute this from [dev.aarso.domain.tree.MessageTree] (kept out of
     *   this pure function for testability).
     */
    fun resolve(
        msgId: String,
        directive: CompactionDirective?,
        verdict: Verdict?,
        isBookmarked: Boolean,
        isOnVersionSpine: Boolean,
    ): ResolvedFidelity {
        if (directive != null) {
            return ResolvedFidelity(
                fidelity = directive.fidelity,
                mustInclude = directive.mustInclude,
                isFailureTombstone = false,
                reason = FidelityReason.USER_SET,
            )
        }

        if (verdict != null && verdict.grade == VerdictGrade.WRONG.value) {
            return ResolvedFidelity(
                fidelity = Fidelity.F1,
                mustInclude = false,
                isFailureTombstone = true,
                reason = FidelityReason.VERDICT_NEGATIVE_TOMBSTONE,
            )
        }

        var fidelity = Fidelity.F1
        var reason = FidelityReason.DEFAULT_ORDINARY
        fun raiseTo(candidate: Fidelity, why: FidelityReason) {
            if (candidate > fidelity) {
                fidelity = candidate
                reason = why
            }
        }
        if (verdict != null && verdict.grade == VerdictGrade.REFERENCE.value) {
            raiseTo(Fidelity.F3, FidelityReason.VERDICT_POSITIVE)
        }
        if (isBookmarked) {
            raiseTo(Fidelity.F2, FidelityReason.BOOKMARKED)
        }
        if (isOnVersionSpine) {
            raiseTo(Fidelity.F2, FidelityReason.VERSION_SPINE)
        }

        return ResolvedFidelity(
            fidelity = fidelity,
            mustInclude = false,
            isFailureTombstone = false,
            reason = reason,
        )
    }
}

/** The result of [CompactionContract.resolve] — everything a [CompactionEngine] needs to know about one message. */
data class ResolvedFidelity(
    val fidelity: Fidelity,
    val mustInclude: Boolean,
    val isFailureTombstone: Boolean,
    val reason: FidelityReason,
)

/** Which rule decided a [ResolvedFidelity] — surfaced in the Compaction Preview so the fidelity badge is explainable, not just a number. */
enum class FidelityReason {
    USER_SET,
    VERDICT_NEGATIVE_TOMBSTONE,
    VERDICT_POSITIVE,
    BOOKMARKED,
    VERSION_SPINE,
    DEFAULT_ORDINARY,
}

/** What actually happens to a message in a compaction run — the Preview's "fate" badge (STUDIO_UX_SPEC.md §5.2). */
enum class MessageFate {
    KEPT_VERBATIM,
    FAITHFUL,
    GIST,
    DROPPED,
    TOMBSTONE,
}

/** Maps a [ResolvedFidelity] to the [MessageFate] a compaction run will apply. */
object Fates {
    fun forResolution(resolved: ResolvedFidelity): MessageFate = when {
        resolved.isFailureTombstone -> MessageFate.TOMBSTONE
        resolved.fidelity == Fidelity.F3 -> MessageFate.KEPT_VERBATIM
        resolved.fidelity == Fidelity.F2 -> MessageFate.FAITHFUL
        resolved.fidelity == Fidelity.F1 -> MessageFate.GIST
        // F0: droppable, unless must-include floors it to a gist instead of vanishing.
        resolved.mustInclude -> MessageFate.GIST
        else -> MessageFate.DROPPED
    }
}
