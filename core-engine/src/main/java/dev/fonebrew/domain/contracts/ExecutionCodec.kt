// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.contracts

import dev.fonebrew.contracts.execution.AuthorityGrantRef
import dev.fonebrew.contracts.execution.CancellationMode
import dev.fonebrew.contracts.execution.Connectivity
import dev.fonebrew.contracts.execution.ConnectivityKind
import dev.fonebrew.contracts.execution.CostTier
import dev.fonebrew.contracts.execution.ExecutionBudget
import dev.fonebrew.contracts.execution.ExecutionExitState
import dev.fonebrew.contracts.execution.ExecutionHandle
import dev.fonebrew.contracts.execution.ExecutionLogs
import dev.fonebrew.contracts.execution.ExecutionProvenance
import dev.fonebrew.contracts.execution.ExecutionReceipt
import dev.fonebrew.contracts.execution.ExecutionRequest
import dev.fonebrew.contracts.execution.ExecutionState
import dev.fonebrew.contracts.execution.ExecutionTarget
import dev.fonebrew.contracts.execution.ExecutionTargetType
import dev.fonebrew.contracts.execution.ExpectedOutput
import dev.fonebrew.contracts.execution.ExternalDuplicateBehavior
import dev.fonebrew.contracts.execution.FgsType
import dev.fonebrew.contracts.execution.Heartbeat
import dev.fonebrew.contracts.execution.OperationClass
import dev.fonebrew.contracts.execution.ProvenanceStyle
import dev.fonebrew.contracts.execution.ReconnectAttempt
import dev.fonebrew.contracts.execution.ReconnectOutcome
import dev.fonebrew.contracts.execution.ReconnectToken
import dev.fonebrew.contracts.execution.RequestEnvironment
import dev.fonebrew.contracts.execution.ResourceSummary
import dev.fonebrew.contracts.execution.SecretHandleRef
import dev.fonebrew.contracts.execution.SessionReauthorization
import dev.fonebrew.contracts.execution.SideEffect
import dev.fonebrew.contracts.execution.TargetCost
import dev.fonebrew.contracts.execution.TargetSnapshot
import dev.fonebrew.contracts.execution.TargetTrust
import dev.fonebrew.contracts.execution.TerminationCause
import dev.fonebrew.contracts.execution.ThermalPolicyRef
import dev.fonebrew.contracts.execution.ThermalState
import dev.fonebrew.contracts.execution.Timings
import dev.fonebrew.contracts.execution.TypedOperation
import dev.fonebrew.contracts.execution.VerificationState
import dev.fonebrew.contracts.execution.ReceiptVerification
import dev.fonebrew.contracts.execution.VisibilityContract
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Encode/decode for `dev.fonebrew.contracts.execution` (WP-4), same pattern as [EnvelopeCodec]/
 * [WorkspaceCodec]/[AuthorityCodec]. `ExecutionTarget`/`ExecutionRequest`/`ExecutionHandle`/
 * `ExecutionReceipt` are the four top-level shapes with `unknownFields`; every nested sub-shape
 * (`Timings`, `ExecutionLogs`, ...) round-trips through the same helper functions used to build
 * and read the parent's JSON, without its own unknown-field preservation (mirroring the wire
 * schemas: only the four top-level objects carry `unknownFields`).
 */
object ExecutionCodec {

    // -------------------------------------------------------------------------------------
    // ExecutionTarget
    // -------------------------------------------------------------------------------------

    private val TARGET_KNOWN_KEYS = setOf(
        "id", "type", "displayName", "trust", "arch", "connectivity", "cost", "provenanceStyle",
        "fgsType", "hostFingerprint", "capabilities", "visibilityContract"
    )

