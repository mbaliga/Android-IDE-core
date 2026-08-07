// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// LoopActivationContracts.kt — the "activation" domain's shared wire-shape data classes and
// the eleven-state (plus five-terminal) import/activation state machine.
//
// Mirrors, field-for-field, the JSON Schema documents under schemas/loops/*.schema.json:
//   LoopInstallation       -> loop-installation.schema.json
//   LoopActivationReceipt  -> loop-activation-receipt.schema.json
//   BindingProfile         -> binding-profile.schema.json
//   LoopValidationReport   -> loop-validation-report.schema.json
// If a field appears in one place, it MUST appear in the other, or the two have drifted and
// one of them is wrong. See docs/ratified/loops/LOOP_IMPORT_ACTIVATION_CONTRACT.md
// (FB-RAT-IMP-001..010, FB-RAT-PHN-009) and docs/ratified/loops/LOOP_COMPATIBILITY_CONTRACT.md
// (FB-RAT-CMP-003) for the citations this file's fields operationalize. This is the
// "activation-compatibility" WP-1L group's first of two files — LoopCompatibilityContracts.kt
// (same package) owns CompatibilityOutcome/CompatibilityAxis/LoopCompatibilityReport.
//
// Cross-package reuse (first-party imports, not third-party dependencies): IntegrityRef and
// ProducerRef from dev.aarso.contracts.common (matches every other domain's file in this
// directory). Same-PACKAGE reuse (no import needed — Kotlin visibility is automatic within one
// package): ReleaseIdentity and TransferEnvelope from LoopPackageContracts.kt; AuthorityRung,
// DistFlavor, and BindingSlotKind from LoopDefinitionContracts.kt; LoopValidationSeverity from
// LoopPackageContracts.kt. None of these are redefined here.
//
// KNOWN PRE-EXISTING PACKAGE CONFLICT (not introduced or fixed by this file — flagged for the
// integration pass): `dev.aarso.contracts.loops.ValidationFinding` is ALREADY declared twice in
// this package — once in LoopAuthoringContracts.kt (`severity: FindingSeverity`) and once in
// LoopPackageContracts.kt (`severity: LoopValidationSeverity`) — a genuine redeclaration this
// package will not compile with as-is. This file deliberately does NOT add a third conflicting
// declaration: the loop-validation-report.schema.json finding shape below is named
// [ValidationReportFinding] instead of [ValidationFinding], specifically to avoid worsening that
// collision. Reconciling the other two is out of this group's assigned scope (activation-
// compatibility only) and is not attempted here.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no
// Android imports.
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build
// environment — this file has been written carefully (balanced braces, matched types, no
// typos attempted) but has NOT been compiled. Do not report it as compiling; that is for
// the next session with Gradle available to confirm. This file depends on
// contracts/kotlin/CommonContracts.kt and, within this same package,
// contracts/kotlin/LoopPackageContracts.kt and contracts/kotlin/LoopDefinitionContracts.kt
// being compiled in the same module/source set.
//
// Why InstallationState IS a real (enum-plus-transition-table) state machine here, unlike
// LoopPackageContracts.kt's deliberate choice to have none: this domain owns the actual live
// runtime state of an in-progress-or-completed import attempt on the phone (WP-0 survey's
// "real state machines belong to the domains that own actual state" — the same reasoning
// ExecutionContracts.kt gives for ExecutionLifecycleState). It stops short of a full sealed-
// interface-per-state hierarchy (unlike ExecutionLifecycleState) because LoopInstallation's job
// is the WIRE RECORD of that state machine (mirroring loop-installation.schema.json field-for-
// field), not an in-process runtime driver — [InstallationState.isValidTransition] gives the
// same from-state/to-state table ExecutionLifecycleState's sealed hierarchy encodes, as a plain
// function over a plain enum, which is enough for a wire-shape file to validate a transition
// without inventing sixteen additional sealed subtypes this domain does not otherwise need.

package dev.aarso.contracts.loops

import dev.aarso.contracts.common.IntegrityRef
import dev.aarso.contracts.common.ProducerRef
import java.time.Instant

private val ENGINE_VERSION_REGEX = Regex("^\\d+\\.\\d+\\.\\d+$")

