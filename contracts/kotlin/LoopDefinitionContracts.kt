// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// LoopDefinitionContracts.kt — the "loops" domain's canonical LoopDefinition wire-shape data
// classes: identity, node/gateway/edge graph, budgets, binding slots, capability requests,
// execution-target/distribution constraints, engine compatibility range, verification policy,
// terminal-state mapping, provenance, and license.
//
// Mirrors, field-for-field, schemas/loops/loop-definition.v2.schema.json (the FIRST
// LoopDefinition schema this repository has ever emitted — no v1 exists anywhere under
// schemas/, matching WP-1's correct decision to skip it). Node/gateway/edge shapes live in
// THIS SAME FILE, not split into separate files, mirroring that schema's own $defs-in-one-file
// structure (the semantic/package digest covers a LoopDefinition as one unit — semantic-digest
// .v1.json). Normative grounding is docs/ratified/loops/LOOP_ENGINEERING_SPEC_V2.1.md
// (§2.2/§3 LoopDefinition, §4 node contracts + Amendment 1's eight categories, §5 gateway
// contracts, §16 verification, §17 subloops, §18 budgets) plus the byte-level frozen registries
// under schemas/loops/registries/ (floop-container-format.v1, semantic-digest.v1,
// canonicalization.v1, loop-validation-rules.v1, capability-ids.v1,
// model-capability-vocabulary.v1) — NOT redefined here; a conflict between this file and a
// registry is this file's error, the registry wins, per LOOP_FROZEN_CONCEPTS_WP1L_G0.md.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant only. No kotlinx-serialization, no kotlinx-datetime, no Android
// imports, no kotlinx-coroutines Flow (this work package does not ask for a provider interface
// for this domain, matching contracts/kotlin/AuthorityContracts.kt's precedent for a
// data-shapes-only domain rather than a streaming-provider domain like ExecutionContracts.kt).
// This file's small shared sub-shapes (AuthorityRung, ExecutionTargetType) are duplicated
// locally rather than imported from dev.aarso.contracts.authority/execution — this repo's own
// convention, established at the JSON Schema layer (schemas/loops/loop-definition.v2.schema.json
// duplicates the same two enums locally rather than cross-file $ref-ing authority/grant.schema
// .json or execution/target.schema.json) and carried through here for the same reason: only
// dev.aarso.contracts.common is a sanctioned cross-domain import for this work package.
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build environment —
// this file has been written carefully (balanced braces, matched types, no typos attempted) but
// has NOT been compiled. Do not report it as compiling; that is for the next session with
// Gradle available to confirm. This file depends on contracts/kotlin/CommonContracts.kt (package
// dev.aarso.contracts.common) being compiled in the same module/source set, and
// contracts/kotlin/LoopAuthoringContracts.kt (same package as this file, dev.aarso.contracts
// .loops) reaches back into several types declared here (AuthorityRung, ExecutionTargetType,
// TerminalRunState, LicenseRef) via ordinary same-package visibility, no import needed.
//
// Why sealed interfaces appear below (SchemaReference, ImplementationReference, Compensation)
// where schemas/loops/loop-definition.v2.schema.json instead used `oneOf`/`if`-`then`: the same
// rationale contracts/kotlin/AuthorityContracts.kt's header gives for AuthorityDecision — a
// sealed interface with per-variant data classes is a COMPILE-TIME-checked guarantee a JSON
// Schema `if`/`then` conditional cannot match. [Compensation] is the sharpest example: the JSON
// Schema can only require a `nonCompensabilityAcknowledged` field be `true` when `compensable`
// is `false` (a runtime check on an omittable boolean); the sealed-interface encoding below makes
// it IMPOSSIBLE to construct a "not compensable, not acknowledged" `Compensation` value at all —
// there is no constructor path to it. This is strictly stronger than LOOP-VERIFY-001's schema
// operationalization, not merely a restatement of it.

package dev.aarso.contracts.loops

import java.time.Instant

// =========================================================================================
// Shared enums and validation patterns
// =========================================================================================

