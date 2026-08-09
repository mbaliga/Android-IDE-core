# Workspace Kernel Spec — the `workspace` domain

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED for FB-RAT-WS-001 through FB-RAT-WS-007 (all ACCEPTED). FB-RAT-WS-008 is
**EXPERIMENTAL** and is deliberately split in this document per its own validation correction: the
**invariant** it names (zero lost edits under forced kill) is normative P0 and is folded into §4
below; the **numeric thresholds** (100 forced kills, 50,000-file repo) are calibration parameters
and are NOT restated here — they live in `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`, owned by
another agent. **Scope:** the Workspace Kernel — the object model, state machines, provider
seams, and invariants FB-RAT-WS-001 makes the constellation's single point of authority for
projects, files, buffers, indexes, tasks, language sessions, launch state, and recovery. This
document is the citation target for `FB-RAT-WS-001`…`FB-RAT-WS-008`.

This document depends on `docs/ratified/COMMON_CONVENTIONS.md` (`FB-RAT-COM-001`…`012`,
`FB-RAT-DIST-004`) and `schemas/common/*.schema.json` (`ContractEnvelope`, `ErrorEnvelope`,
`IntegrityRef`) — it does not repeat their citations, only uses the shapes. Per that document's
own §3, shared sub-shapes (`ResourceUri`, `IntegrityRef`, a reduced `ErrorEnvelope`) are
**duplicated locally as `$defs`** inside each `schemas/workspace/*.schema.json` file rather than
cross-file `$ref`'d, so every schema file here validates standalone without a multi-file
resolver — the same tradeoff, made for the same reason.

**Repo-placement note (WP-0 survey, `docs/WP0_SURVEY.md` §1(b)):** no repo in the constellation
has anything resembling a Workspace Kernel today — this is genuinely greenfield. The natural home
for an eventual implementation is `core-engine/src/main/java/dev/aarso/domain/workspace/` (a new
package; the real application code lives under `core-engine/src/main/java/dev/aarso/...`, not
`app/src/main/java/...` — CLAUDE.md's repo map is stale on this point, per the WP-0 survey). This
document does not itself add that package; it specifies the contract an eventual implementation
of it must satisfy.

**Scope note on what is and is not schema-validated this pass.** The WP-1 task brief's object
model names eight types (Workspace, Project, ResourceUri, DocumentBuffer, RepositoryState,
LanguageSession, TaskDefinition, RecoverySnapshot) but its explicit schema-file deliverable list
names only seven files: `workspace`, `project`, `resource-uri`, `document-buffer`,
`recovery-snapshot`, `buffer-journal-entry`, `reconnect-token`. **RepositoryState, LanguageSession,
and TaskDefinition are fully specified below (§2.5–§2.7) and modeled in
`contracts/kotlin/WorkspaceContracts.kt`, but deliberately have no standalone JSON Schema or
fixtures in this pass** — this is a literal reading of the task brief's file list, not a silent
gap; it is called out here so the next session can course-correct if that reading was wrong.
`Project.repositoryStateId`/`taskDefinitionIds`/`languageSessionIds` carry forward-pointing stable
IDs to these types without needing their JSON shape to exist yet.

---

## 1. Authority (FB-RAT-WS-001)

**FB-RAT-WS-001 — ACCEPTED.** Workspace Kernel is authoritative for projects, files, buffers,
indexes, tasks, language sessions, launch state, recovery.

Concretely: any other domain in the constellation (search, loop engineering, device broker,
execution contract/authority, integration lanes) that needs to open a file, know what a buffer's
current content is, enumerate a project's tasks, or recover from a crash **MUST** go through the
Workspace Kernel's object model and provider seam (§6) — never read/write a resource directly, and
never maintain a second, competing notion of "what files exist" or "what's dirty." Concretely per
type:

| Type | Kernel is authoritative for | Schema this pass? |
|---|---|---|
| `Workspace` | the top-level container + provider connection health | Yes — §7.1 |
| `Project` | project membership, root location, repo/task/language-session links | Yes — §7.2 |
| `ResourceUri` | resolving *any* file/resource identity, across all five providers | Yes — §7.3 |
| `DocumentBuffer` | in-memory edit state, dirtiness, save/conflict lifecycle | Yes — §7.4 |
| `RepositoryState` | git working-tree state, operation locks | No — §2.5 |
| `LanguageSession` | active language-service sessions | No — §2.6 |
| `TaskDefinition` | typed, repeatable project tasks | No — §2.7 |
| `RecoverySnapshot` | crash-safe checkpoint/restore | Yes — §7.5 |

`data/search/`'s index (already real and wired per WP0_SURVEY.md §1(g) — `SearchDriverFactory`,
`SearchProjector`, `SearchIndexer`) is explicitly **not** re-specified here: FB-RAT-WS-006 (§4)
already settles it as a disposable projection *of* Workspace Kernel state, not a second
authoritative store, and search's own contract corpus is WP-6's job, not this one's.

