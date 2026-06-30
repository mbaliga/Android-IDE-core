package dev.aarso.domain.council

/**
 * Autonomy as an escalation matrix (docs/design/council-workflows.md). The owner's
 * model: a workflow step has an estimated **cost**; below an autonomy budget it runs
 * on its own; beyond it, it **escalates** up a chain of **gates** — a council
 * persona/model, then named team members who can also approve, with the **primary
 * user as the terminal authority**. Test/CI status feeds in too: green tests widen
 * how far the loop may go without asking.
 *
 * Pure domain — no Android, no network. JVM-testable. Wiring into the runner (pause
 * for the chosen approver, resume on approval) is the integration step.
 */

/** What an action is estimated to consume, across the dimensions the owner named. */
data class Cost(
    val tokens: Long = 0,
    val seconds: Long = 0,
    val moneyCents: Long = 0,
    /** discrete model/tool calls — a proxy for compute / resource fan-out. */
    val calls: Int = 0,
) {
    operator fun plus(o: Cost) = Cost(tokens + o.tokens, seconds + o.seconds, moneyCents + o.moneyCents, calls + o.calls)
}

/** A ceiling. `null` on a dimension means "unlimited there". */
data class Budget(
    val tokens: Long? = null,
    val seconds: Long? = null,
    val moneyCents: Long? = null,
    val calls: Int? = null,
) {
    /** True iff [c] stays within every limited dimension (any dimension over ⇒ false). */
    fun covers(c: Cost): Boolean =
        (tokens == null || c.tokens <= tokens) &&
            (seconds == null || c.seconds <= seconds) &&
            (moneyCents == null || c.moneyCents <= moneyCents) &&
            (calls == null || c.calls <= calls)

    companion object {
        val UNLIMITED = Budget()
        val ZERO = Budget(0, 0, 0, 0)
    }
}

enum class ApproverKind { AGENT, TEAM_MEMBER, USER }

/** Who sits at a rung: a council agent/model, a named teammate, or the primary user. */
data class Approver(val kind: ApproverKind, val name: String)

/** A rung in the escalation matrix: this approver may approve up to [ceiling]. */
data class Gate(val approver: Approver, val ceiling: Budget)

/**
 * The escalation matrix. Below [autoBudget] (or [autoBudgetWithTestsGreen] when CI
 * is green) the workflow proceeds autonomously; beyond it, the **first gate whose
 * ceiling covers the cost** must approve. [gates] ascend in authority; the last
 * should be the user at [Budget.UNLIMITED] — the terminal say.
 */
data class EscalationPolicy(
    val autoBudget: Budget,
    val gates: List<Gate>,
    /** A more generous autonomy budget to use when tests/CI are green. */
    val autoBudgetWithTestsGreen: Budget? = null,
) {
    init { require(gates.isNotEmpty()) { "policy needs at least the user as the terminal gate" } }
}

sealed interface GateDecision {
    /** Within the autonomy budget — run without asking anyone. */
    data object Proceed : GateDecision
    /** This gate must approve before proceeding. */
    data class Escalate(val gate: Gate) : GateDecision
}

object Escalation {
    /**
     * Decide whether a step of estimated [cost] may proceed, or which gate must
     * approve it. [testsGreen] = true widens the autonomy budget when the policy
     * sets one (the test/CI signal unifies into the same matrix).
     */
    fun decide(cost: Cost, policy: EscalationPolicy, testsGreen: Boolean? = null): GateDecision {
        val budget = if (testsGreen == true && policy.autoBudgetWithTestsGreen != null) {
            policy.autoBudgetWithTestsGreen
        } else {
            policy.autoBudget
        }
        if (budget.covers(cost)) return GateDecision.Proceed
        return GateDecision.Escalate(policy.gates.firstOrNull { it.ceiling.covers(cost) } ?: policy.gates.last())
    }
}
