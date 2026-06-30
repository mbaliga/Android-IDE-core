package dev.aarso.domain.material

import dev.aarso.domain.model.ModelSpec

/**
 * The primary optical axis of the Hyle material language
 * (docs/design/material-language.md): **reflective vs radiant.**
 *
 * Everything local and of-the-earth is **reflective** — it only shows the light
 * that falls on it, inert until you touch it (on-device models, the user's own
 * turns). A watched, from-elsewhere surface is **radiant** — it emits its *own*
 * light (radioluminescent), which is the legible, in-the-substance signature of a
 * "watched object" (CLAUDE.md binding rule #2: cloud is opt-in and visibly watched).
 *
 * This file is the *semantic* seam only — it answers "is this surface local or
 * from-elsewhere," nothing about colour or glow. It stays in the app because it is
 * about Aarso's own data (which engine produced a turn). The renderer that turns a
 * [MaterialClass] into specular vs emission lives in the design system and is *fed*
 * this; keeping the hue out here is deliberate (the radiant hue is still open). Pure
 * and JVM-tested.
 */
enum class MaterialClass {
    /** Local, of-here, yours — only reflects light. On-device models; user turns. */
    REFLECTIVE,

    /** From-elsewhere, watched — emits its own light. Watched cloud models. */
    RADIANT,
}

/** Where a surface's content came from — the thing the optical axis reads. */
enum class Provenance {
    /** Produced on this device (a local engine) or by the user themselves. */
    LOCAL,

    /** Produced by a watched, user-configured cloud provider — off this device. */
    ELSEWHERE,
}

/** Pure classifier: provenance → optical class. No colour, no rendering. */
object Material {

    fun classOf(provenance: Provenance): MaterialClass = when (provenance) {
        Provenance.LOCAL -> MaterialClass.REFLECTIVE
        Provenance.ELSEWHERE -> MaterialClass.RADIANT
    }

    /**
     * A model's provenance. On-device runtimes ([ModelSpec.isOnDevice]) are local;
     * everything else is a watched cloud provider, i.e. from elsewhere. This
     * coincides with [ModelSpec.watched] by construction (cloud == watched), so a
     * radiant surface is exactly a watched one.
     */
    fun provenanceOf(spec: ModelSpec): Provenance =
        if (spec.isOnDevice) Provenance.LOCAL else Provenance.ELSEWHERE

    fun classOf(spec: ModelSpec): MaterialClass = classOf(provenanceOf(spec))

    /** Convenience for the renderer: does this surface emit its own light? */
    fun isRadiant(spec: ModelSpec): Boolean = classOf(spec) == MaterialClass.RADIANT
}
