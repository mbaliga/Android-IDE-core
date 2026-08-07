// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// LoopMarketplaceContracts.kt — the "loop marketplace" domain's shared wire-shape data classes.
//
// Mirrors, field-for-field, the JSON Schema documents under schemas/loops/*.schema.json:
//   LoopRelease -> loop-release.schema.json
//   LoopListing -> loop-listing.schema.json
//   LoopReview  -> loop-review.schema.json
// If a field appears in one place, it MUST appear in the other, or the two have drifted and
// one of them is wrong. See docs/ratified/loops/LOOP_MARKETPLACE_CONTRACT.md (§1-§16,
// FB-RAT-MKT-001 through FB-RAT-MKT-012) for the citations each field operationalizes, and
// that document's header for the binding hard scope boundary this file's types describe but
// do not themselves implement or operate: "this build-out does NOT build a hosted marketplace
// service, accounts, reviews, or moderation storage. It emits contracts, client code paths,
// and artifacts compatible with [the signed] static/Git registry only." LoopRelease and
// LoopListing are CLIENT-SIDE / STATIC-REGISTRY-COMPATIBLE types (one index entry, one
// presentation record — LOOP_MARKETPLACE_CONTRACT.md §11). LoopReview is the wire shape
// FB-RAT-MKT-006 ratifies for a review record IF AND WHEN reviews are ever turned on via the
// optional bounded backend §2 permits — per §8, "the static/Git registry (§11) carries no
// review mechanism of any kind," so this type's presence here is a contract for a future
// surface, not a claim that this repo persists, serves, or moderates any instance of it today.
// See each type's own KDoc for the citation, not just this file header.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no Android
// imports.
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build
// environment — this file has been written carefully (balanced braces, matched types, no
// typos attempted) but has NOT been compiled. Do not report it as compiling; that is for the
// next session with Gradle available to confirm. This file depends on
// contracts/kotlin/CommonContracts.kt (package dev.aarso.contracts.common) being compiled in
// the same module/source set, for IntegrityRef — reused as-is rather than redefined, matching
// LoopPackageContracts.kt's own precedent for importing IntegrityRef/ProducerRef from
// dev.aarso.contracts.common rather than redeclaring it. This is the OPPOSITE convention from
// this same work package's JSON Schema files, which duplicate IntegrityRef locally as a
// $defs entry in every schema so each one validates standalone — the standalone-file
// requirement is a JSON Schema authoring constraint (no cross-file $ref), not a Kotlin one.
//
// NAME COLLISION — RESOLVED (WP-2 gate, real Gradle compile): the `ValidationFinding` collision
// this comment used to flag between LoopAuthoringContracts.kt and LoopPackageContracts.kt is
// fixed — LoopAuthoringContracts.kt's version (with `remediation`) is canonical, and
// LoopPackageContracts.kt now carries `typealias LoopValidationSeverity = FindingSeverity`
// instead of a second enum, so every pre-existing reference to either name still compiles.
//
// Why no sealed-interface state machine appears in this file: LOOP_MARKETPLACE_CONTRACT.md §10
// already renders the moderation lifecycle (PENDING -> PUBLISHED -> LIMITED/DELISTED -> TAKEDOWN,
// KEY_REVOKED cross-cutting) as an explicit from-state/event/to-state table, and §11 maps that
// table onto the static/Git registry's actual mechanism (PR merge, GitHub issue, index-field
// edit) with NO separate moderation-storage system of its own. This file's job is the WIRE
// SHAPES that table's realization reads and writes (an index entry's revoked flag, a review's
// moderationState), not a re-encoding of the table itself as a Kotlin sealed interface —
// matching LoopPackageContracts.kt's identical reasoning for why IT has no sealed interface for
// its own already-tabled state machines. ReviewModerationState below is a plain enum for the
// same reason CommonContracts.kt's ConformanceTestClass and LoopPackageContracts.kt's
// PackageFileClassification are: it maps 1:1 onto a JSON Schema `enum`.

package dev.aarso.contracts.loops

import dev.aarso.contracts.common.IntegrityRef
import java.time.Instant

private val SEMVER_REGEX = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")

// =========================================================================================
// LoopRelease — schemas/loops/loop-release.schema.json
// =========================================================================================

