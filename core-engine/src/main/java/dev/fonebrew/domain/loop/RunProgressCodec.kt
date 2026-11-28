package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnNodeKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * One [RunProgress] event ⇄ one line of JSON (JSONL). This is the wire form that lets the live
 * run view travel: out of a headless `fonebrew` CLI over SSH, into a git-recorded run log, or in
 * from an external agent's transcript that has been mapped onto the same events. Unknown event
 * types and unknown fields decode to `null` / are ignored, so an older reader survives a newer
 * writer (forward-compatible); `v` is bumped only for an incompatible change.
 *
 * Pure encoding — no I/O. Uses org.json (Android's bundled copy on-device, the real artifact in
 * JVM tests), like the other codecs in this module.
 */
object RunProgressCodec {
    const val VERSION = 1

    fun encode(e: RunProgress): String = toJson(e).toString()

    fun encodeAll(events: Iterable<RunProgress>): String =
        events.joinToString("\n") { encode(it) }

    /** Decode one line; `null` for blank, malformed, or an event type this build does not know. */
    fun decode(line: String): RunProgress? {
        if (line.isBlank()) return null
        val o = try { JSONObject(line) } catch (_: Exception) { return null }
        return try { fromJson(o) } catch (_: Exception) { null }
    }

    fun decodeAll(text: String): List<RunProgress> = text.lineSequence().mapNotNull { decode(it) }.toList()

    private fun toJson(e: RunProgress): JSONObject = JSONObject().put("v", VERSION).put("t", e.t).also { o ->
        when (e) {
            is RunProgress.RunStarted -> o.put("e", "run_started").put("objective", e.objective)
                .put("nodeCount", e.nodeCount).put("hardCap", e.hardCap)
            is RunProgress.NodeEntered -> o.put("e", "node_entered").put("nodeId", e.nodeId)
                .put("kind", e.kind.element).putOpt("viaEdgeId", e.viaEdgeId)
            is RunProgress.StepStarted -> o.put("e", "step_started").put("stepIndex", e.stepIndex)
                .put("nodeId", e.nodeId).put("role", e.role).putOpt("model", e.model)
                .put("agentId", e.agentId).put("parentAgentId", e.parentAgentId)
            is RunProgress.StepDelta -> o.put("e", "step_delta").put("stepIndex", e.stepIndex)
                .put("nodeId", e.nodeId).put("text", e.text).put("agentId", e.agentId)
            is RunProgress.StepCompleted -> o.put("e", "step_completed").put("agentId", e.agentId)
                .put("step", stepJson(e.step))
            is RunProgress.StepFailed -> o.put("e", "step_failed").put("stepIndex", e.stepIndex)
                .put("nodeId", e.nodeId).put("message", e.message).put("agentId", e.agentId)
            is RunProgress.GatewayDecided -> o.put("e", "gateway_decided").put("nodeId", e.nodeId)
                .put("chosenEdgeId", e.chosenEdgeId).putOpt("chosenLabel", e.chosenLabel)
                .put("alternativeEdgeIds", JSONArray(e.alternativeEdgeIds))
            is RunProgress.EdgeTaken -> o.put("e", "edge_taken").put("edgeId", e.edgeId)
                .put("from", e.fromNodeId).put("to", e.toNodeId)
            is RunProgress.RunFinished -> o.put("e", "run_finished").put("stoppedBecause", e.stoppedBecause)
                .put("reachedEnd", e.reachedEnd).put("stepCount", e.stepCount).put("elapsedMs", e.elapsedMs)
        }
    }

    private fun stepJson(s: GraphStep) = JSONObject()
        .put("index", s.index).put("nodeId", s.nodeId).put("role", s.role).putOpt("model", s.model)
        .put("output", s.output).putOpt("tokensIn", s.tokensIn).putOpt("tokensOut", s.tokensOut)
        .put("durationMs", s.durationMs).put("estimated", s.estimated)

    private fun fromJson(o: JSONObject): RunProgress? {
        if (o.optInt("v", VERSION) > VERSION) return null
        val t = o.getLong("t")
        fun optStr(k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
        return when (o.getString("e")) {
            "run_started" -> RunProgress.RunStarted(t, o.getString("objective"), o.getInt("nodeCount"), o.getInt("hardCap"))
            "node_entered" -> RunProgress.NodeEntered(
                t, o.getString("nodeId"),
                BpmnNodeKind.fromElement(o.getString("kind")) ?: return null,
                optStr("viaEdgeId"),
            )
            "step_started" -> RunProgress.StepStarted(
                t, o.getInt("stepIndex"), o.getString("nodeId"), o.getString("role"), optStr("model"),
                o.optString("agentId", RunProgress.agentIdFor(o.getInt("stepIndex"))),
                o.optString("parentAgentId", RunProgress.ROOT_AGENT),
            )
            "step_delta" -> RunProgress.StepDelta(
                t, o.getInt("stepIndex"), o.getString("nodeId"), o.getString("text"),
                o.optString("agentId", RunProgress.agentIdFor(o.getInt("stepIndex"))),
            )
            "step_completed" -> {
                val s = o.getJSONObject("step")
                val step = GraphStep(
                    index = s.getInt("index"), nodeId = s.getString("nodeId"), role = s.getString("role"),
                    model = if (s.has("model") && !s.isNull("model")) s.getString("model") else null,
                    output = s.getString("output"),
                    tokensIn = if (s.has("tokensIn")) s.getLong("tokensIn") else null,
                    tokensOut = if (s.has("tokensOut")) s.getLong("tokensOut") else null,
                    durationMs = s.optLong("durationMs", 0L), estimated = s.optBoolean("estimated", false),
                )
                RunProgress.StepCompleted(t, step, o.optString("agentId", RunProgress.agentIdFor(step.index)))
            }
            "step_failed" -> RunProgress.StepFailed(
                t, o.getInt("stepIndex"), o.getString("nodeId"), o.getString("message"),
                o.optString("agentId", RunProgress.agentIdFor(o.getInt("stepIndex"))),
            )
            "gateway_decided" -> RunProgress.GatewayDecided(
                t, o.getString("nodeId"), o.getString("chosenEdgeId"), optStr("chosenLabel"),
                o.getJSONArray("alternativeEdgeIds").let { a -> List(a.length()) { a.getString(it) } },
            )
            "edge_taken" -> RunProgress.EdgeTaken(t, o.getString("edgeId"), o.getString("from"), o.getString("to"))
            "run_finished" -> RunProgress.RunFinished(
                t, o.getString("stoppedBecause"), o.getBoolean("reachedEnd"), o.getInt("stepCount"), o.getLong("elapsedMs"),
            )
            else -> null
        }
    }
}
