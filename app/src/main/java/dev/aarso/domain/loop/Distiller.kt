package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.Bounds
import dev.aarso.domain.bpmn.BpmnArchive
import dev.aarso.domain.bpmn.BpmnEdge
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNode
import dev.aarso.domain.bpmn.BpmnNodeKind
import dev.aarso.domain.council.Generator

/**
 * The distiller — the *ceiling* of loop distillation (docs/design/loop-distillation.md).
 * It reads a described method (a paper's method section / a pasted description) and
 * produces an editable [Loop]. Aarso eating its own tail: a loop that makes loops.
 *
 * **Division of labour (the honest design):** the *model* does the understanding —
 * classify the method into one of a small set of orchestration topologies and extract
 * its parameters into a compact, inspectable [DistilledSpec]. The *code* does the
 * construction — it builds a guaranteed-valid [BpmnGraph] from that spec, serialised to
 * the loop's [Loop.bpmnXml]. So the output is always valid, transportable BPMN (it can
 * only emit graphs from a known-good family) and the model never hand-writes XML. This
 * is the "legible first draft you refine," not a verified reproduction.
 *
 * The model is the caller's [Generator] — on-device or a watched cloud model; the
 * distiller is model-agnostic and never defaults to cloud (CLAUDE.md #2). The result is
 * an **Unused** [Loop]; provenance (source + model + date) is stamped into the BPMN's
 * start-event `<aarso:meta>`, so the influence stays visible and travels with the file.
 *
 * Pure Kotlin (suspend over [Generator]); JVM-tested against a scripted fake generator.
 */
class Distiller(private val extractor: Generator) {

    suspend fun distill(
        source: String,
        sourceLabel: String,
        distilledBy: String,
        distilledOn: String,
        id: String,
        now: Long = 0L,
        maxAttempts: Int = 2,
    ): DistillResult {
        var lastRaw = ""
        var hint = ""
        val attempts = maxAttempts.coerceAtLeast(1)
        for (i in 0 until attempts) {
            val raw = extractor.complete(SYSTEM, userPrompt(source, hint)).trim()
            lastRaw = raw
            when (val parsed = parse(raw)) {
                is Parse.Unparseable ->
                    hint = "Your reply could not be parsed. Reply with ONLY the KEY: value " +
                        "lines, and choose a PATTERN from: ${TopologyKind.keys}."
                is Parse.Ok -> {
                    if (parsed.recognised || i == attempts - 1) {
                        val ext = provenance(parsed.spec, sourceLabel, distilledBy, distilledOn)
                        val graph = TopologyBuilder.build(id, parsed.spec, ext)
                        return if (valid(graph)) {
                            DistillResult.Ok(
                                loop = Loop(
                                    id = id,
                                    name = parsed.spec.title,
                                    bpmnXml = BpmnArchive.write(graph),
                                    state = LoopState.UNUSED,
                                    createdAt = now,
                                    updatedAt = now,
                                ),
                                spec = parsed.spec,
                            )
                        } else {
                            DistillResult.Failed("built graph failed validation", raw)
                        }
                    }
                    hint = "Be explicit: pick the closest PATTERN from ${TopologyKind.keys}."
                }
            }
        }
        return DistillResult.Failed("could not distil a loop from the source", lastRaw)
    }

    // ---- parsing the line format into a spec ----

    private sealed interface Parse {
        data object Unparseable : Parse
        data class Ok(val spec: DistilledSpec, val recognised: Boolean) : Parse
    }

