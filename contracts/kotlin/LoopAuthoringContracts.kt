// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// LoopAuthoringContracts.kt — the "loops" domain's mutable-authoring-state wire-shape data
// classes: LoopDraft (identity, revision, graph, presentation, validation state, test
// workspace, source surface, local history), the draft-revision/concurrency contract
// (DraftMutationRequest / DraftMutationConflictReport), and LoopSemanticDiff — the shared
// object model behind both an AI-assisted structural edit proposal and a fork/upstream
// lineage-comparison diff.
//
// Mirrors, field-for-field, schemas/loops/loop-draft.schema.json and
// schemas/loops/loop-semantic-diff.schema.json. Normative grounding: LOOP_ENGINEERING_SPEC_V2.1
// .md §2.1 (LoopDraft), §15 (draft revision + concurrency contract), §6 (AI-assisted
// structural-edit proposal fields); LOOP_FORK_LINEAGE_CONTRACT.md §8 (FB-RAT-LIN-004, nine
// separately-reported diff categories) and §9 (FB-RAT-LIN-005, the independently-surfaced
// authority/verification blocking review group); LOOP_PHONE_AUTHORING_SPEC.md §8 (the phone's
// own BASE_REVISION/PROPOSED/APPLIED/DISCARDED UI state machine, layered on top of the
// LoopSemanticDiff object modeled here — not re-modeled by this file) and §13 (DRAFT_CLEAN/
// DIRTY_JOURNALED persistence, the UI-facing counterpart to this file's LoopDraft.localHistory).
// Cross-references, does not redefine: semantic-digest.v1.json (semantic digest string format
// and the presentation/layout exclusion list), loop-validation-rules.v1.json (finding codes),
// capability-ids.v1.json (authorityLadder, capability IDs).
//
// This file is in the SAME package as LoopDefinitionContracts.kt (dev.aarso.contracts.loops)
// and reaches into two of its types by ordinary same-package visibility, no import needed:
// AuthorityRung (PerCapabilityAuthorityDelta.before/after) and LicenseRef
// (LicenseChange.previousLicenseRef/newLicenseRef reuse LoopDefinitionContracts.kt's LicenseRef
// as-is rather than redefining it — the same "reuse, don't redefine" discipline
// contracts/kotlin/ExecutionContracts.kt applies to dev.aarso.contracts.common.ArtifactRef).
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant only, plus one FIRST-PARTY cross-domain import
// (dev.aarso.contracts.common.ArtifactRef, for LoopDraft.testWorkspaceRef) — the same kind of
// import contracts/kotlin/ExecutionContracts.kt and contracts/kotlin/AuthorityContracts.kt's
// sibling domains already make for a shape "genuinely shared with the common envelope", per the
// WP-1L task brief. No kotlinx-serialization, no kotlinx-datetime, no Android imports, no
// kotlinx-coroutines Flow (no provider/streaming interface is requested for this domain).
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build environment —
// this file has been written carefully (balanced braces, matched types, no typos attempted) but
// has NOT been compiled. Do not report it as compiling; that is for the next session with
// Gradle available to confirm. This file depends on contracts/kotlin/CommonContracts.kt
// (ArtifactRef) and contracts/kotlin/LoopDefinitionContracts.kt (AuthorityRung, LicenseRef)
// being compiled in the same module/source set.
//
// Why sealed interfaces appear below (DraftGraph, BaseRevisionRef) where the JSON Schema
// counterparts instead used `if`/`then` (DraftGraph) or `anyOf` (BaseRevisionRef): same
// rationale as LoopDefinitionContracts.kt's header note and contracts/kotlin/AuthorityContracts
// .kt's AuthorityDecision — a discriminated union expressed as a sealed interface is a
// compile-time-checked guarantee no runtime `if`/`then`/`anyOf` conditional can match. Unlike
// [Compensation] in LoopDefinitionContracts.kt, [DraftMutationConflictReport] below is a case
// where Kotlin is STRICTLY MORE capable than the JSON Schema counterpart, not merely a stronger
// encoding of the same check: `init{}` CAN compare two sibling `Long` properties for inequality
// (`actualRevision != expectedRevision`), something JSON Schema draft 2020-12 has no keyword for
// at all (see loop-draft.schema.json's own `$comment` on `DraftMutationConflictReport` for the
// schema-side acknowledgement of that gap) — flagged inline at that constructor, not silently
// exploited without comment.

