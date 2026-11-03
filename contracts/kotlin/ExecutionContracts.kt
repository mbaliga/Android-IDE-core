// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
//
// ExecutionContracts.kt — the "execution" domain's shared wire-shape data classes,
// lifecycle state machine, and provider contract interface.
//
// Mirrors, field-for-field, the JSON Schema documents under schemas/execution/*.schema.json:
//   ExecutionTarget    -> target.schema.json
//   ExecutionRequest   -> request.schema.json
//   ExecutionHandle    -> handle.schema.json
//   ExecutionReceipt   -> receipt.schema.json
// plus ArtifactRef, reused as-is from dev.fonebrew.contracts.common (schemas/common/
// artifact-ref.schema.json) — NOT redefined here, per the WP-1 task brief.
// If a field appears in one place, it MUST appear in the other, or the two have drifted
// and one of them is wrong. See docs/ratified/EXECUTION_CONTRACT.md for the citations
// (FB-RAT-STR-004, FB-RAT-EXE-001..011) each field operationalizes.
//
// Toolchain constraint (binding): kotlinc-compilable with NO third-party dependencies —
// stdlib + java.time.Instant only, EXCEPT kotlinx-coroutines Flow for ExecutionProvider.observe()
// (the one named exception in the WP-1 task brief, matching the workspace-kernel domain's own
// exception). No kotlinx-serialization, no kotlinx-datetime, no Android imports.
//
// COMPILATION STATUS: UNVERIFIED. kotlinc/Gradle are not available in this build
// environment — this file has been written carefully (balanced braces, matched types, no
// typos attempted) but has NOT been compiled. Do not report it as compiling; that is for
// the next session with Gradle available to confirm. This file also depends on
// contracts/kotlin/CommonContracts.kt (package dev.fonebrew.contracts.common) being compiled
// in the same module/source set — it is not a standalone-compilable file by itself.
//
// Why a sealed-interface state machine DOES appear in this file (unlike CommonContracts.kt,
// which explicitly does not need one): the execution domain owns real state transitions —
// FB-RAT-EXE-002's twelve-state lifecycle — and per the WP-0 survey, real state machines
// belong to the domains that own actual state. ExecutionLifecycleState below is a sealed
// interface (not a bare enum) precisely so illegal transitions are a COMPILE-TIME concern
// for any code that pattern-matches exhaustively over it, mirroring the encoding style the
// WP-1 task brief specified: "sealed interfaces for state machines... exactly as specified
// in the object models given to you." The wire-facing ExecutionState enum (used inside
// ExecutionHandle, matching handle.schema.json's $defs/ExecutionState) stays a plain enum
// because that is what the JSON Schema `enum` keyword maps onto 1:1 — the sealed interface
// is the in-memory/runtime state-machine encoding a provider implementation actually
// programs against; the enum is the serialized wire projection of the same twelve values.

package dev.fonebrew.contracts.execution

import dev.fonebrew.contracts.common.ArtifactRef
import dev.fonebrew.contracts.common.CapabilityManifest
import dev.fonebrew.contracts.common.ProducerRef
import java.time.Instant
import kotlinx.coroutines.flow.Flow

// =========================================================================================
// ExecutionTarget — schemas/execution/target.schema.json
// =========================================================================================

/** FB-RAT-EXE-010: the closed vocabulary of initial execution providers. */
enum class ExecutionTargetType { LOCAL_ANDROID, SSH_HOST, RASPBERRY_PI, GITHUB_ACTIONS, GITEA_ACTIONS }

/**
 * Host-trust state, mirroring the TOFU pattern already real in core-engine's
 * RemoteSessionDriver/KnownHosts (WP-0 survey §1(h)). CHANGED means a previously-trusted
 * hostFingerprint no longer matches — a provider MUST suspend for a fresh user trust
 * decision, never silently proceed.
 */
enum class TargetTrust { UNKNOWN, TRUST_ON_FIRST_USE, TRUSTED, CHANGED, UNTRUSTED }

/** How a target is reached. ALWAYS_ON_LOCAL is LOCAL_ANDROID only. */
enum class ConnectivityKind { ALWAYS_ON_LOCAL, INTERMITTENT_NETWORK, REQUIRES_EXPLICIT_CONNECT }

data class Connectivity(
    val kind: ConnectivityKind,
    val lastSeenReachableUtc: Instant? = null
)

