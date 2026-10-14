package dev.fonebrew.watch.core

/**
 * WatchBrew's colour tokens: **ink, paper and the brew accent ramp, nothing else.**
 *
 * ## Where these values come from (and the single-source follow-up)
 *
 * They are a **copied constant set**, hand-mirrored from the phone design system's
 * `dev.aarso.hyle.theme` (`hyle-design-system/hyle/src/main/java/dev/aarso/hyle/theme/Color.kt`,
 * Hyle 0.2.x):
 *
 * | here          | Hyle            | role on the wrist                            |
 * |---------------|-----------------|----------------------------------------------|
 * | [INK]         | `Ink`           | background (OLED-black-adjacent)             |
 * | [RAISED]      | `Raised`        | card / chip surface                          |
 * | [INSET]       | `Inset`         | inset wells                                  |
 * | [OUTLINE]     | `Outline`       | hairline dividers                            |
 * | [PAPER]       | `TextHigh`      | primary text ("paper" on the dark field)     |
 * | [PAPER_MID]   | `TextMid`       | secondary text                               |
 * | [PAPER_FAINT] | `TextDisabled`  | disabled / faint text                        |
 * | [BREW]        | `Violet`        | the one accent                               |
 * | [BREW_BRIGHT] | `VioletHover`   | accent, lightened                            |
 * | [BREW_DEEP]   | `VioletPressed` | accent, darkened                             |
 * | [BREW_DIM]    | `VioletDim`     | accent selection tint                        |
 * | [ON_BREW]     | `OnViolet`      | text/ink on the accent                       |
 *
 * **Why copied and not depended on:** `dev.aarso:hyle` is *phone* Compose (Material 3 + a
 * desktop-class component kit); pulling it into a watch APK would drag in the wrong UI toolkit.
 * This module therefore carries the **colour tokens only** — no components, no type, no fonts — and
 * maps them through `ui/WatchTheme.kt` onto Wear Compose Material. Duplicated constants are exactly
 * the divergence risk the single-source rule exists to prevent (Hyle 0.1.0 shipped from three
 * divergent copies), so this duplication is **named, not hidden**:
 *
 * **Follow-up (single-source, owner-gated to the Hyle repo, NOT done here):** add a *wear target* to
 * `mbaliga/Hyle-Design-System` that publishes these tokens (and later the Wear-shaped components) so
 * this file can be deleted in favour of a dependency. Until then a change to Hyle's palette must be
 * mirrored here by hand; `WatchPaletteTest` reads Hyle's own `Color.kt` from the submodule and fails
 * the gate if any mirrored value drifts, so the divergence cannot be silent.
 *
 * ## The colour-blindness rule (the owner is red-green colour-blind)
 *
 * Nothing in WatchBrew signals state by hue. State is carried by **shape + words** (see [Glyph]); the
 * only hue in the palette is the one brand accent, used for emphasis and never as the sole carrier
 * of a meaning. This palette deliberately has **no success-green, no warning-amber and no error-red**
 * (Hyle's `Success`/`Warning`/`ErrorRed` are not copied): `WatchPaletteTest` pins that no palette
 * entry sits in a red or green hue band. Wear Compose's `Colors` still requires an `error` slot; the
 * theme fills it with [PAPER] (an ink/paper inversion), so even a component that paints "error"
 * cannot paint it red.
 *
 * All values are ARGB (`0xAARRGGBB`) `Long`s — plain JVM constants, so the palette (and the contrast
 * guarantees in `WatchPaletteTest`) needs no Compose runtime.
 */
object WatchPalette {
    const val INK: Long = 0xFF0E0F12
    const val RAISED: Long = 0xFF16181D
    const val INSET: Long = 0xFF20242B
    const val OUTLINE: Long = 0xFF262A31

    const val PAPER: Long = 0xFFECEDEF
    const val PAPER_MID: Long = 0xFF9CA3AF
    const val PAPER_FAINT: Long = 0xFF4A4E57

    const val BREW: Long = 0xFF8E7BFF
    const val BREW_BRIGHT: Long = 0xFF9F8FFF
    const val BREW_DEEP: Long = 0xFF7262CC
    const val BREW_DIM: Long = 0xFF2A2541
    const val ON_BREW: Long = 0xFF160F2E

    /** Every token, in declaration order (for the palette's own invariants). */
    val ALL: Map<String, Long> = linkedMapOf(
        "INK" to INK, "RAISED" to RAISED, "INSET" to INSET, "OUTLINE" to OUTLINE,
        "PAPER" to PAPER, "PAPER_MID" to PAPER_MID, "PAPER_FAINT" to PAPER_FAINT,
        "BREW" to BREW, "BREW_BRIGHT" to BREW_BRIGHT, "BREW_DEEP" to BREW_DEEP, "BREW_DIM" to BREW_DIM,
        "ON_BREW" to ON_BREW,
    )

    /** ARGB as the `Int` that Tiles/ProtoLayout colour builders take. */
    fun argbInt(color: Long): Int = color.toInt()
}