package dev.aarso.contracts.loops

import dev.aarso.contracts.common.ArtifactRef
import java.time.Instant

private val SEMANTIC_DIGEST_PATTERN = Regex("^sha256:[0-9a-f]{64}\$")
private val FINDING_CODE_PATTERN = Regex("^LOOP-[A-Z]+-[0-9]{3}\$")
private val CAPABILITY_ID_PATTERN = Regex("^fb\\.[a-z0-9_]+(\\.[a-z0-9_]+)+\$")

// =========================================================================================
// Shared small vocabularies
// =========================================================================================

/** §15's exact actor-class vocabulary — the same one LOOP_FORK_LINEAGE_CONTRACT.md §3 reuses for lineage events rather than reinventing (a lineage event and a draft revision are the same kind of fact). */
enum class ActorClass { USER, DISTILLER, AI_PROPOSAL, IMPORT_MIGRATION, UPSTREAM_APPLY }

enum class ValidationStatus { NOT_VALIDATED, VALIDATING, VALID, INVALID }

/** loop-validation-rules.v1.json's exact three severityLevels. Deliberately distinct from dev.aarso.contracts.common.ErrorSeverity (which has a fourth, CRITICAL, band) — different closed vocabularies for different domains, not conflated. */
enum class FindingSeverity { ERROR, WARNING, INFO }

/**
 * A validation finding referencing a stable code from loop-validation-rules.v1.json's namespace
 * architecture (LOOP_ENGINEERING_SPEC_V2.1 §19), e.g. "LOOP-GRAPH-002". Not cross-checked against
 * the live registry by this constructor — a code matching [FINDING_CODE_PATTERN] but absent from
 * the registry is a validator-time error, not a construction-time one. Reused as-is by both
 * [ValidationState] (draft/diff validity) below — there is only one finding shape in this file.
 */
data class ValidationFinding(
    val code: String,
    val severity: FindingSeverity,
    val objectType: String,
    val message: String,
    val remediation: String? = null
) {
    init {
        require(FINDING_CODE_PATTERN.matches(code)) { "ValidationFinding.code must match 'LOOP-<NAMESPACE>-<3 digits>' (got '$code')." }
        require(objectType.isNotBlank()) { "ValidationFinding.objectType must be non-blank." }
        require(message.isNotBlank()) { "ValidationFinding.message must be non-blank." }
    }
}

/**
 * A validator MUST NOT report `status = VALID` while `findings` contains an ERROR-severity
 * entry — this constructor does NOT enforce that cross-field agreement (mirrors
 * loop-draft.schema.json's own acknowledged gap); see
 * fixtures/loops/loop-draft/adversarial/ for the resulting schema-valid-but-dangerous case this
 * leaves open at the wire-schema layer, which applies equally to this Kotlin type.
 */
data class ValidationState(
    val status: ValidationStatus,
    val findings: List<ValidationFinding> = emptyList(),
    val lastValidatedAtUtc: Instant? = null,
    val rulesetVersion: String? = null
)

// =========================================================================================
// DraftGraph — LoopDraft.graph, "embeds or references a loop-definition.v2-shaped structure"
// =========================================================================================

sealed interface DraftGraph {
    /**
     * The graph is authored inline in this draft. `definition` is deliberately a loosely typed
     * `Map<String, Any?>`, NOT a [LoopDefinition] — a LoopDraft is explicitly "not trusted or
     * publishable until packaged" (§2.1) and is expected to be structurally incomplete for long
     * stretches of an authoring session. [ValidationState] is the honest signal of whether
     * `definition` currently would construct a valid [LoopDefinition]; a build step (§20 step 2)
     * is what actually re-validates it in full before a package may be produced.
     */
    data class Embedded(val definition: Map<String, Any?>) : DraftGraph

