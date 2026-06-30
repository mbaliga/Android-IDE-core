package dev.aarso.domain.loop

/**
 * A loop as a managed object in the Loops list (docs/design/workflow-builder.md).
 *
 * Owner's lifecycle: **Unused** (editable draft, not yet triggered) → **Running**
 * (live) → **Retired** (run its course). Duplicating a Running or Retired loop
 * yields a new **Unused** draft (never live by default); **only Unused loops are
 * editable**. This is the envelope the list + Git sync manage; the definition
 * itself serialises to BPMN (`domain/bpmn`). Pure; JVM-tested.
 */
enum class LoopState { UNUSED, RUNNING, RETIRED }

data class Loop(
    val id: String,
    val name: String,
    /** The BPMN 2.0 definition. Null for a brand-new empty draft. */
    val bpmnXml: String? = null,
    val state: LoopState = LoopState.UNUSED,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val lastRunAt: Long? = null,
)

/** Pure lifecycle transitions enforcing the owner's rules. */
object LoopLifecycle {

    /** Only Unused drafts are editable (in the visual builder). */
    fun isEditable(loop: Loop): Boolean = loop.state == LoopState.UNUSED

    /** Trigger a draft → it goes live. Valid only from Unused. */
    fun trigger(loop: Loop, now: Long): Loop {
        require(loop.state == LoopState.UNUSED) { "only an Unused loop can be triggered" }
        return loop.copy(state = LoopState.RUNNING, updatedAt = now, lastRunAt = now)
    }

    /** A running loop completes its course → Retired. */
    fun retire(loop: Loop, now: Long): Loop {
        require(loop.state == LoopState.RUNNING) { "only a Running loop can retire" }
        return loop.copy(state = LoopState.RETIRED, updatedAt = now)
    }

    /**
     * Duplicate any loop into a fresh **Unused** draft — never live by default, so a
     * Running/Retired loop can be forked and tweaked. Carries the definition over.
     */
    fun duplicate(loop: Loop, newId: String, now: Long): Loop = Loop(
        id = newId,
        name = nextCopyName(loop.name),
        bpmnXml = loop.bpmnXml,
        state = LoopState.UNUSED,
        createdAt = now,
        updatedAt = now,
        lastRunAt = null,
    )

    /** The loops for one Running/Retired/Unused tab. */
    fun inState(loops: List<Loop>, state: LoopState): List<Loop> = loops.filter { it.state == state }

    private fun nextCopyName(name: String): String =
        if (name.endsWith(" (copy)")) name else "$name (copy)"
}
