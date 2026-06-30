package dev.aarso.domain.council

/**
 * The loop, as an engine (docs/design/council-workflows.md). This is the headless
 * spike: it proves the council-of-experts loop — objective → propose → critique →
 * refine — without any UI or model wiring. An **expert** is a role + system prompt
 * + (optionally) the model best suited to it; the runner drives them against a
 * minimal [Generator] so it stays pure and JVM-testable. The app layer adapts a
 * real `InferenceEngine` (on-device or watched cloud) to [Generator].
 *
 * Naming (CLAUDE.md §3): council / experts / workflow — never "MoE".
 */

/** The single capability the loop needs: complete a (system, user) prompt to text. */
fun interface Generator {
    suspend fun complete(system: String, user: String): String
}

/** One expert: a role, its instruction, and the model assigned to it (null = caller's default). */
data class Expert(val role: String, val systemPrompt: String, val model: String? = null)

/** When the loop stops. A hard cap always applies on top, to bound cost. */
sealed interface Stop {
    data class MaxIterations(val n: Int) : Stop
    /** Stop once the critic begins its reply with "APPROVE". */
    data object CriticApproves : Stop
}

data class Iteration(val n: Int, val proposal: String, val critique: String, val approved: Boolean)

/**
 * A live step in a run, for the node/data-flow view (IA §G2). Emitted as the loop moves between
 * stages so the canvas can light up which node is active and what's done, in real time.
 */
data class LoopProgress(val iteration: Int, val stage: Stage, val approved: Boolean = false) {
    enum class Stage { PROPOSING, CRITIQUING, ITERATION_DONE }
}

data class RunResult(
    val iterations: List<Iteration>,
    val finalProposal: String,
    val stoppedBecause: String,
)

/**
 * Optional autonomy gating for a run (docs/design/workflow-builder.md §3). When a
 * [policy] is supplied, each build step's projected cumulative [Cost] is checked
 * against the escalation matrix before it runs:
 * - within budget ⇒ proceed silently;
 * - over budget ⇒ the matching [Gate] must clear. [approve] is the single
 *   resolution point — the app routes it (AGENT → ask an agent model; USER →
 *   inline card; TEAM_MEMBER → inbox). If [approve] is null, only AGENT gates
 *   auto-proceed; a human gate halts the run (you cannot ask a human with no
 *   callback). [testsGreen] widens the budget when the policy sets a green-tests
 *   variant — the CI signal folds into the same matrix.
 *
 * [estimateStep] sizes the upcoming build (the proposer prompt about to be sent),
 * so the gate sees the real cost. Leaving [policy]/[estimateStep] null restores the
 * ungated loop exactly.
 */
data class Gating(
    val policy: EscalationPolicy,
    val estimateStep: suspend (Expert, String) -> Cost,
    val approve: (suspend (Gate, Cost) -> Boolean)? = null,
    val testsGreen: Boolean? = null,
)

/**
 * Runs the canonical refine loop with a proposer and a critic (ideally different
 * models — that's the point of model-diversity). [generatorFor] resolves the
 * generator for each expert, so each role can run on the model best suited to it.
 * Optional [Gating] makes the loop a suspendable escalation machine. Richer graphs
 * come later; this is the core engine.
 */
class WorkflowRunner(private val generatorFor: (Expert) -> Generator) {

    suspend fun run(
        objective: String,
        proposer: Expert,
        critic: Expert,
        stop: Stop,
        hardCap: Int = 8,
        gating: Gating? = null,
        progress: (suspend (LoopProgress) -> Unit)? = null,
    ): RunResult {
        val iterations = mutableListOf<Iteration>()
        var proposal = ""
        var critique = ""
        var spent = Cost()
        val cap = when (stop) {
            is Stop.MaxIterations -> minOf(stop.n, hardCap)
            Stop.CriticApproves -> hardCap
        }
        var n = 0
        while (n < cap) {
            n++
            val proposeUser = buildString {
                append("Objective:\n").append(objective).append('\n')
                if (proposal.isNotEmpty()) {
                    append("\nYour previous attempt:\n").append(proposal)
                    append("\n\nCritique to address:\n").append(critique).append('\n')
                }
                append("\nProduce the next attempt.")
            }

            if (gating != null) {
                val projected = spent + gating.estimateStep(proposer, proposeUser)
                when (val decision = Escalation.decide(projected, gating.policy, gating.testsGreen)) {
                    is GateDecision.Escalate -> {
                        val gate = decision.gate
                        val cleared = gating.approve?.invoke(gate, projected)
                            ?: (gate.approver.kind == ApproverKind.AGENT)
                        if (!cleared) {
                            return RunResult(
                                iterations,
                                proposal,
                                "halted at ${gate.approver.name} gate before iteration $n",
                            )
                        }
                    }
                    GateDecision.Proceed -> {}
                }
                spent = projected
            }

            progress?.invoke(LoopProgress(n, LoopProgress.Stage.PROPOSING))
            proposal = generatorFor(proposer).complete(proposer.systemPrompt, proposeUser).trim()

            progress?.invoke(LoopProgress(n, LoopProgress.Stage.CRITIQUING))
            val critiqueUser = "Objective:\n$objective\n\nCandidate:\n$proposal\n\n" +
                "Critique it against the objective. If it fully meets the objective, " +
                "begin your reply with APPROVE."
            critique = generatorFor(critic).complete(critic.systemPrompt, critiqueUser).trim()
            val approved = critique.startsWith("APPROVE", ignoreCase = true)
            iterations += Iteration(n, proposal, critique, approved)
            progress?.invoke(LoopProgress(n, LoopProgress.Stage.ITERATION_DONE, approved))

            if (stop is Stop.CriticApproves && approved) {
                return RunResult(iterations, proposal, "critic approved at iteration $n")
            }
            if (stop is Stop.MaxIterations && n >= stop.n) {
                return RunResult(iterations, proposal, "reached $n iteration(s)")
            }
        }
        return RunResult(iterations, proposal, "hit hard cap $cap")
    }
}
