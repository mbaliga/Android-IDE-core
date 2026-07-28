package dev.aarso.domain.provenance

/**
 * Legible routing (Doc 01 §6.5). When the app decides *which engine answers a turn*, the
 * decision is not hidden behind a spinner — it surfaces a small honest contract the user
 * can read and, crucially, **override**. The whole design thesis ("make routing/influence
 * visible, keep the user in the loop") lives in this file.
 *
 * A routing decision is the tuple `{model, tier, one-line why, est. cost, provenance}`.
 * [Tier] answers *where* the compute happens, [ProvenanceState] answers *did it leave my
 * device* — and the user can replace the decision with a [RoutingOverride].
 *
 * This is a **heuristic baseline**, deliberately tiny and fully legible — *not* the
 * closed routing engine (constellation §7). [Router.decide] is the visible default the
 * user overrides; the real engine plugs in later behind a stable public API. Keeping the
 * baseline simple and documented as such is the point: nothing about the default routing
 * is a black box.
 *
 * Pure domain (no Android, no network). Cost stays in **minor units** (cents/paise) as a
 * plain [Long]; formatting a currency string is a UI concern and is not done here.
 */
enum class Tier(
    /** The provenance this tier produces — the canonical Tier→provenance mapping. */
    val provenance: ProvenanceState,
    /** Short human label for the tier chip. */
    val label: String,
) {
    /** This phone's own NPU/CPU via llama.cpp — free, private, never leaves. */
    ON_DEVICE(provenance = ProvenanceState.LOCAL, label = "On-device"),

    /**
     * The user's **own** machine (a paired Pi / desktop runner). Still [LOCAL]
     * provenance — sovereign, under the user's control, not a third party's cloud.
     */
    RUNNER(provenance = ProvenanceState.LOCAL, label = "Runner"),

    /** A third-party cloud provider — a watched object; reaches off-device. */
    CLOUD(provenance = ProvenanceState.CLOUD, label = "Cloud"),
    ;
}

/**
 * The legible-routing contract for one turn: the model picked, the [tier] it runs on, a
 * one-line [why] (human-readable rationale), an [estCostMinor] estimate in minor units,
 * and the [provenance] receipt. Built by [Router.decide] or hand-constructed; mutated
 * only through [applyOverride].
 *
 * Invariant worth knowing: a well-formed decision's [provenance] equals its
 * `tier.provenance`. [applyOverride] preserves this by recomputing from the new tier.
 */
data class RoutingDecision(
    val model: String,
    val tier: Tier,
    /** One-line rationale, e.g. "on-device default (free, private)". */
    val why: String,
    /** Estimated cost in minor currency units (cents/paise). 0 for free tiers. */
    val estCostMinor: Long,
    val provenance: ProvenanceState,
) {
    /**
     * A single plain line for the routing chip / tooltip:
     * `model · tier · why · ~cost`. Cost is the raw minor-unit number with a `~` prefix
     * — currency symbol/decimals are the UI's job, kept out of the domain on purpose.
     */
    fun summaryLine(): String =
        "$model · ${tier.label} · $why · ~$estCostMinor"
}

/**
 * A user's manual replacement for a routing decision: pick the [model] and [tier]
 * directly. Cost/why/provenance are recomputed by [applyOverride] — the user picks the
 * *where* and *what*, the model stays honest about the consequences.
 */
data class RoutingOverride(
    val model: String,
    val tier: Tier,
)

/**
 * Apply a user [RoutingOverride] to a decision. Provenance is **recomputed from the
 * override's tier** (never copied stale), the cost estimate is carried over unchanged
 * (the override doesn't re-price), and [why] is set to the honest, fixed reason
 * "overridden by user" so the surface never pretends the choice was the system's.
 */
fun RoutingDecision.applyOverride(o: RoutingOverride): RoutingDecision =
    copy(
        model = o.model,
        tier = o.tier,
        why = "overridden by user",
        provenance = o.tier.provenance,
    )

/**
 * The **legible default** router — a small, fully-readable heuristic, not the closed
 * routing engine. It encodes binding rule 2 (on-device is the default; cloud is opt-in)
 * as plain branches the user can predict and override.
 *
 * This intentionally does *no* benchmarking, quality scoring, or learned routing — that
 * is the future engine's job behind a stable API. Here we only answer "what is the
 * honest default given what's available?" and explain it in one line.
 */
object Router {

    /**
     * Decide the default route.
     *
     * @param preferOnDevice the user's standing preference (binding rule 2 default: true).
     * @param hasOnDeviceModel an on-device model is loaded/available.
     * @param hasCloudKey a cloud provider key is configured (opt-in).
     * @param onDeviceModel the on-device model id, if any.
     * @param cloudModel the cloud model id, if any.
     * @param estCloudCostMinor estimated cloud cost in minor units (used only on CLOUD).
     *
     * Branches, in order:
     * 1. prefer-on-device **and** an on-device model exists → [Tier.ON_DEVICE], free,
     *    why "on-device default (free, private)".
     * 2. else a cloud key + cloud model exists → [Tier.CLOUD], priced, why
     *    "no on-device model — using cloud" (or "cloud (your choice)" if the user did
     *    not prefer on-device).
     * 3. else an on-device model exists (even if not preferred) → [Tier.ON_DEVICE], free,
     *    why "on-device (only option available)".
     * 4. else nothing usable → an [Tier.ON_DEVICE] placeholder with empty model,
     *    provenance [ProvenanceState.UNKNOWN], why "no model available".
     */
    fun decide(
        preferOnDevice: Boolean,
        hasOnDeviceModel: Boolean,
        hasCloudKey: Boolean,
        onDeviceModel: String?,
        cloudModel: String?,
        estCloudCostMinor: Long,
    ): RoutingDecision {
        val cloudAvailable = hasCloudKey && cloudModel != null

        // 1. The honest default: stay on-device when preferred and possible.
        if (preferOnDevice && hasOnDeviceModel && onDeviceModel != null) {
            return RoutingDecision(
                model = onDeviceModel,
                tier = Tier.ON_DEVICE,
                why = "on-device default (free, private)",
                estCostMinor = 0L,
                provenance = Tier.ON_DEVICE.provenance,
            )
        }

        // 2. Cloud — either no local option, or the user's explicit choice.
        if (cloudAvailable) {
            val why =
                if (preferOnDevice) "no on-device model — using cloud"
                else "cloud (your choice)"
            return RoutingDecision(
                model = cloudModel!!,
                tier = Tier.CLOUD,
                why = why,
                estCostMinor = estCloudCostMinor,
                provenance = Tier.CLOUD.provenance,
            )
        }

        // 3. On-device as the only option, even if not preferred.
        if (hasOnDeviceModel && onDeviceModel != null) {
            return RoutingDecision(
                model = onDeviceModel,
                tier = Tier.ON_DEVICE,
                why = "on-device (only option available)",
                estCostMinor = 0L,
                provenance = Tier.ON_DEVICE.provenance,
            )
        }

        // 4. Nothing usable — honest placeholder, never a silent failure.
        return RoutingDecision(
            model = "",
            tier = Tier.ON_DEVICE,
            why = "no model available",
            estCostMinor = 0L,
            provenance = ProvenanceState.UNKNOWN,
        )
    }
}