    /**
     * This draft tracks an already-packaged/installed LoopDefinition it was forked from (§11
     * "imported packages remain editable on the phone through a forked draft"; FB-RAT-IMP-006
     * "editing an installed release creates a forked local draft rather than mutating it") — the
     * draft has not yet diverged into its own [Embedded] graph.
     */
    data class Referenced(val loopId: String, val semanticVersion: String, val packageDigest: String) : DraftGraph {
        init {
            require(loopId.isNotBlank()) { "DraftGraph.Referenced.loopId must be non-blank." }
            require(semanticVersion.isNotBlank()) { "DraftGraph.Referenced.semanticVersion must be non-blank." }
            require(SEMANTIC_DIGEST_PATTERN.matches(packageDigest)) { "DraftGraph.Referenced.packageDigest must match 'sha256:<64 lowercase hex>' (got '$packageDigest')." }
        }
    }
}

// =========================================================================================
// Semantic operations — shared shape vocabulary
// =========================================================================================

enum class OperationType { ADD, REMOVE, REPLACE, REORDER, MODIFY_PROPERTY }

/**
 * The nine FB-RAT-LIN-004 categories (LOOP_FORK_LINEAGE_CONTRACT.md §8) a diff MUST report
 * separately. Also used as [SemanticOperation.targetType]'s closed vocabulary — license changes
 * are deliberately NOT a member (they are reported only through [LicenseChange], per §8: "MUST
 * additionally be surfaced as its own flag alongside these nine categories, rather than folded
 * into Documentation").
 */
enum class SemanticDiffCategory { DOCUMENTATION, SCHEMA, NODE, EDGE, CAPABILITY, AUTHORITY, BUDGET, TEST, COMPATIBILITY }

/** [SemanticOperationSummary.targetType]'s wider, nullable vocabulary — a draft-history record MAY also target PRESENTATION (layout-only), or leave targetType unset for a definition-wide change (e.g. objective text). Distinct from [SemanticDiffCategory], which is the narrower, non-nullable, license-excluding set a [LoopSemanticDiff] MUST use. */
enum class DraftOperationTargetType { NODE, EDGE, GATEWAY, SCHEMA, CAPABILITY, BUDGET, TEST, DOCUMENTATION, COMPATIBILITY, LICENSE, PRESENTATION }

/** One operation within a [DraftRevisionRecord.operationList] or [DraftMutationRequest.operations]. */
data class SemanticOperationSummary(
    val opId: String,
    val opType: OperationType,
    val description: String,
    val targetType: DraftOperationTargetType? = null,
    val targetId: String? = null
) {
    init {
        require(opId.isNotBlank()) { "SemanticOperationSummary.opId must be non-blank." }
        require(description.isNotBlank()) { "SemanticOperationSummary.description must be non-blank." }
    }
}

data class AuthorityVerificationDelta(
    val authorityWidened: Boolean,
    val verificationWeakened: Boolean,
    val details: String? = null
)

// =========================================================================================
// DraftRevisionRecord — §15
// =========================================================================================

/**
 * §15's exact revision-record shape: actor class, timestamp, base semantic digest, resulting
 * semantic digest, operation list, validation state, authority/verification delta.
 */
data class DraftRevisionRecord(
    val revisionNumber: Long,
    val actorClass: ActorClass,
    val timestampUtc: Instant,
    val baseSemanticDigest: String,
    val resultingSemanticDigest: String,
    val operationList: List<SemanticOperationSummary>,
    val validationState: ValidationState,
    val authorityVerificationDelta: AuthorityVerificationDelta,
    val idempotencyKey: String? = null
) {
    init {
        require(revisionNumber >= 0) { "DraftRevisionRecord.revisionNumber must be >= 0." }
        require(SEMANTIC_DIGEST_PATTERN.matches(baseSemanticDigest)) { "DraftRevisionRecord.baseSemanticDigest must match 'sha256:<64 lowercase hex>' (got '$baseSemanticDigest')." }
        require(SEMANTIC_DIGEST_PATTERN.matches(resultingSemanticDigest)) { "DraftRevisionRecord.resultingSemanticDigest must match 'sha256:<64 lowercase hex>' (got '$resultingSemanticDigest')." }
        require(operationList.isNotEmpty()) { "DraftRevisionRecord.operationList must be non-empty — a revision without operations is meaningless." }
    }
}

// =========================================================================================
// LoopDraft — §2.1
// =========================================================================================

enum class SourceSurface { PHONE, WEB }

/**
 * Mutable authoring state with stable draft identity, revision, graph, presentation/layout
 * metadata, validation state, test workspace reference, source surface, and local history
 * (§2.1). A draft is not trusted or publishable until packaged (§20).
 */
