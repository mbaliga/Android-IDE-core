# WP-3 Gate Report — Workspace Kernel: local provider + journal (phase B)

**Scope:** `06_WORK_PACKAGES.md`'s WP-3 entry against `docs/ratified/WORKSPACE_KERNEL_SPEC.md`
(WP-1): `DocumentBuffer`/`WorkspaceProvider` journal + `RecoverySnapshot` on the **local**
provider; the `ResourceUri` model (already declared in WP-1's `contracts/kotlin/
WorkspaceContracts.kt`, consumed here); the conflict machine; a unified human/agent edit
transaction path as **adapters** over existing editor/agent code, not a rewrite of it. SSH is
explicitly **not** in scope (WP-5's job, per the brief).

**Run date:** 2026-08-07. **Method:** the same real-compile-and-test standard WP-2 established —
`./gradlew :core-engine:testFullDebugUnitTest`, not structural inspection.

## Gate verdict: **GREEN**, with one honest scope note (§5) and one flagged contract gap worked around at the implementation layer (§4)

---

## 0. What was built

All under `core-engine/src/main/java/dev/aarso/`:

- `domain/workspace/DocumentBufferMachine.kt` / `WorkspaceProviderMachine.kt` — the two named
  state tables (WORKSPACE_KERNEL_SPEC.md §3.1/§3.2) as real, fail-closed `transition(current,
  event): Result` functions. Every row of both from-state/event/to-state tables is one branch;
  anything not in the table is `Rejected`, not a silent fall-through.
- `domain/workspace/LocalWorkspaceProvider.kt` — the LOCAL member of the five-provider vocabulary
  (`WorkspaceProvider` implemented over `java.io.File`). Revisions are SHA-256 content digests
  (via WP-2's `Digest`), not mtimes — FB-RAT-WS-004's "MUST NOT silently overwrite changed
  content" needs a revision that actually changes when content does, which an mtime at coarse
  filesystem timestamp granularity cannot guarantee under fast successive writes. `write()`
  returns `ResourceWrite.Conflict` (not a bare boolean, not a swallowed exception) whenever
  `expectedRevision` no longer matches. `watch()` is backed by a real `java.nio.file.WatchService`
  on a daemon thread — the one place this domain's own toolchain rule allows `Flow` outside
  suspend functions, per `WorkspaceProvider.watch()`'s own doc comment.
- `domain/workspace/BufferReplay.kt` — deterministic, pure journal-to-content replay (the
  mechanism WORKSPACE_KERNEL_SPEC.md §6.3 describes: "mirrors exactly what a text editor's own
  undo/redo stack needs"). Handles all seven `JournalOpType` values: `SET_FULL_CONTENT` replaces
  the whole buffer, `INSERT`/`DELETE`/`REPLACE` apply their `ByteRange` against the
  UTF-8-byte-accumulated content so far, `OPEN`/`CLOSE`/`DISCARD` are pure lifecycle markers
  tracked separately from content.
- `domain/contracts/WorkspaceCodec.kt` — JSON encode/decode for `BufferJournalEntry` and
  `RecoverySnapshot` (plus `ResourceUri`/`BufferSnapshotEntry` as shared sub-shapes), same
  unknown-field-preserving pattern WP-2's `EnvelopeCodec` established, reusing that pass's
  `JsonInterop` helpers rather than duplicating them.
- `data/entity/{BufferJournalEntryEntity,RecoverySnapshotEntity,BufferRegistryEntity}.kt` +
  matching DAOs + `data/RoomWorkspaceJournal.kt` (the `WorkspaceJournal` implementation:
  `append`/`checkpoint`/`restore`) — Room-backed, append-only for the journal/snapshot tables
  (same house pattern as `LedgerDao`/`ReceiptDao`), upsert for the registry table (see §4).
  Wired into `AppDatabase` (v6→v7) and `AppContainer`.
- `domain/workspace/AgentEditJournalAdapter.kt` — FB-RAT-WS-005 ("human and agent edits share ONE
  transaction/journal/review/undo/conflict model") made real as a **decorator**, per the brief's
  explicit instruction ("hook point for existing editor/agent code identified in WP-0, adapters
  only — do not refactor the live app"). It wraps `domain/ide/RepoWorkLoop.kt`'s existing
  `ChangeCommitter` seam (Sprint 5's agentic-loop-over-an-existing-repo code, itself completely
  untouched by this pass) so that every already-approved `ChangeSet` a `RepoWorkLoop` commits also
  lands in the `WorkspaceJournal` with `originKind = AGENT`, through the exact same `append()`
  entry point a human edit would use — not a second, parallel write path.

Six new test files (`DocumentBufferMachineTest`, `WorkspaceProviderMachineTest`,
`LocalWorkspaceProviderTest`, `BufferReplayTest`, `RoomWorkspaceJournalTest`,
`AgentEditJournalAdapterTest`, `WorkspaceCodecTest` — seven, correcting the count).

## 1. `core-engine` JVM gate — PASS

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 50s
```

Parsed from `core-engine/build/test-results/testFullDebugUnitTest/*.xml` (144 result files):
**1297 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip every prior gate
report has noted — no regression), up from WP-2's 1250-test baseline (+47 new tests).

