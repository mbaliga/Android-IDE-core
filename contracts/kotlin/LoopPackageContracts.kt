// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// LoopPackageContracts.kt — the "loop package" domain's shared wire-shape data classes.
//
// Mirrors, field-for-field, the JSON Schema documents under schemas/loops/*.schema.json
// (NOT schemas/loops/registries/, which are WP-1L-G0's frozen registries, not schemas this
// work package owns):
//   LoopPackageManifest    -> loop-package-manifest.schema.json
//   TransferEnvelope       -> transfer-envelope.schema.json
//   LoopPackageSignature   -> loop-package-signature.schema.json
//   PackageFixtureRecord   -> package-fixture-record.schema.json
//   LoopBuildReceipt       -> loop-build-receipt.schema.json
// If a field appears in one place, it MUST appear in the other, or the two have drifted and
// one of them is wrong. See docs/ratified/loops/LOOP_PACKAGE_SPEC.md (§1-§14, FB-RAT-PKG-001
// through FB-RAT-PKG-010, FB-RAT-WEB-005) for the citations each field operationalizes, and
// schemas/loops/registries/{floop-container-format,semantic-digest,canonicalization,
// loop-validation-rules,capability-ids}.v1.json for the frozen concepts this domain builds
// on and never redefines — a conflict between this file and one of those registries is this
// file's error, and the registry wins.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no
// Android imports.
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build
// environment — this file has been written carefully (balanced braces, matched types, no
// typos attempted) but has NOT been compiled. Do not report it as compiling; that is for
// the next session with Gradle available to confirm. This file depends on
// contracts/kotlin/CommonContracts.kt (package dev.fonebrew.contracts.common) being compiled
// in the same module/source set, for IntegrityRef and ProducerRef — reused as-is rather
// than redefined, per the WP-1L task brief's "first-party import, not a third-party
// dependency" allowance (matches ExecutionContracts.kt's own precedent of importing
// ArtifactRef/CapabilityManifest/ProducerRef from dev.fonebrew.contracts.common). Note this is
// the OPPOSITE convention from this same work package's JSON Schema files, which duplicate
// IntegrityRef locally as a $defs entry in every schema so each one validates standalone —
// the standalone-file requirement is a JSON Schema authoring constraint (no cross-file
// $ref), not a Kotlin one; Kotlin already has a real, compiled shared module to import from.
//
// Why no sealed-interface state machine appears in this file: this domain's real state
// machines — the ten-step build pipeline, the signature verification/revocation lifecycle,
// the browser storage/persistence lifecycle — are already ratified as explicit
// from-state/event/to-state tables in docs/ratified/loops/LOOP_PACKAGE_SPEC.md §5/§11 and
// LOOP_WEB_STUDIO_SPEC.md §8/§9/§13. This file's job is the WIRE SHAPES those tables
// produce and consume (a build receipt, a signature record), not a re-encoding of the
// tables themselves as Kotlin sealed interfaces — matching CommonContracts.kt's own
// reasoning for why IT has no sealed interface, and unlike ExecutionContracts.kt/
// AuthorityContracts.kt, which DO own real state (a running handle, an authority decision)
// and correctly use sealed interfaces for it. BuildStepName/BuildStepOutcome below are
// plain enums for the same reason CommonContracts.kt's ConformanceTestClass is: they map
// 1:1 onto a JSON Schema `enum`. The fixed ten-step ORDER is enforced by LoopBuildReceipt's
// own init block (exactly 10 entries, position N's stepNumber == N+1), not by a
// sealed-interface transition graph.

package dev.fonebrew.contracts.loops

import dev.fonebrew.contracts.common.IntegrityRef
import dev.fonebrew.contracts.common.ProducerRef
import java.time.Instant

private val SEMVER_REGEX = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")

// =========================================================================================
// LoopPackageManifest — schemas/loops/loop-package-manifest.schema.json
// =========================================================================================

/** Stable human/version identity of a loop family (LOOP_PACKAGE_SPEC.md §2 — MUST NOT be conflated with packageContentDigest, the exact content identity). */
data class LoopPackageIdentity(
    val loopId: String,
    val semanticVersion: String
) {
    init {
        require(loopId.isNotBlank()) { "LoopPackageIdentity.loopId must be non-blank." }
        require(semanticVersion.matches(SEMVER_REGEX)) {
            "LoopPackageIdentity.semanticVersion must be SemVer 2.0.0 (got '$semanticVersion')."
        }
    }
}