    fun encodeExecutionTarget(t: ExecutionTarget): JSONObject {
        val obj = JSONObject()
        obj.put("id", t.id)
        obj.put("type", t.type.name)
        obj.put("displayName", t.displayName)
        obj.put("trust", t.trust.name)
        obj.put("arch", t.arch)
        obj.put("connectivity", JSONObject().apply {
            put("kind", t.connectivity.kind.name)
            put("lastSeenReachableUtc", t.connectivity.lastSeenReachableUtc?.toString())
        })
        obj.put("cost", JSONObject().apply {
            put("tier", t.cost.tier.name)
            put("notes", t.cost.notes)
        })
        obj.put("provenanceStyle", t.provenanceStyle.name)
        obj.put("fgsType", t.fgsType.name)
        obj.put("hostFingerprint", t.hostFingerprint)
        t.capabilities?.let { obj.put("capabilities", EnvelopeCodec.encodeCapabilityManifest(it)) }
        t.visibilityContract?.let {
            obj.put("visibilityContract", JSONObject().apply {
                put("executionLocationVisible", it.executionLocationVisible)
                put("revisionVisible", it.revisionVisible)
                put("dataBoundaryVisible", it.dataBoundaryVisible)
                put("stateVisible", it.stateVisible)
                put("resultVisible", it.resultVisible)
            })
        }
        mergeUnknownFields(obj, t.unknownFields)
        return obj
    }

    fun decodeExecutionTarget(json: JSONObject): ExecutionTarget {
        val connectivityJson = json.getJSONObject("connectivity")
        val costJson = json.getJSONObject("cost")
        val visibilityJson = json.optJSONObjectOrNull("visibilityContract")
        return ExecutionTarget(
            id = json.getString("id"),
            type = ExecutionTargetType.valueOf(json.getString("type")),
            displayName = json.getString("displayName"),
            trust = TargetTrust.valueOf(json.getString("trust")),
            arch = json.getString("arch"),
            connectivity = Connectivity(
                kind = ConnectivityKind.valueOf(connectivityJson.getString("kind")),
                lastSeenReachableUtc = connectivityJson.optStringOrNull("lastSeenReachableUtc")?.let { Instant.parse(it) }
            ),
            cost = TargetCost(tier = CostTier.valueOf(costJson.getString("tier")), notes = costJson.optStringOrNull("notes")),
            provenanceStyle = ProvenanceStyle.valueOf(json.getString("provenanceStyle")),
            fgsType = FgsType.valueOf(json.getString("fgsType")),
            hostFingerprint = json.optStringOrNull("hostFingerprint"),
            capabilities = json.optJSONObjectOrNull("capabilities")?.let { EnvelopeCodec.decodeCapabilityManifest(it) },
            visibilityContract = visibilityJson?.let {
                VisibilityContract(
                    executionLocationVisible = it.getBoolean("executionLocationVisible"),
                    revisionVisible = it.getBoolean("revisionVisible"),
                    dataBoundaryVisible = it.getBoolean("dataBoundaryVisible"),
                    stateVisible = it.getBoolean("stateVisible"),
                    resultVisible = it.getBoolean("resultVisible")
                )
            },
            unknownFields = extractUnknownFields(json, TARGET_KNOWN_KEYS)
        )
    }

    // -------------------------------------------------------------------------------------
    // ExecutionRequest
    // -------------------------------------------------------------------------------------

    private val REQUEST_KNOWN_KEYS = setOf(
        "id", "targetId", "operation", "workingRevision", "environment", "secretHandles", "budget",
        "authorityGrant", "expectedOutputs", "idempotencyKey", "sideEffectExternal", "fgsType",
        "openEnded", "externalDuplicateBehavior", "specialUseJustification"
    )