// =========================================================================================
// PackageSignatureState — LOOP_PACKAGE_SPEC.md §11's signature verification/revocation table
// =========================================================================================

/**
 * Copied verbatim from docs/ratified/loops/LOOP_PACKAGE_SPEC.md §11's from-state/event/to-
 * state/notes table. Shared by [LoopInstallation.signatureState] and
 * [LoopActivationReceipt.signatureState] — a receipt (only ever written on reaching
 * [InstallationState.INSTALLED]) is further restricted to [TERMINAL_IMPORTED_STATES]; an
 * in-progress [LoopInstallation] MAY be null (not yet evaluated) or any of the other seven.
 */
enum class PackageSignatureState {
    PACKAGE_ACQUIRED, SIGNATURE_PRESENT, UNSIGNED,
    SIGNATURE_VALID_REVOCATION_CHECKED, SIGNATURE_INVALID, SIGNATURE_VALID_REVOCATION_UNKNOWN,
    REJECTED,
    IMPORTED_UNSIGNED_NARROWED_GRANTS, IMPORTED_REVOKED_KEY_FLAGGED,
    IMPORTED_SIGNED_VERIFIED, IMPORTED_SIGNED_REVOCATION_UNKNOWN;

    companion object {
        /**
         * The four terminal postures reachable once an import actually proceeds past the
         * signature branch (§11's UNSIGNED/SIGNATURE_VALID_REVOCATION_CHECKED/
         * SIGNATURE_VALID_REVOCATION_UNKNOWN outcomes) — the only values valid on a
         * [LoopInstallation] at [InstallationState.INSTALLED] or on any [LoopActivationReceipt]
         * (which exists only because that state was reached).
         */
        val TERMINAL_IMPORTED_STATES: Set<PackageSignatureState> = setOf(
            IMPORTED_SIGNED_VERIFIED, IMPORTED_SIGNED_REVOCATION_UNKNOWN,
            IMPORTED_REVOKED_KEY_FLAGGED, IMPORTED_UNSIGNED_NARROWED_GRANTS
        )
    }
}

// =========================================================================================
// InstallationState — LOOP_IMPORT_ACTIVATION_CONTRACT.md §3's eleven-state (+5 terminal) machine
// =========================================================================================

/**
 * Copied verbatim from docs/ratified/loops/LOOP_IMPORT_ACTIVATION_CONTRACT.md §3 — the
 * resolution of the source pack's two colliding draft import state machines
 * (`10_DUAL_VALIDATION_ADDENDUM.md` §B2). Do not re-derive or approximate this list; a
 * conflict between this enum and that document's table is this file's error, and the document
 * wins.
 */
enum class InstallationState {
    ACQUIRING, SNAPSHOTTED, CONTAINER_VERIFIED, PARSED_VALIDATED, COMPATIBILITY_EVALUATED,
    PREVIEWED, WAITING_BINDINGS, WAITING_AUTHORITY, READY_TO_SIMULATE, INSTALLABLE, INSTALLED,
    CANCELLED, REJECTED_UNSAFE, REJECTED_POLICY, BLOCKED_INCOMPATIBLE, FAILED_SAFE;

    val isTerminal: Boolean get() = this in TERMINAL

