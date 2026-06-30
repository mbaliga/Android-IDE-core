# Aarso — full state snapshot (2026-06-19)

> **Purpose.** A single honest document: what exists, what's missing, what needs
> fixing, and where the ambition points. Updated at the end of each major session.
> **PR #3 is now merged — `main` is the source of truth** and carries all the work
> below. PR #1 (the old Phase 0 branch) is superseded by `main` and closed.
> Last update: 2026-06-21 (PRs #18–#31 merged → main; the headless engines are now
> **wired into device-testable UI** + a new spatial map; APK v0.5.x on `apk-dist`.
> See "Made real on device" below). Earlier: 2026-06-21 (PR #4; visual editor; reframe
> to a post-desktop computing environment + Workbench/Aarso naming split).

---

## Made real on device (2026-06-21, PRs #18–#31)

The Phase-1/2/3 headless engines are now wired into UI you can test on the phone, and the
spatial shell was reworked to the planned map. **Gestures/network/inference are owner-verified**
(no device/emulator/SSH/cloud in CI); everything below is compile + unit-test green, both flavors.

**Remote spine made real.** sshj transport (`data/remote/SshjTransport`) behind the pure
`RemoteTransport` seam + `RemoteHostStore` (Keystore-encrypted keys/trust pins); a wireframe
**Remote** screen (connect → trust prompt with real fingerprint → exec → raw output) and an
**interactive terminal** (PTY shell → `VtParser` → `ScreenBuffer`). Settings → Remote.

**Loops made real.** Each run persists into the message tree (`RunLog`); `.bpmn` **Git sync**
(push/pull) wired via `LoopSyncRepo`; the canvas dot-grid now **blooms only around the bricks**.

**Cost (G1).** `PricingStore` + per-turn inline cost on cloud replies + a cloud-pricing editor
(Dev → Cost). **Durable queue** (`OperationWorker` + `OperationQueueStore`) — loop-push survives
the subway (parks + retries).

**New spatial map.** Left = Chats · Right = Settings (now hosts **Models**) · **Top = Project
planning** (kanban + list + notes, `ProjectRoom`) · **Bottom = Dev tools** · **pinch-out = Loops**
· pinch-in = Tree. Bidirectional `v`/`z` axes in `SpatialController`.

**Onboarding/legibility.** Reusable **guided walk-throughs** (`domain/guide` + `HelpIcon`) on
setups. **Free-tier guide** — `domain/cost/FreeTier` + bundled `assets/free_tiers.json` (11
providers, June-2026, each with `sourceUrl`) + a monthly freshness **pipeline**
(`scripts/update_free_tiers.py` + Action) + **per-provider free-tier usage** tracking
(`FreeTierUsageStore`, matched to limits via `FreeTierMatch`). Settings → Cloud free tiers.

**Pending owner input:** the design-system pass ("ditch Aeon → generic"). The handoff notes a
read-only design system is incoming and UI is "restyled, not rearchitected" — so the scope of a
generic restyle now vs. waiting for that system is an owner call. Also deferred: the Models
cloud/local **filter** (room is reachable; filter is the small follow-on).

---

## Reframe + naming split (2026-06-21)

