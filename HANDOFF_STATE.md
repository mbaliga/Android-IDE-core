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
With that fix, `:core-engine:testFullDebugUnitTest` runs for real: **1339 tests, 0 failures, 1
ignored** as of WP-4's completion (1297 at the end of WP-3, 1250 at the end of WP-2) — this is the
live baseline every subsequent WP should keep green, not the guessed "868 tests" figure
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

## What is NOT done yet in this repo

- **WP-5 through WP-11** — not started. See `fonebrew_handoff/06_WORK_PACKAGES.md` for the full
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

1. Read `docs/WP1_GATE_REPORT.md`, `docs/WP1L_GATE_REPORT.md`, `docs/WP2_GATE_REPORT.md`,
   `docs/WP3_GATE_REPORT.md`, and `docs/WP4_GATE_REPORT.md` in full before touching anything those
   five passes produced.
2. Fix the `INT-NNN` → `FB-RAT-INT-NNN` citation-format gap in the 5 integration docs (mechanical,
   low-risk once done carefully with full-document review, not sed-across-the-corpus). Still open
   — not touched by WP-2, WP-3, or WP-4.
3. Proceed to **WP-5** (SSH + CI providers against the same conformance suites WP-4's local
   provider used: an SSH `ExecutionProvider`/workspace provider reusing the already-real sshj lane
   at `domain/remote/` — WP0_SURVEY.md §1(h) — over a loopback sshd in tests; GitHub Actions +
   Gitea Actions `ExecutionProvider`s against recorded HTTP fixtures, not live network calls;
   reconnect tokens; visible provenance fields end-to-end, per `06_WORK_PACKAGES.md`'s WP-5 entry).
   A Room-backed `GrantStore`/`PrincipalStore` (WP-4 left both in-memory-only, §Open-thread 9
   below) may be worth revisiting alongside WP-5 if SSH/CI providers turn out to need persisted
   grants across a restart — check before assuming in-memory is still sufficient.
4. A targeted grep for "CSApp"/"Assay" across all four constellation repos was recommended by
   `docs/WP0_SURVEY.md` §1(f) but not yet run — do this before assuming the integration lanes are
   fully greenfield.
5. Two owner-only calls are now ready for a decision, not blocking further build-out but worth
   surfacing: the differentiator-first vs. substrate-first reordering (§Deviations above), and the
   bounded-marketplace-backend phasing question (`08_OPEN_QUESTIONS.md` §E.1) — `LOOP_MARKETPLACE_CONTRACT.md`
   already builds toward the static/Git-registry answer (b) by default per the master brief, but
   the owner has not ruled on it.
6. `Android-IDE-Studio`'s own `CLAUDE.md` (repo map + gate command) is stale — still describes the
   pre-de-fork monolithic `app/` layout and never mentions `core-engine`/`:core`. Not fixed here
   (out of this repo's scope) but worth a fix next time a session works in that repo directly.
7. `Android-IDE-Studio`'s CI (PR #86) is red on a private cross-repo submodule access issue
   (`mbaliga/Android-IDE-core` returns "Repository not found" to the default `GITHUB_TOKEN`) that
   needs an owner-level fix (a PAT/deploy-key secret, or temporarily making the repo public) — not
   something further code changes here can resolve. See that PR's comment thread for the full
   diagnosis; local verification (both repos' real Gradle gates) is green regardless.
8. Real Room-backed JVM testing (a genuine embedded SQLite driver, not the fake-DAO pattern) was
   investigated during WP-3 and found non-trivial with this module's current Room setup (the
   Android-`Context`-requiring `Room.databaseBuilder` overload, not Room's KMP/context-free one) —
   flagged as a possible future toolchain improvement, not attempted mid-WP.
9. WP-4's `AuthorityEngine`/`AuditedAuthorityEngine` have no persisted `Grant`/`Principal` store
   yet (`InMemoryGrantStore`/`InMemoryPrincipalStore` only) — fine for this pass (no consumer
   exists to need persistence across a restart), but worth a Room-backed store the moment WP-5's
   SSH/CI providers or a real UI surface needs grants to survive a process death.
10. `CapabilityRegistry.kt` (WP-4) mirrors `schemas/loops/registries/capability-ids.v1.json` by
    hand, manually cross-checked once (all 20 entries match) but not automatically kept in sync —
    a future registry addition (new `fb.*` IDs are additive/MINOR per that file's own `bumpRule`)
    needs a matching manual update here, or a real automated consistency test, whichever a later
    session has time for.

## Commits

See git log on branch `claude/fonebrew-development-clzu43` for the
`[WP0]`/`[WP1L-G0]`/`[WP1]`/`[WP1L]`/`[WP2]`/`[WP3]`/`[WP4]` prefixed commits implementing this
state.