    companion object {
        /** The ten-transition main path, ACQUIRING through INSTALLED, in order (§3's table). */
        val MAIN_PATH: List<InstallationState> = listOf(
            ACQUIRING, SNAPSHOTTED, CONTAINER_VERIFIED, PARSED_VALIDATED,
            COMPATIBILITY_EVALUATED, PREVIEWED, WAITING_BINDINGS, WAITING_AUTHORITY,
            READY_TO_SIMULATE, INSTALLABLE, INSTALLED
        )

        /** §3's five terminal states, reachable from various ranges of [MAIN_PATH] — see [isValidTransition]. */
        val TERMINAL: Set<InstallationState> = setOf(
            CANCELLED, REJECTED_UNSAFE, REJECTED_POLICY, BLOCKED_INCOMPATIBLE, FAILED_SAFE
        )

        /** [MAIN_PATH] minus the terminal INSTALLED entry — the eleven non-terminal states a terminal transition can originate from. */
        private val NON_TERMINAL: List<InstallationState> = MAIN_PATH.dropLast(1)

        /**
         * True iff [to] is a state [from] may transition to directly, per §3's from-state/event/
         * to-state table (main path) and terminal-states table (reachability ranges). A terminal
         * state has no outgoing edge (§3, §12 — "installation remains disabled until
         * re-evaluated" is the only thing that happens next, never a further InstallationState
         * transition of the SAME record).
         */
        fun isValidTransition(from: InstallationState, to: InstallationState): Boolean {
            if (from.isTerminal) return false
            val fromIndex = NON_TERMINAL.indexOf(from)
            val nextOnPath = MAIN_PATH.getOrNull(MAIN_PATH.indexOf(from) + 1)
            if (nextOnPath == to) return true
            return when (to) {
                CANCELLED, FAILED_SAFE -> true
                REJECTED_UNSAFE -> fromIndex in 0..NON_TERMINAL.indexOf(CONTAINER_VERIFIED)
                REJECTED_POLICY -> fromIndex in NON_TERMINAL.indexOf(CONTAINER_VERIFIED)..NON_TERMINAL.indexOf(WAITING_AUTHORITY)
                BLOCKED_INCOMPATIBLE -> fromIndex in NON_TERMINAL.indexOf(PARSED_VALIDATED)..NON_TERMINAL.indexOf(COMPATIBILITY_EVALUATED)
                else -> false
            }
        }
    }
}

// =========================================================================================
// DurableObjectRef — a lightweight pointer to another durable object in this constellation
// =========================================================================================

/**
 * Points at another durable object (a [LoopCompatibilityReport], a [BindingProfile], a receipt)
 * by its own FB-RAT-COM-002 `objectId`, with an optional integrity pin (FB-RAT-COM-005).
 * Deliberately NOT [dev.aarso.contracts.common.ArtifactRef] — that type describes a byte
 * artifact's storage location; these references are to structured JSON documents already
 * modeled by their own data classes in this file, not opaque byte blobs.
 */
data class DurableObjectRef(
    val objectId: String,
    val digest: IntegrityRef? = null
) {
    init {
        require(objectId.isNotBlank()) { "DurableObjectRef.objectId must be non-blank." }
    }
}

// =========================================================================================
// LoopInstallation — schemas/loops/loop-installation.schema.json
// =========================================================================================

/**
 * The phone-local durable record of one package's journey through [InstallationState]. See
 * loop-installation.schema.json's `$comment` for the full immutability invariant this type
 * cannot enforce structurally (installed bytes are immutable once [installationState] reaches
 * [InstallationState.INSTALLED] — LOOP-ID-002).
 *
 * @param authorityGrants Grant IDs (schemas/authority/grant.schema.json `Grant.grantId`) only —
 *   referenced by ID, never embedded; the authority domain (`dev.aarso.contracts.authority`)
 *   owns the full `Grant` shape.
 */
data class LoopInstallation(
    val schemaVersion: String,
    val installationId: String,
    val releaseIdentity: ReleaseIdentity,
    val source: TransferEnvelope,
    val installationState: InstallationState,
    val createdAtUtc: Instant,
    val signatureState: PackageSignatureState? = null,
    val compatibilityReportRef: DurableObjectRef? = null,
    val bindingProfileRef: DurableObjectRef? = null,
    val authorityGrants: List<String> = emptyList(),
    val receipts: List<DurableObjectRef> = emptyList(),
    val updatedAtUtc: Instant? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "LoopInstallation.schemaVersion must be major version 1 (got '$schemaVersion')."
        }
        require(installationId.isNotBlank()) { "LoopInstallation.installationId must be non-blank." }

        if (installationState == InstallationState.INSTALLED) {
            requireNotNull(compatibilityReportRef) {
                "LoopInstallation.compatibilityReportRef is required once installationState reaches INSTALLED."
            }
            requireNotNull(bindingProfileRef) {
                "LoopInstallation.bindingProfileRef is required once installationState reaches INSTALLED."
            }
            require(signatureState in PackageSignatureState.TERMINAL_IMPORTED_STATES) {
                "LoopInstallation.signatureState must be one of " +
                    "${PackageSignatureState.TERMINAL_IMPORTED_STATES} when installationState is " +
                    "INSTALLED (got $signatureState) — LOOP_PACKAGE_SPEC.md §11."
            }
            require(receipts.isNotEmpty()) {
                "LoopInstallation.receipts must be non-empty once installationState reaches INSTALLED " +
                    "(FB-RAT-IMP-009 — the activation receipt this transition itself writes)."
            }
        } else if (installationState in setOf(
                InstallationState.COMPATIBILITY_EVALUATED, InstallationState.PREVIEWED,
                InstallationState.WAITING_BINDINGS, InstallationState.WAITING_AUTHORITY,
                InstallationState.READY_TO_SIMULATE, InstallationState.INSTALLABLE,
                InstallationState.BLOCKED_INCOMPATIBLE
            )
        ) {
            requireNotNull(compatibilityReportRef) {
                "LoopInstallation.compatibilityReportRef is required once installationState reaches " +
                    "COMPATIBILITY_EVALUATED or later on the main path (or BLOCKED_INCOMPATIBLE)."
            }
        }
    }
}