## 2. Object model

### 2.1 `Workspace` (`schemas/workspace/workspace.schema.json`)

The top-level durable object. Holds membership (`projectIds`), focus (`activeProjectId`), the
live health of every configured `WorkspaceProvider` (`providerConnections[]`, using the
`WorkspaceProviderState` machine — §3.2), and a pointer to the latest `RecoverySnapshot`.

### 2.2 `Project` (`schemas/workspace/project.schema.json`)

One project inside a workspace: a display name, a `ResourceUri` root, and forward-pointing stable
IDs to its `RepositoryState` (if git-tracked), `TaskDefinition`s, and `LanguageSession`s.

### 2.3 `ResourceUri` (FB-RAT-WS-002, `schemas/workspace/resource-uri.schema.json`)

**FB-RAT-WS-002 — ACCEPTED.** Provider-qualified; local/SAF/SSH/snapshot/virtual MUST NOT share
ambiguous raw paths.

Five providers, closed vocabulary: `LOCAL` (on-device filesystem), `SAF` (Android Storage Access
Framework), `SSH` (a remote host over the real `sshj`-backed SSH lane — WP0_SURVEY.md §1(h), not
greenfield, already exists at `core-engine/src/main/java/dev/aarso/domain/remote/`), `SNAPSHOT` (a
point-in-time, read-only view backed by a `RecoverySnapshot`), `VIRTUAL` (a synthetic resource
with no backing store — an unsaved scratch buffer, a generated diff view).

The single authoritative identity string is `raw` — a self-describing, scheme-prefixed string
(`local://…`, `saf://…`, `ssh://…`, `snapshot://…`, `virtual://…`). `resource-uri.schema.json`
structurally pins `raw`'s scheme prefix to match the declared `provider` (an `allOf`/`if`/`then`
per provider value, the same pattern `envelope.schema.json` uses to pin `schemaVersion`'s major
component) — a `ResourceUri` claiming `provider: "LOCAL"` with an `ssh://` `raw` value is a
**structural** rejection (`fixtures/workspace/invalid/resource-uri-scheme-provider-mismatch.invalid.json`),
not just a reader-discipline concern.

`displayPath` is explicitly **non-authoritative** — UI display only. Two different real resources
under two different providers MAY legitimately share the same `displayPath` (e.g. a local
checkout and its SSH-mirrored counterpart both containing `README.md`); only the `(provider, raw)`
pair disambiguates them. See `fixtures/workspace/adversarial/
resource-uri-cross-provider-ambiguity.adversarial.json` for the fixture this guards against, and
its sibling `.expected.txt` for the identity-key obligation this places on every consumer.

### 2.4 `DocumentBuffer` (FB-RAT-WS-003/004/005, `schemas/workspace/document-buffer.schema.json`)

Covered in full in §3.1 (state machine) and §4 (invariants) below — this subsection is the field
inventory only.

| Field | Purpose |
|---|---|
| `bufferId` | FB-RAT-COM-002 stable ID |
| `resourceUri` | which resource this buffer edits (§2.3) |
| `state` | `DocumentBufferState` — §3.1 |
| `dirty` | derived-but-explicit; structurally pinned to `state` |
| `baseRevision` | the provider revision this buffer was opened/synced from; `expectedRevision` for optimistic writes (FB-RAT-WS-004) |
| `contentRevision` | opaque revision of current in-memory content |
| `encoding` / `lineEndings` | text-shape metadata; `lineEndings: MIXED` is itself an accessibility-relevant fact (FB-RAT-COM-009) — a silent-normalize-on-save is a legibility violation |
| `journalSequence` | highest `BufferJournalEntry.sequence` applied — §6.3 |
| `conflictState` | non-null only in `CONFLICTED`/`MERGING` |
| `lastSaveError` | an `ErrorEnvelope`-shaped explanation, non-null only in `SAVE_FAILED` (FB-RAT-COM-007/009 — a `SAVE_FAILED` buffer is not a bare status flag with no explanation) |

### 2.5 `RepositoryState` (FB-RAT-WS-001/007) — object model + operation-lock section

**Not schema-validated this pass** (see the scope note above) — specified here in full so
`contracts/kotlin/WorkspaceContracts.kt`'s `RepositoryState`/`GitOperationLock` types have a
citable source, and so the proposed git-library decision below has a home.