    fun encodeExecutionRequest(r: ExecutionRequest): JSONObject {
        val obj = JSONObject()
        obj.put("id", r.id)
        obj.put("targetId", r.targetId)
        obj.put("operation", JSONObject().apply {
            put("operationClass", r.operation.operationClass.name)
            put("command", r.operation.command)
            put("args", JSONArray(r.operation.args))
            put("workingDirectory", r.operation.workingDirectory)
        })
        obj.put("workingRevision", r.workingRevision)
        obj.put("environment", JSONObject().apply { put("envVars", JSONObject(r.environment.envVars)) })
        obj.put("secretHandles", JSONArray().apply {
            r.secretHandles.forEach { put(JSONObject().apply { put("handleId", it.handleId); put("purpose", it.purpose) }) }
        })
        obj.put("budget", JSONObject().apply {
            put("sessionReauthorization", JSONObject().apply {
                put("maxSessionSeconds", r.budget.sessionReauthorization.maxSessionSeconds)
                put("requiresUserReauth", r.budget.sessionReauthorization.requiresUserReauth)
            })
            put("thermalStateObservabilityRequired", r.budget.thermalStateObservabilityRequired)
            put("wallClockSeconds", r.budget.wallClockSeconds)
            put("maxOutputBytes", r.budget.maxOutputBytes)
            put("thermalPausePolicyRef", r.budget.thermalPausePolicyRef?.ref)
        })
        obj.put("authorityGrant", JSONObject().apply {
            put("grantId", r.authorityGrant.grantId)
            put("scopes", JSONArray(r.authorityGrant.scopes))
        })
        obj.put("expectedOutputs", JSONArray().apply {
            r.expectedOutputs.forEach { put(JSONObject().apply { put("role", it.role); put("mediaType", it.mediaType) }) }
        })
        obj.put("idempotencyKey", r.idempotencyKey)
        obj.put("sideEffectExternal", r.sideEffectExternal)
        obj.put("fgsType", r.fgsType.name)
        obj.put("openEnded", r.openEnded)
        obj.put("externalDuplicateBehavior", r.externalDuplicateBehavior?.name)
        obj.put("specialUseJustification", r.specialUseJustification)
        mergeUnknownFields(obj, r.unknownFields)
        return obj
    }

    fun decodeExecutionRequest(json: JSONObject): ExecutionRequest {
        val opJson = json.getJSONObject("operation")
        val argsJson = opJson.getJSONArray("args")
        val envJson = json.getJSONObject("environment").getJSONObject("envVars")
        val secretHandlesJson = json.getJSONArray("secretHandles")
        val budgetJson = json.getJSONObject("budget")
        val sessionReauthJson = budgetJson.getJSONObject("sessionReauthorization")
        val authorityGrantJson = json.getJSONObject("authorityGrant")
        val scopesJson = authorityGrantJson.getJSONArray("scopes")
        val expectedOutputsJson = json.getJSONArray("expectedOutputs")

        val envVars = LinkedHashMap<String, String>()
        val envKeys = envJson.keys()
        while (envKeys.hasNext()) { val k = envKeys.next(); envVars[k] = envJson.getString(k) }

        return ExecutionRequest(
            id = json.getString("id"),
            targetId = json.getString("targetId"),
            operation = TypedOperation(
                operationClass = OperationClass.valueOf(opJson.getString("operationClass")),
                command = opJson.getString("command"),
                args = (0 until argsJson.length()).map { argsJson.getString(it) },
                workingDirectory = opJson.optStringOrNull("workingDirectory")
            ),
            workingRevision = json.optStringOrNull("workingRevision"),
            environment = RequestEnvironment(envVars = envVars),
            secretHandles = (0 until secretHandlesJson.length()).map {
                val h = secretHandlesJson.getJSONObject(it)
                SecretHandleRef(handleId = h.getString("handleId"), purpose = h.getString("purpose"))
            },
            budget = ExecutionBudget(
                sessionReauthorization = SessionReauthorization(
                    maxSessionSeconds = sessionReauthJson.getLong("maxSessionSeconds"),
                    requiresUserReauth = sessionReauthJson.getBoolean("requiresUserReauth")
                ),
                thermalStateObservabilityRequired = budgetJson.getBoolean("thermalStateObservabilityRequired"),
                wallClockSeconds = budgetJson.optLongOrNull("wallClockSeconds"),
                maxOutputBytes = budgetJson.optLongOrNull("maxOutputBytes"),
                thermalPausePolicyRef = budgetJson.optStringOrNull("thermalPausePolicyRef")?.let { ThermalPolicyRef(it) }
            ),
            authorityGrant = AuthorityGrantRef(
                grantId = authorityGrantJson.getString("grantId"),
                scopes = (0 until scopesJson.length()).map { scopesJson.getString(it) }
            ),
            expectedOutputs = (0 until expectedOutputsJson.length()).map {
                val e = expectedOutputsJson.getJSONObject(it)
                ExpectedOutput(role = e.getString("role"), mediaType = e.getString("mediaType"))
            },
            idempotencyKey = json.optStringOrNull("idempotencyKey"),
            sideEffectExternal = json.getBoolean("sideEffectExternal"),
            fgsType = FgsType.valueOf(json.getString("fgsType")),
            openEnded = json.getBoolean("openEnded"),
            externalDuplicateBehavior = json.optStringOrNull("externalDuplicateBehavior")?.let { ExternalDuplicateBehavior.valueOf(it) },
            specialUseJustification = json.optStringOrNull("specialUseJustification"),
            unknownFields = extractUnknownFields(json, REQUEST_KNOWN_KEYS)
        )
    }

