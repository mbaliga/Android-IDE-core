// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// LoopRuntimeContracts.kt — the "loop runtime" domain's shared wire-shape data classes and the
// 17-state run machine.
//
// Mirrors, field-for-field, the JSON Schema documents under schemas/loops/*.schema.json:
//   LoopRun        -> loop-run.schema.json
//   LoopRunSummary -> loop-run-summary.schema.json
// If a field appears in one place, it MUST appear in the other, or the two have drifted and one
// of them is wrong. Normative grounding is docs/ratified/loops/LOOP_ENGINEERING_SPEC_V2.1.md §9
// (the 17-state run machine, its transition table, and Amendment §2's WAITING_USER/CANCELLED
// changes relative to the base pack) plus docs/ratified/loops/LOOP_RESULT_SHARING_CONTRACT.md §3
// (the share-flow state machine LoopRunSummary is the middle stage of).
//
// FULFILLS A FORWARD POINTER: contracts/kotlin/LoopDefinitionContracts.kt's `TerminalRunState`
// enum KDoc states explicitly: "the full 17-state machine with its nine non-terminal states
// belongs to a run/receipt contract — LoopRuntimeContracts.kt, forward-pointed by
// LOOP_ENGINEERING_SPEC_V2.1's cross-references, not written by this work package." THIS is that
// file. [RunState] below is the fulfillment; it does not replace [TerminalRunState] (kept exactly
// as that file's own definition-time-reference use case needs it), and the two are reconciled by
// name (not by inheritance — Kotlin enums cannot extend one another) via
// [RunState.toTerminalRunStateOrNull].
//
// Cross-package reuse (first-party imports, not third-party dependencies):
// dev.fonebrew.contracts.common.ArtifactRef, dev.fonebrew.contracts.common.SideEffectState — reused
// as-is, matching every other domain's file in this directory.
//
// Same-PACKAGE reuse (no import needed — Kotlin visibility is automatic within one package;
// dev.fonebrew.contracts.loops now spans seven files): [dev.fonebrew.contracts.loops.DurableObjectRef]
// (LoopActivationContracts.kt), [BaseRevisionRef] (LoopAuthoringContracts.kt — reused directly
// for [LoopRun.sourceRef]: "one draft or installed release" is exactly what that sealed interface
// already models), [ActorClass] (LoopAuthoringContracts.kt), [ExecutionTargetType]/[NodeCategory]/
// [TerminalRunState] (LoopDefinitionContracts.kt), [ReleaseIdentity] (LoopPackageContracts.kt).
// None of these are redefined here. This file's own [RunState]/[TerminalReasonCategory]/
// [LocalCloudRatio]/[NodeAttemptOutcome] are, in turn, reused BY NAME (same-package visibility,
// no import) from contracts/kotlin/LoopResultAndLineageContracts.kt (same package) for
// [dev.fonebrew.contracts.loops.LoopResultShare] and its bucketed node-outcome-count map — see that
// file's own header for the reverse direction of this cross-file dependency note.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies — stdlib +
// java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no Android imports, no
// kotlinx-coroutines Flow (this work package's task brief asks for wire-shape data classes plus a
// state machine, not a streaming provider interface — no ExecutionProvider/WorkspaceProvider-style
// contract is invented here).
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build environment —
// this file has been written carefully (balanced braces, matched types, no typos attempted) but
// has NOT been compiled. Do not report it as compiling; that is for the next session with Gradle
// available to confirm. This file depends on contracts/kotlin/CommonContracts.kt and, within this
// same package, contracts/kotlin/LoopActivationContracts.kt, LoopAuthoringContracts.kt,
// LoopDefinitionContracts.kt, LoopPackageContracts.kt, and LoopResultAndLineageContracts.kt being
// compiled in the same module/source set.
//
// Why [RunState] IS a real (enum-plus-transition-table) state machine here, matching
// LoopActivationContracts.kt's [InstallationState] precedent rather than LoopPackageContracts.kt's
// deliberate choice to have none: this domain owns the actual live runtime state of an
// in-progress-or-completed loop execution — exactly the "domains that own actual state" carve-out
// the WP-0 survey and every sibling state-machine file in this package already establish.
// [RunState.isValidTransition] gives the same from-state/to-state table §9's transition table
// encodes, as a plain function over a plain enum (not a sealed-interface-per-state hierarchy) —
// matching [InstallationState]'s exact precedent for a wire-shape file, not an in-process runtime
// driver. [RunEvent]'s own constructor calls [RunState.isValidTransition] directly, making an
// illegal transition un-constructible — strictly stronger than the JSON Schema counterpart, which
// (having no state-machine-aware keyword) can only validate each event's `toState` against the
// closed 17-value enum independently (see loop-run.schema.json's own adversarial/ fixtures for the
// resulting schema-valid-but-illegal-transition case this Kotlin type does NOT allow).