/**
 * One capability request drawn from capability-ids.v1.json's reverse-DNS namespace
 * (fb.<category>.<action>). Membership in the frozen registry and the authorityRung it
 * implies are validated by the loop-validation-rules.v1 engine (LOOP-CAP-001), not by this
 * type — [CAPABILITY_ID_PATTERN] checks syntactic shape only, deliberately, so an additive
 * registry bump never requires a change here.
 */
data class CapabilityRequest(
    val capabilityId: String,
    val justification: String? = null,
    val egressAllowlist: List<String> = emptyList()
) {
    init {
        require(capabilityId.matches(CAPABILITY_ID_PATTERN)) {
            "CapabilityRequest.capabilityId must match fb.<category>.<action> (got '$capabilityId')."
        }
        if (capabilityId == "fb.network.egress") {
            require(egressAllowlist.isNotEmpty()) {
                "CapabilityRequest.egressAllowlist must be non-empty when capabilityId is " +
                    "'fb.network.egress' (LOOP-PKG-007 — a granted External capability is never " +
                    "a general network grant)."
            }
        }
    }

    companion object {
        val CAPABILITY_ID_PATTERN: Regex = Regex("^fb\\.[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$")
    }
}

/**
 * Build-time compatibility summary embedded in manifest.json (floop-container-format.v1.json
 * requiredTopLevelLayout: "capability requests, compatibility declaration"). NOT a substitute
 * for the fuller, separate compatibility.json a public release additionally requires
 * (LOOP_PACKAGE_SPEC.md §10) — LOOP_COMPATIBILITY_CONTRACT.md governs that file's full
 * multi-axis shape, not redefined here.
 */
data class CompatibilityDeclaration(
    val engineVersionRange: String,
    val notes: String? = null
) {
    init {
        require(engineVersionRange.isNotBlank()) {
            "CompatibilityDeclaration.engineVersionRange must be non-blank (SemVer range syntax, e.g. '>=1.0.0 <2.0.0')."
        }
    }
}

/**
 * floop-container-format.v1.json packageInventory.classifications, verbatim — deliberately
 * lowerCamelCase (not this file's otherwise-UPPER_SNAKE convention) because these three
 * identifiers ARE the frozen registry's own wire vocabulary; renaming them to
 * SEMANTIC_FILE/DERIVED_FILE/SIGNATURE_FILE plus a wireValue mapping (as DigestAlgorithm
 * does for "SHA-256", which is not a legal Kotlin identifier) would add an indirection this
 * three-value, already-legal-identifier case does not need.
 */
enum class PackageFileClassification { semanticFile, derivedFile, signatureFile }

/**
 * Per-file inventory entry (floop-container-format.v1.json packageInventory) — field names
 * match the registry's own vocabulary exactly: "path, byteLength, sha256, and classification".
 */
data class PackageInventoryEntry(
    val path: String,
    val byteLength: Long,
    val sha256: String,
    val classification: PackageFileClassification
) {
    init {
        require(path.isNotBlank()) { "PackageInventoryEntry.path must be non-blank." }
        require(path.toByteArray(Charsets.UTF_8).size <= 255) {
            "PackageInventoryEntry.path must be at most 255 bytes (floop-container-format.v1.json pathRules.maxPathBytes)."
        }
        require(byteLength >= 0) { "PackageInventoryEntry.byteLength must be >= 0." }
        require(sha256.matches(SHA256_HEX_REGEX)) {
            "PackageInventoryEntry.sha256 must be 64 lowercase-hex characters (got '$sha256')."
        }
    }
}

/** Pointer to the detached loop-package-signature.v1 record under signatures/. */
data class ManifestSignatureRef(
    val path: String,
    val sha256: String
) {
    init {
        require(path.startsWith("signatures/")) {
            "ManifestSignatureRef.path must be under 'signatures/' (floop-container-format.v1.json)."
        }
        require(sha256.matches(SHA256_HEX_REGEX)) { "ManifestSignatureRef.sha256 must be 64 lowercase-hex characters." }
    }
}