private val CAPABILITY_ID_PATTERN = Regex("^fb\\.[a-z0-9_]+(\\.[a-z0-9_]+)+$")
private val SEMVER_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z-.]+)?(\\+[0-9A-Za-z-.]+)?$")
private val ENGINE_VERSION_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+$")
private val SEMANTIC_DIGEST_PATTERN = Regex("^sha256:[0-9a-f]{64}\$")

/** §4 Amendment 1: the eight node categories — the base pack's original five (renamed/clarified, not changed in kind) plus three genuinely new ones (VERIFIER, ARTIFACT_IMPORT_EXPORT, WAIT_EVENT_GATE). */
enum class NodeCategory {
    DETERMINISTIC_TRANSFORMATION, MODEL_INFERENCE, TYPED_TOOL, HUMAN_DECISION,
    BOUNDED_SUBLOOP, VERIFIER, ARTIFACT_IMPORT_EXPORT, WAIT_EVENT_GATE
}

/** capability-ids.v1.json's authorityLadder, duplicated locally (mirrors contracts/kotlin/AuthorityContracts.kt's own AuthorityRung — a deliberate parallel definition, not an import, per this file's header note). */
enum class AuthorityRung {
    OBSERVE, READ, PROPOSE, MODIFY_DRAFT, EXECUTE_REVERSIBLE, EXECUTE_EXTERNAL, EXECUTE_DESTRUCTIVE, PUBLISH_OR_RELEASE
}

/** schemas/execution/target.schema.json's ExecutionTargetType (FB-RAT-EXE-010), duplicated locally. */
enum class ExecutionTargetType { LOCAL_ANDROID, SSH_HOST, RASPBERRY_PI, GITHUB_ACTIONS, GITEA_ACTIONS }

/**
 * §9's eight terminal run states, duplicated locally for definition-time references (the full
 * 17-state machine with its nine non-terminal states belongs to a run/receipt contract —
 * LoopRuntimeContracts.kt, forward-pointed by LOOP_ENGINEERING_SPEC_V2.1's cross-references,
 * not written by this work package).
 */
enum class TerminalRunState {
    SUCCEEDED_VERIFIED, SUCCEEDED_UNVERIFIED, STOPPED_BUDGET, STOPPED_POLICY,
    FAILED_SAFE, FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN, CANCELLED;

    companion object {
        /** The six terminal states that are FAILURES — the closed set a node's own failure-to-terminal-state mapping (§4) may resolve to. Mirrors ExecutionState.TERMINAL's companion pattern in contracts/kotlin/ExecutionContracts.kt. */
        val FAILURE_STATES: Set<TerminalRunState> = setOf(
            STOPPED_BUDGET, STOPPED_POLICY, FAILED_SAFE, FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN, CANCELLED
        )
    }
}

enum class IdempotencyMode { PURE, IDEMPOTENT_WITH_KEY, NOT_IDEMPOTENT }

enum class SideEffectClass { NONE, REVERSIBLE_LOCAL, PERSISTENT_LOCAL, EXTERNAL_VISIBLE, DESTRUCTIVE }

enum class TimeoutAction { FAIL_SAFE, ESCALATE_TO_USER, RETRY }

enum class BackoffStrategy { NONE, FIXED, EXPONENTIAL }

/** CLAUDE.md's `dist` dimension. 'PLAY' excludes overlay/screen-capture/USB-host. */
enum class DistFlavor(val wireValue: String) {
    FULL("full"), PLAY("play");

    companion object {
        fun fromWireValue(value: String): DistFlavor =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown DistFlavor wire value: $value")
    }
}

/** §2.6's exact seven binding-target kinds ("models, repositories, execution targets, devices, secrets, policies, and budgets"). */
enum class BindingSlotKind { MODEL, REPOSITORY, EXECUTION_TARGET, DEVICE, SECRET, POLICY, BUDGET }

// =========================================================================================
// SchemaReference — a typed input/output/port JSON Schema, inline or by package path
// =========================================================================================

sealed interface SchemaReference {
    /** An embedded JSON Schema draft 2020-12 (or later ratified version) document. Not itself validated as a meta-schema by this constructor — LOOP-SCHEMA-002 is a validator-time obligation. */
    data class Inline(val schema: Map<String, Any?>) : SchemaReference

