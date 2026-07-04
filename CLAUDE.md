# CLAUDE.md — Aarso / Workbench build handoff

**Aarso** (*mirror*; package `dev.aarso`) is a local-first Android app for working with multiple
AI models. Its design thesis is **legibility + cognitive sovereignty** — a *tool-as-argument*
artifact, not a generic chat client. Where a fork exists, prefer the option that makes
routing/influence visible and keeps the user in the loop, even at some cost to convenience.

> **Read `docs/STATE.md` first.** It is the living index of *what's done / pending / the end
> goal* across the whole constellation. This file is the **build rules + how-to-continue**;
> `docs/STATE.md` and `docs/monetization.md` are the current state + business. When they
> disagree, STATE.md is newer.

## North star + naming split
The product is a **post-desktop, touch-native computing environment** that makes the phone a
**sovereign primary device** — sovereign because every layer is legible, not because the work is
effortless. The agent is what lets you do real computing work without desktop fluency.

Two names, two scopes (do not conflate):
- **Workbench** — *placeholder* for the **host app** (the computing environment). Not final; the
  naming brief is owner-decided. Everything that isn't the lens is Workbench.
- **Aarso** (*mirror*) — the **within-axis self-reflection lens** only. Bounded `domain/mirror/`
  seam, ships **inert**, carries **no §5b/§5c metric logic** — ⛔ blocked on Issue #2 (rule 4).
  Package rename (`dev.aarso` → host name) is decoupled, deferred to a late "Sprint R".

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
- Naming register: follow the codebase (e.g. *Aarso*, *Hyle*).
- Target: **a high-end arm64-v8a Android phone** (large unified RAM, recent Android). `minSdk 31`,
  `targetSdk/compileSdk 36`, single ABI `arm64-v8a`.

