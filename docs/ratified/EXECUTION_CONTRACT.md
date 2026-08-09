# Execution Contract — the `execution` domain

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED (this document); the Execution Contract + **Authority engine** half named in
the WP-0 survey (`docs/WP0_SURVEY.md` §1(c)) is **out of scope here** — this document specifies
the execution object model, lifecycle, provider contract, and platform-grounding rules only.
`ExecutionRequest.authorityGrant` is a deliberately minimal reference into that other, not-yet-
built domain (see §4.4) — this document does not invent the grant/capability-ladder shape itself,
per the instruction not to self-ratify another domain's design.

**Scope:** the `execution` domain named in the WP-1 task brief — `ExecutionTarget`,
`ExecutionRequest`, `ExecutionHandle`, `ExecutionReceipt`, and the `ExecutionProvider` interface
every concrete provider (local Android, SSH, Raspberry Pi, GitHub Actions, Gitea Actions —
FB-RAT-EXE-010) implements. This document is the citation target for `FB-RAT-STR-004` and
`FB-RAT-EXE-001`…`FB-RAT-EXE-011`. It builds on, and does not repeat, `docs/ratified/
COMMON_CONVENTIONS.md`'s `ContractEnvelope`/`ErrorEnvelope`/`CapabilityManifest`/`ArtifactRef` —
every top-level shape below is typically carried as the `payload` of a
`ContractEnvelope<T>` (`schemas/common/envelope.schema.json`), and `ArtifactRef`
(`schemas/common/artifact-ref.schema.json`) is reused as-is inside `ExecutionReceipt.outputs`
and `ExecutionReceipt.logs.ref` — neither is redefined here.

