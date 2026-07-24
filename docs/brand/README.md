# Fonebrew brand assets

Tangible launch collateral for the Play listing + app icon. Clean-room (no borrowed
marks), built from the app's own design tokens.

## The mark

The logo is a symmetric form split at a central seam — a solid shape and its lighter
twin — resting above its own fading reflection: mirror imagery, alluding to Aarso, the
app's internal self-reflection lens (`domain/mirror/`). It encodes the thesis: the app
reflects your own interaction with models back to you.

| File | Use |
|---|---|
| [`fonebrew-logo.svg`](fonebrew-logo.svg) | Full logo on the dark field (512×512). Play "app icon" upload, store graphics, README. |
| [`fonebrew-icon-foreground.svg`](fonebrew-icon-foreground.svg) | Adaptive-icon **foreground** layer (108dp canvas, 72dp safe zone). Pair with a solid `#0E0F12` background layer. |

## Palette (from the app tokens)

| Token | Hex |
|---|---|
| Field (background) | `#0E0F12` |
| Violet (primary) | `#8E7BFF` |
| Violet light (highlight) | `#A99BFF` |

## Producing raster assets

SVG is the source of truth. Export the PNGs Play needs from it:

```bash
# 512×512 app icon (Play requires 512 PNG)
rsvg-convert -w 512 -h 512 docs/brand/fonebrew-logo.svg -o fonebrew-icon-512.png
# or: inkscape docs/brand/fonebrew-logo.svg --export-type=png -w 512 -o fonebrew-icon-512.png
```

Still needed for a full Play listing (tracked, not yet generated):
- **Feature graphic** 1024×500 (see `docs/play/store-listing.md`).
- **Phone screenshots** ×2–8 — owner-captured on the RedMagic (no device in CI).

The textual listing (title / short / full description, within Play's limits) is
generated headlessly by `domain/launch/StoreListing.kt`.
