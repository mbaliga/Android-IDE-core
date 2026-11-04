# S-FB4 — Compose Desktop draws one room; which Kotlin / Compose Multiplatform pairing works

**Verdict: PROVEN at Kotlin 2.1.0 (the repo pin) + Compose Multiplatform 1.8.2.**
**Compose Multiplatform 1.7.3 draws the room but FAILS the app's pinned markdown renderer** (finding **F2**) — that
is the version clash this spike was asked to measure. Evidence class: `CONTAINER-BUILD-ONLY`; look and feel is **NDV**
(not device verified — it is not even a claim about the Android app's real look). Plan step: `docs/PORTING_PLAN.md`
§6.1 **LX-2 / S-FB4**; the pairing table below is the P12 cost *input*.

## The question

Does Compose for Desktop draw one of the app's room layouts, headlessly, and **which Kotlin ↔ Compose Multiplatform pairing
does it work on without clashing with the repo's do-not-regress pins** (Kotlin 2.1.0, Compose BoM 2025.05.01 = Foundation
1.8.2, markdown renderer 0.35.0)? "Draws" is judged by pixels, not by "it compiled".

## What was built

A **simplified, static copy** of the shape of core's Chats room (`ConversationsRoom.kt`: title + close, the
All/Text/Image/Starred/Projects tab strip, three conversation cards) using plain `foundation` + `material3` `Text`
and a copy of Hyle's dark-register tokens (`Palette.kt`). **No dependency on `:core-engine`, on Hyle, or on any private
submodule.** The colourblind rule is followed in the copy: tab selection = bar **and** weight **and** a `▸` marker;
starred = filled star **and** the word "starred"; on-device vs cloud = a drawn square vs a drawn eye-in-circle **and** the
labels `ON-DEVICE` / `CLOUD · watched` (rule 2's "watched object"); the tree marker is drawn, not a font glyph.

## Evidence

```bash
export LANG=C.UTF-8 LC_ALL=C.UTF-8
./gradlew -p desktop/spikes/S-FB4 --no-daemon test -Pfb4.requireXvfb=true     # default pairing; JDK 17 + fonts + xvfb-run
./gradlew -p desktop/spikes/S-FB4 --no-daemon createDistributable            # unsigned app image (build/compose/binaries/main/app/spike-fb4)
./gradlew -p desktop/spikes/S-FB4 --no-daemon test -PkotlinVersion=2.1.0 -PcomposeVersion=1.7.3   # the failing pairing
```

**8 tests at the default pairing (Kotlin 2.1.0 + CMP 1.8.2), 0 failures:**

- `RoomRenderTest` (6; **no X server** — `ImageComposeScene`, an offscreen Skia surface): the room renders to a non-blank
  520×560 image whose corner is the `Ink` surface and which has >25 distinct colours; the `Raised` card surface (>20 000 px),
  the violet accent (>60 px) and high-contrast text (>400 px — proves fonts resolved) are present; both marker glyphs'
  tints are present and changing `cloud` changes the render; changing the selected tab / the starred flag changes the
  pixels; two renders are byte-identical; the pairing that actually ran is printed.
- `MarkdownPinTest` (1): the pinned `com.mikepenz.markdown.m3.Markdown` 0.35.0 composes and draws text (3265 dark pixels).
  It advances the scene clock until the renderer's background parse lands, and **captures recomposer-thread exceptions**
  (a composition failure is thrown on the scene's coroutine, not into the test thread — without this the failure shows
  only as a blank image).
- `XvfbWindowSmokeTest` (1): a **real** Compose Desktop `Window` (AWT + Skiko, `SKIKO_RENDER_API=SOFTWARE`) opens on a virtual
  X server (`xvfb-run`), is screenshotted with `java.awt.Robot`, and the screenshot has the `Ink`/`Raised` surfaces, the
  violet accent and text (ink 159 036 px, raised 103 663, violet 579, text 2 723). Skipped — shown as *skipped*, not passed —
  where `xvfb-run` is absent, unless `-Pfb4.requireXvfb=true` (CI sets it) turns that into a failure.
- `createDistributable` (not a test): produced a 135 MB unsigned app image at the default pairing
  (`bin/spike-fb4`, `lib/` with a bundled `runtime/`). **Not** signed, not uploaded, not for release. A **manual** check (not in
  CI, not a test): the packaged `bin/spike-fb4`, started under `SKIKO_RENDER_API=SOFTWARE xvfb-run`, was still running when
  `timeout 12` killed it (exit 124, no crash output) — no screenshot of the packaged app was taken.

Renders for inspection: `build/renders/room-default.png` (offscreen), `window-xvfb.png` (real window), `markdown.png`.

### The pairing table (P12 cost input) — measured, each row is a full re-run of the same 8 tests

| Kotlin (compiler + Compose plugin) | Compose Multiplatform | Skiko | Result | Note |
|---|---|---|---|---|
| **2.1.0** (repo pin) | 1.7.3 | 0.8.18 | **7 / 8 — `MarkdownPinTest` FAILS** | `composition failed: java.lang.NoSuchMethodError: 'void androidx.compose.foundation.text.BasicTextKt.BasicText-CL7eQgs(AnnotatedString, Modifier, TextStyle, Function1, int, boolean, int, int, Map, ColorProducer, TextAutoSize, Composer, int, int, int)'` (parameter types shown without their package prefixes) — the room itself draws fine |
| **2.1.0** (repo pin) | **1.8.2** | 0.9.4.2 | **8 / 8** | default; CMP 1.8.2 = Compose 1.8.2 = the Foundation line of the repo's BoM 2025.05.01 |
| 2.1.20 (OQ-17 first-wave stage) | 1.8.2 | 0.9.4.2 | 8 / 8 | not required by this spike |
| 2.2.21 (OQ-38 iOS pin) | 1.9.3 | 0.9.22.2 | 8 / 8 | not required by this spike; the classpath held **two** `runtime-desktop` versions (1.9.3 and 1.9.4) — not investigated |

(The JetBrains Kotlin ↔ Compose compatibility table was **not** consulted; the rows are what actually ran.)

## Findings

| # | Finding |
|---|---|
| F1 | **The desktop head can stay on the repo's Kotlin 2.1.0** with CMP **1.8.2**. No compiler/plugin warning was emitted at that pairing (`org.jetbrains.kotlin.plugin.compose` is pinned to the Kotlin version, the K2 Compose compiler plugin). The OQ-17 staged bump (2.1.20) and OQ-38's 2.2.21 are *not forced* by Compose Desktop on this evidence; they also pass. This does **not** say the Android gate would survive those bumps (S-FB4 never touches it). |
| **F2** | **CMP 1.7.3 cannot run the markdown renderer core pins.** `multiplatform-markdown-renderer-m3:0.35.0` calls the `BasicText` overload with `TextAutoSize`, which exists only from Foundation 1.8 — the exact `NoSuchMethodError` `CLAUDE.md` records for an old Compose BoM on Android (the "launch/send crash"). The failure is silent in a Skia scene (an empty render) until the recomposer's uncaught exception is captured. So the **Compose ↔ markdown pin carries over to desktop**: any desktop head must be on a Compose Multiplatform whose Foundation is ≥ 1.8, in lockstep with the Android BoM's line. |
| F3 | Runtime `kotlin-stdlib` resolves **above** the compiler (2.1.21 at the Kotlin-2.1.0 pairings) because the markdown renderer / CMP artifacts are built on newer 2.1.x; harmless here, but not "exactly the pin". |
| F4 | `ImageComposeScene` (the headless render entry point) is an `@ExperimentalComposeUiApi`; the test opts in. It needs **no X server and no GPU**, but it does need a system font set: Skia reads fonts through fontconfig, so a font-less image renders no text (the workflow installs `fontconfig` + `fonts-dejavu-core` if `fc-list` is empty). |
| F5 | A real window needs a display; on a virtual X server Skiko must be forced to its software rasteriser (`SKIKO_RENDER_API=SOFTWARE`) — there is no GL. The window opened and painted correctly under Xvfb in this container. Not tested: a real X11/Wayland session, HiDPI scaling, input, GPU rendering. |
| F6 | `createDistributable` works at the default pairing with only a JDK 17 (jpackage); **no application identifier is set** (R11) — `packageName = "spike-fb4"` is the image's file name, there is no `bundleID`/reverse-DNS id. `packageDeb`/signing were not attempted. |
| F7 | **`LocalContext`, `BackHandler`, `viewModel()`, `R.font`/`R.drawable` and `android.graphics` are what the plan expects to block the *real* rooms** (LX-6). A static grep shows only **3 of Hyle's 31 source files** import `android.*` (`cells/Aeon.kt`, `HyleHaptics.kt`, `HyleSubstrate.kt`); `core-engine/.../ui/rooms/ChatsRoom.kt` itself imports `androidx.compose.ui.platform.LocalContext`. None of this was compiled here — the copy deliberately avoids all of them, so this spike says nothing about how hard LX-6's replacements are. |

## What this does not show

- **Not the real room.** A simplified copy with static data — no view-model, gestures, state, Hyle atoms, real theme, or navigation.
  Whether the real `ui/` compiles on Compose Desktop (LX-6's seven blockers) is untested.
- **Look and feel is NDV.** Pixel checks prove "drew the palette, text, shapes and state changes", not that it looks right to a person.
- Not a UI *test* framework: assertions are pixel counts on a software render. Not Wayland, HiDPI, macOS, Windows, accessibility.
- The Android side was never involved; the Compose BoM / markdown pins on Android are unchanged and unexercised here.
- `createDistributable` ≠ a releasable package: unsigned, no installer format, no auto-update, not run on a user machine.

## Layout

`settings.gradle.kts` (plugin versions are properties) · `gradle.properties` (default pairing) · `build.gradle.kts` ·
`src/main/kotlin/dev/fonebrew/spike/fb4/{Palette,ConversationsRoom,Main}.kt` (`Main --smoke <png>` is the window driver) ·
`src/test/kotlin/…/{RoomRenderTest,MarkdownPinTest,XvfbWindowSmokeTest}.kt`. `build/` is git-ignored.