| Field | Purpose |
|---|---|
| `repositoryStateId` | FB-RAT-COM-002 stable ID |
| `provider` | which git host — `GITHUB` / `GITEA` / `OTHER_REST` |
| `root` | the working tree's `ResourceUri` |
| `head` | current HEAD commit SHA |
| `branch` | current branch name, or null if detached HEAD |
| `dirtyDigest` | `IntegrityRef` digest over the uncommitted working-tree diff; null when clean |
| `stagedDigest` | `IntegrityRef` digest over the staged diff; null when nothing staged |
| `upstream` | remote name/branch + ahead/behind counts, when tracking one |
| `operationLock` | non-null only while a history-rewriting op is in progress — below |

**FB-RAT-WS-007 — ACCEPTED.** History-rewriting Git ops take an operation lock with
progress/abort/recovery.

`operationLock` carries: `lockId`, `operationKind` (`REBASE` / `MERGE` / `CHERRY_PICK` /
`RESET_HARD` / `HISTORY_REWRITE_OTHER`), `acquiredAtUtc`, `holder` (FB-RAT-COM-008 initiating
principal), `progress` (0.0–1.0), `abortable`, and `recoverySnapshotId` — a pointer to a
`RecoverySnapshot` with `reason: PRE_OPERATION` taken immediately before the operation began, so
abort/recovery has something concrete to restore to. A caller MUST NOT begin a second
history-rewriting operation on the same `RepositoryState` while `operationLock` is non-null.

