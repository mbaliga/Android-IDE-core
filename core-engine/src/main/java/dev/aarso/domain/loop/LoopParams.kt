package dev.aarso.domain.loop

import dev.aarso.domain.bpmn.BpmnGraph

/**
 * `${key}` placeholder substitution for a parameterized loop run (CORE_PHASES.md P3). Param
 * specs are derived by **scanning** the graph at open time — the objective plus every node's
 * system prompt — for `${...}` tokens; string-typed v1, no schema beyond "it's text the user
 * fills in." [GraphRunner.run] refuses to start rather than silently substituting an empty
 * string when a key is missing, so a run either has everything it needs or names exactly
 * what's absent. Pure; JVM-tested.
 */
object LoopParams {
    private val PLACEHOLDER = Regex("""\$\{([^}]+)}""")

    /** Every distinct `${key}` referenced by [text], in first-seen order. */
    fun placeholdersIn(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** Every distinct placeholder anywhere in [graph]: the [objective] plus each node's
     *  `ext["systemPrompt"]`. Node names/labels are display text, never templated. */
    fun scan(graph: BpmnGraph, objective: String): List<String> {
        val keys = LinkedHashSet<String>()
        keys += placeholdersIn(objective)
        for (node in graph.nodes) {
            node.ext["systemPrompt"]?.let { keys += placeholdersIn(it) }
        }
        return keys.toList()
    }

    /** Keys [scan] finds that [params] has no value for. Empty = safe to run. */
    fun missing(graph: BpmnGraph, objective: String, params: Map<String, String>): List<String> =
        scan(graph, objective).filterNot { params.containsKey(it) }

    /** Replace every `${key}` in [text] with `params[key]`. A key absent from [params] is left
     *  untouched — callers must have already refused to start via [missing] before this runs. */
    fun substitute(text: String, params: Map<String, String>): String =
        PLACEHOLDER.replace(text) { m -> params[m.groupValues[1]] ?: m.value }
}