/**
 * Pointer to a detached loop-package-signature.v1 record (LoopPackageSignature, in
 * LoopPackageContracts.kt), co-hosted with a release's asset on the publisher's own GitHub
 * Release (LOOP_MARKETPLACE_CONTRACT.md §11) — NOT modeled as an archive-relative
 * "signatures/"-prefixed path like [ManifestSignatureRef], because this object lives outside
 * any .floop archive, in the registry index.
 */
data class SignatureAssetRef(
    val signatureAssetUrl: String,
    val sha256: String
) {
    init {
        require(signatureAssetUrl.isNotBlank()) { "SignatureAssetRef.signatureAssetUrl must be non-blank." }
        require(sha256.matches(SHA256_HEX_REGEX)) {
            "SignatureAssetRef.sha256 must be 64 lowercase-hex characters (got '$sha256')."
        }
    }
}

/**
 * loop-release.v1 — one entry of the signed static/Git-backed registry index
 * (LOOP_MARKETPLACE_CONTRACT.md §11: "THE DEFINITION of the marketplace for this build-out").
 * Immutable and content-addressed: the identity tuple {loopId, semanticVersion, packageDigest,
 * publisherKeyFingerprint} MUST NOT change once published (FB-RAT-PKG-006, FB-RAT-MKT-003;
 * LOOP_ENGINEERING_SPEC_V2.1.md §14: "A release is identified by {loopId, semanticVersion,
 * packageDigest, publisherKeyFingerprint}. The digest is the final source of byte-level
 * identity."). This constructor cannot itself enforce non-mutation OVER TIME across two
 * separate instances (that is a registry-repository / PR-review obligation, not a
 * single-object structural property — see loop-release.schema.json's top-level $comment and
 * this schema's adversarial/identity-digest-mutation-attempt fixture for the case this leaves
 * open); the field name "packageDigest" (not "packageContentDigest") matches §14's own literal
 * wording — see that same $comment for the naming-drift note against
 * [dev.aarso.contracts.loops.ReleaseIdentity]'s "packageContentDigest" field in
 * LoopPackageContracts.kt, the same underlying SHA-256 digest under a different sibling-document
 * spelling.
 *
 * @param revoked Trust-status flag, not content (§10's KEY_REVOKED realized at §11 as exactly
 *   this field). MUST NOT be read as retroactively invalidating bytes already installed
 *   locally (§6).
 * @param revokedAtUtc Required non-null exactly when [revoked] is true — see `init`.
 */
data class LoopRelease(
    val loopId: String,
    val semanticVersion: String,
    val packageDigest: IntegrityRef,
    val publisherKeyFingerprint: String,
    val releaseAssetUrl: String,
    val publishedAtUtc: Instant,
    val signatureRef: SignatureAssetRef,
    val releaseChannel: String? = null,
    val revoked: Boolean = false,
    val revokedAtUtc: Instant? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(loopId.isNotBlank()) { "LoopRelease.loopId must be non-blank." }
        require(semanticVersion.matches(SEMVER_REGEX)) {
            "LoopRelease.semanticVersion must be SemVer 2.0.0 (got '$semanticVersion')."
        }
        require(publisherKeyFingerprint.matches(SHA256_HEX_REGEX)) {
            "LoopRelease.publisherKeyFingerprint must be 64 lowercase-hex characters."
        }
        require(releaseAssetUrl.isNotBlank()) { "LoopRelease.releaseAssetUrl must be non-blank." }
        if (revoked) {
            requireNotNull(revokedAtUtc) {
                "LoopRelease.revokedAtUtc is required when revoked is true."
            }
        } else {
            require(revokedAtUtc == null) {
                "LoopRelease.revokedAtUtc must be null when revoked is false."
            }
        }
    }
}

// =========================================================================================
// LoopListing — schemas/loops/loop-listing.schema.json
// =========================================================================================

/**
 * One loop-release.v1 identity tuple a [LoopListing] presents, duplicated locally rather than
 * reusing [LoopRelease] wholesale — a listing only ever needs the identity + discovery-facet
 * subset, not the full release record (releaseAssetUrl/signatureRef/revocation), matching
 * loop-listing.schema.json's own ReferencedRelease $defs entry field-for-field.
 */
data class ReferencedRelease(
    val loopId: String,
    val semanticVersion: String,
    val packageDigest: IntegrityRef,
    val channel: String? = null,
    val recommended: Boolean = false
) {
    init {
        require(loopId.isNotBlank()) { "ReferencedRelease.loopId must be non-blank." }
        require(semanticVersion.matches(SEMVER_REGEX)) {
            "ReferencedRelease.semanticVersion must be SemVer 2.0.0 (got '$semanticVersion')."
        }
    }
}