> **Proposed decision (not self-ratified) — for the non-ratified-registers agent to log as a new
> `FB-RAT-*-NEW` entry.** Per WP-0 survey §3: no repo in this constellation has JGit or any git
> library as a dependency today — all existing git integration
> (`core-engine/src/main/java/dev/aarso/domain/git/GitContentsApi.kt`, `GitTreeApi.kt`) is pure
> REST request-builders against GitHub/Gitea, executed by an injected transport. For a real
> `RepositoryState`/operation-lock implementation, the independent validation pass that reviewed
> this work package recommends: **JGit** for the read/status/commit paths (a working-tree-level
> library, not a REST client, is genuinely needed once `dirtyDigest`/`stagedDigest` must be
> computed against an actual `.git` directory rather than parsed from a host's REST response);
> **evaluate libgit2-JNI (`git24j`)** for merge/rebase/conflict, where JGit's pure-JVM merge
> machinery is a known weaker spot; and note that **history-rewriting ops MAY also route through
> the SSH lane** (already real — `core-engine/src/main/java/dev/aarso/domain/remote/`, WP0_SURVEY.md
> §1(h)) as an alternative to an embedded git library, executing `git rebase`/`git reset` etc. on
> a remote host the user already trusts. This is a **proposal**, not a decision this document is
> authorized to make — it is recorded here, in the section the task brief asked for it, purely so
> it has a citable location. No `FB-RAT-WS-*` ID is claimed for it.

### 2.6 `LanguageSession` (FB-RAT-WS-001)

**Not schema-validated this pass.** The source pack names this type with no further qualifier at
all — unlike `TaskDefinition` (§2.7), which the brief gives an explicit field qualifier for. Kept
intentionally minimal here: `languageSessionId`, `projectId`, `languageId` (e.g. `"kotlin"`),
`state` (`STARTING` / `READY` / `DEGRADED` / `STOPPED` — a small dedicated enum, deliberately not
reusing `WorkspaceProviderState`, since an LSP session's lifecycle is not the same machine as a
provider connection's even though both have a superficially similar shape), `startedAtUtc`, and an
optional `capabilityManifestId` forward-pointer to a `CapabilityManifest`
(`subjectKind: "LSP"`, `schemas/common/capability-manifest.schema.json`) — no `CapabilityManifest`
fields are invented here, only the stable-ID link. Full LSP/DAP capability wiring (what operations
a given language server actually supports, negotiated) is downstream of the Execution Contract +
Authority engine domain (WP-0 survey (c) — create-new, not built by this work package or any
existing repo).

### 2.7 `TaskDefinition` (FB-RAT-WS-001)

**Not schema-validated this pass.** Qualifier given in the task brief: typed command/tool, target
constraints, problem matcher, authority requirements — all four present in
`contracts/kotlin/WorkspaceContracts.kt`:

| Field | Purpose |
|---|---|
| `commandKind` | typed command/tool family — `SHELL` / `GRADLE` / `ARDUINO_CLI` / `SSH_REMOTE` / `OTHER` |
| `command` | the literal invocation (e.g. a Gradle task name, a shell command) |
| `targetConstraints` | required capabilities, a required device ID, a working directory |
| `problemMatchers` | named regexes that parse tool output into problems |
| `authorityRequirements` | network / filesystem-write / device-execution / history-rewrite booleans — declared up front so a future Execution Contract + Authority engine (WP-0 survey (c)) has something concrete to gate against once it exists; this domain does not itself grant or enforce authority |

### 2.8 `RecoverySnapshot` (FB-RAT-WS-001/003, `schemas/workspace/recovery-snapshot.schema.json`)

Covered in full in §4 (invariants) and §6.3 (`WorkspaceJournal`) below — field inventory:
`snapshotId`, `workspaceId`, `takenAtUtc`, `reason` (`PERIODIC_CHECKPOINT` / `PRE_OPERATION` /
`PROCESS_DEATH_CAPTURE` / `MANUAL`), `lastJournalSequence` (the global journal watermark this
snapshot represents), and `bufferSnapshots[]` (per-buffer `bufferId` + `resourceUri` +
`journalSequence` + `dirty` + `encoding` + `lineEndings` + `contentDigest`, enough to fully
reconstitute every non-clean buffer at capture time).

---

## 3. State machines

The source pack's ASCII diagrams for these two machines were garbled by docx-to-markdown
conversion. Rebuilt here as explicit from-state/event/to-state tables, not arrows.

### 3.1 `DocumentBufferState`

Source shape given: `CLOSED -> OPEN_CLEAN -> OPEN_DIRTY -> SAVING -> OPEN_CLEAN | SAVE_FAILED`;
`OPEN_DIRTY -> CONFLICTED -> MERGING -> OPEN_DIRTY`. The `DISCARD` transitions below are this
document's addition, needed to make FB-RAT-WS-003's "until explicit save **or discard**" half of
the invariant checkable at all — the source ASCII only spelled out the save half.

| From state | Event | To state |
|---|---|---|
| `CLOSED` | `OPEN(resourceUri)` | `OPEN_CLEAN` |
| `OPEN_CLEAN` | `EDIT` | `OPEN_DIRTY` |
| `OPEN_CLEAN` | `CLOSE` | `CLOSED` |
| `OPEN_DIRTY` | `SAVE_REQUESTED` | `SAVING` |
| `OPEN_DIRTY` | `REMOTE_REVISION_CHANGED` | `CONFLICTED` |
| `OPEN_DIRTY` | `DISCARD` | `CLOSED` |
| `SAVING` | `SAVE_SUCCEEDED` | `OPEN_CLEAN` |
| `SAVING` | `SAVE_FAILED` | `SAVE_FAILED` |
| `SAVE_FAILED` | `RETRY_SAVE` | `SAVING` |
| `SAVE_FAILED` | `EDIT` | `OPEN_DIRTY` |
| `SAVE_FAILED` | `DISCARD` | `CLOSED` |
| `CONFLICTED` | `BEGIN_MERGE` | `MERGING` |
| `CONFLICTED` | `DISCARD` | `CLOSED` |
| `MERGING` | `MERGE_RESOLVED` | `OPEN_DIRTY` |

Structural encoding: `document-buffer.schema.json` pins `dirty` (`false` only for
`CLOSED`/`OPEN_CLEAN`, `true` for every other state), `conflictState` (non-null only for
`CONFLICTED`/`MERGING`), and `lastSaveError` (non-null only for `SAVE_FAILED`) to `state` via
`allOf`/`if`/`then`. The Kotlin encoding (`DocumentBufferState` sealed interface,
`WorkspaceContracts.kt`) goes further: `Conflicted`/`Merging` carry their `BufferConflict` payload
and `SaveFailed` carries its `ErrorEnvelope` directly as constructor data, and `dirty` is a
computed property — the contradictory states the wire fixtures
(`fixtures/workspace/invalid/document-buffer-dirty-state-contradiction.invalid.json`,
`.../document-buffer-conflict-state-mismatch.invalid.json`) exist to catch are **unrepresentable**
in the Kotlin type, not merely rejected at validation time.

### 3.2 `WorkspaceProviderState`

Source shape given: `UNCONFIGURED -> CONNECTING -> READY <-> DEGRADED -> DISCONNECTED |
AUTH_REQUIRED | FAILED`.

| From state | Event | To state |
|---|---|---|
| `UNCONFIGURED` | `CONFIGURE(credentials/host)` | `CONNECTING` |
| `CONNECTING` | `CONNECT_SUCCEEDED` | `READY` |
| `CONNECTING` | `AUTH_REQUIRED_DETECTED` | `AUTH_REQUIRED` |
| `CONNECTING` | `CONNECT_FAILED` | `FAILED` |
| `READY` | `HEALTH_CHECK_DEGRADED` | `DEGRADED` |
| `DEGRADED` | `HEALTH_CHECK_RECOVERED` | `READY` |
| `READY` | `AUTH_EXPIRED` | `AUTH_REQUIRED` |
| `DEGRADED` | `AUTH_EXPIRED` | `AUTH_REQUIRED` |
| `READY` | `DISCONNECT_REQUESTED` \| `CONNECTION_LOST` | `DISCONNECTED` |
| `DEGRADED` | `CONNECTION_LOST` | `DISCONNECTED` |
| `AUTH_REQUIRED` | `REAUTHENTICATED` (optionally via a `ReconnectToken`, §6.4) | `CONNECTING` |
| `DISCONNECTED` | `RECONNECT_REQUESTED` (optionally via a `ReconnectToken`) | `CONNECTING` |
| `FAILED` | `RETRY` | `CONNECTING` |

Structural home: `Workspace.providerConnections[].state` (`workspace.schema.json`) uses this
seven-value enum; `WorkspaceProviderState` in `WorkspaceContracts.kt` is the sealed-interface
counterpart, each non-`Unconfigured`/`Connecting`/`Ready` variant carrying an optional `detail`
string mirroring the wire shape's `providerConnections[].detail` field (FB-RAT-COM-009 — textual
semantics for a non-`READY` connection, independent of a color-only status dot).

---

## 4. Invariants

- **FB-RAT-WS-003 — ACCEPTED.** Dirty buffers survive Activity recreation/process death/reboot
  until explicit save or discard. Mechanism: `DocumentBuffer.journalSequence` +
  `WorkspaceJournal.append`/`checkpoint`/`restore` (§6.3) — every edit is journaled before it is
  considered durable-enough-to-survive-a-kill, and `RecoverySnapshot.bufferSnapshots[]` captures
  enough per-buffer state (§2.8) to reconstitute every non-clean buffer after a crash. The
  forced-kill-mid-append edge of this invariant is **not** fully verifiable by a JSON fixture — see
  `fixtures/workspace/adversarial/buffer-journal-forced-kill-mid-append.adversarial.json` and its
  `.expected.txt` for the JVM property-based test this invariant actually requires.
- **FB-RAT-WS-004 — ACCEPTED.** Remote saves use source revisions, MUST NOT silently overwrite
  changed content. Mechanism: `DocumentBuffer.baseRevision` is presented as `expectedRevision` to
  `WorkspaceProvider.write()` (§6.1); the return type `ResourceWrite` is a sealed interface whose
  `Conflict` variant is a **typed, mandatory-to-handle result**, not an exception a caller could
  accidentally swallow, and not a silent success.
- **FB-RAT-WS-005 — ACCEPTED.** Human and agent edits share ONE transaction/journal/review/undo/
  conflict model. Mechanism: `BufferJournalEntry.originKind` (`HUMAN` / `AGENT`) plus
  `originPrincipal` (§6.3) — one journal, one `WorkspaceJournal.append()` entry point, both lanes
  distinguishable after the fact for provenance (FB-RAT-COM-008) but never routed through separate
  write paths. A `CONFLICTED` buffer's resolution path (`BEGIN_MERGE` → `MERGING` →
  `MERGE_RESOLVED`, §3.1) applies identically regardless of which lane produced the conflicting
  edit — see `fixtures/workspace/adversarial/document-buffer-unresolved-conflict.adversarial.json`
  for the fixture naming the "no silent auto-resolve, for either lane" obligation this implies.
