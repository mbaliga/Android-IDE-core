package dev.aarso.domain.council

/**
 * A named voice in the council (handoff §4b — Mixture-of-Agents, **never** "MoE").
 * Persona-diversity (§4b default): one resident model wearing different hats via
 * different system prompts. The council is the *lateral* axis of the tree — N
 * sibling responses held simultaneously, with the user as the router/aggregator
 * who can see and choose. This is the inverse of MoE's hidden, learned routing.
 */
data class Agent(val name: String, val systemPrompt: String)

object Council {
    /** Default panel — distinct stances, editable later. */
    val defaultAgents: List<Agent> = listOf(
        Agent("Proposer", "Give a direct, concrete answer. Commit to a position."),
        Agent("Skeptic", "Challenge assumptions; surface risks, edge cases, and counterarguments."),
        Agent("Synthesizer", "Weigh the trade-offs and give a balanced, practical synthesis."),
    )

    /** System prompt for the optional model-as-aggregator convergence turn (§4b). */
    fun aggregatorSystemPrompt(): String =
        "You are the aggregator. Read the panel's answers below and produce one " +
            "convergent answer: keep what's strongest, reconcile disagreements, note any " +
            "remaining uncertainty. Be concise."

    fun aggregatorUserPrompt(question: String, answers: List<Pair<String, String>>): String =
        buildString {
            append("Question:\n").append(question).append("\n\n")
            for ((name, text) in answers) {
                append("— ").append(name).append(" —\n").append(text).append("\n\n")
            }
            append("Now give the convergent answer.")
        }
}