/** Lightweight embedded provenance summary — not a substitute for the fuller, separate provenance.json a public release also requires (LOOP_PACKAGE_SPEC.md §10). */
data class ManifestProvenance(
    val producer: ProducerRef? = null,
    val sourceDraftRevision: String? = null,
    val createdAtUtc: Instant? = null
)

/**
 * manifest.json — the .floop container's REQUIRED manifest (floop-container-format.v1.json
 * requiredTopLevelLayout). That registry defines the archive bytes, path rules,
 * packageInventory classifications, and packageContentDigest algorithm this type's fields
 * must agree with byte-for-byte.
 */
data class LoopPackageManifest(
    val schemaVersion: String,
    val packageIdentity: LoopPackageIdentity,
    val capabilityRequests: List<CapabilityRequest>,
    val compatibilityDeclaration: CompatibilityDeclaration,
    val packageInventory: List<PackageInventoryEntry>,
    val packageContentDigest: IntegrityRef,
    val signatureRef: ManifestSignatureRef? = null,
    val license: String? = null,
    val provenance: ManifestProvenance? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "LoopPackageManifest.schemaVersion must be major version 1 (got '$schemaVersion') — " +
                "an unrecognized major is the 'unknown major schema version in manifest.json' " +
                "adversarial class floop-container-format.v1.json's pathRules names explicitly; " +
                "a reader MUST reject it before doing anything else with this document."
        }
        require(packageInventory.isNotEmpty()) { "LoopPackageManifest.packageInventory must be non-empty." }
        require(
            packageInventory.any {
                it.path == "definition.json" && it.classification == PackageFileClassification.semanticFile
            }
        ) {
            "LoopPackageManifest.packageInventory MUST contain a 'definition.json' entry classified " +
                "semanticFile — definition.json is container-REQUIRED (floop-container-format.v1.json)."
        }
    }
}

// =========================================================================================
// TransferEnvelope — schemas/loops/transfer-envelope.schema.json
// =========================================================================================

/** LOOP_DUAL_SURFACE_ARCHITECTURE.md §7's canonical LoopPackage boundary crossings. */
enum class TransferChannel { FILE, SHARE_SHEET, HTTPS_URL, QR, GIT }

/**
 * The user-initiated envelope that precedes any package transfer (LOOP_ENGINEERING_SPEC_V2.1.md
 * §21): "Transfer moves inert bytes. It does not grant authority, resolve secrets, or start
 * execution... Every transfer is represented by a user-initiated envelope containing expected
 * media type and digest." Typically carried as the `payload` of a
 * `ContractEnvelope<TransferEnvelope>` (dev.fonebrew.contracts.common), inheriting
 * objectId/createdAtUtc/producer from that outer envelope.
 *
 * @param initiatedByUser MUST-level invariant (FB-RAT-IMP-002): defaults to, and is validated
 *   to equal, `true`. There is no representable false state — a producer that cannot
 *   truthfully assert user initiation MUST NOT construct a TransferEnvelope at all. Kept as an
 *   explicit constructor parameter (rather than a hardcoded `val` with no parameter) so this
 *   class's shape still mirrors the JSON Schema's `required` field list exactly, with the
 *   `require()` below doing the work `const: true` does on the wire.
 */
data class TransferEnvelope(
    val transferChannel: TransferChannel,
    val expectedMediaType: String,
    val expectedDigest: IntegrityRef,
    val sourceDescription: String,
    val initiatedByUser: Boolean = true,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(initiatedByUser) {
            "TransferEnvelope.initiatedByUser must be true (LOOP_ENGINEERING_SPEC_V2.1.md §21, " +
                "FB-RAT-IMP-002) — a transfer that was not user-initiated MUST NOT be represented " +
                "by this type at all, not represented with this flag set to false."
        }
        require(expectedMediaType.matches(Regex("^[\\w.+-]+/[\\w.+-]+$"))) {
            "TransferEnvelope.expectedMediaType must look like an IANA media type (got '$expectedMediaType')."
        }
        require(sourceDescription.isNotBlank()) { "TransferEnvelope.sourceDescription must be non-blank." }
    }
}

