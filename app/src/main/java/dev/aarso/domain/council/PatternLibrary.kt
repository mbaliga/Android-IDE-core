package dev.aarso.domain.council

import dev.aarso.domain.bpmn.Bounds
import dev.aarso.domain.bpmn.BpmnEdge
import dev.aarso.domain.bpmn.BpmnGraph
import dev.aarso.domain.bpmn.BpmnNode
import dev.aarso.domain.bpmn.BpmnNodeKind

/**
 * The curated reference library — the *floor* of loop distillation
 * (docs/design/loop-distillation.md). The owner's insight: orchestration papers
 * (mixture-of-agents, self-consistency, reflexion, debate, …) are **nothing but
 * loops** in Aarso's terminology. This hand-authors the first four as standard
 * [BpmnGraph]s so they are runnable, readable, editable, and ownable today —
 * independent of the (later) meta-distiller that would emit new ones from a paper.
 *
 * Each pattern is a valid BPMN 2.0 graph: it round-trips through
 * `domain/bpmn/BpmnArchive` (so it opens in any BPMN tool and syncs to the user's
 * Git host as `.bpmn`), carries its **provenance** in the start event's
 * `<aarso:meta>` extension, and is **model-agnostic** — nodes name a `role`, and
 * where model-diversity is the research point a `diversity` hint, but **no node
 * defaults to a watched cloud model** (CLAUDE.md #2: on-device is the default; the
 * user assigns models when a loop moves Unused → Running).
 *
 * Naming (CLAUDE.md §3): council / experts / loop — never "MoE". "Mixture of Agents"
 * is a real paper title and is fine; what we never do is rebrand our own council.
 *
 * Pure Kotlin; JVM-tested. Execution stays with the graph runner.
 */
data class ReferencePattern(
    /** Stable short id, e.g. "moa". */
    val id: String,
    /** Human title, e.g. "Mixture of Agents". */
    val title: String,
    /** Citation / source the loop was distilled from (provenance). */
    val source: String,
    /** One line: what the loop does. */
    val summary: String,
    /** The editable loop definition. */
    val graph: BpmnGraph,
)

object PatternLibrary {

    /** Every curated pattern, in a stable display order. */
    val all: List<ReferencePattern> by lazy {
        listOf(mixtureOfAgents, selfConsistency, reflexion, multiAgentDebate)
    }

    fun byId(id: String): ReferencePattern? = all.firstOrNull { it.id == id }

    // ---- shape helpers (kept tiny, like EscalationBpmn) ----

    private const val ROW = 150.0          // baseline lane y
    private const val COL = 160.0          // column spacing x

    private fun start(id: String, name: String, x: Double, ext: Map<String, String>) =
        BpmnNode(id, BpmnNodeKind.START_EVENT, name, Bounds(x, ROW + 14, 36.0, 36.0), ext)

    private fun end(id: String, name: String, x: Double, y: Double = ROW) =
        BpmnNode(id, BpmnNodeKind.END_EVENT, name, Bounds(x, y + 14, 36.0, 36.0))

    private fun task(id: String, name: String, x: Double, y: Double, ext: Map<String, String> = emptyMap()) =
        BpmnNode(id, BpmnNodeKind.SERVICE_TASK, name, Bounds(x, y, 130.0, 64.0), ext)

    private fun rule(id: String, name: String, x: Double, y: Double, ext: Map<String, String> = emptyMap()) =
        BpmnNode(id, BpmnNodeKind.BUSINESS_RULE_TASK, name, Bounds(x, y, 130.0, 64.0), ext)

    private fun xor(id: String, name: String, x: Double, y: Double = ROW + 7) =
        BpmnNode(id, BpmnNodeKind.EXCLUSIVE_GATEWAY, name, Bounds(x, y, 50.0, 50.0))

    private fun fork(id: String, name: String, x: Double, y: Double = ROW + 7) =
        BpmnNode(id, BpmnNodeKind.PARALLEL_GATEWAY, name, Bounds(x, y, 50.0, 50.0))

