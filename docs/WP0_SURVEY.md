# WP-0 Survey — Fonebrew Handoff-Pack Build-Out

**What this is:** WP-0 of the Fonebrew constellation handoff-pack build-out. Written
2026-08-07. This is a **read-only survey** — four independent read-only agents each inspected
one repo in the constellation (`android-ide-core`, `Aarso`, `Android-IDE-Studio`,
`Hyle-Design-System`) and reported what actually exists on disk, in git history, and (where
checkable) on GitHub. This document synthesizes those four raw reports into one artifact-by-
artifact inventory against the handoff pack's own `04`/`05`/`09` architecture-contract docs.

**Governing principle: trust the code over the pack, and trust the newest dated artifact over
an older one that describes itself as authoritative.** All four source reports resolved doc
staleness by git last-modified timestamp, not by a doc's own self-description, and this document
carries that resolution forward rather than re-litigating it. Nothing here was invented beyond
what the four reports state; where a report didn't cover something, that gap is stated as a gap,
not filled in.

**No code changes were made in WP-0** (restated at the end, per instruction, but true throughout
— this document is pure inventory).

---

## 1. Artifact-group inventory

For each target artifact group named in the handoff pack's `04`/`05`/`09` architecture-contracts
docs: create-new / extend-existing / already-exists, with exact evidence from the four reports.

### (a) Common envelope/error/receipt library — **CREATE-NEW, fully greenfield**

No repo has anything resembling a shared `ContractEnvelope<T>` / `ErrorEnvelope` / `ImportReceipt`
/ `ArtifactRef` / `CapabilityManifest` / `MigrationPlan` type.

- **core:** `grep` for `Envelope|Receipt|IdempotencyKey|ArtifactRef|MigrationPlan|ErrorTaxonomy`
  across `app/` and `core-engine/` → zero matches. Also zero for `ContractEnvelope`,
  `CapabilityManifest`, `WorkspaceKernel`, `DocumentBuffer`, `CapabilityAuthority`,
  `ExecutionContract`. `core-engine/src/main/java/dev/aarso/domain/` has 40 subpackages and none
  is `contracts`/`envelope`/`receipt`.
- **aarso:** grep for `envelope|receipt|ArtifactRef|CapabilityManifest|MigrationPlan|
  IdempotencyKey|schemaVersion` across the whole (5-file) repo → zero matches.
- **studio:** grep for `ContractEnvelope`, `ErrorEnvelope`, `ImportReceipt`, `ArtifactRef`,
  `CapabilityManifest`, `MigrationPlan`, `WorkspaceKernel`, `DocumentBuffer`, `RecoverySnapshot`
  → zero hits. Nearest thing that exists is ad hoc, feature-local, un-generalized result/error
  types: `sealed interface ActivateResult { Success; data class Error(val message: String) }` in
  `app/src/full/java/dev/aarso/studio/entitlement/StudioEntitlement.kt:9-12`, and a small sealed
  `LsOutcome` in `app/src/full/java/dev/aarso/studio/entitlement/LsTransport.kt`. Neither carries
  `schemaVersion`/`objectId`/`idempotencyKey`/`integrity`.
- **hyle:** exhaustive grep across `.kt`/`.ts`/`.md`/`.json` → zero relevant matches (one
  incidental unrelated hit: an animation-amplitude "envelope" comment in `FerrofluidProbe.kt:104`).
  Nearest conceptual analog: `crash-recovery/src/main/java/dev/aarso/crashrecovery/CrashReport.kt`
  — structured fields + derived `headline` (lines 22-34), and a versioned `encode()`/`decode()`
  format (`MAGIC = "CRASHv2"`, line 90, with legacy-format fallback at lines 191-223). This is a
  crash-report-specific model with a narrow serialization/migration pattern, not a reusable
  generic envelope type.

**Verdict: create-new.** No existing library to extend anywhere in the constellation.

### (b) Workspace Kernel — **CREATE-NEW**

- **core:** explicit grep for `WorkspaceKernel` and `DocumentBuffer` → zero hits (part of the
  same pass as (a) above).
- **studio:** same term list grepped, zero hits.
- **aarso / hyle:** not named in either report's grep target lists; the aarso report's broader
  sweep ("no trace of ... Device Broker, Execution Contract, or any routing-engine material —
  none of the WP-1/WP-1L vocabulary appears in this repo at all") covers it by implication, and
  no directory or file named anything Workspace-Kernel-like appears in either repo's full file
  listing.

**Verdict: create-new.** Zero prior art under this name in any of the four repos.

### (c) Execution Contract + Authority engine — **CREATE-NEW**; one narrow precedent

- **core:** grep confirms zero matches for `ExecutionContract` and `ratified` anywhere in the
  repo (code or docs).
- **studio:** grep for `CapabilityManifest` (and the same term list as (a)) → zero hits. The
  nearest real precedent in the whole constellation is **Studio's entitlement gate**: a single
  boolean `isEntitled` check wired through `StudioEntryPoint.kt:36-55`
  (`installStudioEntryPoint`/`reinstallStudioSeams`), backed by `StudioEntitlement.kt` (96 lines,
  activate/validateNow/deactivate), `EntitlementRecordStore.kt` (53 lines, Keystore-backed),
  `LemonSqueezyTransport.kt` (80 lines, real OkHttp License-API calls). This is a narrow,
  single-purpose product gate, not a capability-ladder/authority engine.
- **aarso:** explicit finding — "No trace of `CapabilityManifest`, authority/grant/ladder
  concepts, `Device Broker`, `Execution Contract`, or any routing-engine material — none of the
  WP-1/WP-1L vocabulary appears in this repo at all" (confirmed by grep for
  `annex|private mechanism|proprietary|do not touch|routing`, which matched only two generic
  README lines unrelated to authority/routing).