    /** Package-relative path under schemas/ (floop-container-format.v1.json). */
    data class PackageRelativePath(val refPath: String) : SchemaReference {
        init { require(refPath.isNotBlank()) { "SchemaReference.PackageRelativePath.refPath must be non-blank." } }
    }
}

data class Port(
    val portId: String,
    /** Null means untyped/any — MUST NOT be used for a port carrying a value later relied on by a downstream typed schema without an explicit adapter node. */
    val schemaRef: SchemaReference? = null,
    val mediaType: String? = null,
    val description: String? = null
) {
    init { require(portId.isNotBlank()) { "Port.portId must be non-blank." } }
}

// =========================================================================================
// ImplementationReference — §4 "implementation reference or binding slot"
// =========================================================================================

sealed interface ImplementationReference {
    data class CapabilityBinding(val capabilityId: String) : ImplementationReference {
        init { require(CAPABILITY_ID_PATTERN.matches(capabilityId)) { "CapabilityBinding.capabilityId must be a reverse-DNS fb.* ID (got '$capabilityId')." } }
    }

    data class TypedSlot(val slotId: String) : ImplementationReference {
        init { require(slotId.isNotBlank()) { "TypedSlot.slotId must be non-blank." } }
    }

    /** §17: subloop embedded as a package-local definition. */
    data class EmbeddedSubloop(val subloopDefinitionRef: String) : ImplementationReference {
        init { require(subloopDefinitionRef.isNotBlank()) { "EmbeddedSubloop.subloopDefinitionRef must be non-blank." } }
    }

    /** §17: subloop referenced by immutable package identity. */
    data class ReferencedSubloop(
        val referencedLoopId: String,
        val referencedSemanticVersion: String,
        val referencedPackageDigest: String
    ) : ImplementationReference {
        init {
            require(referencedLoopId.isNotBlank()) { "ReferencedSubloop.referencedLoopId must be non-blank." }
            require(referencedSemanticVersion.isNotBlank()) { "ReferencedSubloop.referencedSemanticVersion must be non-blank." }
            require(SEMANTIC_DIGEST_PATTERN.matches(referencedPackageDigest)) { "ReferencedSubloop.referencedPackageDigest must match 'sha256:<64 lowercase hex>' (got '$referencedPackageDigest')." }
        }
    }
}

// =========================================================================================
// Timeout / retry / compensation / verification
// =========================================================================================

data class TimeoutPolicy(val timeoutSeconds: Double?, val onTimeout: TimeoutAction) {
    init { timeoutSeconds?.let { require(it >= 0) { "TimeoutPolicy.timeoutSeconds must be >= 0 when present." } } }
}

data class RetryPolicy(
    val maxRetries: Int,
    val backoff: BackoffStrategy,
    val baseDelaySeconds: Double? = null,
    val retryableFailureClasses: List<String> = emptyList()
) {
    init {
        require(maxRetries >= 0) { "RetryPolicy.maxRetries must be >= 0." }
        baseDelaySeconds?.let { require(it >= 0) { "RetryPolicy.baseDelaySeconds must be >= 0 when present." } }
    }
}

/**
 * §4 "compensation behavior or explicit non-compensability flag". See this file's header note
 * for why this sealed interface is a strictly stronger encoding of LOOP-VERIFY-001 than the JSON
 * Schema counterpart's if/then can express: there is no constructor path to a "not compensable,
 * not acknowledged" value.
 */
sealed interface Compensation {
    data class Compensable(val description: String) : Compensation {
        init { require(description.isNotBlank()) { "Compensation.Compensable.description must be non-blank." } }
    }

    /** The sole way to represent "not compensable" — its mere existence as a constructed value IS the explicit acknowledgement LOOP-VERIFY-001 requires. */
    data object NonCompensableAcknowledged : Compensation
}

data class VerificationRequirements(val requiredForSuccess: Boolean, val verifierIds: List<String> = emptyList())

// =========================================================================================
// NodeDefinition — §4
// =========================================================================================

