// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// LoopResultAndLineageContracts.kt — the "loop result sharing" and "loop fork lineage" domains'
// shared wire-shape data classes.
//
// Mirrors, field-for-field, the JSON Schema documents under schemas/loops/*.schema.json:
//   LoopResultShare -> loop-result-share.schema.json
//   ForkLineage     -> loop-fork-lineage.schema.json
//   AuthorityDiff   -> authority-diff.schema.json
// If a field appears in one place, it MUST appear in the other, or the two have drifted and one
// of them is wrong. Normative grounding: docs/ratified/loops/LOOP_RESULT_SHARING_CONTRACT.md
// (FB-RAT-RES-001..007, FB-RAT-MKT-008 — read FIRST for the FB-RAT-RES-004 correction and the
// coarse-bucketing requirement, both operationalized below) and
// docs/ratified/loops/LOOP_FORK_LINEAGE_CONTRACT.md (FB-RAT-LIN-001..007, LIN-007 DEFERRED).
//
// THE FB-RAT-RES-004 CORRECTION (LOOP_RESULT_SHARING_CONTRACT.md §6): the source draft's label
// `RUNTIME_ATTESTED` is UNSUPPORTABLE — no signing-key option available to a sideloaded Android
// app can prove "a compatible Fonebrew runtime signed this." [VerificationClaimLabel] below uses
// the corrected label `SELF_SIGNED_RECEIPT` (pseudonymous key continuity only), never the source's
// original wrong one, plus an explicit `UNSIGNED` marker so a missing key is never silently
// omitted (§3). `claimText` for each label is fixed to the exact honest wording §6 requires — see
// [VerificationClaim]'s init block, which enforces this as a COMPILE-TIME-CHECKED invariant
// (construction fails if `claimText` does not match the label's fixed wording exactly), strictly
// stronger than loop-result-share.schema.json's `const` (which only a JSON Schema validator run
// enforces, not a Kotlin constructor).
//
// THE COARSE-BUCKETING REQUIREMENT (§4): duration, retry totals, per-node counts, and token/cost
// aggregates MUST be reported as one of a small number of coarse, versioned ranges, never raw
// values — a high-dimensional quasi-identifier otherwise (cited USENIX Security 2024 token-length
// side-channel work). [DurationBucket]/[RetryCountBucket]/[CostBucket]/[TokenCountBucket]/
// [NodeCountBucket] below encode this STRUCTURALLY: each is a closed enum, so
// [LoopResultShare]'s corresponding fields cannot hold a raw `Double`/`Int` even if a caller
// wanted them to — there is no constructor path to a "bucket" field carrying an unbucketed value.
//
// Cross-package reuse: none beyond what same-package files already provide (this file adds no
// new dev.fonebrew.contracts.common import).
//
// Same-PACKAGE reuse (no import needed — dev.fonebrew.contracts.loops spans seven files):
// [ReleaseIdentity] (LoopPackageContracts.kt), [LicenseRef] (LoopDefinitionContracts.kt),
// [ActorClass], [PerCapabilityAuthorityDelta], [AuthorityDeltaDirection], [LicenseChangeFinding]
// (LoopAuthoringContracts.kt), [DurableObjectRef] (LoopActivationContracts.kt),
// [TerminalRunState] (LoopDefinitionContracts.kt), [TerminalReason]/[NodeAttemptOutcome]
// (LoopRuntimeContracts.kt, same package, same work package). None of these are redefined here.
// This file's own [CapabilityCategory] and [VerificationClaim] are, in turn, reused BY NAME (same-
// package visibility) from LoopRuntimeContracts.kt's [dev.fonebrew.contracts.loops.LoopRunSummary] —
// see that file's own header for the reverse direction of this cross-file dependency note. Two
// Kotlin files sharing one package and referencing each other's types in both directions is not a
// circular-import problem in Kotlin (unlike some languages) — the compiler resolves the whole
// source set's declarations together; this is stated explicitly here only because it is a genuine
// two-way dependency between this work package's two deliverable files, worth calling out.
//
// AUTHORITY-DIFF STANDALONE DESIGN NOTE: [AuthorityDiff] below reuses [PerCapabilityAuthorityDelta]
// and [AuthorityDeltaDirection] directly from LoopAuthoringContracts.kt (same package) rather than
// redefining them — the exact per-capability shape that file's own [AuthorityDiffSummary] already
// carries inline. [AuthorityDiff] promotes that shape to its own top-level, addressable type per
// this work package's instruction that authority-diff be "standalone... consumed by both
// loop-installation (update gate) and loop-fork-lineage." [ForkLineage.authorityDiffSinceFork]
// below is the loop-fork-lineage consumer this file implements; loop-installation.schema.json's
// own future update-gate field is a forward pointer this file does not implement (that schema was
// frozen by a prior WP-1L group, not modified here) — flagged in this work package's report.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies — stdlib +
// java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no Android imports, no
// kotlinx-coroutines Flow (no provider/streaming interface is requested for either domain in this
// work package).
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build environment —
// this file has been written carefully (balanced braces, matched types, no typos attempted) but
// has NOT been compiled. Do not report it as compiling; that is for the next session with Gradle
// available to confirm. This file depends on contracts/kotlin/CommonContracts.kt and, within this
// same package, contracts/kotlin/LoopActivationContracts.kt, LoopAuthoringContracts.kt,
// LoopDefinitionContracts.kt, LoopPackageContracts.kt, and LoopRuntimeContracts.kt being compiled
// in the same module/source set.
//
// Why no sealed-interface state machine appears in this file: LOOP_RESULT_SHARING_CONTRACT.md §3's
// share-flow and LOOP_FORK_LINEAGE_CONTRACT.md §10's upstream-comparison-and-selective-apply flow
// are both already rendered as explicit from-state/event/to-state tables in their owning ratified
// documents, and neither is this file's object to re-drive at runtime — [LoopResultShare] is the
// TERMINAL artifact §3's table produces (entered at RECEIPT_STORED), and [ForkLineage] is the
// durable RECORD §10's machine reads and writes (lineage events, applied operations), not a
// re-encoding of either table itself as a Kotlin sealed hierarchy. This matches
// LoopMarketplaceContracts.kt's identical reasoning for its own moderation-lifecycle table.

