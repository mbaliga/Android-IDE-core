# Common Conventions — the `foundations` domain

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED. **Scope:** the shared contract vocabulary every other domain in the
Fonebrew constellation builds on — normative language, identity, versioning, time, integrity,
append-only facts, errors, provenance, accessibility, and the conformance-test discipline that
gates release. This document is the citation target for `FB-RAT-COM-001`…`FB-RAT-COM-012` and
`FB-RAT-DIST-004`. It does not repeat WP-1L's loop-specific freeze (`docs/ratified/loops/
LOOP_FROZEN_CONCEPTS_WP1L_G0.md`) or any other domain's own contract doc — those are separate
outputs, cross-referenced only.

**Why this domain is first.** Every other WP-1 domain (workspace kernel, execution contract/
authority, loop engineering, device broker, integration lanes, search) references
`ContractEnvelope`/`ErrorEnvelope`/`ArtifactRef` from this one. Per the WP-0 survey (`docs/
WP0_SURVEY.md` §1(a)), no repo in the constellation has anything resembling these types today —
this is genuinely greenfield, not a wrapper over existing code, so the shapes fixed here become
load-bearing the moment a second domain starts building against them.

---

## 1. Normative language (FB-RAT-COM-001)

**FB-RAT-COM-001 — ACCEPTED.** MUST/SHOULD/MAY are used in every ratified doc and schema in this
constellation, and **only** for testable obligations — a requirement a conformance fixture (§8)
can actually assert pass/fail against. Prose that states intent, rationale, or preference does
not use these keywords. Where this document or a schema's `description` field says "MUST", a
corresponding fixture under `fixtures/` either demonstrates the enforcement (structural) or
carries a sibling `.expected.txt` naming the non-structural check a real implementation is
obligated to perform (§9).

## 2. Stable identity (FB-RAT-COM-002)

**FB-RAT-COM-002 — ACCEPTED.** Every durable object has a globally unique stable ID, independent
of its display name or path. Concretely, in this domain's shapes:

| Type | Stable-ID field | Independent of |
|---|---|---|
| `ContractEnvelope<T>` | `objectId` | the payload's own display name, the storage path it's written to |
| `ErrorEnvelope` | `objectId` (optional, back-reference) | — |
| `CapabilityManifest` | `manifestId` (+ optional `subjectId` for the concrete subject) | the manifest's file name if persisted |
| `ArtifactRef` | `id` | `storageLocation.locator` — an artifact keeps its `id` if moved or re-hosted |
| `MigrationPlan` | `migrationId` | the git commit or PR that introduced it |
| `ConformanceSuite` | `suiteId` | `name`, which is display-only |

Readers **MUST NOT** infer identity from a display name, file path, or `storageLocation` —
those are allowed to change without the object losing identity. `objectId`/`id`/`manifestId`/
etc. **MUST NOT** change once assigned.

## 3. Schema compatibility (FB-RAT-COM-003)

**FB-RAT-COM-003 — ACCEPTED.** Every serialized contract carries `schemaVersion` +
`producerVersion`; readers reject unknown MAJOR, preserve unknown MINOR fields on round-trip.

This is operationalized, not left as prose, in `schemas/common/envelope.schema.json`:
`schemaVersion` is pattern-pinned to `^1\.\d+\.\d+$` because that schema document **is** major
version 1 of the `ContractEnvelope` contract — an envelope claiming `schemaVersion: "2.0.0"`
fails **structural** JSON Schema validation against this document today, not just a reader's
runtime discipline (see `fixtures/common/invalid/envelope-unsupported-major.invalid.json`, and
§9 below for the actual pass/fail run). A future major-2 envelope shape ships as a new schema
document (`envelope.v2.schema.json` or similar), not a loosened pattern on this one.

`producerVersion` lives inside `ProducerRef.version` (nested under `producer`, not a top-level
envelope field) — the producing component's own release version, independent of
`schemaVersion`, which versions the *contract shape* only.

**Unknown-field preservation.** Every top-level shape in this domain (`ContractEnvelope`,
`ErrorEnvelope`, `CapabilityManifest`, `ArtifactRef`, `MigrationPlan`, `ConformanceSuite`) carries
an `unknownFields` object. A decoder that receives a MINOR-newer payload than it understands
**MUST** stash fields it does not recognize into `unknownFields` rather than drop them, and an
encoder re-serializing that object **SHOULD** merge `unknownFields` back into the top-level
object on the wire (JSON Schema has no native "preserve-and-passthrough" keyword, so this is
modeled as an explicit field rather than relying on `additionalProperties: true` alone — the
latter lets a validator *accept* unknown fields but does nothing to guarantee an
decode-then-re-encode round-trip actually keeps them).

Only `ContractEnvelope` itself carries `schemaVersion`. `ErrorEnvelope`, `CapabilityManifest`,
`ArtifactRef`, and `ConformanceSuite` do not repeat it — they are typically carried as the
`payload` of a `ContractEnvelope<T>` and inherit `schemaVersion`/`objectId`/`createdAtUtc`/
`producer` from that outer envelope. `MigrationPlan` is the one exception with version fields of
its own (`fromSchemaVersion`/`toSchemaVersion`) — those describe the *other* contract's version
transition this plan performs, not `MigrationPlan`'s own schema shape.

## 4. Time semantics (FB-RAT-COM-004)

**FB-RAT-COM-004 — ACCEPTED.** UTC instants (+ optional source tz); monotonic sequence preferred
over wall-clock for ordering.

`ContractEnvelope.createdAtUtc` is an RFC 3339 UTC instant (`format: date-time` in the schema;
`java.time.Instant` in Kotlin — see `contracts/kotlin/CommonContracts.kt`). `sourceTimezone` is
an optional IANA zone name for **display only**; a reader **MUST NOT** use it for ordering.
`sequence`, when present, **MUST** be preferred over `createdAtUtc` for ordering two envelopes
from the same producer — clock skew across devices/services makes wall-clock ordering
unreliable, sequence numbers are not.

## 5. Integrity (FB-RAT-COM-005)

**FB-RAT-COM-005 — ACCEPTED.** SHA-256 (or stronger) + byte length on artifacts/manifests/
receipts.

`IntegrityRef` (`algorithm` ∈ `{SHA-256, SHA-384, SHA-512}`, `digestHex`, `byteLength`) is the
shared shape, `$ref`-able within a schema document (duplicated as a `$defs` entry in both
`envelope.schema.json` and `artifact-ref.schema.json` rather than cross-file `$ref`'d, so each
schema file validates standalone without a multi-file resolver). The schema goes one step past
the bare "SHA-256+" requirement: an `allOf`/`if`/`then` block pins `digestHex`'s exact hex length
to the declared `algorithm` (64/96/128 hex chars for SHA-256/384/512), so a digest of the wrong
length for its claimed algorithm is a **structural** rejection, not just a semantic one.

What JSON Schema **cannot** check: whether `digestHex` is the *correct* digest of the actual
payload bytes. That is why `fixtures/common/adversarial/
envelope-integrity-digest-mismatch.adversarial.json` exists — a syntactically well-formed,
schema-passing digest that does not match its payload — with a sibling `.expected.txt` stating
the runtime recompute-and-compare obligation (§9).

## 6. Append-only facts (FB-RAT-COM-006)

**FB-RAT-COM-006 — ACCEPTED.** Events/receipts append-only; retries carry idempotency keys.

`ContractEnvelope.idempotencyKey` is the mechanism: a caller-supplied key a receiving store uses
to de-duplicate a retried write of the same logical event. It is nullable **only** for envelopes
carrying no retryable side effect (e.g. a pure read result) — an envelope that performs a
retryable write and omits `idempotencyKey` is a contract violation, not a valid "don't care"
state. This domain does not itself define an event-log or receipt-store shape (that is
domain-specific — e.g. `GraphRunLedger`'s `LedgerCapture` rows in the loop domain, or the
integration domain's `ImportReceipt`, §10); it defines the field every such log entry's envelope
carries so append-only + idempotent-retry behavior is checkable the same way everywhere.

## 7. Error taxonomy (FB-RAT-COM-007)

**FB-RAT-COM-007 — ACCEPTED.** Stable code, severity, retryability, side-effect state, technical
detail, recovery action.

`ErrorEnvelope` (`schemas/common/error.schema.json`) carries exactly these six, plus
`userMessage` for accessibility (§9) and an optional back-reference `objectId`:

- `code` — stable, `SCREAMING_SNAKE_CASE`, never reused for a different meaning across releases.
- `severity` — `INFO` / `WARNING` / `ERROR` / `CRITICAL`.
- `retryable` — whether retrying the same operation (with the same `idempotencyKey`, if any)
  can plausibly succeed.
- `sideEffectState` — `NONE` / `PARTIAL` / `COMPLETED` / `UNKNOWN`. `UNKNOWN` **MUST** be used
  rather than guessing `NONE` when the producer genuinely cannot determine the outcome (e.g. a
  network write that timed out after the request left the device) — guessing wrong here is worse
  than admitting uncertainty, since a caller may use this field to decide whether blind retry is
  safe.
- `detail` — technical detail for logs/diagnostics. **MUST NOT** contain secrets or API keys
  (binding rule 5 in `CLAUDE.md`: keys are never logged) — see
  `fixtures/common/adversarial/error-secret-leak-in-detail.adversarial.json` for the fixture
  this guards against and the redaction obligation it documents.
- `recoveryAction` — a concrete next step. Never just repeats `detail`.

## 8. Provenance minimum (FB-RAT-COM-008)

**FB-RAT-COM-008 — ACCEPTED.** Every execution/model/import result records source location,
project revision, initiating principal, evidence links.

`ArtifactRef.producerReceipt` (`ProducerReceiptRef` in Kotlin) is where this lives:
`sourceLocation`, `projectRevision`, `initiatingPrincipal` are all **required** fields on that
sub-object (not optional prose — see the worked example in §10.4), and `evidenceLinks` is an
open array for supporting refs (logs, receipts, screenshots). `CapabilityManifest.producer` and
`MigrationPlan.issuedAtUtc` carry a lighter provenance touch (who + when) appropriate to those
being declarative/governance objects rather than execution results — the full four-field
provenance minimum applies specifically to *results* (artifacts, receipts), per the rule's own
wording ("execution/model/import result").

## 9. Accessibility semantics (FB-RAT-COM-009)

**FB-RAT-COM-009 — ACCEPTED.** Every state/action has textual semantics; color/gesture/haptic/
position never the sole carrier.

`ErrorEnvelope.userMessage` is this domain's concrete instance: a required, non-blank,
plain-language string carrying the textual semantics of an error state, independent of whatever
color/icon/haptic a UI surface also renders alongside it. A conformance fixture for any UI
surface that renders `ErrorEnvelope` **MUST** assert the surface remains legible with color and
icon stripped — this document does not itself own a UI surface to test, so that assertion is a
forward pointer to whichever domain renders `ErrorEnvelope` (most directly the workspace kernel's
error-surface UI, not built by this work package).

## 10. Common envelope shapes (FB-RAT-COM-011)

**FB-RAT-COM-011 — ACCEPTED.** `ContractEnvelope`, `ErrorEnvelope`, `CapabilityManifest`,
`ArtifactRef`, `ExecutionReceipt`, `ImportReceipt`, `MigrationPlan`, `ConformanceSuite` as shared
concepts.

Six of these eight are fully specified by this document + `schemas/common/*.schema.json` +
`contracts/kotlin/CommonContracts.kt`. Two are declared but **not** specified here:

- **`ImportReceipt`** — owned by the integration domain (INT-024, a different WP-1 agent).
  `contracts/kotlin/CommonContracts.kt` declares an open marker interface,
  `ImportReceiptPayload`, so that domain can define `ImportReceipt` as (in effect) a
  `ContractEnvelope<T : ImportReceiptPayload>` without a circular module dependency. No fields
  are invented here.
- **`ExecutionReceipt`** — owned by the Execution Contract + Authority engine domain (WP-0
  survey (c) — create-new; no existing precedent in any of the four repos beyond Studio's narrow
  entitlement gate, which is not a receipt system). Same treatment: an open marker interface,
  `ExecutionReceiptPayload`, with zero invented fields — a forward pointer, not a self-ratified
  shape.

Below is one worked, schema-**validated** JSON example per fully-specified shape. Every example
on this page was checked with `jsonschema.Draft202012Validator` against its schema in
`schemas/common/` before being pasted in — see the WP-1 handoff report for the exact command and
pass/fail transcript; they are not hand-typed prose that merely looks plausible.

### 10.1 `ContractEnvelope<ErrorEnvelope>` — `schemas/common/envelope.schema.json`

```json
{
  "schemaVersion": "1.0.0",
  "objectId": "01J8Z2G0S4Y5H1D9C3V6T8N2QK",
  "createdAtUtc": "2026-08-07T09:14:31Z",
  "sourceTimezone": "Asia/Kolkata",
  "sequence": 4821,
  "producer": {
    "name": "core-engine.git.contents-api",
    "version": "0.13.0",
    "instanceId": null
  },
  "idempotencyKey": "sync-req-8f3c1e2a-retry-1",
  "integrity": {
    "algorithm": "SHA-256",
    "digestHex": "9b37278e52d5a6424654e45b23a633ff2f73c01bd8743dfca6b557a89cbeff0e",
    "byteLength": 467
  },
  "payload": {
    "code": "GIT_AUTH_EXPIRED",
    "severity": "ERROR",
    "retryable": true,
    "sideEffectState": "NONE",
    "detail": "GitHub REST contents API returned 401 for GET /repos/o/r/contents/README.md; token last refreshed 2026-08-06T10:02:11Z.",
    "recoveryAction": "Re-authenticate the GitHub connection in Settings > Global > Source Control, then retry the sync.",
    "userMessage": "Your GitHub connection expired. Reconnect it to keep syncing this project.",
    "objectId": "01J8Z1FVZ0T7Q8W3B7K2R6M4XN"
  },
  "unknownFields": {}
}
```

### 10.2 `ErrorEnvelope` (standalone) — `schemas/common/error.schema.json`

```json
{
  "code": "GIT_AUTH_EXPIRED",
  "severity": "ERROR",
  "retryable": true,
  "sideEffectState": "NONE",
  "detail": "GitHub REST contents API returned 401 for GET /repos/o/r/contents/README.md; token last refreshed 2026-08-06T10:02:11Z.",
  "recoveryAction": "Re-authenticate the GitHub connection in Settings > Global > Source Control, then retry the sync.",
  "userMessage": "Your GitHub connection expired. Reconnect it to keep syncing this project.",
  "objectId": "01J8Z1FVZ0T7Q8W3B7K2R6M4XN"
}
```

### 10.3 `CapabilityManifest` — `schemas/common/capability-manifest.schema.json`

```json
{
  "manifestId": "01J8Z3H7N2X4M6P8Q1R3S5T7VW",
  "subjectKind": "DEVICE",
  "subjectId": "usb-stk500-arduino-uno-r3",
  "supportedOperations": ["device.flash", "device.readFuses", "device.reset"],
  "limits": {
    "maxConcurrentOperations": 1,
    "maxPayloadBytes": 32768,
    "flashTimeoutMs": 60000
  },
  "versions": {
    "subjectVersion": "stk500v1-bootloader-2.0",
    "protocolVersion": "STK500v1"
  },
  "targetRequirements": {
    "usbHostMode": true,
    "minAndroidSdk": 31,
    "abi": "arm64-v8a"
  },
  "producer": {
    "name": "core-engine.device.arduino-cli",
    "version": "0.13.0",
    "instanceId": null
  },
  "issuedAtUtc": "2026-08-07T09:15:02Z",
  "unknownFields": {}
}
```

(`subjectId`/`limits`/`targetRequirements` here name `core-engine/src/main/java/dev/aarso/
domain/device` per CLAUDE.md's repo map and the WP-0 survey's flag on it — the survey confirmed
the `device` package exists but did **not** independently re-verify the specific
`ArduinoCli`/`Stk500` file paths beyond that, so treat this example's subject as illustrative of
the shape, not as a re-confirmation of those exact files.)

### 10.4 `ArtifactRef` — `schemas/common/artifact-ref.schema.json`

```json
{
  "id": "01J8Z4K9P3R5T7V9W1X3Y5Z7AB",
  "mediaType": "application/vnd.android.package-archive",
  "digest": {
    "algorithm": "SHA-256",
    "digestHex": "7743a49bf42f5b901f4abb34e588ed0f860fef14766f96d0709c81f7afbe4273",
    "byteLength": 40
  },
  "sizeBytes": 40,
  "producerReceipt": {
    "receiptObjectId": "01J8Z5M1Q4S6U8W1Y2A4C6E8GH",
    "producer": {
      "name": "core-engine.builds.ci-trigger",
      "version": "0.13.0",
      "instanceId": null
    },
    "sourceLocation": "github.com/owner/android-ide-core@apk-dist:aarso-sd.apk",
    "projectRevision": "ab3567ecf1a2b3c4d5e6f7089a1b2c3d4e5f6789",
    "initiatingPrincipal": "gh-actions:build-test#4821",
    "evidenceLinks": ["https://github.com/owner/android-ide-core/actions/runs/4821"]
  },
  "storageLocation": {
    "kind": "GIT_HOST",
    "locator": "github.com/owner/android-ide-core:apk-dist:aarso-sd.apk"
  },
  "verificationState": "VERIFIED",
  "unknownFields": {}
}
```

(`storageLocation.kind: "GIT_HOST"` with a `github.com/...` locator matches the constellation's
actual git-integration shape per the WP-0 survey §3: pure REST request-builders
`GitContentsApi.kt`/`GitTreeApi.kt` against GitHub/Gitea, no JGit or embedded git library
anywhere. `sourceLocation` here also matches CLAUDE.md's real APK-delivery convention — the
orphan `apk-dist` branch carrying `aarso-sd.apk`.)

### 10.5 `MigrationPlan` — `schemas/common/migration-plan.schema.json`

```json
{
  "migrationId": "01J8Z6N3R6T8V1X3Z5B7D9F1HJ",
  "contractRef": "schemas/common/envelope.schema.json",
  "fromSchemaVersion": "1.0.0",
  "toSchemaVersion": "1.1.0",
  "compatibility": "MINOR",
  "dataMigration": {
    "steps": [
      "Add default null for the new optional 'sequence' field on any stored envelope missing it.",
      "No existing field is renamed, removed, or retyped; old readers continue to work unmigrated."
    ],
    "reversible": true
  },
  "rollback": {
    "possible": true,
    "steps": [
      "Drop the 'sequence' field from stored envelopes (safe: MINOR readers already treat it as optional)."
    ]
  },
  "issuedAtUtc": "2026-08-07T09:16:44Z",
  "unknownFields": {}
}
```

### 10.6 `ConformanceSuite` — `schemas/common/conformance-suite.schema.json`

```json
{
  "suiteId": "01J8Z7P5T8V1X3Z5B7D9F1H3JK",
  "name": "CommonContracts envelope+error conformance",
  "contractRef": "schemas/common/envelope.schema.json",
  "testClasses": [
    { "testClass": "GOLDEN_SERIALIZATION", "jvmTestable": "YES", "notes": null },
    { "testClass": "STATE_TRANSITION", "jvmTestable": "YES", "notes": "VerificationState/SideEffectState enum transitions" },
    { "testClass": "ADVERSARIAL", "jvmTestable": "YES", "notes": "unsupported-major, digest-mismatch, path-traversal fixtures" },
    { "testClass": "PROVIDER_CONFORMANCE", "jvmTestable": "PARTIAL", "notes": "cloud provider SSE shape checkable; on-device llama.cpp path is owner-verified" },
    { "testClass": "RECOVERY", "jvmTestable": "PARTIAL", "notes": "decode-time recovery logic is JVM-testable; crash-recovery UI flow is device-only" },
    { "testClass": "PERFORMANCE", "jvmTestable": "NO", "notes": "no device/emulator in the build container" },
    { "testClass": "ACCESSIBILITY", "jvmTestable": "PARTIAL", "notes": "userMessage presence/non-emptiness is JVM-testable; screen-reader behavior is device-only" },
    { "testClass": "COMPATIBILITY", "jvmTestable": "YES", "notes": "MigrationPlan fixtures + reject-unknown-major fixture" }
  ],
  "fixtureDirectoryRef": "fixtures/common",
  "definitionOfReady": true,
  "unknownFields": {}
}
```

## 11. Conformance requirement — the 8 test classes (FB-RAT-COM-010, FB-RAT-DIST-004)

**FB-RAT-COM-010 — ACCEPTED.** Golden serialization, state-machine, adversarial, recovery,
compatibility, and provider tests per contract. **FB-RAT-DIST-004 — ACCEPTED.** Definition of
Ready/Done: contract-first DoR/DoD + the 8 conformance test classes as release governance.

`FB-RAT-DIST-004` names eight classes (two more than the six `FB-RAT-COM-010` lists by name —
`PERFORMANCE` and `ACCESSIBILITY` round it out); this table is the authoritative eight, with the
JVM-testability classification the WP-1 handoff pack specifies (`yes`/`partial`/`no`), reused
verbatim rather than re-derived:

| # | Test class | What it proves | JVM-testable |
|---|---|---|---|
| 1 | Golden serialization | A fixed, checked-in example encodes/decodes to byte-identical (or semantically-identical, field-by-field) output across releases — catches accidental wire-shape drift. | **YES** |
| 2 | State transition | Enum/state fields only move through their declared legal transitions (e.g. `VerificationState` `UNVERIFIED → VERIFIED/FAILED`, never a backward-then-forward loop that skips a re-check) — catches illegal state jumps. | **YES** |
| 3 | Adversarial | Malformed, boundary, and semantically-hostile inputs (unsupported major, digest mismatch, path traversal, secret leakage, privilege escalation) are rejected or handled per their documented obligation, not silently accepted. | **YES** |
| 4 | Provider conformance | A concrete provider/implementation (a specific cloud model provider's SSE stream, a specific device's STK500 dialect, a specific language server) actually satisfies the contract it claims to. | **PARTIAL** — request/response shape and parsing logic are JVM-testable against fixtures; live on-device/network behavior is owner-verified only (`CLAUDE.md` "Environment honesty" — no device/emulator/board/SSH host in the build container). |
| 5 | Recovery | After a crash, corrupt-write, or partial-failure mid-operation, the system reaches a consistent, legible state rather than a bricked or silently-wrong one. | **PARTIAL** — decode-time recovery logic (e.g. "reject a corrupt envelope, don't half-apply it") is JVM-testable; the actual crash-recovery UI flow (`dev.aarso:crash-recovery`, `CrashRecoveryActivity`) is device-only per the same environment-honesty constraint. |
| 6 | Performance | The contract's operations meet a stated latency/throughput/memory bound on the FB-RAT-STR-007 benchmark target (16GB ARM64 flagship, handheld+docked). | **NO** — no device or emulator exists in this build container at all; this class is entirely owner-verified. |
| 7 | Accessibility | Every state/action this contract renders through has textual semantics that survive with color/gesture/haptic/position stripped (FB-RAT-COM-009). | **PARTIAL** — structural presence/non-emptiness of a textual-semantics field (e.g. `ErrorEnvelope.userMessage`) is JVM-testable; actual screen-reader/assistive-tech behavior is device-only. |
| 8 | Compatibility | A MAJOR-version-unknown payload is rejected; a MINOR-newer payload round-trips with unknown fields preserved; a `MigrationPlan`'s rollback claims match its `possible` flag. | **YES** |

This table is itself expressible as a `ConformanceSuite` (§10.6 is exactly that, for this
domain's own envelope+error contracts) — every other domain's contract doc is expected to ship
its own `ConformanceSuite` instance following this same eight-row shape, per `FB-RAT-DIST-004`'s
Definition-of-Ready requirement that fixtures exist **before** feature implementation.

## 12. No contract bypass (FB-RAT-COM-012)

**FB-RAT-COM-012 — ACCEPTED.** A feature bypassing envelope/errors/authority/receipts is a
prototype, not architecture.

Concretely for this domain: reading `ContractEnvelope.payload` without first checking
`integrity` (when present) is a bypass (§5, §10.1's adversarial counterpart in
`fixtures/common/adversarial/envelope-integrity-digest-mismatch.adversarial.json`). Trusting
`CapabilityManifest.supportedOperations` without validating it against the subject's actual
vocabulary is a bypass (§10.3's adversarial counterpart,
`fixtures/common/adversarial/capability-manifest-privilege-escalation.adversarial.json`).
Opening an `ArtifactRef.storageLocation` without path-safety checks is a bypass (§10.4's
adversarial counterpart, `fixtures/common/adversarial/
artifact-ref-path-traversal.adversarial.json`). None of these are hypothetical — each has a
checked-in fixture demonstrating the exact shape of the bypass a real implementation must not
commit.

---

## Forward pointers (owned elsewhere, not restated here)

- **`ImportReceipt`'s full shape** — integration domain, `INT-024` (§10 above).
- **`ExecutionReceipt`'s full shape** — Execution Contract + Authority engine domain (§10 above).
- **Loop-specific frozen concepts** (`.floop` container format, semantic digest, validation rule
  codes, capability-ID namespace) — `docs/ratified/loops/LOOP_FROZEN_CONCEPTS_WP1L_G0.md`, a
  separate gate (WP-1L-G0) this document does not restate or supersede.
- **Search contracts** — WP-6, not this work package. Per the WP-0 survey §1(g), search is
  already fully wired into the UI (`core-engine/src/main/java/dev/aarso/{domain,data,ui}/
  search/`) — treat any future search-contracts corpus as modeling real, shipped substrate, not
  greenfield.
- **Workspace Kernel / Execution Contract + Authority engine / Device Broker naming** — WP-0
  survey (b)/(c)/(e): all create-new or extend-existing-substrate-without-that-name; this
  document does not define those domains' own shapes, only the envelope they will carry theirs
  in.
