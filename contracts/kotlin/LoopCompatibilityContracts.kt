// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// LoopCompatibilityContracts.kt — the "compatibility" domain's shared wire-shape data class.
//
// Mirrors, field-for-field, the JSON Schema document under schemas/loops/*.schema.json:
//   LoopCompatibilityReport  -> loop-compatibility-report.schema.json
// If a field appears in one place, it MUST appear in the other, or the two have drifted and
// one of them is wrong. See docs/ratified/loops/LOOP_COMPATIBILITY_CONTRACT.md
// (FB-RAT-CMP-001..007, FB-RAT-IMP-007) for the citations this file's fields operationalize.
// This is the "activation-compatibility" WP-1L group's second of two files —
// LoopActivationContracts.kt (same package) owns LoopInstallation/LoopActivationReceipt/
// BindingProfile/LoopValidationReport, including [SubstitutionPolicy] (LOOP_COMPATIBILITY
// _CONTRACT.md §5's own vocabulary, but shared with [dev.aarso.contracts.loops.BindingRecord]
// there, so it is defined in that file, not this one — [CompatibilityOutcome] below is the
// converse case: owned here, and reused by that file's LoopActivationReceipt without
// redefinition, since both files compile into the same package).
//
// Cross-package reuse (first-party import, not a third-party dependency): IntegrityRef from
// dev.aarso.contracts.common. Same-PACKAGE reuse (no import needed): DistFlavor from
// LoopDefinitionContracts.kt, reused as [CacheKey.distributionFlavor] rather than redefining a
// third FULL/PLAY-shaped enum in this package.
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
// contracts/kotlin/LoopDefinitionContracts.kt being compiled in the same module/source set.
//
// Why [CompatibilityOutcome.worstOf] exists and is not just documentation: this is the exact
// same precedence-order derivation loop-compatibility-report.schema.json encodes structurally
// via a five-branch allOf/if/then/contains chain (because JSON Schema has no native "reduce"
// operation) — Kotlin has a real reduce, so [LoopCompatibilityReport]'s `init` block calls this
// function directly rather than re-encoding the same five if/else branches as five separate
// `require()` calls the way the JSON Schema had to. One source of truth for the precedence rule,
// expressed the natural way in each language.

package dev.aarso.contracts.loops

import dev.aarso.contracts.common.IntegrityRef
import java.time.Instant

// =========================================================================================
// CompatibilityOutcome — LOOP_COMPATIBILITY_CONTRACT.md §3 (FB-RAT-CMP-004)
// =========================================================================================

/**
 * The five-value outcome enum, in ASCENDING severity / DESCENDING recoverability order —
 * [entries] and [PRECEDENCE] both rely on this declaration order, so do not reorder these
 * without updating both. `FB-RAT-IMP-007` (owned by LOOP_IMPORT_ACTIVATION_CONTRACT.md)
 * consumes this exact enum for its `COMPATIBILITY_EVALUATED` transition.
 */
enum class CompatibilityOutcome {
    COMPATIBLE, COMPATIBLE_WITH_BINDINGS, DEGRADED, BLOCKED, UNSUPPORTED;

    companion object {
        /** Highest precedence first — mirrors §3's "the first row below whose condition is true wins" table exactly. */
        val PRECEDENCE: List<CompatibilityOutcome> = listOf(UNSUPPORTED, BLOCKED, DEGRADED, COMPATIBLE_WITH_BINDINGS, COMPATIBLE)

        /**
         * §3's strict precedence rule: the worst (highest-[PRECEDENCE]) outcome among [outcomes]
         * wins, regardless of how many lower-precedence outcomes also occur. [COMPATIBLE] when
         * [outcomes] is empty — an evaluation with no axes to report is vacuously compatible,
         * though [LoopCompatibilityReport] never actually constructs one that way (it always
         * carries exactly ten axis outcomes).
         */
        fun worstOf(outcomes: Collection<CompatibilityOutcome>): CompatibilityOutcome =
            PRECEDENCE.firstOrNull { it in outcomes } ?: COMPATIBLE
    }
}