- **FB-RAT-WS-006 — ACCEPTED.** Indexes and language-service state are disposable projections;
  buffers and journals are authoritative. Mechanism: this document defines no schema for a search
  index or a language-server's internal state — `LanguageSession` (§2.6) records only that a
  session exists and its coarse lifecycle state, never the language server's own internal model,
  which is free to be rebuilt from the authoritative buffer/journal state at any time with no data
  loss. `data/search/`'s FTS5 index (already real, WP0_SURVEY.md §1(g)) is the concrete existing
  instance of this rule in the codebase today, even though it predates this document.
- **FB-RAT-WS-007 — ACCEPTED.** History-rewriting Git ops take an operation lock with
  progress/abort/recovery. Mechanism: `RepositoryState.operationLock` — §2.5.
- **FB-RAT-WS-008 — EXPERIMENTAL, split per its own validation correction.** The **invariant**
  (zero lost edits under forced kill) is normative P0 and is exactly the FB-RAT-WS-003 mechanism
  above — no separate mechanism is defined for it. The **numeric calibration thresholds** (100
  forced kills, a 50,000-file repo, given in the task brief as INITIAL calibratable fixtures) are
  NOT restated in this document; they are routed to `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`
  (owned by another agent) as the task brief instructs. This document does not invent or repeat
  those numbers.

---

## 5. Scale thresholds (FB-RAT-WS-008) — cross-reference only

Per §4's split: the calibratable numeric thresholds (forced-kill count, repo file count) live in
`docs/non_ratified/EXPERIMENTAL_DECISIONS.md`. This document does not restate them — only the
invariant they calibrate against (FB-RAT-WS-003/§4) is normative here.

---

## 6. Provider interfaces (`schemas/workspace/` has no schema for these — Kotlin-only, wire shape
is per-call request/response, not a persisted contract)