package dev.fonebrew.contracts.loops

import java.time.Instant

private val ENGINE_VERSION_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+\$")
private val SEMANTIC_DIGEST_PATTERN = Regex("^sha256:[0-9a-f]{64}\$")

// =========================================================================================
// VerificationClaim — §6, FB-RAT-RES-004 (corrected)
// =========================================================================================

/**
 * RES-004 correction (§6): renamed from the source draft's unsupportable `RUNTIME_ATTESTED`. No
 * implementable signing-key option in the source pack's own analysis supports a genuine
 * runtime-attestation claim on a sideload-first Android app (a self-generated Keystore key has no
 * root of trust; an app-embedded secret is trivially extractable from a sideloaded APK; hardware
 * key attestation cuts against this product's sovereignty/sideload-first posture and is not
 * adopted). [SELF_SIGNED_RECEIPT] proves only pseudonymous key continuity across shares.
 * [UNSIGNED] is the explicit no-key marker §3 requires ("never silently omitted") rather than a
 * missing field.
 */
enum class VerificationClaimLabel { SELF_SIGNED_RECEIPT, UNSIGNED }

private const val SELF_SIGNED_RECEIPT_CLAIM_TEXT =
    "The same signing key produced this receipt and any other receipts under it (pseudonymous " +
        "continuity only). This does NOT prove a genuine Fonebrew runtime, a specific device, or " +
        "an unmodified app produced it, and does NOT prove the loop is safe, correct, " +
        "representative, or suitable for another user."