// =========================================================================================
// LoopPackageSignature — schemas/loops/loop-package-signature.schema.json
// =========================================================================================

/**
 * The {loopId, semanticVersion, packageContentDigest} tuple LOOP_ENGINEERING_SPEC_V2.1.md §14
 * defines a release's identity as ("A release is identified by {loopId, semanticVersion,
 * packageDigest, publisherKeyFingerprint}" — publisherKeyFingerprint is carried as
 * [LoopPackageSignature]'s own sibling field rather than duplicated inside this tuple, since
 * LOOP_PACKAGE_SPEC.md §11 binds it to the signature independently).
 */
data class ReleaseIdentity(
    val loopId: String,
    val semanticVersion: String,
    val packageContentDigest: IntegrityRef
) {
    init {
        require(loopId.isNotBlank()) { "ReleaseIdentity.loopId must be non-blank." }
        require(semanticVersion.matches(SEMVER_REGEX)) {
            "ReleaseIdentity.semanticVersion must be SemVer 2.0.0 (got '$semanticVersion')."
        }
    }
}

/**
 * LOOP_WEB_STUDIO_SPEC.md §13's phone-vs-browser signing tiers. PHONE_KEYSTORE: signed
 * on-device with an Android Keystore/StrongBox-backed Ed25519 key — the only tier eligible
 * for marketplace publication. SOFT_KEY: a non-extractable browser IndexedDB CryptoKey,
 * scoped to unlisted, account-free drafts only (FB-RAT-WEB-009, EXPERIMENTAL) — MUST NOT be
 * presented as equivalent to a phone-signed signature.
 */
enum class KeyProvenance { PHONE_KEYSTORE, SOFT_KEY }

/**
 * loop-package-signature.v1 — an Ed25519 detached signature over a LoopPackage release
 * identity (LOOP_PACKAGE_SPEC.md §11, FB-RAT-PKG-005): "A release signature MUST bind
 * exactly: loopId, semanticVersion, packageContentDigest, canonicalizationVersion, the
 * publisher's key fingerprint, and the release channel."
 *
 * @param signedDigest The raw Ed25519 signature bytes (64 bytes), base64-encoded — NOT
 *   itself a digest despite the field name (inherited from this work package's task brief);
 *   it is the signature computed over the fb-loop-canon-1-canonicalized bytes of
 *   [releaseIdentity] plus [canonicalizationVersion], [publisherKeyFingerprint], and
 *   [releaseChannel].
 * @param releaseChannel The distribution channel this signature is bound to. Modeled as a
 *   plain String, not an enum — the closed channel vocabulary is owned by
 *   LOOP_MARKETPLACE_CONTRACT.md's listing/release model, not re-derived or invented here.
 * @param softKeyScope Structural guard for the SOFT_KEY tier: required to equal
 *   "UNLISTED_DRAFT_ONLY" whenever [keyProvenance] is SOFT_KEY, and required null when
 *   PHONE_KEYSTORE — see the `init` block.
 */
data class LoopPackageSignature(
    val publisherKeyFingerprint: String,
    val signedDigest: String,
    val releaseIdentity: ReleaseIdentity,
    val releaseChannel: String,
    val signedAtUtc: Instant,
    val keyProvenance: KeyProvenance,
    val softKeyScope: String? = null,
    val signatureAlgorithm: String = "Ed25519",
    val canonicalizationVersion: String = "fb-loop-canon-1",
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(signatureAlgorithm == "Ed25519") {
            "LoopPackageSignature.signatureAlgorithm must equal 'Ed25519' — the only signature " +
                "suite this corpus defines (LOOP_PACKAGE_SPEC.md §11, 10_DUAL_VALIDATION_ADDENDUM.md §D)."
        }
        require(canonicalizationVersion == "fb-loop-canon-1") {
            "LoopPackageSignature.canonicalizationVersion must equal 'fb-loop-canon-1' " +
                "(canonicalization.v1.json)."
        }
        require(publisherKeyFingerprint.matches(SHA256_HEX_REGEX)) {
            "LoopPackageSignature.publisherKeyFingerprint must be 64 lowercase-hex characters."
        }
        require(signedDigest.isNotBlank()) { "LoopPackageSignature.signedDigest must be non-blank." }
        require(releaseChannel.isNotBlank()) { "LoopPackageSignature.releaseChannel must be non-blank." }
        when (keyProvenance) {
            KeyProvenance.SOFT_KEY -> require(softKeyScope == "UNLISTED_DRAFT_ONLY") {
                "LoopPackageSignature.softKeyScope must be 'UNLISTED_DRAFT_ONLY' when keyProvenance " +
                    "is SOFT_KEY (LOOP_WEB_STUDIO_SPEC.md §13 — a soft key is scoped to unlisted, " +
                    "account-free drafts only, and MUST NOT be accepted for direct marketplace " +
                    "publication: REJECTED_INSUFFICIENT_SIGNATURE)."
            }
            KeyProvenance.PHONE_KEYSTORE -> require(softKeyScope == null) {
                "LoopPackageSignature.softKeyScope must be null when keyProvenance is PHONE_KEYSTORE."
            }
        }
    }
}