- **hyle:** not covered; no hits in that report's grep passes either.

**Verdict: create-new.** If WP-1 wants a design precedent for "check a gate before allowing an
action," Studio's entitlement gate is the only real specimen in the constellation, but it is not
a capability/authority engine and shouldn't be mistaken for one.

### (d) Loop Engineering (GraphRunner adapter target) — **EXTEND-EXISTING**

Real, load-bearing runtime already exists in core and should be wrapped, not reinvented:

- `core-engine/src/main/java/dev/aarso/domain/loop/GraphRunner.kt` (269 lines), test
  `core-engine/src/test/java/dev/aarso/domain/loop/GraphRunnerTest.kt`.
- Public API (cited directly from the core report): `class GraphRunner(generatorFor, gatewayPolicy
  = ConditionGatewayPolicy, tokenCounter, now)` (`GraphRunner.kt:129-142`); `suspend fun
  run(graph, objective, hardCap = 24, params, budget: LoopBudget?, onStep): GraphRunResult`
  (`GraphRunner.kt:165-172`); `data class GraphStep(...)` (`:34-44`); `data class
  GraphRunResult(...)` (`:49-56`).
- `core-engine/src/main/java/dev/aarso/domain/loop/LoopBudget.kt` — budget checked between steps
  (`GraphRunner.kt:190-196, 220`); `stoppedBecause` values enumerated:
  `"budget:tokens"|"budget:steps"|"budget:wall"|"cancelled"|"hit step cap (N)"|"no start
  event"|"no outgoing edge"|"missing params: …"`.
- `${key}` param substitution via `LoopParams` (`GraphRunner.kt:173-177, 222-224`) — unresolved
  key is a refusal to start, never silent-blank.
- `core-engine/src/main/java/dev/aarso/domain/loop/GraphRunLedger.kt` writes `LedgerCapture` rows
  tagged `surface = "loop"`, separate from tree-logging `GraphRunLog`.

**Post-survey filesystem note (not from the four reports — observed directly while authoring
this document, flagged separately from the survey findings above):** `git status --porcelain` in
`/home/user/Android-IDE-core` right now shows a set of **untracked** files that did not exist
when the core survey ran (the core report explicitly stated "no `ratified`/`schemas`/`contracts`/
`fixtures` directory anywhere in the repo"):

```
?? docs/ratified/loops/LOOP_FROZEN_CONCEPTS_WP1L_G0.md
?? schemas/loops/registries/canonicalization.v1.json
?? schemas/loops/registries/capability-ids.v1.json
?? schemas/loops/registries/floop-container-format.v1.json
?? schemas/loops/registries/loop-validation-rules.v1.json
?? schemas/loops/registries/model-capability-vocabulary.v1.json
?? schemas/loops/registries/semantic-digest.v1.json
?? schemas/loops/fixtures/canonicalization/  (6 test-vector sets + README)
?? scripts/canon_reference.py
```

File birth timestamps are **2026-08-07 03:35:02–03:39:34 UTC**, i.e. after the four survey
agents ran. `docs/ratified/loops/LOOP_FROZEN_CONCEPTS_WP1L_G0.md` self-describes as "WP-1L-G0 —
GATE CLEARED, 2026-08-07," freezing four normative loop concepts (`.floop` container format,
semantic digest, a 24-code validation-rule registry, a 20-ID `fb.*` capability-ID namespace) plus
two supporting registries (canonicalization profile, model-capability vocabulary). I did not
audit this content beyond a directory listing and the file header — **WP-1/WP-1L must read and
verify it directly rather than trust this summary**, and must reconcile it with whatever WP-1L-G0
work this document's requester already knows about, since it is invisible to all four survey
reports.

**Verdict: extend-existing** (GraphRunner/LoopBudget/GraphRunLedger is the runtime to adapt) —
**with the caveat that a separate, uncommitted gate artifact for the loop contract corpus already
exists on disk in `core` right now** and predates nothing in the four reports below it.

### (e) Device Broker — **EXTEND-EXISTING substrate; no "broker" abstraction by that name**