### 6.1 `WorkspaceProvider`

```kotlin
interface WorkspaceProvider {
    suspend fun stat(uri: ResourceUri): ResourceStat
    suspend fun read(uri: ResourceUri, expectedRevision: String? = null): ResourceRead
    suspend fun write(uri: ResourceUri, bytes: ByteArray, expectedRevision: String?): ResourceWrite
    fun watch(scope: ResourceUri): Flow<ResourceChange>
    suspend fun list(scope: ResourceUri, cursor: String? = null): ResourcePage
}
```

Exact signatures as given in the task brief. `watch()` is the one place in this domain's Kotlin
surface that uses `kotlinx-coroutines` `Flow` — the single named exception in the shared toolchain
rules to the "stdlib + `java.time.Instant` only" constraint. `write()`'s `ResourceWrite` return
type is the FB-RAT-WS-004 mechanism (§4): `Success` or `Conflict`, never a bare boolean, never a
thrown-and-possibly-uncaught exception standing in for "the remote moved."

### 6.2 `WorkspaceJournal`

```kotlin
interface WorkspaceJournal {
    suspend fun append(change: BufferJournalEntry)
    suspend fun checkpoint(workspaceId: String): RecoverySnapshot
    suspend fun restore(workspaceId: String): RecoverySnapshot?
}
```

Exact signatures as given in the task brief. `restore()` returns `null` when no snapshot exists
for the given workspace (a brand-new workspace, never checkpointed) — this is a valid, expected
outcome, not an error.

### 6.3 `BufferJournalEntry` — designed this pass (named, not specified, in the source pack)

Fields: `sequence` (monotonic, FB-RAT-COM-004), `bufferId`, `resourceUri`, `opType` (`INSERT` /
`DELETE` / `REPLACE` / `SET_FULL_CONTENT` / `OPEN` / `CLOSE` / `DISCARD`), `byteRange` (half-open
`[startByte, endByteExclusive)`, required for `INSERT`/`DELETE`/`REPLACE`, null otherwise),
`content` (required for `INSERT`/`REPLACE`/`SET_FULL_CONTENT`, null otherwise), `recordedAtUtc`,
`originKind` (`HUMAN` / `AGENT` — the FB-RAT-WS-005 provenance marker), `originPrincipal`
(FB-RAT-COM-008). Identity for a journal entry is its `(bufferId, sequence)` pair — no separate
`objectId` is declared, because an append-only log entry's position in the log **is** its
identity, unlike the freestanding durable objects `FB-RAT-COM-002` targets.

Design rationale for the per-`opType` content/byteRange pairing: it mirrors exactly what a text
editor's own undo/redo stack needs to replay an edit deterministically — a byte-range op needs to
know where and (except for `DELETE`) what; a whole-buffer op (`SET_FULL_CONTENT`) needs only the
new content; a lifecycle marker (`OPEN`/`CLOSE`/`DISCARD`) needs neither.

### 6.4 `ReconnectToken` — designed this pass (named, not specified, in the source pack)

Fields: `providerId` (scopes the token to one `Workspace.providerConnections[]` entry —
`WorkspaceProviderState`'s `AUTH_REQUIRED`/`DISCONNECTED` → `CONNECTING` transitions, §3.2, are
where this is presented), `tokenBytes` (opaque, provider-issued, base64/base64url-shaped),
`issuedAtUtc`, `expiresAtUtc` (MUST be after `issuedAtUtc` — enforced in the Kotlin `init{}`, not
structurally enforceable in the JSON Schema without a `$data` extension this constellation does
not depend on).

`tokenBytes` is a bearer credential in every way that matters for binding rule 5 (API keys
encrypted at rest via Android Keystore, never logged) even though it is not literally an API key —
see `fixtures/workspace/adversarial/reconnect-token-plaintext-secret-leak.adversarial.json` for
the fixture naming this obligation explicitly, since "ReconnectToken" does not read as "credential"
the way "ApiKey" does and this constellation has already had one binding rule specifically about
not under-classifying secrets.

---

## 7. Schema + fixture inventory

All seven files below are draft 2020-12, all carry a `$comment` LICENSE-PENDING marker, all
`Draft202012Validator.check_schema()`-clean (§9 transcript). One worked, schema-**validated** JSON
example per schema — each is byte-identical to its `fixtures/workspace/valid/*.json` counterpart.

### 7.1 `Workspace` — `schemas/workspace/workspace.schema.json`