/** Cost tier. BILLED targets (GITHUB_ACTIONS/GITEA_ACTIONS) can silently exhaust an allowance — see CLAUDE.md 'CI caveat'. */
enum class CostTier { FREE, METERED, BILLED }

data class TargetCost(
    val tier: CostTier,
    val notes: String? = null
)

/**
 * FB-RAT-STR-004: compute provenance permits user-initiated SSH and CI compute only when
 * execution location, revision, data boundary, state, and result are visible.
 */
enum class ProvenanceStyle { LOCAL_DEVICE_DIRECT, USER_INITIATED_REMOTE, CI_PIPELINE }

/**
 * All five fields MUST be true for a provider to be permitted to start work on a target
 * whose provenanceStyle is USER_INITIATED_REMOTE or CI_PIPELINE (FB-RAT-STR-004). A false
 * value is a legitimate, representable state (e.g. mid-handshake) — enforcing "MUST NOT
 * start otherwise" is an Execution Contract provider-gate obligation, not something this
 * data class enforces itself (see docs/ratified/EXECUTION_CONTRACT.md §4 and
 * fixtures/execution/adversarial/target-visibility-contract-incomplete.adversarial.json).
 */
data class VisibilityContract(
    val executionLocationVisible: Boolean,
    val revisionVisible: Boolean,
    val dataBoundaryVisible: Boolean,
    val stateVisible: Boolean,
    val resultVisible: Boolean
) {
    /** True only when every FB-RAT-STR-004 dimension is visible. Convenience for a provider gate — not itself a bypass of the gate's own obligation to check this. */
    fun isComplete(): Boolean =
        executionLocationVisible && revisionVisible && dataBoundaryVisible && stateVisible && resultVisible
}

/**
 * Android foreground-service type this target's on-device orchestration runs under, when it
 * must run as a bounded-duration FGS at all. DATA_SYNC/MEDIA_PROCESSING carry a hard
 * 6h/rolling-24h ceiling with no extension (Service.onTimeout()) and MUST NOT be paired with
 * an open-ended ExecutionRequest (see ExecutionRequest's init{} check below). SPECIAL_USE
 * requires a manifest justification string plus Play review. CONNECTED_DEVICE (no fixed
 * timeout) is for USB/serial device work. NONE is for work that needs no foreground service.
 */
enum class FgsType { DATA_SYNC, MEDIA_PROCESSING, SPECIAL_USE, CONNECTED_DEVICE, NONE }

/**
 * A place execution can run. Typically carried as the `payload` of a
 * dev.fonebrew.contracts.common.ContractEnvelope<ExecutionTarget>. `id` is this object's own
 * FB-RAT-COM-002 stable ID, distinct from the envelope's objectId.
 *
 * @param capabilities Optional CapabilityManifest (reused from
 *   dev.fonebrew.contracts.common.CapabilityManifest, not redefined here), expected to carry
 *   `subjectKind == CapabilitySubjectKind.EXECUTION` when present — this class does not
 *   itself enforce that narrowing (mirrors the schema's allOf/$ref composition, which JSON
 *   Schema enforces structurally but a plain Kotlin property type cannot without a custom
 *   validated wrapper type this domain has not been asked to invent).
 */
data class ExecutionTarget(
    val id: String,
    val type: ExecutionTargetType,
    val displayName: String,
    val trust: TargetTrust,
    val arch: String,
    val connectivity: Connectivity,
    val cost: TargetCost,
    val provenanceStyle: ProvenanceStyle,
    val fgsType: FgsType,
    val hostFingerprint: String? = null,
    val capabilities: CapabilityManifest? = null,
    val visibilityContract: VisibilityContract? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "ExecutionTarget.id must be non-blank (FB-RAT-COM-002)." }
        require(displayName.isNotBlank()) { "ExecutionTarget.displayName must be non-blank." }
        require(arch.isNotBlank()) { "ExecutionTarget.arch must be non-blank." }
        if (provenanceStyle == ProvenanceStyle.USER_INITIATED_REMOTE || provenanceStyle == ProvenanceStyle.CI_PIPELINE) {
            requireNotNull(visibilityContract) {
                "ExecutionTarget.visibilityContract must be non-null when provenanceStyle is " +
                    "USER_INITIATED_REMOTE or CI_PIPELINE (FB-RAT-STR-004)."
            }
        }
    }
}