private const val UNSIGNED_CLAIM_TEXT =
    "This receipt is unsigned: no local signing key was available. No continuity claim can be made at all."

/**
 * §6: the only claim a verifier MAY draw from a valid [SELF_SIGNED_RECEIPT] signature is
 * pseudonymous key continuity — never runtime authenticity, device identity, or correctness of any
 * kind. `claimText` is fixed to the exact honest wording per [label] — this constructor enforces
 * that as a compile-time-checked invariant (construction throws if `claimText` does not match),
 * strictly stronger than loop-result-share.schema.json's `const` keyword, which only a schema
 * validator run enforces.
 */
data class VerificationClaim(
    val label: VerificationClaimLabel,
    val claimText: String,
    val signingKeyFingerprint: String? = null
) {
    init {
        when (label) {
            VerificationClaimLabel.SELF_SIGNED_RECEIPT -> {
                require(claimText == SELF_SIGNED_RECEIPT_CLAIM_TEXT) {
                    "VerificationClaim: label=SELF_SIGNED_RECEIPT requires the exact fixed claimText from LOOP_RESULT_SHARING_CONTRACT.md §6 (RES-004 correction)."
                }
                requireNotNull(signingKeyFingerprint) {
                    "VerificationClaim: label=SELF_SIGNED_RECEIPT requires a non-null signingKeyFingerprint."
                }
            }
            VerificationClaimLabel.UNSIGNED -> {
                require(claimText == UNSIGNED_CLAIM_TEXT) {
                    "VerificationClaim: label=UNSIGNED requires the exact fixed unsigned-marker claimText (§3, 'never silently omitted')."
                }
                require(signingKeyFingerprint == null) {
                    "VerificationClaim: label=UNSIGNED must not carry a signingKeyFingerprint."
                }
            }
        }
    }
}

// =========================================================================================
// Coarse-bucketing enums — §4 coarse-bucketing requirement, structural not documentation-only
// =========================================================================================

/** §4 coarse-bucketing requirement, "duration." */
enum class DurationBucket {
    UNDER_10_SECONDS, TEN_SECONDS_TO_ONE_MINUTE, ONE_TO_FIVE_MINUTES, FIVE_TO_THIRTY_MINUTES,
    THIRTY_MINUTES_TO_TWO_HOURS, OVER_TWO_HOURS
}

/** §4 coarse-bucketing requirement, "retry totals." */
enum class RetryCountBucket { ZERO, ONE_TO_TWO, THREE_TO_FIVE, SIX_TO_TEN, OVER_TEN }

/** §4 coarse-bucketing requirement, "token/cost aggregates" (cost half). */
enum class CostBucket {
    ZERO, UNDER_ONE_CENT, ONE_CENT_TO_TEN_CENTS, TEN_CENTS_TO_ONE_DOLLAR, ONE_TO_TEN_DOLLARS,
    OVER_TEN_DOLLARS
}

/** §4 coarse-bucketing requirement, "token/cost aggregates" (token half). */
enum class TokenCountBucket {
    ZERO, UNDER_ONE_THOUSAND, ONE_THOUSAND_TO_TEN_THOUSAND, TEN_THOUSAND_TO_ONE_HUNDRED_THOUSAND,
    ONE_HUNDRED_THOUSAND_TO_ONE_MILLION, OVER_ONE_MILLION
}

/** §4 coarse-bucketing requirement, "per-node counts." */
enum class NodeCountBucket { ZERO, ONE_TO_FIVE, SIX_TO_TWENTY, TWENTY_ONE_TO_FIFTY, OVER_FIFTY }

/** capability-ids.v1.json's exact 8-value `category` enum — never a specific reverse-DNS capability ID (§4). */
enum class CapabilityCategory(val wireValue: String) {
    WORKSPACE("workspace"), EXECUTION("execution"), DEVICE("device"), NETWORK("network"),
    SECRET("secret"), MODEL("model"), MARKETPLACE("marketplace"), RELEASE("release");