- **core:** `core-engine/src/main/java/dev/aarso/data/DeviceRepo.kt` — runs a `DeviceRecipe` →
  `ExecRequest` over the SSH spine via `RemoteSessionDriver`; only proceeds on an already-vetted
  host; returns raw streamed output, never paraphrased (core report §3). `device` is one of the
  40 subpackages under `core-engine/src/main/java/dev/aarso/domain/` (per the core report's
  package enumeration in §1); CLAUDE.md's repo map additionally names `ArduinoCli`, `usb/`
  `IntelHex`+`Stk500` under `domain/device`, but the core report did not itself re-verify those
  specific file paths beyond confirming the `device` package's existence — flagged as
  CLAUDE.md-sourced, not independently re-confirmed in this survey pass.
- **aarso:** confirms no "Device Broker" vocabulary present in that repo (§5, same sentence
  quoted under (c)).
- No report from studio or hyle mentions device-broker material.

**Verdict: extend-existing substrate** (`DeviceRepo.kt` + `domain/device` + the SSH spine below)
— there is no existing type or module literally named "Device Broker"; that would be new naming/
wrapping work over real device-execution code that already exists.

### (f) Integration lanes (CSApp/Assay import grammar) — **CREATE-NEW (no evidence found)**

None of the four reports mentions "CSApp" or "Assay" anywhere, in any repo, in any form. No
report records a grep specifically for these terms, so this is an **absence of evidence, not
confirmed evidence of absence** — none of the four survey passes targeted this vocabulary.
**Recommend a direct, targeted grep for `CSApp`/`Assay` across all four repos as an immediate
WP-1 pre-check** before assuming this is fully greenfield; this document cannot responsibly
upgrade "not mentioned" to "confirmed absent" the way it can for terms the reports explicitly
grepped for and reported zero hits on.

### (g) Search contracts — **ALREADY EXISTS AND IS WIRED INTO THE UI — CORRECTION to the handoff pack**

> **Flag for the handoff-pack authors:** the pack's own context doc reportedly claims on-device
> search is *not* wired into the UI. The core survey report found the opposite, with specific
> line-level evidence, and traced exactly why the pack's claim is stale. Treat the pack's "not
> wired" claim as **superseded** by the evidence below.

Full stack exists in core, exactly matching CLAUDE.md's repo map:
- `core-engine/src/main/java/dev/aarso/domain/search/`: `Segmenter.kt`, `LexicalSearch.kt`,
  `SearchIndexables.kt`, `query/{QueryCompiler,QueryParser,QueryNode,Field,FacetEvaluator,
  ConversationFacetFilter,ScopeStack,RelativeDate}.kt`.
- `core-engine/src/main/java/dev/aarso/data/search/`: `SearchDriverFactory.kt`,
  `SearchProjector.kt`, `SearchIndexer.kt`, `SearchQuery.kt`, `SearchRepository.kt`.
- `core-engine/src/main/java/dev/aarso/ui/search/`: `SearchViewModel.kt`, `SearchPresenter.kt`,
  `SearchResultsPresenter.kt`, `SearchOverlay.kt`, `InChatFindPresenter.kt`.

**Wiring evidence, read directly from `core-engine/src/main/java/dev/aarso/ui/spatial/
SpatialRoot.kt`:** `SearchOverlay`/`SearchViewModel` imported (`:82-83`) and instantiated
(`:228`); `SearchOverlay` conditionally rendered on a real `searchOpen` boolean (`:503-514`);
`searchOpen` is driven from three independent live entry points — a `Ctrl+K` hardware-keyboard
shortcut on the root `onPreviewKeyEvent` (`:284-291`), an `onOpenSearch` callback wired from
`ChatsRoom` (`:318-322`), and an `onSearchAllChats` callback from `ChatScreen` that also forwards
query text into `searchViewModel` (`:396-399`). This is real, load-bearing UI wiring, not dead
code.

**Why the pack disagrees:** `docs/HANDOFF-CURRENT.md:195-196` (git last-modified **2026-07-15**)
says search is "not yet wired into the Conversations UI." `docs/STATE.md:100-138` (git
last-modified **2026-08-01**, newer) has a full "Sovereign on-device search (M0→M3)" section
claiming M0-M3 shipped, including "M3 — the surfaces... full-screen search overlay... find-in-
chat... Ctrl+K" (`STATE.md:131-134`) — and this is what the code confirms. HANDOFF-CURRENT.md is
the stale doc here; it predates a later wiring pass STATE.md was updated to reflect. (A second
trap layer: HANDOFF-CURRENT.md itself claims "STATE.md is stale as of this pointer" at its own
lines 15-18 — but that self-declared-authority claim is itself from the older, July-15 doc, and
STATE.md has since moved past it. Don't trust a doc's self-declared authority either, only its
actual timestamp vs. what it says.)

**Verdict: already exists, already wired.** WP-1 should treat search as real substrate to model
a search-contracts corpus against, not as a UI gap to fill.

### (h) SSH lane — **ALREADY EXISTS**