data class LoopDraft(
    val draftId: String,
    val revision: Long,
    val graph: DraftGraph,
    val validationState: ValidationState,
    val sourceSurface: SourceSurface,
    /** Draft-level presentation/layout metadata, named after and excluded by the same key names semantic-digest.v1.json's exclusionListV1 drops from a packaged LoopDefinition. */
    val presentation: Map<String, Any?> = emptyMap(),
    /** Reused as-is from dev.aarso.contracts.common.ArtifactRef (not redefined here) — a test/fixture workspace bundle. Null before any simulation/replay has produced a workspace. */
    val testWorkspaceRef: ArtifactRef? = null,
    /** §15: append-only local history of revisions. Never reused after deletion — [draftId] identity, not enforced by this constructor (a store-level obligation over time, not a single-instance structural property). */
    val localHistory: List<DraftRevisionRecord> = emptyList(),
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(draftId.isNotBlank()) { "LoopDraft.draftId must be non-blank (§14, §2.1)." }
        require(revision >= 0) { "LoopDraft.revision must be >= 0." }
    }
}

// =========================================================================================
// Draft-revision/concurrency contract — §15
// =========================================================================================

/**
 * §15: "Every draft mutation is applied against an expected revision." A conforming store either
 * applies this and appends a [DraftRevisionRecord] with `revisionNumber = expectedRevision + 1`,
 * or rejects it with a [DraftMutationConflictReport] — there is no third outcome.
 */
data class DraftMutationRequest(
    val draftId: String,
    val expectedRevision: Long,
    val operations: List<SemanticOperationSummary>,
    val actorClass: ActorClass,
    val idempotencyKey: String? = null
) {
    init {
        require(draftId.isNotBlank()) { "DraftMutationRequest.draftId must be non-blank." }
        require(expectedRevision >= 0) { "DraftMutationRequest.expectedRevision must be >= 0." }
        require(operations.isNotEmpty()) { "DraftMutationRequest.operations must be non-empty." }
    }
}

/**
 * §15: "A stale mutation is rejected with a semantic conflict report; it is not last-write-wins."
 * Unlike the JSON Schema counterpart (which cannot compare two sibling integer properties for
 * inequality — see loop-draft.schema.json's own `$comment` on this type), THIS constructor DOES
 * enforce `actualRevision != expectedRevision` — a report claiming a conflict where the two
 * revisions are equal is not constructible.
 */
data class DraftMutationConflictReport(
    val draftId: String,
    val expectedRevision: Long,
    val actualRevision: Long,
    val semanticConflictDescription: String,
    /** The revision(s) applied between expectedRevision and actualRevision — what the stale mutation did not see. */
    val conflictingRevisions: List<DraftRevisionRecord> = emptyList(),
    val resolutionOptions: List<String> = emptyList()
) {
    init {
        require(draftId.isNotBlank()) { "DraftMutationConflictReport.draftId must be non-blank." }
        require(expectedRevision >= 0) { "DraftMutationConflictReport.expectedRevision must be >= 0." }
        require(actualRevision >= 0) { "DraftMutationConflictReport.actualRevision must be >= 0." }
        require(actualRevision != expectedRevision) {
            "DraftMutationConflictReport.actualRevision (${actualRevision}) must differ from expectedRevision (${expectedRevision}) — a conflict report where the two are equal is not a real conflict."
        }
        require(semanticConflictDescription.isNotBlank()) { "DraftMutationConflictReport.semanticConflictDescription must be non-blank." }
    }
}

// =========================================================================================
// LoopSemanticDiff — LOOP_ENGINEERING_SPEC_V2.1 §6 / LOOP_FORK_LINEAGE_CONTRACT.md §8-§9
// =========================================================================================

/** AI_PROPOSAL: Distiller/AI-assisted structural edit (§6, FB-RAT-PHN-006) awaiting user approval. LINEAGE_COMPARISON: fork-vs-upstream-parent comparison (LOOP_FORK_LINEAGE_CONTRACT.md §10). */
enum class DiffSourceKind { AI_PROPOSAL, LINEAGE_COMPARISON }