// =========================================================================================
// ExecutionRequest — schemas/execution/request.schema.json
// =========================================================================================

enum class OperationClass {
    ONE_SHOT_COMMAND, BUILD, AGENT_LOOP, TERMINAL_SESSION, DEVICE_FLASH, FILE_TRANSFER, CI_DISPATCH, CUSTOM
}

data class TypedOperation(
    val operationClass: OperationClass,
    val command: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String? = null
) {
    init {
        require(command.isNotBlank()) { "TypedOperation.command must be non-blank." }
    }
}

data class RequestEnvironment(
    /**
     * Non-secret environment variables ONLY. FB-RAT-EXE-007 / CLAUDE.md rule 5 require any
     * secret-bearing value to be referenced through [ExecutionRequest.secretHandles] instead —
     * see fixtures/execution/adversarial/request-secret-value-in-envvars.adversarial.json for
     * the violation this field's contract forbids (a violation this data class's shape cannot
     * catch on its own, since Kotlin cannot know a String value is a live credential either).
     */
    val envVars: Map<String, String> = emptyMap()
)

/**
 * FB-RAT-EXE-007: purpose-bound secret handle. Deliberately carries NO field capable of
 * holding a secret VALUE — only an opaque handleId into the Keystore-backed secret store
 * (security/KeystoreSecret.kt) plus the purpose it is bound to.
 */
data class SecretHandleRef(
    val handleId: String,
    val purpose: String
) {
    init {
        require(handleId.isNotBlank()) { "SecretHandleRef.handleId must be non-blank." }
        require(purpose.isNotBlank()) { "SecretHandleRef.purpose must be non-blank." }
    }
}

data class SessionReauthorization(
    val maxSessionSeconds: Long,
    val requiresUserReauth: Boolean
) {
    init {
        require(maxSessionSeconds >= 1) { "SessionReauthorization.maxSessionSeconds must be >= 1." }
    }
}

/** EXPERIMENTAL — see docs/non_ratified/EXPERIMENTAL_DECISIONS.md (owned by another domain). Null unless that doc's thermal-policy entry applies. */
data class ThermalPolicyRef(val ref: String)

data class ExecutionBudget(
    val sessionReauthorization: SessionReauthorization,
    /** FB-RAT-EXE-006 P0-normative half: the provider MUST be able to report thermal state for the duration of this request. Defaults true. */
    val thermalStateObservabilityRequired: Boolean = true,
    val wallClockSeconds: Long? = null,
    val maxOutputBytes: Long? = null,
    /** EXPERIMENTAL, not ratified — FB-RAT-EXE-006's pause/offload POLICY half. Null is the only ratified default; a non-null value MUST NOT be treated as binding. */
    val thermalPausePolicyRef: ThermalPolicyRef? = null
) {
    init {
        wallClockSeconds?.let { require(it >= 1) { "ExecutionBudget.wallClockSeconds must be >= 1 when present." } }
        maxOutputBytes?.let { require(it >= 0) { "ExecutionBudget.maxOutputBytes must be >= 0 when present." } }
    }
}

/**
 * Minimal reference into a grant issued by the Execution Contract + Authority engine
 * (WP-0 survey (c) — a separate, create-new domain not detailed by this file's object
 * model). This is the smallest shape the execution domain needs so a request cannot be
 * modeled at all without an authority reference (FB-RAT-COM-012 "no contract bypass") — the
 * full grant/capability-ladder shape belongs to that other domain and is not invented here.
 */
data class AuthorityGrantRef(
    val grantId: String,
    val scopes: List<String>
) {
    init {
        require(grantId.isNotBlank()) { "AuthorityGrantRef.grantId must be non-blank." }
        require(scopes.isNotEmpty()) { "AuthorityGrantRef.scopes must be non-empty." }
    }
}

data class ExpectedOutput(
    val role: String,
    val mediaType: String
) {
    init {
        require(role.isNotBlank()) { "ExpectedOutput.role must be non-blank." }
        require(mediaType.isNotBlank()) { "ExpectedOutput.mediaType must be non-blank." }
    }
}

/** FB-RAT-EXE-008: what a provider MUST do on observing a retried idempotencyKey it has already seen. */
enum class ExternalDuplicateBehavior { IGNORE_DUPLICATE, RETURN_PRIOR_RESULT, REJECT_DUPLICATE }

