# Aarso brand assets

Tangible launch collateral for the Play listing + app icon. Clean-room (no borrowed
marks), built from the app's own design tokens.

## The mark

**Aarso means *mirror*.** The logo is a symmetric form split at a central seam — a
solid shape and its lighter twin — resting above its own fading reflection. It encodes
the thesis: the app reflects your own interaction with models back to you.

| File | Use |
|---|---|
| [`aarso-logo.svg`](aarso-logo.svg) | Full logo on the dark field (512×512). Play "app icon" upload, store graphics, README. |
| [`aarso-icon-foreground.svg`](aarso-icon-foreground.svg) | Adaptive-icon **foreground** layer (108dp canvas, 72dp safe zone). Pair with a solid `#0E0F12` background layer. |

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
rsvg-convert -w 512 -h 512 docs/brand/aarso-logo.svg -o aarso-icon-512.png
# or: inkscape docs/brand/aarso-logo.svg --export-type=png -w 512 -o aarso-icon-512.png
```

Still needed for a full Play listing (tracked, not yet generated):
- **Feature graphic** 1024×500 (see `docs/play/store-listing.md`).
- **Phone screenshots** ×2–8 — owner-captured on the RedMagic (no device in CI).

The textual listing (title / short / full description, within Play's limits) is
generated headlessly by `domain/launch/StoreListing.kt`.