// =========================================================================================
// BindingProfile — schemas/loops/binding-profile.schema.json
// =========================================================================================

/**
 * LOOP_COMPATIBILITY_CONTRACT.md §5's four-value substitution policy (FB-RAT-CMP-003) — the
 * corpus's real vocabulary, NOT the WP-1L task brief's abbreviated three-value paraphrase
 * ("EXACT|EQUIVALENT|USER_APPROVED"). Shared by [BindingRecord] and [BoundSlotSummary].
 */
enum class SubstitutionPolicy { EXACT_ONLY, DECLARED_EQUIVALENTS, USER_APPROVED, ANY_COMPATIBLE }

/** LOOP_IMPORT_ACTIVATION_CONTRACT.md §5's local/cloud/remote provenance for a resolved binding. */
enum class BindingProvenance { LOCAL, CLOUD, REMOTE }

/**
 * One resolved binding slot record (LOOP_IMPORT_ACTIVATION_CONTRACT.md §5's required field
 * list). [targetType] is deliberately a free string, not [BindingSlotKind] (defined in
 * LoopDefinitionContracts.kt, same package) — mirrors binding-profile.schema.json's own choice
 * to leave it open, since the schema documents lowercase examples ('model', 'repository') that
 * do not match [BindingSlotKind]'s SCREAMING_SNAKE wire form; a producer SHOULD draw from
 * [BindingSlotKind] in spirit without this type enforcing the exact casing.
 *
 * @param secretReferenceId A Keystore-held secret HANDLE ID only (security/KeystoreSecret.kt) —
 *   MUST NEVER carry a raw secret value (CLAUDE.md binding rule 5; see
 *   fixtures/loops/binding-profile/adversarial/secret-value-looks-raw.adversarial.json for the
 *   fixture modeling exactly that violation, which this constructor cannot detect by shape
 *   alone).
 */
data class BindingRecord(
    val slotId: String,
    val selectedProviderId: String,
    val substitutionPolicy: SubstitutionPolicy,
    val provenance: BindingProvenance,
    val requirement: String? = null,
    val providerVersion: String? = null,
    val targetType: String? = null,
    val substitutionReason: String? = null,
    val constraints: Map<String, Any?> = emptyMap(),
    val budgets: Map<String, Any?> = emptyMap(),
    val secretReferenceId: String? = null
) {
    init {
        require(slotId.isNotBlank()) { "BindingRecord.slotId must be non-blank." }
        require(selectedProviderId.isNotBlank()) { "BindingRecord.selectedProviderId must be non-blank." }
        if (substitutionPolicy == SubstitutionPolicy.EXACT_ONLY) {
            require(substitutionReason == null) {
                "BindingRecord.substitutionReason must be null when substitutionPolicy is EXACT_ONLY " +
                    "(LOOP_COMPATIBILITY_CONTRACT.md §5 — there is no substitution to explain)."
            }
        }
    }
}