/**
 * §4: every node's full contract. Structurally operationalizes, via `init{}` (mirroring
 * schemas/loops/loop-definition.v2.schema.json's `allOf`/`if`/`then` blocks):
 *  - LOOP-BUDGET-002 (§17): `category == BOUNDED_SUBLOOP` requires a non-null, `>= 1`
 *    `maxRecursionDepth`, and an [ImplementationReference.EmbeddedSubloop] or
 *    [ImplementationReference.ReferencedSubloop] implementation.
 *  - `idempotencyMode == IDEMPOTENT_WITH_KEY` requires a non-blank `idempotencyKeyStrategy`.
 *  - `category == WAIT_EVENT_GATE` requires a non-null `timeoutPolicy.timeoutSeconds` (an
 *    unbounded wait is an unbounded cycle in disguise — same principle as §17's cycle-bound
 *    requirement).
 *  - `onFailureTerminalState` MUST be one of [TerminalRunState.FAILURE_STATES].
 *
 * What this constructor CANNOT verify (same limitation as the JSON Schema counterpart, and for
 * the same reason — these are graph/cross-object properties, not single-object shape): that
 * `nodeId` is unique across a LoopDefinition's `nodes` list (LOOP-ID-001), or that this node is
 * reachable from `LoopDefinition.startNodeId` (LOOP-GRAPH-002). See
 * fixtures/loops/loop-definition.v2/adversarial/ for the JSON-level demonstration of both gaps.
 */
data class NodeDefinition(
    val nodeId: String,
    val category: NodeCategory,
    val inputPorts: List<Port>,
    val outputPorts: List<Port>,
    val implementation: ImplementationReference,
    val requestedCapabilities: List<String>,
    val authorityClass: AuthorityRung,
    val preferredExecutionTargets: List<ExecutionTargetType>,
    val permittedExecutionTargets: List<ExecutionTargetType>,
    val timeoutPolicy: TimeoutPolicy,
    val retryPolicy: RetryPolicy,
    val idempotencyMode: IdempotencyMode,
    val compensation: Compensation,
    val verificationRequirements: VerificationRequirements,
    val sideEffectClass: SideEffectClass,
    val onFailureTerminalState: TerminalRunState,
    val explanation: String,
    val idempotencyKeyStrategy: String? = null,
    val maxRecursionDepth: Int? = null,
    /** Only meaningful when category=MODEL_INFERENCE — tag IDs from model-capability-vocabulary.v1.json. Not a closed Kotlin enum since that registry's tags array is additive; validated only as non-blank strings here. */
    val requestedModelCapabilityTags: List<String> = emptyList(),
    /** Excluded from semanticDigest by key name (semantic-digest.v1.json exclusionListV1). */
    val presentation: Map<String, Any?> = emptyMap(),
    val notes: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(nodeId.isNotBlank()) { "NodeDefinition.nodeId must be non-blank (FB-RAT-COM-002)." }
        require(requestedCapabilities.all { CAPABILITY_ID_PATTERN.matches(it) }) {
            "NodeDefinition.requestedCapabilities must all be reverse-DNS fb.* IDs — got $requestedCapabilities."
        }
        require(permittedExecutionTargets.isNotEmpty()) { "NodeDefinition.permittedExecutionTargets must be non-empty." }
        require(explanation.isNotBlank()) { "NodeDefinition.explanation must be non-blank (§4 'human-readable explanation')." }
        require(onFailureTerminalState in TerminalRunState.FAILURE_STATES) {
            "NodeDefinition.onFailureTerminalState must be one of TerminalRunState.FAILURE_STATES, got $onFailureTerminalState."
        }
        if (category == NodeCategory.BOUNDED_SUBLOOP) {
            requireNotNull(maxRecursionDepth) { "NodeDefinition: category=BOUNDED_SUBLOOP requires a non-null maxRecursionDepth (§17, LOOP-BUDGET-002)." }
            require(maxRecursionDepth >= 1) { "NodeDefinition.maxRecursionDepth must be >= 1 when present." }
            require(implementation is ImplementationReference.EmbeddedSubloop || implementation is ImplementationReference.ReferencedSubloop) {
                "NodeDefinition: category=BOUNDED_SUBLOOP requires an EmbeddedSubloop or ReferencedSubloop implementation, got $implementation."
            }
        }
        if (idempotencyMode == IdempotencyMode.IDEMPOTENT_WITH_KEY) {
            require(!idempotencyKeyStrategy.isNullOrBlank()) {
                "NodeDefinition: idempotencyMode=IDEMPOTENT_WITH_KEY requires a non-blank idempotencyKeyStrategy."
            }
        }
        if (category == NodeCategory.WAIT_EVENT_GATE) {
            requireNotNull(timeoutPolicy.timeoutSeconds) {
                "NodeDefinition: category=WAIT_EVENT_GATE requires a non-null timeoutPolicy.timeoutSeconds (a statically visible bound, same principle as §17's cycle-bound requirement)."
            }
        }
    }
}