/**
 * A request to run a typed operation against a specific ExecutionTarget (by id — not
 * embedded). Typically carried as the `payload` of a
 * dev.fonebrew.contracts.common.ContractEnvelope<ExecutionRequest>.
 *
 * Structurally operationalizes (via the init{} checks below, mirroring
 * schemas/execution/request.schema.json's allOf/if/then blocks):
 *  - Platform grounding: `openEnded == true` MUST NOT pair with `fgsType` DATA_SYNC or
 *    MEDIA_PROCESSING (Service.onTimeout() would kill open-ended work with no extension).
 *  - FB-RAT-EXE-008: `sideEffectExternal == true` MUST carry a non-blank `idempotencyKey`
 *    and a non-null `externalDuplicateBehavior`.
 *  - Platform grounding: `fgsType == SPECIAL_USE` MUST carry a non-blank `specialUseJustification`.
 */
data class ExecutionRequest(
    val id: String,
    val targetId: String,
    val operation: TypedOperation,
    val workingRevision: String?,
    val environment: RequestEnvironment,
    val secretHandles: List<SecretHandleRef>,
    val budget: ExecutionBudget,
    val authorityGrant: AuthorityGrantRef,
    val expectedOutputs: List<ExpectedOutput>,
    val idempotencyKey: String?,
    val sideEffectExternal: Boolean,
    val fgsType: FgsType,
    val openEnded: Boolean,
    val externalDuplicateBehavior: ExternalDuplicateBehavior? = null,
    val specialUseJustification: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(id.isNotBlank()) { "ExecutionRequest.id must be non-blank (FB-RAT-COM-002)." }
        require(targetId.isNotBlank()) { "ExecutionRequest.targetId must be non-blank." }
        if (openEnded) {
            require(fgsType != FgsType.DATA_SYNC && fgsType != FgsType.MEDIA_PROCESSING) {
                "ExecutionRequest: openEnded=true MUST NOT declare fgsType DATA_SYNC or " +
                    "MEDIA_PROCESSING (hard 6h/rolling-24h ceiling, no extension) — got $fgsType."
            }
        }
        if (sideEffectExternal) {
            require(!idempotencyKey.isNullOrBlank()) {
                "ExecutionRequest: sideEffectExternal=true requires a non-blank idempotencyKey (FB-RAT-EXE-008)."
            }
            requireNotNull(externalDuplicateBehavior) {
                "ExecutionRequest: sideEffectExternal=true requires a non-null externalDuplicateBehavior (FB-RAT-EXE-008)."
            }
        }
        if (fgsType == FgsType.SPECIAL_USE) {
            require(!specialUseJustification.isNullOrBlank()) {
                "ExecutionRequest: fgsType=SPECIAL_USE requires a non-blank specialUseJustification."
            }
        }
    }
}

// =========================================================================================
// ExecutionHandle — schemas/execution/handle.schema.json
// =========================================================================================

/**
 * FB-RAT-EXE-002 lifecycle, wire-facing enum form (mirrors handle.schema.json's
 * $defs/ExecutionState 1:1). See [ExecutionLifecycleState] below for the sealed-interface
 * runtime encoding of the same twelve values, and docs/ratified/EXECUTION_CONTRACT.md §2 for
 * the full from-state/trigger/to-state/receipt table.
 */
enum class ExecutionState {
    QUEUED, PREPARING, RUNNING, WAITING_USER, SUSPENDED, VERIFYING,
    SUCCEEDED, SUCCEEDED_UNVERIFIED,
    FAILED_SAFE, FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN, CANCELLED;

    val isTerminal: Boolean
        get() = this in TERMINAL

    companion object {
        val TERMINAL: Set<ExecutionState> = setOf(
            SUCCEEDED, SUCCEEDED_UNVERIFIED, FAILED_SAFE, FAILED_SIDE_EFFECTS_POSSIBLE,
            TARGET_STATE_UNKNOWN, CANCELLED
        )
    }
}

/**
 * Sealed-interface runtime encoding of the FB-RAT-EXE-002 lifecycle — the in-memory state
 * machine a provider implementation programs against, so an exhaustive `when` over this
 * type is a compile-time-checked guarantee that every lifecycle state is handled. This is
 * the type CommonContracts.kt's header comment explains this domain needs and the
 * common/foundations domain deliberately does not provide.
 */