// =========================================================================================
// CompatibilityAxis — LOOP_COMPATIBILITY_CONTRACT.md §2 (FB-RAT-CMP-001)
// =========================================================================================

/** The ten axes a compatibility evaluation MUST cover — no fewer, no draft-only axis not folded in. */
enum class CompatibilityAxis {
    SCHEMA, ENGINE, NODE_TYPE, CAPABILITY, MODEL_CLASS,
    EXECUTION_TARGET, PLATFORM_DISTRIBUTION, DEVICE, RESOURCE, POLICY
}

/** LOOP_COMPATIBILITY_CONTRACT.md §11's closed, machine-checkable remediation-action enum. */
enum class RemediationType {
    BIND_RESOURCE, INSTALL_TRUSTED_CAPABILITY, CHOOSE_ALTERNATE_RELEASE, ENABLE_PERMISSION,
    SELECT_REMOTE_TARGET, LOWER_AUTHORITY, UPDATE_ENGINE, RUN_MIGRATION,
    CONTACT_PUBLISHER, ABANDON_IMPORT
}

/** A grounding finding for one [AxisOutcome], typically LOOP-COMPAT-* but any registry namespace MAY apply. */
data class AxisFinding(
    val code: String,
    val severity: LoopValidationSeverity,
    val message: String? = null
) {
    init {
        require(code.matches(Regex("^LOOP-[A-Z]+-[0-9]{3}$"))) {
            "AxisFinding.code must match 'LOOP-<NAMESPACE>-<3 digits>' (got '$code')."
        }
    }
}

/**
 * One axis's own outcome, reason, and (when not [CompatibilityOutcome.COMPATIBLE]) required
 * remediation (LOOP_COMPATIBILITY_CONTRACT.md §11 — "any non-COMPATIBLE axis outcome MUST carry
 * a remediationType").
 *
 * @param reason Actionable reason text (§1 — "without hiding degradation"). This constructor
 *   only checks non-blank; a placeholder like "x" satisfies that structurally but is not
 *   actionable — see fixtures/loops/loop-compatibility-report/adversarial/placeholder-non-
 *   actionable-reason.adversarial.json for the case this leaves undetected.
 */
data class AxisOutcome(
    val axis: CompatibilityAxis,
    val outcome: CompatibilityOutcome,
    val reason: String,
    val remediationType: RemediationType? = null,
    val findings: List<AxisFinding> = emptyList()
) {
    init {
        require(reason.isNotBlank()) { "AxisOutcome.reason must be non-blank." }
        if (outcome != CompatibilityOutcome.COMPATIBLE) {
            requireNotNull(remediationType) {
                "AxisOutcome.remediationType is required when outcome is not COMPATIBLE " +
                    "(LOOP_COMPATIBILITY_CONTRACT.md §11 — got outcome=$outcome for axis=$axis " +
                    "with no remediationType)."
            }
        }
    }
}

// =========================================================================================
// CacheKey — LOOP_COMPATIBILITY_CONTRACT.md §10's exact six-field cache key tuple
// =========================================================================================

/**
 * §10: "A compatibility report MUST be cached under a key formed from exactly this tuple." A
 * cache invalidates (CACHED_VALID -> CACHED_STALE) when any one of these six fields changes on
 * the installing phone; regardless of cache validity, a fresh evaluation is required
 * immediately before any EXECUTE_DESTRUCTIVE-or-above or PUBLISH_OR_RELEASE-class execution
 * (§10's unconditional MUST_REVALIDATE row) — that trigger is not itself a field of this type,
 * since it is a property of the ACTION about to run, not of the cached report.
 *
 * @param deviceOsProfile MUST NOT be a value capable of re-identifying one specific physical
 *   unit (§8's DeviceIdentity.family/board-not-usbDescriptor discipline, applied here by the
 *   same reasoning) — not enforced by this constructor; see fixtures/loops/loop-compatibility-
 *   report/adversarial/device-profile-looks-reidentifying.adversarial.json.
 * @param distributionFlavor Reuses [DistFlavor] (LoopDefinitionContracts.kt, same package)
 *   rather than redefining a third FULL/PLAY-shaped enum in this package.
 */