```json
{
  "workspaceId": "01J8ZK1M3P5R7T9V1X3Z5B7D9F",
  "displayName": "Fonebrew constellation",
  "createdAtUtc": "2026-07-01T08:00:00Z",
  "projectIds": [
    "01J8ZK3N5Q7S9U1W3Y5A7C9E1G",
    "01J8ZK4P6R8T1V3X5Z7B9D1F3H"
  ],
  "activeProjectId": "01J8ZK3N5Q7S9U1W3Y5A7C9E1G",
  "providerConnections": [
    {
      "providerId": "local-primary",
      "providerKind": "LOCAL",
      "state": "READY",
      "lastTransitionAtUtc": "2026-08-07T09:00:00Z",
      "detail": null
    },
    {
      "providerId": "ssh-host-9f21e3",
      "providerKind": "SSH",
      "state": "DEGRADED",
      "lastTransitionAtUtc": "2026-08-07T10:12:04Z",
      "detail": "Latency above 2s on last three round trips; connection still usable."
    }
  ],
  "recoverySnapshotId": "01J8ZM7Q9S1U3W5Y7A9C1E3G5J",
  "unknownFields": {}
}
```

### 7.2 `Project` — `schemas/workspace/project.schema.json`

```json
{
  "projectId": "01J8ZK3N5Q7S9U1W3Y5A7C9E1G",
  "workspaceId": "01J8ZK1M3P5R7T9V1X3Z5B7D9F",
  "displayName": "android-ide-core",
  "rootUri": {
    "provider": "SSH",
    "raw": "ssh://host-9f21e3/home/dev/projects/android-ide-core",
    "displayPath": "android-ide-core",
    "unknownFields": {}
  },
  "repositoryStateId": "01J8ZN2S4U6W8Y1A3C5E7G9J1L",
  "taskDefinitionIds": ["01J8ZP4U6W8Y1A3C5E7G9J1L3N"],
  "languageSessionIds": ["01J8ZQ6W8Y1A3C5E7G9J1L3N5P"],
  "unknownFields": {}
}
```

### 7.3 `ResourceUri` — `schemas/workspace/resource-uri.schema.json`

```json
{
  "provider": "SSH",
  "raw": "ssh://host-9f21e3/home/dev/projects/android-ide-core/README.md",
  "displayPath": "android-ide-core/README.md",
  "unknownFields": {}
}
```

### 7.4 `DocumentBuffer` — `schemas/workspace/document-buffer.schema.json`

```json
{
  "bufferId": "01J8ZR8Y1A3C5E7G9J1L3N5P7R",
  "resourceUri": {
    "provider": "LOCAL",
    "raw": "local:///storage/emulated/0/Android-IDE-core/core-engine/src/main/java/dev/aarso/domain/loop/GraphRunner.kt",
    "displayPath": "core-engine/.../GraphRunner.kt",
    "unknownFields": {}
  },
  "state": "OPEN_DIRTY",
  "dirty": true,
  "baseRevision": "ab3567ecf1a2b3c4d5e6f7089a1b2c3d4e5f6789",
  "contentRevision": "buf-rev-0000000412",
  "encoding": "UTF-8",
  "lineEndings": "LF",
  "journalSequence": 412,
  "conflictState": null,
  "unknownFields": {}
}
```

A second valid fixture, `fixtures/workspace/valid/document-buffer-save-failed-valid.json`,
exercises the `SAVE_FAILED` + `lastSaveError` branch (added after this schema's first pass — see
§9's revalidation note).

### 7.5 `RecoverySnapshot` — `schemas/workspace/recovery-snapshot.schema.json`

```json
{
  "snapshotId": "01J8ZM7Q9S1U3W5Y7A9C1E3G5J",
  "workspaceId": "01J8ZK1M3P5R7T9V1X3Z5B7D9F",
  "takenAtUtc": "2026-08-07T10:15:00Z",
  "reason": "PERIODIC_CHECKPOINT",
  "lastJournalSequence": 412,
  "bufferSnapshots": [
    {
      "bufferId": "01J8ZR8Y1A3C5E7G9J1L3N5P7R",
      "resourceUri": {
        "provider": "LOCAL",
        "raw": "local:///storage/emulated/0/Android-IDE-core/core-engine/src/main/java/dev/aarso/domain/loop/GraphRunner.kt",
        "displayPath": "core-engine/.../GraphRunner.kt",
        "unknownFields": {}
      },
      "journalSequence": 412,
      "dirty": true,
      "encoding": "UTF-8",
      "lineEndings": "LF",
      "contentDigest": {
        "algorithm": "SHA-256",
        "digestHex": "06fd7ffc8dd822485735eb68535a45943875ea5c9f91ea77f1d7bbae5d42bc4c",
        "byteLength": 9821
      }
    }
  ],
  "unknownFields": {}
}
```

### 7.6 `BufferJournalEntry` — `schemas/workspace/buffer-journal-entry.schema.json`

```json
{
  "sequence": 412,
  "bufferId": "01J8ZR8Y1A3C5E7G9J1L3N5P7R",
  "resourceUri": {
    "provider": "LOCAL",
    "raw": "local:///storage/emulated/0/Android-IDE-core/core-engine/src/main/java/dev/aarso/domain/loop/GraphRunner.kt",
    "displayPath": "core-engine/.../GraphRunner.kt",
    "unknownFields": {}
  },
  "opType": "INSERT",
  "byteRange": { "startByte": 4821, "endByteExclusive": 4821 },
  "content": "        onStep(GraphStep.Cancelled(reason = \"budget:tokens\"))\n",
  "recordedAtUtc": "2026-08-07T10:14:58Z",
  "originKind": "AGENT",
  "originPrincipal": "agent-run:wp1-workspace-kernel-4821",
  "unknownFields": {}
}
```

### 7.7 `ReconnectToken` — `schemas/workspace/reconnect-token.schema.json`

```json
{
  "providerId": "ssh-host-9f21e3",
  "tokenBytes": "d2Vic29ja2V0LXJlY29ubmVjdC10b2tlbi1vcGFxdWUtYnl0ZXMtNDgyMQ==",
  "issuedAtUtc": "2026-08-07T09:00:00Z",
  "expiresAtUtc": "2026-08-07T21:00:00Z",
  "unknownFields": {}
}
```

---

## 8. Conformance test classes (FB-RAT-DIST-004 — same 8-class framework `COMMON_CONVENTIONS.md`
§11 uses)