North star widened: a **post-desktop, touch-native computing environment** that makes the
phone a **sovereign primary device**. Naming split — **Workbench** (placeholder) = the host
app; **Aarso** (*mirror*) = the within-axis self-reflection **lens** only, now a bounded inert
`domain/mirror/` seam (`MirrorLens`/`InertMirrorLens`/`MirrorSeam`, JVM-tested) carrying **no
§5b/§5c logic** (Issue #2). Full sequenced plan in `docs/build-plan.md` (§F run order). Package
rename deferred to "Sprint R".

**Phases 0–2 complete & merged (2026-06-21).** Headless, JVM-tested, both flavors green:
- **Sprint 0** — framing + mirror seam (`domain/mirror/`).
- **Sprint 1** — remote-exec/SSH spine (`domain/remote/`): host/identity, trust model
  (Vetted/Unknown/Changed), session state machine, exec streaming, SFTP, transport seam,
  session driver.
- **Sprint 2** — terminal model (`domain/remote/term/`): `ScreenBuffer` + `VtParser` + `PtyChannel`.
- **Sprint 3** — hardware targets (`domain/device/`): RPi/Arduino-v1/ESP-OTA recipes over the
  SSH spine + arduino-cli output parser.
- **Sprint 4** — graph runner (`domain/loop/`): runs an arbitrary `BpmnGraph`, run→tree mapping,
  free-form `GraphEdit` + `Bricks`.
- **Sprint 5** — repo work loop (`domain/diff/ChangeSet`, `domain/ide/RepoWorkLoop`):
  card→objective→read→propose→**approve**→commit.
- **Sprint 6** — diff-review (`domain/diff/ReviewSession`): per-hunk approve/reject + partial apply.

Remaining autonomous work: **Phase 3 (P1–P6)** — live SSE usage→Cost, provider pricing surface,
PM test-dashboard, real local embedder, connectivity/queue, Git sync of loops+tree. Then the
§E owner-gated ceiling (device verification, design-system restyle, Issue #2). Data-layer
wiring (SSH transport over sshj/jsch, run persistence, recipe execution) is the 📱 follow-on.

---

## Expanded direction (2026-06-19) — agentic Android IDE + PM + launch

Owner reframed the Play-Store ambition: Aarso as a place to **conceive, build, test,
and launch an Android app end-to-end from the phone** ("TikTok for Android app dev"),
with project management and launch collateral in-app. Sequencing: **foundation first,
then the three pillars equally.** Headless, CI-verified bricks landed (UI parked in
`docs/design/open-ux-decisions.md` for a batched owner pick):

- **Project management** — `domain/pm/IssueBoard*` + tests + `docs/design/project-management.md`.
  Kanban whose cards *are* your repo's issues (move = relabel/close on your host).
- **Agentic IDE** — `domain/ide/ProjectScaffold` + tests. Idea/spec → a minimal,
  CI-buildable Android project (incl. a build workflow that emits an APK artifact),
  closing the scaffold → `GitContentsApi.putFile` → CI → `BuildsApi` → install loop.
- **Launch generation** — `domain/launch/StoreListing` + tests (Play copy within the
  real char limits) and `docs/brand/` (clean-room "mirror" logo + adaptive-icon
  foreground + palette/export notes).

- **The Cost epic** — `domain/cost/DecisionCost` + tests + `docs/design/cost.md` +
  a wireframe Cost facet. Multi-dimensional, risk-adjusted decision cost: money/time/
  tokens, per-attempt transaction costs, the expected value of being wrong, and the
  cost of the advice itself (the model's price + the risk it's wrong). Bridges the
  loop's internal `Cost`/`Escalation` to real-world decisions.

- **IDE last mile (built):** `domain/ide/ScaffoldPublishApi` + `data/ScaffoldPublishRepo`
  (+ tests) create a repo on the user's host and push the scaffold — closing idea →
  repo → CI builds APK → install. Wired into `AppContainer`; the Develop "New project"
  facet has a **gated** Create-repo-&-push action (explicit confirm; real side effect).

Non-UX queue — now built (headless, JVM-tested):
- **Provider-reported usage → Cost** — `domain/cost/ProviderUsage` (Anthropic/OpenAI/
  Gemini usage parsers → `UsageReport` → advice cost). Live SSE capture is the runtime
  follow-on (owner-verified).
- **PM aggregation** — `Boards.summary` + `BoardSummary` (pending/in-flight/done) + a
  wireframe summary line in the Board facet.
- **Loop ↔ Board/Cost** — `domain/cost/CardDecision` (card → loop objective; loop `Cost`
  → a decision forecast with optional "loop was wrong" risk).
- **CI hardening (#5)** — mitigated by the gradle retry loop; gating `foojay` off on CI
  is deferred as risky (it would touch the toolchain provisioning local/web builds rely on).

**Visual editor (Loop room) — now a real editor.** `ui/loops/LoopRoom.kt` (Aeon/Hyle
styled): dot-grid canvas with draggable nodes, **tap a task to edit** its name + system
prompt, per-role model pickers, objective + iterations, **Run** (drives `WorkflowRunner`,
runnable with Echo dev models in debug), and **Save / Load** named loops persisted as
**BPMN 2.0** via `LoopStore` (`domain/loop/LoopConfig` + `LoopConfigBpmn`, JVM round-trip
tested; `LoopStore` wired into `AppContainer`). Journeys testable on device: author →
configure nodes → run → save → load → duplicate/delete. Arbitrary graphs + a graph
runner remain the next step.

Genuinely remaining: live SSE usage capture (runtime), and everything UX (design system)
or owner-only (§5b/§5c — Issue #2; native-accel benchmark; on-device verification).

---

## The vision (owner's words, distilled)

Aarso is not a chat client. It is a **physics engine for thought** — a place where:

- **Models are instruments**, not endpoints. You compose them into loops.
- **Sovereignty is material.** You see what runs where (on-device vs. watched-cloud),
  you own your history (tree to your Git host), you make the surface yours (Hyle theme).
- **The loop is the product.** The iterative objective → propose → critique → refine
  cycle — the thing we do *with* Claude — is the feature. It should be visual, editable,
  named, shareable, and yours. Not a settings dialog.
- **Your repos, your builds.** Aarso should be the place you develop Android apps: see
  CI status, browse code with the Lens, install builds, run loops over your codebase.
- **Legibility over power.** Non-technical users should be able to read the app's
  output, understand what it's doing, and feel in control — not impressed by capability
  they can't audit.

**One thesis sentence:** the interface does not mediate reality through a language it
controls; it presents material whose physics you read for yourself.

---

## What exists today (branch: `claude/festive-feynman-e76e8x`, PR #3)

### Core engine — fully built

| What | File(s) | Notes |
|---|---|---|
| Append-only message tree | `domain/tree/`, `data/MessageTreeRepository` | git-like; branch/restore are ops on the tree |
| Local GGUF inference | `inference/LlamaCppEngine`, `llama_jni.cpp` | per-token logprobs/entropy, chat template from GGUF |
| Echo dev engine | `inference/EchoEngine` | debug only; release shows no-model state |
| Cloud SSE streaming | `inference/cloud/{Anthropic,OpenAI,Gemini}Engine` | any vendor; watched badge |
| Council / multi-model | `domain/council/Agent`, `WorkflowRunner` | fan-out, personas, model-diversity modes |
| Loop engine (headless) | `domain/council/Workflow`, `Escalation`, `CostEstimator` | objective→propose→critique→refine; escalation gates; AGENT/human rungs |
| BPMN serialisation | `domain/bpmn/{BpmnGraph,BpmnArchive}` | loops as BPMN 2.0 XML with `<aarso:meta>`; DI bounds; round-trip tested |
| Progressive disclosure | `domain/disclosure/Disclosure` | CORE/STUDIO/POWER tiers; mandatory surfaces can't be hidden |
| SD image generation | `sdengine/`, `inference/image/SdImageEngine` | DreamShaper8 / SD1.5 / SDXL-Turbo |
| Cloud image generation | `inference/image/{GptImage1,StabilitySD3,ImagenEngine}` | watched |
| Downloads | `data/{ModelDownloader,DownloadCenter,DownloadResume,DownloadService}` | HTTP Range resume, FGS, notification |
| Tree backup (Git) | `data/GitBackup`, `domain/sync/TreeArchive` | push/pull; open tree format; conflict-free union |
| Git host layer | `domain/git/GitContentsApi`, `data/GitHostStore`, `data/GitTransport` | GitHub + Gitea/Forgejo; PAT in Keystore |
| Builds API | `domain/builds/{BuildsApi,Build}`, `data/BuildsRepo` | release assets + dist-branch APKs; CI checks badge |
| CI trigger | `domain/builds/CiTrigger` | listWorkflows, dispatch, listRuns; GitHub + Gitea Actions |
| APK sideload | `data/ApkInstaller` (full flavor) | download + FileProvider + install intent; play stub |
| CodeLens | `domain/codelens/CodeLens`, `ui/codelens/CodeLensScreen` | draggable glass; blur/focus animation; never prints "Reading…" |
| Hyle design system | `ui/aeon/Aeon.kt` (package `ui.hyle`), `ui/theme/` | HyleButton, HyleChip, HyleField, etc. |
| Theme engine | `ui/theme/{HyleColors,AccentRamp,ThemePicker,Texture}` | Light/Dark/System; free accent; grain texture; WCAG AA enforced |
| Keystore encryption | `security/KeystoreSecret` | AES-GCM; keys never logged |
| Screen capture/OCR | `service/ScreenCaptureService`, ML Kit | full flavor only |
| Bubble overlay | `service/OverlayService` | full flavor only |
| §7 invocation | `service/VoiceInteraction*`, `data/SharedIntake` | assist gesture, share sheet, PROCESS_TEXT |
| Hyle Probe | `:hyle-probe` module | 5 feel-test tabs: radiant glow, glass+sand, ferrofluid bead, Lens, Hyle atoms |

### UI — what the user can actually touch

| Screen / room | State | Notes |
|---|---|---|
| Chat (home) | ✅ solid | markdown, entropy colouring, council chips, model picker, stop generation, tree nav |
| Chats room (left edge) | ✅ solid | All / Image / Bookmarked tabs; round + FAB; bookmark toggle |
| Settings room (right edge) | ✅ but rough | nested Global / Text / Image; Appearance / Summon / Council / **Loops preview** / Instruments / **Git & coding** / **Builds** / About |
| Models room (bottom edge) | ✅ solid | coverflow Chat / Image / BYO; download lifecycle; monogram tiles |
| Tree room (pinch-in z-axis) | ✅ solid | conversation map; scoped outline |
| Onboarding | ✅ exists | 2-screen stance + chat setup card with model recommendation |
| File browser + Lens | ✅ works | files open in CodeLensScreen; CI panel per host |
| Loop preview (dialog) | ⚠️ barely visible | buried TextButton → dialog. Shows the engine works; not a real UI |
| Builds list (Settings) | ⚠️ minimal | listed in Settings→Global→Builds; Install button; no dedicated view |

---

## What's broken / missing / the honest gaps

### 1. The graph editor — Phase 1 canvas landed (2026-06-19) ✅

`BpmnGraph`, `BpmnArchive`, and `WorkflowRunner` were already JVM-tested.
**`ui/loops/LoopRoom.kt` (Phase 1) is now in PR #3:**
- Dot-grid canvas with draggable BPMN node cards (Start → Proposer → Critic → End)
- Arrow connectors with arrowheads; accent colour on loop-back/approve edges
- Model pickers + objective + iterations in controls panel below the canvas
- Run button → real WorkflowRunner inference → inline iteration log (no status words)

**What's still missing (Phase 2):**
- Bricks palette for free-form node add/remove
- Free-form edge drawing (connect any nodes)
- Custom system prompts per node
- Persist loop definitions as BPMN files to Git host (`LoopStore`)
- Loop room as a spatial room (currently: Settings → Global → Loops)
- Loop lifecycle (Unused/Running/Retired), NL authoring

### 2. GitHub connection UX — token-first wizard landed (2026-06-19) ✅

**Old flow** (the "punishment"): 9 text fields — host, display name, instance URL,
owner/org, repo, branch, git name, git email, PAT. A CI/CD YAML config experience.

**New flow** (PR #3): 2-step wizard powered by `domain/git/GitLookupApi.kt`:
- Step 1: Host + PAT (+ instance URL for Gitea) → Connect; resolves identity + repos
- Step 2: Searchable repo list, tap to pick → Save (all fields inferred)
- Email override only when GitHub email is private; linear progress while connecting

**Still missing:** surface this from the home screen for new users who have no
Git host connected (the "Connect your repos" onboarding card).

**What it should be:**
1. A **single prompt**: "Paste your GitHub token" (with a link to generate one)
2. Auto-resolve owner/orgs from the API
3. Repo picker (list + search, not a text field)
4. Done in 2 taps after pasting the token

This is a two-part problem: (a) the form has too many fields, (b) it's buried in
Settings. It should be surfaced prominently — a "Connect your repos" card on the home
screen for new users.

### 3. Loops are invisible to the user

The loop engine is real but the product surface is one TextButton. The spatial map
says "Loops" but there's no Loop room. The user has no way to discover that Aarso
can do iterative multi-model refinement.

### 4. The dev room (Branches / Tests / Builds) doesn't exist

`BuildsApi` + `CiTrigger` + `BuildsRepo` are all headless. The CI panel exists in
Settings (buried). There is no bottom dev room with a Builds facet, no check-runs
badge, no branch picker. All the domain work is done; only the room is missing.

### 5. Buttons are not ferrofluid

The Hyle atoms probe shows the material direction (ferrofluid bead AGSL shader).
The actual `HyleButton` in the app is a plain violet rounded rect — no wet specular,
no fresnel rim, no surface-tension press. The candy gloss (gradient + sheen) is in
the probe but hasn't been ported to the main component layer. This is the
rendering-handoff.md task — a side Claude chat can refine the AGSL and hand it back.

### 6. Voice / STT not started

`AarsoRecognitionService` is a deliberate stub. On-device STT is the eventual path
(no cloud telemetry). Deferred until the loop builder and Git UX are solid.

### 7. §5b/§5c blocked

Self-observation engine + drift metric are waiting on owner input (GitHub Issue #2).
`PlaceholderEmbedder` is the stand-in.

---

## What's planned (next, in order)

### P1 — DONE (2026-06-19)

1. ✅ **Loop room Phase 1 canvas** — `ui/loops/LoopRoom.kt` in PR #3.
2. ✅ **GitHub connect UX** — token-first wizard + `GitLookupApi` in PR #3.

### P1 remaining — surface GitHub connect for new users

Surface a "Connect your repos" card on the home/chat screen for users who have no
Git host connected yet. Currently the wizard is reachable only via Settings → Global
→ Git & coding.

### P2 — Port ferrofluid material to buttons

Use the rendering-handoff.md + a side Claude chat to refine the AGSL button material,
then port it back to `HyleButton` / `HyleChip` / `HyleNavChip`. Owner-verified on
device.

### P3 — Dev room (bottom)

Wire `BuildsRepo` + `CiTrigger` into a bottom spatial room:
- Builds facet: list of APKs, CI badge, one-tap install
- CI facet: workflow runs, trigger button
- The existing Settings panels become the edit surface; the room becomes the view

### P4 — NL loop authoring

A text box: "I want to propose a fix for X, critique it for security, then synthesise
a final version." → auto-generates a BpmnGraph. Port to voice when STT lands.

### P5 — §5b / §5c (blocked on Issue #2)

Owner's baseline idiolect → drift metric → self-observation. Do not start.

---

## Non-negotiable rules (do not relax)

1. **No telemetry, analytics, or phoning home. Ever.**
2. On-device is the default. Every cloud touch is a **watched** object.
3. Never call the council "MoE / Mixture of Experts." It is a **council**.
4. §5b/§5c — do not build until Issue #2 is answered.
5. Keys live in the Android Keystore; never logged; sent only to their own host.
6. Plan before code; small legible commits; honest uncertainty.
7. **THE LAW:** state is shown by material, never said by language. No status words.

---

## Binding open questions (need owner answer before building)

| # | Question | Blocks |
|---|---|---|
| Issue #2 | §5c idiolect baseline — owner's own writing samples | §5b drift, §5c self-observation |
| — | GitHub connect: should the repo picker come before or after the PAT entry? | UX redesign (P1) |
| — | Loop room location: spatial slot is "pinch to zoom = Loops" — confirm or reassign? | Loop room (P1) |
| — | Flag-report email for Play policy compliance (`InvocationFeatures.kt`) | Play publication |

---

## Branch / PR state

| Branch | State |
|---|---|
| `main` | **Source of truth** — PR #3 merged in; all work below lives here |
| `claude/festive-feynman-e76e8x` | Merged via PR #3; historical |
| `claude/workbench-build-handoff-KvOr1` | PR #1 (Phase 0) — superseded by `main`, closed |
| `apk-dist` | Current sideload APK (versionCode 3, 0.3.0) |

CI: `.github/workflows/ci.yml` runs the JVM unit tests (both flavors) + a
non-gating native `assembleFullDebug` on every push/PR. On-device behaviour
stays owner-verified (no device in CI).

APK: `https://github.com/mbaliga/mobile-llm/raw/apk-dist/aarso-sd.apk`
(private repo — needs a logged-in GitHub session)