Real implementation via `sshj` (`com.hierynomus:sshj:0.39.0`, `gradle/libs.versions.toml:22,74`;
wired in `core-engine/build.gradle.kts:168`), not a stub:
- `core-engine/src/main/java/dev/aarso/domain/remote/`: `RemoteHost.kt`, `RemoteTransport.kt`
  (transport seam), `RemoteSessionDriver.kt` (lifecycle: connect → classify host key → suspend
  for user trust decision on Unknown/Changed → auth → exec → close, via `SessionMachine`),
  `KnownHosts.kt` (trust-on-first-use record), `Sftp.kt`, plus `remote/term/`
  (`VtParser.kt`, `ScreenBuffer.kt`, `PtyChannel.kt` — a real terminal emulator layer).
- `core-engine/src/main/java/dev/aarso/data/remote/SshjTransport.kt` — the real sshj-backed
  `RemoteTransport` implementation.
- `core-engine/src/main/java/dev/aarso/data/RemoteHostStore.kt` — persists `RemoteHost` configs +
  pinned host keys + secrets (Keystore-encrypted), mirrors `GitHostStore`'s pattern.
- `core-engine/src/main/java/dev/aarso/data/DeviceRepo.kt` — see (e); consumes this spine.
- UI wiring: `ui/develop/DevelopRoom.kt` and `TerminalFacet.kt` reference `RemoteHostStore`/
  `DeviceRepo`.
- On-device SSH behavior is owner-verified only per repo convention; JVM tests use a fake
  transport (`RemoteSessionDriver` KDoc, `RemoteSessionDriver.kt:11`).

**Verdict: already exists.** Real substrate a Device Broker or Execution Contract design would
model against, not greenfield.

### (i) CI build spine — **ALREADY EXISTS, two distinct things by this name**

**In-app "builds" feature (core):** `core-engine/src/main/java/dev/aarso/domain/builds/`
(`Build.kt`, `BuildsApi.kt`, `CiTrigger.kt`) builds REST requests against the user's own Git host
(GitHub or Gitea) for release-asset and dist-branch (`apk-dist` → `aarso-sd.apk`) APK sources, and
parses CI check-runs/status as a test badge — pure request-builder/parser, no network of its own.
`core-engine/src/main/java/dev/aarso/data/BuildsRepo.kt` is the data-layer facade;
`core-engine/src/{full,play}/java/dev/aarso/data/ApkInstaller.kt` are flavor-specific installers.
Wired into UI: `BuildsRepo` used from `ui/develop/DevelopRoom.kt`.

