package dev.fonebrew.ui.mode

import dev.aarso.hyle.cells.HyleModeOption

/**
 * The two interaction-mode options' shared copy (owner ruling 2026-09-15) — one list, used by
 * both the onboarding mode-choice page ([dev.fonebrew.ui.OnboardingScreen]) and Settings →
 * General's "Interaction style" picker ([dev.fonebrew.ui.rooms.SettingsRoom]), so the wording
 * never drifts between the two entry points. [HyleModeOption.id] matches
 * [dev.aarso.interactionmode.InteractionMode.name] exactly (`"REGULAR"` / `"ASOC"`) so a caller
 * can round-trip `InteractionMode.valueOf(option.id)` without a separate lookup table.
 */
val InteractionModeOptions: List<HyleModeOption> = listOf(
    HyleModeOption(
        id = "REGULAR",
        title = "Regular",
        description = "Familiar tabs and buttons — everything reachable by tap.",
    ),
    HyleModeOption(
        id = "ASOC",
        title = "asoc",
        description = "The spatial rooms + gesture grammar this app is built around.",
        badge = "experimental",
    ),
)
