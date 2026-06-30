package dev.aarso.domain.provenance

/**
 * Provenance — the **"watched-object" model** for where a unit of work was actually
 * computed. A first-class system surface (Doc 00 §3.6), not a cosmetic badge: the user
 * is owed an honest, at-a-glance answer to *did this leave my device?* every time.
 *
 * Binding rule 2 ("on-device is always the default; cloud is opt-in and visibly marked
 * a watched object") is what this type *encodes*. A [ProvenanceState] is the legible
 * receipt that makes routing/influence visible instead of a hidden fallback.
 *
 * **Colour is NEVER the sole encoder.** Every state carries a non-colour identity — a
 * stable [iconKey] (the glyph the UI resolves: ⌂ / ☁ / split / ?) and a human [label] —
 * so the meaning survives greyscale, colour-blindness, and screen-reader contexts. A
 * UI may *add* colour on top, but the icon + label alone must be sufficient (a11y +
 * colour-independence). The model deliberately lives here, pure and JVM-tested, so the
 * honesty is machine-verified and not a render-time afterthought.
 *
 * The four states are total and mutually exclusive:
 * - [LOCAL]   — computed entirely on this device. Never left. Not watched.
 * - [CLOUD]   — reached a cloud provider (a watched object). Left the device.
 * - [MIXED]   — some local, some cloud (e.g. a council with both kinds of member, or a
 *               loop that fell back to cloud for one step). Watched, because part of it
 *               reached off-device — the strongest honest claim is "some of this left."
 * - [UNKNOWN] — provenance could not be determined. Surfaced honestly rather than
 *               guessed; not asserted as safe, so it is *not* marked watched (we make
 *               no claim either way — see [Provenances.combine]).
 *
 * Pure domain (no Android). UI resolves [iconKey] to a concrete glyph/contentDescription.
 */
enum class ProvenanceState(
    /**
     * Stable, UI-agnostic glyph handle — the non-colour primary cue. The UI maps this to
     * an icon + content description; the string is the contract, not the pixels.
     */
    val iconKey: String,
    /** Short human label — the non-colour secondary cue (screen readers, dense rows). */
    val label: String,
    /**
     * True when this work reached **off-device** (cloud), so it must be flagged a
     * "watched object" per binding rule 2. [UNKNOWN] is *not* watched — we make no claim.
     */
    val watched: Boolean,
) {
    /** ⌂ — stayed on this device. */
    LOCAL(iconKey = "home", label = "On-device", watched = false),

    /** ☁ — reached a cloud provider; a watched object. */
    CLOUD(iconKey = "cloud", label = "Cloud", watched = true),

    /** ⌖ — part local, part cloud; watched because some of it left the device. */
    MIXED(iconKey = "split", label = "Mixed", watched = true),

    /** ? — could not be determined; surfaced honestly, no safety claim either way. */
    UNKNOWN(iconKey = "question", label = "Unknown", watched = false),
    ;
}

/**
 * Pure operations over [ProvenanceState]. No Android, no I/O — the combine rules are the
 * whole point and are exhaustively unit-tested.
 */
object Provenances {

    /**
     * The provenance a single [tier] produces. ON_DEVICE and RUNNER are both [LOCAL] —
     * a RUNNER is the user's *own* machine (sovereign, never left the user's control);
     * CLOUD is [CLOUD]. See [Tier.provenance], the canonical mapping this delegates to.
     */
    fun ofTier(tier: Tier): ProvenanceState = tier.provenance

    /**
     * Fold many provenances into the single honest claim for a composite (a council, a
     * loop run, a branch). Rules, in order, deliberately explicit:
     *
     * 1. **empty** → [UNKNOWN] — nothing to claim about.
     * 2. all [LOCAL] → [LOCAL].
     * 3. all [CLOUD] → [CLOUD].
     * 4. any [LOCAL] **and** any [CLOUD] present → [MIXED] — some of it left the device.
     * 5. [UNKNOWN] mixed with others: if *both* a local and a cloud part are also present
     *    it is already [MIXED] (rule 4 wins). Otherwise the unknown taints the claim — we
     *    cannot honestly say "all local" or "all cloud", so it degrades to [MIXED] when
     *    paired with a known part, and stays [UNKNOWN] only when everything is unknown.
     *
     * The bias is conservative: when in doubt we never *under*-state reach. The only way
     * to earn the clean [LOCAL] badge is for every part to be provably local.
     */
    fun combine(states: Collection<ProvenanceState>): ProvenanceState {
        if (states.isEmpty()) return ProvenanceState.UNKNOWN

        val hasLocal = states.contains(ProvenanceState.LOCAL)
        val hasCloud = states.contains(ProvenanceState.CLOUD)
        val hasMixed = states.contains(ProvenanceState.MIXED)
        val hasUnknown = states.contains(ProvenanceState.UNKNOWN)

        // A reach off-device anywhere — explicit MIXED, or both a local and a cloud part.
        if (hasMixed || (hasLocal && hasCloud)) return ProvenanceState.MIXED

        // Everything unknown — honestly unknown, no further claim.
        if (hasUnknown && !hasLocal && !hasCloud) return ProvenanceState.UNKNOWN

        // Unknown paired with a single known kind: cannot certify "all X" → MIXED.
        if (hasUnknown) return ProvenanceState.MIXED

        // No unknown, no mix — the clean cases.
        if (hasCloud) return ProvenanceState.CLOUD
        return ProvenanceState.LOCAL // hasLocal, nothing else
    }
}
