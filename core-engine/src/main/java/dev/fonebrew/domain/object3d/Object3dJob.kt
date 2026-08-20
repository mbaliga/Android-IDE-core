// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.object3d

import dev.fonebrew.contracts.common.ErrorEnvelope
import dev.fonebrew.domain.contracts.EnvelopeCodec
import dev.fonebrew.domain.contracts.extractUnknownFields
import dev.fonebrew.domain.contracts.mergeUnknownFields
import dev.fonebrew.domain.contracts.optJSONObjectOrNull
import dev.fonebrew.domain.contracts.optStringOrNull
import org.json.JSONObject
import java.time.Instant

/** §5: "adapters for Meshy and Tripo (both: text->3D and image->3D)". */
enum class Object3dProvider { MESHY, TRIPO }

enum class Object3dJobMode { TEXT_TO_3D, IMAGE_TO_3D }

/** §5/§6, verbatim: "QUEUED->RUNNING->DOWNLOADING->DONE/FAILED". */
enum class Object3dJobState {
    QUEUED, RUNNING, DOWNLOADING, DONE, FAILED;

    val isTerminal: Boolean get() = this == DONE || this == FAILED
}

/**
 * One async cloud 3D-generation job (§5), mirroring
 * schemas/object3d/object3d-job.schema.json field-for-field. Every cloud job is, by construction,
 * a watched-cloud operation (binding rule 2) — this type has no on-device counterpart (§4's
 * on-device path is synchronous within one chat turn and never produces a job record).
 *
 * The `init` block enforces the same structural invariants the JSON schema's `allOf` if/then
 * rules do — mirrors [dev.fonebrew.contracts.common.ContractEnvelope]/[ErrorEnvelope]'s own
 * "reject an impossible combination before construction, not after" discipline — so a caller
 * cannot even build an `Object3dJob` claiming `state=DONE` with no `resultNodeId`, or
 * `state=FAILED` with no `errorEnvelope`.
 */
data class Object3dJob(
    val jobId: String,
    val provider: Object3dProvider,
    val providerJobId: String,
    val mode: Object3dJobMode,
    val state: Object3dJobState,
    val createdAtUtc: Instant,
    val updatedAtUtc: Instant,
    val schemaVersion: String = "1.0.0",
    val prompt: String? = null,
    val sourceImageRef: String? = null,
    val progressPercent: Double? = null,
    val downloadUrl: String? = null,
    val resultNodeId: String? = null,
    val errorEnvelope: ErrorEnvelope? = null,
    val unknownFields: Map<String, Any?> = emptyMap(),
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "Object3dJob.schemaVersion must be major version 1 (got '$schemaVersion')."
        }
        require(jobId.isNotBlank()) { "Object3dJob.jobId must be non-blank." }
        require(providerJobId.isNotBlank()) { "Object3dJob.providerJobId must be non-blank." }
        progressPercent?.let {
            require(it in 0.0..100.0) { "Object3dJob.progressPercent must be within 0..100 (got $it)." }
        }
        when (mode) {
            Object3dJobMode.TEXT_TO_3D -> {
                require(!prompt.isNullOrBlank()) { "Object3dJob: mode=TEXT_TO_3D requires a non-blank prompt." }
                require(sourceImageRef == null) { "Object3dJob: mode=TEXT_TO_3D must not carry a sourceImageRef." }
            }
            Object3dJobMode.IMAGE_TO_3D -> {
                require(!sourceImageRef.isNullOrBlank()) { "Object3dJob: mode=IMAGE_TO_3D requires a non-blank sourceImageRef." }
                require(prompt == null) { "Object3dJob: mode=IMAGE_TO_3D must not carry a prompt." }
            }
        }
        when (state) {
            Object3dJobState.QUEUED -> {
                require(downloadUrl == null) { "Object3dJob: state=QUEUED must not yet carry a downloadUrl." }
                require(resultNodeId == null) { "Object3dJob: state=QUEUED must not yet carry a resultNodeId." }
            }
            Object3dJobState.RUNNING -> {
                require(resultNodeId == null) { "Object3dJob: state=RUNNING must not yet carry a resultNodeId." }
            }
            Object3dJobState.DOWNLOADING -> {
                require(!downloadUrl.isNullOrBlank()) { "Object3dJob: state=DOWNLOADING requires a downloadUrl." }
                require(resultNodeId == null) { "Object3dJob: state=DOWNLOADING must not yet carry a resultNodeId." }
            }
            Object3dJobState.DONE -> {
                require(!downloadUrl.isNullOrBlank()) { "Object3dJob: state=DONE requires a downloadUrl." }
                require(!resultNodeId.isNullOrBlank()) { "Object3dJob: state=DONE requires a resultNodeId." }
                require(errorEnvelope == null) { "Object3dJob: state=DONE must not carry an errorEnvelope." }
            }
            Object3dJobState.FAILED -> {
                require(errorEnvelope != null) { "Object3dJob: state=FAILED requires an errorEnvelope." }
                require(resultNodeId == null) { "Object3dJob: state=FAILED must not carry a resultNodeId." }
            }
        }
        require(!updatedAtUtc.isBefore(createdAtUtc)) {
            "Object3dJob.updatedAtUtc (${updatedAtUtc}) must not be before createdAtUtc (${createdAtUtc})."
        }
    }
}