    // -------------------------------------------------------------------------------------
    // ExecutionHandle
    // -------------------------------------------------------------------------------------

    private val HANDLE_KNOWN_KEYS = setOf(
        "handleId", "requestId", "targetId", "state", "enteredStateAtUtc", "heartbeat", "cancellationMode",
        "stateReason", "knownStoppedDescription", "reconnectToken", "reconnectAttempt", "resumedFromHandleId"
    )

    fun encodeExecutionHandle(h: ExecutionHandle): JSONObject {
        val obj = JSONObject()
        obj.put("handleId", h.handleId)
        obj.put("requestId", h.requestId)
        obj.put("targetId", h.targetId)
        obj.put("state", h.state.name)
        obj.put("enteredStateAtUtc", h.enteredStateAtUtc.toString())
        obj.put("heartbeat", JSONObject().apply {
            put("heartbeatIntervalSeconds", h.heartbeat.heartbeatIntervalSeconds)
            put("lastHeartbeatUtc", h.heartbeat.lastHeartbeatUtc?.toString())
            put("missedConsecutive", h.heartbeat.missedConsecutive)
        })
        obj.put("cancellationMode", h.cancellationMode.name)
        obj.put("stateReason", h.stateReason)
        obj.put("knownStoppedDescription", h.knownStoppedDescription)
        h.reconnectToken?.let {
            obj.put("reconnectToken", JSONObject().apply {
                put("token", it.token); put("issuedAtUtc", it.issuedAtUtc.toString()); put("expiresAtUtc", it.expiresAtUtc?.toString())
            })
        }
        h.reconnectAttempt?.let {
            obj.put("reconnectAttempt", JSONObject().apply {
                put("attemptedAtUtc", it.attemptedAtUtc.toString()); put("outcome", it.outcome.name)
            })
        }
        obj.put("resumedFromHandleId", h.resumedFromHandleId)
        mergeUnknownFields(obj, h.unknownFields)
        return obj
    }

    fun decodeExecutionHandle(json: JSONObject): ExecutionHandle {
        val heartbeatJson = json.getJSONObject("heartbeat")
        val reconnectTokenJson = json.optJSONObjectOrNull("reconnectToken")
        val reconnectAttemptJson = json.optJSONObjectOrNull("reconnectAttempt")
        return ExecutionHandle(
            handleId = json.getString("handleId"),
            requestId = json.getString("requestId"),
            targetId = json.getString("targetId"),
            state = ExecutionState.valueOf(json.getString("state")),
            enteredStateAtUtc = Instant.parse(json.getString("enteredStateAtUtc")),
            heartbeat = Heartbeat(
                heartbeatIntervalSeconds = heartbeatJson.optLongOrNull("heartbeatIntervalSeconds"),
                lastHeartbeatUtc = heartbeatJson.optStringOrNull("lastHeartbeatUtc")?.let { Instant.parse(it) },
                missedConsecutive = if (heartbeatJson.has("missedConsecutive") && !heartbeatJson.isNull("missedConsecutive")) heartbeatJson.getInt("missedConsecutive") else null
            ),
            cancellationMode = CancellationMode.valueOf(json.getString("cancellationMode")),
            stateReason = json.optStringOrNull("stateReason"),
            knownStoppedDescription = json.optStringOrNull("knownStoppedDescription"),
            reconnectToken = reconnectTokenJson?.let {
                ReconnectToken(token = it.getString("token"), issuedAtUtc = Instant.parse(it.getString("issuedAtUtc")), expiresAtUtc = it.optStringOrNull("expiresAtUtc")?.let { s -> Instant.parse(s) })
            },
            reconnectAttempt = reconnectAttemptJson?.let {
                ReconnectAttempt(attemptedAtUtc = Instant.parse(it.getString("attemptedAtUtc")), outcome = ReconnectOutcome.valueOf(it.getString("outcome")))
            },
            resumedFromHandleId = json.optStringOrNull("resumedFromHandleId"),
            unknownFields = extractUnknownFields(json, HANDLE_KNOWN_KEYS)
        )
    }