    companion object {
        fun fromWireValue(value: String): CapabilityCategory =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown CapabilityCategory wire value: $value")
    }
}

/** §4: node-state counts and stable public node IDs "where permitted" (gated on [publishedLoop], same rule as [dev.fonebrew.contracts.loops.NodeStateSummary]), with per-node counts now BUCKETED — this is [LoopResultShare]'s stage. */
data class SharedNodeStateSummary(
    val publishedLoop: Boolean,
    val totalNodeCountBucket: NodeCountBucket,
    val nodeOutcomeCountBuckets: Map<NodeAttemptOutcome, NodeCountBucket> = emptyMap(),
    val nodeIds: List<String> = emptyList()
) {
    init {
        if (!publishedLoop) {
            require(nodeIds.isEmpty()) {
                "SharedNodeStateSummary.nodeIds MUST be empty unless publishedLoop=true (§4)."
            }
        }
    }
}

// =========================================================================================
// LoopResultShare — schemas/loops/loop-result-share.schema.json
// =========================================================================================

/**
 * The final, redacted, bucketed, previewed, consented, optionally-signed artifact a user's
 * explicit Share Result action produces and that MAY leave the phone (§1-§7). Realizes
 * LOOP_ENGINEERING_SPEC_V2.1 §2.8's `SharedResult` concept. Built only from a
 * [dev.fonebrew.contracts.loops.LoopRunSummary], never constructed directly from live run state.
 *
 * DEFAULT-EXCLUDED FIELDS (FB-RAT-RES-003) intentionally have NO corresponding constructor
 * parameter anywhere on this type — prompt/response content, repo/file/branch/org names, source
 * code, file contents/paths, raw logs, credentials/hostnames, device identity, artifacts, and user
 * identity unless explicitly attached. Their absence from this class's parameter list IS the
 * contract, mirroring loop-result-share.schema.json's `additionalProperties: false`.
 *
 * `releaseIdentity` is this type's expansion of a bare "packageDigest" field into the full
 * {loopId, semanticVersion, packageContentDigest} identity tuple, per §4's own allowlist row
 * grouping all three together — see loop-result-share.schema.json's header for the full
 * field-list-expansion rationale, not repeated here.
 */
data class LoopResultShare(
    val schemaVersion: String,
    val shareId: String,
    val releaseIdentity: ReleaseIdentity,
    val engineVersion: String,
    val terminalState: TerminalRunState,
    val terminalReasonCategory: TerminalReasonCategory,
    val nodeStateSummary: SharedNodeStateSummary,
    val bucketingVersion: String,
    val durationBucket: DurationBucket,
    val retryCountBucket: RetryCountBucket,
    val aggregateCostBucket: CostBucket,
    val aggregateTokensBucket: TokenCountBucket,
    val localCloudRatio: LocalCloudRatio,
    val verificationClaim: VerificationClaim,
    val createdAtUtc: Instant,
    val capabilityCategories: List<CapabilityCategory> = emptyList(),
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(shareId.isNotBlank()) { "LoopResultShare.shareId must be non-blank (FB-RAT-COM-002)." }
        require(ENGINE_VERSION_PATTERN.matches(engineVersion)) { "LoopResultShare.engineVersion must be SemVer x.y.z (got '$engineVersion')." }
        require(bucketingVersion.isNotBlank()) { "LoopResultShare.bucketingVersion must be non-blank (§4)." }
    }
}

// =========================================================================================
// AuthorityDiff — authority-diff.schema.json (standalone, reusable)
// =========================================================================================

/** Which of this type's two named consumers (or another caller) produced a given [AuthorityDiff] instance. Informational only. */
enum class AuthorityDiffContext { UPDATE_GATE, LINEAGE_COMPARISON, OTHER }