sealed interface ExecutionLifecycleState {
    val wireState: ExecutionState

    data object Queued : ExecutionLifecycleState { override val wireState = ExecutionState.QUEUED }
    data object Preparing : ExecutionLifecycleState { override val wireState = ExecutionState.PREPARING }
    data object Running : ExecutionLifecycleState { override val wireState = ExecutionState.RUNNING }
    data object WaitingUser : ExecutionLifecycleState { override val wireState = ExecutionState.WAITING_USER }
    data object Suspended : ExecutionLifecycleState { override val wireState = ExecutionState.SUSPENDED }
    data object Verifying : ExecutionLifecycleState { override val wireState = ExecutionState.VERIFYING }

    sealed interface Terminal : ExecutionLifecycleState

    data object Succeeded : Terminal { override val wireState = ExecutionState.SUCCEEDED }
    data object SucceededUnverified : Terminal { override val wireState = ExecutionState.SUCCEEDED_UNVERIFIED }
    data object FailedSafe : Terminal { override val wireState = ExecutionState.FAILED_SAFE }
    data object FailedSideEffectsPossible : Terminal { override val wireState = ExecutionState.FAILED_SIDE_EFFECTS_POSSIBLE }
    /** FB-RAT-EXE-004: reconnect could not establish state — reported as UNKNOWN, never as termination or success. */
    data object TargetStateUnknown : Terminal { override val wireState = ExecutionState.TARGET_STATE_UNKNOWN }
    data object Cancelled : Terminal { override val wireState = ExecutionState.CANCELLED }
}

data class Heartbeat(
    val heartbeatIntervalSeconds: Long?,
    val lastHeartbeatUtc: Instant? = null,
    val missedConsecutive: Int? = null
) {
    init {
        heartbeatIntervalSeconds?.let { require(it >= 1) { "Heartbeat.heartbeatIntervalSeconds must be >= 1 when present." } }
        missedConsecutive?.let { require(it >= 0) { "Heartbeat.missedConsecutive must be >= 0 when present." } }
    }
}

/** FB-RAT-EXE-003: every provider declares cancellation semantics up front. */
enum class CancellationMode { COOPERATIVE, FORCEFUL, UNSUPPORTED }

data class ReconnectToken(
    val token: String,
    val issuedAtUtc: Instant,
    val expiresAtUtc: Instant? = null
) {
    init {
        require(token.isNotBlank()) { "ReconnectToken.token must be non-blank." }
    }
}

/** FB-RAT-EXE-004: the three *_UNKNOWN outcomes force ExecutionHandle.state == TARGET_STATE_UNKNOWN. */
enum class ReconnectOutcome { ESTABLISHED, STALE_TOKEN_UNKNOWN, INVALID_TOKEN_UNKNOWN, UNREACHABLE_UNKNOWN }

data class ReconnectAttempt(
    val attemptedAtUtc: Instant,
    val outcome: ReconnectOutcome
)

private val STATES_REQUIRING_REASON: Set<ExecutionState> = setOf(
    ExecutionState.FAILED_SAFE, ExecutionState.FAILED_SIDE_EFFECTS_POSSIBLE,
    ExecutionState.TARGET_STATE_UNKNOWN, ExecutionState.CANCELLED, ExecutionState.WAITING_USER
)

private val UNKNOWN_RECONNECT_OUTCOMES: Set<ReconnectOutcome> = setOf(
    ReconnectOutcome.STALE_TOKEN_UNKNOWN, ReconnectOutcome.INVALID_TOKEN_UNKNOWN, ReconnectOutcome.UNREACHABLE_UNKNOWN
)

/**
 * Live/in-flight handle to a started ExecutionRequest. Typically carried as the `payload` of
 * a dev.fonebrew.contracts.common.ContractEnvelope<ExecutionHandle>. `handleId` is this
 * object's own FB-RAT-COM-002 stable ID.
 *
 * @param resumedFromHandleId FB-RAT-EXE-005 (support resume/restart/explicit migration): the
 *   prior handle's id when this handle was produced by resuming journaled work after the
 *   prior handle's owning process died.
 */
