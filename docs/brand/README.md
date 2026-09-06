# Fonebrew brand assets

## The canonical mark (owner ruling, 2026-09-06)

The canonical Fonebrew mark is the **drink-on-a-lanyard glyph** paired with the
**FONEBREW wordmark** — see the splash assets, the actual source of truth today:

| File | What |
|---|---|
| [`../../core-engine/src/main/res/drawable-nodpi/splash_glyph_dark.png`](../../core-engine/src/main/res/drawable-nodpi/splash_glyph_dark.png) | Glyph, light-outline variant (for the dark splash field). |
| [`../../core-engine/src/main/res/drawable-nodpi/splash_glyph_light.png`](../../core-engine/src/main/res/drawable-nodpi/splash_glyph_light.png) | Glyph, dark-outline variant (for the light splash field). |
| [`../../core-engine/src/main/res/drawable-nodpi/splash_wordmark_dark.png`](../../core-engine/src/main/res/drawable-nodpi/splash_wordmark_dark.png) / `splash_wordmark_light.png` | The FONEBREW wordmark, each field variant. |

The owner ruled (2026-09-06) that the **monochrome black/white versions of this glyph are
the canonical brand set** going forward: a white line-art glyph with white liquid on a
black rounded tile, and its black-on-white sibling. The owner showed these in chat, but
the exact export files did not land in this container/repo.

## Derived monochrome masks (placeholder, drop-in replaceable)

Because the owner's original monochrome exports aren't in the repo yet, the in-repo
monochrome assets below are **derived programmatically from the existing alpha-matted
splash glyph** (`splash_glyph_dark.png`) rather than hand-designed:

| File | Use | Derivation |
|---|---|---|
| `core-engine/src/main/res/drawable-nodpi/ic_launcher_monochrome.png` | Android 13+ (API 33) themed-icon `<monochrome>` layer, referenced from both `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`. | `splash_glyph_dark.png`'s alpha channel, recolored solid white, cropped to its content bounding box, scaled and centered so the glyph sits inside the adaptive-icon safe zone (content within the middle ~66/108 of the 108dp canvas) with a small extra safety margin — verified by a circle-mask simulation that no pixel falls outside that zone. |
| `core-engine/src/main/res/drawable-nodpi/ic_stat_fonebrew.png` | Status-bar small icon (`setSmallIcon`) for the app's foreground-service notifications. | Same alpha-channel-as-mask technique, tighter crop (no adaptive-icon safe-zone padding — just a small margin so the mask isn't edge-to-edge). |

Both are genuine alpha masks (transparent background, solid-white content, alpha channel
carries the actual line art) — not a flattened/opaque bitmap — because that's what the
platform requires: it tints the *alpha* of a themed icon and a status-bar icon, ignoring
whatever RGB the source PNG happens to carry.

**These are honest placeholders, not the owner's real monochrome art.** The source glyph
is a tall, narrow shape (a drink cup + straw + lanyard cord), so the derived masks read
correctly as *this* mark's silhouette but are thinner/more vertical than a purpose-drawn
"FB on Black" / "FB on White" glyph would likely be. When the owner's original monochrome
exports land, **replace these two PNGs byte-for-byte** (same file names, same
`drawable-nodpi` location, transparent background + white content + real alpha) and no
code, XML, or call-site change is needed — `ic_launcher.xml`, `ic_launcher_round.xml`, and
the four `setSmallIcon(R.drawable.ic_stat_fonebrew)` call sites all reference these paths
by name already.

Rendering — themed-icon tinting, circle/squircle masking on a real launcher, and how the
status-bar icon actually looks — is **owner-verified on device only**; this build
environment has no device or emulator (see the root `CLAUDE.md` "Environment honesty"
section).

## Archived: the pre-launch mirror-seam mark

`archive/mirror-seam-logo.svg` (formerly `fonebrew-logo.svg`) and
`archive/mirror-seam-icon-foreground.svg` (formerly `fonebrew-icon-foreground.svg`) are
**retired** as of the 2026-09-06 owner ruling above. They predate the launch branding: a
violet, sail-like "mirror" image (two triangles over a fading reflection) alluding to
Aarso, the app's internal self-reflection lens (`domain/mirror/`) — completely different
art from the drink-glyph mark, kept only for historical record. Nothing in the app build
references them (they were documentation-only SVGs to begin with); do not resurrect them
as the app icon or store listing art.

## Producing raster assets from the canonical splash PNGs

The splash PNGs above are already the shipping raster assets — nothing to export for
in-app use. For **Play Store** collateral (512×512 icon upload, feature graphic), compose
from the same glyph + wordmark pair once the owner's monochrome/full-color exports land;
still needed for a full Play listing (tracked, not yet generated):
- **Feature graphic** 1024×500 (see `docs/play/store-listing.md`).
- **Phone screenshots** ×2–8 — owner-captured on the RedMagic (no device in CI).

The textual listing (title / short / full description, within Play's limits) is
generated headlessly by `domain/launch/StoreListing.kt`.