/** The "base revision" a [LoopSemanticDiff] was computed against (§6) — either a [LoopDraft] revision or an immutable release identity tuple (LOOP_FORK_LINEAGE_CONTRACT.md §10 "comparison MUST occur only against immutable snapshots"). */
sealed interface BaseRevisionRef {
    data class DraftBase(val draftId: String, val draftRevisionNumber: Long) : BaseRevisionRef {
        init {
            require(draftId.isNotBlank()) { "BaseRevisionRef.DraftBase.draftId must be non-blank." }
            require(draftRevisionNumber >= 0) { "BaseRevisionRef.DraftBase.draftRevisionNumber must be >= 0." }
        }
    }

    data class ReleaseBase(val loopId: String, val semanticVersion: String, val packageDigest: String) : BaseRevisionRef {
        init {
            require(loopId.isNotBlank()) { "BaseRevisionRef.ReleaseBase.loopId must be non-blank." }
            require(semanticVersion.isNotBlank()) { "BaseRevisionRef.ReleaseBase.semanticVersion must be non-blank." }
            require(SEMANTIC_DIGEST_PATTERN.matches(packageDigest)) { "BaseRevisionRef.ReleaseBase.packageDigest must match 'sha256:<64 lowercase hex>' (got '$packageDigest')." }
        }
    }
}

/** One operation within [LoopSemanticDiff.semanticOperations] — `targetType` is the non-nullable [SemanticDiffCategory] (contrast [SemanticOperationSummary], the draft-history sibling shape, which allows null/PRESENTATION/LICENSE). */
data class SemanticOperation(
    val opId: String,
    val opType: OperationType,
    val targetType: SemanticDiffCategory,
    val description: String,
    val targetId: String? = null,
    val beforeValue: Any? = null,
    val afterValue: Any? = null
) {
    init {
        require(opId.isNotBlank()) { "SemanticOperation.opId must be non-blank." }
        require(description.isNotBlank()) { "SemanticOperation.description must be non-blank." }
    }
}

/**
 * FB-RAT-LIN-004: "Lineage comparison reports node, edge, schema, capability, authority, budget,
 * test, documentation, and compatibility changes separately" — LOOP_FORK_LINEAGE_CONTRACT.md §8's
 * exact nine categories, structurally closed BY CONSTRUCTION: unlike the JSON Schema counterpart
 * (which needs `additionalProperties: false` to reject a runtime-added tenth key), a Kotlin data
 * class with nine named `List<String>` properties makes adding a tenth bucket, or flattening two
 * categories together, a COMPILE ERROR for any code constructing this type — strictly stronger
 * than the schema's runtime check. Each list holds [SemanticOperation.opId] values from the
 * owning [LoopSemanticDiff.semanticOperations] — this type does not itself cross-check that a
 * listed opId's own `targetType` actually matches the category list it appears in (an opaque ID
 * list, no join); see fixtures/loops/loop-semantic-diff/adversarial/ for the resulting
 * schema-valid-but-miscategorized case this leaves open at the wire-schema layer, which applies
 * equally here.
 */
data class CategorizedFindings(
    val documentation: List<String> = emptyList(),
    val schema: List<String> = emptyList(),
    val node: List<String> = emptyList(),
    val edge: List<String> = emptyList(),
    val capability: List<String> = emptyList(),
    val authority: List<String> = emptyList(),
    val budget: List<String> = emptyList(),
    val test: List<String> = emptyList(),
    val compatibility: List<String> = emptyList()
)

enum class LicenseChangeFinding { COMPATIBLE, INCOMPATIBLE, NOT_EVALUATED }

/**
 * LOOP_FORK_LINEAGE_CONTRACT.md §8: "A change to the package's declared license MUST additionally
 * be surfaced as its own flag alongside these nine categories, rather than folded into
 * Documentation." Reuses [LicenseRef] from LoopDefinitionContracts.kt as-is (same package, no
 * import needed) rather than redefining it.
 */
data class LicenseChange(
    val changed: Boolean,
    val previousLicenseRef: LicenseRef? = null,
    val newLicenseRef: LicenseRef? = null,
    val compatibilityFinding: LicenseChangeFinding? = null
) {
    init {
        if (changed) {
            requireNotNull(newLicenseRef) { "LicenseChange: changed=true requires a non-null newLicenseRef." }
            requireNotNull(compatibilityFinding) { "LicenseChange: changed=true requires a non-null compatibilityFinding (feeds LOOP-LINEAGE-002)." }
        }
    }
}