    // =====================================================================
    // 1. Mixture of Agents (Wang et al. 2024, arXiv 2406.04692)
    //    Layered: N proposers answer in parallel; an aggregator synthesises;
    //    the aggregate feeds the next layer's proposers. Diversity = models.
    // =====================================================================
    val mixtureOfAgents: ReferencePattern by lazy {
        val src = "Wang et al. 2024 — Mixture-of-Agents (arXiv:2406.04692)"
        val summary = "N proposers answer in parallel each layer; an aggregator " +
            "synthesises; the aggregate feeds the next layer."
        val lanes = listOf(ROW - 100, ROW, ROW + 100)
        val proposers = (0..2).map { i ->
            task(
                "moa_p$i", "Proposer ${('A' + i)}", COL * 2, lanes[i],
                linkedMapOf("role" to "Proposer", "diversity" to "encouraged"),
            )
        }
        val nodes = buildList {
            add(
                start(
                    "moa_start", "Objective", COL * 0.5,
                    linkedMapOf(
                        "source" to src, "summary" to summary,
                        "pattern" to "mixture-of-agents", "layers" to "3",
                    ),
                ),
            )
            add(fork("moa_fork", "Fan out", COL * 1.4))
            addAll(proposers)
            add(fork("moa_join", "Gather", COL * 3))
            add(task("moa_agg", "Aggregator", COL * 3.7, ROW, linkedMapOf("role" to "Aggregator", "aggregation" to "synthesize")))
            add(xor("moa_more", "More layers?", COL * 4.7))
            add(end("moa_end", "Answer", COL * 5.6))
        }
        val edges = buildList {
            add(BpmnEdge("moa_e_in", "moa_start", "moa_fork"))
            proposers.forEachIndexed { i, p -> add(BpmnEdge("moa_e_f$i", "moa_fork", p.id)) }
            proposers.forEachIndexed { i, p -> add(BpmnEdge("moa_e_j$i", p.id, "moa_join")) }
            add(BpmnEdge("moa_e_agg", "moa_join", "moa_agg"))
            add(BpmnEdge("moa_e_more", "moa_agg", "moa_more"))
            add(BpmnEdge("moa_e_loop", "moa_more", "moa_fork", name = "another layer", condition = "layer < layers"))
            add(BpmnEdge("moa_e_out", "moa_more", "moa_end", name = "final", condition = "layer == layers"))
        }
        ReferencePattern("moa", "Mixture of Agents", src, summary, BpmnGraph("pattern_moa", "Mixture of Agents", nodes, edges))
    }

    // =====================================================================
    // 2. Self-consistency (Wang et al. 2022, arXiv 2203.11171)
    //    Sample N reasoning paths from ONE model, then majority-vote.
    //    Diversity is *sampling*, not models — distinct from MoA.
    // =====================================================================
    val selfConsistency: ReferencePattern by lazy {
        val src = "Wang et al. 2022 — Self-Consistency (arXiv:2203.11171)"
        val summary = "Sample N reasoning paths from one model, then take the " +
            "majority answer. Diversity is sampling, not models."
        val lanes = listOf(ROW - 100, ROW, ROW + 100)
        val samples = (0..2).map { i ->
            task(
                "sc_s$i", "Reason (sample ${i + 1})", COL * 2, lanes[i],
                linkedMapOf("role" to "Solver", "note" to "same model, sampled"),
            )
        }
        val nodes = buildList {
            add(
                start(
                    "sc_start", "Question", COL * 0.5,
                    linkedMapOf(
                        "source" to src, "summary" to summary,
                        "pattern" to "self-consistency", "samples" to "3",
                    ),
                ),
            )
            add(fork("sc_fork", "Sample N", COL * 1.4))
            addAll(samples)
            add(fork("sc_join", "Gather", COL * 3))
            add(rule("sc_vote", "Majority vote", COL * 3.7, ROW, linkedMapOf("aggregation" to "majority")))
            add(end("sc_end", "Answer", COL * 4.6))
        }
        val edges = buildList {
            add(BpmnEdge("sc_e_in", "sc_start", "sc_fork"))
            samples.forEachIndexed { i, s -> add(BpmnEdge("sc_e_f$i", "sc_fork", s.id)) }
            samples.forEachIndexed { i, s -> add(BpmnEdge("sc_e_j$i", s.id, "sc_join")) }
            add(BpmnEdge("sc_e_vote", "sc_join", "sc_vote"))
            add(BpmnEdge("sc_e_out", "sc_vote", "sc_end"))
        }
        ReferencePattern("self-consistency", "Self-Consistency", src, summary, BpmnGraph("pattern_self_consistency", "Self-Consistency", nodes, edges))
    }