**Why this domain matters now.** Per the WP-0 survey (`docs/WP0_SURVEY.md` §1(c)), no repo in the
constellation has an `ExecutionContract`, `CapabilityManifest`-consuming authority gate, or any
routing-engine material today — this is genuinely greenfield. The nearest real substrate is
`core-engine`'s already-shipped `data/DeviceRepo.kt` (§1(e)) and `domain/remote/*` SSH spine
(§1(h)), which this contract is written to be adaptable *over*, not to replace — a future provider
implementation wraps `RemoteSessionDriver`/`SshjTransport`, it does not reinvent them. Package
paths cited anywhere below use the real `core-engine/src/main/java/dev/aarso/...` location (WP-0
survey correction to the stale `app/src/main/java/...` claim in both repos' `CLAUDE.md`), and the
real CI gate command is `./gradlew --no-daemon :core-engine:testFullDebugUnitTest
:core-engine:testPlayDebugUnitTest :core-engine:checkLicense` (read from `.github/workflows/
ci.yml`, not from either stale `CLAUDE.md`).

---

## 1. Compute provenance (FB-RAT-STR-004)

**FB-RAT-STR-004 — ACCEPTED.** Compute provenance: permit user-initiated SSH and CI compute only
when execution location, revision, data boundary, state, and result are visible.

Operationalized as `ExecutionTarget.visibilityContract` (`schemas/execution/target.schema.json`,
`$defs/VisibilityContract`) — five required booleans, one per dimension named in the decision.
`ExecutionTarget.provenanceStyle` is `LOCAL_DEVICE_DIRECT` (no separate contract needed — the
requesting and executing device are the same), `USER_INITIATED_REMOTE` (`SSH_HOST`,
`RASPBERRY_PI`), or `CI_PIPELINE` (`GITHUB_ACTIONS`, `GITEA_ACTIONS`). A target whose
`provenanceStyle` is one of the latter two **MUST** carry a non-null `visibilityContract`
(structurally enforced — see `fixtures/execution/invalid/
target-visibility-contract-missing.invalid.json`), but the schema deliberately does **not** force
every boolean to `true` — a target legitimately needs to be representable mid-handshake, or with a
known gap, before all five are confirmed. The **MUST-level gate obligation** lives one layer up,
in the provider contract:

> An `ExecutionProvider` implementation **MUST** refuse `prepare()`/`start()` for any
> `ExecutionRequest` whose `ExecutionTarget.provenanceStyle` is `USER_INITIATED_REMOTE` or
> `CI_PIPELINE` and whose `visibilityContract` has any field `false`. This is a provider-gate
> obligation JSON Schema validation alone cannot express (see
> `fixtures/execution/adversarial/target-visibility-contract-incomplete.adversarial.json` — a
> structurally valid target with `dataBoundaryVisible: false` that a real implementation MUST
> still refuse to schedule work against).

## 2. Lifecycle (FB-RAT-EXE-002)

**FB-RAT-EXE-002 — ACCEPTED.** Execution lifecycle: queued/preparing/running/waiting/suspended/
verifying/verified-unverified-success/safe-failure/possible-side-effect-failure/unknown-target-
state/cancellation.

Twelve states total (`schemas/execution/handle.schema.json`, `$defs/ExecutionState`); six
non-terminal, six terminal. The table below is authoritative — `ExecutionHandle.state` (live) and
`ExecutionReceipt.exitState` (the terminal subset, once a receipt exists) both draw from this same
vocabulary.

| From state | Triggering event | To state | What the receipt records at this transition |
|---|---|---|---|
| — | Provider accepts an `ExecutionRequest` | `QUEUED` | (no receipt yet — receipts are terminal-only, §4.4) |
| `QUEUED` | Provider begins environment preparation | `PREPARING` | — |
| `PREPARING` | Environment ready, operation begins | `RUNNING` | — |
| `RUNNING` | Operation needs a user decision (e.g. an unfamiliar SSH host key, a destructive-op confirm) | `WAITING_USER` | — |
| `WAITING_USER` | User responds | `RUNNING` | — |
| `RUNNING` | Provider proactively suspends (e.g. app backgrounded, resumable) | `SUSPENDED` | — |
| `SUSPENDED` | Resume | `RUNNING` | — |
| `RUNNING` | Operation completes; provider begins result verification | `VERIFYING` | — |
| `VERIFYING` | Verification passes | `SUCCEEDED` | `exitState=SUCCEEDED`, `verification.state=VERIFIED` (FB-RAT-EXE-009 — see §4.4) |
| `VERIFYING` | Verification was not performed or not possible, but the operation completed cleanly | `SUCCEEDED_UNVERIFIED` | `exitState=SUCCEEDED_UNVERIFIED`, `verification.state=NOT_PERFORMED` or `SKIPPED` |
| `PREPARING` / `RUNNING` / `VERIFYING` | Operation fails cleanly, no side effects occurred | `FAILED_SAFE` | `exitState=FAILED_SAFE`, `sideEffects=[]` (typically) |
| `RUNNING` / `VERIFYING` | Operation fails; side effects may have occurred | `FAILED_SIDE_EFFECTS_POSSIBLE` | `exitState=FAILED_SIDE_EFFECTS_POSSIBLE`, `sideEffects` **MUST** be non-empty (structurally enforced) |
| any non-terminal | `reconnect()` cannot establish state (FB-RAT-EXE-004) | `TARGET_STATE_UNKNOWN` | `exitState=TARGET_STATE_UNKNOWN`, `terminationCause` **MUST** be non-null (structurally enforced) |
| any non-terminal | `cancel()` completes, per the declared `cancellationMode` (FB-RAT-EXE-003) | `CANCELLED` | `exitState=CANCELLED`, `knownStoppedDescription` records what is known to have stopped |

A single-snapshot JSON Schema document cannot itself enforce that a transition **between** two
handle snapshots followed a legal edge above — that is a provider-side state-machine obligation.
`contracts/kotlin/ExecutionContracts.kt`'s `ExecutionLifecycleState` sealed interface is the
runtime encoding a provider implementation programs against, so an exhaustive `when` over it is a
compile-time guarantee every state is handled (not that every *transition* is legal — Kotlin's
type system does not express that without a much heavier encoding this domain has not been asked
to build).

## 3. Platform grounding (blocker-severity, normative)

These facts come from independent validation, not from either repo's `CLAUDE.md`, and are binding
on every provider implementation:

**3.1 — Android W^X (SELinux exec restriction).** Since `targetSdk` 29+, SELinux blocks
`execve()`/exec-mmap of files in app-writable storage. This is **OS-level**, independent of Play
vs. sideload — **no download-and-exec-a-binary path exists on either flavor.** Every
`ExecutionTargetType == LOCAL_ANDROID` provider description **MUST** respect this: a `LOCAL_ANDROID`
`ExecutionRequest.operation.command` **MUST** invoke an already-installed, package-signed
executable or interpreter (a bundled JNI `.so`, a shell built into the OS/Termux-style sandbox this
constellation does not currently ship, an installed app's exported entrypoint) — never a binary
fetched at runtime and exec'd from app-writable storage. This is a documentation-and-review
obligation on provider implementations; it is not (and cannot be) structurally enforced by
`schemas/execution/request.schema.json`, since `operation.command` is necessarily a free string.

**3.2 — Foreground-service (FGS) type ceilings.** `dataSync`/`mediaProcessing` carry a hard
6h/rolling-24h ceiling with **no extension** (`Service.onTimeout()`). Builds, agent loops, and
terminals do not map cleanly to a standard FGS type — they need `specialUse` (manifest
justification string + Play review); USB/serial device work maps to `connectedDevice` (no fixed
timeout). Concretely:

- **Every `ExecutionRequest` and `ExecutionTarget` carries an explicit `fgsType`** field —
  `DATA_SYNC | MEDIA_PROCESSING | SPECIAL_USE | CONNECTED_DEVICE | NONE`
  (`schemas/execution/{request,target}.schema.json`, `$defs/FgsType` — duplicated across the two
  files per this repo's convention, see `docs/ratified/COMMON_CONVENTIONS.md` §5).
- **MUST-level rule, structurally enforced:** an `ExecutionRequest` with `openEnded: true`
  **MUST NOT** declare `fgsType` `DATA_SYNC` or `MEDIA_PROCESSING` — encoded as the first
  `allOf`/`if`/`then` block in `request.schema.json`, and mirrored as a Kotlin `init{}` check in
  `ExecutionContracts.kt`. See `fixtures/execution/invalid/
  request-open-ended-datasync-rejected.invalid.json` (the task brief's required fixture) for the
  structural rejection.
- **`fgsType == SPECIAL_USE` requires a non-blank `specialUseJustification`** — structurally
  enforced by the third `allOf`/`if`/`then` block.
- **Bounded session lengths with explicit user re-authorization**: `ExecutionRequest.budget.
  sessionReauthorization` (`{maxSessionSeconds, requiresUserReauth}`) is a required sub-object on
  every request's `budget` — open-ended work (an agent Loop, a terminal session) does not get an
  indefinite grant just because the operation itself has no natural end.

**3.3 — Android 12+ phantom-process killer.** Assume `SIGKILL` can happen **at any time**, not
just on explicit cancellation. Consequences baked into the receipt/journal model:

- `ExecutionHandle.heartbeat` (`lastHeartbeatUtc`, `heartbeatIntervalSeconds`,
  `missedConsecutive`) — a stale heartbeat is grounds to attempt `reconnect()`, never grounds to
  assume success or failure either way.
- `ExecutionReceipt.terminationCause` — `SIGKILL_SUSPECTED` and `PROCESS_DEATH_UNSPECIFIED` are
  first-class enum values, required non-null whenever `exitState == TARGET_STATE_UNKNOWN`.
- `ExecutionHandle.resumedFromHandleId` — FB-RAT-EXE-005's "support resume/restart/explicit
  migration": a resumed handle after process death gets a **new** `handleId`, linked back to the
  prior one, rather than reusing it (the prior handle's last-known state remains a distinct
  historical fact even if a later resume shows it was wrong).

## 4. Object model (FB-RAT-EXE-001)

**FB-RAT-EXE-001 — ACCEPTED.** Execution object model: `ExecutionTarget`, `ExecutionRequest`,
`ExecutionHandle`, `ExecutionReceipt`, `ArtifactRef`.

### 4.1 `ExecutionTarget` — `schemas/execution/target.schema.json`

A place execution can run. Fields: `id`, `type` (FB-RAT-EXE-010's five-value enum), `displayName`,
`trust` (TOFU state, mirroring core-engine's real `RemoteSessionDriver`/`KnownHosts` pattern —
WP-0 survey §1(h)), `hostFingerprint`, `arch`, `capabilities` (an optional `CapabilityManifest`
reused from common, narrowed to `subjectKind: EXECUTION`), `connectivity`, `cost`,
`provenanceStyle` + `visibilityContract` (§1), `fgsType` (§3.2).

### 4.2 `ExecutionRequest` — `schemas/execution/request.schema.json`

A request to run a typed operation against a target (`targetId`, referenced not embedded). Fields:
`operation` (`operationClass` + `command`/`args`/`workingDirectory`), `workingRevision`,
`environment` (non-secret `envVars` only — see §4.2.1), `secretHandles` (FB-RAT-EXE-007, §4.2.1),
`budget` (wall-clock/output caps + `sessionReauthorization` + the FB-RAT-EXE-006 thermal split,
§4.3), `authorityGrant` (§4.4), `expectedOutputs`, `idempotencyKey` +
`sideEffectExternal`/`externalDuplicateBehavior` (FB-RAT-EXE-008, §4.2.2), `fgsType` + `openEnded`
+ `specialUseJustification` (§3.2).

#### 4.2.1 Secret handles (FB-RAT-EXE-007)

**FB-RAT-EXE-007 — ACCEPTED.** Requests reference purpose-bound secret handles; logs/receipts
never contain secret values.

`ExecutionRequest.secretHandles` is an array of `{handleId, purpose}` — there is **no field
capable of holding a secret value** in this shape, by construction, not by convention. A raw
credential belongs in `security/KeystoreSecret.kt`'s Keystore-backed store, referenced by
`handleId`. `environment.envVars` is documented as non-secret-only, but — unlike `secretHandles` —
its value type (`Map<String, String>`) *can* structurally hold a credential-shaped string; JSON
Schema cannot detect that semantically. See
`fixtures/execution/adversarial/request-secret-value-in-envvars.adversarial.json` (structurally
passes; a real implementation MUST refuse it) for the violation this leaves un-caught at the
schema layer, and `fixtures/execution/adversarial/
receipt-secret-leak-in-log-excerpt.adversarial.json` / `fixtures/execution/valid/
receipt-log-excerpt-redacted-clean.json` for the equivalent leaky-negative/clean-positive pair on
`ExecutionReceipt.logs.excerpt`.

#### 4.2.2 External idempotency (FB-RAT-EXE-008)

**FB-RAT-EXE-008 — ACCEPTED.** External ops declare idempotency key + duplicate behavior before
execution.

`ExecutionRequest.sideEffectExternal: true` (a CI dispatch, a device flash, a git push) **MUST**
pair with a non-blank `idempotencyKey` and a non-null `externalDuplicateBehavior`
(`IGNORE_DUPLICATE | RETURN_PRIOR_RESULT | REJECT_DUPLICATE`) — structurally enforced (second
`allOf`/`if`/`then` block in `request.schema.json`; see `fixtures/execution/invalid/
request-external-missing-idempotency.invalid.json`).

### 4.3 Thermal routing (FB-RAT-EXE-006 — split scope)

**FB-RAT-EXE-006 — EXPERIMENTAL.** Cross-reference `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`
(owned by a separate agent/session in this build-out; not restated here). This document states the
split, per instruction:

- **Thermal STATE OBSERVABILITY is normative P0** — `ExecutionRequest.budget.
  thermalStateObservabilityRequired` (default `true`) and `ExecutionReceipt.resourceSummary.
  thermalStateObserved` are part of this ratified contract. A provider incapable of thermal
  observability MUST NOT accept a request declaring this field `true`.
- **Pause/offload POLICY is experimental, not ratified** —
  `ExecutionRequest.budget.thermalPausePolicyRef` is an optional, nullable pointer into
  `EXPERIMENTAL_DECISIONS.md`'s thermal-policy entry. Null is the only ratified default; a
  non-null value MUST NOT be treated as binding by a provider that does not itself already
  implement that experimental policy.

### 4.4 `ExecutionHandle` — `schemas/execution/handle.schema.json`

Live handle to a started request: `state` (§2), `stateReason` (required non-blank on every
non-plain-success state — FB-RAT-COM-009 accessibility, textual semantics independent of
color/icon), `heartbeat` (§3.3), `cancellationMode` (FB-RAT-EXE-003, below), `reconnectToken` +
`reconnectAttempt` (FB-RAT-EXE-004, below), `resumedFromHandleId` (FB-RAT-EXE-005, §3.3).

**FB-RAT-EXE-003 — ACCEPTED.** Cancellation declaration: every provider declares cancellation
semantics + reports what is known to have stopped. `cancellationMode` (`COOPERATIVE | FORCEFUL |
UNSUPPORTED`) is required on every handle — a caller **MUST** be told cancellation is
`UNSUPPORTED` up front, not discover it via a failed `cancel()` call. `knownStoppedDescription`
records what a `cancel()` attempt actually confirmed stopped, separate from `stateReason` (which
explains *why*, not *what*).

**FB-RAT-EXE-004 — ACCEPTED.** Reconnect uncertainty: if reconnect cannot establish state, report
UNKNOWN rather than termination or success. Structurally enforced via `ExecutionHandle.
reconnectAttempt.outcome` — `STALE_TOKEN_UNKNOWN | INVALID_TOKEN_UNKNOWN | UNREACHABLE_UNKNOWN`
**force** `state == TARGET_STATE_UNKNOWN` (second `allOf`/`if`/`then` block in
`handle.schema.json`). See the task brief's required pair:
`fixtures/execution/valid/handle-reconnect-stale-token-unknown.json` (compliant — `state:
TARGET_STATE_UNKNOWN`) and `fixtures/execution/invalid/
handle-stale-token-false-success-rejected.invalid.json` (structurally identical except `state:
SUCCEEDED` — **rejected**).

### 4.5 `ExecutionReceipt` — `schemas/execution/receipt.schema.json`

The durable, terminal record of one run: `targetSnapshot` (point-in-time copy, not a live
reference — a target's own trust/fingerprint can change later without rewriting history),
`revision` (resolved, e.g. a full commit SHA), `capsuleDigest` (an `IntegrityRef` over the exact
command+args+resolved-env+secret-handle-references that were sent — never secret values),
`timings`, `exitState` (the terminal six-state subset of `ExecutionState`), `terminationCause`
(§3.3), `logs` (`ref`: an `ArtifactRef`, reused from common; `excerpt`: inline, redaction-scanned
— §4.2.1; `redactionApplied`: producer's own claim), `resourceSummary` (§4.3),
`outputs` (`ArtifactRef[]`, reused from common, **not redefined**), `sideEffects`, `verification`,
`provenance` (FB-RAT-COM-008 minimum, duplicated inline per this repo's convention).

**FB-RAT-EXE-009 — ACCEPTED.** Success versus verification: operation success and result
verification are separate states. Structurally enforced (three `allOf`/`if`/`then` blocks in
`receipt.schema.json`): `exitState: SUCCEEDED` requires `verification.state: VERIFIED`;
`SUCCEEDED_UNVERIFIED` requires `NOT_PERFORMED` or `SKIPPED` (never `VERIFIED`, never `FAILED`); a
failed `exitState` must not simultaneously claim `VERIFIED`. See `fixtures/execution/invalid/
receipt-succeeded-without-verification.invalid.json`.

**FB-RAT-EXE-005 — ACCEPTED.** Android recovery posture: assume long-running Android processes may
die; journal work; support resume/restart/explicit migration. See §3.3.

**FB-RAT-EXE-011 — DEFERRED.** Release signing route — cross-reference `docs/non_ratified/
DEFERRED_DECISIONS.md` (owned by a separate agent/session). Not designed here; no field in this
contract assumes a particular signing route. If a future `ExecutionRequest.operationClass` needs
one (e.g. a release-build operation), that is an addition to that other, not-yet-written document,
not a retrofit onto this one.

**FB-RAT-EXE-010 — ACCEPTED.** Initial providers: local Android, SSH, Raspberry Pi, GitHub
Actions, Gitea Actions. `ExecutionTargetType` (`schemas/execution/target.schema.json`) is a closed
five-value enum for exactly this list; a sixth provider type is a MINOR schema addition, never a
repurposed existing value.

## 5. Provider interface

Exact signatures per the WP-1 task brief (`contracts/kotlin/ExecutionContracts.kt`):

```kotlin
interface ExecutionProvider {
    val descriptor: ExecutionProviderDescriptor
    suspend fun prepare(request: ExecutionRequest): PreparedExecution
    suspend fun start(prepared: PreparedExecution): ExecutionHandle
    fun observe(handle: ExecutionHandle): Flow<ExecutionEvent>
    suspend fun cancel(handle: ExecutionHandle, mode: CancelMode): CancelResult
    suspend fun reconnect(token: ReconnectTokenHandle): ExecutionHandle?
}
```

`Flow` (kotlinx-coroutines) on `observe()` is this domain's one named dependency exception, matching
the workspace-kernel domain's own exception per the WP-1 task brief — every other type in
`ExecutionContracts.kt` is stdlib + `java.time.Instant` only. `reconnect()`'s `null` return means
"this token does not correspond to any handle this provider has ever issued" — a different failure
mode from "the handle exists but its state cannot be established," which is instead an
`ExecutionHandle` with `state: TARGET_STATE_UNKNOWN` (§4.4), never a `null`.

`prepare()` is where the FB-RAT-STR-004 visibility-contract gate (§1) and the FB-RAT-COM-012
authority-grant check (§6) MUST run — before any bytes leave the device for a
`USER_INITIATED_REMOTE`/`CI_PIPELINE` target, and before any operation starts on any target
regardless of provenance style.

## 6. No contract bypass (FB-RAT-COM-012)

`ExecutionRequest.authorityGrant` is a **required, non-null** field — there is no shape of this
contract that omits an authority-grant reference (`schemas/execution/request.schema.json`;
mirrored as a non-nullable constructor parameter with a non-blank `grantId` check in
`ExecutionContracts.kt`). This is the execution domain's concrete instance of FB-RAT-COM-012 ("a
feature bypassing envelope/errors/authority/receipts is a prototype, not architecture") —
`AuthorityGrantRef` is deliberately the *minimal* reference shape (`grantId` + `scopes`) this
domain needs to refuse to model a request outside the authority seam; the full grant/capability-
ladder engine belongs to the separate Execution Contract + Authority engine domain named in the
WP-0 survey (§1(c)) and is not invented here.

Two further per-domain bypass instances, mirroring `COMMON_CONVENTIONS.md` §12's pattern:

- Starting work on a `USER_INITIATED_REMOTE`/`CI_PIPELINE` target without checking
  `visibilityContract.isComplete()` first is a bypass (§1).
- Emitting an `ExecutionReceipt` with `exitState: SUCCEEDED` and `verification.state` anything
  other than `VERIFIED` is a bypass of FB-RAT-EXE-009 (§4.5) — success and verification are
  separate states precisely so a receipt cannot silently claim both hollowly.

## 7. Conformance test-class coverage

Following `COMMON_CONVENTIONS.md` §11's eight-class table, applied to this domain's four schemas.
The task brief asked for six explicitly (golden serialization, state-machine, adversarial,
recovery, compatibility, provider-conformance); `PERFORMANCE` and `ACCESSIBILITY` are included for
parity with the full `ConformanceTestClass` enum (`contracts/kotlin/CommonContracts.kt`) and to
keep this domain's `ConformanceSuite` instance genuinely eight-row, per FB-RAT-DIST-004.

| # | Test class | Coverage in this domain | JVM-testable |
|---|---|---|---|
| 1 | Golden serialization | `fixtures/execution/valid/*.json` — one fixture per lifecycle-significant state per schema (e.g. all six `ExecutionReceipt.exitState` values have a dedicated valid fixture). | **YES** |
| 2 | State transition | The lifecycle table (§2); structurally, `handle.schema.json`'s `reconnectAttempt`→`state` if/then and `receipt.schema.json`'s `exitState`↔`verification.state` if/thens (§4.4, §4.5) are the state-machine-adjacent rules a single-snapshot schema *can* enforce. | **YES** for the snapshot-level if/then rules; **PARTIAL** for true cross-snapshot transition legality (provider-side obligation, not schema-checkable — see §2's closing paragraph). |
| 3 | Adversarial | `fixtures/execution/adversarial/*` — secret value in `envVars`, secret leak in a log excerpt, incomplete visibility contract. Required-by-brief structural rejections live in `invalid/` instead (they ARE structurally catchable, unlike the three semantic-only adversarial cases): open-ended+`DATA_SYNC`, stale-token false-success, missing external idempotency. | **YES** |
| 4 | Provider conformance | Request/response shape and the `ExecutionProvider` interface signatures are JVM-testable against fixtures. Live SSH/CI/device provider behavior is owner-verified only (`CLAUDE.md` "Environment honesty" — no device/emulator/board/SSH host in this build container). | **PARTIAL** |
| 5 | Recovery | `resumedFromHandleId`, `terminationCause`, `reconnectAttempt`'s forced-`TARGET_STATE_UNKNOWN` rule are all JVM-testable structurally. The actual Android-process-death/phantom-kill scenario is device-only. | **PARTIAL** |
| 6 | Performance | No device/emulator exists in this build container. | **NO** |
| 7 | Accessibility | `stateReason` non-blank requirement on non-plain-success `ExecutionHandle` states (§4.4) is JVM-testable structurally; actual screen-reader/assistive-tech behavior is device-only. | **PARTIAL** |
| 8 | Compatibility | Every top-level shape here inherits `ContractEnvelope`'s `schemaVersion`/`unknownFields` handling from `COMMON_CONVENTIONS.md` §3 — no separate compatibility mechanism is introduced in this domain. | **YES** (inherited) |

---

## Forward pointers (owned elsewhere, not restated here)

- **Execution Contract + Authority engine's grant/capability-ladder shape** — WP-0 survey §1(c),
  create-new, a separate domain. `ExecutionRequest.authorityGrant` (§6) is the minimal reference
  this document needs and no more.
- **FB-RAT-EXE-006's thermal pause/offload POLICY** — `docs/non_ratified/
  EXPERIMENTAL_DECISIONS.md`, owned by a separate agent/session (§4.3).
- **FB-RAT-EXE-011's release signing route** — `docs/non_ratified/DEFERRED_DECISIONS.md`, owned by
  a separate agent/session (§4.5).
- **Loop Engineering's adaptation of `GraphRunner`/`LoopBudget`/`GraphRunLedger`**
  (`core-engine/src/main/java/dev/aarso/domain/loop/`) — WP-1L, not this document. An
  `ExecutionRequest` with `operation.operationClass: AGENT_LOOP` (§4.2) is how a future Loop-run
  adapter would invoke this contract, but this document does not specify that adapter.
- **Device Broker** (WP-0 survey §1(e)) — `core-engine/src/main/java/dev/aarso/data/DeviceRepo.kt`
  + `domain/device` + the SSH spine (`domain/remote/*`) are the real substrate a `SSH_HOST` /
  `RASPBERRY_PI` `ExecutionProvider` implementation would wrap; this document specifies the
  contract such an implementation must satisfy, not the implementation itself.
