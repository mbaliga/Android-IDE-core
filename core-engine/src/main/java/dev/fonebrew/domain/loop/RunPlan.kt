package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnGraph

/** One tool node as the run would execute it, with `${param}`s already substituted. */
data class ToolUse(
    val nodeId: String,
    val nodeName: String,
    val toolId: String,
    val sideEffect: ToolSideEffect,
    val hosts: List<String>,
    val argsSummary: String,
)

/**
 * What a run WOULD do beyond calling models: every tool node, every host contacted. Computed from
 * the graph + params before any step runs; this is what the confirmation sheet lists and what
 * [GraphRunner] checks against [RunConfirmation] (docs/design/loop-tools.md, "Run-time gate").
 */
data class RunPlan(
    val toolUses: List<ToolUse>,
    /** `(nodeId, toolId)` for tool ids this build does not know. */
    val unsupported: List<Pair<String, String>>,
    /** `(nodeId, message)` for tool nodes whose arguments are invalid. */
    val invalid: List<Pair<String, String>>,
) {
    val toolIds: Set<String> get() = toolUses.map { it.toolId }.toCollection(LinkedHashSet())
    val hosts: Set<String> get() = toolUses.flatMap { it.hosts }.toCollection(LinkedHashSet())
    val usesNetwork: Boolean get() = toolUses.any { it.sideEffect == ToolSideEffect.NETWORK }
    val isEmpty: Boolean get() = toolUses.isEmpty() && unsupported.isEmpty() && invalid.isEmpty()

    /** Human reasons the plan is NOT covered by [confirmation]; empty = covered. */
    fun uncovered(confirmation: RunConfirmation?): List<String> {
        val have = confirmation ?: RunConfirmation.NONE
        val out = ArrayList<String>()
        toolIds.filterNot { it in have.toolIds }.forEach { out += "tool $it" }
        hosts.filterNot { it in have.hosts }.forEach { out += "host $it" }
        return out
    }

    companion object {
        fun of(graph: BpmnGraph, registry: LoopToolRegistry, params: Map<String, String>): RunPlan {
            val uses = ArrayList<ToolUse>()
            val unsupported = ArrayList<Pair<String, String>>()
            val invalid = ArrayList<Pair<String, String>>()
            for (n in graph.nodes) {
                if (!ToolNodes.isToolNode(n)) continue
                val id = ToolNodes.toolId(n)!!
                val tool = registry.find(id)
                if (tool == null) { unsupported += n.id to id; continue }
                val args = ToolNodes.args(n, params)
                val err = tool.validate(args)
                if (err != null) { invalid += n.id to err; continue }
                uses += ToolUse(n.id, n.name.ifBlank { n.id }, id, tool.sideEffect, tool.hosts(args), tool.summarize(args))
            }
            return RunPlan(uses, unsupported, invalid)
        }
    }
}

/** What the user explicitly confirmed on the run-start sheet. [NONE] is the default everywhere. */
data class RunConfirmation(val toolIds: Set<String>, val hosts: Set<String>) {
    companion object {
        val NONE = RunConfirmation(emptySet(), emptySet())

        /** The confirmation the sheet produces when the user accepts exactly [plan]. */
        fun accepting(plan: RunPlan) = RunConfirmation(plan.toolIds, plan.hosts)
    }
}

/** The run-start confirmation sheet's content as pure data (glyph + label; never colour alone). */
data class RunConfirmationSheet(
    val needed: Boolean,
    val headline: String,
    val toolLines: List<String>,
    val hostLines: List<String>,
    val warnings: List<String>,
    /** True while the plan is unrunnable (unsupported/invalid); the sheet must not offer Run. */
    val blocked: Boolean,
)

object RunConfirmationPresenter {

    fun present(
        plan: RunPlan,
        /** Hosts the user confirmed for this loop last time; hosts outside it are flagged NEW. */
        lastConfirmedHosts: Set<String>?,
        imported: Boolean,
        hasCloudSteps: Boolean,
    ): RunConfirmationSheet {
        if (plan.isEmpty) return RunConfirmationSheet(false, "", emptyList(), emptyList(), emptyList(), false)

        val toolLines = plan.toolUses.groupBy { it.toolId }.map { (id, uses) ->
            val g = uses.first().sideEffect
            "${g.glyph} $id (${g.label}) - ${uses.size} node${if (uses.size == 1) "" else "s"}"
        }
        val hostLines = plan.hosts.map { h ->
            val isNew = lastConfirmedHosts != null && h !in lastConfirmedHosts
            val first = lastConfirmedHosts == null
            "${ToolSideEffect.NETWORK.glyph} network: $h" + when {
                isNew && !first -> "  (NEW since you last confirmed)"
                first -> "  (first time confirming this loop)"
                else -> ""
            }
        }
        val warnings = ArrayList<String>()
        if (imported) warnings += "⚠ This loop came from an import and contacts the network. Check each host below before you run it."
        if (plan.toolUses.isNotEmpty() && hasCloudSteps) {
            warnings += "⚠ Tool and network results flow into later model steps, including cloud models (watched objects)."
        }
        plan.unsupported.forEach { (n, t) -> warnings += "⛔ Unsupported in this version: node $n uses tool \"$t\". The run cannot start." }
        plan.invalid.forEach { (n, m) -> warnings += "⛔ Node $n: $m. The run cannot start." }
        val net = plan.usesNetwork
        val headline = if (net) "This run contacts ${plan.hosts.size} host${if (plan.hosts.size == 1) "" else "s"} and uses ${plan.toolIds.size} tool${if (plan.toolIds.size == 1) "" else "s"}."
        else "This run uses ${plan.toolIds.size} tool${if (plan.toolIds.size == 1) "" else "s"} (no network)."
        return RunConfirmationSheet(
            needed = true, headline = headline, toolLines = toolLines, hostLines = hostLines,
            warnings = warnings, blocked = plan.unsupported.isNotEmpty() || plan.invalid.isNotEmpty(),
        )
    }
}

/** Egress notice for the import review (informational; the runner gate is what enforces). */
data class EgressNotice(val hosts: List<String>, val toolIds: List<String>) {
    val present: Boolean get() = toolIds.isNotEmpty()
    fun lines(): List<String> = buildList {
        if (!present) return@buildList
        if (hosts.isNotEmpty()) add("${ToolSideEffect.NETWORK.glyph} Network: this loop contacts ${hosts.joinToString(", ")}. It will not run until you confirm those hosts.")
        add("Tools used: ${toolIds.joinToString(", ")}.")
    }

    companion object {
        val NONE = EgressNotice(emptyList(), emptyList())

        /** Registry-aware scan of the RAW (un-substituted) args, so tools this build does not know
         *  still show up by id. A network node whose host is not yet known (a `${param}` in the
         *  url) is listed as such rather than dropped. */
        fun of(graph: BpmnGraph, registry: LoopToolRegistry): EgressNotice {
            val toolNodes = graph.nodes.filter(ToolNodes::isToolNode)
            val ids = toolNodes.mapNotNull(ToolNodes::toolId).distinct()
            val hosts = LinkedHashSet<String>()
            for (n in toolNodes) {
                val t = registry.find(ToolNodes.toolId(n)!!)
                val raw = ToolNodes.rawArgs(n)
                if (t == null) {
                    if (raw.containsKey("url")) hosts += "(unrecognised tool with a url)"
                    continue
                }
                val h = t.hosts(raw)
                if (h.isNotEmpty()) hosts += h
                else if (t.sideEffect == ToolSideEffect.NETWORK) hosts += "(host set by a parameter)"
            }
            return EgressNotice(hosts.toList(), ids)
        }
    }
}