    private fun parse(raw: String): Parse {
        val map = LinkedHashMap<String, String>()
        val roles = ArrayList<DistilledSpec.Role>()
        for (line in raw.lines()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = clean(line.substring(idx + 1))
            if (value.isEmpty()) continue
            if (key == "role") {
                val bar = value.indexOf('|')
                val name = clean(if (bar >= 0) value.substring(0, bar) else value)
                val instr = clean(if (bar >= 0) value.substring(bar + 1) else "")
                if (name.isNotEmpty()) roles += DistilledSpec.Role(name, instr.ifEmpty { name })
            } else if (key !in map) {
                map[key] = value
            }
        }

        val patternStr = map["pattern"]
        val kind = patternStr?.let { TopologyKind.from(it) }
        val recognised = kind != null
        if (!recognised && roles.isEmpty() && map["title"] == null) return Parse.Unparseable
        val effective = kind ?: TopologyKind.PIPELINE

        val spec = DistilledSpec(
            title = map["title"]?.let(::clean)?.ifEmpty { null } ?: "Distilled loop",
            pattern = effective,
            agents = (map["agents"]?.toIntOrNull() ?: defaultAgents(effective)).coerceIn(1, 6),
            rounds = (map["rounds"]?.toIntOrNull() ?: 1).coerceIn(1, 5),
            aggregation = map["aggregation"]?.lowercase()?.takeIf { it.isNotBlank() && it != "none" },
            stop = map["stop"]?.let(::clean)?.ifEmpty { null } ?: "rounds",
            roles = roles.ifEmpty { defaultRoles(effective) },
            summary = map["summary"]?.let(::clean)?.ifEmpty { null } ?: (map["title"] ?: "Distilled loop"),
        )
        return Parse.Ok(spec, recognised)
    }

    private fun provenance(spec: DistilledSpec, source: String, by: String, on: String): Map<String, String> =
        linkedMapOf(
            "source" to source,
            "pattern" to spec.pattern.key,
            "distilledBy" to by,
            "distilledOn" to on,
            "summary" to spec.summary,
        )

    private fun valid(g: BpmnGraph): Boolean {
        val ids = g.nodes.map { it.id }.toSet()
        if (ids.size != g.nodes.size) return false
        if (g.nodes.count { it.kind == BpmnNodeKind.START_EVENT } != 1) return false
        if (g.nodes.none { it.kind == BpmnNodeKind.END_EVENT }) return false
        if (g.edges.any { it.sourceId !in ids || it.targetId !in ids }) return false
        return BpmnArchive.read(BpmnArchive.write(g)) == g
    }

    private companion object {
        val SYSTEM =
            "You convert a described multi-agent or multi-step method into a loop topology.\n" +
                "Read the METHOD and reply with ONLY these lines (KEY: value), nothing else:\n" +
                "TITLE: <short name>\n" +
                "PATTERN: <one of: ${TopologyKind.keys}>\n" +
                "AGENTS: <integer parallel agents/samples; 1 if not applicable>\n" +
                "ROUNDS: <integer loop iterations; 1 if a single pass>\n" +
                "AGGREGATION: <synthesize, majority, judge, or none>\n" +
                "STOP: <short stop condition, e.g. rounds, critic-approves, score-threshold>\n" +
                "ROLE: <name> | <one-line instruction>   (repeat for each distinct expert)\n" +
                "SUMMARY: <one sentence>\n" +
                "Pick the closest PATTERN. One line per ROLE."

        fun userPrompt(source: String, hint: String): String = buildString {
            if (hint.isNotEmpty()) append(hint).append("\n\n")
            append("METHOD:\n").append(source)
        }

        fun defaultAgents(k: TopologyKind) = when (k) {
            TopologyKind.SAMPLE_VOTE -> 5
            TopologyKind.FAN_OUT_AGGREGATE, TopologyKind.DEBATE -> 3
            else -> 1
        }

        fun defaultRoles(k: TopologyKind): List<DistilledSpec.Role> = when (k) {
            TopologyKind.FAN_OUT_AGGREGATE -> listOf(
                DistilledSpec.Role("Proposer", "Answer the objective directly."),
                DistilledSpec.Role("Aggregator", "Synthesise the proposals into one answer."),
            )
            TopologyKind.SAMPLE_VOTE -> listOf(
                DistilledSpec.Role("Solver", "Reason step by step, then give a final answer."),
            )
            TopologyKind.REFINE_LOOP -> listOf(
                DistilledSpec.Role("Actor", "Attempt the task."),
                DistilledSpec.Role("Evaluator", "Score the attempt against the objective."),
                DistilledSpec.Role("Reflector", "Write verbal feedback to improve the next attempt."),
            )
            TopologyKind.DEBATE -> listOf(
                DistilledSpec.Role("Debater", "Argue your answer; revise after seeing the others."),
                DistilledSpec.Role("Judge", "Read the debate and decide the final answer."),
            )
            TopologyKind.PIPELINE -> listOf(DistilledSpec.Role("Step", "Do the task."))
        }

        fun clean(s: String): String = s.trim().replace(Regex("\\s+"), " ")
    }
}