// =========================================================================================
// PackageFixtureRecord — schemas/loops/package-fixture-record.schema.json
// =========================================================================================

/** The recorded human-decision outcome a fixture replays for a human-decision node. */
data class RecordedDecision(
    val decisionValue: Any?,
    val decidedByFixtureAuthor: String? = null
)

/**
 * A single recorded simulation/replay/fault-injection fixture under a package's fixtures/
 * directory, keyed on (nodeId, iterationIndex, callIndex) per LOOP_WEB_STUDIO_SPEC.md §6's
 * fixture-keying correction (10_DUAL_VALIDATION_ADDENDUM.md §D): keying by node ID alone
 * breaks on retries, by ordinal alone breaks on branch changes, by prompt hash alone breaks
 * on every prompt edit.
 *
 * @param recordedOutput The recorded/mocked output for a model-inference, typed-tool,
 *   verifier, or artifact-import/export node. Untyped (`Any?`) because its real shape is
 *   whatever the node's own declared output schema requires — not independently
 *   constrained here.
 * @param recordedDecision The recorded outcome for a human-decision node. Exactly one of
 *   [recordedOutput] / [recordedDecision] MUST be non-null — see `init`.
 * @param recordedPromptDigest NON-BINDING: carried alongside the (nodeId, iterationIndex,
 *   callIndex) key, never as part of it. A mismatch against the node's CURRENT prompt digest
 *   at validation time marks this fixture STALE (LOOP-TEST-002), never a silent miss or a
 *   silent match against the wrong prompt.
 * @param stale Advisory/cache field, not authoritative on its own — see [recordedPromptDigest].
 */
data class PackageFixtureRecord(
    val nodeId: String,
    val iterationIndex: Int,
    val callIndex: Int,
    val recordedOutput: Any?,
    val recordedDecision: RecordedDecision?,
    val recordedPromptDigest: IntegrityRef? = null,
    val stale: Boolean? = null,
    val recordedAtUtc: Instant? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(nodeId.isNotBlank()) { "PackageFixtureRecord.nodeId must be non-blank." }
        require(iterationIndex >= 0) { "PackageFixtureRecord.iterationIndex must be >= 0." }
        require(callIndex >= 0) { "PackageFixtureRecord.callIndex must be >= 0." }
        require(recordedOutput != null || recordedDecision != null) {
            "PackageFixtureRecord must carry at least one of recordedOutput or recordedDecision " +
                "— a fixture recording neither replays nothing."
        }
    }
}

// =========================================================================================
// LoopBuildReceipt — schemas/loops/loop-build-receipt.schema.json
// =========================================================================================

/** LOOP_ENGINEERING_SPEC_V2.1.md §20's ten fixed build-pipeline steps, in ORDER — `.ordinal` (0-based) + 1 is the step number. */
enum class BuildStepName {
    FREEZE_DRAFT_REVISION, VALIDATE_SEMANTIC_GRAPH, CANONICALIZE, SCAN_FORBIDDEN_CONTENT,
    RUN_REQUIRED_FIXTURES, GENERATE_COMPAT_PROVENANCE_DOCS, INVENTORY_FILES,
    COMPUTE_CONTENT_DIGEST, SIGN_RELEASE_IDENTITY, EMIT_BUILD_RECEIPT
}

