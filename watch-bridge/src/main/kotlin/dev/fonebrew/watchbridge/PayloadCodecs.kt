package dev.fonebrew.watchbridge

import org.json.JSONArray
import org.json.JSONObject

/** The envelope's `type` values. Hard-coded strings — never derived from class names (R8-safe, rename-safe). */
internal enum class WireType(val wire: String) {
    CONTRACT_HELLO("ContractHello"),
    ASK_REQUEST("AskRequest"),
    ASK_CHUNK("AskChunk"),
    ASK_ERROR("AskError"),
    TASK_SNAPSHOT("TaskSnapshot"),
    TASK_TOGGLE_REQUEST("TaskToggleRequest"),
    TASK_TOGGLE_RESULT("TaskToggleResult"),
    LOOP_RUN_SNAPSHOT("LoopRunSnapshot"),
    ;

    companion object {
        fun fromWire(wire: String): WireType? = entries.firstOrNull { it.wire == wire }
    }
}

internal fun WatchMessage.wireType(): WireType = when (this) {
    is ContractHello -> WireType.CONTRACT_HELLO
    is AskRequest -> WireType.ASK_REQUEST
    is AskChunk -> WireType.ASK_CHUNK
    is AskError -> WireType.ASK_ERROR
    is TaskSnapshot -> WireType.TASK_SNAPSHOT
    is TaskToggleRequest -> WireType.TASK_TOGGLE_REQUEST
    is TaskToggleResult -> WireType.TASK_TOGGLE_RESULT
    is LoopRunSnapshot -> WireType.LOOP_RUN_SNAPSHOT
}

/**
 * Per-message payload codecs: each message's fields <-> a payload [JSONObject]. Encoding writes
 * only fields that are present (a null optional is *omitted*, never written as JSON `null`), so a
 * non-terminal [AskChunk] has no `usage` key at all. Decoding goes through [Fields] only, and builds
 * the message through [build] so a violated constructor invariant becomes a [WireException].
 */
internal object PayloadCodecs {

    // ---- encode ------------------------------------------------------------------------------

    fun encode(message: WatchMessage): JSONObject = when (message) {
        is ContractHello -> JSONObject().apply {
            put("contractVersion", message.contractVersion)
            put("phoneAppVersion", message.phoneAppVersion)
        }
        is AskRequest -> JSONObject().apply {
            put("requestId", message.requestId)
            put("prompt", message.prompt)
        }
        is AskChunk -> JSONObject().apply {
            put("requestId", message.requestId)
            put("text", message.text)
            put("terminal", message.terminal)
            put("engineId", message.engineId)
            put("watchedObject", message.watchedObject)
            message.usage?.let { put("usage", encodeUsage(it)) }
        }
        is AskError -> JSONObject().apply {
            put("requestId", message.requestId)
            put("reason", message.reason)
        }
        is TaskSnapshot -> JSONObject().apply {
            put(
                "tasks",
                JSONArray().also { arr ->
                    message.tasks.forEach { task ->
                        arr.put(
                            JSONObject().apply {
                                put("id", task.id)
                                put("title", task.title)
                                put("state", task.state.wire)
                            },
                        )
                    }
                },
            )
            put("pendingCount", message.pendingCount)
        }
        is TaskToggleRequest -> JSONObject().apply {
            put("taskId", message.taskId)
            put("done", message.done)
        }
        is TaskToggleResult.Applied -> JSONObject().apply {
            put("taskId", message.taskId)
            put("outcome", OUTCOME_APPLIED)
            put("done", message.done)
        }
        is TaskToggleResult.Rejected -> JSONObject().apply {
            put("taskId", message.taskId)
            put("outcome", OUTCOME_REJECTED)
            put("reason", message.reason)
        }
        is LoopRunSnapshot -> JSONObject().apply {
            put("runId", message.runId)
            put("loopName", message.loopName)
            put("stateGlyphKey", message.stateGlyphKey)
            message.stepName?.let { put("stepName", it) }
            message.spend?.let { spend ->
                put(
                    "spend",
                    JSONObject().apply {
                        put("amountMinor", spend.amountMinor)
                        put("currency", spend.currency)
                        put("estimated", spend.estimated)
                    },
                )
            }
        }
    }

    private fun encodeUsage(usage: Usage): JSONObject = JSONObject().apply {
        usage.inputTokens?.let { put("inputTokens", it) }
        usage.outputTokens?.let { put("outputTokens", it) }
        put("estimated", usage.estimated)
    }