// =========================================================================================
// GatewayDefinition / EdgeDefinition — §5
// =========================================================================================

data class GatewayOutcome(
    val label: String,
    val targetNodeId: String,
    /** Null marks this outcome as the default/else branch. */
    val conditionExpression: String? = null,
    val priority: Int? = null
) {
    init {
        require(label.isNotBlank()) { "GatewayOutcome.label must be non-blank (§5 'stable edge labels')." }
        require(targetNodeId.isNotBlank()) { "GatewayOutcome.targetNodeId must be non-blank." }
    }
}

/**
 * §5 (FB-RAT-LOOP-003). Structurally operationalizes LOOP-GRAPH-003: `exhaustive == false`
 * requires a non-null `defaultOrTerminalFailurePath`.
 */
data class GatewayDefinition(
    val gatewayId: String,
    val outcomes: List<GatewayOutcome>,
    val exhaustive: Boolean,
    val defaultOrTerminalFailurePath: GatewayOutcome? = null
) {
    init {
        require(gatewayId.isNotBlank()) { "GatewayDefinition.gatewayId must be non-blank." }
        require(outcomes.isNotEmpty()) { "GatewayDefinition.outcomes must be non-empty." }
        if (!exhaustive) {
            requireNotNull(defaultOrTerminalFailurePath) {
                "GatewayDefinition: exhaustive=false requires a non-null defaultOrTerminalFailurePath (LOOP-GRAPH-003)."
            }
        }
    }
}

data class EdgeDefinition(
    val edgeId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val label: String? = null,
    /** Non-null when this edge realizes one of a gateway's declared outcomes (§5). */
    val gatewayId: String? = null
) {
    init {
        require(edgeId.isNotBlank()) { "EdgeDefinition.edgeId must be non-blank." }
        require(fromNodeId.isNotBlank()) { "EdgeDefinition.fromNodeId must be non-blank." }
        require(toNodeId.isNotBlank()) { "EdgeDefinition.toNodeId must be non-blank." }
    }
}

// =========================================================================================
// Budgets — §18
// =========================================================================================

data class BudgetCost(val amount: Double, val currency: String) {
    init {
        require(amount >= 0) { "BudgetCost.amount must be >= 0." }
        require(Regex("^[A-Z]{3}\$").matches(currency)) { "BudgetCost.currency must be an ISO 4217 code (got '$currency')." }
    }
}

/**
 * §18: "Minimum budget dimensions are steps, wall clock, model tokens, monetary cost, and tool
 * calls." Hard runtime boundaries, not estimates. `unknownBudgetDimensions` preserves fields a
 * decoder does not recognize, treated conservatively by runtimes that cannot enforce them.
 */
data class Budgets(
    val steps: Int,
    val wallClockSeconds: Double,
    val tokens: Int,
    val cost: BudgetCost,
    val toolCalls: Int,
    val memoryBytes: Long? = null,
    val thermalPolicyRef: String? = null,
    val storageBytes: Long? = null,
    val networkBytesTotal: Long? = null,
    val deviceOperations: Int? = null,
    val unknownBudgetDimensions: Map<String, Any?> = emptyMap()
) {
    init {
        require(steps >= 1) { "Budgets.steps must be >= 1." }
        require(wallClockSeconds >= 0) { "Budgets.wallClockSeconds must be >= 0." }
        require(tokens >= 0) { "Budgets.tokens must be >= 0." }
        require(toolCalls >= 0) { "Budgets.toolCalls must be >= 0." }
        memoryBytes?.let { require(it >= 0) { "Budgets.memoryBytes must be >= 0 when present." } }
        storageBytes?.let { require(it >= 0) { "Budgets.storageBytes must be >= 0 when present." } }
        networkBytesTotal?.let { require(it >= 0) { "Budgets.networkBytesTotal must be >= 0 when present." } }
        deviceOperations?.let { require(it >= 0) { "Budgets.deviceOperations must be >= 0 when present." } }
    }
}