## Repo map
```
app/                        main module (Kotlin + Compose, manual DI — no Hilt)
  src/main/java/dev/aarso/
    domain/                 pure Kotlin, JVM-tested: tree, council, bpmn, loop (GraphRunner),
                            diff (ChangeSet/ReviewSession/LineDiff), device (ArduinoCli, usb/
                            IntelHex+Stk500), git (GitContentsApi/GitTreeApi), ide (RepoWorkLoop),
                            remote (SSH session/term), disclosure, instruments, mirror (inert)
    data/                   Room (append-only tree), stores, repos, transports; AgentRepoRunner,
                            DeviceRepo, CrashLog, GitEdit/GitBackup/GitBrowse, RemoteHostStore
    inference/              InferenceEngine; LlamaCppEngine (JNI), Echo (dev), EngineGenerator,
                            cloud/ (Anthropic, OpenAI-compat, Gemini — SSE), image/
    service/                GenerationService (FGS), OverlayService, ScreenCapture (+OCR), Voice
    ui/                     AppRoot + SpatialRoot (room model, NOT bottom nav); rooms/, loops/,
                            develop/, codelens/, ide/ (ReviewSheet), remote/, theme/, aeon/
    security/               KeystoreSecret (AES-GCM key encryption)
  src/main/cpp/             llama_jni.cpp + CMake + llama.cpp submodule → libaarso_llama.so
  src/test/                 400+ JVM unit tests (domain/ + data-layer) — keep green
sdengine/                   stable-diffusion.cpp submodule + sd_jni.cpp → libaarso_sd.so
hyle-design-system/         git submodule (mbaliga/Hyle-Design-System) — the SINGLE source of
                            dev.aarso:hyle:0.2.0, composited via includeBuild (settings.gradle.kts).
                            No vendored :hyle module here anymore.
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
  16 KB-page-aligned libs).
- Flavors (`dist` dimension): **`full`** (sideload; all tiers; appId `dev.aarso.full`, default) /
  **`play`** (policy-safe; no overlay/screen-capture/USB-host; appId `dev.aarso`).
- Gate: `./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest` (keep green). Hyle's own
  `:hyle:test` now runs in the Hyle repo's CI; core consumes Hyle via the includeBuild'd submodule
  (`git submodule update --init --recursive` first, so the composite build resolves `dev.aarso:hyle`).
- `./gradlew :app:assembleFullDebug` → sideload APK (slow native cross-compile).
  `:app:bundlePlayRelease` → Play AAB.
- **Compose ↔ markdown pin (do not regress):** the mikepenz markdown renderer (`0.35.0`) needs
  Compose **Foundation 1.8** (`BasicText`'s `TextAutoSize`). `composeBom = 2025.05.01` (Foundation
  1.8.2) satisfies it. Pinning an older BoM → runtime `NoSuchMethodError` on every markdown turn
  (this was the launch/send crash). Keep BoM ≥ 1.8 or downgrade the renderer in lockstep.
- **APK delivery:** push the APK as `aarso-sd.apk` on the **orphan branch `apk-dist`** (`--force`).
- **CI caveat:** the workflow runs the JVM gate only — the native assemble is `if: false` (it OOMs
  the runner), so **CI never launches the app**. A device-only launch/render crash passes CI. The
  in-app **crash-recovery harness** exists precisely because of this. **Red `build-test` is a
  GitHub-Actions billing/minutes block, not a code or 403 flake** (corrected 2026-06-30): every run
  dies in 3–6s with no runner assigned (job never starts) — the signature of exhausted Actions
  minutes / spending limit on a private repo. The gate itself is green (reproduced locally: 868
  tests, 0 failures). Fix is account-level (top up minutes / raise the limit) or rely on public
  repos (unlimited free Actions). "Re-run to clear" does nothing — see `docs/STATE.md` §9.

## Environment honesty
The container compiles everything but has **no device, emulator, board, or SSH host**. All
runtime/gesture/render/network/hardware behaviour is **owner-verified only**. The tested cores
(parsers, Intel HEX, STK500, diff, Git request builders, BPMN round-trip, block parser) are the
machine-verified parts. Never report on-device behaviour as confirmed.

---

## The constellation (multi-repo / multi-app)
A family of cooperating apps, not a monolith. Dependency direction sinks toward the routing engine.

| Component | What | Source | Status |
|---|---|---|---|
| **Aarso/Workbench** (this repo) | the computing environment | **open core** | shipping v0.13.0 |
| **Hyle** | design system | **open** | **separate repo `mbaliga/Hyle-Design-System`** — consumed here via git submodule + includeBuild; the single source of `dev.aarso:hyle:0.2.0` (split done) |
| **PM + authoring** | the paid "Studio" layer | **closed** | repo pending owner; code in main, to carve out |
| **Sound & haptics** | companion authoring app | **open** | not started |
| **Routing engine** | on-device + cloud LLM router (any app) | **closed today; rec = open-core** | not started |

Integration rule: the routing engine needs a **stable public API** from day one. See `docs/STATE.md`
§7 + `docs/monetization.md` for the open-core recommendation (open engine, dual-licensed
intelligence, never hold others' keys).

## Current state — v0.13.0 (2026-06-28; on `apk-dist` as `aarso-sd.apk`)
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
- **Reliability:** **crash-recovery harness** in the final app (captures the trace, shows a
  Recovery screen, never bricks); **fixed the launch/send crash** (Compose BoM → Foundation 1.8).
- **Design system:** Hyle single-sourced to its own repo `mbaliga/Hyle-Design-System`
  (`dev.aarso:hyle:0.2.0`), consumed here via git submodule + includeBuild; the vendored `:hyle`
  copy is deleted. (`0.1.0` retired — it had shipped from three divergent copies.)
- Versions this stage: v0.9.0 IA → v0.13.0 (current). See STATE.md §3 for the per-version list.

**PLANNED / PENDING**
- *Owner-blocked (need an owner action):* create the **PM/authoring**, **sound/haptics**,
  **routing-engine** repos + grant access; then carve them out. (**Hyle is done** — its own repo
  `mbaliga/Hyle-Design-System`, consumed via submodule + includeBuild.) Wire **monetization**
  (task #1). Confirm **routing-engine** open-core.
- *Engineering follow-ups:* Chat §B4 per-member **files** (needs file→context plumbing); **live
  per-step streaming in the graph Loop run** (`GraphRunner` progress callback); **video/3D**
  engines; **AI-assisted config** (parked); **drag-a-wire** Loop connect; **USB** on-device verify
  with a real CDC board (CH340/CP210x clones need a vendor driver).
- *Owner-blocked backlog (`CLAUDE.md` history):* §5c/§5b drift (Issue #2); real on-device embedder
  (replace `PlaceholderEmbedder`); §5a base-vs-instruct diff; acceleration (Vulkan/NPU —
  benchmark, never assume); Google Play publication mechanics (AAB/signing/data-safety/screens).

## Monetization (decided — `docs/monetization.md`)
FOSS-first **patronage + warm unlock**; **no coercive subscriptions ever**; nobody locked out.
Line = **free to build, pay to ship & sell**: free/open core (chat, on-device + open models,
Loops, git tree, coding Agent, all Settings incl. GitHub) vs a paid **Studio** layer (PM UX,
app-store push pipeline, branding playbooks). One-time **Lifetime ~$20** spine + optional
**Friends-of-the-Dev** patronage (gates nothing) + **pay-what-you-can/EMI**. Honor-system unlock
(an open build can't be enforced — by design). **Open decision:** closed `:power` module vs
fully-open + supporter flag.

## Open owner decisions
1. Create the new repos + grant access (PM / sound-haptics / routing engine). *(Hyle done — `mbaliga/Hyle-Design-System`.)*
2. Monetization seam: closed `:power` vs open + supporter flag.
3. Routing engine: confirm open-core (STATE.md §7).
4. PM free/paid line (rec: PM paid).
5. Device verification: Echo send + relaunch (markdown fix), the Loop editor feel, Devices/SSH
   flows, USB flash with a real board.

## How to continue (for the next chat)
1. Read **`docs/STATE.md`** (the living index), then this file's binding rules + building.
2. `docs/monetization.md` for the business; `docs/design/*` for per-surface specs
   (`agentic-ide.md`, `information-architecture.md`, `workflow-builder.md`); `docs/handoff/
   hyle-extraction.md` — the split plan, now **executed** (Hyle lives in `mbaliga/Hyle-Design-System`;
   kept as historical record).
3. Keep the gate green, ship small legible PRs to `main`, refresh `aarso-sd.apk` on `apk-dist`,
   and be honest that on-device behaviour is owner-verified.