data class ExecutionHandle(
    val handleId: String,
    val requestId: String,
    val targetId: String,
    val state: ExecutionState,
    val enteredStateAtUtc: Instant,
    val heartbeat: Heartbeat,
    val cancellationMode: CancellationMode,
    val stateReason: String? = null,
    val knownStoppedDescription: String? = null,
    val reconnectToken: ReconnectToken? = null,
    val reconnectAttempt: ReconnectAttempt? = null,
    val resumedFromHandleId: String? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(handleId.isNotBlank()) { "ExecutionHandle.handleId must be non-blank (FB-RAT-COM-002)." }
        require(requestId.isNotBlank()) { "ExecutionHandle.requestId must be non-blank." }
        require(targetId.isNotBlank()) { "ExecutionHandle.targetId must be non-blank." }
        if (state in STATES_REQUIRING_REASON) {
            require(!stateReason.isNullOrBlank()) {
                "ExecutionHandle.stateReason must be non-blank when state=$state " +
                    "(FB-RAT-COM-009 accessibility — textual semantics for a non-plain-success state)."
            }
        }
        reconnectAttempt?.let { attempt ->
            if (attempt.outcome in UNKNOWN_RECONNECT_OUTCOMES) {
                require(state == ExecutionState.TARGET_STATE_UNKNOWN) {
                    "ExecutionHandle: reconnectAttempt.outcome=${attempt.outcome} MUST force " +
                        "state=TARGET_STATE_UNKNOWN (FB-RAT-EXE-004 — never a termination or " +
                        "success state), got state=$state."
                }
            }
        }
    }
}

// =========================================================================================
// ExecutionReceipt — schemas/execution/receipt.schema.json
// =========================================================================================

/** Point-in-time copy of a subset of ExecutionTarget's fields, not a live reference. */
data class TargetSnapshot(
    val id: String,
    val type: ExecutionTargetType,
    val displayName: String,
    val trust: TargetTrust,
    val arch: String,
    val hostFingerprint: String? = null
)

data class Timings(
    val queuedAtUtc: Instant,
    val finishedAtUtc: Instant,
    val startedAtUtc: Instant? = null,
    val durationMs: Long? = null
) {
    init {
        durationMs?.let { require(it >= 0) { "Timings.durationMs must be >= 0 when present." } }
    }
}

/** The terminal subset of [ExecutionState] — a receipt is only emitted once a handle reaches one of these six. */
enum class ExecutionExitState { SUCCEEDED, SUCCEEDED_UNVERIFIED, FAILED_SAFE, FAILED_SIDE_EFFECTS_POSSIBLE, TARGET_STATE_UNKNOWN, CANCELLED }

/** Android 12+ phantom-process-killer awareness (platform grounding). Required non-null when exitState == TARGET_STATE_UNKNOWN. */
enum class TerminationCause { NORMAL, USER_CANCELLED, SIGKILL_SUSPECTED, PROCESS_DEATH_UNSPECIFIED, RECONNECT_UNKNOWN }

data class ExecutionLogs(
    val redactionApplied: Boolean,
    val ref: ArtifactRef? = null,
    /**
     * Short inline excerpt. MUST be redaction-scanned before population, identically to
     * dev.fonebrew.contracts.common.ErrorEnvelope.detail — see
     * fixtures/execution/adversarial/receipt-secret-leak-in-log-excerpt.adversarial.json for
     * the violation this contract forbids.
     */
    val excerpt: String? = null
) {
    init {
        excerpt?.let { require(it.length <= 4096) { "ExecutionLogs.excerpt must be <= 4096 chars." } }
    }
}

data class ResourceSummary(
    val cpuSecondsUsed: Double? = null,
    val wallClockSecondsUsed: Double? = null,
    val networkBytesUp: Long? = null,
    val networkBytesDown: Long? = null,
    val peakMemoryBytes: Long? = null,
    val thermalStateObserved: ThermalState? = null
)

enum class ThermalState { NOMINAL, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN }

data class SideEffect(
    val description: String,
    val external: Boolean,
    val reversible: Boolean? = null,
    val occurredAtUtc: Instant? = null
) {
    init {
        require(description.isNotBlank()) { "SideEffect.description must be non-blank." }
    }
}

/** FB-RAT-EXE-009: operation success and result verification are separate states. */
enum class VerificationState { NOT_PERFORMED, VERIFIED, FAILED, SKIPPED }

data class ReceiptVerification(
    val state: VerificationState,
    val method: String? = null,
    val verifiedAtUtc: Instant? = null
)