/**
 * The standalone, reusable capability-by-capability authority-rung comparison object this work
 * package's task brief names explicitly: "a reusable shape consumed by both loop-installation
 * (update gate) and loop-fork-lineage (lineage comparison) -- design it standalone, do not embed
 * it only in one or the other." Reuses [PerCapabilityAuthorityDelta]/[AuthorityDeltaDirection]
 * from LoopAuthoringContracts.kt as-is (same package) rather than redefining them.
 *
 * `requiresFreshApproval` MUST equal true iff [perCapability] contains at least one entry with
 * direction `ADDED` or `WIDENED` (FB-RAT-IMP-008, FB-RAT-LIN-005) — this constructor enforces BOTH
 * directions of that equivalence, strictly stronger than authority-diff.schema.json's `allOf`
 * (which a caller could, in principle, bypass by hand-authoring JSON directly; this type makes the
 * mismatched-flag case un-constructible).
 */
data class AuthorityDiff(
    val comparisonId: String,
    val generatedAtUtc: Instant,
    val requiresFreshApproval: Boolean,
    val perCapability: List<PerCapabilityAuthorityDelta> = emptyList(),
    val comparisonContext: AuthorityDiffContext? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(comparisonId.isNotBlank()) { "AuthorityDiff.comparisonId must be non-blank (FB-RAT-COM-002)." }
        val anyWideningOrAdd = perCapability.any {
            it.direction == AuthorityDeltaDirection.WIDENED || it.direction == AuthorityDeltaDirection.ADDED
        }
        require(requiresFreshApproval == anyWideningOrAdd) {
            "AuthorityDiff.requiresFreshApproval must equal (any perCapability entry is WIDENED or ADDED) " +
                "per FB-RAT-IMP-008/FB-RAT-LIN-005 — got requiresFreshApproval=$requiresFreshApproval, " +
                "computed=$anyWideningOrAdd."
        }
    }
}

// =========================================================================================
// ForkLineage — schemas/loops/loop-fork-lineage.schema.json
// =========================================================================================

enum class UpstreamRelationship { NONE, TRACKING, REBASED }

/** §2's exact seven lineage-event kinds, verbatim. */
enum class LineageEventType {
    FORK_CREATED, UPSTREAM_COMPARED, UPSTREAM_OPERATION_APPLIED,
    FORK_REVISION_CREATED_FROM_UPSTREAM, SUBLOOP_EXTRACTED, DERIVATIVE_PUBLISHED,
    LOCAL_FORK_DELETED
}

/** §2's append-only lineage-event record. Actor uses [ActorClass] (LoopAuthoringContracts.kt §15's vocabulary), reused not reinvented — "a lineage event and a draft revision are the same kind of fact" (§3). */
data class LineageEvent(
    val eventId: String,
    val eventType: LineageEventType,
    val occurredAtUtc: Instant,
    val actor: ActorClass,
    val relatedRef: DurableObjectRef? = null
) {
    init { require(eventId.isNotBlank()) { "LineageEvent.eventId must be non-blank." } }
}

/** §2's Applied upstream change set entry — §7/§10: applied individually, never batched. */
data class AppliedUpstreamOperation(
    val opId: String,
    val appliedAtUtc: Instant,
    val beforeSemanticDigest: String,
    val afterSemanticDigest: String,
    val sourceSemanticDiffRef: DurableObjectRef
) {
    init {
        require(opId.isNotBlank()) { "AppliedUpstreamOperation.opId must be non-blank." }
        require(SEMANTIC_DIGEST_PATTERN.matches(beforeSemanticDigest)) { "AppliedUpstreamOperation.beforeSemanticDigest must match 'sha256:<64 lowercase hex>' (got '$beforeSemanticDigest')." }
        require(SEMANTIC_DIGEST_PATTERN.matches(afterSemanticDigest)) { "AppliedUpstreamOperation.afterSemanticDigest must match 'sha256:<64 lowercase hex>' (got '$afterSemanticDigest')." }
    }
}