/**
 * Listing-level compatibility summary — creator-declared discovery metadata, distinct from the
 * fuller per-release compatibility.json LOOP_COMPATIBILITY_CONTRACT.md governs in detail
 * (LOOP_PACKAGE_SPEC.md §10). [declaredAxes] is an open map because the ten-axis vocabulary
 * itself (FB-RAT-CMP-001) is owned by that document and capability-ids.v1.json /
 * model-capability-vocabulary.v1.json, not re-enumerated here as a closed key set.
 *
 * @param verified MUST default false and MUST NOT be true without non-empty [evidenceRefs] —
 *   FB-RAT-CMP-007 (EXPERIMENTAL, cited as already-scoped context, not re-ratified):
 *   "Treat creator-declared device/language compatibility as unverified metadata until backed
 *   by fixtures or explicit shared receipts... A creator claim without such evidence MUST be
 *   labeled declared, never verified."
 */
data class ListingCompatibilityDeclaration(
    val verified: Boolean = false,
    val declaredAxes: Map<String, Any?> = emptyMap(),
    val engineVersionRange: String? = null,
    val evidenceRefs: List<String> = emptyList()
) {
    init {
        if (verified) {
            require(evidenceRefs.isNotEmpty()) {
                "ListingCompatibilityDeclaration.evidenceRefs must be non-empty when verified " +
                    "is true (FB-RAT-CMP-007 — an unevidenced claim MUST be labeled 'declared', " +
                    "never 'verified')."
            }
        }
    }
}

/**
 * loop-listing.v1 — the mutable presentation record for a loop family (FB-RAT-MKT-003:
 * "Marketplace listings are mutable presentation records; referenced releases are immutable
 * content-addressed artifacts."), maintained alongside the signed Git index
 * (LOOP_MARKETPLACE_CONTRACT.md §4, §11). [category] is modeled as an open string — no ratified
 * document in this corpus freezes a category vocabulary (see loop-listing.schema.json's own
 * field description).
 */
data class LoopListing(
    val listingId: String,
    val referencedReleases: List<ReferencedRelease>,
    val displayName: String,
    val description: String,
    val category: String,
    val compatibilityDeclaration: ListingCompatibilityDeclaration,
    val lastUpdatedUtc: Instant,
    val publisherKeyFingerprint: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(listingId.isNotBlank()) { "LoopListing.listingId must be non-blank." }
        require(referencedReleases.isNotEmpty()) {
            "LoopListing.referencedReleases must be non-empty (FB-RAT-MKT-003 — a listing " +
                "presents at least one release)."
        }
        require(displayName.isNotBlank()) { "LoopListing.displayName must be non-blank." }
        require(displayName.length <= 200) { "LoopListing.displayName must be at most 200 characters." }
        require(description.length <= 20000) { "LoopListing.description must be at most 20000 characters." }
        require(category.isNotBlank()) { "LoopListing.category must be non-blank." }
        publisherKeyFingerprint?.let {
            require(it.matches(SHA256_HEX_REGEX)) {
                "LoopListing.publisherKeyFingerprint must be 64 lowercase-hex characters when present."
            }
        }
    }
}

// =========================================================================================
// LoopReview — schemas/loops/loop-review.schema.json
// =========================================================================================

/**
 * The {loopId, semanticVersion, packageDigest} tuple a [LoopReview] is about. Reviews are
 * release-specific (LOOP_MARKETPLACE_CONTRACT.md §8: "declares: release identity (never a
 * listing or a loopId alone — reviews are release-specific, §4)... a review of 1.2.0 says
 * nothing about 1.3.0 unless the service explicitly carries it forward with that fact
 * visible."). Named distinctly from [dev.aarso.contracts.loops.ReleaseIdentity]
 * (LoopPackageContracts.kt, which carries "packageContentDigest") to avoid a same-package name
 * collision and because this type's field is literally "packageDigest" — see [LoopRelease]'s
 * KDoc for the naming-drift note.
 */
