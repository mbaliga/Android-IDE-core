package dev.fonebrew.domain.thread

/**
 * A "choose for me" record (THREAD_TOPOLOGY_PLAN.md's `delegation_events` table; owner decision
 * 2 — "one unified delegation-event concept, kept-vs-reverted tracked descriptively only").
 * Covers all four surfaces uniformly: model-picks-at-branch-point, council auto-merge acceptance,
 * loop gateway auto-choice, and accepted auto-defaults.
 *
 * Shaped like [ThreadMarker] (`at: Long` epoch millis, plain data class): [dev.fonebrew.data.DelegationStore]
 * is its Room-backed home, same relationship [dev.fonebrew.data.CurationStore] has to `Version`.
 *
 * @property outcome tracked **descriptively only** — never interpreted (THREAD_TOPOLOGY_PLAN.md
 *   binding constraint 3 / CLAUDE.md rule 4: §5b/§5c interpretation stays blocked on Issue #2).
 *   `KEPT`/`REVERTED` resolution is a later work package's job (`DelegationOutcomes.correlate`,
 *   WP8) — this type only carries the field, it does not compute it.
 */
data class DelegationEvent(
    val id: String,
    val at: Long,
    val kind: DelegationKind,
    val rootId: String? = null,
    val anchorMsgId: String? = null,
    val chosenRef: String? = null,
    val alternatives: List<String> = emptyList(),
    val outcome: DelegationOutcome = DelegationOutcome.PENDING,
    val outcomeAt: Long? = null,
)

/** The four "choose for me" surfaces, unified under one concept per owner decision 2. */
enum class DelegationKind {
    MODEL_PICK_BRANCH,
    COUNCIL_AUTOMERGE,
    GATEWAY_AUTO,
    AUTO_DEFAULT,
}

enum class DelegationOutcome {
    PENDING,
    KEPT,
    REVERTED,
}
