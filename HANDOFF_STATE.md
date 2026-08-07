# HANDOFF_STATE.md — android-ide-core

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

Resume seam for the Fonebrew handoff-pack build-out (`fonebrew_handoff/` pack, prepared
2026-08-07). Read this file first in any future session touching this work before re-reading the
whole pack.

## Build environment note (found during WP-2, worth knowing immediately)

A real Android SDK + JDK toolchain IS obtainable in this sandbox — `scripts/setup-android-sdk.sh`
works (dl.google.com is reachable), and `export ANDROID_HOME=<sdk path>` plus `echo
"sdk.dir=<sdk path>" > local.properties` gets Gradle running. **One non-obvious fix is required:**
the sandbox's default locale is POSIX/C, not UTF-8 (`locale` shows `LC_CTYPE="POSIX"`), which
breaks the Kotlin compiler on any file containing a backtick-quoted test name with a non-ASCII
character (an em dash, in the case that surfaced it) — `InvalidPathException: Malformed input`.
Fix: `export LANG=C.utf8 LC_ALL=C.utf8` (that locale is preinstalled) before invoking `./gradlew`.
With that fix, `:core-engine:testFullDebugUnitTest` runs for real: **1489 tests, 0 failures, 1
ignored** as of WP-9's completion (1455 at the end of WP-8b, 1410 at the end of WP-8a) — this is
the live baseline every subsequent WP should keep green, not the guessed "868 tests" figure
`CLAUDE.md`/`docs/STATE.md` still quote (stale, pre-dates this session's additions).
`Android-IDE-Studio`'s `core` submodule is repointed at this repo's `claude/fonebrew-development-
clzu43` branch (not `main` — see that repo's `settings.gradle.kts` pin comment and this repo's
WP-2 gate report §4); its own CI (`mbaliga/Android-IDE-Studio#86`) is currently red on an
account/repo-access issue unrelated to any code here (a private cross-repo submodule clone
failing with "Repository not found" — needs an owner-level PAT/deploy-key fix, see that PR's
comment thread) — not something a further code change in this repo can fix. The NDK auto-install (`ndk;28.2.13676358`) does fail in this sandbox and
the native `assembleFullDebug`/CMake path is NOT verified here — the JVM unit-test gate does not
need it and works around it fine; do not spend time chasing the NDK unless a native build is
actually required. `git submodule update --init hyle-design-system` (the native llama.cpp/
stable-diffusion.cpp submodules are NOT needed for the JVM gate and are large — skip them) is
required before Gradle can resolve `dev.aarso:hyle:0.2.0`.

## WPs done, this repo

- **WP-0 (preflight survey)** — DONE. `docs/WP0_SURVEY.md`. Read-only, no code changes. Key
  corrections to the handoff pack found: real app code lives under `core-engine/`, not `app/`
  (both `CLAUDE.md` files in this constellation are stale on this point); on-device search is
  already fully wired into the UI (the pack's own context doc claimed it was not); the real CI
  gate is `./gradlew --no-daemon :core-engine:testFullDebugUnitTest :core-engine:testPlayDebugUnitTest :core-engine:checkLicense`
  (differs from both CLAUDE.md files); no JGit dependency exists yet (pure REST git APIs).
- **WP-1L-G0 (freeze the four blocking loop concepts)** — DONE. `docs/ratified/loops/
  LOOP_FROZEN_CONCEPTS_WP1L_G0.md` + `schemas/loops/registries/{floop-container-format,
  semantic-digest,canonicalization,loop-validation-rules,capability-ids,
  model-capability-vocabulary}.v1.json` + golden canonicalization vectors at
  `schemas/loops/fixtures/canonicalization/` (6 vectors, real computed SHA-256 digests, proven
  deterministic across two independent generator runs). This gate is declared "absolute" by the
  handoff pack's master prompt — nothing loop-related is testable without it. Kotlin contracts
  consuming these registries do NOT exist yet — that is WP-1L proper, not this gate.
- **WP-1 (non-loop contract corpus)** — DONE, gate **YELLOW** (green on every testable item, 2
  named non-blocking gaps). Full detail: `docs/WP1_GATE_REPORT.md`. Summary: 207 files (12
  ratified specs, 4 non-ratified registers, 30 JSON schemas across 6 domains — common, workspace,
  execution, authority, devices, integrations — 6 Kotlin contract files, 155 fixture files).
  30/30 schemas structurally valid, 88/88 cross-file `$ref`s resolve, 130/130 fixtures behave
  exactly as required, traceability matrix regenerated with 101/101 in-scope decision IDs
  assigned to exactly one artifact (0 dangling, 0 double-owned). One real Kotlin lexical bug
  found and fixed (`IntegrationContracts.kt`: a KDoc comment containing a literal `*/` sequence
  in its own prose prematurely closed the doc comment — fixed by adding a space). **Two items
  UNVERIFIED, honestly, both structural sandbox limits, not silently skipped:** Kotlin
  compilation (no `kotlinc`/Gradle toolchain in this sandbox — run
  `./gradlew :core-engine:compileFullDebugKotlin` or equivalent against
  `contracts/kotlin/*.kt` once real Gradle is available); unknown-field round-trip preservation
  (needs a real Kotlin deserializer to prove; structural design evidence — matching
  `unknownFields` fields on both schema and Kotlin sides — is present but not proof). **One named
  non-blocking format gap:** the 5 integration-domain docs cite decision IDs as short-form
  `INT-NNN` instead of the corpus-standard `FB-RAT-INT-NNN`; content coverage is complete, only
  the citation string format is inconsistent — left unfixed to avoid mis-editing ~90 citation
  instances under time pressure; flagged for the next session.
- **WP-1L (loop contract corpus v2.1)** — DONE, gate **YELLOW→fixed to effectively green**. Full
  detail: `docs/WP1L_GATE_REPORT.md`. Summary: de-duplicated the 12 dual-surface specs (each
  shipped as a first-draft-plus-contradictory-second-pass) into `docs/ratified/loops/*.md`;
  resolved the 3 conflicting `.floop` layouts and 2 conflicting import state machines into the
  single 11-state machine, **verified byte-identical** between `LOOP_IMPORT_ACTIVATION_CONTRACT.md`
  and the `loop-installation.v1` schema enum; resolved the P0-vs-P1 Registry contradiction to P1
  (matches the no-hosted-marketplace scope boundary); renumbered the docx-corrupted ordered lists.
  Emitted 21 loop JSON schemas (5 groups), 8 Kotlin files (`LoopDefinitionContracts.kt` through
  `LoopRuntimeContracts.kt` — `LoopContracts.kt` from the base pack is superseded, not kept
  alongside), 169 fixture files, and `AMENDMENTS.md` (the `FB-RAT-INT-003` supersession line for
  `FB-RAT-MKT-001`, the 3 undeclared v2.1 deltas, the `Write` mapping row, the
  `LoopRunState↔ExecutionState` mapping, the `derivesFrom` table, the `StudioApp`/`WebLoopStudio`
  glossary fix, and the proposed-new-decisions section). 21/21 schemas valid, 127/127 valid
  fixtures pass, 43/43 invalid rejected, 42/42 adversarial carry substantive `.expected.txt`, 0
  dangling `LOOP-*` rule-code references. **Two genuine corpus defects the gate found were fixed
  post-gate, not left flagged** (exact source text was already in hand from the register, so this
  was transcription, not invention): `FB-RAT-LBX-002` was dangling (both
  `LOOP_PHONE_AUTHORING_SPEC.md` and `LOOP_DUAL_SURFACE_ARCHITECTURE.md` pointed ownership at each
  other) — fixed by correctly declaring it ACCEPTED in `LOOP_PHONE_AUTHORING_SPEC.md` per the
  dual-surface register's own repository-effect column, plus adding that document's
  previously-missing decision-IDs-cited footer; 14 DEFERRED/EXPERIMENTAL/REJECTED loop IDs were
  cited as living in `docs/non_ratified/*.md` but had no row there — added all 14 (plus
  `FB-RAT-LBX-008`, not flagged but also missing) with verbatim register text.
  **Superseded during WP-2:** Kotlin compilation is no longer UNVERIFIED — see the "Build
  environment note" above and the addenda in both `docs/WP1_GATE_REPORT.md` and
  `docs/WP1L_GATE_REPORT.md`. Real compilation caught a genuine cross-file `ValidationFinding`
  redeclaration between `LoopAuthoringContracts.kt` and `LoopPackageContracts.kt` that three
  separate WP-1L authoring agents had each individually flagged as a known risk but left
  unresolved (correctly, since reconciling another file's declaration was outside each one's
  assigned scope) — fixed via a `typealias`, zero regressions, full gate re-run confirmed green.
  Unknown-field round-trip remains genuinely UNVERIFIED (needs a real runtime round-trip test, not
  just a successful compile).
- **WP-2 (shared envelope/error/receipt runtime library)** — DONE, gate **GREEN**. Full detail:
  `docs/WP2_GATE_REPORT.md`. Summary: implemented (not just declared) `IdGenerator` (real ULID),
  `Digest` (SHA-256 `IntegrityRef`), `JsonInterop` (unknown-field preservation over `org.json`),
  `EnvelopeCodec` (encode/decode for `ContractEnvelope`/`ErrorEnvelope`/`CapabilityManifest`/
  `ArtifactRef`/`MigrationPlan`/`ConformanceSuite`), `MigrationRunner` (fail-closed step
  execution), and a Room-backed `ReceiptStore` (idempotent append-only, `AppDatabase` v5→v6),
  wired into `AppContainer`. `core-engine` JVM gate: 1250 tests, 0 failures (up from 1214). Two
  real bugs found and fixed during verification: Kotlin's nested-block-comment behavior
  (`IdGenerator.kt`/`EnvelopeCodec.kt` KDoc prose containing a literal `/*` from a glob-style
  path reference cascaded into an "Unclosed comment" compile error) and a `MigrationRunnerTest`
  fixture that violated `DataMigrationSteps`'s own non-empty-steps invariant (test bug, not a
  `MigrationRunner` bug). **Also wired into the real second consumer**, correcting the pack's
  literal "wire into Aarso" instruction per WP-0's finding that `Aarso` isn't a consumer at all —
  see `docs/WP2_GATE_REPORT.md` §4 for the `Android-IDE-Studio` submodule repoint (its `core` pin
  was stale, pointing at a since-merged PR's commit; repointed to this branch, gate re-verified
  green there too: `:app:testFullDebugUnitTest` 9/9, `:hyle:test` 10/10, both 0 failures). Honest
  gap: the WP-2 brief's literal gate ("receipt store survives simulated process kill") is only
  proven at the idempotent-append/JVM level here, not via a real forced-kill/replay — that's
  WP-3's journal/`RecoverySnapshot` machinery's job, not WP-2's.
- **WP-3 (Workspace Kernel: local provider + journal)** — DONE, gate **GREEN**. Full detail:
  `docs/WP3_GATE_REPORT.md`. Summary: `DocumentBufferMachine`/`WorkspaceProviderMachine` (both
  named state tables, WORKSPACE_KERNEL_SPEC.md §3.1/§3.2, fail-closed, exhaustively tested both
  directions); `LocalWorkspaceProvider` (real `java.io.File`-backed `WorkspaceProvider`,
  content-digest revisions, real `WatchService`-backed `watch()`); `BufferReplay` (deterministic
  journal-to-content reconstruction, all 7 `JournalOpType`s); `WorkspaceCodec` (JSON round-trip,
  same unknown-field-preserving pattern as WP-2's `EnvelopeCodec`); Room-backed
  `RoomWorkspaceJournal` (`append`/`checkpoint`/`restore`, `AppDatabase` v6→v7); and
  `AgentEditJournalAdapter` — the FB-RAT-WS-005 "one journal for human and agent edits" seam,
  built as a **decorator over `RepoWorkLoop`'s existing `ChangeCommitter`**, not a rewrite.
  `core-engine` JVM gate: **1297 tests, 0 failures** (up from 1250). The headline result: a
  **100-iteration simulated forced-kill suite** (`RoomWorkspaceJournalTest`) — randomized edit
  sequences, randomized kill points, a second independent `RoomWorkspaceJournal` instance over
  the same backing store standing in for "process restarted" — zero loss, zero duplication,
  byte-identical content reconstruction, all 100/100. One real compile bug found and fixed
  (`kotlinx.coroutines.async`/`delay`/`withTimeout` called via FQN inside `runBlocking` failed to
  resolve their implicit `CoroutineScope` receiver — fixed by importing normally); the
  `WatchService`-backed test was specifically re-run twice to rule out timing flakiness, passed
  both times. **Flagged, not silently patched:** a genuine WP-1 contract gap (neither
  `BufferJournalEntry` nor `DocumentBuffer` carries a `workspaceId`, so `checkpoint(workspaceId)`
  needs a bufferId→workspaceId link `WorkspaceJournal`'s own interface doesn't provide) worked
  around via a new, explicitly-documented `BufferRegistryEntity`/`registerBuffer()` beyond that
  interface. **Also wired into the real second consumer** the same way WP-2 was: `Android-IDE-
  Studio`'s `core` submodule stays pinned to this branch (already repointed in WP-2), so its next
  CI run against this branch picks up WP-3 automatically once that PR's unrelated CI-access issue
  (see the build-environment note above) is resolved.
- **WP-4 (Execution Contract + Authority engine)** — DONE, gate **GREEN**, compiled and passed on
  the first real Gradle attempt. Full detail: `docs/WP4_GATE_REPORT.md`. Summary: `AuthorityEngine`
  is the real policy-engine implementation `CAPABILITY_AUTHORITY_MODEL.md` itself named as future
  work — default-deny, purpose/target/time binding, no transitive delegation (verified by a
  correctness property, not just a check: the engine only ever reads the REQUESTING principal's
  own grants, never an ancestor's). `LocalProcessExecutionProvider` is the real `LOCAL_ANDROID`
  `ExecutionProvider` (lifecycle/cancellation/reconnect against real `/bin/sh -c` processes in this
  JVM gate, mirroring WP-3's `LocalWorkspaceProvider` testing pattern). `AuditedAuthorityEngine`
  wires authority decisions through WP-2's `ReceiptStore`; `SecretHandleBroker` + a reference
  in-memory implementation; `SecretRedactionScanner` proves the "secret-redaction sentinel test"
  gate (zero hits against real live secret values, and proven non-vacuous). `core-engine` JVM
  gate: **1339 tests, 0 failures** (up from 1297). The full privilege-escalation fixture pack
  (default deny, child-exceeds-parent, transitive delegation, purpose-crossing secret use, expired
  grant, delegation widening, grant/capability rung mismatch) is now real, passing engine tests,
  not just static JSON fixtures. One real design bug caught and fixed *before* ever running Gradle
  (a scope-mismatch-vs-rung-mismatch reason-code conflation in the engine's own draft — see
  `docs/WP4_GATE_REPORT.md` §3). **Flagged, not silently invented:** `RequireStrongerAuthority`
  needed an explicit `escalateToRung` mechanism to be reachable at all, since a single `fb.*`
  capability always sits at exactly one registry rung (§4 of that report).
- **WP-5 (SSH + CI providers)** — DONE, gate **GREEN**, compiled and passed on the first real
  Gradle attempt. Full detail: `docs/WP5_GATE_REPORT.md`. Summary: `SshExecutionProvider` adapts
  the already-real `domain/remote` SSH spine (`RemoteSessionDriver`/`RemoteTransport`/
  `KnownHosts`) into `ExecutionProvider`, serving both `SSH_HOST` and `RASPBERRY_PI` (a Pi target
  is SSH-reachable, matching the existing Arduino-via-Pi precedent — one provider, not two).
  `CiActionsExecutionProvider` adapts the already-real `domain/builds/CiTrigger` dispatch/
  list-runs request-builders into `ExecutionProvider`, serving both `GITHUB_ACTIONS` and
  `GITEA_ACTIONS`. `core-engine` JVM gate: **1350 tests, 0 failures** (up from 1339). A failed CI
  run correctly reaches `FAILED_SIDE_EFFECTS_POSSIBLE`, never `FAILED_SAFE` (the dispatch's
  external side effect already happened by the time a conclusion is known); `cancel()` on the CI
  provider honestly reports `UNSUPPORTED` rather than pretending (no cancel-run request builder
  exists in `CiTrigger` yet). One real design bug caught and fixed **before ever compiling**: a
  first draft of `SshExecutionProvider.start()` would have reported `RUNNING` even when the user
  rejected an unfamiliar host's trust key (`RemoteSessionDriver.open()` returns normally, not by
  throwing, on a rejected trust decision) — caught by re-reading that already-real code's control
  flow carefully rather than assuming it, fixed, regression-tested. **Honest gaps:** no real
  loopback sshd exists in this sandbox (checked directly) so SSH tests fake the transport layer
  only, same pattern `domain/remote`'s own pre-existing tests use; neither new provider is wired
  into `AppContainer` (both need a resolved host/credential a UI surface must supply, not
  something to guess); an SSH `WorkspaceProvider` (WP-3's domain, same spine) is a flagged
  follow-up, not built this pass.
- **WP-6 (search contracts + wiring)** — DONE, gate **GREEN**, compiled and passed on the first
  real Gradle attempt, zero regression on the 30 pre-existing `LexicalSearchTest` tests. Full
  detail: `docs/WP6_GATE_REPORT.md`. Summary: `contracts/kotlin/SearchContracts.kt` — the first
  formal search contract this build-out has written (no prior ratified spec existed for this
  domain); does NOT redefine an embedder interface (`dev.aarso.embedding.Embedder` already
  satisfies that requirement, checked before writing anything). `WorkspaceSearchProjector` maps a
  WP-3 `BufferSnapshotEntry` into the exact `SearchDoc` shape the real, already-shipped
  `LexicalSearch` engine consumes — literally reusing the existing ranking code, not forking it.
  `WorkspaceSearchIndex` is a deliberately in-memory "minimal functional search surface" (not a
  new SQLDelight table — `CLAUDE.md`'s own rules flag that schema as a "do not unify" hazard zone,
  and no live editing UI exists yet to populate a persistent index from anyway).
  `SemanticSearchProvider` + `DisabledSemanticSearchProvider` is the honest disabled-flag default
  the brief asks for — no speculative embedding pipeline invented. `core-engine` JVM gate: **1359
  tests, 0 failures** (up from 1350).
- **WP-7 (Integration lanes R1-R4: CSApp/Assay/Studio)** — DONE, gate **GREEN**. Full detail:
  `docs/WP7_GATE_REPORT.md`. Summary: implements (WP-1 already ratified the contracts) R1 (shared
  grammar codec + `ImportReceiptBuilder`), R2 (CSApp file lane — `CsAppImportLane`: whole-source
  duplicate-snapshot short-circuit, per-issue new/changed/conflicting/duplicate classification,
  tolerant per-record parsing so a bad `severity`/timestamp rejects only that one record), R3
  (Assay repo lane — `SarifParser` for the exact SARIF 2.1.0 profile subset ASSAY_REPO_CONTRACT_V1.md
  §4 pins, `AssayImportLane`: `runId`-keyed dedupe, finding-file digest verification,
  `completeness != COMPLETE` partial-source flagging). **R4 (Studio continuity) is correctly out of
  scope** — the responsibility matrix explicitly puts "Studio priority/boards/incident workflow"
  in Core's own "must not own" column; it belongs in `Android-IDE-Studio`, a separate repo.
  `core-engine` JVM gate: **1394 tests, 0 failures** (up from 1359), all codec round-trips proven
  against the REAL WP-1 fixture files (`fixtures/integrations/valid/*.json`, embedded verbatim),
  not hand-invented test data. One real design bug found and fixed: a first-draft
  `validateSchemaVersion(manifest)`/`validateSchemaVersion(index)` took an already-constructed
  contract object, but `IssuesManifest`/`AssayIndex`'s own `init{}` blocks already reject an
  unsupported major version at construction time — meaning the function could never actually
  observe the condition it existed to detect. Fixed by checking the raw JSON string before
  attempting the strict decode (see that report §2 — the same pattern the CSApp tolerant-parsing
  path already needed for per-record severity/timestamp validation, §3 of that report). A
  background research pass confirmed both `mbaliga/assay` and `mbaliga/csapp` are real but
  pre-alpha (no live manifest/index files exist anywhere yet) — `csapp`'s own GitHub Issue #3
  shows the other side is already aware of and blocked on this exact contract.
- **WP-8 (loop engineering lifecycle around GraphRunner)** — DONE, gate **GREEN**, compiled and
  passed on the first real Gradle attempt. Full detail: `docs/WP8_GATE_REPORT.md`. Summary:
  `LoopRunDriver` drives WP-1L's real 17-state `RunState` machine (`LOOP_ENGINEERING_SPEC_V2.1.md`
  §9) around the already-real `GraphRunner` engine — CREATED→PREFLIGHT→WAITING_BINDING→
  WAITING_AUTHORITY→READY→RUNNING→terminal, every event individually legal (enforced by
  `RunEvent`'s own constructor) AND checked as a full sequence (`assertLegalEventSequence`, a
  belt-and-suspenders test helper). Translates `GraphRunner`'s flat `GraphRunResult` into the full
  `LoopRun` audit record (event log, per-node `NodeAttempt`s, a correctly §9-categorized
  `TerminalReason`). A mid-run cancellation correctly routes through `CANCELLING` (two events),
  not straight to `CANCELLED` (not a legal direct transition from `RUNNING`) — proven by a
  dedicated test. `core-engine` JVM gate: **1402 tests, 0 failures** (up from 1394). **Honest scope
  boundary, not silently skipped:** `WAITING_BINDING`/`WAITING_AUTHORITY` are auto-resolved seams
  this pass (`GraphRunner` has no device-binding or authority concept of its own) — real
  integration with WP-10's Device Broker / WP-4's `AuthorityEngine` is a flagged follow-up, not
  built here. `WAITING_USER`/`SUSPENDED` are unexercised since `GraphRunner` itself has no
  mid-graph pause concept. `SUCCEEDED_UNVERIFIED` maps to `REQUIRED_VERIFIER_INCOMPLETE` as an
  interpretation choice (the closed category vocabulary has no "no verifiers configured" value) —
  flagged, matching the same honesty standard WP-4/WP-6's own interpretation notes set.
- **WP-8a (packages, transfer, import, activation)** — DONE, gate **GREEN**, compiled and passed
  on the first real Gradle attempt. Full detail: `docs/WP8a_GATE_REPORT.md`. Summary:
  `LoopInstallationDriver` drives WP-1L's real eleven-state `InstallationState` machine
  (`LOOP_IMPORT_ACTIVATION_CONTRACT.md` §3, `contracts/kotlin/LoopActivationContracts.kt`) through
  the ten-step import/activation main path — ACQUIRING→SNAPSHOTTED→CONTAINER_VERIFIED→
  PARSED_VALIDATED→COMPATIBILITY_EVALUATED→PREVIEWED→WAITING_BINDINGS→WAITING_AUTHORITY→
  READY_TO_SIMULATE→INSTALLABLE→INSTALLED — or one of five terminal off-ramps, exactly the same
  "adapter around an already-real state machine" posture WP-8's `LoopRunDriver` established,
  except import/activation is genuinely greenfield (nothing pre-existing to adapt, per
  `docs/WP0_SURVEY.md`). Genuinely enforces FB-RAT-IMP-002 ("transfer moves inert bytes... no
  model, tool, shell, remote, device, or side-effecting node may execute during parsing,
  validation, preview, or installation") **by construction**: everything through `PREVIEWED` is
  pure byte/JSON inspection, and the only injected suspend seams (`resolveBindings`/
  `resolveAuthority`/`runSimulation`) are never called before `WAITING_BINDINGS`. `core-engine`
  JVM gate: **1410 tests, 0 failures** (up from 1402). Fail-closed proven per-branch, not just by
  final-state assertion: a digest mismatch is `REJECTED_UNSAFE` *before `parseManifest` is ever
  called* (asserted directly, not inferred); a `BLOCKED`/`UNSUPPORTED` compatibility outcome never
  reaches the binding-resolution seam; declining authority never reaches `runSimulation`. Every
  transition the driver performs is checked against the real `InstallationState.isValidTransition`
  internally (`reject()`'s own `require()`) — the same belt-and-suspenders discipline WP-8's
  `assertLegalEventSequence` established, applied here as an always-on internal invariant instead
  of a test-only helper. `LoopInstallation`'s own strict `init{}` invariants (non-null
  `compatibilityReportRef` from `COMPATIBILITY_EVALUATED` onward; `bindingProfileRef` + a
  terminal-imported `signatureState` + non-empty `receipts` specifically at `INSTALLED`) did real
  correctness work again — same "the contract's own strictness does verification work no fixture
  could" pattern WP-4/WP-7 already demonstrated. One real bug caught while authoring the test file,
  before ever running Gradle: `ManifestSignatureRef`'s constructor was guessed as
  `(id, keyProvenance, fingerprint)`; the real shape is `(path: String, sha256: String)` with a
  `path.startsWith("signatures/")` requirement — fixed. **Honest scope boundary, not silently
  folded in:** §20's package **build** pipeline (freeze/canonicalize/secret-scan/sign/
  build-receipt — the *authoring*-side counterpart to this pass's *import*-side work) is not built
  this pass, flagged as its own future-sized undertaking (a real canonical-JSON serializer matching
  WP-1L-G0's `canonicalization.v1.json` profile plus real secret-scanning); no real compatibility
  evaluator (`evaluateCompatibility` is caller-supplied — the state-machine plumbing is real, the
  semver-range/capability-declaration comparison logic behind it is not); no real binding/authority
  resolution UI or persistence (`resolveBindings`/`resolveAuthority` are seams, same shape as
  WP-8's `LoopRunDriver` gap); no real signature verification (`security/KeystoreSecret.kt` exists
  but isn't wired to this flow — a "signed" package is marked `IMPORTED_SIGNED_VERIFIED` purely
  because `manifest.signatureRef != null`, not because a cryptographic signature was checked); no
  persistence of the outcome anywhere (matches the "no consumer yet" pattern every WP since WP-2
  has left for comparable pieces).
- **WP-8b (phone authoring surfaces)** — DONE, gate **GREEN**, compiled and passed on the first
  real Gradle attempt. Full detail: `docs/WP8b_GATE_REPORT.md`. Summary: builds the JVM-testable
  domain-layer substrate under the five-view phone authoring UI `LOOP_PHONE_AUTHORING_SPEC.md`
  specifies (Intent/Stage/Graph/Node Sheet/Run), not the Compose screens themselves — the same
  domain-first, UI-later-and-owner-verified split WP-3/WP-4/WP-8 already established, explained in
  full in that report's §4 rather than left implicit. Six new files under `domain/loop/authoring/`:
  `TouchConnectionGrammar` (`FB-RAT-PHN-004`, the tap-connection state table replacing drag-a-wire
  as primary); `SemanticDiffProposal` (`FB-RAT-PHN-006`, the AI-proposal review model + four-state
  approve/reject machine, authority-widening operations kept visually separable); `RunViewActionGuard`
  (`FB-RAT-PHN-008`, the Run View's intervention-action legality table — explicitly not the
  canonical `RunState` machine — enforcing "no structural editing during a run" by construction:
  there is no mutation verb in the vocabulary at all); `DraftPersistenceLifecycle` (§13's
  interruption/recovery table plus a real `DraftEditJournal` proving the `FB-RAT-COM-006`
  idempotency-key-dedup property as tested code); `LoopDraftUndoStack` (`FB-RAT-PHN-011` — flagged
  honestly as **PROPOSED, not yet ratified** in the source spec itself, implemented anyway per its
  own fully-specified "proposed shape" since the gap is real, same posture WP-3 took recording
  `FB-RAT-WS-NEW-1` as a proposal rather than a ruling); `StageLinearizer` (§3.2's "Stage View
  total by construction" requirement — per-gateway structurability via a bounded
  single-entry-single-exit check, plus back-edge/cycle classification for bounded repeat groups).
  `core-engine` JVM gate: **1455 tests, 0 failures** (up from 1410), 45 new tests across 7 classes,
  zero regressions. One real bug caught while hand-deriving `StageLinearizer`'s non-structurable
  test fixture, before ever running Gradle: the first draft let a sibling branch's own target node
  become the "rejoin," collapsing a genuine crossing violation into a false-positive structurable
  verdict — fixed by excluding branch targets from the rejoin-candidate set (`docs/WP8b_GATE_REPORT.md`
  §3). **Honest scope boundary:** the five Compose screens themselves are not built this pass —
  this container has no device/emulator and no Robolectric/Compose-UI-test harness in `core-engine`,
  so hand-written screens here would compile at best and be otherwise unverified, exactly the class
  of claim `CLAUDE.md`'s "Environment honesty" rule warns against (§4 of that report); `StageLinearizer`
  is a bounded SESE approximation, not a full RPST/SPQR-tree implementation of the cited papers
  (§6); no AI/Distiller wiring; `DraftEditJournal` is in-memory, not Room-backed (WP-3's journal
  already proved the durable-append pattern generically for a different payload shape); no
  `AppContainer` wiring for any of the six pieces.
- **WP-9 (language lane vertical slices: TypeScript, Python)** — DONE, gate **GREEN** (one real
  test-authoring bug caught by the first real Gradle run, fixed, re-verified green on the
  second). Full detail: `docs/WP9_GATE_REPORT.md`. Summary: genuinely greenfield, like WP-6's
  search domain — no ratified spec or schema corpus for language lanes existed before this pass.
  New `contracts/kotlin/LanguageLaneContracts.kt`: `LspCapability`/`DapCapability` (bounded,
  closed sets), `ToolchainDeliveryMechanism` (five members grounded directly in
  `01_VALIDATION_REPORT.md` §B1/§B3's W^X-exec-restriction and Play-interpreter-carve-out
  findings, not invented — "downloaded native exec" is not a mechanism at all, on any flavor),
  `LanguagePackManifest`/`ToolchainCapsuleManifest`/`TaskDefinition`/`LaunchConfiguration`, and
  `BuiltInLanguagePacks` (concrete TypeScript/Python fixtures fully declared per "wire TS+Python
  as far as JVM-verifiable"; Rust/C++ intentionally bare — no local LSP/DAP capsule reference,
  "contract + REMOTE-mechanism only in this pass" per the brief). Five new files under
  `domain/language/`: `LspSessionMachine`/`DapSessionMachine` (lifecycle state machines derived
  from each protocol's own handshake, plus capability negotiation as a plain intersection —
  neither side's unilateral wish list wins); `LanguageUriMapper` (workspace-relative path ⇄ LSP
  `DocumentUri`, percent-encoding spaces/non-ASCII so a naive string-concat URI, which silently
  breaks the moment a filename has a space, never ships); `DiagnosticsOwnership` (fails closed
  into `Conflicted` when two packs claim the same file extension, never silently picks a winner
  by list order); `ToolchainDeliveryLegality` (the per-mechanism-per-flavor legality table §B3
  requires — **flagged as a reasoned proposal, not an owner-ratified decision**, no `FB-RAT-*` ID
  exists for it yet, same posture as WP-3's `FB-RAT-WS-NEW-1` and WP-8b's `FB-RAT-PHN-011`
  handling). `core-engine` JVM gate: **1489 tests, 0 failures** (up from 1455), 34 new tests
  across 6 classes. The one bug caught was in the *test fixture*, not production code: a
  `DiagnosticsOwnershipTest` first draft under-counted a `findAllConflicts` fixture (missed that
  the real `BuiltInLanguagePacks.CPP` fixture already claims `.c` in addition to `.h`, so both
  were genuinely contested, not just `.h`) — caught by the first real Gradle run, fixed, re-run
  green (`docs/WP9_GATE_REPORT.md` §3). **Honest scope boundary:** no real LSP/DAP client spawns
  an actual subprocess yet (the state machines are the shape-law an adapter must obey, same
  posture as WP-3's `DocumentBufferMachine`); no JSON wire-format codec (the brief didn't ask for
  one, unlike WP-2/3/4/7's domains); no `AppContainer` wiring; no toolchain capsule fetch/
  install/verify (closer to WP-8a's `LoopInstallationDriver` territory than a language-lane
  concern) (`docs/WP9_GATE_REPORT.md` §5).

## What is NOT done yet in this repo

- **WP-10 and WP-11** — not started. See `fonebrew_handoff/06_WORK_PACKAGES.md` for the full
  staged plan and `fonebrew_handoff/06_WORK_PACKAGES.md`'s "Sizing honesty" section for the
  expected multi-session shape of this build-out.

## Deviations from the master prompt worth knowing about

- The differentiator-first reordering (`06_WORK_PACKAGES.md`'s alternate ordering,
  WP-0→WP-1→WP-1L→WP-2→WP-8→WP-8a→WP-8b) was **not** adopted — this session followed the default
  substrate-first numbered order, since the reordering is explicitly an owner decision
  (`08_OPEN_QUESTIONS.md` §E.7), not the session's to make. State this choice here as instructed:
  **substrate-first ordering is in effect; nobody has decided otherwise.**
- No hosted marketplace service, accounts, reviews, or moderation storage has been built or
  planned as buildable — out of scope for this entire build-out per the master prompt.
- No owner-reserved decision in `08_OPEN_QUESTIONS.md` has been decided by this session. Where
  work touched one (e.g. the JGit-vs-libgit2-JNI git-library choice, proposed as
  `FB-RAT-WS-NEW-1` in `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`), it is recorded as a
  **proposal**, not a ruling.

## Open threads for the next session

1. Read `docs/WP1_GATE_REPORT.md` through `docs/WP9_GATE_REPORT.md` in full before touching
   anything those twelve passes produced.
2. Fix the `INT-NNN` → `FB-RAT-INT-NNN` citation-format gap in the 5 integration docs (mechanical,
   low-risk once done carefully with full-document review, not sed-across-the-corpus). Still open
   — not touched by WP-2 through WP-9.
3. Proceed to **WP-10** (Device Broker + flash safety contracts). WP-11 (closeout) follows.
3f. WP-9's language lanes have no real LSP/DAP client spawning an actual subprocess yet (the
    state machines are the shape-law an adapter must obey), no JSON wire-format codec, no
    `AppContainer` wiring, and no toolchain capsule fetch/install/verify — see
    `docs/WP9_GATE_REPORT.md` §5 for the full list. `ToolchainDeliveryLegality`'s per-flavor
    table is a reasoned proposal, not an owner-ratified decision (no `FB-RAT-*` ID exists for it
    yet) — same posture as WP-3's `FB-RAT-WS-NEW-1` and WP-8b's `FB-RAT-PHN-011`.
3a. `LoopRunDriver` (WP-8) has two honest, flagged integration gaps worth revisiting alongside
    WP-10: `resolveBinding`/`resolveAuthority` are auto-resolve stubs, not wired to a real
    Device Broker or `AuthorityEngine` capability check yet (`docs/WP8_GATE_REPORT.md` §2/§5).
3b. `LoopInstallationDriver` (WP-8a) has the same shape of gap plus more: `resolveBindings`/
    `resolveAuthority`/`evaluateCompatibility`/`runSimulation` are all caller-supplied seams with
    no real implementation behind them yet, and §20's package **build** pipeline (the authoring-
    side counterpart to WP-8a's import-side work) is a separate, not-yet-scoped future pass — see
    `docs/WP8a_GATE_REPORT.md` §4 for the full list (also: no real signature verification against
    `security/KeystoreSecret.kt`, no persistence of `InstallationOutcome`).
3c. WP-8b's five Compose phone-authoring screens (Intent/Stage/Graph/Node Sheet/Run) are not built
    — only their domain-layer substrate is (`docs/WP8b_GATE_REPORT.md` §0/§4). Building the real
    screens needs either a device/emulator or a Robolectric/Compose-UI-test harness added to
    `core-engine` (neither exists in this sandbox today) to have any verification loop at all —
    flagged as a real prerequisite, not something to route around with unverified hand-written UI.
3d. `FB-RAT-PHN-011` (undo/redo, WP-8b's `LoopDraftUndoStack`) is implemented but **not ratified**
    — `LOOP_PHONE_AUTHORING_SPEC.md` §14 itself marks it `PROPOSED`. An owner/Amendments-phase
    decision to accept, reject, or fold it into an existing ID is still open
    (`docs/WP8b_GATE_REPORT.md` §5).
3e. `StageLinearizer` (WP-8b) is a bounded single-entry-single-exit approximation of Stage View
    structurability, not the full RPST/SPQR-tree algorithm the cited papers describe, and
    §3.2's own golden corpus of irreducible graphs (its point 5) does not exist in this repository
    yet — flagged as "scoped to WP-1L's fixture work" by the spec itself, still unclaimed
    (`docs/WP8b_GATE_REPORT.md` §6).
4. `WorkspaceSearchIndex`/`SemanticSearchProvider` (WP-6) have no live consumer yet — a Develop-tab
   search surface or a `WorkspaceJournal`-observing auto-indexer is a natural follow-up, not built
   this pass (see `docs/WP6_GATE_REPORT.md` §5).
5. An SSH `WorkspaceProvider` (WP-3's domain) reusing the same `domain/remote` spine
   `SshExecutionProvider` (WP-5) already adapts is a flagged, not-yet-built follow-up — natural to
   pick up whenever a later pass needs remote file access through the Workspace Kernel.
6. **Done, not open anymore:** the CSApp/Assay cross-repo grep `docs/WP0_SURVEY.md` §1(f)
   recommended ran during WP-7 (a background research pass, not a manual grep) — confirmed clean
   (no stray references in any of the four constellation repos beyond the WP-1 contract corpus
   itself) and confirmed both `mbaliga/assay`/`mbaliga/csapp` are real but pre-alpha with no live
   manifest/index data yet to import (see `docs/WP7_GATE_REPORT.md` §5).
7. Two owner-only calls are now ready for a decision, not blocking further build-out but worth
   surfacing: the differentiator-first vs. substrate-first reordering (§Deviations above), and the
   bounded-marketplace-backend phasing question (`08_OPEN_QUESTIONS.md` §E.1) — `LOOP_MARKETPLACE_CONTRACT.md`
   already builds toward the static/Git-registry answer (b) by default per the master brief, but
   the owner has not ruled on it.
8. `Android-IDE-Studio`'s own `CLAUDE.md` (repo map + gate command) is stale — still describes the
   pre-de-fork monolithic `app/` layout and never mentions `core-engine`/`:core`. Not fixed here
   (out of this repo's scope) but worth a fix next time a session works in that repo directly.
9. `Android-IDE-Studio`'s CI (PR #86) is red on a private cross-repo submodule access issue
   (`mbaliga/Android-IDE-core` returns "Repository not found" to the default `GITHUB_TOKEN`) that
   needs an owner-level fix (a PAT/deploy-key secret, or temporarily making the repo public) — not
   something further code changes here can resolve. See that PR's comment thread for the full
   diagnosis; local verification (both repos' real Gradle gates) is green regardless.
10. Real Room-backed JVM testing (a genuine embedded SQLite driver, not the fake-DAO pattern) was
    investigated during WP-3 and found non-trivial with this module's current Room setup (the
    Android-`Context`-requiring `Room.databaseBuilder` overload, not Room's KMP/context-free one) —
    flagged as a possible future toolchain improvement, not attempted mid-WP.
11. WP-4's `AuthorityEngine`/`AuditedAuthorityEngine` have no persisted `Grant`/`Principal` store
    yet (`InMemoryGrantStore`/`InMemoryPrincipalStore` only) — WP-5's SSH/CI providers turned out
    not to need one either (both are stateless adapters over existing spines); still worth a
    Room-backed store the moment a real UI surface needs grants to survive a process death.
12. `CapabilityRegistry.kt` (WP-4) mirrors `schemas/loops/registries/capability-ids.v1.json` by
    hand, manually cross-checked once (all 20 entries match) but not automatically kept in sync —
    a future registry addition (new `fb.*` IDs are additive/MINOR per that file's own `bumpRule`)
    needs a matching manual update here, or a real automated consistency test, whichever a later
    session has time for.
13. Neither `SshExecutionProvider` nor `CiActionsExecutionProvider` (WP-5) is wired into
    `AppContainer` — both need a resolved host/credential a UI surface must supply. See
    `docs/WP5_GATE_REPORT.md` §5 for the full list of honest gaps (no loopback sshd in this
    sandbox, `CiActionsExecutionProvider.cancel()` genuinely `UNSUPPORTED`, no SSH
    `WorkspaceProvider` yet).
14. WP-7's CSApp/Assay import lanes (`CsAppImportLane`/`AssayImportLane`/`ImportReceiptBuilder`)
    have no live pipeline wiring or `AppContainer` consumer, and no real Snapshot-step I/O
    (reading actual files/repo commits) — see `docs/WP7_GATE_REPORT.md` §6 for the full list.

## Commits

See git log on branch `claude/fonebrew-development-clzu43` for the
`[WP0]`/`[WP1L-G0]`/`[WP1]`/`[WP1L]`/`[WP2]`/`[WP3]`/`[WP4]`/`[WP5]`/`[WP6]`/`[WP7]`/`[WP8]`/`[WP8a]`/`[WP8b]`/`[WP9]`
prefixed commits implementing this state.