One real compile bug found and fixed during verification: `LocalWorkspaceProviderTest`'s `watch()`
test called `kotlinx.coroutines.async`/`delay`/`withTimeout` via fully-qualified name inside a
`runBlocking` lambda; the implicit `CoroutineScope` receiver these extension functions need did
not resolve through the FQN call form ("Unresolved reference 'async'", cascading into two
unrelated-looking errors on later lines in the same expression). Fixed by importing them
normally. Re-verified green, then re-ran `LocalWorkspaceProviderTest` alone a second time in
isolation specifically to rule out a timing flake in the real-`WatchService`-backed test — passed
consistently both times (0.3–0.34s, confirming the watcher thread's event genuinely fired within
the test's timeout, not that the test silently no-opped).

## 2. State machines — exhaustive coverage, both directions

`DocumentBufferMachineTest`/`WorkspaceProviderMachineTest` assert every legal transition in
WORKSPACE_KERNEL_SPEC.md §3.1/§3.2's tables reaches the exact expected next state (including that
`Conflicted`/`Merging` preserve their carried `BufferConflict` payload across `BEGIN_MERGE`), plus
a representative sample of illegal transitions from each state are rejected rather than silently
accepted. This is the "conflict suite" half of the WP-3 brief's stated gate.

## 3. Simulated forced-kill suite — 100/100, zero loss, zero duplication

