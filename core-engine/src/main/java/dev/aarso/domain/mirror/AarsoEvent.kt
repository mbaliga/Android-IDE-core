package dev.aarso.domain.mirror

/**
 * One line of the Aarso event log (STUDIO_UX_SPEC.md §10): *"append-only, on-device, excluded
 * from sync by default... core writes, nothing reads."* This is the write-side data shape only —
 * no computation, no baseline, no display. Those all wait on the owner-supplied methodology
 * (Aarso Issue #2), per CLAUDE.md rule 4 and the mirror seam's own inert-by-default contract
 * ([MirrorSeam]/[InertMirrorLens]).
 *
 * @property payloadJson opaque per-[kind] JSON, mirroring [dev.aarso.domain.MessageNode.metadata]'s
 *   "free-form, persisted as JSON, the writer knows the shape" split — [AarsoEventLog] doesn't
 *   need to understand a payload's shape to append it.
 * @property sessionRef optional correlation id (e.g. a conversation or loop-run id) so a future
 *   reader (post-ratification) can group events without this layer needing to know why.
 */
data class AarsoEvent(
    val t: Long,
    val kind: AarsoEventKind,
    val payloadJson: String,
    val sessionRef: String? = null,
)

/** The event kinds named in STUDIO_UX_SPEC.md §10's schema, verbatim. */
enum class AarsoEventKind(val wire: String) {
    VERDICT("verdict"),
    BOOKMARK("bookmark"),
    VERSION("version"),
    REWIND("rewind"),
    ROUNDTABLE_OUTCOME("roundtable_outcome"),
    MODEL_CHOICE("model_choice"),
    COMPACTION_RUN("compaction_run"),
    COMPOSER_EDIT_DELTA("composer_edit_delta"),
}