data class ReleaseIdentityRef(
    val loopId: String,
    val semanticVersion: String,
    val packageDigest: IntegrityRef
) {
    init {
        require(loopId.isNotBlank()) { "ReleaseIdentityRef.loopId must be non-blank." }
        require(semanticVersion.matches(SEMVER_REGEX)) {
            "ReleaseIdentityRef.semanticVersion must be SemVer 2.0.0 (got '$semanticVersion')."
        }
    }
}

/**
 * FB-RAT-MKT-006's five separate rating dimensions, each independently scored — never
 * collapsed into one aggregate. NOTE: no ratified document in this corpus fixes a numeric
 * scale for these ratings; 1-5 (inclusive) is this file's own authoring choice (matching
 * loop-review.schema.json's identical, identically-flagged choice), not a ratified decision.
 * Flagged for the Amendments-phase agent if the owner wants a different scale fixed instead.
 */
data class ReviewRatings(
    val usefulness: Int,
    val reliability: Int,
    val understandability: Int,
    val efficiency: Int,
    val safety: Int
) {
    init {
        for ((name, value) in listOf(
            "usefulness" to usefulness,
            "reliability" to reliability,
            "understandability" to understandability,
            "efficiency" to efficiency,
            "safety" to safety
        )) {
            require(value in 1..5) { "ReviewRatings.$name must be in 1..5 (got $value)." }
        }
    }
}

/**
 * Pseudonymous review-author reference. NOT a real-world verified identity — this corpus has
 * no accounts system for the marketplace's static/Git registry (LOOP_MARKETPLACE_CONTRACT.md
 * §11); reviews specifically are not even built by this pass (§8, "Not built in this pass").
 * [pseudonymousId] is whatever pseudonymous identity the submission-by-pull-request process
 * establishes (e.g. a GitHub handle on the PR that carried this review), documented as a
 * constraint a future optional backend MUST honor if it ever assigns review authorship, not an
 * assumption that a real user-account system exists.
 */
data class ReviewerRef(
    val pseudonymousId: String,
    val submissionRef: String? = null
) {
    init {
        require(pseudonymousId.isNotBlank()) { "ReviewerRef.pseudonymousId must be non-blank." }
    }
}

/**
 * Subset of LOOP_MARKETPLACE_CONTRACT.md §10's six-state moderation table applicable to a
 * review specifically. KEY_REVOKED is deliberately excluded — §10 states it "applies to a
 * publisher's key, not to one release's editorial state," and a review is neither a release nor
 * a key; a revoked publisher key does not, on its own, change any single review's
 * moderationState. Transitions follow the same from-state/event/to-state table §10 defines for
 * marketplace content generally; not re-derived here (see this file's header for why no sealed
 * interface encodes that table).
 */
enum class ReviewModerationState { PENDING, PUBLISHED, LIMITED, DELISTED, TAKEDOWN }

/**
 * loop-review.v1 — FB-RAT-MKT-006: "Reviews separate usefulness, reliability,
 * understandability, efficiency, and safety, plus a worked-for-me signal and free text." Per
 * LOOP_MARKETPLACE_CONTRACT.md §8's own framing, this type is the CONTRACT a review record MUST
 * have IF AND WHEN reviews are ever turned on via the optional bounded backend §2 permits — the
 * static/Git registry (§11) "carries no review mechanism of any kind." Constructing an instance
 * of this type does not, by itself, imply this repo persists or serves it anywhere.
 *
 * @param freeText MAY be empty (a rating-only review with no prose) — the field itself is
 *   required so a decoder never has to distinguish "no free text field" from "empty free text".
 * @param verifiedInstallRef Optional untyped reference string (not an inline object) — the full
 *   shape of an install-verification receipt is owned by LOOP_IMPORT_ACTIVATION_CONTRACT.md,
 *   not redefined here.
 * @param verifiedRunRef Optional untyped reference string — the full shared-result-receipt
 *   shape is owned by LOOP_RESULT_SHARING_CONTRACT.md, not redefined here.
 */
data class LoopReview(
    val reviewId: String,
    val releaseIdentity: ReleaseIdentityRef,
    val ratings: ReviewRatings,
    val workedForMe: Boolean,
    val freeText: String,
    val reviewerRef: ReviewerRef,
    val createdAtUtc: Instant,
    val moderationState: ReviewModerationState = ReviewModerationState.PENDING,
    val verifiedInstallRef: String? = null,
    val verifiedRunRef: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(reviewId.isNotBlank()) { "LoopReview.reviewId must be non-blank." }
    }
}