enum class AuthorityDeltaDirection { WIDENED, NARROWED, UNCHANGED, ADDED, REMOVED }

data class PerCapabilityAuthorityDelta(
    val capabilityId: String,
    val direction: AuthorityDeltaDirection,
    /** Null when this capability is newly requested (direction=ADDED). */
    val before: AuthorityRung? = null,
    /** Null when this capability is being dropped (direction=REMOVED). */
    val after: AuthorityRung? = null
) {
    init { require(CAPABILITY_ID_PATTERN.matches(capabilityId)) { "PerCapabilityAuthorityDelta.capabilityId must be a reverse-DNS fb.* ID (got '$capabilityId')." } }
}

/**
 * LOOP_FORK_LINEAGE_CONTRACT.md §9 (FB-RAT-LIN-005): four diff conditions pulled OUT of the
 * ordinary nine-category [CategorizedFindings] into a separate, always-visible, blocking review
 * group. `acknowledgedByUser = true` requires a non-null `acknowledgedAtUtc` (enforced below).
 * This type does not itself cross-check that every [CategorizedFindings.authority]/`.node`/
 * `.edge`/`.schema` entry which structurally qualifies as one of these four conditions is
 * actually reflected here — see fixtures/loops/loop-semantic-diff/adversarial/ for the resulting
 * schema-valid-but-dangerous case this leaves open at the wire-schema layer, which applies
 * equally here (detecting it requires interpreting [SemanticOperation.beforeValue]/`afterValue`
 * against capability-ids.v1.json's authorityLadder ordering — semantic interpretation, not a
 * constructible-type-level invariant).
 */
data class BlockingReviewGroup(
    val authorityWidening: Boolean,
    val verificationWeakening: Boolean,
    val destructivePathIntroduced: Boolean,
    val schemaBreakage: Boolean,
    val acknowledgedByUser: Boolean,
    val acknowledgedAtUtc: Instant? = null
) {
    init {
        if (acknowledgedByUser) {
            requireNotNull(acknowledgedAtUtc) { "BlockingReviewGroup: acknowledgedByUser=true requires a non-null acknowledgedAtUtc." }
        }
    }
}

/** §6 "authority-diff summary", reported independently of ordinary graph changes per FB-RAT-LIN-005 and FB-RAT-PHN-006 ("Authority-widening changes MUST be visually separated from ordinary graph edits, not interleaved with them in one undifferentiated list"). */
data class AuthorityDiffSummary(
    val blockingReviewGroup: BlockingReviewGroup,
    val perCapability: List<PerCapabilityAuthorityDelta> = emptyList()
)

/**
 * A categorized semantic diff between a base revision and a proposed/comparison target — nine
 * separate finding categories ([CategorizedFindings], FB-RAT-LIN-004) plus an independently
 * surfaced [licenseChange] flag and an independently surfaced [authorityDiffSummary] blocking
 * review group (FB-RAT-LIN-005). Produced either by an AI-assisted edit proposal
 * (`sourceKind = AI_PROPOSAL`) or a fork/upstream lineage comparison
 * (`sourceKind = LINEAGE_COMPARISON`) — both are the same reported shape per §6's proposal
 * fields and LOOP_FORK_LINEAGE_CONTRACT.md §8/§9's diff report. `validationResult` reuses
 * [ValidationState] as-is (same shape as [LoopDraft.validationState] — there is only one
 * validation-report shape in this file).
 */
data class LoopSemanticDiff(
    val diffId: String,
    val sourceKind: DiffSourceKind,
    val generatedAtUtc: Instant,
    val baseRevision: BaseRevisionRef,
    val categorizedFindings: CategorizedFindings,
    val licenseChange: LicenseChange,
    val behaviorExplanation: String,
    val validationResult: ValidationState,
    val authorityDiffSummary: AuthorityDiffSummary,
    val semanticOperations: List<SemanticOperation> = emptyList(),
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(diffId.isNotBlank()) { "LoopSemanticDiff.diffId must be non-blank (FB-RAT-COM-002)." }
        require(behaviorExplanation.isNotBlank()) { "LoopSemanticDiff.behaviorExplanation must be non-blank (§6 'behavior explanation')." }
    }
}