/** The structured topology the extractor fills in — the inspectable middle step. */
data class DistilledSpec(
    val title: String,
    val pattern: TopologyKind,
    val agents: Int,
    val rounds: Int,
    val aggregation: String?,
    val stop: String,
    val roles: List<Role>,
    val summary: String,
) {
    data class Role(val name: String, val instruction: String)
}

/** The small family of orchestration topologies the distiller can build (always valid). */
enum class TopologyKind(val key: String) {
    FAN_OUT_AGGREGATE("fan-out-aggregate"),
    SAMPLE_VOTE("sample-vote"),
    REFINE_LOOP("refine-loop"),
    DEBATE("debate"),
    PIPELINE("pipeline"),
    ;

    companion object {
        fun from(s: String): TopologyKind? {
            val k = s.trim().lowercase()
            return entries.firstOrNull { it.key == k || it.name.lowercase() == k }
        }

        val keys: String get() = entries.joinToString(", ") { it.key }
    }
}

sealed interface DistillResult {
    data class Ok(val loop: Loop, val spec: DistilledSpec) : DistillResult
    data class Failed(val reason: String, val raw: String) : DistillResult
}

/** Deterministic spec → valid BpmnGraph. Each builder emits start + end + connected edges. */
private object TopologyBuilder {

    fun build(id: String, spec: DistilledSpec, startExt: Map<String, String>): BpmnGraph = when (spec.pattern) {
        TopologyKind.FAN_OUT_AGGREGATE -> fanOutAggregate(id, spec, startExt)
        TopologyKind.SAMPLE_VOTE -> sampleVote(id, spec, startExt)
        TopologyKind.REFINE_LOOP -> refineLoop(id, spec, startExt)
        TopologyKind.DEBATE -> debate(id, spec, startExt)
        TopologyKind.PIPELINE -> pipeline(id, spec, startExt)
    }

    private fun ev(x: Double) = Bounds(x, 140.0, 36.0, 36.0)
    private fun gw(x: Double) = Bounds(x, 133.0, 50.0, 50.0)
    private fun tk(x: Double, row: Int = 0) = Bounds(x, 110.0 + row * 90.0, 130.0, 64.0)
    private const val COL = 160.0

    private fun start(ext: Map<String, String>) =
        BpmnNode("start", BpmnNodeKind.START_EVENT, "Objective", ev(40.0), ext)

    private fun fanOutAggregate(id: String, s: DistilledSpec, ext: Map<String, String>): BpmnGraph {
        val proposer = s.roles.firstOrNull() ?: DistilledSpec.Role("Proposer", "Answer the objective.")
        val agg = s.roles.getOrNull(1) ?: DistilledSpec.Role("Aggregator", "Synthesise the proposals.")
        val nodes = ArrayList<BpmnNode>()
        val edges = ArrayList<BpmnEdge>()
        nodes += start(ext)
        nodes += BpmnNode("fork", BpmnNodeKind.PARALLEL_GATEWAY, "Fan out", gw(COL))
        edges += BpmnEdge("e_in", "start", "fork")
        val lanes = intArrayOf(-1, 0, 1)
        for (i in 0 until s.agents) {
            val pid = "p$i"
            nodes += BpmnNode(
                pid, BpmnNodeKind.SERVICE_TASK, "${proposer.name} ${i + 1}", tk(COL * 2, lanes[i % 3]),
                linkedMapOf("role" to proposer.name, "instruction" to proposer.instruction, "diversity" to "encouraged"),
            )
            edges += BpmnEdge("e_f$i", "fork", pid)
            edges += BpmnEdge("e_j$i", pid, "join")
        }
        nodes += BpmnNode("join", BpmnNodeKind.PARALLEL_GATEWAY, "Gather", gw(COL * 3))
        nodes += BpmnNode(
            "agg", BpmnNodeKind.SERVICE_TASK, agg.name, tk(COL * 3.7),
            linkedMapOf("role" to agg.name, "instruction" to agg.instruction, "aggregation" to (s.aggregation ?: "synthesize")),
        )
        edges += BpmnEdge("e_agg", "join", "agg")
        if (s.rounds > 1) {
            nodes += BpmnNode("more", BpmnNodeKind.EXCLUSIVE_GATEWAY, "More layers?", gw(COL * 4.7))
            nodes += BpmnNode("end", BpmnNodeKind.END_EVENT, "Answer", ev(COL * 5.6))
            edges += BpmnEdge("e_more", "agg", "more")
            edges += BpmnEdge("e_loop", "more", "fork", name = "another layer", condition = "layer < ${s.rounds}")
            edges += BpmnEdge("e_out", "more", "end", name = "final", condition = "layer == ${s.rounds}")
        } else {
            nodes += BpmnNode("end", BpmnNodeKind.END_EVENT, "Answer", ev(COL * 4.7))
            edges += BpmnEdge("e_out", "agg", "end")
        }
        return BpmnGraph(id, s.title, nodes, edges)
    }