/**
 * Device- and user-specific resolution of one release's abstract binding slots
 * (LOOP_ENGINEERING_SPEC_V2.1.md §2.6). **NOT publishable package content** (FB-RAT-WEB-005) —
 * a package build pipeline MUST NOT include a BindingProfile in its output.
 *
 * @param slotBindings MUST have unique `slotId` values across entries — not enforced by this
 *   constructor (see [BindingRecord]'s adversarial fixture for the duplicate-slotId case; the
 *   real rejection is `LOOP-ID-001`, `objectType: "slot"`, at the rule-engine layer).
 */
data class BindingProfile(
    val schemaVersion: String,
    val profileId: String,
    val packageDigest: IntegrityRef,
    val slotBindings: List<BindingRecord>,
    val resolvedAtUtc: Instant,
    val loopId: String? = null,
    val semanticVersion: String? = null,
    val producer: ProducerRef? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "BindingProfile.schemaVersion must be major version 1 (got '$schemaVersion')."
        }
        require(profileId.isNotBlank()) { "BindingProfile.profileId must be non-blank." }
        require(slotBindings.isNotEmpty()) { "BindingProfile.slotBindings must be non-empty." }
    }
}

// =========================================================================================
// LoopValidationReport — schemas/loops/loop-validation-report.schema.json
// =========================================================================================

/** loop-validation-rules.v1.json's `namespaces[].prefix` list, verbatim. */
enum class ValidationNamespace {
    LOOP_ID, LOOP_GRAPH, LOOP_SCHEMA, LOOP_CAP, LOOP_BUDGET,
    LOOP_VERIFY, LOOP_PKG, LOOP_COMPAT, LOOP_TEST, LOOP_LINEAGE;

    /** The wire prefix this Kotlin identifier stands for, e.g. LOOP_PKG -> "LOOP-PKG" (Kotlin identifiers cannot contain '-'). */
    val wirePrefix: String get() = name.replace('_', '-')
}

/** Which of the two dual-surface implementations produced a report (LOOP_DUAL_SURFACE_ARCHITECTURE.md). */
enum class ValidationSurface { PHONE, BROWSER }

data class SurfaceImplementation(
    val surface: ValidationSurface,
    val name: String,
    val version: String,
    val instanceId: String? = null
) {
    init {
        require(name.isNotBlank()) { "SurfaceImplementation.name must be non-blank." }
        require(version.isNotBlank()) { "SurfaceImplementation.version must be non-blank." }
    }
}

/**
 * One finding in a [LoopValidationReport]. Named [ValidationReportFinding], NOT
 * `ValidationFinding` — see this file's header comment on the pre-existing
 * `ValidationFinding` collision this name deliberately avoids adding to. Reuses
 * [LoopValidationSeverity] (LoopPackageContracts.kt, same package) rather than yet another
 * duplicate three-value severity enum.
 *
 * @param namespace MUST equal `code`'s own `LOOP-<NAMESPACE>` prefix — not enforced by this
 *   constructor for the same reason the JSON Schema counterpart does not cross-check it
 *   structurally (see loop-validation-report.schema.json's Finding/namespace description); see
 *   fixtures/loops/loop-validation-report/adversarial/namespace-code-mismatch.adversarial.json.
 */
data class ValidationReportFinding(
    val namespace: ValidationNamespace,
    val code: String,
    val severity: LoopValidationSeverity,
    val objectType: String,
    val message: String,
    val nodeId: String? = null,
    val edgeId: String? = null,
    val remediation: String? = null,
    val nonDismissible: Boolean? = null
) {
    init {
        require(code.matches(Regex("^LOOP-[A-Z]+-[0-9]{3}$"))) {
            "ValidationReportFinding.code must match 'LOOP-<NAMESPACE>-<3 digits>' (got '$code')."
        }
        require(objectType.isNotBlank()) { "ValidationReportFinding.objectType must be non-blank." }
        require(message.isNotBlank()) { "ValidationReportFinding.message must be non-blank." }
    }
}