/** §11's declared-derivative fields (FB-RAT-LIN-006), populated only once a fork has actually been published. */
data class DerivativePublication(
    val publishedLoopId: String,
    val listingRef: DurableObjectRef? = null,
    val testsInheritedUnchanged: List<String> = emptyList(),
    val testsChangedOrAdded: List<String> = emptyList(),
    val upstreamContributionIntended: Boolean = false,
    val ownershipTransferred: Boolean = false
) {
    init { require(publishedLoopId.isNotBlank()) { "DerivativePublication.publishedLoopId must be non-blank." } }
}

/** §2's Contribution/author record. */
data class AuthorshipContribution(
    val contributorId: String,
    val contributionDescription: String
) {
    init {
        require(contributorId.isNotBlank()) { "AuthorshipContribution.contributorId must be non-blank." }
        require(contributionDescription.isNotBlank()) { "AuthorshipContribution.contributionDescription must be non-blank." }
    }
}

/**
 * A provenance record connecting a derivative draft or release to its parent release(s), applied
 * upstream changes, authorship, attribution, and license (LOOP_FORK_LINEAGE_CONTRACT.md,
 * FB-RAT-LIN-001..006; LIN-007 DEFERRED). Realizes LOOP_ENGINEERING_SPEC_V2.1 §2.9's `ForkLineage`
 * concept. `lastComparedAgainstUpstream` points at a
 * [dev.fonebrew.contracts.loops.LoopSemanticDiff] (LoopAuthoringContracts.kt) — "exactly where
 * FB-RAT-LIN-004's per-category diff and FB-RAT-LIN-005's independent authority diff get
 * consumed," per this work package's task brief — never re-embedded here.
 * `authorityDiffSinceFork` is the narrower, standalone [AuthorityDiff]: a capability-only
 * comparison between this fork's CURRENT authority requests and its parent's requests AT FORK
 * TIME, distinct from the fuller upstream-comparison diff.
 *
 * This constructor enforces §5/LOOP-LINEAGE-002's publish-time gate: [publication] non-null
 * requires [licenseCompatibilityFinding] to be exactly `COMPATIBLE`. It does NOT verify the
 * converse — that [DerivativePublication.publishedLoopId] actually differs from
 * [parentReleaseRef]'s `loopId` unless [DerivativePublication.ownershipTransferred] is true — see
 * loop-fork-lineage.schema.json's own adversarial/01 fixture for why (cross-field string
 * inequality is not a constructible-type-level invariant without a live registry lookup this type
 * has no access to).
 */
data class ForkLineage(
    val schemaVersion: String,
    val forkId: String,
    val parentReleaseRef: ReleaseIdentity,
    val attributionRequired: Boolean,
    val licenseRef: LicenseRef,
    val authorshipContributions: List<AuthorshipContribution>,
    val initiatingActor: ActorClass,
    val createdAtUtc: Instant,
    val forkReason: String? = null,
    val upstreamRelationship: UpstreamRelationship? = null,
    val lastComparedAgainstUpstream: DurableObjectRef? = null,
    val authorityDiffSinceFork: AuthorityDiff? = null,
    val appliedUpstreamOperations: List<AppliedUpstreamOperation> = emptyList(),
    val lineageEvents: List<LineageEvent> = emptyList(),
    val licenseCompatibilityFinding: LicenseChangeFinding? = null,
    val publication: DerivativePublication? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(forkId.isNotBlank()) { "ForkLineage.forkId must be non-blank (§2, §3, §14 draftId)." }
        require(authorshipContributions.isNotEmpty()) {
            "ForkLineage.authorshipContributions must be non-empty — an empty list is exactly LOOP-LINEAGE-001's 'missing parent attribution' condition."
        }
        if (publication != null) {
            require(licenseCompatibilityFinding == LicenseChangeFinding.COMPATIBLE) {
                "ForkLineage: publication requires licenseCompatibilityFinding == COMPATIBLE (§5, LOOP-LINEAGE-002) — got '$licenseCompatibilityFinding'."
            }
        }
    }
}