    // -------------------------------------------------------------------------------------
    // ExecutionReceipt
    // -------------------------------------------------------------------------------------

    private val RECEIPT_KNOWN_KEYS = setOf(
        "receiptId", "requestId", "handleId", "targetSnapshot", "revision", "capsuleDigest", "timings",
        "exitState", "logs", "resourceSummary", "outputs", "sideEffects", "verification", "provenance",
        "terminationCause"
    )

    fun encodeExecutionReceipt(r: ExecutionReceipt): JSONObject {
        val obj = JSONObject()
        obj.put("receiptId", r.receiptId)
        obj.put("requestId", r.requestId)
        obj.put("handleId", r.handleId)
        obj.put("targetSnapshot", JSONObject().apply {
            put("id", r.targetSnapshot.id); put("type", r.targetSnapshot.type.name); put("displayName", r.targetSnapshot.displayName)
            put("trust", r.targetSnapshot.trust.name); put("arch", r.targetSnapshot.arch); put("hostFingerprint", r.targetSnapshot.hostFingerprint)
        })
        obj.put("revision", r.revision)
        obj.put("capsuleDigest", EnvelopeCodec.encodeIntegrityRef(r.capsuleDigest))
        obj.put("timings", JSONObject().apply {
            put("queuedAtUtc", r.timings.queuedAtUtc.toString()); put("finishedAtUtc", r.timings.finishedAtUtc.toString())
            put("startedAtUtc", r.timings.startedAtUtc?.toString()); put("durationMs", r.timings.durationMs)
        })
        obj.put("exitState", r.exitState.name)
        obj.put("logs", JSONObject().apply {
            put("redactionApplied", r.logs.redactionApplied)
            r.logs.ref?.let { put("ref", EnvelopeCodec.encodeArtifactRef(it)) }
            put("excerpt", r.logs.excerpt)
        })
        obj.put("resourceSummary", JSONObject().apply {
            put("cpuSecondsUsed", r.resourceSummary.cpuSecondsUsed); put("wallClockSecondsUsed", r.resourceSummary.wallClockSecondsUsed)
            put("networkBytesUp", r.resourceSummary.networkBytesUp); put("networkBytesDown", r.resourceSummary.networkBytesDown)
            put("peakMemoryBytes", r.resourceSummary.peakMemoryBytes); put("thermalStateObserved", r.resourceSummary.thermalStateObserved?.name)
        })
        obj.put("outputs", JSONArray().apply { r.outputs.forEach { put(EnvelopeCodec.encodeArtifactRef(it)) } })
        obj.put("sideEffects", JSONArray().apply {
            r.sideEffects.forEach {
                put(JSONObject().apply {
                    put("description", it.description); put("external", it.external)
                    put("reversible", it.reversible); put("occurredAtUtc", it.occurredAtUtc?.toString())
                })
            }
        })
        obj.put("verification", JSONObject().apply {
            put("state", r.verification.state.name); put("method", r.verification.method); put("verifiedAtUtc", r.verification.verifiedAtUtc?.toString())
        })
        obj.put("provenance", JSONObject().apply {
            put("sourceLocation", r.provenance.sourceLocation); put("projectRevision", r.provenance.projectRevision)
            put("initiatingPrincipal", r.provenance.initiatingPrincipal); put("evidenceLinks", JSONArray(r.provenance.evidenceLinks))
        })
        obj.put("terminationCause", r.terminationCause?.name)
        mergeUnknownFields(obj, r.unknownFields)
        return obj
    }

