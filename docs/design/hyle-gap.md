# Hyle gap — why the Tactile Kit didn't reach the app

**Status:** open / not yet built. Written 2026-07-24 after the owner noted that,
the glass pane aside, little of Hyle's influence shows in the app — "in the colour
picker, the elements, nothing." This is the honest diagnosis + the port plan.

> TL;DR — the Hyle **library** shipped only the *abstract* layer (colour tokens + a
> provenance/finish contract) and explicitly deferred the Compose/AGSL render layer.
> The app renders through `Aeon.kt`, a parallel set of *conventional* components that
> consume ~none of the control tokens. So the tactile material language exists in the
> handoff HTML and in raw tokens, but **nowhere in a rendered component.** Only the
> palette + glass pane bridged the gap.

## 1. What the handoff actually is

The reference (`Tactile Kit v31`, owner handoff) is a **physical-hardware control
language**, not a flat UI kit:

- Turnable **knobs** (`.kscale`/`.kplain`/`.kdial`) and **faders** (`.hsh`/`.hsf`/`.vf`).
- Sculpted concave **crater** buttons carved into the body (`.crater`), with deep inset
  shadow stacks.
- A **lit-material library** — machined, brushed, sand, sandstone, leather, concrete,
  glass — each an `feTurbulence` + `feDiffuseLighting` texture with a directional light
  and a specific blend mode (`soft-light`/`overlay`/`screen`). The texture is the
  *control finish* (`--ctex`), applied to every knob/thumb/cap/crater — that is where
  the tactility comes from.
- Segmented **transport bars** with metallic fret-pill dividers, **VU meters**, engraved
  **LCD screens** with scanlines, speaker **grilles**, and **jacks**.
- A customizer built from **accent chips** (`.chip`, circular swatches with a double-ring
  selected state) + **material swatches** (`.sw-machined`, `.sw-brushed`, …).

## 2. What actually reached the app — three things

1. **Palette** — dark AMOLED field + violet `#8E7BFF` accent. ✔ (via `AeonColors`/tokens)
2. **Glass pane** — the single place the app imports the Hyle library:
   `app/src/main/java/dev/aarso/ui/hyle/HyleGlass.kt:24` → `HyleTokens.Color.colorPaletteGlassPane`.
   This *is* the "pane treatment" the owner already recognised.
3. **A background grain you can't really see** — `app/src/main/java/dev/aarso/ui/theme/Texture.kt`
   draws a single flat random-noise tile, hard-capped at ~14% alpha
   (`alpha = (strength * 0.14f * 255f)…coerceIn(0, 36)`), and its own comment says
   *"Keep this on the base background only."* So it is off the controls entirely — the
   opposite of the kit, where the lit texture *is* the control surface.

Everything else was never built.

## 3. Root cause — two "Hyle"s that don't meet

- **The library `dev.aarso:hyle`** (the split-out repo, `hyle-design-system/`) shipped
  only the abstract layer. Its own header says so verbatim
  (`hyle/src/main/java/dev/aarso/hyle/Hyle.kt`):
  > *"this module owns the render side — the tokens and the contract… this first cut is
  > deliberately pure data + contract (JVM-tested), no Compose… It will grow the Compose
  > `Modifier`s and AGSL shaders next."*
  That "next" — the material/component layer — never happened. Notably the **tokens did
  capture the tactile palette faithfully** (`HyleTokens.kt`): `controlSurface #16161A`,
  `controlGroove #050506`, `controlRim`, `controlEdge`, `controlScreen #141210`,
  `controlIndicator #6B6760` map 1:1 to the kit's `--srf / --groove / --rim / --edge /
  --screen / --glyph`. The paint exists; the brushwork doesn't.
- **The app** renders through `app/src/main/java/dev/aarso/ui/aeon/Aeon.kt` (package
  `dev.aarso.ui.hyle` — a folder/package mismatch, and a *different thing* from the
  library). Its components — `HyleButton`, `HyleCard`, `HyleField`, `HyleChip`,
  `HyleTabBar` — are flat, conventional Compose. They predate the tokens and reference
  none of the `control*` tokens. The colour picker
  (`app/src/main/java/dev/aarso/ui/hyle/HyleColorPicker.kt`) is a generic HSV square +
  rainbow hue slider with a plain white thumb — none of the kit's accent chips or
  material swatches.

## 4. Component-by-component gap

| Kit element | In the app today | Gap |
|---|---|---|
| Palette (field + violet) | `AeonColors` / tokens | ✔ done |
| Glass pane | `HyleGlass.kt` | ✔ done (the one token consumed) |
| Lit material finishes (machined/brushed/sand/…) | flat noise on bg only, ~14% | ✗ never built as a control finish |
| Knobs / faders / vertical faders | — | ✗ none |
| Crater buttons | flat `HyleCard`/`HyleButton` | ✗ none |
| Transport bar / VU meters / LCD screen | — | ✗ none |
| Accent-chip + material-swatch customizer | generic HSV picker | ✗ none |
| Toggles / jacks / grilles | Material switch | ✗ none |
| Control tokens (`controlSurface/groove/rim/edge/screen`) | defined in library, unused by app | ✗ not wired into `Aeon.kt` |

## 5. Port plan (when prioritised)

The material layer is real custom-draw Compose work, and the lit finishes are
`feDiffuseLighting` — i.e. an **AGSL shader** on Android (API 33+; we're minSdk 31, so
gate it with a flat-texture fallback below 33). Every surface is **owner-verified on
device** — CI never launches the app.

**Phase A — focused, high-impact (make the influence *show*):**
1. A `Modifier.hyleControlSurface(finish)` that paints the tactile surface: the
   `control*` tokens as the base gradient + a real lit texture (AGSL where available,
   a stronger tiled bitmap below 33), applied to `HyleCard` / `HyleButton` / `HyleTabBar`.
2. Rebuild `HyleColorPicker` as the kit's customizer: a row of accent **chips** with the
   double-ring selected state + **material swatches**, backed by the existing accent
   presets (violet/cyan/…). Keep the HSV field as an "advanced" disclosure.
3. Turn the background grain up off the floor and onto the intended surfaces.

**Phase B — the control components (bigger):**
Knob, fader/vertical fader, crater button, segmented transport, LCD screen, VU meter —
built into the **library** (`dev.aarso:hyle`), so they are the single source, then
`Aeon.kt` re-exports/points at them. This is where the "control panel" feel actually
lands, and where AGSL is load-bearing.

**Sequencing note:** Phase A is the smallest change that makes the app *read* as Hyle;
Phase B is the full kit and should follow the Play beta, not block it.
