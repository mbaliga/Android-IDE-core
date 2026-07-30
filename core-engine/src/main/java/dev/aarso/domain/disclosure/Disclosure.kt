package dev.aarso.domain.disclosure

/**
 * Progressive disclosure (docs/design/disclosure.md). The app's growing complexity
 * is revealed in **tiers**, chosen by the onboarding intent step and changeable any
 * time in Settings.
 *
 * **Guardrail (thesis):** disclosure governs what is shown *by default*, never what
 * is *knowable*. A surface marked [mandatory] is always present and cannot be hidden
 * — that is how the watched-cloud marker (and any active routing/cost signal) stays
 * visible even at the simplest tier. "Simple" means fewer entry points, never an
 * opaque app. Pure; JVM-tested.
 */
enum class DisclosureTier {
    /** One chat, on-device default, the watched-cloud badge, basic settings. */
    CORE,

    /** + Images, Models depth (coverflow/BYO), the Tree map, the theme engine. */
    STUDIO,

    /** + Loops, Git & coding, council defaults, voice, expanded instruments. */
    POWER,
}

/** A capability/room that disclosure can reveal, with the tier that first shows it. */
enum class Surface(val minTier: DisclosureTier, val mandatory: Boolean = false) {
    CHAT(DisclosureTier.CORE, mandatory = true),
    SETTINGS_BASIC(DisclosureTier.CORE, mandatory = true),

    /** The "you are talking to a watched object" marker — a thesis invariant. */
    WATCHED_BADGE(DisclosureTier.CORE, mandatory = true),

    IMAGES(DisclosureTier.STUDIO),
    MODELS_DEPTH(DisclosureTier.STUDIO),
    TREE_MAP(DisclosureTier.STUDIO),
    THEME_ENGINE(DisclosureTier.STUDIO),

    LOOPS(DisclosureTier.POWER),
    GIT_CODING(DisclosureTier.POWER),
    COUNCIL(DisclosureTier.POWER),
    VOICE(DisclosureTier.POWER),
    INSTRUMENTS(DisclosureTier.POWER),
}

/**
 * The intent asked at first run. It only *sets a starting tier* — the user can move
 * tiers (or toggle individual surfaces) afterwards; the wizard is never a one-way door.
 */
enum class Intent(val tier: DisclosureTier, val headline: String) {
    PRIVATE_CHAT(DisclosureTier.CORE, "Private chat"),
    CHAT_AND_IMAGES(DisclosureTier.STUDIO, "Chat + images"),
    POWER_USER(DisclosureTier.POWER, "Loops & coding"),
}

/**
 * Per-surface overrides on top of the tier: the tinkerer can switch a single surface
 * on early or off — except [Surface.mandatory] ones, which stay on regardless.
 */
data class DisclosureOverrides(
    val enabled: Set<Surface> = emptySet(),
    val disabled: Set<Surface> = emptySet(),
)

object Disclosure {

    /** Whether [surface] is shown at [tier], honouring [overrides] and the guardrail. */
    fun isRevealed(
        surface: Surface,
        tier: DisclosureTier,
        overrides: DisclosureOverrides = DisclosureOverrides(),
    ): Boolean {
        if (surface.mandatory) return true                 // invariant: can't be hidden
        if (surface in overrides.disabled) return false
        if (surface in overrides.enabled) return true
        return tier.ordinal >= surface.minTier.ordinal
    }

    /** All surfaces visible at [tier] (+ overrides), in declaration order. */
    fun surfacesFor(
        tier: DisclosureTier,
        overrides: DisclosureOverrides = DisclosureOverrides(),
    ): List<Surface> = Surface.entries.filter { isRevealed(it, tier, overrides) }

    /** Map a first-run [Intent] to its starting tier. */
    fun tierFor(intent: Intent): DisclosureTier = intent.tier

    /** Lenient parse of the persisted string back to a tier (defaults to [POWER]). */
    fun tierOf(name: String?): DisclosureTier =
        DisclosureTier.entries.firstOrNull { it.name == name } ?: DisclosureTier.POWER
}