package dev.fonebrew.contracts.loops

import dev.fonebrew.contracts.common.ArtifactRef
import dev.fonebrew.contracts.common.SideEffectState
import java.time.Instant

private val SEMANTIC_DIGEST_PATTERN = Regex("^sha256:[0-9a-f]{64}\$")
private val ENGINE_VERSION_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+\$")

// =========================================================================================
// RunState — LOOP_ENGINEERING_SPEC_V2.1 §9's 17-state run machine
// =========================================================================================

/**
 * §9's 17 required run states, copied VERBATIM: 9 non-terminal followed by 8 terminal. Do not
 * re-derive or approximate this list; a conflict between this enum and §9's table is this file's
 * error, and the document wins. `WAITING_USER` is non-terminal (Amendment §2 — a change from the
 * base pack's `LoopResult`, where it was terminal); `CANCELLED` is new relative to the base pack.
 *
 * The 8 terminal values are BY-NAME-IDENTICAL to [TerminalRunState] (LoopDefinitionContracts.kt,
 * same package) — that enum exists for definition-time references (a node's own
 * failure-to-terminal-state mapping, §4) and explicitly forward-points to this file for the full
 * machine. Kotlin enums cannot extend one another, so the two are reconciled by name via
 * [toTerminalRunStateOrNull] rather than by inheritance.
 */
enum class RunState {
    CREATED, PREFLIGHT, WAITING_BINDING, WAITING_AUTHORITY, READY, RUNNING, WAITING_USER,
    SUSPENDED, CANCELLING,
    SUCCEEDED_VERIFIED, SUCCEEDED_UNVERIFIED, STOPPED_BUDGET, STOPPED_POLICY, FAILED_SAFE,
    FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN, CANCELLED;

    val isTerminal: Boolean get() = this in TERMINAL

    /**
     * The [TerminalRunState] value sharing this [RunState]'s name, or null if this value is one
     * of the 9 non-terminal states. Total for every terminal [RunState] value — the two enums'
     * terminal subsets are exactly name-identical by construction (both copied verbatim from the
     * same §9 list), so this lookup cannot fail for a terminal input.
     */
    fun toTerminalRunStateOrNull(): TerminalRunState? =
        if (!isTerminal) null else TerminalRunState.entries.first { it.name == this.name }

    companion object {
        val NON_TERMINAL: Set<RunState> = setOf(
            CREATED, PREFLIGHT, WAITING_BINDING, WAITING_AUTHORITY, READY, RUNNING,
            WAITING_USER, SUSPENDED, CANCELLING
        )

        val TERMINAL: Set<RunState> = setOf(
            SUCCEEDED_VERIFIED, SUCCEEDED_UNVERIFIED, STOPPED_BUDGET, STOPPED_POLICY, FAILED_SAFE,
            FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN, CANCELLED
        )

        /**
         * §9's from-state/event/to-state table, reduced to (from, to) reachability pairs — the
         * event text and receipt columns are documentation-level (carried on [RunEvent.description]
         * instead), matching [dev.fonebrew.contracts.loops.InstallationState.isValidTransition]'s
         * identical precedent for a wire-shape file rather than a runtime driver.
         */
        private val TRANSITIONS: Map<RunState, Set<RunState>> = mapOf(
            CREATED to setOf(PREFLIGHT),
            PREFLIGHT to setOf(WAITING_BINDING, WAITING_AUTHORITY, FAILED_SAFE),
            WAITING_BINDING to setOf(WAITING_AUTHORITY, CANCELLING),
            WAITING_AUTHORITY to setOf(READY, CANCELLING),
            READY to setOf(RUNNING),
            RUNNING to setOf(
                WAITING_USER, SUSPENDED, CANCELLING, STOPPED_BUDGET, STOPPED_POLICY,
                SUCCEEDED_VERIFIED, SUCCEEDED_UNVERIFIED, FAILED_SAFE,
                FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN
            ),
            WAITING_USER to setOf(RUNNING, CANCELLING),
            SUSPENDED to setOf(RUNNING, CANCELLING),
            CANCELLING to setOf(CANCELLED, TARGET_STATE_UNKNOWN)
        )

        /** True iff [to] is a state [from] may transition to directly, per §9's table. A terminal state has no outgoing edge. */
        fun isValidTransition(from: RunState, to: RunState): Boolean =
            !from.isTerminal && TRANSITIONS[from]?.contains(to) == true
    }
}