enum class BuildStepOutcome { SUCCEEDED, FAILED, SKIPPED }

/** One row of the fixed ten-step pipeline record. `stepNumber` and `stepName` MUST agree with each other — see `init`. */
data class BuildStepRecord(
    val stepNumber: Int,
    val stepName: BuildStepName,
    val outcome: BuildStepOutcome,
    val detail: String? = null
) {
    init {
        require(stepNumber in 1..10) { "BuildStepRecord.stepNumber must be in 1..10 (got $stepNumber)." }
        require(BuildStepName.entries[stepNumber - 1] == stepName) {
            "BuildStepRecord.stepName ($stepName) does not match the fixed pipeline order at " +
                "position $stepNumber (expected ${BuildStepName.entries[stepNumber - 1]})."
        }
    }
}

// ValidationFinding and its severity enum (FindingSeverity) are declared once, in
// LoopAuthoringContracts.kt, and reused here — both files share the dev.fonebrew.contracts.loops
// package. A real Gradle compile of this module (WP-2) caught a duplicate declaration here
// (identical shape minus a `remediation` field, plus a redundant severity enum with the same
// three values as FindingSeverity) that no earlier structural/lexical check could see, since
// brace-counting and JSON-schema validation cannot detect a cross-file Kotlin redeclaration.
// Fixed by consolidating on LoopAuthoringContracts.kt's version, which is the more complete of
// the two (it carries `remediation`, matching loop-validation-rules.v1.json's registry shape).
// LoopActivationContracts.kt and LoopCompatibilityContracts.kt both reference the old
// `LoopValidationSeverity` name directly, so it stays available as a typealias rather than
// forcing an edit to every call site across the corpus for a rename with no semantic content.
typealias LoopValidationSeverity = FindingSeverity

/** Summary of build step 5 (RUN_REQUIRED_FIXTURES). */
data class TestSuiteResults(
    val fixturesRun: Int,
    val fixturesPassed: Int,
    val fixturesFailed: Int,
    val fixturesStale: Int
) {
    init {
        require(fixturesRun >= 0) { "TestSuiteResults.fixturesRun must be >= 0." }
        require(fixturesPassed >= 0) { "TestSuiteResults.fixturesPassed must be >= 0." }
        require(fixturesFailed >= 0) { "TestSuiteResults.fixturesFailed must be >= 0." }
        require(fixturesStale >= 0) { "TestSuiteResults.fixturesStale must be >= 0 (LOOP-TEST-002 count, never silently dropped)." }
    }
}

/** What build step 9 (SIGN_RELEASE_IDENTITY) actually did, if attempted. `outcome` MUST agree with the corresponding [BuildStepRecord] in [LoopBuildReceipt.steps]. */
data class SignatureAction(
    val outcome: BuildStepOutcome,
    val producedSignatureRef: ManifestSignatureRef? = null
)

/**
 * The record LOOP_ENGINEERING_SPEC_V2.1.md §20 step 10 emits: which of the ten fixed
 * pipeline steps ran and their outcome, the resulting digests, validation findings, test
 * results, signature action, builder identity, and the fail-closed flag. "The build fails
 * closed. It never publishes a partially built package."
 *
 * @param allStepsSucceeded The fail-closed flag. A plain Boolean, not a bare `true`-only
 *   invariant, because a receipt for a BLOCKED build is itself a required, valid artifact
 *   (LOOP_PACKAGE_SPEC.md §5: a failed/non-deterministic build "is not silently discarded,
 *   it is recorded as such"). What `init` enforces instead is narrower and structural: when
 *   this is `false`, [packageContentDigest] MUST be null (so a consumer reading that field
 *   without checking this flag first can never mistake a blocked build's receipt for a
 *   completed one); when `true`, every required step (1-8, 10) MUST be SUCCEEDED and step 9
 *   MUST be SUCCEEDED or SKIPPED. This is this type's actual enforcement of "do not allow a
 *   build receipt to exist in a partial-success state that could be mistaken for a completed
 *   build."
 * @param packageContentDigest Null whenever [allStepsSucceeded] is false; required non-null
 *   when true — see `init`.
 * @param fileInventoryDigest SHA-256 over the COMPLETE packageInventory list (every
 *   semanticFile, derivedFile, and signatureFile entry) — unlike packageContentDigest, which
 *   per floop-container-format.v1.json covers only semanticFile entries. Receipt-only,
 *   whole-archive tamper evidence; does not redefine packageContentDigest.
 */