/**
 * The pure state-transition law for [Object3dJobState] (§5/§6) — same "machine enforces which
 * transitions are legal, a real driver walks a concrete job through it" split
 * [dev.fonebrew.domain.device.broker.DeviceOperationMachine] establishes for
 * `DeviceOperationState`. QUEUED->RUNNING->DOWNLOADING->DONE is the one success path; [Event.Fail]
 * is legal from any NON-terminal state (a job can fail while queued, while running, or while
 * downloading) but never from an already-terminal state (DONE/FAILED) — a finished job's outcome
 * does not change after the fact, mirroring this corpus's append-only-history discipline.
 */
object Object3dJobMachine {

    sealed interface Event {
        data object StartRunning : Event
        data object BeginDownload : Event
        data class Complete(val resultNodeId: String) : Event
        data class Fail(val reason: String) : Event
    }

    sealed interface Result {
        data class Advanced(val state: Object3dJobState) : Result
        data class Rejected(val reason: String) : Result
    }

    fun start(): Object3dJobState = Object3dJobState.QUEUED

    fun apply(state: Object3dJobState, event: Event): Result {
        if (event is Event.Fail) {
            return if (state.isTerminal) reject(state, event) else Result.Advanced(Object3dJobState.FAILED)
        }
        return when (state) {
            Object3dJobState.QUEUED -> when (event) {
                Event.StartRunning -> Result.Advanced(Object3dJobState.RUNNING)
                else -> reject(state, event)
            }
            Object3dJobState.RUNNING -> when (event) {
                Event.BeginDownload -> Result.Advanced(Object3dJobState.DOWNLOADING)
                else -> reject(state, event)
            }
            Object3dJobState.DOWNLOADING -> when (event) {
                is Event.Complete -> Result.Advanced(Object3dJobState.DONE)
                else -> reject(state, event)
            }
            Object3dJobState.DONE, Object3dJobState.FAILED -> reject(state, event) // terminal: nothing further is legal
        }
    }

    private fun reject(state: Object3dJobState, event: Event): Result.Rejected = Result.Rejected(
        "Object3dJobMachine: event ${event::class.simpleName} is not legal from state $state (docs/design/objects-3d.md §5/§6)."
    )
}

/**
 * `org.json` encode/decode for [Object3dJob], mirroring
 * schemas/object3d/object3d-job.schema.json field-for-field — same discipline
 * `dev.fonebrew.domain.contracts.EnvelopeCodec`/`dev.fonebrew.domain.thread.ThreadCodec` use,
 * including unknown-field round-trip. `errorEnvelope` is delegated to
 * [EnvelopeCodec.encodeErrorEnvelope]/[EnvelopeCodec.decodeErrorEnvelope] rather than
 * re-implemented here, reusing the one canonical [ErrorEnvelope] codec this corpus already has.
 */
object Object3dJobCodec {

    private val KNOWN_KEYS = setOf(
        "schemaVersion", "jobId", "provider", "providerJobId", "mode", "prompt", "sourceImageRef",
        "state", "progressPercent", "downloadUrl", "resultNodeId", "errorEnvelope", "createdAtUtc", "updatedAtUtc",
    )

    fun encode(job: Object3dJob): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", job.schemaVersion)
        obj.put("jobId", job.jobId)
        obj.put("provider", job.provider.name)
        obj.put("providerJobId", job.providerJobId)
        obj.put("mode", job.mode.name)
        job.prompt?.let { obj.put("prompt", it) }
        job.sourceImageRef?.let { obj.put("sourceImageRef", it) }
        obj.put("state", job.state.name)
        job.progressPercent?.let { obj.put("progressPercent", it) }
        job.downloadUrl?.let { obj.put("downloadUrl", it) }
        job.resultNodeId?.let { obj.put("resultNodeId", it) }
        job.errorEnvelope?.let { obj.put("errorEnvelope", EnvelopeCodec.encodeErrorEnvelope(it)) }
        obj.put("createdAtUtc", job.createdAtUtc.toString())
        obj.put("updatedAtUtc", job.updatedAtUtc.toString())
        mergeUnknownFields(obj, job.unknownFields)
        return obj
    }

    fun decode(json: JSONObject): Object3dJob = Object3dJob(
        schemaVersion = json.getString("schemaVersion"),
        jobId = json.getString("jobId"),
        provider = Object3dProvider.valueOf(json.getString("provider")),
        providerJobId = json.getString("providerJobId"),
        mode = Object3dJobMode.valueOf(json.getString("mode")),
        prompt = json.optStringOrNull("prompt"),
        sourceImageRef = json.optStringOrNull("sourceImageRef"),
        state = Object3dJobState.valueOf(json.getString("state")),
        progressPercent = if (json.has("progressPercent") && !json.isNull("progressPercent")) json.getDouble("progressPercent") else null,
        downloadUrl = json.optStringOrNull("downloadUrl"),
        resultNodeId = json.optStringOrNull("resultNodeId"),
        errorEnvelope = json.optJSONObjectOrNull("errorEnvelope")?.let(EnvelopeCodec::decodeErrorEnvelope),
        createdAtUtc = Instant.parse(json.getString("createdAtUtc")),
        updatedAtUtc = Instant.parse(json.getString("updatedAtUtc")),
        unknownFields = extractUnknownFields(json, KNOWN_KEYS),
    )
}
