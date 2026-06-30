package dev.aarso.domain.mirror

/**
 * The **mirror lens** (Aarso) — a *bounded, empty seam*, not a feature.
 *
 * Aarso is the within-axis self-reflection lens: it would observe the user's own
 * voice and report drift toward an LLM register (§5b drift metric) and offer
 * retrospective self-observation (§5c). **That logic is owner-only and blocked on
 * GitHub Issue #2** (binding rule 4) — the idiolect baseline and the measurement
 * methodology are the owner's input, not ours to invent.
 *
 * So this module is deliberately *inert*. It exists only to fix the module boundary
 * now: a host (Workbench) can declare a seam where a lens plugs in, ship with the
 * [InertMirrorLens] (which observes nothing and reports nothing), and later — once
 * Issue #2 is answered — drop a real lens in **without touching the host**. Building
 * the seam is allowed; building the metric is not.
 *
 * Invariants enforced here:
 * - The default lens is inert; it never fabricates a drift number or a "reflection."
 * - The lens is *within-axis*: it reflects the user back to the user. It is **not** a
 *   telemetry channel — an [Observation] is never persisted, sent, or logged by this
 *   module (binding rule 1). A real lens, when it lands, observes locally only.
 *
 * Pure Kotlin; JVM-tested. No Android, no I/O, no metric logic.
 */

/**
 * Something the user authored that a lens *could* observe (a turn they wrote, a name
 * they chose). Carried by value; this module does nothing with it. The baseline a
 * real lens would compare against is **not** modelled here — that is Issue #2.
 */
data class Observation(val text: String, val sourceId: String? = null)

/**
 * What a lens would hand back. Intentionally minimal and **empty by default**: a real
 * §5b/§5c lens would populate drift/notes, but until Issue #2 defines what those mean
 * we refuse to invent a shape that pretends to measure. [present] is false for the
 * inert lens, so callers can render "no lens installed" *materially* (THE LAW), never
 * a fake score.
 */
data class Reflection(
    /** True only when a real lens produced this. The inert lens always returns false. */
    val present: Boolean = false,
    /** Free-form, lens-supplied notes. Empty until a real lens lands. */
    val notes: List<String> = emptyList(),
) {
    companion object {
        /** The honest "nothing to reflect yet" value. */
        val NONE = Reflection(present = false, notes = emptyList())
    }
}

/**
 * The pluggable seam. A host depends on this interface, never on a concrete lens, so
 * the real (Issue-#2-gated) implementation can be installed later with no host change.
 */
interface MirrorLens {
    /** Observe [observation] and return a [Reflection]. Inert lenses return [Reflection.NONE]. */
    fun reflect(observation: Observation): Reflection

    /** Whether a real lens is installed. False for [InertMirrorLens] — render it materially. */
    val installed: Boolean
}

/**
 * The shipping default: a lens that observes nothing and reports nothing. It holds the
 * boundary open without crossing the Issue #2 wall. Every call returns [Reflection.NONE]
 * and [installed] is false.
 */
object InertMirrorLens : MirrorLens {
    override fun reflect(observation: Observation): Reflection = Reflection.NONE
    override val installed: Boolean = false
}