| Test class | JVM-testable | Notes |
|---|---|---|
| `GOLDEN_SERIALIZATION` | YES | The seven worked examples in §7 — schema-validated JSON, no device needed. |
| `STATE_TRANSITION` | YES | §3.1/§3.2's tables are directly assertable as pure-Kotlin state-machine unit tests once `contracts/kotlin/` is wired into a Gradle module. |
| `ADVERSARIAL` | PARTIAL | The 4 fixtures in `fixtures/workspace/adversarial/` are structurally JVM-checkable (they pass schema validation); the semantic obligation each `.expected.txt` names (identity-key discipline, no-silent-auto-resolve, journal durability, secret redaction) requires either an integration test or, for the forced-kill scenario, a real process-kill harness this container cannot run. |
| `PROVIDER_CONFORMANCE` | NO | `WorkspaceProvider`/`WorkspaceJournal` have no concrete implementation yet (LOCAL/SAF/SSH/SNAPSHOT/VIRTUAL) — conformance testing requires an implementation to test, which is out of this work package's scope. |
| `RECOVERY` | PARTIAL | `RecoverySnapshot` round-trip (serialize/deserialize) is JVM-testable; actual process-death/OOM-kill recovery is device-only (CLAUDE.md "Environment honesty" — no device/emulator in this build container). |
| `PERFORMANCE` | NO | FB-RAT-WS-008's numeric thresholds (100 forced kills, 50,000-file repo) are exactly the calibration this test class would exercise — routed to `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`, not run here. |
| `ACCESSIBILITY` | PARTIAL | Every state/field carrying required textual semantics (`conflictState.remoteSummary`, `lastSaveError.userMessage`, `providerConnections[].detail`) is structurally present and non-blank-enforced; whether an actual UI surface renders them legibly with color/icon stripped is device-only. |
| `COMPATIBILITY` | YES | Every schema's `unknownFields` bag + `additionalProperties: true` is JVM-testable via a decode→mutate→re-encode round-trip once a codec exists (none is built by this work package — schemas define the shape, not the codec). |

---

## 9. Validation transcript

See the WP-1 handoff report (this domain's final message) for the exact commands run and their
full output. Summary: all 7 schemas `Draft202012Validator.check_schema()`-clean; all 9 valid
fixtures (7 canonical + 2 additional `SAVE_FAILED`/`lastSaveError` fixtures added after
`document-buffer.schema.json`'s first pass) PASS their schema; all 12 invalid fixtures FAIL as
required; all 4 adversarial fixtures structurally PASS with a sibling `.expected.txt` each.

---

## 10. References

- `docs/ratified/COMMON_CONVENTIONS.md` — the shared envelope/error/integrity/accessibility
  vocabulary this document builds on.
- `docs/WP0_SURVEY.md` — repo-placement evidence (§1(b) Workspace Kernel create-new, §1(h) SSH
  lane already real, §1(g) search already real and wired, §3 no git library dependency).
- `docs/non_ratified/EXPERIMENTAL_DECISIONS.md` — FB-RAT-WS-008's numeric calibration thresholds
  (not owned by this document).
- `docs/non_ratified/LICENSE_PENDING.md` — this repo's license status.
- `schemas/workspace/*.schema.json`, `contracts/kotlin/WorkspaceContracts.kt`,
  `fixtures/workspace/{valid,invalid,adversarial}/` — the artifacts this document specifies.
