# Fonebrew (android-ide-core) — multi-platform porting plan

> Part of the constellation-wide porting program (`Personal-Tracker/PORTING_PROGRAM.md`, 2026-10-06).
> Status: **PLAN — nothing in this document has been built.** Every claim about a target platform is
> labelled with its evidence class (§0). This file is owned by the lead planning session; a platform
> track updates only its own §4 row and appends to `PROGRESS` entries in this repo's own state file
> (`HANDOFF_STATE.md`, open thread 15).
> Decisions this plan raises are filed as `FB-RAT-PORT-NEW-<n>` **proposals** in
> `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`. None is ratified here, and this plan changes no code,
> build file, CI file or ratified document. It says nothing about the private downstream layer's contents
> (`docs/CORE_PHASES.md` header: core is a public repo and that layer's internals stay out).

## 0. Evidence labels (never dropped)

`LAB` · `CI (hosted VM) evidence` · `EMULATOR EVIDENCE` · `SIMULATOR` · `CI-APPROX — NOT DEVICE EVIDENCE` ·
`SIMULATED — NOT DEVICE EVIDENCE` · `VIRTUALIZED — NOT DEVICE EVIDENCE` · `SYNTHETIC` · `CI-ONLY / NOT RUN` ·
`NEEDS-DEVICE-VALIDATION` (NDV) · `NEEDS-OWNER-VALIDATION` (NOV), plus the program's additions `PLAN` ·
`NOT-APPLICABLE (<reason>)` · `CONTAINER-BUILD-ONLY` · `BROWSER-HEADLESS` (program §2). This plan uses
`CI (hosted VM)` as shorthand for `CI (hosted VM) evidence`. `PLAN` marks this document's own claims, and
`CONTAINER-BUILD-ONLY` means JVM x86_64 compile and tests in a build container, nothing else. This repo's own
status vocabulary stays mandatory in commits and docs: `code-complete`, `verified-JVM`, `owner-verify`
(`docs/CORE_PHASES.md`); `owner-verify` means NDV or NOV here. Nothing in this
file is `verified-JVM` for any non-Android target.

## 1. What this repo is, in porting terms

- **Product.** Fonebrew (owner-ruled brand 2026-08-01; code names Aarso/Workbench; package `dev.aarso`), the
  open core of a sovereign, touch-native computing environment on an arm64 Android phone: on-device (llama.cpp
  GGUF) plus opt-in "watched" cloud multi-model chat, an append-only git-like message tree, a Council, a
  Loop graph engine (GraphRunner + BPMN 2.0), an agentic repo loop over REST git, Devices (SSH, USB flash),
  on-device image generation, FTS5 conversation search, and the handoff-pack contract corpus (workspace kernel,
  execution/authority, device broker, loop packaging/import) as pure-JVM code with JSON schemas.
- **State.** v0.13.0, `versionCode 17` (`app/build.gradle.kts`), sideload APK on `apk-dist`; WP-0 to WP-11 complete
  (`HANDOFF_STATE.md`); 1549 tests, 0 failures, 1 skipped per `docs/WP11_GATE_REPORT.md` (not re-run for this
  plan). **Hosted CI today is unknown:** the six most recent `ci.yml` runs (GitHub API, 2026-10-06; weekly
  `main` canaries 09-28 and 10-05, PR runs 09-25) all concluded `failure`, causes not inspected here; earlier
  failures were diagnosed as a billing block (`docs/OWNER_GATES.md` §B). The six-hourly cleanup workflow does run.
  The repo is public per the API (`private:false`, default branch `main`).
- **Targets today.** Android arm64-v8a `full` (sideload: overlay, screen-capture OCR, USB flash, in-app APK
  install) and `play` flavors (`dist` dimension); a debug-only Hyle Probe launcher plus the `:hyle-probe` harness.
- **Stale maps.** Root `CLAUDE.md` and `docs/STATE.md` still place code under `app/`; it lives in `core-engine/`
  (`docs/WP0_SURVEY.md`, `docs/HANDOFF-CURRENT.md`). This plan uses the real layout.
- **Stack.** Kotlin 2.1.0 (JVM 17 toolchain) · C++17 JNI shims · SQLDelight `.sq` · JSON Schema 2020-12 · a Python
  canonicalizer. UI is Jetpack Compose (BoM 2025.05.01, Foundation 1.8.2 pinned for the mikepenz markdown renderer
  0.35.0) with in-repo Hyle atoms and a custom spatial room model, **not Compose Multiplatform**. Build is Gradle,
  AGP 8.9.1, KSP 2.1.0-1.0.29, SQLDelight 2.1.0, CMake 3.31.6, NDK 28.2.13676358; modules `:app` (thin shell),
  `:core-engine` (a `com.android.library` carrying all code and the `dist` flavors), `:sdengine`, `:hyle-probe`,
  plus `includeBuild("hyle-design-system")`. Frameworks: Room 2.7.1 (16 entities/DAOs, AppDatabase v8, destructive
  migration, schema export off), SQLDelight + `androidx.sqlite:sqlite-bundled` 2.7.0, coroutines 1.10.1, OkHttp 4.12.0
  with SSE, sshj 0.39.0, ML Kit text recognition (full flavor, GMS), `dev.aarso:hyle:0.2.0` and
  `dev.aarso:crash-recovery:1.0.0`. Native: llama.cpp @ `5343f450` and stable-diffusion.cpp @ `cfbc19d1` (own ggml,
  deliberately a separate library), both arm64-only (`-march=armv8.2-a+dotprod+i8mm+fp16`); submodules are not checked
  out in the planning container.
- **Size** (measured 2026-10-06 with `find … -name '*.kt' | xargs cat | wc -l`): 394 Kotlin files, 49,497 LOC across
  `core-engine/src/{main,full,play,debug}` and `contracts/kotlin`; 192 test files, 19,661 LOC (159 under `domain/`,
  20 `data/`, 12 `ui/`, 1 `inference/`); 6 `hyle-probe` files, 1,444 LOC; JNI shims 384 + 80 LOC; 63 JSON files under
  `schemas/` (51 `*.schema.json`) and 324 files under `fixtures/`.

## 2. Portable core vs platform-bound layers

| Module / dir | Role | Portability | Approx LOC | Notes |
|---|---|---|---|---|
| `core-engine/src/main/java/dev/aarso/domain/` (47 subpackages, 198 files) | tree, council, bpmn, loop (GraphRunner), diff/ide, remote (SSH session machine, VT parser), device (broker, flash driver, USB codecs), git/builds request builders, search, workspace, execution, authority, language lanes, mirror (inert) | pure-kotlin-jvm | 19,005 | No `android.*`/`androidx.*` import (grep). JVM-isms: `java.time`, `org.json` (21 files), `java.text` (3), `WatchService` (`LocalWorkspaceProvider`), `ProcessBuilder("/bin/sh","-c")` (`LocalProcessExecutionProvider`, line 100). Two back-edges into `data/`: `AuditedAuthorityEngine` to `ReceiptStore`, `CiActionsExecutionProvider` to `GitTransport`. |
| `contracts/kotlin/` (16 files; extra source root of `:core-engine`) | neutral contract types (envelopes, capability manifest, workspace/execution/authority/device/loop contracts) | pure-kotlin-jvm | 7,246 | Imports only `java.time` and coroutines `Flow`; `ExecutionTargetType` has `LOCAL_ANDROID` and no desktop value (line 55). |
| `schemas/`, `fixtures/`, `scripts/canon_reference.py` | JSON Schemas, fixtures, golden canonicalization vectors | other (platform-neutral data) | n/a | The vectors in `schemas/loops/fixtures/canonicalization/` are the cross-surface conformance gate (`LOOP_DUAL_SURFACE_ARCHITECTURE.md` §9). |
| `inference/` (13 files) | `InferenceEngine` (per-token logprob/entropy), `LlamaCppEngine` (JNI), Echo, cloud SSE engines, `SdImageEngine`, registry | pure-kotlin-jvm (JNI-bound) | 957 | One `android.util.Base64` import. Four `data/` types it depends on: `ImageStore`, `LocalModel`, `LocalModelStore`, `ProviderStore`. |
| `data/` (89 files) | Room entities/DAOs, 13 SharedPreferences stores, file stores, transports, search driver, USB | android-bound (mixed) | 5,771 | 27 files import `android.*`; `LedgerStore` and `ConversationsStore` depend back on `ui.state`. `UsbFlasher`/`CdcUsbSerialLink` are `android.hardware.usb`. |
| `data/search/` + `Search.sq` | FTS5 external-content index; sync triggers installed as raw SQL | kmp-common (SQL) | 234 (`.sq`) | Already exercised on a host JVM by `SearchDatabaseFactoryTest`. Only the path resolution is Android-bound. |
| `core-engine/src/main/cpp/llama_jni.cpp`, `sdengine/…/sd_jni.cpp` | GGUF load/stream shim; txt2img shim | native-c | 384 + 80 | `<android/log.h>`, arm64-only flags; `onToken` resolved by name (R8 minify stays off). |
| `security/KeystoreSecret.kt` | AES-256-GCM with a non-exportable AndroidKeyStore key (rule 5) | android-bound | 62 | Needs one actual per platform. |
| `di/AppContainer.kt`, `AarsoApp.kt` | manual DI root taking `Context` | android-bound | 285 + 27 | About 25 construction sites take `Context`; wires Room, stores, engines, crash-recovery. |
| `service/` + `src/full/` | FGS, assist/voice-interaction, overlay, MediaProjection + ML Kit OCR, APK installer | android-bound | 285 + 518 | Flavor-gated and separable; honest stubs off-Android. |
| `ui/` (57 files) | Compose screens, `SpatialRoot`, rooms, loops, develop, theme | android-bound (Compose-portable) | 15,018 | `LocalContext` 17 files, `BackHandler` 9, `viewModel()` 10, `Intent` 5, `R.font`/`R.drawable` 2, `android.graphics` 3. No Compose UI test harness. |
| `hyle-probe`, `src/debug` | material feel tests (AGSL `RuntimeShader`) | android-bound | 1,444 + 167 | SkSL via Skiko has a different API. |
| `hyle-design-system` (submodule) | `dev.aarso:hyle`, `dev.aarso:crash-recovery` | android-bound | not checked out | Contents unknown here; `crash-recovery` is used by `AarsoApp.kt` and `MainActivity.kt`. |

Platform-bound APIs that matter:

| API | Where | Porting impact |
|---|---|---|
| JNI engines (`System.loadLibrary("aarso_llama"/"aarso_sd")`) | `LlamaCppEngine.kt:137`, `SdImageEngine.kt:67`, both `cpp/` trees | Desktop JVM keeps the `external fun` surface and needs per-OS CMake builds plus a loader. iOS has no JNI (cinterop). |
| Room 2.7.1 via `Room.databaseBuilder(context, …)` | `AppContainer.kt`, `data/AppDatabase.kt` | Room-KMP needs the context-free builder and a path provider (open thread 10 in `HANDOFF_STATE.md`). No `Migration` object exists; every bump is destructive. |
| `SharedPreferences` (13 files) + `filesDir`/`cacheDir`/`getDatabasePath` | `data/*Store.kt` | A small KV plus app-dirs abstraction (20 `data/` classes take a `Context`). Formats are JSON and plain files, so data carries over. |
| Android Keystore | `KeystoreSecret.kt` | Rule 5 names it; each platform needs an owner-approved equivalent. |
| Foreground services, assist service, overlay, screen capture, USB host, APK installer | `service/`, `src/full/`, `data/device/`, `src/{full,play}/java/dev/aarso/data/ApkInstaller.kt` | No equivalent off-Android. `SharedIntake` is platform-neutral. `docs/design/app-distribution.md` §3 already says desktop artifacts are downloads handed to the OS and iOS never installs. |
| OkHttp + SSE (9 files), sshj (`SshjTransport.kt`) | `inference/cloud/`, `data/` | Unchanged on desktop JVM. iOS needs Ktor and libssh2/NMSSH behind `RemoteTransport`. |
| Play/full flavor split | `core-engine/build.gradle.kts`, `docs/ratified/DISTRIBUTION_CAPABILITY_SPLIT.md` | Each new platform needs its own capability rows (`FB-RAT-DIST-001`). |

## 3. Binding rules this port must not break

- **No telemetry, analytics, crash-reporting SaaS or phoning home, ever** (`CLAUDE.md` rule 1; `docs/CORE_PHASES.md`
  invariant 2). Binds the port: no in-app updater, no packaging-tool telemetry, no hidden fallback to cloud.
  Network stays user-initiated (cloud turn, consented download, the user's own git host, SSH host, CI).
- **On-device default; cloud is opt-in per use and visibly a "watched object"**, provider-generic (rule 2).
- **Council is never "MoE"** (rule 3). **§5b/§5c drift is blocked on Issue #2**; `domain/mirror` stays inert (rule 4).
- **Keys** are encrypted at rest, never logged, sent only to their provider, and **never transfer between surfaces**
  (rule 5; `docs/HANDOFF-CURRENT.md` §0). Rule 5 names the Android Keystore and says a port presents an equivalent
  custody model for owner approval (OQ-22; proposed `FB-RAT-PORT-NEW-4`).
- **Environment honesty**: no device, emulator, Mac or UT phone in CI; never claim runtime behaviour works (rule 6;
  `docs/CORE_PHASES.md` status vocabulary).
- **Pure-domain-first.** `domain/` and `contracts/` carry zero `android.*` imports and every piece is JVM-tested. The real
  gate is `./gradlew --no-daemon :core-engine:testFullDebugUnitTest :core-engine:testPlayDebugUnitTest :core-engine:checkLicense`
  (`.github/workflows/ci.yml`) and must stay green (program R2).
- **No Fonebrew backend, build farm, store account or payment backend**; a server-side package build service must not
  exist (`HANDOFF-CURRENT.md` §0; `FB-RAT-INT-003`; `LOOP_DUAL_SURFACE_ARCHITECTURE.md` §12). The browser never holds
  credentials or originates execution (`FB-RAT-WEB-002/003/004`).
- **Licence policy.** Apache-2.0 with DCO; linked or vendored dependencies only Apache-2.0/MIT/BSD/ISC (MPL-2.0 case by
  case); **all copyleft including LGPL is banned for linking**; runner-invoked tools may carry any licence; every
  dependency version-pinned and recorded in `NOTICE`; `checkLicense` gates (`config/allowed-licenses.json`).
  `FB-RAT-DIST-003` (toolchain licence policy) is DEFERRED. Consequence: **no Qt/QML client** on any target without an
  owner exception.
- **Colour never carries meaning alone**: violet `#8E7BFF` and cyan `#08FED5` are the only hue axis; state is shape plus
  label plus luminance (`CORE_PHASES.md` invariant 3). **State is shown by material, never by status words or spinners**
  (`docs/status.md`, `docs/design/rendering-handoff.md`).
- **Hyle tokens, not literals**; `hyle-design-system` stays the single source of `dev.aarso:hyle`, composited by
  `includeBuild`, and the published artifact is not substituted (`CORE_PHASES.md` invariant 4).
- **Room grammar is locked**: Chat is home; Conversations left, Settings right, Develop bottom, Product top; pinch Z; no
  new rooms, gestures or Z levels. **Pointer/keyboard is an accelerator, never a gate and never a second IA**
  (`FB-RAT-LBX-006`; `FB-RAT-PORT-001` bars new top-level rooms).
- **Do-not-unify and do-not-regress pins**: Room (message tree) and SQLDelight FTS5 (search) coexist as separate SQLite
  files; `sqldelight = 2.1.0` (2.3.x forces a KGP bump that breaks KSP); Compose BoM at or above Foundation 1.8 in lockstep
  with markdown renderer 0.35.0; R8 minification off; the raw-SQL FTS5 trigger workaround stays (`CLAUDE.md`).
- **Fenced-off code**: `domain/council/CostEstimator.kt` is not generalized; the message tree is append-only;
  `GraphRunLog.toNodes` semantics do not change (`CORE_PHASES.md` invariant 5).
- **Play/full split is per mechanism** (`FB-RAT-DIST-001`); Android W^X makes downloaded-native-exec impossible on every
  Android flavor. A desktop build can execute downloaded toolchains, which changes the language-lane legality table
  (`FB-RAT-LANG-NEW-1`, proposed, not ratified).
- **Identity.** Brand string is "Fonebrew"; the `dev.aarso` rename is deferred to Sprint R, so no Gradle identifier is touched
  (`CORE_PHASES.md` invariant 7). Program R11: no per-platform id (Flatpak, bundle, MSI GUID, click name) before a
  row in the program's identifier registry (`Personal-Tracker/NAMES.md`, not a file in this repo) (OQ-25).
- **Downstream consumption.** `:core-engine` stays a `com.android.library` with the `dist` flavor dimension because
  `Android-IDE-Studio`'s thin `:app` consumes it through a submodule pin and `includeBuild` (`docs/WP2_GATE_REPORT.md` §4;
  `FB-RAT-PORT-011`: the downstream layer may consume but never own or block core substrate).
- **Owner-reserved decisions are never self-ratified**: `FB-RAT-*` registers, `FB-RAT-WS-NEW-1`, `FB-RAT-LANG-NEW-1`,
  `FB-RAT-PHN-011`, licensing (`docs/OWNER_GATES.md` §C). Proposals go to `EXPERIMENTAL_DECISIONS.md`.
- **No second owned machine** to build on; rented, fungible hosted CI is the permanent build host
  (`docs/handoff/device-independence.md`, a research brief marked "no decision made"). Avoid Actions-specific features.
- **Generated data** (`model_catalog.json`, `free_tiers.json`) comes from Nooz's ai-catalogue and is never hand-edited here.
- **Program rules that bind every step (§3 of the program):** R1 disjoint directories, R2 existing gate stays green, R3 new
  workflow files only (SHA-pinned; `ci.yml` untouched; Windows path lint before any `windows-*` lane), R4 pure-core-first,
  R5 scaffolds bind, serve and sign nothing, R6 nothing released and no PR artifacts, R7 real command output only,
  R12 reframes are labelled.

## 4. Target matrix (owner's order)

| Target | Feasibility | Approach | Blockers | Effort (eng-weeks, estimate) | Evidence today |
|---|---|---|---|---|---|
| Ubuntu Touch | reframe | Not a port. Nearest shape: a Click webapp (Morph/webapp-container, HTML/JS only) over the ratified static Web Studio (loop authoring, testing, packaging; no credentials, no execution). A Fonebrew APK under Waydroid is owner-device evidence only (OQ-21). No QML click. | No Web Studio code exists (`FB-RAT-WEB-010` proposed; hosting `FB-RAT-LBX-009` deferred); no UT device on record (OQ-1); Qt would link LGPL; no app-reachable keystore, no daemons, unfocused apps are SIGSTOPped; ASOM is Android-only | 8 for the Click shape; Web Studio unestimated; a native Lomiri port (24+) is not proposed | PLAN |
| Linux desktop | moderate | Staged KMP seam (domain + contracts + inference to `jvmMain`), Compose Desktop head reusing `ui/`, JVM actuals (paths/KV, secret custody, Room-KMP, JNI loader), llama.cpp/sd.cpp Linux builds (F8), jpackage/Flatpak (F10) | Single Android library module; seam must not break the downstream pin, KSP/Room, sqldelight 2.1.0, BoM/markdown pins; about 25 `Context`-taking construction sites in `AppContainer`; no JVM Hyle/crash-recovery; OQ-22; no `LOCAL_DESKTOP` target; no UI test harness; CI budget (OQ-20) | 8 | PLAN |
| iOS / iPadOS | hard | After the seam: domain + contracts to `commonMain`, Ktor and libssh2, cinterop engines with Metal, Room-KMP on iOS, CMP iOS shell (iPad first), Keychain, Share Extension; no overlay/assist/USB/APK-install/local process | No JNI; ~26k LOC of `java.*` rewrite with golden-vector regression risk; no local process; jetsam limits far below the 16 GB benchmark; Apple Developer Program and signing (OQ-2, OQ-3); sd.cpp has no documented iOS build | 20 | PLAN |
| macOS | straight | Rides the Linux JVM build: jpackage `.dmg`, Metal engines (F8), Keychain or Swift-helper actual, Services/URL intake | Needs the Linux seam; Developer ID and notarisation are owner actions (OQ-3); no Mac on record (OQ-5), so device gates stay NOV | 3 | PLAN |
| Windows | straight | Rides the Linux JVM build: jpackage MSI and winget, win-x64 DLLs (F8), DPAPI/Credential Manager actual, a Windows answer for the POSIX-shell assumptions | Needs the Linux seam; `/bin/sh` in `LocalProcessExecutionProvider` and POSIX quoting in `DeviceRecipes`; no `.gitattributes` for byte-exact golden vectors; signing route (OQ-3); the owner's only Windows machine (OQ-5) | 3 | PLAN |

Efforts are estimates and match the program's §5 row (UT 8 + Linux 8 + iOS 20 + macOS 3 + Windows 3 = 42, Web Studio excluded).
Why these words: **Linux is moderate** because ~26k LOC of domain and contracts and ~1k LOC of inference port with no source
change, while ~5.8k LOC of `data/` and ~15k LOC of `ui/` are the touched surface; the estimate excludes USB flashing, the
overlay/assist/OCR tiers and any UI redesign. **macOS and Windows are straight** only because they reuse the Linux
artifact; their real work is actuals, native builds and packaging. **iOS is hard, not reframe**: the product category exists,
but the agentic-IDE promise shrinks to SSH and CI targets and the cost is commonizing the domain. **Ubuntu Touch is a reframe**
because a JVM/Compose app cannot ship as a confined Click app (no JVM in the Click path, no X11 for confined apps) and a native
alternative would need a Kotlin/Native rewrite plus an LGPL exception. A thin remote client that drives an SSH runner is not
offered inside a browser container, since the browser holds no credentials and does not execute (`FB-RAT-WEB-002/003`).

## 5. Tier and sequencing

**Tier B (A if OQ-7a says a desktop Fonebrew is wanted).** On engineering merit this is the constellation's cleanest
Compose-Multiplatform candidate (the largest pure-Kotlin substrate, zero Android imports in the core, 159 of 192 test files on
`domain/`), and the seam it produces (Room-KMP verdict, secret-custody actual, native-engine matrix, a desktop capability row) is
shared infrastructure. It stays B until the owner rules OQ-7a because: the device-independence brief rejects owning a second
machine; `FB-RAT-PORT-001`'s centre of gravity is workspace, execution, language, debugger, Git and docked, not a new platform;
and v0.13 has had no owner device verification (`docs/OWNER_GATES.md` §A), so each port multiplies unverified claims.

| Platform | Program wave (§7) | Build-entry | Repo-local gate before the wave starts |
|---|---|---|---|
| Ubuntu Touch | **P-UT a**, only once a Web Studio build exists (not in the program's initial P-UT a list; not P-UT b, since this repo has no JVM-cored QML click) | F7 webapp template; a static Web Studio build; a `NAMES.md` row for the click name (R11) | v0.13 device verification; OQ-1 or an explicit CI-only waiver; the Web Studio decision (question 8) |
| Linux | **P-LX** ("Fonebrew KMP seam + Compose Desktop (Room-KMP spike)", after hnm's desktop window) | F1 (`jvm()`), F5, and F2/F6 where consumed; OQ-17 ruled | v0.13 device verification; OQ-7a ruled; the existing Android gate demonstrably green in hosted CI (unknown today, see §1) |
| iOS / iPadOS | **P-iOS** ("Fonebrew, after the P-LX seam"), iPadOS first | CMP-iOS recipe proven on Clavis in the simulator; F1 iOS targets; a `macos-latest` lane | P-LX seam green; OQ-2 ruled; OQ-7a ruled |
| macOS | **P-mac** | P-LX binaries; signing secrets (OQ-3) | no Mac on record, so device gates NOV until OQ-5 |
| Windows | **P-win** | P-LX binaries; a signing route or accepted unsigned (OQ-3) | the Windows-path lint and byte-exact fixtures (step WIN-1); the shell answer (WIN-4); the Dell while it is still Windows (OQ-5) |

**OQ-7a, device independence.** `docs/handoff/device-independence.md` rejects "a second physical device the owner has to buy,
house and maintain" and accepts rented, ephemeral cloud compute. It is a research brief with no decision recorded. Building
desktop artifacts on hosted runners is consistent with it. *Running* a desktop Fonebrew is a separate matter, and this plan
proposes (`FB-RAT-PORT-NEW-1`) that the desktop surface is the larger-screen, pointer-capable surface for a docked phone and a
build for other users of the open core, never an owner workflow, with **Android staying the primary surface** and no state or
feature reachable only on desktop. A desktop JVM app is not the Android docked mode: `PointerLayoutMachine`
(`docs/NEXT_SESSIONS.md` item 4) is unbuilt and Android 16 desktop windowing is hardware-gated, so the desktop head should consume
that same machine rather than invent a second IA. Whether the owner will use it, and on what hardware, is OQ-5 and question 16.

## 6. Work breakdown

**Placement rules.** New files go only under `desktop/` (its own Gradle build with its own `settings.gradle.kts`; precedent:
asystemofmodels' `desktop/` and `lab/`), `packaging/<os>/`, `ubuntu-touch/` and `apple/`, and, after the staged extraction in LX-4,
new source sets in a new KMP module. Steps LX-1 and LX-2 edit no existing file. Changes to Android source arrive only as normal
PRs, one concern each, with the Android gate green and no on-device behaviour change (owner-verify). Every workflow is a **new**
file with SHA-pinned actions, compile-only on PRs, package and sign lanes on `main` or tags only, no PR artifacts, artifacts named
`UNSIGNED — not for release` (R3, R6). `ci.yml` and `cleanup-artifacts.yml` are not edited. Steps touching `:core-engine` need an
Android SDK (`scripts/setup-android-sdk.sh` installs one per `HANDOFF_STATE.md` in some sandboxes; the planning container has none);
steps confined to `desktop/` need only a JDK. Estimates are engineer-weeks.

### 6.1 Linux desktop (moderate, 8w) — the proving ground

**LX-1 Host lane, pure core first (1.0).** `desktop/settings.gradle.kts` and `desktop/core-host/` map `domain/`, `contracts/kotlin`
and the domain tests by `srcDir` (the pattern `core-engine/build.gradle.kts` already uses for `../contracts/kotlin`); JVM only, no
AGP; add `org.json:json` explicitly, as the Android runtime uses its bundled variant while the tests already use `20231013` (test-only today, so
`checkLicense` has not scanned it; a runtime use must pass the allowlist); exclude the two back-edge files for now. *CI:* `.github/workflows/desktop-host-core.yml` with a root-unchanged `git diff --exit-code`
check. *Done when:* the host JDK compiles the mapped sources and runs the mapped domain tests, with the count reconciled against the
1549 baseline. *Container:* `CONTAINER-BUILD-ONLY` locally, `CI (hosted VM)` once the lane runs.

**LX-2 Spikes (1.0), each recorded pass, fail or unknown in `HANDOFF_STATE.md`.** S-FB1 Room-KMP with `BundledSQLiteDriver` on
`jvm()` **at the current pins** (Kotlin 2.1.0, KSP 2.1.0-1.0.29, Room 2.7.1), including schema-export handling and AppDatabase v8 built
through the context-free builder; Room 2.8 only if OQ-17 allows. S-FB2 the FTS5 `SearchDatabase` and the Room database open in one
process. S-FB3 `System.load` of a Linux CPU build of the llama shim, and separately both ggml-bearing libraries (llama and sd) loaded in
one JVM (unknown). S-FB4 Compose Desktop draws one room with the existing atoms (look is NDV). Where: `desktop/spikes/`,
`.github/workflows/desktop-linux.yml`. A failed spike is `BLOCKED(<reason>)` (R7), never a workaround. If S-FB1 fails, SQLDelight
for the tree is a fallback that **amends the do-not-unify rule**, so it needs an owner ruling first (proposed `FB-RAT-PORT-NEW-3`).

**LX-3 Seam-enabling refactors (1.0), Android-side PRs, Android gate green each.** (a) Invert `AuditedAuthorityEngine` to `ReceiptStore`
and `CiActionsExecutionProvider` to `GitTransport` through small interfaces in `domain/`. (b) Introduce paths, key-value and secret-vault
interfaces (interim local interfaces that F6 later replaces) in place of `Context` across the 20 `Context`-taking `data/` classes, with Android implementations that
keep today's file and prefs formats. (c) Put `ImageStore`, `LocalModel`, `LocalModelStore`, `ProviderStore` behind interfaces for
`inference/`. (d) Break the `LedgerStore`/`ConversationsStore` to `ui.state` edges. *Done when:* the host lane compiles the two excluded
files unchanged and the Android gate stays green.

**LX-4 Module extraction, owner-gated (0.75; question 3, proposed `FB-RAT-PORT-NEW-2`).** A new KMP module (`androidTarget` + `jvm()`)
beneath `:core-engine` takes `domain/`, `contracts/`, `inference/` and the pure parts of `data/`; a second takes the portable `ui/`. `:core-engine`
stays a `com.android.library` with `dist` flavors and depends on them, so the downstream pin and flavor resolution still work. A port may not
edit `ci.yml` (R3), so the extraction PR must keep `:core-engine` tasks that still run the moved tests, or the owner approves a gate change as its
own PR. *Done when:* the Android gate runs the same test count and the host lane retargets to the moved sources.

**LX-5 JVM actuals (1.0).** In `desktop/`: XDG paths; key-value store as the same plain JSON files; secret vault on Secret Service/libsecret with
a passphrase-encrypted-file fallback **labelled in the UI** (proposed `FB-RAT-PORT-NEW-4`, needs OQ-22); the Room-KMP factory per LX-2; a JVM
container replacing `AppContainer`; coroutine jobs and a tray notification in place of the foreground service; the F2 crash-recovery JVM
variant, or none (question 13). *Done when:* headless tests cover every actual and the container builds without Android classes.

**LX-6 Compose Desktop head (1.75).** `desktop/app/` reuses the extracted `ui/` after replacing `LocalContext` (17 files), `BackHandler` (9),
`viewModel()` (10), `R.font`/`R.drawable` (2, compose-resources) and the `android.graphics` use (3) with Skia calls. The Compose and markdown
pins stay in lockstep (OQ-17). Same room grammar, no new rooms, gestures or Z levels; pointer and keyboard as accelerators only; state still
shown by material; violet/cyan with shape and label. A desktop screenshot or UI-test job is added or render stays unverified. *Container:* compile
yes, run no; look and feel NDV.

**LX-7 Native engines (0.5, from F8).** llama.cpp (sd.cpp optional) built by CMake for Linux x86_64 on `ubuntu-22.04` (glibc baseline), the shim's
`<android/log.h>` behind `#ifdef __ANDROID__`. llama.cpp stays at `5343f450` until F8's pin is validated on a device, because the shim is written
against that revision. *Done when:* the libraries build in hosted CI and a load-and-tokenize smoke passes; no model download in CI (R5) and no
speed claim.

**LX-8 Packaging (0.5).** jpackage app-image on a jlinked Temurin 21 (`--release 17` bytecode), a tarball with `install.sh`, a Flatpak manifest
that consumes the prebuilt app-image (Flathub builds offline); deb and AppImage secondary. No in-app updater (rule 1). No Flatpak id before a `NAMES.md`
row (R11). Where: `packaging/linux/`, package lane in `desktop-linux.yml`. Install and behaviour on a Steam Deck or other Linux host are NDV
(`DEVICE_CHECKLIST_LINUX.md` rows from F11).

**LX-9 Capability row (0.5).** `LOCAL_DESKTOP` in `ExecutionTargetType` and desktop rows in the capability manifest and its schema; stubs say so in the UI
(proposed `FB-RAT-PORT-NEW-5`). Code lands only after the owner rules.

### 6.2 Ubuntu Touch (reframe, 8w; Web Studio unestimated)

**UT-1 Gates, no code (1.0).** OQ-1 device or an explicit CI-only waiver; OQ-21; the Web Studio decision (question 8); a `NAMES.md` row for the click name.

**UT-2 Click scaffold (2.0).** `ubuntu-touch/` from F7's webapp template: `manifest.json.in`, `apparmor.in` (common policy groups only), `clickable.yaml`;
`.github/workflows/ubuntu-touch.yml` (Clickable in a digest-pinned image; compile-only on PRs). The assumption that a 24.04-1.x click installs on 24.04-2.x is
recorded as an assumption. *Done when:* an unsigned arm64 click builds in hosted CI (`CI-APPROX — NOT DEVICE EVIDENCE`); the container cannot build one.

**UT-3 Web Studio payload (1.5).** The static client consumed as a read-only payload built elsewhere; no loopback listener unless the owner approves (R5, OQ-15);
the golden canonicalization vectors must match byte for byte (`BROWSER-HEADLESS`).

**UT-4 Content Hub (1.5).** Loop packages and drafts in and out as files via `content_exchange`; no credentials in the click; drafts persisted continuously because
Lomiri freezes unfocused apps within seconds. NDV.

**UT-5 Evidence and docs (1.0).** `DEVICE_CHECKLIST_UT.md` rows; a README labelled per R12 ("a Web Studio shell for Ubuntu Touch, not the Android app"); a Waydroid note
stating it is owner-device evidence only, never a port.

**UT-6 Store prep, disabled (1.0).** An OpenStore listing template (open source, common groups), no publish.

*Not planned:* a QML/Qt client, including the program's jlinked-JVM-behind-QML shape used for other repos (LGPL); a Compose Desktop click (needs X11 under XMir and the
`unconfined` template, manual review); on-device inference or key storage on UT; and a Kotlin/Native-plus-QML native app (24+ weeks, licence exception).

### 6.3 iOS / iPadOS (hard, 20w; iPad first)

**IOS-1 Domain and contracts to `commonMain` (8.0).** `java.time` to kotlinx-datetime, `org.json` to kotlinx-serialization, the `java.text` users (`Segmenter`/`LexicalSearch`,
`ConversationFilters`, `LocaleFormat`) to platform actuals, `WatchService` and `ProcessBuilder` out of the iOS source set (no local process). The golden vectors and
`scripts/canon_reference.py` must stay byte-identical. The 159 domain test files move to `commonTest` where they can. The same move is what a Kotlin/JS Web Studio core
(`FB-RAT-WEB-010`, undecided) would need. *Evidence:* `jvmTest` plus `iosSimulatorArm64Test` on `macos-latest` (`CI (hosted VM)`, `SIMULATOR`).

**IOS-2 Transports (2.0).** OkHttp and SSE to Ktor (Darwin engine) in 9 files; sshj to libssh2 or NMSSH behind `RemoteTransport`. The request builders stay.

**IOS-3 Engines (4.0).** llama.cpp xcframework with Metal through cinterop; foreground-only inference that stops Metal work on resign-active; model tiers re-gated against jetsam
limits (unknown per device, NDV). sd.cpp has no documented iOS build, so on-device image generation is out unless the owner wants a Core ML or MPSGraph route (not estimated).

**IOS-4 Persistence (1.0).** Room-KMP on iOS with FTS5 in the bundled driver (an assumption to test first); `isExcludedFromBackup` on ledgers and models so iCloud Backup is not silent
egress; Keychain `WhenUnlockedThisDeviceOnly`, never synchronizable (OQ-22).

**IOS-5 CMP iOS shell (3.0).** `apple/ios/` (XcodeGen head from F9/F10), iPad first with the keyboard and pointer accelerators, Share Extension and App Intents feeding `SharedIntake`;
overlay, assist, USB and APK-install tiers are stubs that say so. `.github/workflows/ios.yml` on `macos-latest`: simulator build with `CODE_SIGNING_ALLOWED=NO`.

**IOS-6 Apple pipeline and review (2.0).** `PrivacyInfo.xcprivacy`; review risks to disclose and prompt for (downloaded executable code, large model downloads, personal data reaching third-party
AI, BYOK key flow); TestFlight's automatic crash-report egress disclosed and accepted or avoided (OQ-2); signing jobs as disabled templates; unsigned `.xcarchive`. Device behaviour NDV.

### 6.4 macOS (straight, 3w) and 6.5 Windows (straight, 3w)

**MAC-1 Metal engines (0.5).** `GGML_METAL` builds through F8 on `macos-latest`, `.dylib` loader; compile only in CI, Metal runtime behaviour NOV (no Mac).

**MAC-2 Secret custody (1.0).** Data-protection Keychain or a file key wrapped by a Swift helper (`apple/macos-helper/`), never the `security` CLI into the login keychain; the reported tier shown in the UI (OQ-22).

**MAC-3 `.dmg` (0.5).** jpackage on `macos-latest`, minimum hardened-runtime entitlements, `.github/workflows/desktop-macos.yml`; notarisation and signing are disabled templates until OQ-3; UNSIGNED.

**MAC-4 Intake (0.5).** Services/Share and a URL scheme into `SharedIntake` (scheme name needs a `NAMES.md` row).

**MAC-5 Evidence (0.5).** `DEVICE_CHECKLIST_MACOS.md` rows, all NOV until a Mac exists.

**WIN-1 Prerequisites (0.25).** Add a `.gitattributes` marking the golden vectors and fixtures `-text` (the repo has none; measured today: no tracked path has a Windows-illegal character or a reserved
name, longest tracked path 118 characters, no CR bytes in `schemas/` or `fixtures/`), plus the program's path-lint job, as a normal PR before any `windows-*` lane.

**WIN-2 DLLs (0.5).** win-x64 JNI libraries through F8 on `windows-2025` (CUDA off pending the licence stance); `windows-11-arm` compile and tests only.

**WIN-3 Secret custody (0.75).** DPAPI or Credential Manager vault via JNA; `%LOCALAPPDATA%` (non-roaming) for keys and ledgers (OQ-22).

**WIN-4 Local execution (1.0; question 6, proposed `FB-RAT-PORT-NEW-5`).** `LocalProcessExecutionProvider` hard-codes `/bin/sh -c` and `DeviceRecipes` assumes POSIX quoting. Options: require WSL or
Git-Bash and say so; per-shell adapters with conformance tests; or SSH and CI targets only. **Proposed default until ruled: local targets are a labelled stub and SSH/CI targets work.**

**WIN-5 MSI (0.5).** jpackage MSI (WiX) on `windows-2025` with `.github/workflows/desktop-windows.yml`, winget manifest as a disabled template, no id or GUID before a `NAMES.md` row, no in-app updater; UNSIGNED.
Windows runtime and installer behaviour are NDV (the owner's only Windows machine, OQ-5) and otherwise `CI (hosted VM)`.

## 7. Shared foundation this repo consumes or provides

| Item | Direction | Detail |
|---|---|---|
| F1 hyle-kmp | consumes | Production UI uses in-repo `ui/hyle` and `ui/aeon`; only `hyle-probe` imports `dev.aarso.hyle` tokens, so desktop needs tokens on `jvm()` or an in-module copy (question 13). The AGP/Kotlin lockstep (PT:D-Q, OQ-17) hits this repo's do-not-regress pins. Publishing Hyle as a binary artifact (OQ-17 option C) conflicts with `CORE_PHASES.md` invariant 4 and needs a ruling. |
| F2 crash-recovery KMP | consumes | `AarsoApp.kt` and `MainActivity.kt` call `CrashRecovery`; the JVM Swing/AWT variant (after PT:D-P verification, OQ-27) or the feature is dropped on desktop. |
| F3 cell-shell | not assumed | The program lists Fonebrew core, but no `cell-shell` reference exists in this repo (grep), so adoption is unproven. |
| F4 asom-client | consumes, conditional | Only if question 12 picks HTTP to an ASOM desktop daemon; unusable off-Android until asom rules D25(b) (OQ-7d). Otherwise llama.cpp is embedded as today. |
| F5 kmp-conventions | consumes | The `android.*` import ban is checkable on day one (`domain/` already satisfies it), and F5's licence allowlist must be no weaker than `config/allowed-licenses.json` (LGPL banned). |
| F6 platform-ports | consumes and informs | Secure storage with a reported tier, app dirs, file picker/share, power/thermal probes. LX-3's interim interfaces are shaped to be replaced by F6; `KeystoreSecret` is the Android reference actual. Gate: OQ-22. |
| F7 ubuntu-touch-shell | consumes the webapp template only | OpenStore policy and `DEVICE_CHECKLIST_UT.md`; not the jlinked-JVM QML shell. |
| F8 native-engines pin | consumes and informs | This repo's pins (`5343f450`, `cfbc19d1`) are the revisions the Android build ships and the shim resolves `onToken` by name; moving to F8's pin needs an Android re-verify (owner-verify). The working shim and arm64 flags are the reference. |
| F9 CI matrix, F10 packaging | consumes | By SHA-pinned `uses:` from new workflow files only (R3); the Windows path lint precedes WIN lanes; Flathub's offline rule. |
| F11 evidence and checklists | consumes | Device checklists and `platform-evidence/1` records; progress entries go to `HANDOFF_STATE.md`. |
| F12 web/brand kit | not consumed | |

**Provides to others:** the Room-KMP and FTS5-coexistence verdict from LX-2 (useful to other Android apps that keep Room data); the three-stage seam pattern (map by directory,
refactor behind interfaces, extract); the golden canonicalization vectors as the conformance gate for any second surface (Web Studio, Kotlin/JS, iOS); requirements for F6's key-storage
tier; and a `LOCAL_DESKTOP` capability-row proposal in the contract corpus. The private downstream consumer receives the same seam through the existing submodule pin.

## 8. Open questions for the owner

1. **Desktop vs device independence (OQ-7a, OQ-5).** Is Fonebrew desktop the docked and other-users surface (proposed `FB-RAT-PORT-NEW-1`), and will you use it? *Blocks:* the tier (B or A) and the start of P-LX.
2. **Toolchain pins (OQ-17).** Stage the pin or bump Kotlin/AGP, given the BoM, KSP and sqldelight do-not-regress pins; is Room 2.8 allowed? *Blocks:* LX-2, LX-6, every shared-foundation item.
3. **Module shape and persistence.** Extract new KMP modules beneath `:core-engine` (proposed `FB-RAT-PORT-NEW-2`) or convert in place; Room-KMP first with no tree migration (proposed `FB-RAT-PORT-NEW-3`); should the downstream pin move to a published artifact first (`HANDOFF-CURRENT.md` §12)? *Blocks:* LX-4 onward.
4. **Secret custody per platform (OQ-22).** Are Keychain, Secret Service with a labelled passphrase-file fallback, DPAPI/Credential Manager and no stored BYOK keys on UT acceptable equivalents of rule 5 (proposed `FB-RAT-PORT-NEW-4`)? *Blocks:* LX-5, MAC-2, WIN-3, IOS-4.
5. **Android-only tiers (OQ-6).** Drop or reframe the assist gesture, overlay, screen-capture OCR (ML Kit is GMS-only), USB flashing, APK install and foreground services off-Android (proposed `FB-RAT-PORT-NEW-5`)? *Blocks:* LX-9, every UI stub.
6. **Windows local execution.** Which of the three options in WIN-4, and is the proposed default acceptable? *Blocks:* WIN-4.
7. **Ubuntu Touch (OQ-1, OQ-21).** Accept the Click-webapp reframe (proposed `FB-RAT-PORT-NEW-6`); which device, and does it replace the Android phone; is Waydroid an acceptable answer; would you grant an LGPL Qt exception (not proposed)? *Blocks:* the whole UT track.
8. **Web Studio.** Build it (graph library, static hosting under `FB-RAT-LBX-009`), and rule `FB-RAT-WEB-010` (one shared implementation vs two)? *Blocks:* UT-3, IOS-1's Kotlin/JS option.
9. **iOS (OQ-2).** Accept the ~26k LOC `commonMain` rewrite, no local process, foreground-only inference, iPad first; Developer Program; delivery route without a Mac. *Blocks:* the P-iOS wave.
10. **CI budget, visibility, release signing (OQ-20, OQ-3).** Fund minutes (macOS and Windows lanes), keep the repo public, or accept Linux-only CI; who signs releases (`FB-RAT-EXE-011` is DEFERRED)? *Blocks:* every non-Linux lane and any package lane.
11. **Identity (OQ-25).** Run Sprint R before minting Flatpak, bundle, MSI and click ids? *Blocks:* LX-8, MAC-4, WIN-5, UT-2, IOS-5.
12. **ASOM on desktop (OQ-7d).** Embed llama.cpp directly or call an ASOM desktop daemon over HTTP; is there an HTTP pairing equivalent of the AIDL rule? *Blocks:* F4 use, `docs/NEXT_SESSIONS.md` item 8.
13. **Hyle and crash-recovery on JVM (OQ-17, OQ-27).** Publish `jvm()`/iOS targets from the Hyle repo, or re-implement tokens in this repo's module? *Blocks:* LX-5, LX-6.
14. **Licensing.** Confirm Apache-2.0 covers the `LICENSE-PENDING` corpus (this plan's file carries no marker, as the marker convention names other directories) and rule `FB-RAT-DIST-003` before bundling a JRE or llama.cpp. *Blocks:* distributable desktop builds.
15. **JGit (`FB-RAT-WS-NEW-1`).** Accept it for desktop, where real local git becomes cheap? *Blocks:* nothing in this plan; it widens the desktop surface.
16. **Device verification (OQ-5, OQ-1, OQ-2).** Which platforms will you verify, on what hardware, in what order? *Blocks:* promotion of any step beyond `PLAN`, `CI (hosted VM)` or `CI-APPROX`.

Resolved by this change unless the owner objects: the plan lives at `docs/PORTING_PLAN.md` (beside `build-plan.md`), with a row in `docs/design/README.md`,
and the decisions it raises are filed as proposals in `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`, never ratified in the plan.

## 9. Sources read

Repo files read for the 2026-10-06 profile: `README.md`, `CLAUDE.md`, `HANDOFF_STATE.md`, `CONTRIBUTING.md`, `TRADEMARKS.md`, `LICENSE`, `NOTICE`, `settings.gradle.kts`, `build.gradle.kts`,
`gradle.properties`, `gradle/libs.versions.toml`, `.gitmodules`, `.github/workflows/ci.yml`, `config/allowed-licenses.json`, `app/build.gradle.kts`, the four `AndroidManifest.xml` files,
`core-engine/build.gradle.kts`, `core-engine/src/main/cpp/{CMakeLists.txt,llama_jni.cpp}`, `sdengine/{build.gradle.kts,src/main/cpp/CMakeLists.txt,src/main/cpp/sd_jni.cpp}`, `hyle-probe/build.gradle.kts`,
`di/AppContainer.kt`, `ui/MainActivity.kt`, `security/KeystoreSecret.kt`, `inference/{InferenceEngine,LlamaCppEngine}.kt`, `data/search/SearchDriverFactory.kt`, `domain/search/Segmenter.kt`,
`data/device/UsbFlasher.kt`, the `flavor/InvocationFeatures.kt` pair, `src/full/.../ScreenCaptureService.kt`, `ui/theme/{Type,Texture}.kt`, `ui/aeon/Aeon.kt`, `contracts/kotlin/ExecutionContracts.kt`;
`docs/{STATE,status,HANDOFF-CURRENT,NEXT_SESSIONS,OWNER_GATES,CORE_PHASES,CORE_FOUNDATIONS,build-plan,WP0_SURVEY,TRACEABILITY_MATRIX}.md`, `docs/design/{README,app-distribution,material-language,rendering-handoff}.md`,
`docs/handoff/{device-independence,hyle-extraction}.md`, `docs/non_ratified/{LICENSE_PENDING,DEFERRED_DECISIONS,EXPERIMENTAL_DECISIONS}.md`,
`docs/ratified/{PRODUCT_DIRECTION_AND_BENCHMARK_BASELINE,DISTRIBUTION_CAPABILITY_SPLIT}.md`, `docs/ratified/loops/{LOOP_DUAL_SURFACE_ARCHITECTURE,LOOP_WEB_STUDIO_SPEC,LOOP_PHONE_AUTHORING_SPEC}.md`.
Additionally read or measured while writing: `docs/WP2_GATE_REPORT.md` §4, `docs/WP11_GATE_REPORT.md`, the counts and greps quoted in §1, §2 and WIN-1, `.github/workflows` listing, and the GitHub API
facts quoted in §1. Program inputs: `Personal-Tracker/PORTING_PROGRAM.md` (§0–§8) and the platform briefs under `Personal-Tracker/porting/platforms/`.

## Owner rulings and the proposed line (added 2026-10-07)

Status: PLAN. Nothing here is built, run on a device, signed or submitted. The program-level plan is Personal-Tracker `PORTING_PROGRAM.md` ([PR #10](https://github.com/mbaliga/Personal-Tracker/pull/10)), which holds the owner's rulings and section 5A, the proposed port / no-port line. The cells, estimates and open questions above are this repo's original plan and are unedited. Where the owner has since answered a question, the answer is below. Section 5A is a proposal; the owner has not yet confirmed it.

### Where Android-IDE-core sits in the proposed line (program section 5A.3, a proposal)

| Target       | Verdict | Weeks and flags |
| ------------ | ------- | --------------- |
| Ubuntu Touch | port    | 24w g r         |
| Linux        | follows | 8w g            |
| iOS/iPadOS   | port    | 20w g           |
| macOS        | no-port | -               |
| Windows      | no-port | -               |

Key: `follows` means it ports only as far as the products that depend on it; `exists` means the program reads it as already running there, unverified (finish, verify and sign); flags: `g` gated on a prerequisite, `r` re-estimate or floor, `o` its own program, `s` scope note. The program's P4, P8, P12 and P13 gate whole columns or repos and are not flagged per cell. A port verdict counts the deliverable in the line; where this repo's plan calls a deliverable a reframe (program rule R12) it keeps that label. Tests cited in the reason: (a) the owner said it is needed there; (b) its job is really done on that OS by real users; (c) that OS is where it is sold or its audience is; it has no reason to exist if (x) its surface is absent or untouchable, (y) the capability is forbidden or impossible, or (z) the only form is a thin wrapper or a different product nobody asked for. P-numbers and OQ-numbers refer to the program plan (Personal-Tracker `PORTING_PROGRAM.md`, sections 5A.5 and 8).

Reason: Read from the owner's "native apps on all mobile devices, without exception" (the program plan's reading; OQ-7a is unanswered): UT and iOS are ports. UT is counted at the 24+ weeks Fonebrew's own plan gives for a Kotlin/Native-plus-QML app, not the 8-week Click webapp; that plan lists every native UT shape as not planned (a QML/Qt client, including a jlinked JVM behind QML, because of its LGPL rule; a Compose Desktop click, because it needs XMir and the unconfined template; the 24+ week app, which needs a licence exception), so any of them needs a decision (OQ-36). Linux builds only the KMP seam as a shared build lane; macOS and Windows are not asked for.

### Owner rulings that apply here

- **OQ-17 toolchain (2026-10-06):** "B: staged pin (Recommended)": Kotlin 2.1.20 and Compose Multiplatform 1.8.2 for the first wave, 2.4.x deferred. This repo's do-not-regress pins (Compose BoM 2025.05.01, sqldelight 2.1.0, KSP) stay as written (program rule R2); the pin has not been run in any consumer and the OQ-17 'Also' approvals are unanswered.
- **OQ-38 iOS pin (2026-10-07):** "Move iOS to Kotlin 2.2.21 + CMP 1.9.3 (Recommended)", with the first iOS proof on an explicitly selected Xcode 26.x. The program plan reads this as moving a repo that ships an iOS target as a whole (a Gradle build has one Kotlin version); that reading is not researched, and the bump cost for repos pinned lower is in no figure. This repo's do-not-regress pins (Kotlin 2.1.0, KSP 2.1.0-1.0.29, sqldelight 2.1.0, Compose BoM 2025.05.01) are not changed by this section. Whether they survive an iOS target at Kotlin 2.2.21 is unchecked, and the existing Android gate must stay green (program rule R2).
- **Ubuntu Touch scope (2026-10-06):** "Native only, no substitutes" for Android-only products (the program marks this repo's Ubuntu Touch cell as a substitute). This plan's own Ubuntu Touch cell above (a Click webapp over the static Web Studio) is a substitute the ruling excludes; the proposed line counts a native shape instead. **OQ-21:** "No, native ports only" (Waydroid is not accepted). **Ubuntu Touch keys:** "App-private file allowed" (an app-private file with the weaker guarantee shown in the UI).
- **Ubuntu Touch device:** the owner's answers about the device and the pre-spike are tracked here by id only (OQ-1, OQ-33, OQ-37). Every Ubuntu Touch device gate stays NDV until a device decision is made (OQ-37).
- **OQ-31 Mac (2026-10-06 and 2026-10-07):** "Buy a Mac", and on 2026-10-07 an Apple-silicon Mac, not yet bought; no Apple device gate is called checkable before then. Program directive I-8 (no second owned machine to build on, the rule this plan cites from device-independence.md) is unedited and its drafted amendment PROPOSED-4 is not approved, so that rule stands as written until the owner rules.
- **Apple (OQ-2, 2026-10-06):** "Whatever let's me sell apps on the app store": the paid Developer Program and the App Store are the target channel. TestFlight is not used until the exception to I-1 (OQ-32, drafted as PROPOSED-1, not approved) is approved.
- **OQ-20 CI (2026-10-06):** "Linux-only CI when private (Recommended)": this repo is public, so the ruling does not limit its hosted macOS lanes (the iOS simulator build in step IOS-5); going private would stop them. Actions artifact storage is still exhausted (program rule R6).
- **OQ-5 hardware (2026-10-06):** the owner's answer changes which of their other machines can serve as device gates, so a gate this plan names on specific hardware may be moved or dropped. Which machine carries which device gate is not decided (OQ-33). Directive I-8 stands as written (see the Mac bullet).
- **OQ-22 key custody (2026-10-06):** "OS keystore, weaker fallback shown (Recommended)": Keychain, Credential Manager (DPAPI), Secret Service, a passphrase-protected file or an app-private file on Ubuntu Touch, each with the weaker guarantee stated in the UI. Whether this ruling counts as the repo-local owner approval this plan asks for is for this repo to record; nothing in this section ratifies a repo decision.
- **Fonebrew statement (2026-10-06, free text):** "Fonebrew needs native apps on all mobile devices, without exception." The program plan reads this as Ubuntu Touch and iOS/iPadOS being ports for Fonebrew, desktops not being asked for. The Click webapp shape in the Ubuntu Touch cell above is the kind of substitute the Ubuntu Touch scope ruling excludes; how a native shape squares with the LGPL Qt rule (I-11) is open (OQ-36).
- **Directives (2026-10-06):** "Draft amendments for approval": program directives I-1 to I-12 and rules R1 to R12 are unchanged; PROPOSED-1 to PROPOSED-4 in Personal-Tracker `DECISIONS.md` are drafts awaiting the owner.

### Prerequisites and open questions that touch this repo (program sections 5A.5 and 8)

Prerequisites (program-level; not costed here):

- program P1: The Fonebrew KMP seam (domain, contracts and inference moved to jvmMain, a Room-KMP spike, a desktop head used only as a build and test lane)
- program P3: S-UT1: one headless jlinked-JVM-in-a-click spike driven from QML (not run); needed only if the QML route is taken, which also needs an I-11 exception (program P10) and which this plan lists as not planned
- program P4: A device that can run the 24.04 Ubuntu Touch the program plan targets (OQ-37)
- program P7: The native-engines pin (F8): one llama.cpp and stable-diffusion.cpp commit with per-OS builds
- program P8: An Apple-silicon Mac (OQ-31: chosen on 2026-10-07, not yet bought)
- program P9: OpenStore manual review for reserved or unconfined clicks takes open-source applications only; the rule applies if Fonebrew takes an unconfined route
- program P10: Fonebrew's native Ubuntu Touch shape: its own plan lists every native shape as not planned (a QML/Qt client, including a jlinked JVM behind QML, because of its LGPL rule, I-11; a Compose Desktop click; a Kotlin/Native-plus-QML app at 24+ weeks with a licence exception) (OQ-36)
- program P11: S-UT2: a Compose Desktop (Skiko linux-arm64) window under XMir with the unconfined template on a 24.04 device (Typewright's own UT-5 spike: not run, high failure risk); needed only if the Compose route is taken, which this plan lists as not planned
- program P12: The iOS toolchain pin (OQ-38, ruled 2026-10-07): Kotlin 2.2.21 with Compose Multiplatform 1.9.3 for the iOS targets, proven on an explicitly selected Xcode 26.x; the bump cost for consumers pinned lower is in no figure; for this repo the cost includes its do-not-regress pins (see the OQ-38 bullet above)
- program P14: Fonebrew OQ-7a: does a desktop Fonebrew square with its device-independence document, and will it be used

Owner questions in the program register that concern this repo (status as of 2026-10-07):

- OQ-1 (ruled): Ubuntu Touch device (tracked here by id only)
- OQ-2 (ruled): Apple Developer Program and the delivery route
- OQ-5 (ruled): Hardware stance
- OQ-7 (open): Repo-local gates, clause (a): does a desktop Fonebrew square with its device-independence document, and will it be used
- OQ-17 (ruled): Toolchain pins: the pin is ruled; the "Also" approvals (converting shared modules to kotlin("multiplatform"), asom's no-KMP rule staying asom-local) are unanswered
- OQ-20 (ruled): CI minutes, storage and repo visibility
- OQ-21 (ruled): Waydroid as the Ubuntu Touch answer
- OQ-22 (ruled): Secret custody per platform
- OQ-31 (ruled): CI for App Store builds; which Mac
- OQ-32 (open): Exception to I-1 for TestFlight and App Store crash reports
- OQ-33 (open): Hardware details still open
- OQ-36 (open): Fonebrew on Ubuntu Touch: the native shape
- OQ-37 (answered in part): A second Ubuntu Touch device
- OQ-38 (ruled): iOS toolchain pin

When the owner confirms or changes the line, this repo's original cells above stay as the engineering detail; only the verdicts and re-costs in program section 5A change.