    // ---- decode ------------------------------------------------------------------------------

    fun decode(type: WireType, f: Fields): WatchMessage = when (type) {
        WireType.CONTRACT_HELLO -> build(f) {
            ContractHello(contractVersion = f.int("contractVersion"), phoneAppVersion = f.string("phoneAppVersion"))
        }
        WireType.ASK_REQUEST -> build(f) {
            AskRequest(requestId = f.string("requestId"), prompt = f.string("prompt"))
        }
        WireType.ASK_CHUNK -> build(f) { decodeAskChunk(f) }
        WireType.ASK_ERROR -> build(f) {
            AskError(requestId = f.string("requestId"), reason = f.string("reason"))
        }
        WireType.TASK_SNAPSHOT -> build(f) { decodeTaskSnapshot(f) }
        WireType.TASK_TOGGLE_REQUEST -> build(f) {
            TaskToggleRequest(taskId = f.string("taskId"), done = f.boolean("done"))
        }
        WireType.TASK_TOGGLE_RESULT -> build(f) { decodeTaskToggleResult(f) }
        WireType.LOOP_RUN_SNAPSHOT -> build(f) { decodeLoopRunSnapshot(f) }
    }

    private fun decodeAskChunk(f: Fields): AskChunk {
        val terminal = f.boolean("terminal")
        // Decide on presence BEFORE the constructor so the reason names the wire field, and so a
        // non-terminal chunk that carries `usage` is rejected rather than having it ignored.
        val usage = f.optObj("usage")?.let(::decodeUsage)
        if (terminal && usage == null) f.fail("terminal chunk is missing \"usage\"")
        if (!terminal && usage != null) f.fail("\"usage\" is present on a non-terminal chunk")
        return AskChunk(
            requestId = f.string("requestId"),
            text = f.string("text"),
            terminal = terminal,
            engineId = f.string("engineId"),
            watchedObject = f.boolean("watchedObject"),
            usage = usage,
        )
    }

    private fun decodeUsage(f: Fields): Usage =
        // `estimated` is REQUIRED: a missing flag must not default to "real".
        Usage(inputTokens = f.optLong("inputTokens"), outputTokens = f.optLong("outputTokens"), estimated = f.boolean("estimated"))

    private fun decodeTaskSnapshot(f: Fields): TaskSnapshot {
        val arr = f.array("tasks")
        val tasks = (0 until arr.length()).map { i ->
            val t = f.element(arr, "tasks", i)
            val stateWire = t.string("state")
            TaskEntry(
                id = t.string("id"),
                title = t.string("title"),
                state = WatchTaskState.fromWire(stateWire) ?: t.fail("unknown task state \"${stateWire.take(24)}\""),
            )
        }
        return TaskSnapshot(tasks = tasks, pendingCount = f.int("pendingCount"))
    }

    private fun decodeTaskToggleResult(f: Fields): TaskToggleResult {
        val taskId = f.string("taskId")
        return when (val outcome = f.string("outcome")) {
            OUTCOME_APPLIED -> {
                if (f.has("reason")) f.fail("an \"applied\" result must not carry \"reason\"")
                TaskToggleResult.Applied(taskId = taskId, done = f.boolean("done"))
            }
            OUTCOME_REJECTED -> {
                if (f.has("done")) f.fail("a \"rejected\" result must not carry \"done\"")
                TaskToggleResult.Rejected(taskId = taskId, reason = f.string("reason"))
            }
            else -> f.fail("unknown outcome \"${outcome.take(24)}\"")
        }
    }

    private fun decodeLoopRunSnapshot(f: Fields): LoopRunSnapshot = LoopRunSnapshot(
        runId = f.string("runId"),
        loopName = f.string("loopName"),
        stateGlyphKey = f.string("stateGlyphKey"),
        stepName = f.optString("stepName"),
        spend = f.optObj("spend")?.let { s ->
            Spend(amountMinor = s.long("amountMinor"), currency = s.string("currency"), estimated = s.boolean("estimated"))
        },
    )

    /** Runs a message constructor; a violated invariant ([IllegalArgumentException]) becomes a [WireException]. */
    private inline fun <T : WatchMessage> build(f: Fields, block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            f.fail("invalid message: ${e.message}")
        }

    private const val OUTCOME_APPLIED = "applied"
    private const val OUTCOME_REJECTED = "rejected"
}