    fun decodeExecutionReceipt(json: JSONObject): ExecutionReceipt {
        val targetSnapshotJson = json.getJSONObject("targetSnapshot")
        val timingsJson = json.getJSONObject("timings")
        val logsJson = json.getJSONObject("logs")
        val resourceSummaryJson = json.getJSONObject("resourceSummary")
        val outputsJson = json.getJSONArray("outputs")
        val sideEffectsJson = json.getJSONArray("sideEffects")
        val verificationJson = json.getJSONObject("verification")
        val provenanceJson = json.getJSONObject("provenance")
        val evidenceLinksJson = provenanceJson.getJSONArray("evidenceLinks")

        return ExecutionReceipt(
            receiptId = json.getString("receiptId"),
            requestId = json.getString("requestId"),
            handleId = json.getString("handleId"),
            targetSnapshot = TargetSnapshot(
                id = targetSnapshotJson.getString("id"),
                type = ExecutionTargetType.valueOf(targetSnapshotJson.getString("type")),
                displayName = targetSnapshotJson.getString("displayName"),
                trust = TargetTrust.valueOf(targetSnapshotJson.getString("trust")),
                arch = targetSnapshotJson.getString("arch"),
                hostFingerprint = targetSnapshotJson.optStringOrNull("hostFingerprint")
            ),
            revision = json.optStringOrNull("revision"),
            capsuleDigest = EnvelopeCodec.decodeIntegrityRef(json.getJSONObject("capsuleDigest")),
            timings = Timings(
                queuedAtUtc = Instant.parse(timingsJson.getString("queuedAtUtc")),
                finishedAtUtc = Instant.parse(timingsJson.getString("finishedAtUtc")),
                startedAtUtc = timingsJson.optStringOrNull("startedAtUtc")?.let { Instant.parse(it) },
                durationMs = timingsJson.optLongOrNull("durationMs")
            ),
            exitState = ExecutionExitState.valueOf(json.getString("exitState")),
            logs = ExecutionLogs(
                redactionApplied = logsJson.getBoolean("redactionApplied"),
                ref = logsJson.optJSONObjectOrNull("ref")?.let { EnvelopeCodec.decodeArtifactRef(it) },
                excerpt = logsJson.optStringOrNull("excerpt")
            ),
            resourceSummary = ResourceSummary(
                cpuSecondsUsed = if (resourceSummaryJson.has("cpuSecondsUsed") && !resourceSummaryJson.isNull("cpuSecondsUsed")) resourceSummaryJson.getDouble("cpuSecondsUsed") else null,
                wallClockSecondsUsed = if (resourceSummaryJson.has("wallClockSecondsUsed") && !resourceSummaryJson.isNull("wallClockSecondsUsed")) resourceSummaryJson.getDouble("wallClockSecondsUsed") else null,
                networkBytesUp = resourceSummaryJson.optLongOrNull("networkBytesUp"),
                networkBytesDown = resourceSummaryJson.optLongOrNull("networkBytesDown"),
                peakMemoryBytes = resourceSummaryJson.optLongOrNull("peakMemoryBytes"),
                thermalStateObserved = resourceSummaryJson.optStringOrNull("thermalStateObserved")?.let { ThermalState.valueOf(it) }
            ),
            outputs = (0 until outputsJson.length()).map { EnvelopeCodec.decodeArtifactRef(outputsJson.getJSONObject(it)) },
            sideEffects = (0 until sideEffectsJson.length()).map {
                val s = sideEffectsJson.getJSONObject(it)
                SideEffect(
                    description = s.getString("description"), external = s.getBoolean("external"),
                    reversible = if (s.has("reversible") && !s.isNull("reversible")) s.getBoolean("reversible") else null,
                    occurredAtUtc = s.optStringOrNull("occurredAtUtc")?.let { t -> Instant.parse(t) }
                )
            },
            verification = ReceiptVerification(
                state = VerificationState.valueOf(verificationJson.getString("state")),
                method = verificationJson.optStringOrNull("method"),
                verifiedAtUtc = verificationJson.optStringOrNull("verifiedAtUtc")?.let { Instant.parse(it) }
            ),
            provenance = ExecutionProvenance(
                sourceLocation = provenanceJson.getString("sourceLocation"),
                projectRevision = provenanceJson.getString("projectRevision"),
                initiatingPrincipal = provenanceJson.getString("initiatingPrincipal"),
                evidenceLinks = (0 until evidenceLinksJson.length()).map { evidenceLinksJson.getString(it) }
            ),
            terminationCause = json.optStringOrNull("terminationCause")?.let { TerminationCause.valueOf(it) },
            unknownFields = extractUnknownFields(json, RECEIPT_KNOWN_KEYS)
        )
    }
}