data class LoopBuildReceipt(
    val buildReceiptId: String,
    val sourceDraftRevision: String,
    val steps: List<BuildStepRecord>,
    val allStepsSucceeded: Boolean,
    val validationFindings: List<ValidationFinding>,
    val packageContentDigest: IntegrityRef?,
    val builderProducer: ProducerRef,
    val createdAtUtc: Instant,
    val validationRulesetVersion: String = "fb-loop-validation-rules-1",
    val canonicalizationVersion: String = "fb-loop-canon-1",
    val semanticDigest: String? = null,
    val semanticDigestVersion: String? = null,
    val testSuiteResults: TestSuiteResults? = null,
    val fileInventoryDigest: IntegrityRef? = null,
    val signatureAction: SignatureAction? = null,
    val warnings: List<String> = emptyList(),
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(buildReceiptId.isNotBlank()) { "LoopBuildReceipt.buildReceiptId must be non-blank." }
        require(sourceDraftRevision.isNotBlank()) { "LoopBuildReceipt.sourceDraftRevision must be non-blank." }
        require(steps.size == 10) { "LoopBuildReceipt.steps must have exactly 10 entries (got ${steps.size})." }
        steps.forEachIndexed { index, step ->
            require(step.stepNumber == index + 1) {
                "LoopBuildReceipt.steps[$index].stepNumber must be ${index + 1} (got ${step.stepNumber})."
            }
        }
        require(steps[9].outcome == BuildStepOutcome.SUCCEEDED) {
            "LoopBuildReceipt.steps[9] (EMIT_BUILD_RECEIPT) must be SUCCEEDED — the document's own " +
                "existence is the evidence step 10 completed; a build that fails to emit a receipt " +
                "produces no receipt object at all."
        }
        require(validationRulesetVersion == "fb-loop-validation-rules-1") {
            "LoopBuildReceipt.validationRulesetVersion must equal 'fb-loop-validation-rules-1'."
        }
        require(canonicalizationVersion == "fb-loop-canon-1") {
            "LoopBuildReceipt.canonicalizationVersion must equal 'fb-loop-canon-1'."
        }

        if (!allStepsSucceeded) {
            require(packageContentDigest == null) {
                "LoopBuildReceipt.packageContentDigest must be null when allStepsSucceeded is " +
                    "false — a blocked build's receipt MUST NOT carry a digest that could be " +
                    "mistaken for a completed build's (see the class doc)."
            }
        } else {
            requireNotNull(packageContentDigest) {
                "LoopBuildReceipt.packageContentDigest is required when allStepsSucceeded is true."
            }
            steps.forEachIndexed { index, step ->
                val stepNumber = index + 1
                val acceptable = if (stepNumber == 9) {
                    setOf(BuildStepOutcome.SUCCEEDED, BuildStepOutcome.SKIPPED)
                } else {
                    setOf(BuildStepOutcome.SUCCEEDED)
                }
                require(step.outcome in acceptable) {
                    "LoopBuildReceipt.steps[$index] (step $stepNumber, ${step.stepName}) must have " +
                        "outcome in $acceptable when allStepsSucceeded is true (got ${step.outcome})."
                }
            }
        }

        require((semanticDigest == null) == (semanticDigestVersion == null)) {
            "LoopBuildReceipt.semanticDigest and semanticDigestVersion must both be null or both be non-null."
        }
        semanticDigest?.let {
            require(it.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
                "LoopBuildReceipt.semanticDigest must match 'sha256:<64 lowercase hex>' " +
                    "(semantic-digest.v1.json's chosen serialization), got '$it'."
            }
            require(semanticDigestVersion == "fb-semantic-digest-1") {
                "LoopBuildReceipt.semanticDigestVersion must equal 'fb-semantic-digest-1' when semanticDigest is present."
            }
        }
    }
}