    private fun sampleVote(id: String, s: DistilledSpec, ext: Map<String, String>): BpmnGraph {
        val solver = s.roles.firstOrNull() ?: DistilledSpec.Role("Solver", "Reason, then answer.")
        val nodes = ArrayList<BpmnNode>()
        val edges = ArrayList<BpmnEdge>()
        nodes += start(ext)
        nodes += BpmnNode("fork", BpmnNodeKind.PARALLEL_GATEWAY, "Sample", gw(COL))
        edges += BpmnEdge("e_in", "start", "fork")
        val lanes = intArrayOf(-1, 0, 1)
        for (i in 0 until s.agents) {
            val sid = "s$i"
            nodes += BpmnNode(
                sid, BpmnNodeKind.SERVICE_TASK, "${solver.name} ${i + 1}", tk(COL * 2, lanes[i % 3]),
                linkedMapOf("role" to solver.name, "instruction" to solver.instruction, "note" to "sampled"),
            )
            edges += BpmnEdge("e_f$i", "fork", sid)
            edges += BpmnEdge("e_j$i", sid, "join")
        }
        nodes += BpmnNode("join", BpmnNodeKind.PARALLEL_GATEWAY, "Gather", gw(COL * 3))
        nodes += BpmnNode(
            "vote", BpmnNodeKind.BUSINESS_RULE_TASK, "Vote", tk(COL * 3.7),
            linkedMapOf("aggregation" to (s.aggregation ?: "majority")),
        )
        nodes += BpmnNode("end", BpmnNodeKind.END_EVENT, "Answer", ev(COL * 4.7))
        edges += BpmnEdge("e_vote", "join", "vote")
        edges += BpmnEdge("e_out", "vote", "end")
        return BpmnGraph(id, s.title, nodes, edges)
    }

    private fun refineLoop(id: String, s: DistilledSpec, ext: Map<String, String>): BpmnGraph {
        val actor = s.roles.getOrNull(0) ?: DistilledSpec.Role("Actor", "Attempt the task.")
        val evalr = s.roles.getOrNull(1) ?: DistilledSpec.Role("Evaluator", "Score the attempt.")
        val refl = s.roles.getOrNull(2) ?: DistilledSpec.Role("Reflector", "Give feedback.")
        val nodes = listOf(
            start(ext),
            BpmnNode("actor", BpmnNodeKind.SERVICE_TASK, actor.name, tk(COL),
                linkedMapOf("role" to actor.name, "instruction" to actor.instruction)),
            BpmnNode("eval", BpmnNodeKind.SERVICE_TASK, evalr.name, tk(COL * 2),
                linkedMapOf("role" to evalr.name, "instruction" to evalr.instruction, "diversity" to "encouraged")),
            BpmnNode("gw", BpmnNodeKind.EXCLUSIVE_GATEWAY, "Succeeded?", gw(COL * 3)),
            BpmnNode("reflect", BpmnNodeKind.SERVICE_TASK, refl.name, tk(COL * 2, 1),
                linkedMapOf("role" to refl.name, "instruction" to refl.instruction, "memory" to "verbal")),
            BpmnNode("end", BpmnNodeKind.END_EVENT, "Solution", ev(COL * 4)),
        )
        val edges = listOf(
            BpmnEdge("e_in", "start", "actor"),
            BpmnEdge("e_ev", "actor", "eval"),
            BpmnEdge("e_gw", "eval", "gw"),
            BpmnEdge("e_yes", "gw", "end", name = "yes", condition = "evaluation == success"),
            BpmnEdge("e_no", "gw", "reflect", name = "no", condition = "evaluation == fail"),
            BpmnEdge("e_loop", "reflect", "actor", name = "retry", condition = "trials < ${s.rounds.coerceAtLeast(2)}"),
        )
        return BpmnGraph(id, s.title, nodes, edges)
    }