/**
 * The output of the CONTAINER_VERIFIED-to-PARSED_VALIDATED transition
 * (LOOP_IMPORT_ACTIVATION_CONTRACT.md §3): schema/graph/engine-policy validation against
 * loop-validation-rules.v1.json.
 *
 * @param findings MUST be sorted by (namespace, then code, then nodeId/edgeId) per
 *   loop-validation-rules.v1.json's own top-level description — not enforced by this
 *   constructor (see [sortedFindings] for a producer-side helper that DOES enforce it, and
 *   fixtures/loops/loop-validation-report/adversarial/unsorted-findings.adversarial.json for the
 *   violation this leaves undetected at construction time).
 * @param highestSeverity MUST equal the most severe value across [findings] (ERROR > WARNING >
 *   INFO), or null when [findings] is empty — enforced by `init` below (unlike the sort-order
 *   invariant above, this one IS mechanically checkable at construction time).
 */
data class LoopValidationReport(
    val schemaVersion: String,
    val reportId: String,
    val semanticDigest: String,
    val surfaceImplementation: SurfaceImplementation,
    val findings: List<ValidationReportFinding>,
    val createdAtUtc: Instant,
    val rulesetVersion: String = "fb-loop-validation-rules-1",
    val semanticDigestVersion: String = "fb-semantic-digest-1",
    val highestSeverity: LoopValidationSeverity? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "LoopValidationReport.schemaVersion must be major version 1 (got '$schemaVersion')."
        }
        require(reportId.isNotBlank()) { "LoopValidationReport.reportId must be non-blank." }
        require(rulesetVersion == "fb-loop-validation-rules-1") {
            "LoopValidationReport.rulesetVersion must equal 'fb-loop-validation-rules-1'."
        }
        require(semanticDigest.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
            "LoopValidationReport.semanticDigest must match 'sha256:<64 lowercase hex>' (got '$semanticDigest')."
        }
        require(semanticDigestVersion == "fb-semantic-digest-1") {
            "LoopValidationReport.semanticDigestVersion must equal 'fb-semantic-digest-1'."
        }
        val expectedHighest = derivedHighestSeverity(findings)
        require(highestSeverity == expectedHighest) {
            "LoopValidationReport.highestSeverity must be $expectedHighest given the severities " +
                "present in findings (got $highestSeverity)."
        }
    }

    companion object {
        /** ERROR > WARNING > INFO; null when [findings] is empty. */
        fun derivedHighestSeverity(findings: List<ValidationReportFinding>): LoopValidationSeverity? =
            when {
                findings.any { it.severity == LoopValidationSeverity.ERROR } -> LoopValidationSeverity.ERROR
                findings.any { it.severity == LoopValidationSeverity.WARNING } -> LoopValidationSeverity.WARNING
                findings.any { it.severity == LoopValidationSeverity.INFO } -> LoopValidationSeverity.INFO
                else -> null
            }

        /** Producer-side helper enforcing the (namespace, code, nodeId/edgeId) sort key the registry requires. */
        fun sortedFindings(findings: List<ValidationReportFinding>): List<ValidationReportFinding> =
            findings.sortedWith(
                compareBy(
                    { it.namespace },
                    { it.code },
                    { it.nodeId ?: it.edgeId ?: "" }
                )
            )
    }
}

// =========================================================================================
// LoopActivationReceipt — schemas/loops/loop-activation-receipt.schema.json
// =========================================================================================

/** LOOP_COMPATIBILITY_CONTRACT.md §5's per-slot substitution summary, as recorded in a receipt (FB-RAT-CMP-003 "substitutions are recorded in the activation receipt"). */
data class BoundSlotSummary(
    val slotId: String,
    val selectedProviderId: String,
    val substitutionPolicy: SubstitutionPolicy,
    val substitutionReason: String? = null
) {
    init {
        require(slotId.isNotBlank()) { "BoundSlotSummary.slotId must be non-blank." }
        require(selectedProviderId.isNotBlank()) { "BoundSlotSummary.selectedProviderId must be non-blank." }
    }
}

/** One authority-rung bucket of granted capabilities, for receipt display (LOOP_IMPORT_ACTIVATION_CONTRACT.md §6). Reuses [AuthorityRung] (LoopDefinitionContracts.kt, same package) rather than redefining the eight-rung ladder a third time in this package. */
data class GrantedAuthorityBucket(
    val authorityRung: AuthorityRung,
    val capabilityIds: List<String>
) {
    init {
        require(capabilityIds.isNotEmpty()) { "GrantedAuthorityBucket.capabilityIds must be non-empty." }
    }
}