/** FB-RAT-COM-008 provenance minimum, duplicated inline (mirrors dev.fonebrew.contracts.common.ProducerReceiptRef) per this domain's own receipt shape. */
data class ExecutionProvenance(
    val sourceLocation: String,
    val projectRevision: String,
    val initiatingPrincipal: String,
    val evidenceLinks: List<String> = emptyList()
) {
    init {
        require(sourceLocation.isNotBlank()) { "ExecutionProvenance.sourceLocation must be non-blank (FB-RAT-COM-008)." }
        require(projectRevision.isNotBlank()) { "ExecutionProvenance.projectRevision must be non-blank (FB-RAT-COM-008)." }
        require(initiatingPrincipal.isNotBlank()) { "ExecutionProvenance.initiatingPrincipal must be non-blank (FB-RAT-COM-008)." }
    }
}

/**
 * The durable, terminal record of one ExecutionRequest run. Typically carried as the
 * `payload` of a dev.fonebrew.contracts.common.ContractEnvelope<ExecutionReceipt>. `receiptId`
 * is this object's own FB-RAT-COM-002 stable ID. Receipts are append-only (FB-RAT-COM-006) —
 * a corrected receipt is a NEW receipt, never an in-place edit.
 *
 * `outputs` reuses dev.fonebrew.contracts.common.ArtifactRef as-is — NOT redefined here.
 *
 * Structurally operationalizes (via init{}, mirroring receipt.schema.json's allOf/if/then):
 *  - FB-RAT-EXE-009: exitState SUCCEEDED requires verification.state VERIFIED;
 *    SUCCEEDED_UNVERIFIED requires NOT_PERFORMED or SKIPPED; a failed exitState MUST NOT
 *    claim VERIFIED.
 *  - Platform grounding: exitState TARGET_STATE_UNKNOWN requires a non-null terminationCause.
 *  - exitState FAILED_SIDE_EFFECTS_POSSIBLE requires a non-empty sideEffects list.
 */
data class ExecutionReceipt(
    val receiptId: String,
    val requestId: String,
    val handleId: String,
    val targetSnapshot: TargetSnapshot,
    val revision: String?,
    val capsuleDigest: dev.fonebrew.contracts.common.IntegrityRef,
    val timings: Timings,
    val exitState: ExecutionExitState,
    val logs: ExecutionLogs,
    val resourceSummary: ResourceSummary,
    val outputs: List<ArtifactRef>,
    val sideEffects: List<SideEffect>,
    val verification: ReceiptVerification,
    val provenance: ExecutionProvenance,
    val terminationCause: TerminationCause? = null,
    val unknownFields: Map<String, Any?> = emptyMap()
) {
    init {
        require(receiptId.isNotBlank()) { "ExecutionReceipt.receiptId must be non-blank (FB-RAT-COM-002)." }
        require(requestId.isNotBlank()) { "ExecutionReceipt.requestId must be non-blank." }
        require(handleId.isNotBlank()) { "ExecutionReceipt.handleId must be non-blank." }
        when (exitState) {
            ExecutionExitState.SUCCEEDED ->
                require(verification.state == VerificationState.VERIFIED) {
                    "ExecutionReceipt: exitState=SUCCEEDED requires verification.state=VERIFIED (FB-RAT-EXE-009)."
                }
            ExecutionExitState.SUCCEEDED_UNVERIFIED ->
                require(verification.state == VerificationState.NOT_PERFORMED || verification.state == VerificationState.SKIPPED) {
                    "ExecutionReceipt: exitState=SUCCEEDED_UNVERIFIED requires verification.state " +
                        "NOT_PERFORMED or SKIPPED (FB-RAT-EXE-009), got ${verification.state}."
                }
            ExecutionExitState.FAILED_SAFE, ExecutionExitState.FAILED_SIDE_EFFECTS_POSSIBLE ->
                require(verification.state != VerificationState.VERIFIED) {
                    "ExecutionReceipt: exitState=$exitState MUST NOT claim verification.state=VERIFIED (FB-RAT-EXE-009)."
                }
            ExecutionExitState.TARGET_STATE_UNKNOWN, ExecutionExitState.CANCELLED -> Unit
        }
        if (exitState == ExecutionExitState.TARGET_STATE_UNKNOWN) {
            requireNotNull(terminationCause) {
                "ExecutionReceipt: exitState=TARGET_STATE_UNKNOWN requires a non-null terminationCause (platform grounding — Android 12+ phantom-process-killer awareness)."
            }
        }
        if (exitState == ExecutionExitState.FAILED_SIDE_EFFECTS_POSSIBLE) {
            require(sideEffects.isNotEmpty()) {
                "ExecutionReceipt: exitState=FAILED_SIDE_EFFECTS_POSSIBLE requires a non-empty sideEffects list."
            }
        }
    }
}