**Repo-level GH Actions gate (real command, both CLAUDE.md files are stale here):**
- **core:** `.github/workflows/ci.yml` gate is `./gradlew --no-daemon
  :core-engine:testFullDebugUnitTest :core-engine:testPlayDebugUnitTest
  :core-engine:checkLicense` (3-attempt retry for transient registry 403s). Both CLAUDE.md files
  in context state the gate as `:app:test...` (Android-IDE-core's) or additionally `:hyle:test`
  (Android-IDE-Studio's) — post-de-fork, `:app` has no test sources (`find app -iname test -type
  d` → empty), and `ci.yml` itself documents the move in a comment. Native `assembleFullDebug` is
  weekly-cron/manual-only (`if: false` on push — "OOMs the runner").
- **studio:** `.github/workflows/ci.yml` gate is `./gradlew --no-daemon :app:testFullDebugUnitTest
  :app:testPlayDebugUnitTest :hyle:test :core:core-engine:checkLicense` (also 3x retry); native
  assemble also `if: false`. Plus a second workflow, `update-free-tiers.yml` — monthly cron +
  manual dispatch, checks `app/src/main/assets/free_tiers.json` staleness and opens a reminder
  issue if stale (a docs-freshness bot, not a build/test gate).
- **hyle:** the hyle report does not mention `.github/workflows/` at all — **not covered**, gap
  stated rather than filled.
- **aarso:** no `.github/` directory exists in the checkout at all (confirmed by the aarso
  report's `find` pass) — no CI in this repo.

**Verdict: already exists** (both meanings), with real per-repo divergence in the exact gate
command that WP-1 should read from `ci.yml` directly rather than from either CLAUDE.md.

### (j) Existing `docs/ratified`, `schemas/`, `contracts/kotlin/` directories — **none existed at survey time; see post-survey note under (d)**

- **core report:** exhaustive `find` for `ratified`, `non_ratified`, `schemas`, `contracts`,
  `fixtures` → none exist. Full `docs/` tree enumerated; no such directories present.
- **aarso report:** whole repo is 5 files; no such directories (implicit from the full listing).
- **studio report:** exhaustive `find` for `ratified`, `non_ratified`, any path containing
  `schemas`/`contracts`, `fixtures`, `*.floop`, `loop-validation-rules*`, `capability-ids*` → no
  matches whatsoever.
- **hyle report:** `find` for `ratified`/`schemas`/`non_ratified` directories → none exist;
  `docs/` contains only `PHILOSOPHY.md`.
- **No `contracts/kotlin/` directory, nor any file named `*Contracts.kt`, was found in any of the
  four repos** (core report explicitly re-confirmed via `find . -iname "*Contracts.kt"` → empty,
  during authoring of this document).

**Post-survey correction (see full detail under (d) above):** as of authoring this document,
`/home/user/Android-IDE-core` has an **uncommitted** `docs/ratified/loops/` and `schemas/loops/`
tree (`git status --porcelain` shows `??` for all of it) that did not exist when the core survey
ran. This is the one place where "none exist" needs an asterisk — read (d) before treating the
loop-contract corpus as untouched.

---

## 2. Package naming inventory

| Repo | `dev.fonebrew` (or its subpackages) | `Aarso` (display) | `fonebru`/`Fonebrew`/`FoneBru` (any case) |
|---|---|---|---|
| **core** (`android-ide-core`) | 1,631 grep lines across code+docs; every Kotlin `package`/`import`, every `namespace`/`applicationId`, `rootProject.name = "Aarso"` (`settings.gradle.kts:36`) | 291 occurrences (app display name, doc prose, KDoc, `docs/brand/aarso-logo.svg`) | **6 total, all in docs, zero in code.** `docs/STATE.md:100,101,220`; `docs/CORE_PHASES.md:19` — "Brand string is 'FoneBru' (owner ruling 2026-07-11). Package rename stays deferred (Sprint R) — do not touch Gradle identifiers."; `docs/HANDOFF-CURRENT.md:19`; `docs/handoff/device-independence.md:17` |
| **aarso** (`Aarso` repo) | package `dev.fonebrew.mirror` (`MirrorLens.kt`, `MirrorSeam.kt`) | small — repo is literally named/branded Aarso throughout its 5 files | not reported — the aarso report did not run a naming-inventory grep; no Fonebrew occurrence appears in the file contents quoted in that report |
| **studio** (`Android-IDE-Studio`) | packages `dev.fonebrew`, `dev.fonebrew.studio_pm`, `dev.fonebrew.studio_launch`, `dev.fonebrew.studio.entitlement` | present (app labels, docs) | appears as a **git branch name** (`claude/fonebrew-development-clzu43`, the checked-out branch) and as a **doc filename** (`docs/FONEBREW_OVERVIEW.md`, touched by merged PR #84 and again by `origin/main`'s `8c04bff`) — still zero occurrences in any Kotlin package declaration per that report |
| **hyle** (`Hyle-Design-System`) | packages `dev.aarso.hyle`, `dev.aarso.crashrecovery`, `dev.aarso.hyle.worlds` | present (module/artifact naming) | not mentioned anywhere in the hyle report |

**Bottom line (from the core report, the only one that ran an explicit count):** "Fonebrew"/
"FoneBru" is a **documented owner ruling on a future display brand string only** — it has not
touched any package declaration, `applicationId`, `namespace`, module name, or class name in the
open-core repos surveyed. Studio shows the name migrating into a branch name and a doc filename,
still not into code. Nothing in any of the four repos has pre-committed to a `fonebrew`-rooted
Kotlin package.

---

## 3. Git library note

**No JGit anywhere in the surveyed constellation.** The core report is the only one that checked
this explicitly: `grep -rni "jgit"` across every `build.gradle.kts` and `gradle/libs.versions.toml`
in `android-ide-core` → zero hits. Git integration is pure REST request-builders/parsers,
executed by an injected transport — `core-engine/src/main/java/dev/aarso/domain/git/
GitContentsApi.kt` and `GitTreeApi.kt` (GitHub + Gitea) — no embedded git implementation, no
`.git`-directory manipulation library. The only real network deps found are `okhttp`/`okhttp-sse`
(`core-engine/build.gradle.kts:165-166`) and `sshj` (`:168`).

Studio's `core/` directory is a **git submodule** (source-control checkout mechanism, via
`.gitmodules` + `includeBuild`), which is a different sense of "git" entirely from an in-app git
API library — not to be conflated with the above. Neither the aarso nor the hyle report addresses
an in-app git library question (not applicable to those repos' content).

---

## 4. Aarso repo scope note

Confirmed from the aarso report: `Aarso` (`/home/user/Aarso`) is a **tiny, bounded, intentionally
inert seam repo** — the "mirror" self-reflection lens — and **not the main app**. Evidence:

- **5 files total** in the whole working tree: `README.md`, `docs/EVENT_CONTRACT.md`,
  `src/main/kotlin/dev/aarso/mirror/MirrorLens.kt`, `MirrorSeam.kt`,
  `src/test/kotlin/dev/aarso/mirror/MirrorSeamTest.kt`.
- **No Gradle project at all** — no `build.gradle*`, `settings.gradle*`, gradle wrapper,
  `CLAUDE.md`, `docs/STATE.md`, or `PHASE_STATE.md` anywhere in the checkout. Not independently
  buildable as checked out.
- Content is a swappable-implementation seam pattern (`MirrorLens` interface, `InertMirrorLens`
  no-op default, `MirrorSeam` holder with `install`/`uninstall`) plus a doc
  (`docs/EVENT_CONTRACT.md`) that documents an event-log write-side contract whose **actual code
  lives in the other repo** (`android-ide-core`, package `dev.fonebrew.domain.mirror`) — not present
  here.
- Currently always **OFF**: `AppContainer.kt` (in the other repo) hard-codes
  `AarsoCaptureSettings.OFF`; no settings UI to enable it exists.
- Gated on `Android-IDE-Studio` Issue #2, not an issue in this repo — the repo's own doc flags
  that its instructions mistakenly pointed at "Aarso Issue #2," which returns 404 (verified
  against the GitHub API); the real tracking issue lives in the Studio repo.
- No envelope/receipt/contract vocabulary, no routing/authority/capability-ladder vocabulary
  anywhere in this repo (confirmed by grep, §a/§c above).

WP-1/WP-1L should treat this repo as source of exactly two things: the event-schema shape in
`docs/EVENT_CONTRACT.md` as prior art for a future enum-plus-payload log pattern, and the
seam/inert-default/install pattern in `MirrorLens.kt`/`MirrorSeam.kt` — nothing else.

---

## 5. Studio repo state

**Thin composite build**, real (not just documented): `app/` (Application class + entitlement
gate only — `app/src/main/java/dev/aarso/StudioApp.kt`, 31 lines, subclasses core's `FonebrewApp`,
only overrides `onCreate()` → `installStudioEntryPoint(this)`) + `:studio-pm` + `:studio-launch` +
a `core/` git submodule providing `dev.aarso:core-engine` via `includeBuild`
(`settings.gradle.kts:69-73`, explicit `dependencySubstitution`).

- **`core/` submodule is present but NOT checked out** in this working copy (`ls core/` → empty;
  `git submodule status` shows uninitialized). Its recorded gitlink, however, is already
  `2969437789199c3699dc3aa78dc3db98d5ff3090` (post-PR-#11 core main) — the pin itself was already
  moved by commit `f49cd5c`; only the **inline comment** in `settings.gradle.kts:33-38` is stale
  (still describes an older pin). Core's `main` has since advanced one more PR past that pin (to
  `ab3567e`, "Search M0–M3") — so the pin is one PR behind current core `main`, just not for the
  reason the doc gives.
- **`studio-pm/`** — real `com.android.library`: `ProjectRoom.kt` (299 lines, Board/List/
  Waterfall, imports `dev.fonebrew.domain.pm.{BoardCard,BoardColumn,Boards}` from core-engine),
  `StudioRooms.kt` (`installStudioProjectRoom()`).
- **`studio-launch/`** — real `com.android.library`: `StoreListing.kt` (75 lines, pure-Kotlin Play
  listing generator), `StudioDevelopFacets.kt` (Launch tab: store-listing form + scaffold drafts,
  registers a `DevelopTab("Launch")` into core's seam).

**Entitlement status:** structurally code-complete but **cannot sell**. `StudioEntitlement.kt`
(96 lines), `EntitlementRecordStore.kt` (53 lines, Keystore-backed), `LemonSqueezyTransport.kt`
(80 lines, real OkHttp License-API calls) all exist and are wired through `StudioEntryPoint.kt`.
Blocking issue: `LemonSqueezyConfig.kt:13-17` has four literal `TODO-lemon-squeezy-*` placeholder
values (`STORE_ID`, `PRODUCT_ID`, `STORE_URL`), with an explicit `// TODO(owner): replace every
value below with the real Lemon Squeezy store before v1 ships.` This is the entire TODO surface
of the module — grep across `app/`, `studio-pm/`, `studio-launch/` finds no other TODOs.

**Instrument-track status (PC-A/PC-B "Roundtable"):**
- **In this repo:** zero `Roundtable`/`VersionSuggestSlot` code — every occurrence is doc-only
  (`PHASE_STATE.md`, `RATIFICATION.md`, `docs/handoff`/`docs/STUDIO_*`). `ProjectRoomSlot` (the
  pattern Roundtable is modeled on) is real and in use.
- **Cross-repo check (GitHub API + core checkout, corroboration only):** core PR #14 ("Session 0
  + PC-A: licensing hygiene + Conversation Instrument") is **open, draft, unmerged**; its head
  commit `cdcc339` does contain `RoundtableSlot`/`VersionSuggestSlot` in
  `core-engine/src/main/java/dev/aarso/ui/curation/CurationSlots.kt` (64 new lines, modeled on
  `ProjectRoomSlot`), but these files are **absent from core's `origin/main`**. Studio PR #84
  (Session 0 docs) is merged; this repo's checked-out HEAD already contains that merge.
- **Conclusion:** PC-A (data model, inert slots) is built and reviewed but genuinely unmerged in
  core; PC-B (Studio's actual Roundtable UI/orchestration) has not been started anywhere.

**CI:** `.github/workflows/ci.yml` gate = `./gradlew --no-daemon :app:testFullDebugUnitTest
:app:testPlayDebugUnitTest :hyle:test :core:core-engine:checkLicense` (3x retry); native
`assembleFullDebug` step present but `if: false`. Second workflow `update-free-tiers.yml` is a
monthly-cron docs-freshness bot, not a build/test gate.

**No envelope/error/receipt runtime, no `docs/ratified/`, `schemas/`, or `contracts/kotlin/`
exist in this repo** (exhaustive `find`, zero matches) — see §1(a)/(j) above.

---

## 6. Hyle repo state

**Tokens vs real atoms — confirmed tokens-only, no Compose in `:hyle` itself.** `grep -rn
"androidx.compose" hyle/` → zero matches; `grep -rln "@Composable"` across the whole repo matches
only `hyle-probe/` (5 files) and `wallpaper/.../WallpaperSettingsActivity.kt` — **none inside
`hyle/`**. What `hyle/` actually contains: `Hyle.kt` (`sealed interface Finish`
[`Reflective`/`Radiant`], `data class Pulse` with `WATCHED`/`STILL` presets, `object
RadiantHues`, `sealed interface Provenance` [`OnDevice`/`Cloud`] + a `Glyph` enum for the
colour-blind-safe channel) and generated `tokens/HyleTokens.kt`. The module's own header comment
(`Hyle.kt:1-15`) states the intent plainly: "deliberately pure data + contract (JVM-tested), no
Compose, no colour committed... Modifiers and AGSL shaders next" — an acknowledged future step,
not an oversight. The real componentized primitives that exist today are **Lit/TypeScript web
components** in `src/components/` (~20: `hy-knob`, `hy-fader`, `hy-toggle`, `hy-meter`, `hy-vu`,
`hy-chip`, `hy-button`, `hy-input`, `hy-card`, `hy-transport`, `hy-joystick`, `hy-dial`,
`hy-scroll-wheel`, `hy-key`, `hy-icon`, `hy-color-picker`, `hy-field`, `hy-pane`, `hy-pulse`,
`hy-surface`), not Compose.

**Crash-recovery module:** separately-versioned `dev.aarso.crashrecovery`, plain `android.widget`
views (no Compose), **zero dependency on `:hyle`** (`crash-recovery/build.gradle.kts:1-8`).
`CrashReport.kt` (structured fields + derived `headline`), `CrashRecoveryActivity.kt` (757 lines
— two-pane recovery screen, Share/Copy/Continue, confirm-gated Reset). This is the nearest
"structured report → review screen → confirm-gated action" UI-shape precedent in the whole
constellation, but it's crash-specific and deliberately hyle-independent — not directly reusable.

**Maven coordinates (from `build.gradle.kts` files, trusted over stale README/settings.gradle.kts
comments):**
- `dev.aarso:hyle:0.2.0` (`hyle/build.gradle.kts:20-22`; header comment: "The `0.1.0` coordinate
  is permanently retired... this is the first single-sourced release, `0.2.0`.")
- `dev.aarso:crash-recovery:1.1.0` (`crash-recovery/build.gradle.kts:11-13`)
- Root `README.md` and `settings.gradle.kts:23` still advertise the old `0.1.0`/`1.0.0` — both
  postdate the version bumps they fail to reflect (git-timestamp comparison in the hyle report);
  treat `build.gradle.kts` as authoritative since that's what `includeBuild`/`maven-publish`
  actually resolve against.

**No envelope/error/receipt code, no `docs/ratified/`/`schemas/` directories** — `docs/` contains
only `PHILOSOPHY.md`. CI workflows were **not covered** by the hyle report (gap, not a finding of
absence).

---

## 7. Mapping table

| Artifact | Status | Repo placement | Evidence |
|---|---|---|---|
| (a) Common envelope/error/receipt library | **create-new** | none exists; natural home is core-engine given it hosts the rest of the domain/data layer | core/aarso/studio/hyle: all four grepped zero hits on `Envelope`/`Receipt`/`ArtifactRef`/`CapabilityManifest`/`MigrationPlan`. Nearest analogs: Studio `StudioEntitlement.kt:9-12` `ActivateResult`, `LsTransport.kt` `LsOutcome`; Hyle `crash-recovery/.../CrashReport.kt` `encode()`/`decode()` "CRASHv2" |
| (b) Workspace Kernel | **create-new** | none | core + studio: explicit grep for `WorkspaceKernel`/`DocumentBuffer` → zero hits; no mention in aarso/hyle reports |
| (c) Execution Contract + Authority engine | **create-new** | none; nearest precedent is Studio's narrow entitlement gate | core: zero hits `ExecutionContract`/`ratified`; studio: zero hits `CapabilityManifest` etc., but `StudioEntitlement.kt`/`EntitlementRecordStore.kt`/`LemonSqueezyTransport.kt`/`StudioEntryPoint.kt` exist as a narrow gate precedent; aarso: "none of the WP-1/WP-1L vocabulary appears in this repo at all" |
| (d) Loop Engineering (GraphRunner adapter target) | **extend-existing** | core-engine `domain/loop/` | `core-engine/src/main/java/dev/aarso/domain/loop/GraphRunner.kt` (269 lines) + `LoopBudget.kt` + `GraphRunLedger.kt`, full API cited, test `GraphRunnerTest.kt`. Plus post-survey (uncommitted): `docs/ratified/loops/LOOP_FROZEN_CONCEPTS_WP1L_G0.md` + `schemas/loops/registries/*.json` (6 files) + `schemas/loops/fixtures/canonicalization/*`, created 2026-08-07 03:35–03:39 UTC, after the survey ran |
| (e) Device Broker | **extend-existing** (substrate; no "broker" by that name) | core-engine `data/DeviceRepo.kt` + `domain/device` + SSH spine | `core-engine/src/main/java/dev/aarso/data/DeviceRepo.kt` (runs `DeviceRecipe`→`ExecRequest` via `RemoteSessionDriver`, vetted-host-only); `device` is one of 40 `domain/` subpackages per core report's enumeration |
| (f) Integration lanes (CSApp/Assay import grammar) | **create-new — no evidence found either way** | none | zero mentions of "CSApp" or "Assay" in any of the four reports; no report ran a targeted grep for these terms — WP-1 should grep directly before assuming greenfield |
| (g) Search contracts | **already exists, wired into UI** — correction to handoff pack | core-engine `domain/search`, `data/search`, `ui/search` | full file list in core report §5; wiring confirmed at `ui/spatial/SpatialRoot.kt:82-83,228,284-291,318-322,396-399,503-514`; `docs/STATE.md:100-138` (2026-08-01, newer) says shipped, contradicts `docs/HANDOFF-CURRENT.md:195-196` (2026-07-15, older, stale) |
| (h) SSH lane | **already exists** | core-engine `domain/remote`, `data/remote/SshjTransport.kt` | `sshj:0.39.0` in `core-engine/build.gradle.kts:168`; `domain/remote/{RemoteHost,RemoteTransport,RemoteSessionDriver,KnownHosts}.kt` + `remote/term/*`; `data/remote/SshjTransport.kt`, `data/RemoteHostStore.kt`; wired in `ui/develop/DevelopRoom.kt`/`TerminalFacet.kt` |
| (i) CI build spine | **already exists** (in-app feature + repo-level gate, both real) | core-engine `domain/builds` + `.github/workflows/ci.yml` in core and studio | `domain/builds/{Build,BuildsApi,CiTrigger}.kt` + `data/BuildsRepo.kt`, wired into `DevelopRoom.kt`; core `ci.yml` gate is `:core-engine:test{Full,Play}DebugUnitTest :core-engine:checkLicense` (both CLAUDE.md files are stale here); studio `ci.yml` gate is `:app:test{Full,Play}DebugUnitTest :hyle:test :core:core-engine:checkLicense`; hyle CI not covered by that report |
| (j) `docs/ratified`/`schemas`/`contracts/kotlin` dirs | **none existed at survey time in any repo**; core now has an uncommitted partial exception | n/a | core/studio/hyle reports: exhaustive `find` → none; aarso: 5-file repo, none. Post-survey (uncommitted, not in any report): core's `docs/ratified/loops/` + `schemas/loops/` — see (d). No `contracts/kotlin/` found anywhere, before or after |

---

## Closing

**No code changes were made in WP-0.** This document is a pure inventory synthesized from four
read-only survey reports plus one directly-observed, clearly-flagged filesystem discrepancy found
while authoring it (§1(d)/(j)).

**What WP-1/WP-1L should read first, given these findings:**

1. **`docs/ratified/loops/LOOP_FROZEN_CONCEPTS_WP1L_G0.md`** and the six `schemas/loops/
   registries/*.json` files in `android-ide-core` — read and verify these directly before doing
   any loop-contract-corpus work. They are uncommitted, postdate every survey report this document
   is built from, and their content has not been audited here beyond a listing and a file header.
   Reconcile with whatever WP-1L-G0 gate work is already known to the requester.
2. **`core-engine/src/main/java/dev/aarso/domain/loop/GraphRunner.kt`** (+ `LoopBudget.kt`,
   `GraphRunLedger.kt`) — the real runtime any Loop Engineering adapter wraps; do not reinvent it.
3. **`core-engine/src/main/java/dev/aarso/ui/spatial/SpatialRoot.kt`** and the `domain/search`/
   `data/search`/`ui/search` trees — search is real, wired, shipped substrate, not a gap; correct
   the handoff pack's context doc on this point (§1(g)).
4. **Each repo's actual `.github/workflows/ci.yml`**, not either CLAUDE.md — the real gate
   commands diverge from both CLAUDE.md files in context (§1(i)).
5. **A targeted grep for "CSApp"/"Assay"** across all four repos — the one artifact group this
   survey pass could not resolve either way (§1(f)).
6. **`Aarso/docs/EVENT_CONTRACT.md`** and the aarso repo's `MirrorLens.kt`/`MirrorSeam.kt` seam
   pattern — the only two things that small repo actually contributes (§4).