/**
 * A closed, coarse vocabulary this file authors from §9's own per-transition terminal-reason text
 * (one value per distinct terminal-reason phrase in that table's rows) — schema-authoring work
 * explicitly invited by LOOP_RESULT_SHARING_CONTRACT.md §4 ("terminal state and terminal reason
 * category... MUST itself be one of a small closed set of coarse categories, never a free-text
 * reason"). New normative vocabulary, not a re-derivation of an existing registry list; a future
 * LOOP-* rule code MAY be added to loop-validation-rules.v1.json to make individual categories
 * machine-checkable, not done here.
 */
enum class TerminalReasonCategory {
    VALIDATION_FAILURE, CANCELLATION_REQUESTED, CANCELLATION_TARGET_UNCONFIRMED,
    BUDGET_STEPS_EXHAUSTED, BUDGET_WALL_CLOCK_EXHAUSTED, BUDGET_TOKENS_EXHAUSTED,
    BUDGET_COST_EXHAUSTED, BUDGET_TOOL_CALLS_EXHAUSTED, POLICY_BOUNDARY,
    ALL_REQUIRED_VERIFIERS_PASSED, REQUIRED_VERIFIER_INCOMPLETE,
    NODE_FAILURE_NO_SIDE_EFFECTS, NODE_FAILURE_SIDE_EFFECTS_POSSIBLE,
    SIDE_EFFECT_OUTCOME_UNCONFIRMED
}

/** §9: "Terminal states MUST include a terminal reason..." `detail` is local-diagnostic free text — never carried into [dev.fonebrew.contracts.loops.LoopResultShare], which carries only [category]. */
data class TerminalReason(
    val category: TerminalReasonCategory,
    val detail: String
) {
    init { require(detail.isNotBlank()) { "TerminalReason.detail must be non-blank." } }
}

// =========================================================================================
// TargetDecision — which ExecutionTargetType was chosen, and by whom/when
// =========================================================================================

data class TargetDecision(
    val targetId: String,
    val executionTargetType: ExecutionTargetType,
    val decidedAtUtc: Instant,
    val decidedBy: ActorClass,
    val reason: String? = null
) {
    init { require(targetId.isNotBlank()) { "TargetDecision.targetId must be non-blank." } }
}

// =========================================================================================
// RunEvent — one §9 transition table row actually taken during a run
// =========================================================================================

/**
 * One entry in [LoopRun.eventLog]. `fromState` is null only for the initiating event into
 * [RunState.CREATED] (§9's "(none — initiation)" row). The constructor calls
 * [RunState.isValidTransition] directly — an event describing an illegal §9 transition cannot be
 * constructed at all, strictly stronger than loop-run.schema.json's per-entry-only enum check (see
 * that schema's adversarial/02 fixture for the resulting schema-valid-but-illegal-transition case
 * this type does not allow).
 */
data class RunEvent(
    val eventId: String,
    val occurredAtUtc: Instant,
    val toState: RunState,
    val description: String,
    val fromState: RunState? = null,
    val nodeId: String? = null,
    val receiptRef: DurableObjectRef? = null,
    val metadata: Map<String, Any?> = emptyMap()
) {
    init {
        require(eventId.isNotBlank()) { "RunEvent.eventId must be non-blank." }
        require(description.isNotBlank()) { "RunEvent.description must be non-blank." }
        if (fromState != null) {
            require(RunState.isValidTransition(fromState, toState)) {
                "RunEvent: '$fromState' -> '$toState' is not a valid LOOP_ENGINEERING_SPEC_V2.1 §9 transition."
            }
        }
    }
}

// =========================================================================================
// NodeAttempt
// =========================================================================================

enum class NodeAttemptOutcome { SUCCEEDED, FAILED, RETRIED, SKIPPED, TIMED_OUT }

data class NodeAttempt(
    val nodeId: String,
    val attemptNumber: Int,
    val startedAtUtc: Instant,
    val outcome: NodeAttemptOutcome? = null,
    val endedAtUtc: Instant? = null,
    val nodeCategory: NodeCategory? = null,
    val idempotencyKeyUsed: String? = null,
    val receiptRef: DurableObjectRef? = null
) {
    init {
        require(nodeId.isNotBlank()) { "NodeAttempt.nodeId must be non-blank." }
        require(attemptNumber >= 1) { "NodeAttempt.attemptNumber must be >= 1 (got $attemptNumber)." }
    }
}

