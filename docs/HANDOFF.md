# Aarso — Handoff (complete)

> Single, self-contained handoff for any session (human or agent) picking this up.
> Pairs with `CLAUDE.md` (binding rules) and `docs/status.md` (running snapshot) and
> `docs/design/` (per-feature design). **Design-system specifics are deliberately out of
> scope here** — the owner is providing a read-only design system; current UI is
> intentionally wireframe/Aeon-Hyle and will be restyled, not rearchitected.
>
> Last updated: 2026-06-21. Everything below is **CI-green** unless marked otherwise.
> Source of truth: **`main`**. The 2026-06-21 reframe + autonomous build are sequenced in
> `docs/build-plan.md` (read it next): **Phases 0–2 + P1/P2/P5/P6 are merged** (PRs #5–#16) —
> the mirror seam, the remote-exec/terminal/device spine, the closed agentic loop on existing
> repos (graph runner → repo work loop → diff-review), live usage→Cost, pricing book, durable
> queue, and loop `.bpmn` Git sync. All headless + JVM-tested (395 @Test). Remaining: P3 (UI),
> P4 (real embedder — device/model), and the §E owner-gated ceiling (device verification, the
> data-layer wiring behind the new seams, design-system restyle, Issue #2).
>
> **2026-06-21 (later) — made real on device (PRs #18–#31).** The headless engines are now wired
> into testable UI + a new spatial map; see `docs/status.md` → "Made real on device": sshj Remote
> + interactive terminal, loops-into-tree + `.bpmn` Git sync, Cost G1 (per-turn inline + pricing),
> durable operation queue, the four-edge spatial shell (Top = Project planning, Bottom = Dev,
> pinch-out = Loops) + Models-into-Settings, guided walk-throughs, the free-tier guide + monthly
> freshness pipeline + per-provider usage, and the bloom canvas. APK on `apk-dist`. The
> design-system "ditch Aeon → generic" pass is the open owner call.

---

## 1. North star

**Aarso** (*mirror*; package `dev.aarso`) is a **local-first Android app for working with
multiple AI models**, whose design thesis is **legibility + cognitive sovereignty** — a
*tool-as-argument* artifact, not a generic chat client. One thesis sentence:

> *The interface does not mediate reality through a language it controls; it presents
> material whose physics you read for yourself.*

It is a **physics engine for thought**:
- **Models are instruments**, composed into **loops**, not endpoints.
- **Sovereignty is material**: you see what runs where (on-device vs. *watched* cloud),
  you own your history (mirrored to your Git host), you make the surface yours.
- **The loop is the product**: objective → propose → critique → refine, made visual,
  editable, named, shareable.
- **Your repos, your builds**: Aarso is where you *develop* Android apps — browse code,
  run loops over it, see CI, install builds.
- **Legibility over power**: non-technical users can read what it's doing and feel in
  control; nothing important is hidden.

### Expanded direction (owner, 2026-06-19)
Aarso (esp. the Play build) is also an **agentic Android IDE** — conceive → build → test
→ launch an Android app **end-to-end from the phone** ("TikTok for Android app dev"),
plus **project management** and **app-launch generation** in-app. Sequencing chosen:
**foundation first, then the three pillars equally.** A fourth pillar, **Cost**, was added
(below). Pillars are built as one integrated story.

### Reframe + naming split (owner, 2026-06-21) — see `docs/build-plan.md`
The north star widened to a **post-desktop, touch-native computing environment** that makes
the phone a **sovereign primary device**. Two scopes now: **Workbench** = placeholder name
for the **host app** (everything that isn't the lens); **Aarso** (*mirror*) = the within-axis
self-reflection **lens** only, living in a bounded inert `domain/mirror/` seam (no §5b/§5c
logic — Issue #2). The build plan (`docs/build-plan.md`) sequences autonomous, headless work:
a remote-exec/device-IO spine (SSH/terminal/RPi/Arduino), closing the agentic loop on existing
repos (graph runner → repo work loop → diff-review), then the remaining headless follow-ons —
stopping at the device/design/Issue-#2 ceiling (§E there). Package rename is deferred ("Sprint R").

---

## 2. Binding rules (owner-set — do not relax; full text in `CLAUDE.md`)

1. **No telemetry, analytics, or phoning home. Ever.** Zero such dependencies.
2. **On-device is the default.** Cloud is opt-in per use, never a hidden fallback; every
   cloud provider is a visibly-marked **"watched object."** Provider-generic
   (Anthropic / OpenAI-compatible / Gemini — any vendor).
3. **Never call the council "MoE."** It is a **council** (mixture-of-agents).
4. **§5c idiolect baseline is owner-only input** — §5b drift + §5c self-observation are
   **blocked on GitHub Issue #2**. Build nothing there until answered.
5. **API keys** encrypted at rest via Android Keystore (`security/KeystoreSecret.kt`);
   never logged; sent only to their own provider/host.
6. **Plan before code; small legible commits; honest uncertainty.** Never claim
   on-device behaviour works — the build env has no device/emulator.
7. **THE LAW:** state is shown by **material**, never said by language. No status words.
8. **Never invent measurement/scientific methodology or "the numbers"** — frameworks
   yes, but parameters (idiolect baseline, costs/probabilities, prices) are user input.

---

## 3. Target device, build, distribution

- Target: high-end **arm64-v8a** Android phone (RedMagic 11 Pro; Snapdragon 8 Elite,
  24 GB unified RAM). `minSdk 31`, `targetSdk/compileSdk 36`, single ABI `arm64-v8a`.
- Stack: **Kotlin + Jetpack Compose**, Room, **manual DI** (`di/AppContainer.kt`, no Hilt),
  llama.cpp + stable-diffusion.cpp via JNI/CMake (NDK `28.2.13676358`, CMake `3.31.6`).
- JDK 17 (foojay resolver auto-provisions). `git submodule update --init --recursive`.
- **Two product flavors** (`dist` dimension):
  - **`full`** (default; sideload/`apk-dist`): full catalog, all §7 tiers, in-app APK
    install. appId `dev.aarso.full`.
  - **`play`** (Google Play): policy-safe catalog, no overlay/screen-capture, output
    flagging, no `REQUEST_INSTALL_PACKAGES`. appId `dev.aarso`.
- Commands: `./gradlew :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :hyle:test`
  (both flavors must stay green); `:app:assembleFullDebug` (native, heavy);
  `:app:bundlePlayRelease` (AAB).
- **APK delivery:** orphan branch **`apk-dist`** holds `aarso-sd.apk` (+ `hyle-probe.apk`),
  force-pushed. Install URL: `https://github.com/mbaliga/mobile-llm/raw/apk-dist/aarso-sd.apk`
  (private repo).
- **Environment honesty:** the build container compiles everything but has **no
  device/emulator**. All runtime behaviour (inference quality, image output, overlay/
  OCR, network round-trips, performance) is **owner-verified on the phone**.

---

## 4. Architecture spine (don't violate)

- **One append-only, git-like message tree** (`domain/tree/`, `data/MessageTreeRepository`).
  Branch / restore / model-switch / council are *operations over this one tree*.
- **`InferenceEngine` plumbs per-token logprobs/entropy from token one** — the reason
  llama.cpp over Ollama. Don't drop that surface. Cloud engines have no logprobs (honest
  "dark" state).
- Local generation uses the **model's own chat template** (GGUF metadata) + repeat
  penalty 1.1. JNI crosses tokens as UTF-8 `byte[]` (emoji-safe).
- KV-cache snapshots via `llama_state_save_file/load_file`.
- **Council** default agents Proposer/Skeptic/Synthesizer + a model-diversity mode.
- **Loops** = the council loop made a product; a loop **definition** serialises to
  **BPMN 2.0** (`domain/bpmn/`), a **run** is the message tree; both sync to the user's
  Git host.
- **Cost** is one language across the loop's internal budget (`council/Cost`,
  `Escalation`) and real-world decisions (`domain/cost/`).
- **Manual DI**: everything is wired top-to-bottom in `di/AppContainer.kt`.

---

## 5. Repo map

```
app/  (Kotlin + Compose, manual DI)
  src/main/java/dev/aarso/
    domain/        pure JVM-tested logic:
      tree/ template/ catalog/ council/ prompt/ instrument/ model/ cloud/
      image/ codelens/ disclosure/ git/ builds/ bpmn/ material/
      mirror/       MirrorLens/InertMirrorLens/MirrorSeam — inert lens seam (Issue #2) ← Sprint 0
      remote/       RemoteHost, Identity, KnownHosts/Trust, SessionMachine, RemoteSessionDriver,
                    Sftp, RemoteTransport; term/ (ScreenBuffer, VtParser, PtyChannel) ← Sprint 1-2
      device/       DeployTarget(Fqbn/SerialPort), recipe/DeviceRecipes, ArduinoCli ← Sprint 3
      loop/         Loop, LoopLifecycle, LoopCatalog, RunLog, Distiller, LoopConfig(+Bpmn),
                    GraphRunner, GraphRunLog, GraphEdit/Bricks                       ← Sprint 4
      diff/         LineDiff, ChangeSet, ReviewSession/ChangeSetReview          ← Sprint 5-6
      ide/          ProjectScaffold, ScaffoldPublishApi, RepoWorkLoop      ← agentic IDE + Sprint 5
      pm/           IssueBoard, IssueBoardApi, BoardSummary           ← project mgmt
      launch/       StoreListing                                       ← launch gen
      cost/         DecisionCost, ProviderUsage, CardDecision, UsageAccumulator,
                    PricingBook                                         ← Cost epic + P1/P2
      net/          OperationQueue/Backoff — durable retry queue        ← P5
      sync/         TreeArchive, TreeBackup, LoopSync (.bpmn → host)     ← tree/loop sovereignty + P6
    data/          Room, stores, downloader, Git transport/repos, ApkInstaller,
                   IssueBoardRepo, ScaffoldPublishRepo, LoopStore, BuildsRepo
    inference/     InferenceEngine; LlamaCppEngine (JNI), Echo, cloud/, image/, EngineGenerator
    embedding/     §5c cold-start logging; PlaceholderEmbedder (NOT a real model yet)
    service/       GenerationService (FGS), Overlay/ScreenCapture (full), VoiceInteraction (§7)
    security/      KeystoreSecret (AES-GCM)
    ui/            spatial/ (SpatialRoot), rooms/ (Chats/Models/Settings/Tree),
                   ChatScreen, loops/LoopRoom, develop/DevelopRoom, codelens/, theme/, aeon/(=hyle)
    di/AppContainer.kt
  src/full/ , src/play/   flavor-specific (ApkInstaller, ModelCatalog, InvocationFeatures)
  src/main/cpp/  llama_jni.cpp + CMake + llama.cpp submodule  → libaarso_llama.so
sdengine/   separate module: stable-diffusion.cpp submodule + sd_jni.cpp → libaarso_sd.so
hyle/       design-system module (Hyle.kt)        hyle-probe/  AGSL feel-test probes
.github/workflows/ci.yml   JVM tests (both flavors + :hyle), flake-retry, native build off
docs/  design/ (per-feature) · play/ (store compliance) · brand/ (logo) · status.md · HANDOFF.md
```

Counts: **175** main Kotlin files, **70** test files, **395** `@Test` methods.

---

## 6. Feature inventory (status + files)

Legend: ✅ built & CI-green · 🟡 partial · 🔌 headless engine, UI/runtime pending ·
📱 device-verified-only · ⛔ blocked.

### 6.1 Core engine
- ✅ Message tree + branch/restore — `domain/tree/`, `data/MessageTreeRepository`.
- ✅ Inference boundary w/ logprobs — `inference/InferenceEngine`, `EchoInferenceEngine`.
- 📱 Native llama.cpp — `inference/LlamaCppEngine`, `src/main/cpp/llama_jni.cpp` (builds; runtime owner-verified).
- ✅ Cloud SSE engines — `inference/cloud/{Anthropic,OpenAiCompat,Gemini}Engine` (+ `CloudEngine`).
- ✅ Council / loop engine — `domain/council/{Workflow(WorkflowRunner),Agent,Escalation,CostEstimator,PatternLibrary,EscalationBpmn}`.
- ✅ KV-cache snapshots, model switching/registry — `data/KvCacheStore`, `inference/ModelRegistry`.
- ✅ Cold-start embedding pipeline — `embedding/` (🟡 `PlaceholderEmbedder`, real model pending).
- ✅ Instruments (entropy→confidence, prompt linter) — `domain/instrument/`, `domain/prompt/`.

### 6.2 Loops — the visual editor  ✅ (this session)
- `ui/loops/LoopRoom.kt` — dot-grid canvas, draggable nodes, refine/approve edges,
  **tap-a-task to edit** name + system prompt, per-role model pickers, objective,
  iterations, **Run** (drives `WorkflowRunner` via `EngineGenerator`; runs on Echo dev
  models in debug), iteration log, **Save/Load named loops**.
- `domain/loop/LoopConfig` + `LoopConfigBpmn` — LoopConfig ⇄ BpmnGraph ⇄ **BPMN 2.0 XML**
  (`BpmnArchive`); JVM round-trip tested.
- `data/LoopStore` — persists loops (BPMN + Unused/Running/Retired lifecycle); wired in `AppContainer`.
- 🔌 **Next:** arbitrary graphs (Bricks palette, free-form edges) + a **graph runner**
  (execute any BpmnGraph, not just proposer↔critic); persist runs to the tree; Git sync of `.bpmn`; NL authoring; lifecycle UI (trigger/retire).

### 6.3 Cost epic  ✅ (this session) — `docs/design/cost.md`
- `domain/cost/DecisionCost` — `CostVector(money/minutes/tokens)`, `RiskedOutcome` (error
  EV = chance×impact), `Decision` (onSuccess, perAttempt that *recurs*, successChance,
  risks, adviceCost), `forecast` → **expected & worst band**, expected attempts
  (geometric), `Valuation` rollup. `LlmAdvice` prices token I/O; `Cost.toCostVector()`
  bridges the loop's internal cost.
- `domain/cost/ProviderUsage` — parse **provider-reported** usage (Anthropic/OpenAI/
  Gemini) → `UsageReport` → advice cost; `UsagePricing` (user-set; not a baked price table).
- `domain/cost/CardDecision` — board card → loop objective, loop `Cost` → a decision forecast.
- UI: a **Cost facet** in the Develop room (pre-loaded with the BlackBerry-keyboard case).
- 🔌 **Next:** capture **live** SSE usage from the engines into `adviceCost` (runtime);
  per-turn "this advice cost X, risks Y" (UX decision **G**); decisions as tree nodes (G3).

### 6.4 Project management  ✅ (this session) — `docs/design/project-management.md`
- `domain/pm/IssueBoard` (BoardCard, BoardColumn, `status:*` label/closed=Done inference,
  `Boards.group/labelsForMove/summary`, `BoardSummary`), `IssueBoardApi` (list/create/move
  + parse, skips PRs, tolerant of Gitea string labels), `data/IssueBoardRepo` (via GitTransport).
- UI: **Board facet** (Develop room) — summary line, create card, move across columns.
- 🔌 **Next:** test-dashboard facet (reuse `BuildsApi.parseChecks`), card↔loop run UI.

### 6.5 Agentic IDE  ✅ (this session) — `docs/design/coding-assistant.md`, `app-distribution.md`
- `domain/ide/ProjectScaffold` — idea/spec → a minimal, CI-buildable Compose project
  (incl. a build workflow emitting an APK artifact).
- `domain/ide/ScaffoldPublishApi` + `data/ScaffoldPublishRepo` — **create a repo + push
  the files** (idea→repo→CI builds APK→install loop). **Gated** UI (real account side effect).
- UI: **New project facet** (preview file tree + content; gated Create-repo-&-push).

### 6.6 Launch generation  ✅ (this session)
- `domain/launch/StoreListing` — Play title/short/full/what's-new within the real char
  limits; deterministic, rebrand-proof.
- `docs/brand/` — clean-room "mirror" logo (`aarso-logo.svg`), adaptive-icon foreground,
  palette, raster-export notes.
- UI: **Launch facet** — generated listing w/ live char-limit meters + Play checklist.
- 📱 **Next:** feature graphic 1024×500, owner-captured screenshots; provider-generated copy.

### 6.7 Your repos: Git layer / CodeLens / Builds
- ✅ Git host layer — `domain/git/{GitHost,GitContentsApi,GitLookupApi}`, `data/{GitHostStore
  (token in Keystore),GitTransport,GitBackup,GitBrowse,GitEdit}`; token-first connect wizard
  (Settings → Git & coding). 📱 network owner-verified.
- ✅ CodeLens — `domain/codelens/CodeLens`, `ui/codelens/CodeLensScreen` (read code in plain English).
- ✅ Builds/CI — `domain/builds/{Build,BuildsApi,CiTrigger}`, `data/BuildsRepo`; **Builds
  facet** (Develop room) lists CI APKs, tests-as-a-badge, sideload Install.
- ✅ APK install — `data/ApkInstaller` (full = download+FileProvider+install intent; play = stub).
- ✅ Tree-sovereignty backup — `domain/sync/{TreeArchive,TreeBackup}`, `data/GitBackup`.

### 6.8 Image generation
- ✅ On-device SD — `sdengine/`, `inference/image/SdImageEngine` (DreamShaper8/SD1.5/SDXL-Turbo). 📱 runtime owner-verified.
- ✅ Cloud image — `inference/image/CloudImageEngines` (gpt-image-1, Stability SD3, Imagen) — watched.
- ✅ Image turns live in the tree; browsing = filter in Chats.

### 6.9 §7 invocation layer (full flavor)
- ✅ Share-sheet + PROCESS_TEXT → `data/SharedIntake`; assist gesture + recognition trio
  (`service/AarsoInteraction*`, `AarsoRecognitionService` is a deliberate STT stub);
  floating bubble (`service/OverlayService`); screen-capture + ML Kit OCR
  (`service/ScreenCaptureService`). 📱 all owner-verified.

### 6.10 Cross-cutting
- ✅ Progressive disclosure — `domain/disclosure/` (CORE/STUDIO/POWER; mandatory surfaces unhideable).
- ✅ Theme/Hyle — `ui/theme/`, `ui/aeon/Aeon.kt` (pkg `ui.hyle`): runtime palette, accent,
  texture, WCAG AA enforced. (Design system inbound — treat current as wireframe.)
- ✅ Keystore encryption — `security/KeystoreSecret`.
- ✅ Spatial UI shell — `ui/spatial/SpatialRoot` (Chat home; Chats left, Settings right,
  Models below, Tree on z-axis). Develop room currently lives under Settings → Develop
  (interim; UX decision **B1** = move to a bottom dev room).
- ✅ Develop room — `ui/develop/DevelopRoom.kt` (wireframe): Board · New project · Launch ·
  Builds · Cost. Home **"Connect your repos"** card on the chat screen.

---

## 7. What's done (summary)
Foundation (CI + docs + PR hygiene) and **five engine pillars** — core spine, Loops
(with a real visual editor), Cost, PM, IDE (incl. publish), Launch — plus Git/CodeLens/
Builds, image gen, §7, disclosure, theme. All headless logic is **JVM-tested and
CI-green** (287 tests). Touchable wireframe UI exists for every new pillar.

---

## 8. What needs to be done

### 8.1 Engine / headless follow-ons (no device, no design system needed)
- **Graph runner** for arbitrary BpmnGraphs (Loops Phase 2) + Bricks palette/free edges.
- **Live SSE usage capture** wired from `CloudEngine` into `Cost.adviceCost` (parsers exist).
- **PM test-dashboard** facet + card↔loop run; persist loop **runs** into the message tree.
- **Provider pricing** config surface (so `UsagePricing` reflects real per-model prices).
- **Git sync** of loop `.bpmn` files + tree archive to the host (backup brick exists).
- Real **local embedder** to replace `PlaceholderEmbedder` (pipeline already live).

### 8.2 Runtime / device-gated (owner-verified on the phone)
- Native llama.cpp load/stream/logprobs; SD image output; overlay/assist/OCR/STT flows.
- **Acceleration path** (§10.6): NPU/QNN vs Adreno/Vulkan vs CPU — **benchmark on device**, never assume.
- Exercise network journeys against a real host (board move/create, scaffold publish,
  build install, Git backup, cloud chat/usage).
- Performance/coherence of loops with real models.

### 8.3 UX / design-system (awaits the owner's read-only design system)
- Restyle all wireframe surfaces (Develop room, Cost/Board/Launch/IDE facets, connect card).
- **B1**: relocate Develop to the bottom **dev room** (needs the spatial-map reorg:
  Models folds into Settings; bottom axis opens Develop) — gesture code, device-verified.
- Loop room: free-form canvas editing UI; lifecycle UI; material (ferrofluid) port to atoms.
- On-device **voice/STT** for NL authoring (`AarsoRecognitionService` stub).

### 8.4 Owner-only / blocked
- **§5b drift + §5c self-observation** — ⛔ blocked on **Issue #2** (idiolect baseline +
  metric methodology). Do not build.
- Play: **flag-report email** (`src/play/.../InvocationFeatures.kt`), privacy-policy hosting,
  Play Console setup, content rating, data-safety, signing/AAB, screenshots
  (`docs/play/` has drafts).

---

## 9. Open UX decisions (`docs/design/open-ux-decisions.md`)
All answered by owner 2026-06-19 (★ = chosen): **A1** `status:*` labels · **B1** bottom
dev-room facets (built as content; relocation pending) · **C1** "New project" card ·
**D1** home connect card · **E1** "Prepare for launch" panel · **F1** single Install
button · **G** (Cost placement) — **open**: G1 standalone+inline / G2 standalone only /
G3 decisions as tree nodes.

---

## 10. Issue #2 (blocking, owner-only)
Owner must define: the **idiolect baseline** (§10.3 — your own voice that drift is
measured *away from*); what counts as "drift toward LLM register" (lexicon/tells,
weighting); homogenization-to-the-mean vs slop; the §5b drift/creep metric; and confirm
§5c is retrospective-only (no live HUD). Also §10.4 council aggregator default, §10.5
cloud fan-out allow/refuse, §10.6 acceleration path (by benchmark). Until answered,
`PlaceholderEmbedder` stands in and §5b/§5c stay unbuilt.

---

## 11. Journeys to test on device
1. **Build & run a loop** (debug build, no downloads — Echo models run): Settings →
   Global → Open Loops → objective → tap nodes to edit prompts → pick models → iterations
   → **Run** → read iteration log.
2. **Save / Load loops**: Save (→ BPMN) → **Loops** → Load / Duplicate / Delete.
3. **Develop room** (Settings → Global → Develop): Board (with a Git host) · New project
   (generate → gated Create-repo-&-push) · Launch (listing + meters + checklist) · Builds
   (install) · Cost (forecast).
4. **Connect repos**: home card → token-first wizard.
5. (Existing) chat, branch/restore, tree zoom, model download, council, image mode, §7 summon.

Release builds need a downloaded GGUF or a cloud provider to run loops.

---

## 12. CI / branches / PR
- **CI** `.github/workflows/ci.yml`: JDK 17 + Android SDK + pinned NDK/CMake → unit tests
  (both flavors + `:hyle`) **with a 3× retry** (rides out transient Maven Central 403s);
  native `assembleFullDebug` is **disabled** (`if: false`) because it OOM/cancels the
  hosted runner — native is owner-verified on device, and the unit-test task already
  compiles all Kotlin. `workflow_dispatch` enabled for manual re-runs.
- **Branches:** `main` (source of truth) · `claude/repo-state-assessment-ogkh1o` (this
  work → PR #4 draft) · `apk-dist` (artifacts) · old `claude/*` merged/closed.
- **PRs:** #4 open (draft, this session) · #3 merged · #1 closed (superseded).
- **Lessons (CI):** runner has SDK but `sdkmanager` not on PATH (use `setup-android`);
  Maven Central intermittently 403s (retry); the native build is too heavy for hosted runners.

---

## 13. How to continue (new session)
1. Read `CLAUDE.md` (rules) → this doc → `docs/status.md` → `docs/design/README.md`.
2. Pick from §8.1 (engine follow-ons need neither device nor design system — safest).
3. Keep commits small + legible; both flavors green; honest about device-gated claims.
4. For UI, wait for the design system or build wireframe (boxy, text+rectangles) and let
   CI compile-check it; never report on-device behaviour as confirmed.
5. Never touch §5b/§5c (Issue #2). Never call the council "MoE." Keys stay in the Keystore.
