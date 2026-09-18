# images/

Sizes and check commands: `Personal-Tracker/store/ASSET_SPECS.md`.

## Needed, none present yet

- `icon.png` — 512x512, **no alpha**. Export the adaptive icon
  (`core-engine/src/main/res/mipmap-*`) flattened onto its background layer; do not
  upload the foreground with transparency.
- `featureGraphic.png` — 1024x500, no alpha.
- `phoneScreenshots/` — 2 to 8, 1080x1920, no alpha.

## Screenshots must come from a real device

There is no emulator in the build environment (see `CLAUDE.md`, "Environment
honesty"), and CI never launches the app: the native assemble is disabled because
it OOMs the runner. So every screenshot here is owner-captured on the phone.

Shoot, in this order:
1. First-run setup card.
2. A chat with markdown rendering and the instruments strip expanded.
3. The Tree map, showing a branch and a restore point. This is the feature nothing
   else on the store has; it deserves the second slot if you only take three.
4. The Models catalogue.

```sh
adb exec-out screencap -p > shot.png
magick shot.png -background black -alpha remove -alpha off phoneScreenshots/01.png
```

**Before uploading, check every frame for:** a real API key visible anywhere, a
private repository name in the agent or Git surfaces, and any conversation content
you would not publish. Screenshots of this app leak more easily than most.