data class CacheKey(
    val packageDigest: IntegrityRef,
    val engineVersion: String,
    val capabilityInventoryDigest: IntegrityRef,
    val policyDigest: IntegrityRef,
    val deviceOsProfile: String,
    val distributionFlavor: DistFlavor
) {
    init {
        require(engineVersion.matches(Regex("^\\d+\\.\\d+\\.\\d+$"))) {
            "CacheKey.engineVersion must be a plain SemVer string (got '$engineVersion')."
        }
        require(deviceOsProfile.isNotBlank()) { "CacheKey.deviceOsProfile must be non-blank." }
    }
}

// =========================================================================================
// LoopCompatibilityReport — schemas/loops/loop-compatibility-report.schema.json
// =========================================================================================

/**
 * The phone-authoritative evaluation of whether a specific installing phone can understand,
 * install, bind, and run a specific LoopPackage release, across all ten [CompatibilityAxis]
 * values (LOOP_COMPATIBILITY_CONTRACT.md §1-§11).
 *
 * @param phoneAuthoritative Pinned to `true` by construction (FB-RAT-CMP-005) — there is no
 *   representable false state in this type, mirroring [dev.aarso.contracts.loops.TransferEnvelope
 *   .initiatedByUser]'s same pattern: a marketplace- or browser-produced PREVIEW is a
 *   genuinely different, unmodeled shape (§6 — it MUST be labeled a preview and MUST NOT be
 *   cached or reused as if authoritative), never a false value of this field.
 * @param axisOutcomes MUST carry exactly one entry per [CompatibilityAxis] value (ten total,
 *   enforced by `init` below — the same "no fewer, no duplicates" guarantee
 *   loop-compatibility-report.schema.json's minItems=maxItems=10-plus-ten-`contains`-clauses
 *   combination encodes structurally in JSON Schema).
 * @param overallOutcome MUST equal `CompatibilityOutcome.worstOf(axisOutcomes.map { it.outcome
 *   })` — enforced by `init` below, not left to the caller to get right.
 */
data class LoopCompatibilityReport(
    val reportId: String,
    val loopId: String,
    val semanticVersion: String,
    val packageDigest: IntegrityRef,
    val axisOutcomes: List<AxisOutcome>,
    val overallOutcome: CompatibilityOutcome,
    val cacheKey: CacheKey,
    val evaluatedAtUtc: Instant,
    val schemaVersion: String = "1.0.0",
    val producer: dev.aarso.contracts.common.ProducerRef? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    /** Always true — see the class doc; there is no constructor parameter for this because there is no representable false state. */
    val phoneAuthoritative: Boolean = true

    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "LoopCompatibilityReport.schemaVersion must be major version 1 (got '$schemaVersion')."
        }
        require(reportId.isNotBlank()) { "LoopCompatibilityReport.reportId must be non-blank." }
        require(loopId.isNotBlank()) { "LoopCompatibilityReport.loopId must be non-blank." }

        val axesPresent = axisOutcomes.map { it.axis }
        require(axesPresent.size == CompatibilityAxis.entries.size && axesPresent.toSet() == CompatibilityAxis.entries.toSet()) {
            "LoopCompatibilityReport.axisOutcomes must carry exactly one entry per CompatibilityAxis " +
                "value (${CompatibilityAxis.entries.size} total) — got ${axisOutcomes.size} entries " +
                "covering axes $axesPresent."
        }

        val expectedOverall = CompatibilityOutcome.worstOf(axisOutcomes.map { it.outcome })
        require(overallOutcome == expectedOverall) {
            "LoopCompatibilityReport.overallOutcome must be $expectedOverall given axisOutcomes " +
                "(LOOP_COMPATIBILITY_CONTRACT.md §3's strict precedence order; got $overallOutcome)."
        }
    }
}