// =========================================================================================
// ExecutionProvider — provider contract interface (exact signatures per WP-1 task brief)
// =========================================================================================

/** Ties an ExecutionProvider implementation back to its CapabilityManifest declaration. */
data class ExecutionProviderDescriptor(
    val providerId: String,
    val supportedTargetTypes: List<ExecutionTargetType>,
    val capabilityManifest: CapabilityManifest,
    val producer: ProducerRef? = null
) {
    init {
        require(providerId.isNotBlank()) { "ExecutionProviderDescriptor.providerId must be non-blank." }
        require(supportedTargetTypes.isNotEmpty()) { "ExecutionProviderDescriptor.supportedTargetTypes must be non-empty." }
    }
}

/**
 * Output of ExecutionProvider.prepare() — an ExecutionRequest that has passed provider-side
 * preflight (target reachability, authority grant check, visibility-contract check per
 * FB-RAT-STR-004) and is ready for start(). Deliberately opaque beyond the fields a caller
 * needs to decide whether/when to call start() — the provider's own internal preparation
 * state (e.g. a checked-out working tree, a pulled container image) is not modeled here.
 */
data class PreparedExecution(
    val requestId: String,
    val targetId: String,
    val preparedAtUtc: Instant,
    val estimatedStart: Instant? = null
)

/** ExecutionProvider.cancel()'s mode selector — MUST be one the provider's own ExecutionHandle.cancellationMode declared as supported. */
enum class CancelMode { COOPERATIVE, FORCEFUL }

/** FB-RAT-EXE-003: result of a cancel() call — reports what is known to have stopped. */
data class CancelResult(
    val accepted: Boolean,
    val knownStoppedDescription: String?,
    val resultingState: ExecutionState
)

/** A single observed lifecycle/progress event from ExecutionProvider.observe(). */
sealed interface ExecutionEvent {
    val handleId: String
    val atUtc: Instant

    data class StateChanged(override val handleId: String, override val atUtc: Instant, val newState: ExecutionState, val reason: String?) : ExecutionEvent
    data class HeartbeatReceived(override val handleId: String, override val atUtc: Instant) : ExecutionEvent
    data class OutputChunk(override val handleId: String, override val atUtc: Instant, val text: String) : ExecutionEvent
    data class ReceiptReady(override val handleId: String, override val atUtc: Instant, val receipt: ExecutionReceipt) : ExecutionEvent
}

/** Opaque token passed to reconnect() — the same shape as [ReconnectToken] issued on an ExecutionHandle. */
data class ReconnectTokenHandle(val token: String)

/**
 * Every execution provider (LOCAL_ANDROID, SSH_HOST, RASPBERRY_PI, GITHUB_ACTIONS,
 * GITEA_ACTIONS per FB-RAT-EXE-010) implements this interface. Exact signatures per the
 * WP-1 task brief. The `Flow` return type on [observe] is this domain's one named
 * kotlinx-coroutines dependency exception (matching the workspace-kernel domain's own
 * exception) — every other type in this file is stdlib + java.time.Instant only.
 */
interface ExecutionProvider {
    val descriptor: ExecutionProviderDescriptor

    suspend fun prepare(request: ExecutionRequest): PreparedExecution

    suspend fun start(prepared: PreparedExecution): ExecutionHandle

    fun observe(handle: ExecutionHandle): Flow<ExecutionEvent>

    suspend fun cancel(handle: ExecutionHandle, mode: CancelMode): CancelResult

    /**
     * FB-RAT-EXE-004: if reconnect cannot establish state, implementations MUST return an
     * ExecutionHandle with state == TARGET_STATE_UNKNOWN (never fabricate a termination or
     * success state) rather than returning null to mean "unknown" — null is reserved for
     * "this token does not correspond to any handle this provider has ever issued" (a
     * different failure mode from "the handle exists but its current state cannot be
     * established").
     */
    suspend fun reconnect(token: ReconnectTokenHandle): ExecutionHandle?
}
