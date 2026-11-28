package dev.fonebrew.ui.loops

import dev.fonebrew.domain.loop.AgentStatus
import dev.fonebrew.domain.loop.AgentRow
import dev.fonebrew.domain.loop.GatewayDecision
import dev.fonebrew.domain.loop.RunPhase
import dev.fonebrew.domain.loop.RunView

/**
 * Pure text/glyph rules for the live run panel, kept out of the composable so they are JVM-tested.
 * Status is always a glyph **and** a word — never colour alone (the owner is colour-blind; red/green
 * never carry meaning).
 */
object RunLiveFormat {
    fun glyph(s: AgentStatus) = when (s) {
        AgentStatus.RUNNING -> "●"
        AgentStatus.DONE -> "✓"
        AgentStatus.FAILED -> "✕"
        AgentStatus.CANCELLED -> "⏹"
    }

    fun word(s: AgentStatus) = when (s) {
        AgentStatus.RUNNING -> "running"
        AgentStatus.DONE -> "done"
        AgentStatus.FAILED -> "failed"
        AgentStatus.CANCELLED -> "stopped"
    }

    fun elapsed(ms: Long): String {
        val m = ms.coerceAtLeast(0)
        return when {
            m < 10_000 -> "%d.%ds".format(m / 1000, (m % 1000) / 100)
            m < 60_000 -> "${m / 1000}s"
            else -> "${m / 60_000}m ${"%02d".format((m % 60_000) / 1000)}s"
        }
    }

    /** Header line: where the run is, in words. */
    fun header(view: RunView, nowMs: Long): String = buildString {
        when (view.phase) {
            RunPhase.IDLE -> append("not started")
            RunPhase.RUNNING -> {
                val active = view.runningAgents.filter { it.id != "run" }
                append("running")
                if (active.isNotEmpty()) append(" · ").append(active.joinToString(", ") { it.label })
                append(" · step ").append(view.steps.size + active.size)
                if (view.hardCap > 0) append(" of ≤").append(view.hardCap)
            }
            RunPhase.FINISHED -> append("finished · ").append(view.steps.size).append(" step(s)")
        }
        if (view.phase != RunPhase.IDLE) append(" · ").append(elapsed(view.elapsedMs(nowMs)))
        val tin = view.tokensIn; val tout = view.tokensOut
        if (tin != null && tout != null) append(" · ").append(tin + tout).append(" tok")
    }

    /** One agent row's trailing meta: model · elapsed · tokens (only what was measured). */
    fun agentMeta(a: AgentRow, nowMs: Long): String = buildString {
        a.model?.let { append(it).append(" · ") }
        append(elapsed(a.elapsedMs(nowMs)))
        val tin = a.tokensIn; val tout = a.tokensOut
        if (tin != null && tout != null) append(" · ").append(tin + tout).append(" tok")
    }

    /** "◇ Reviewer ok? → approve (not: refine)" — the branch taken, and what was passed over. */
    fun gateway(g: GatewayDecision, labelOf: (String) -> String?, edgeLabel: (String) -> String?): String = buildString {
        append("◇ ").append(labelOf(g.nodeId) ?: g.nodeId).append(" → ")
        append(g.chosenLabel ?: edgeLabel(g.chosenEdgeId) ?: g.chosenEdgeId)
        if (g.alternativeEdgeIds.isNotEmpty()) {
            append(" (not: ").append(g.alternativeEdgeIds.joinToString(", ") { edgeLabel(it) ?: it }).append(")")
        }
    }

    /** The streaming tail shown under a running agent: last [max] chars, ellipsised at the head. */
    fun tail(text: String, max: Int = 600): String =
        if (text.length <= max) text else "…" + text.takeLast(max)
}