    // =====================================================================
    // 3. Reflexion (Shinn et al. 2023, arXiv 2303.11366)
    //    Actor attempts; evaluator scores; on failure a self-reflection writes
    //    verbal feedback to memory and the actor retries with it.
    // =====================================================================
    val reflexion: ReferencePattern by lazy {
        val src = "Shinn et al. 2023 — Reflexion (arXiv:2303.11366)"
        val summary = "Actor attempts; evaluator scores; on failure a self-reflection " +
            "writes verbal feedback to memory and the actor retries."
        val nodes = buildList {
            add(
                start(
                    "rx_start", "Task", COL * 0.5,
                    linkedMapOf(
                        "source" to src, "summary" to summary,
                        "pattern" to "reflexion", "memory" to "verbal",
                    ),
                ),
            )
            add(task("rx_actor", "Actor — attempt", COL * 1.4, ROW, linkedMapOf("role" to "Actor")))
            add(task("rx_eval", "Evaluator — score", COL * 2.5, ROW, linkedMapOf("role" to "Evaluator", "diversity" to "encouraged")))
            add(xor("rx_gw", "Succeeded?", COL * 3.6))
            add(task("rx_reflect", "Self-reflection", COL * 2.5, ROW + 120, linkedMapOf("role" to "Reflector", "memory" to "verbal")))
            add(end("rx_end", "Solution", COL * 4.5))
        }
        val edges = buildList {
            add(BpmnEdge("rx_e_in", "rx_start", "rx_actor"))
            add(BpmnEdge("rx_e_eval", "rx_actor", "rx_eval"))
            add(BpmnEdge("rx_e_gw", "rx_eval", "rx_gw"))
            add(BpmnEdge("rx_e_yes", "rx_gw", "rx_end", name = "yes", condition = "evaluation == success"))
            add(BpmnEdge("rx_e_no", "rx_gw", "rx_reflect", name = "no", condition = "evaluation == fail"))
            add(BpmnEdge("rx_e_loop", "rx_reflect", "rx_actor", name = "retry with memory", condition = "trials < max"))
        }
        ReferencePattern("reflexion", "Reflexion", src, summary, BpmnGraph("pattern_reflexion", "Reflexion", nodes, edges))
    }

    // =====================================================================
    // 4. Multi-agent debate (Du et al. 2023, arXiv 2305.14325)
    //    N agents answer; over R rounds each revises seeing the others;
    //    a judge converges. Diversity = models strengthens the debate.
    // =====================================================================
    val multiAgentDebate: ReferencePattern by lazy {
        val src = "Du et al. 2023 — Multi-Agent Debate (arXiv:2305.14325)"
        val summary = "N agents answer; over R rounds each revises seeing the others; " +
            "a judge converges to a verdict."
        val lanes = listOf(ROW - 100, ROW, ROW + 100)
        val debaters = (0..2).map { i ->
            task(
                "dbt_d$i", "Debater ${('A' + i)}", COL * 2, lanes[i],
                linkedMapOf("role" to "Debater", "diversity" to "encouraged"),
            )
        }
        val nodes = buildList {
            add(
                start(
                    "dbt_start", "Question", COL * 0.5,
                    linkedMapOf(
                        "source" to src, "summary" to summary,
                        "pattern" to "multi-agent-debate", "rounds" to "3",
                    ),
                ),
            )
            add(fork("dbt_fork", "Open", COL * 1.4))
            addAll(debaters)
            add(fork("dbt_join", "Exchange", COL * 3))
            add(xor("dbt_more", "More rounds?", COL * 3.8))
            add(task("dbt_judge", "Judge — converge", COL * 4.5, ROW + 110, linkedMapOf("role" to "Judge", "aggregation" to "judge")))
            add(end("dbt_end", "Verdict", COL * 5.6))
        }
        val edges = buildList {
            add(BpmnEdge("dbt_e_in", "dbt_start", "dbt_fork"))
            debaters.forEachIndexed { i, d -> add(BpmnEdge("dbt_e_f$i", "dbt_fork", d.id)) }
            debaters.forEachIndexed { i, d -> add(BpmnEdge("dbt_e_j$i", d.id, "dbt_join")) }
            add(BpmnEdge("dbt_e_more", "dbt_join", "dbt_more"))
            add(BpmnEdge("dbt_e_loop", "dbt_more", "dbt_fork", name = "another round", condition = "round < rounds"))
            add(BpmnEdge("dbt_e_judge", "dbt_more", "dbt_judge", name = "conclude", condition = "round == rounds"))
            add(BpmnEdge("dbt_e_out", "dbt_judge", "dbt_end"))
        }
        ReferencePattern("debate", "Multi-Agent Debate", src, summary, BpmnGraph("pattern_debate", "Multi-Agent Debate", nodes, edges))
    }
}