// =========================================================================================
// UserIntervention — §9, §18: every point a human acted on this run
// =========================================================================================

enum class InterventionKind {
    HUMAN_DECISION_SUPPLIED, WAIT_EVENT_SUPPLIED, BUDGET_EXTENSION_APPROVED,
    AUTHORITY_GRANT_APPROVED, AUTHORITY_GRANT_DECLINED, CANCELLATION_REQUESTED,
    SUSPEND_REQUESTED, RESUME_REQUESTED
}

data class UserIntervention(
    val interventionId: String,
    val occurredAtUtc: Instant,
    val kind: InterventionKind,
    val nodeId: String? = null,
    val details: String? = null
) {
    init { require(interventionId.isNotBlank()) { "UserIntervention.interventionId must be non-blank." } }
}

// =========================================================================================
// RunOutputs — §16
// =========================================================================================

/** §16: primary typed result, artifacts, receipts, human-readable summary, warnings. Full and unredacted. */
data class RunOutputs(
    val primaryResult: Any? = null,
    val artifacts: List<ArtifactRef> = emptyList(),
    val receipts: List<DurableObjectRef> = emptyList(),
    val humanReadableSummary: String? = null,
    val warnings: List<String> = emptyList()
)

// =========================================================================================
// LoopRun — schemas/loops/loop-run.schema.json
// =========================================================================================

/**
 * The COMPLETE local run record (§2.7). Kept ALONGSIDE [LoopRunSummary] per this work package's
 * merged-artifact-list instruction — two distinct objects, not one with an optional-fields toggle.
 * Nothing here is redacted, bucketed, or allowlisted; that happens downstream, first at
 * [LoopRunSummary] and then at [dev.fonebrew.contracts.loops.LoopResultShare]
 * (LoopResultAndLineageContracts.kt). This type MUST NOT itself be uploaded, exported, or shared
 * by any automated path (FB-RAT-MKT-008) — only the explicit Share Result flow may derive a
 * [LoopRunSummary] from it, under explicit user action.
 */
data class LoopRun(
    val schemaVersion: String,
    val runId: String,
    val definitionDigest: String,
    val sourceRef: BaseRevisionRef,
    val bindingProfileRef: DurableObjectRef,
    val runState: RunState,
    val createdAtUtc: Instant,
    val parameters: Map<String, Any?> = emptyMap(),
    val targetDecisions: List<TargetDecision> = emptyList(),
    val eventLog: List<RunEvent> = emptyList(),
    val nodeAttempts: List<NodeAttempt> = emptyList(),
    val userInterventions: List<UserIntervention> = emptyList(),
    val outputs: RunOutputs? = null,
    val terminalReason: TerminalReason? = null,
    val authorityGrants: List<String> = emptyList(),
    val sideEffectState: SideEffectState? = null,
    val updatedAtUtc: Instant? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(runId.isNotBlank()) { "LoopRun.runId must be non-blank (FB-RAT-COM-002)." }
        require(SEMANTIC_DIGEST_PATTERN.matches(definitionDigest)) {
            "LoopRun.definitionDigest must match 'sha256:<64 lowercase hex>' (got '$definitionDigest')."
        }
        if (runState.isTerminal) {
            requireNotNull(terminalReason) { "LoopRun: a terminal runState ('$runState') requires a non-null terminalReason (§9)." }
        } else {
            require(terminalReason == null) { "LoopRun: a non-terminal runState ('$runState') must not carry a terminalReason." }
        }
    }
}

// =========================================================================================
// LoopRunSummary — schemas/loops/loop-run-summary.schema.json
// =========================================================================================

/**
 * §8: computed from coarse [ExecutionTargetType] categories, never host identity. `computed=false`
 * ("no ratio computed") for a zero-eligible-work run — never a default-to-zero percentage. Unlike
 * its JSON Schema counterpart, this constructor DOES verify the three fractions sum to 1.0 when
 * `computed=true` — strictly stronger (see loop-result-share.schema.json's adversarial/02 fixture
 * for the resulting schema-valid-but-nonsensical case this type does not allow).
 */
