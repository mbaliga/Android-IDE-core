// SPDX-License-Identifier: Apache-2.0
package dev.fonebrew.apple

/**
 * What an iOS/iPadOS build of Fonebrew can and cannot do, as data the SwiftUI shell renders.
 *
 * Porting plan IOS-5: "overlay, assist, USB and APK-install tiers are stubs that say so." Nothing
 * here pretends a capability exists; an [Availability.Unavailable] row carries the honest reason
 * shown in the UI. (Rule: legibility over convenience.)
 */
enum class Availability { Available, Stub, Unavailable }

data class Tier(
    val id: String,
    val title: String,
    val availability: Availability,
    /** Shown verbatim in the UI. Never empty for a non-Available tier. */
    val reason: String,
)

object PlatformTiers {
    val all: List<Tier> = listOf(
        Tier("tree", "Message tree + branching", Availability.Available,
            "Shared pure-Kotlin domain (dev.fonebrew.domain.tree), same sources as Android."),
        Tier("diff", "Diff and per-hunk review", Availability.Available,
            "Shared pure-Kotlin domain (dev.fonebrew.domain.diff), same sources as Android."),
        Tier("on_device_llm", "On-device model (llama.cpp / Metal)", Availability.Stub,
            "Not built yet: needs a llama.cpp xcframework with Metal (plan IOS-3). No model runs on this build."),
        Tier("cloud", "Cloud models (BYOK)", Availability.Stub,
            "Not wired yet (plan IOS-2). When it is, every cloud provider is marked a watched object and is opt-in per use."),
        Tier("image_gen", "On-device image generation", Availability.Unavailable,
            "stable-diffusion.cpp has no iOS build; out of scope for this port."),
        Tier("overlay", "Overlay", Availability.Unavailable,
            "iOS has no system overlay window; this Android tier does not exist here."),
        Tier("assist", "Screen capture / assist", Availability.Unavailable,
            "iOS does not allow a third-party app to read other apps' screens."),
        Tier("usb", "USB device flashing", Availability.Unavailable,
            "iPadOS exposes no raw USB serial to apps; Arduino/ESP flashing is not available."),
        Tier("apk_install", "APK install", Availability.Unavailable,
            "Not applicable on iOS."),
        Tier("local_process", "Local shell / toolchain", Availability.Unavailable,
            "iOS apps cannot spawn processes; repo work is Git-API based, remote shells go over SSH (not built yet)."),
    )

    fun byId(id: String): Tier? = all.firstOrNull { it.id == id }
}

/** Provider kinds. Cloud is always a watched object (CLAUDE.md rule 2); on-device never is. */
enum class ProviderKind { OnDevice, Cloud }

fun ProviderKind.isWatchedObject(): Boolean = this == ProviderKind.Cloud