// =========================================================================================
// BindingSlot — §2.6
// =========================================================================================

data class BindingSlot(
    val slotId: String,
    val kind: BindingSlotKind,
    val required: Boolean,
    val description: String? = null,
    val constraints: Map<String, Any?> = emptyMap()
) {
    init { require(slotId.isNotBlank()) { "BindingSlot.slotId must be non-blank." } }
}

// =========================================================================================
// Execution-target / distribution constraints — LOOP_COMPATIBILITY_CONTRACT.md axes 6/7
// =========================================================================================

data class ExecutionTargetConstraints(
    val permittedTargetTypes: List<ExecutionTargetType>,
    val preferredTargetTypes: List<ExecutionTargetType> = emptyList(),
    /** True forces LOCAL_ANDROID/ConnectivityKind.ALWAYS_ON_LOCAL — no intermittent or remote target may satisfy this definition. */
    val requiresAlwaysOnLocal: Boolean = false
) {
    init { require(permittedTargetTypes.isNotEmpty()) { "ExecutionTargetConstraints.permittedTargetTypes must be non-empty." } }
}

data class DistributionConstraints(
    val allowedDistFlavors: List<DistFlavor> = listOf(DistFlavor.FULL, DistFlavor.PLAY),
    val minAndroidApiLevel: Int? = null,
    val requiredAbis: List<String> = listOf("arm64-v8a")
) {
    init {
        require(allowedDistFlavors.isNotEmpty()) { "DistributionConstraints.allowedDistFlavors must be non-empty." }
        minAndroidApiLevel?.let { require(it >= 1) { "DistributionConstraints.minAndroidApiLevel must be >= 1 when present." } }
    }
}

// =========================================================================================
// Verification policy / terminal-state mapping — §16, §9
// =========================================================================================

data class VerifierRef(
    val verifierId: String,
    val requiredForSuccess: Boolean,
    val appliesToNodeIds: List<String> = emptyList(),
    val description: String? = null
) {
    init { require(verifierId.isNotBlank()) { "VerifierRef.verifierId must be non-blank." } }
}

data class VerificationPolicy(val requireVerificationForSuccess: Boolean, val verifiers: List<VerifierRef> = emptyList())

data class NodeFailureEscalation(val nodeId: String, val onFailure: TerminalRunState) {
    init {
        require(nodeId.isNotBlank()) { "NodeFailureEscalation.nodeId must be non-blank." }
        require(onFailure in TerminalRunState.FAILURE_STATES) { "NodeFailureEscalation.onFailure must be one of TerminalRunState.FAILURE_STATES, got $onFailure." }
    }
}

/**
 * Definition-level index of every node's own `onFailureTerminalState` (§4), so a runtime does
 * not have to walk the full `nodes` list for a run-level summary. A validator MUST cross-check
 * this against each [NodeDefinition.onFailureTerminalState] for consistency — this constructor
 * does not itself enforce that cross-list agreement (same limitation as the JSON Schema
 * counterpart).
 */
data class TerminalStateMapping(val unverifiedSuccessAllowed: Boolean, val nodeFailureEscalations: List<NodeFailureEscalation> = emptyList())

// =========================================================================================
// Provenance / license
// =========================================================================================

/** FB-RAT-COM-008 provenance minimum, duplicated inline per this repo's convention (mirrors contracts/kotlin/ExecutionContracts.kt's ExecutionProvenance) rather than imported across a non-common domain boundary. */
data class LoopProvenance(
    val sourceLocation: String,
    val projectRevision: String,
    val initiatingPrincipal: String,
    val evidenceLinks: List<String> = emptyList()
) {
    init {
        require(sourceLocation.isNotBlank()) { "LoopProvenance.sourceLocation must be non-blank (FB-RAT-COM-008)." }
        require(projectRevision.isNotBlank()) { "LoopProvenance.projectRevision must be non-blank (FB-RAT-COM-008)." }
        require(initiatingPrincipal.isNotBlank()) { "LoopProvenance.initiatingPrincipal must be non-blank (FB-RAT-COM-008)." }
    }
}