data class LocalCloudRatio(
    val computed: Boolean,
    val ratioVersion: String,
    val localFraction: Double? = null,
    val remoteFraction: Double? = null,
    val ciFraction: Double? = null
) {
    init {
        require(ratioVersion.isNotBlank()) { "LocalCloudRatio.ratioVersion must be non-blank." }
        if (computed) {
            requireNotNull(localFraction) { "LocalCloudRatio: computed=true requires a non-null localFraction." }
            requireNotNull(remoteFraction) { "LocalCloudRatio: computed=true requires a non-null remoteFraction." }
            requireNotNull(ciFraction) { "LocalCloudRatio: computed=true requires a non-null ciFraction." }
            val sum = localFraction + remoteFraction + ciFraction
            require(kotlin.math.abs(sum - 1.0) < 1e-6) {
                "LocalCloudRatio: localFraction+remoteFraction+ciFraction must sum to 1.0 when computed=true (got $sum)."
            }
        } else {
            require(localFraction == null && remoteFraction == null && ciFraction == null) {
                "LocalCloudRatio: fractions must be null when computed=false (§8 'no ratio computed', never a default-to-zero percentage)."
            }
        }
    }
}

/** §4: node-state counts and stable public node IDs "where permitted" — gated on [publishedLoop], not node-ID stability alone. Raw (unbucketed) counts — this is [LoopRunSummary]'s stage. */
data class NodeStateSummary(
    val publishedLoop: Boolean,
    val totalNodeCount: Int,
    val nodeOutcomeCounts: Map<NodeAttemptOutcome, Int> = emptyMap(),
    val nodeIds: List<String> = emptyList()
) {
    init {
        require(totalNodeCount >= 0) { "NodeStateSummary.totalNodeCount must be >= 0." }
        if (!publishedLoop) {
            require(nodeIds.isEmpty()) {
                "NodeStateSummary.nodeIds MUST be empty unless publishedLoop=true (§4 'only when the loop being reported on has itself been publicly published')."
            }
        }
    }
}

enum class ShareScanState { NOT_YET_SCANNED, SCANNED_CLEAN, SCANNED_FINDINGS_REDACTED }

/**
 * The CLEAR MIDDLE STAGE between a complete [LoopRun] and a finalized, bucketed
 * [dev.fonebrew.contracts.loops.LoopResultShare] — not a third, independent shape. Already stripped
 * of every default-excluded category (FB-RAT-RES-003), still UNBUCKETED (bucketing is a
 * [dev.fonebrew.contracts.loops.LoopResultShare]-step concern, LOOP_RESULT_SHARING_CONTRACT.md §4).
 * Built only from a completed or terminal [LoopRun] (FB-RAT-RES-001) — `terminalState` is
 * therefore [TerminalRunState] (the 8-value closed vocabulary), never a non-terminal value; there
 * is no representable non-terminal [LoopRunSummary]. `verificationClaim` is nullable here (signing
 * happens later, at consent/RECEIPT_SIGNED — LOOP_RESULT_SHARING_CONTRACT.md §3) — contrast
 * [dev.fonebrew.contracts.loops.LoopResultShare.verificationClaim], which is required and non-null.
 */
data class LoopRunSummary(
    val summaryId: String,
    val sourceRunRef: DurableObjectRef,
    val releaseIdentity: ReleaseIdentity,
    val engineVersion: String,
    val terminalState: TerminalRunState,
    val terminalReason: TerminalReason,
    val nodeStateSummary: NodeStateSummary,
    val durationSeconds: Double,
    val retryCount: Int,
    val aggregateCostUsd: Double,
    val aggregateTokens: Long,
    val localCloudRatio: LocalCloudRatio,
    val producedAtUtc: Instant,
    val capabilityCategories: List<CapabilityCategory> = emptyList(),
    val scanState: ShareScanState = ShareScanState.NOT_YET_SCANNED,
    val verificationClaim: VerificationClaim? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(summaryId.isNotBlank()) { "LoopRunSummary.summaryId must be non-blank (FB-RAT-COM-002)." }
        require(ENGINE_VERSION_PATTERN.matches(engineVersion)) { "LoopRunSummary.engineVersion must be SemVer x.y.z (got '$engineVersion')." }
        require(durationSeconds >= 0) { "LoopRunSummary.durationSeconds must be >= 0." }
        require(retryCount >= 0) { "LoopRunSummary.retryCount must be >= 0." }
        require(aggregateCostUsd >= 0) { "LoopRunSummary.aggregateCostUsd must be >= 0." }
        require(aggregateTokens >= 0) { "LoopRunSummary.aggregateTokens must be >= 0." }
    }
}