`RoomWorkspaceJournalTest`'s `` `100 simulated forced kills at random points during append -- zero
loss, zero duplication` `` test is the direct JVM analogue of the WP-3 brief's "forced-kill suite
(100 simulated kills) zero-loss." Each of 100 fixed-seed iterations: builds a randomized edit
sequence (`OPEN` + `SET_FULL_CONTENT` + 0–8 random `INSERT`s at valid byte offsets, offsets
computed against a `BufferReplay`-materialized running content so every generated op is valid),
picks a random kill point, appends only that prefix through one `RoomWorkspaceJournal` instance,
discards that instance ("kills the process"), then builds a **second, independent**
`RoomWorkspaceJournal` over the **same backing DAOs** ("restarts against the same on-disk DB") and
asserts: the recovered entries are exactly and only the pre-kill prefix (no loss, no phantom
entries from the never-appended suffix), `BufferReplay`-materializing the recovered entries
reproduces byte-identical content to materializing the original prefix, and a fresh `checkpoint()`
on the recovered state produces a `contentDigest` matching that same reconstructed content.

**What this proves vs. what it doesn't:** genuinely proves `RoomWorkspaceJournal.append()` holds
no in-memory-only buffered state that a kill could lose (each call synchronously reaches the DAO
before returning) and that replay/recovery logic is exactly correct across 100 randomized
kill-point/edit-sequence combinations. It does **not** cross a real OS process boundary or a real
SQLite file — same honest limit as every Room-backed store in this codebase's JVM gate (no device,
no real SQLite binding for Room specifically — see §6). `androidx.sqlite:sqlite-bundled-jvm` is
present in this module's dependencies, but it backs the **SQLDelight** search index
(`CLAUDE.md`'s own note: "that artifact also ships JVM-host natives, which is why `src/test` runs
real `MATCH`/`bm25()` queries" — for `SearchDatabase`, not `AppDatabase`), not Room; wiring Room
itself to a real embedded SQLite driver for JVM tests was investigated and is a larger, separate
undertaking (Room's `Room.databaseBuilder(Context, ...)` overload used in `AppContainer.kt`
requires an Android `Context`; a context-free JVM builder needs Room's KMP artifacts and
`@ConstructedBy`, not configured in this module) — not attempted here to avoid an unbounded,
unrelated toolchain change inside a single work package.

## 4. A genuine WP-1 contract gap, worked around and flagged, not silently patched

Neither `BufferJournalEntry` nor `DocumentBuffer` (`contracts/kotlin/WorkspaceContracts.kt`, WP-1,
already ratified) carries a `workspaceId` field. `WorkspaceJournal.checkpoint(workspaceId):
RecoverySnapshot`'s exact signature (also WP-1, unchanged here) therefore has no way to determine
which buffers belong to the given workspace from its own parameters or from a journal entry alone.
Fixed at the implementation layer, not by amending the already-ratified contract file this pass
does not own: `BufferRegistryEntity`/`BufferRegistryDao` (new, Room-backed, upsert not append-only
— pure bookkeeping, not a domain-authoritative log) plus `RoomWorkspaceJournal.registerBuffer(workspaceId,
bufferId, resourceUri)`, a method beyond `WorkspaceJournal`'s own minimal interface, that a caller
invokes once per buffer (typically alongside its `OPEN` journal entry) so a later `checkpoint()`
can find it. Documented inline in `BufferRegistryEntity.kt`'s doc comment and here, not hidden.

A related design decision, also not literally specified by the contract: `JournalOpType` has no
save-completion marker (no `SAVE_SUCCEEDED` op), so "dirty" cannot be derived from the journal
alone the way a real `DocumentBufferState` machine would track it. `checkpoint()` resolves this by
only including buffers whose replayed state is still "open" (no `CLOSE`/`DISCARD` as their last
entry) — i.e., "still open" is treated as "presumptively dirty and worth capturing," and every
captured `BufferSnapshotEntry.dirty` is `true` (closed buffers are dropped from the snapshot
entirely, matching "RecoverySnapshot... enough state to reconstitute every **non-clean**
buffer"). This is a deliberate, documented interpretation of an underspecified corner, not an
oversight — flagged here for the owner/next session to confirm or override.

## 5. Scope note: what "unified human/agent edit transaction path" does and doesn't cover this pass

`AgentEditJournalAdapter` wires exactly one existing agent-edit surface —
`domain/ide/RepoWorkLoop.kt`'s `ChangeCommitter` seam, the only "existing editor/agent code" WP-0's
survey actually identified as a concrete, wireable agent-edit path (`docs/WP0_SURVEY.md`'s
inventory found no interactive human-editing UI that produces byte-range `BufferJournalEntry`
ops today — `RepoWorkLoop`'s `ChangeSet` is whole-file-text, which is why the adapter journals
`SET_FULL_CONTENT` per changed file, not `INSERT`/`DELETE`/`REPLACE`). A real interactive editor
surface producing byte-range human edits does not exist in this codebase yet; `BufferReplay`'s
`INSERT`/`DELETE`/`REPLACE` handling is real and tested (`BufferReplayTest`) against exactly the
shapes such a future editor would need, but nothing in this pass wires a human-edit UI to the
journal — there is no human-edit UI to wire.

## 6. What's still genuinely unverified

Same standing caveat as every prior gate: no device, emulator, or real SQLite binding for Room in
this sandbox. `RoomWorkspaceJournal`'s actual Room annotations (`@Entity`/`@Dao`/`@Query`,
including the new `buffer_journal_entries`/`recovery_snapshots`/`buffer_registry` tables and the
`AppDatabase` v6→v7 bump, still riding `fallbackToDestructiveMigration()`) are proven to compile
and are exercised via the fake-DAO pattern, not against real generated SQL. `LocalWorkspaceProvider`
IS proven against a real JVM filesystem (temp directories, a real `WatchService`) — that part is
genuinely stronger than the Room-backed pieces, not a fake. Per this repo's standing
environment-honesty rule, on-device behavior (a real forced app-kill, real SAF/USB storage churn,
real multi-app file contention) stays owner-verified until tested on the phone.