    private fun debate(id: String, s: DistilledSpec, ext: Map<String, String>): BpmnGraph {
        val debater = s.roles.firstOrNull() ?: DistilledSpec.Role("Debater", "Argue and revise.")
        val judge = s.roles.getOrNull(1) ?: DistilledSpec.Role("Judge", "Decide the final answer.")
        val nodes = ArrayList<BpmnNode>()
        val edges = ArrayList<BpmnEdge>()
        nodes += start(ext)
        nodes += BpmnNode("fork", BpmnNodeKind.PARALLEL_GATEWAY, "Open", gw(COL))
        edges += BpmnEdge("e_in", "start", "fork")
        val lanes = intArrayOf(-1, 0, 1)
        for (i in 0 until s.agents) {
            val did = "d$i"
            nodes += BpmnNode(
                did, BpmnNodeKind.SERVICE_TASK, "${debater.name} ${i + 1}", tk(COL * 2, lanes[i % 3]),
                linkedMapOf("role" to debater.name, "instruction" to debater.instruction, "diversity" to "encouraged"),
            )
            edges += BpmnEdge("e_f$i", "fork", did)
            edges += BpmnEdge("e_j$i", did, "join")
        }
        nodes += BpmnNode("join", BpmnNodeKind.PARALLEL_GATEWAY, "Exchange", gw(COL * 3))
        nodes += BpmnNode("more", BpmnNodeKind.EXCLUSIVE_GATEWAY, "More rounds?", gw(COL * 3.8))
        nodes += BpmnNode(
            "judge", BpmnNodeKind.SERVICE_TASK, judge.name, tk(COL * 4.5, 1),
            linkedMapOf("role" to judge.name, "instruction" to judge.instruction, "aggregation" to (s.aggregation ?: "judge")),
        )
        nodes += BpmnNode("end", BpmnNodeKind.END_EVENT, "Verdict", ev(COL * 5.6))
        edges += BpmnEdge("e_more", "join", "more")
        edges += BpmnEdge("e_loop", "more", "fork", name = "another round", condition = "round < ${s.rounds}")
        edges += BpmnEdge("e_judge", "more", "judge", name = "conclude", condition = "round == ${s.rounds}")
        edges += BpmnEdge("e_out", "judge", "end")
        return BpmnGraph(id, s.title, nodes, edges)
    }

    private fun pipeline(id: String, s: DistilledSpec, ext: Map<String, String>): BpmnGraph {
        val nodes = ArrayList<BpmnNode>()
        val edges = ArrayList<BpmnEdge>()
        nodes += start(ext)
        var prev = "start"
        s.roles.forEachIndexed { i, r ->
            val nid = "step$i"
            nodes += BpmnNode(
                nid, BpmnNodeKind.SERVICE_TASK, r.name, tk(COL * (i + 1)),
                linkedMapOf("role" to r.name, "instruction" to r.instruction),
            )
            edges += BpmnEdge("e$i", prev, nid)
            prev = nid
        }
        nodes += BpmnNode("end", BpmnNodeKind.END_EVENT, "Done", ev(COL * (s.roles.size + 1)))
        edges += BpmnEdge("e_end", prev, "end")
        return BpmnGraph(id, s.title, nodes, edges)
    }
}
