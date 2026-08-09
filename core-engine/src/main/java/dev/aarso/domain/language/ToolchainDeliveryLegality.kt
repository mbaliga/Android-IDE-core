package dev.aarso.domain.language

import dev.aarso.contracts.language.DeliveryFlavor
import dev.aarso.contracts.language.ToolchainCapsuleManifest
import dev.aarso.contracts.language.ToolchainDeliveryMechanism

/**
 * WP-9: per-mechanism, per-flavor legality for [ToolchainDeliveryMechanism], grounded in
 * `01_VALIDATION_REPORT.md` §B1/B3 (the W^X exec restriction and Play's interpreter/VM
 * carve-out) rather than invented. **Proposed, not owner-ratified** — no `FB-RAT-*` decision ID
 * exists for this table (no prior spec names one; see `LanguageLaneContracts.kt`'s header for
 * why this whole domain is greenfield this pass), so it is recorded here as a reasoned starting
 * position, flagged in `docs/WP9_GATE_REPORT.md`, same posture WP-3's `FB-RAT-WS-NEW-1` and
 * WP-8b's `FB-RAT-PHN-011` handling both took for a genuinely new decision this session had to
 * make without owner input.
 *
 * Reasoning per mechanism (`LEGALITY` below is the source of truth; this is why it says what it says):
 *  - [ToolchainDeliveryMechanism.INTERPRETER_SCRIPTS]: legal on both — "Play-legal" per the WP-9
 *    brief text itself, Play's interpreter/VM carve-out named explicitly in §B3.
 *  - [ToolchainDeliveryMechanism.PLAY_DYNAMIC_FEATURE]: Play-only — it IS Play's own delivery
 *    channel ("ships additional native code *through Play*," §B3); meaningless outside Play
 *    distribution.
 *  - [ToolchainDeliveryMechanism.BUNDLED_JNILIBS]: legal on both — ordinary per-ABI `jniLibs`
 *    bundled with any APK build, full or Play; §B1 explicitly calls it "the only local native
 *    path in the Play flavor."
 *  - [ToolchainDeliveryMechanism.CAPSULE_APK]: full/sideload-only — a separately-installed signed
 *    APK via `PackageInstaller` is not a standard Play-distributed app's capability, and §B3's
 *    Android Developer Verification note means even sideload is not a policy-free escape valve,
 *    but it is still the more permissive flavor for this specific mechanism.
 *  - [ToolchainDeliveryMechanism.REMOTE]: legal on both — no local execution occurs at all, so no
 *    on-device platform or store-policy restriction applies.
 */
object ToolchainDeliveryLegality {

    private val LEGALITY: Map<ToolchainDeliveryMechanism, Set<DeliveryFlavor>> = mapOf(
        ToolchainDeliveryMechanism.INTERPRETER_SCRIPTS to setOf(DeliveryFlavor.FULL, DeliveryFlavor.PLAY),
        ToolchainDeliveryMechanism.PLAY_DYNAMIC_FEATURE to setOf(DeliveryFlavor.PLAY),
        ToolchainDeliveryMechanism.BUNDLED_JNILIBS to setOf(DeliveryFlavor.FULL, DeliveryFlavor.PLAY),
        ToolchainDeliveryMechanism.CAPSULE_APK to setOf(DeliveryFlavor.FULL),
        ToolchainDeliveryMechanism.REMOTE to setOf(DeliveryFlavor.FULL, DeliveryFlavor.PLAY),
    )

    fun isLegalFor(mechanism: ToolchainDeliveryMechanism, flavor: DeliveryFlavor): Boolean =
        flavor in LEGALITY.getValue(mechanism)

    fun isLegalFor(manifest: ToolchainCapsuleManifest, flavor: DeliveryFlavor): Boolean =
        isLegalFor(manifest.deliveryMechanism, flavor)

    /** Every [ToolchainDeliveryMechanism] not legal for [flavor] -- useful for a capability-manifest generator that must declare prohibited code paths per flavor (§B3: "the capability manifests must be per-mechanism"). */
    fun prohibitedMechanismsFor(flavor: DeliveryFlavor): Set<ToolchainDeliveryMechanism> =
        ToolchainDeliveryMechanism.entries.filterNot { isLegalFor(it, flavor) }.toSet()
}
