# CLAUDE.md — Fonebrew (formerly "Aarso/Workbench") build handoff

**Aarso** (*mirror*; package `dev.fonebrew`) is a local-first Android app for working with multiple
AI models. Its design thesis is **legibility + cognitive sovereignty** — a *tool-as-argument*
artifact, not a generic chat client. Where a fork exists, prefer the option that makes
routing/influence visible and keeps the user in the loop, even at some cost to convenience.

> **Read `docs/STATE.md` first.** It is the living index of *what's done / pending / the end
> goal* across the whole constellation. This file is the **build rules + how-to-continue**;
> `docs/STATE.md` is the current engineering state. When they disagree, STATE.md is newer.
> Business/monetization planning is tracked privately, not in this repo.

## North star + naming split
The product is a **post-desktop, touch-native computing environment** that makes the phone a
**sovereign primary device** — sovereign because every layer is legible, not because the work is
effortless. The agent is what lets you do real computing work without desktop fluency.

Two names, two scopes (do not conflate):
- **Fonebrew** — the **host app** (the computing environment) — **owner-decided, final** (was the
  placeholder "Workbench"; that name is retired, don't reintroduce it). Everything that isn't the
  lens is Fonebrew. The launcher label (`core-engine/src/main/res/values/strings.xml` /
  `src/full/…`) reads "Fonebrew" — **do not revert it to "Aarso."**
- **Aarso** (*mirror*) — the **within-axis self-reflection lens** only. Bounded `domain/mirror/`
  seam, ships **inert**, carries **no §5b/§5c metric logic** — ⛔ blocked on Issue #2 (rule 4).
  Package name (`dev.fonebrew`), class names (`FonebrewApp`, `Theme.Aarso`), and repo names are
  **unchanged for now** — the rename is decoupled from the app-facing name above, deferred to a
  late "Sprint R." Don't let that stale internal naming pull the *user-facing* label back to
  "Aarso"; the two are independent axes.

## Binding rules (owner-set, do not relax)
1. **No telemetry, analytics, or phoning home. Ever.** Zero such dependencies.
2. **On-device is always the default.** Cloud is opt-in per use, never a hidden fallback, and
   every cloud provider is visibly marked a **"watched object"**. Cloud is **provider-generic**
   (Anthropic / OpenAI-compatible / Gemini — any vendor), not vendor-special-cased.
3. **Never label the council feature "MoE" / "Mixture of Experts."** It is a *council*
   (mixture-of-agents). (The IA once said "mixture of experts" descriptively — the UI stays
   "Council"; flag if the owner ever overrides.)
4. **§5c idiolect baseline is owner-only input — do not invent it.** §5c self-observation + §5b
   drift are **blocked on GitHub Issue #2**. The Me/Myself/I drift surface ships **inert**.
5. **API keys**: encrypted at rest via Android Keystore (`security/KeystoreSecret.kt`), never
   logged, never sent anywhere except the provider they belong to.
6. **Plan before code; small legible commits; honest uncertainty.** Never claim on-device
   behaviour works — the build env has no device/emulator; the owner tests on the phone.

## Target device & conventions
- Naming register: follow the codebase (e.g. *Fonebrew*, *Hyle*).
- Target: **a high-end arm64-v8a Android phone** (large unified RAM, recent Android). `minSdk 31`,
  `targetSdk/compileSdk 36`, single ABI `arm64-v8a`.

## Repo map
```
app/                        main module (Kotlin + Compose, manual DI — no Hilt)
  src/main/java/dev/aarso/
    domain/                 pure Kotlin, JVM-tested: tree, council, bpmn, loop (GraphRunner),
                            diff (ChangeSet/ReviewSession/LineDiff), device (ArduinoCli, usb/
                            IntelHex+Stk500), git (GitContentsApi/GitTreeApi), ide (RepoWorkLoop),
                            remote (SSH session/term), disclosure, instruments, mirror (inert),
                            search (Segmenter/LexicalSearch + query/ parser, facets, scope stack)
    data/                   Room (append-only tree), stores, repos, transports; AgentRepoRunner,
                            DeviceRepo, GitEdit/GitBackup/GitBrowse, RemoteHostStore,
                            search/ (SQLDelight FTS5 index: projector, indexer, retrieval)
    inference/              InferenceEngine; LlamaCppEngine (JNI), Echo (dev), EngineGenerator,
                            cloud/ (Anthropic, OpenAI-compat, Gemini — SSE), image/
    service/                GenerationService (FGS), OverlayService, ScreenCapture (+OCR), Voice
    ui/                     AppRoot + SpatialRoot (room model, NOT bottom nav); rooms/, loops/,
                            develop/, codelens/, ide/ (ReviewSheet), remote/, theme/
                            (theme/ is now app-side theming ONLY — ThemeMode, FonebrewTheme,
                            ThemePicker, Texture. The palette + every Hyle component moved
                            to the :hyle library; see below.)
    security/               KeystoreSecret (AES-GCM key encryption)
  src/main/cpp/             llama_jni.cpp + CMake + llama.cpp submodule → libaarso_llama.so
  src/test/                 400+ JVM unit tests (domain/ + data-layer) — keep green
sdengine/                   stable-diffusion.cpp submodule + sd_jni.cpp → libaarso_sd.so
hyle-design-system/         git submodule (mbaliga/Hyle-Design-System) — the SINGLE source of
                            dev.aarso:hyle:0.2.0, composited via includeBuild (settings.gradle.kts).
                            No vendored :hyle module here anymore. Hyle ships COMPONENTS, not just
                            tokens: dev.aarso.hyle.cells (HyleField, HyleButton, HyleCard,
                            HyleTabBar, HyleChip, HyleWellToggle, …), dev.aarso.hyle.theme
                            (HyleColors/LocalHyleColors/accent ramp), and dev.aarso.hyle.component
                            (the desktop-class kit). Do not re-add app-local copies — 0.1.0
                            shipped from three divergent copies; single-sourcing exists to
                            prevent that. Change components in the submodule.
                            **Hyle now ships COMPONENTS, not just tokens** — it is a real
                            dependency, not a mirror. `dev.aarso.hyle.cells` (HyleField,
                            HyleButton, HyleCard, HyleTabBar, HyleChip, HyleWellToggle, …) and
                            `dev.aarso.hyle.theme` (HyleColors, LocalHyleColors, the accent
                            ramp) live there and NOWHERE else. Do not re-add an app-local copy:
                            0.1.0 shipped from three divergent copies, which is what
                            single-sourcing exists to prevent. Change a component in the
                            submodule, not in app/.
hyle-probe/                 on-device render harness app for Hyle (depends on dev.aarso:hyle)
```

### Architecture spine (don't violate)
- **One append-only, git-like message tree.** Branch/restore/model-switch/council are operations
  over it. Restore = make an earlier node the active leaf.
- **`InferenceEngine` plumbs per-token logprobs/entropy from token one** — why llama.cpp over
  Ollama. Don't drop it.
- Local generation uses the **model's own chat template** (GGUF metadata) + repeat penalty 1.1.
  KV-cache snapshots via optional `sessionLoadPath`/`sessionSavePath`.
- Council = editable **participants** (per-member name/instructions/own model/memory); plus a
  model-diversity mode. The interaction model is **immutable once a chat starts** — changing it
  branches with a summary.
- **Loops run on `GraphRunner`** (arbitrary BPMN graphs; gateways branch on edge labels). A loop
  *definition* serialises to **standard BPMN 2.0** (`domain/bpmn/`), a *run* is the message tree;
  both sync to the user's Git host.

## Building
- JDK 17 auto-provisioned (foojay resolver). `git submodule update --init --recursive`.
- Android SDK/NDK: `scripts/setup-android-sdk.sh` (NDK `28.2.13676358`, CMake `3.31.6`; r28 emits
  16 KB-page-aligned libs). Note the script's own `NDK=` pin is the *older* r27 — pass the r28
  version above to `sdkmanager` directly, since both native modules declare `ndkVersion` r28.
- **Native platform level is pinned explicitly (do not remove):** `:core-engine` and `:sdengine`
  each pass `-DANDROID_PLATFORM=android-31` via `defaultConfig.externalNativeBuild.cmake.arguments`.
  Left implicit, AGP configures CMake at **android-22** (`--target=aarch64-none-linux-android22`)
  despite `minSdk = 31`, and bionic guards `POSIX_MADV_*` behind `__ANDROID_API__ >= 23` — so
  `llama.cpp/src/llama-mmap.cpp` fails with *"use of undeclared identifier
  'POSIX_MADV_WILLNEED'"*. This is a build-config bug, not an upstream llama.cpp one.
- **Run gates unpiped.** `./gradlew … | tail` reports *tail's* exit code, so a failed build looks
  green. Redirect to a file and check `$?` (`./gradlew … > log 2>&1; echo $?`).
- **Set a UTF-8 locale before the JVM gate** (`export LANG=C.UTF-8 LC_ALL=C.UTF-8`). Under the
  container's default POSIX locale, Kotlin can't write test classes whose names contain non-ASCII
  (several tests use an em-dash), failing with `InvalidPathException: Malformed input`.
- Flavors (`dist` dimension): **`full`** (sideload; all tiers; appId `dev.aarso.full`, default) /
  **`play`** (policy-safe; no overlay/screen-capture/USB-host; appId `dev.aarso`).
- Gate: `./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest` (keep green). Hyle's own
  `:hyle:test` now runs in the Hyle repo's CI; core consumes Hyle via the includeBuild'd submodule
  (`git submodule update --init --recursive` first, so the composite build resolves `dev.aarso:hyle`).
- `./gradlew :app:assembleFullDebug` → sideload APK (slow native cross-compile).
  `:app:bundlePlayRelease` → Play AAB.
- **Two SQL toolchains coexist in the main module (do not "unify"):** Room owns the append-only
  message tree (`AppDatabase`); **SQLDelight owns the FTS5 search index** (`SearchDatabase`, schema
  at `src/main/sqldelight/dev/aarso/data/search/Search.sq`) as a *separate SQLite file*. This isn't
  duplication — Room exposes no `@Fts5` (only `@Fts3`/`@Fts4`), so bundled SQLite via
  `androidx.sqlite:sqlite-bundled` is the entry ticket for FTS5. That artifact also ships JVM-host
  natives, which is why `src/test` runs **real** `MATCH`/`bm25()` queries with no device or
  Robolectric. Two SQLDelight-analyzer workarounds are documented inline and must not be "cleaned
  up": `ORDER BY bm25(...)` instead of FTS5's `rank` magic column, and the external-content sync
  triggers installed as raw SQL from `SearchDriverFactory` (the analyzer `ClassCastException`s on
  any explicit-column-list `INSERT` into a virtual table). Keep `sqldelight = 2.1.0`: 2.3.x forces
  a Kotlin Gradle Plugin bump that breaks KSP and threatens the Compose BoM pin below.
- **Compose ↔ markdown pin (do not regress):** the mikepenz markdown renderer (`0.35.0`) needs
  Compose **Foundation 1.8** (`BasicText`'s `TextAutoSize`). `composeBom = 2025.05.01` (Foundation
  1.8.2) satisfies it. Pinning an older BoM → runtime `NoSuchMethodError` on every markdown turn
  (this was the launch/send crash). Keep BoM ≥ 1.8 or downgrade the renderer in lockstep.
- **APK delivery:** push the APK as `fonebrew-sd.apk` on the **orphan branch `apk-dist`**
  (`--force`).
- **CI caveat:** the workflow runs the JVM gate only — the native assemble is `if: false` (it OOMs
  the runner), so **CI never launches the app**. A device-only launch/render crash passes CI. The
  in-app **crash-recovery harness** exists precisely because of this. **Actions are WORKING again
  (verified 2026-08-21):** `build-test` runs for real (~6.5 min) and passes — so treat red CI as a
  real signal again, not noise. *(Historical note, kept so the symptom stays recognizable: from
  ~2026-06-30 to 2026-08 every run died in 3–6s with no runner assigned — exhausted Actions
  minutes / spending limit on the private repo, fixed at the account level, never by re-running.
  If that 3–6s-death signature ever returns, it's billing again — see `docs/STATE.md` §9.)*

## Environment honesty
The container compiles everything but has **no device, emulator, board, or SSH host**. All
runtime/gesture/render/network/hardware behaviour is **owner-verified only**. The tested cores
(parsers, Intel HEX, STK500, diff, Git request builders, BPMN round-trip, block parser) are the
machine-verified parts. Never report on-device behaviour as confirmed.

---

## The constellation (multi-repo / multi-app)
A family of cooperating apps, not a monolith.

| Component | What | Source | Status |
|---|---|---|---|
| **Fonebrew** (this repo) | the computing environment | **open core** | shipping v0.13.0 |
| **Hyle** | design system | **open** | **separate repo `mbaliga/Hyle-Design-System`** — consumed here via git submodule + includeBuild; the single source of `dev.aarso:hyle:0.2.0` (split done) |
| **PM + authoring** | a companion project-management surface | not in this repo | code lives elsewhere |
| **Sound & haptics** | companion authoring app | **open** | not started |
| **Routing engine** | on-device + cloud LLM router (any app) | not in this repo | not started here |

Integration rule: the routing engine needs a **stable public API** from day one. Business/licensing
decisions for these components are tracked privately, not in this repo.

## Current state — v0.13.0 (2026-06-28; on `apk-dist` as `fonebrew-sd.apk`)
Everything below compiled + JVM-tested + assembled; **device behaviour is owner-verified.** Full
detail in `docs/STATE.md`.

**DONE — what shipped this stage**
- **Spatial IA, full set:** Chat home; **Conversations** left (All/Text/Image/Starred/Projects);
  **Settings** right (5 icon tabs Global/Image/Text/Video/3D + on-device⇄cloud toggle + GitHub
  connect + Export-everything + **Me·Myself·I** profile via header avatar); **Project** top (Tasks:
  Board/List/**Waterfall** + **Incidents**); **Tree** z-axis (git-sync + export + handoff summary);
  **Develop** bottom.
- **Chat:** Gemini-style **`+` composer** (no image pill); editable **council participants**
  (per-member model+memory); **immutable interaction model** (branch-with-summary on change).
- **Agentic IDE:** Develop→**Agent** (read repo → propose ChangeSet → **per-hunk review** →
  **squashed commit** via Git tree API); **editable CodeLens**; **Devices** (Pi shell /
  Arduino-via-Pi / ESP-OTA / **This phone USB** with tested STK500+IntelHex core); **Loop editor**
  is now a **full free-form graph editor** (add/move/connect/delete, per-node model, GraphRunner
  execution, BPMN save/load).
- **Reliability:** crash recovery via the shared `dev.aarso:crash-recovery` module (same
  submodule as Hyle, separate coordinate — captures the trace, shows a recovery screen,
  never bricks; used across the constellation, not just here — see that repo's README);
  **fixed the launch/send crash** (Compose BoM → Foundation 1.8). **Preview the recovery
  screen without a real crash:** Settings → Global → About, long-press the version line
  (debug builds only) — calls `CrashRecovery.previewIntent(context, "Fonebrew")`.
- **Design system:** Hyle single-sourced to its own repo `mbaliga/Hyle-Design-System`
  (`dev.aarso:hyle:0.2.0`), consumed here via git submodule + includeBuild; the vendored `:hyle`
  copy is deleted. (`0.1.0` retired — it had shipped from three divergent copies.)
- Versions this stage: v0.9.0 IA → v0.13.0 (current). See STATE.md §3 for the per-version list.

**PLANNED / PENDING**
- *Owner-blocked (need an owner action):* create the **sound/haptics** repo + grant access.
  (**Hyle is done** — its own repo `mbaliga/Hyle-Design-System`, consumed via submodule +
  includeBuild. PM/authoring and the routing engine are tracked outside this repo.)
- *Engineering follow-ups:* Chat §B4 per-member **files** (needs file→context plumbing); **live
  per-step streaming in the graph Loop run** (`GraphRunner` progress callback); **video/3D**
  engines; **AI-assisted config** (parked); **drag-a-wire** Loop connect; **USB** on-device verify
  with a real CDC board (CH340/CP210x clones need a vendor driver).
- *Owner-blocked backlog (`CLAUDE.md` history):* §5c/§5b drift (Issue #2); real on-device embedder
  (replace `PlaceholderEmbedder`); §5a base-vs-instruct diff; acceleration (Vulkan/NPU —
  benchmark, never assume); Google Play publication mechanics (AAB/signing/data-safety/screens).

## Open owner decisions (engineering-scoped)
1. Create the sound/haptics repo + grant access.
2. Device verification: Echo send + relaunch (markdown fix), the Loop editor feel, Devices/SSH
   flows, USB flash with a real board.

Business/monetization decisions are tracked privately, not in this repo.

## How to continue (for the next chat)
1. Read **`docs/STATE.md`** (the living index), then this file's binding rules + building.
2.  for the business; `docs/design/*` for per-surface specs
   (`agentic-ide.md`, `information-architecture.md`, `workflow-builder.md`); `docs/handoff/
   hyle-extraction.md` — the split plan, now **executed** (Hyle lives in `mbaliga/Hyle-Design-System`;
   kept as historical record).
3. Keep the gate green, ship small legible PRs to `main`, refresh `fonebrew-sd.apk` on
   `apk-dist`, and be honest that on-device behaviour is owner-verified.

## Reunification note (2026-08-21)
The launch line (`fix/models-carousel-and-terminal`, 45 commits: vision input W1, web search W2,
PTY terminal with @mentions/sigils/! escape, models carousel, onboarding wizard incl. opt-in
AiCore/Gemini Nano, launch branding + `brand_logo` adaptive icon, Watch feature REMOVED — owner
call, moving to Studio) was merged back into the development line (thread topology WP0–11, 3D
objects, desktop-class kit, dev.fonebrew rename). Version spine continues the launch reset:
0.2.0, but versionCode stays monotonic past the interim dev sideloads (18+). The launch line's
styling and copy win where the two conflicted; the de-fork (:core-engine) architecture stands.