/** LOOP_IMPORT_ACTIVATION_CONTRACT.md §7's dangerous-first-run-simulation outcome (FB-RAT-PHN-009). */
enum class SimulationOutcome { SUCCEEDED, FAILED, SKIPPED_NO_SIMULATOR_AVAILABLE }

data class SimulationResult(
    val outcome: SimulationOutcome,
    val gapAcknowledgedByUser: Boolean? = null
) {
    init {
        if (outcome == SimulationOutcome.SKIPPED_NO_SIMULATOR_AVAILABLE) {
            require(gapAcknowledgedByUser == true) {
                "SimulationResult.gapAcknowledgedByUser must be true when outcome is " +
                    "SKIPPED_NO_SIMULATOR_AVAILABLE (§7 — 'the UI MUST state the gap explicitly " +
                    "and require a stronger, distinct acknowledgement')."
            }
        }
    }
}

/**
 * The durable, append-only record written on entry to [InstallationState.INSTALLED]
 * (LOOP_IMPORT_ACTIVATION_CONTRACT.md §3, §10, FB-RAT-IMP-009). [receiptType] is this domain's
 * forward-compatible seam for the future consolidated `Receipt<T>` envelope named at
 * `10_DUAL_VALIDATION_ADDENDUM.md` §E — that consolidation is WP-2 scope, not designed here.
 *
 * @param compatibilityOutcome Reuses [CompatibilityOutcome] (LoopCompatibilityContracts.kt,
 *   same package/file group). Only COMPATIBLE, COMPATIBLE_WITH_BINDINGS, or DEGRADED are
 *   reachable in a real receipt — BLOCKED/UNSUPPORTED route to BLOCKED_INCOMPATIBLE and never
 *   produce one — but the full five-value enum is reused rather than narrowed, so this type
 *   never drifts from LOOP_COMPATIBILITY_CONTRACT.md's own enum.
 */
data class LoopActivationReceipt(
    val receiptId: String,
    val loopId: String,
    val semanticVersion: String,
    val packageDigest: IntegrityRef,
    val signatureState: PackageSignatureState,
    val compatibilityOutcome: CompatibilityOutcome,
    val boundSlots: List<BoundSlotSummary>,
    val grantedAuthoritySummary: List<GrantedAuthorityBucket>,
    val installedAtUtc: Instant,
    val engineVersion: String,
    val schemaVersion: String = "1.0.0",
    val installationId: String? = null,
    val source: TransferEnvelope? = null,
    val validationRulesetVersion: String? = "fb-loop-validation-rules-1",
    val validationFindings: List<ValidationReportFinding> = emptyList(),
    val bindingProfileDigest: IntegrityRef? = null,
    val authorityGrantIds: List<String> = emptyList(),
    val simulationResult: SimulationResult? = null,
    val producer: ProducerRef? = null,
    val warnings: List<String> = emptyList(),
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    /** Fixed literal discriminant — see the class doc on the future consolidated envelope this anticipates. */
    val receiptType: String = "LOOP_ACTIVATION"

    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "LoopActivationReceipt.schemaVersion must be major version 1 (got '$schemaVersion')."
        }
        require(receiptId.isNotBlank()) { "LoopActivationReceipt.receiptId must be non-blank." }
        require(loopId.isNotBlank()) { "LoopActivationReceipt.loopId must be non-blank." }
        require(engineVersion.matches(ENGINE_VERSION_REGEX)) {
            "LoopActivationReceipt.engineVersion must be a plain SemVer string (got '$engineVersion')."
        }
        require(signatureState in PackageSignatureState.TERMINAL_IMPORTED_STATES) {
            "LoopActivationReceipt.signatureState must be one of " +
                "${PackageSignatureState.TERMINAL_IMPORTED_STATES} (got $signatureState) — this " +
                "receipt exists only because InstallationState reached INSTALLED, so signatureState " +
                "MUST already have settled (LOOP_PACKAGE_SPEC.md §11)."
        }
    }
}