/** At least one of [spdxId]/[licenseFileRef]/[customLicenseText] MUST be non-null — feeds LOOP-LINEAGE-* checks (§2.9, LOOP_FORK_LINEAGE_CONTRACT.md). Reused as-is by LoopAuthoringContracts.kt's LicenseChange (same package, no import needed). */
data class LicenseRef(
    val spdxId: String? = null,
    val licenseFileRef: String? = null,
    val customLicenseText: String? = null
) {
    init {
        require(listOfNotNull(spdxId, licenseFileRef, customLicenseText).any { it.isNotBlank() }) {
            "LicenseRef must declare at least one non-blank spdxId, licenseFileRef, or customLicenseText."
        }
    }
}

// =========================================================================================
// LoopDefinition — the root object, §2.2/§3
// =========================================================================================

/**
 * The canonical, semantic graph of a loop (§2.2). Serializable independently of UI layout —
 * `presentation` at any depth is excluded from semanticDigest by semantic-digest.v1.json's
 * exclusionListV1, not enforced by this constructor (exclusion is a digest-computation-time
 * concern, not a construction-time one).
 *
 * What this constructor CANNOT verify (graph/cross-object properties this type alone cannot
 * decide — same limitation as schemas/loops/loop-definition.v2.schema.json, see that file's
 * `$comment` for the full rationale): `nodeId`/`edgeId`/`gatewayId`/`slotId` uniqueness
 * (LOOP-ID-001); that `startNodeId` and every `terminalNodeIds` entry actually name a node in
 * `nodes` (LOOP-GRAPH-001); that every node is reachable from `startNodeId` (LOOP-GRAPH-002);
 * that every cycle implied by `edges`/`gateways` has a statically visible bound
 * (LOOP-GRAPH-004). These remain validator-time obligations.
 */
data class LoopDefinition(
    val loopId: String,
    val semanticVersion: String,
    val objective: String,
    val documentationRef: String?,
    val inputSchema: SchemaReference,
    val outputSchema: SchemaReference,
    val startNodeId: String,
    val terminalNodeIds: List<String>,
    val nodes: List<NodeDefinition>,
    val edges: List<EdgeDefinition>,
    val gateways: List<GatewayDefinition>,
    val budgets: Budgets,
    val bindingSlots: List<BindingSlot>,
    val requiredCapabilities: List<String>,
    val executionTargetConstraints: ExecutionTargetConstraints,
    val distributionConstraints: DistributionConstraints,
    val minEngineVersion: String,
    val maxEngineVersion: String?,
    val verificationPolicy: VerificationPolicy,
    val terminalStateMapping: TerminalStateMapping,
    val provenance: LoopProvenance,
    val licenseRef: LicenseRef,
    val presentation: Map<String, Any?> = emptyMap(),
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(loopId.isNotBlank()) { "LoopDefinition.loopId must be non-blank (FB-RAT-COM-002)." }
        require(SEMVER_PATTERN.matches(semanticVersion)) { "LoopDefinition.semanticVersion must be SemVer (got '$semanticVersion')." }
        require(objective.isNotBlank()) { "LoopDefinition.objective must be non-blank." }
        require(startNodeId.isNotBlank()) { "LoopDefinition.startNodeId must be non-blank." }
        require(terminalNodeIds.isNotEmpty()) { "LoopDefinition.terminalNodeIds must be non-empty." }
        require(nodes.isNotEmpty()) { "LoopDefinition.nodes must be non-empty." }
        require(requiredCapabilities.all { CAPABILITY_ID_PATTERN.matches(it) }) {
            "LoopDefinition.requiredCapabilities must all be reverse-DNS fb.* IDs — got $requiredCapabilities."
        }
        require(ENGINE_VERSION_PATTERN.matches(minEngineVersion)) { "LoopDefinition.minEngineVersion must be MAJOR.MINOR.PATCH (got '$minEngineVersion')." }
        maxEngineVersion?.let {
            require(ENGINE_VERSION_PATTERN.matches(it)) { "LoopDefinition.maxEngineVersion must be MAJOR.MINOR.PATCH when present (got '$it')." }
        }
    }
}
