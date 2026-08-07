# Device State & Safety Spec — the `devices` domain

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**Status:** RATIFIED for `FB-RAT-DEV-001` through `FB-RAT-DEV-009` (all ACCEPTED). `FB-RAT-DEV-010`
is **EXPERIMENTAL** (cross-reference `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`, owned by
another agent) and `FB-RAT-DEV-011` is **DEFERRED** (cross-reference `docs/non_ratified/
DEFERRED_DECISIONS.md`, owned by another agent) — both are stated here only to the extent this
document's own object model and lifecycle need to cite them, not restated or re-decided.

**Scope:** the `devices` domain named in the WP-1 task brief — `DeviceIdentity`,
`DeviceConnection`, `FirmwareArtifact`, `DeviceSnapshot`, `DeviceOperation`, and the flash
contract every USB-attached embedded target flows through. This document is the citation target
for `FB-RAT-DEV-001`…`FB-RAT-DEV-011`. `docs/ratified/DISTRIBUTION_CAPABILITY_SPLIT.md` is a
sibling document in this same work package (device+distribution) — it does not depend on this
one and this one does not depend on it; they are grouped by owning agent, not by contract
coupling.

This document builds on, and does not repeat, `docs/ratified/COMMON_CONVENTIONS.md`'s
`ContractEnvelope`/`ErrorEnvelope`/`CapabilityManifest`/`ArtifactRef`/`IntegrityRef` — every
top-level shape below is typically carried as the `payload` of a `ContractEnvelope<T>`
(`schemas/common/envelope.schema.json`), and `ArtifactRef`/`CapabilityManifest`/`ErrorEnvelope`
are reused as-is (cross-file `$ref`, matching the execution domain's convention — see
`schemas/execution/target.schema.json`) inside `FirmwareArtifact.artifact`,
`DeviceIdentity.capabilities`, and `DeviceOperation.receipt.errorEnvelope` respectively; none is
redefined here. Small shared sub-shapes (`IntegrityRef`, `BoardRef`, `UsbFamily`) are duplicated
locally as `$defs` across `schemas/devices/*.schema.json`, per `COMMON_CONVENTIONS.md` §5's
convention, so every schema file in this domain validates standalone without a multi-file
resolver.

**Repo-placement note (WP-0 survey, `docs/WP0_SURVEY.md` §1(e)).** There is no existing
"Device Broker" abstraction by that name anywhere in the constellation, but there is real
substrate this domain's eventual implementation would wrap, not reinvent:
`core-engine/src/main/java/dev/aarso/data/DeviceRepo.kt` (runs a `DeviceRecipe` → `ExecRequest`
over the SSH spine via `RemoteSessionDriver`, only proceeds on an already-vetted host, returns
raw streamed output, never paraphrased) plus `core-engine/src/main/java/dev/aarso/domain/device/`
(the WP-0 survey confirms this package exists as one of `core-engine`'s 40 `domain/` subpackages;
it flags `ArduinoCli`/`usb/IntelHex`+`Stk500` as CLAUDE.md-sourced claims not independently
re-verified in that survey pass). All package paths cited anywhere in this document use the real
`core-engine/src/main/java/dev/aarso/...` location, not the stale `app/src/main/java/...` claim
in both repos' `CLAUDE.md` files. The real CI gate command is
`./gradlew --no-daemon :core-engine:testFullDebugUnitTest :core-engine:testPlayDebugUnitTest
:core-engine:checkLicense` (read from `.github/workflows/ci.yml`, not from either stale
`CLAUDE.md`).

**No git library dependency note (WP-0 survey §3, relevant only in passing here):** this
constellation has no JGit or any embedded git library — all git integration is pure REST
request-builders (`GitContentsApi.kt`/`GitTreeApi.kt`) executed by an injected transport. Not
directly load-bearing for the device domain (a `FirmwareArtifact.artifact.storageLocation` MAY
have `kind: GIT_HOST` when a firmware image is checked into a repo, exactly as `ArtifactRef`
already supports for any domain), but noted here since the task brief flagged it as relevant to
this agent's git-adjacent surfaces.

---

## 1. Device Broker (FB-RAT-DEV-001)

**FB-RAT-DEV-001 — ACCEPTED.** One Device Broker arbitrates serial, flash, debug, USB permission,
device locks.

This document does not specify a standalone `DeviceBroker` schema or Kotlin type — per the WP-1
task brief's traceability matrix, this domain's schema/Kotlin deliverables are `DeviceIdentity`,
`DeviceConnection`, `FirmwareArtifact`, `DeviceSnapshot`, `DeviceOperation` only. The single-broker
rule is instead **wire-visible as a structural property of those five types**:

- `DeviceConnection.exclusiveLock` (`schemas/devices/connection.schema.json`) is non-null **if and
  only if** `state == BUSY` — enforced both directions by `allOf`/`if`/`then` (see the schema file
  for both halves) and mirrored as a two-way `init{}` check in `DeviceContracts.kt`. A connection
  cannot represent "busy, no lock" or "idle, holding a lock" at all.
- Every `DeviceOperation` (`schemas/devices/operation.schema.json`) carries a required, non-blank
  `authority.exclusiveLockId` — a `DeviceOperation` literally cannot be constructed without
  referencing a broker-granted lock, the same "no contract bypass" pattern
  `ExecutionRequest.authorityGrant` uses in the execution domain (FB-RAT-COM-012, §7 below).

**What this schema layer cannot enforce, and what a real Device Broker implementation MUST do
instead:** re-validate, at the moment a `DeviceOperation` is authorized to leave
`PENDING_PRECONDITIONS`, that `authority.exclusiveLockId` equals the referenced
`DeviceConnection`'s **current** `exclusiveLock.lockId` — not merely that the field is a non-blank
string. See `fixtures/devices/adversarial/operation-authority-without-current-lock.adversarial.json`
for the fixture naming this obligation: a stale, foreign, or replayed lock id is structurally
indistinguishable from a live one, and only a broker that actually checks the connection's live
lock state at authorization time closes that gap. This is this domain's concrete instance of
FB-RAT-COM-012 — see §7.

## 2. Object model (FB-RAT-DEV-002)

**FB-RAT-DEV-002 — ACCEPTED.** Device object model: `DeviceIdentity`, `DeviceConnection`,
`FirmwareArtifact`, `DeviceSnapshot`, `DeviceOperation`, operation receipts.

### 2.1 `DeviceIdentity` — `schemas/devices/device-identity.schema.json`

What a discovered device claims to be: `deviceIdentityId` (FB-RAT-COM-002 stable ID, constant
across reconnects/USB-port moves/re-flashes), `family` (FB-RAT-DEV-010's nine-value USB
classification, §6), `board` (a `BoardRef` into Arduino CLI/PlatformIO metadata, FB-RAT-DEV-009,
§8 — never a separately-owned catalog), `mcu` (free string, nullable), `usbDescriptor`
(vendor/product id + strings), `serial` (the device's **own** USB serial string — distinct from
`deviceIdentityId`, and commonly `null`: many CH34x/CP210x clones report none at all, which is
exactly why `deviceIdentityId` cannot simply be the USB serial), `bootloaderInfo` (how this board
enters/speaks a bootloader), `capabilities` (an optional `CapabilityManifest` narrowed to
`subjectKind: DEVICE`, reused from common), and — load-bearing for §4's flash preflight —
`confidence` (§2.1.1).

#### 2.1.1 Identity confidence (FB-RAT-DEV-004's precondition)

`IdentityConfidence.level` is an ordered four-value scale: `CONFIRMED` (exact USB VID/PID +
protocol handshake match, or explicit user confirmation) > `PROBABLE` (a VID/PID match against a
family that maps to more than one possible board) > `UNCERTAIN` (heuristic-only, no successful
protocol probe) > `UNKNOWN` (no classification attempt has succeeded yet). `basis` is a free-text
array of supporting reasons. `DeviceOperation.preconditions.identityConfidenceAtLeast` (§4) gates
against this scale — a destructive operation SHOULD require at least `PROBABLE`; this schema
permits representing a looser policy but does not itself forbid one (a provider-gate obligation
this document states, not structurally enforces, per the same pattern
`docs/ratified/EXECUTION_CONTRACT.md` §1 uses for `visibilityContract`).

### 2.2 `DeviceConnection` — `schemas/devices/connection.schema.json`

Live connection state for a `DeviceIdentity`: the FB-RAT-DEV-003 nine-state lifecycle (§3),
`usbPermissionGranted` (the flash contract's step 2 — a **visible**, user-facing Android
`UsbManager` permission grant), `transportPath`, and `exclusiveLock` (§1). `connectionId` is its
own FB-RAT-COM-002 stable ID, distinct from `deviceIdentityId` — one physical device produces a
new `DeviceConnection` on every unplug/replug cycle, while its `DeviceIdentity` stays constant.

### 2.3 `FirmwareArtifact` — `schemas/devices/firmware-artifact.schema.json`

A firmware image that can be flashed: `artifact` (the underlying byte artifact — digest, storage,
verification state, and build provenance/"build receipt" — reused wholesale from
`schemas/common/artifact-ref.schema.json`, not redefined), `boardCompatibility` (non-empty array
of `BoardRef` — the FB-RAT-DEV-007 wrong-board check's declared target list, §4 step 3),
`memoryLayout` (`flashBytes`/`ramBytes`/`eepromBytes`/`flashStartAddress`), and `signing`
(`signed`/`signatureScheme`/`verified` — `signed: false` is the common, expected case for
hobbyist/embedded firmware, not a defect).

### 2.4 `DeviceSnapshot` — `schemas/devices/snapshot.schema.json`

A point-in-time belief about what firmware a device is running: `connectionStateAtCapture`,
`firmwareArtifactId` + `firmwareDigest` (the **actually observed** on-device digest, which MAY
differ from the artifact's own recorded digest if verification used a partial method or failed),
`verification` (§2.4.1), and `lastKnownGood` — see FB-RAT-DEV-005 (§5). Snapshots are append-only:
a corrected belief is a new snapshot, never an in-place edit, mirroring FB-RAT-COM-006's general
append-only convention.

#### 2.4.1 Verification shape (shared with `DeviceOperation`)

`{state: NOT_PERFORMED|VERIFIED|FAILED|SKIPPED, method: PROTOCOL_RESPONSE|READ_BACK_HASH|
VERSION_HANDSHAKE|SELF_TEST|null, verifiedAtUtc}` — the same four-method vocabulary flash contract
step 6 names, duplicated locally in both `snapshot.schema.json` and `operation.schema.json` per
`COMMON_CONVENTIONS.md` §5's shared-sub-shape convention.

### 2.5 `DeviceOperation` — `schemas/devices/operation.schema.json`

One Device-Broker-arbitrated operation (`FLASH`, `ERASE`, `READ_BACK_VERIFY`, `SELF_TEST`,
`SERIAL_SESSION`, `DEBUG_SESSION`): `preconditions`, `authority`, `artifactId` (required for
`FLASH`, null otherwise), `protocol`, `state` (the nine-value lifecycle, §4), `verification`,
`rollback`, and a terminal `receipt`. Covered in full in §4 below, since its shape **is** the flash
contract's structural encoding.

## 3. `DeviceConnection` lifecycle (FB-RAT-DEV-003)

**FB-RAT-DEV-003 — ACCEPTED.** Device uncertainty: `READY`, `BOOTLOADER`, `BUSY`, `DISCONNECTED`,
`STATE_UNKNOWN`, `UNSUPPORTED` represented explicitly.

`DISCOVERED -> PERMISSION_REQUIRED -> CONNECTING -> READY | BOOTLOADER | BUSY | DISCONNECTED |
STATE_UNKNOWN | UNSUPPORTED` (nine states total: three transient entry states, six settled
states). Unlike the execution domain's one-shot `ExecutionState` machine, a `DeviceConnection` is
long-lived and cyclic — most settled states transition back out (e.g. reconnect), mirroring
`docs/ratified/WORKSPACE_KERNEL_SPEC.md` §3.2's `WorkspaceProviderState` precedent more closely
than `EXECUTION_CONTRACT.md` §2's terminal-receipt shape.

| From state | Triggering event | To state |
|---|---|---|
| `DISCOVERED` | Request Android USB permission | `PERMISSION_REQUIRED` |
| `PERMISSION_REQUIRED` | User grants permission | `CONNECTING` |
| `PERMISSION_REQUIRED` | User denies permission | `DISCONNECTED` |
| `CONNECTING` | Handshake succeeds, device is a recognized operating target | `READY` |
| `CONNECTING` | Handshake detects the device is already in a bootloader | `BOOTLOADER` |
| `CONNECTING` | Handshake fails (timeout, protocol mismatch) | `DISCONNECTED` |
| `CONNECTING` | Device does not match any known family/protocol | `UNSUPPORTED` |
| `READY` | A `DeviceOperation` is authorized against this connection | `BUSY` |
| `BUSY` | The operation reaches a terminal state | `READY` |
| `READY` | Bootloader entry requested/detected | `BOOTLOADER` |
| `BOOTLOADER` | Bootloader exit / normal-mode handshake | `READY` |
| `READY` \| `BUSY` \| `BOOTLOADER` | Clean unplug detected | `DISCONNECTED` |
| `READY` \| `BUSY` \| `BOOTLOADER` | Connectivity lost ambiguously (no clean unplug signal) | `STATE_UNKNOWN` |
| `DISCONNECTED` | Reconnect requested | `CONNECTING` |
| `STATE_UNKNOWN` | Reconnect attempt succeeds, state re-established | `READY` |
| `STATE_UNKNOWN` | Reconnect attempt fails | `DISCONNECTED` |

`STATE_UNKNOWN` is deliberately distinct from `DISCONNECTED`: `DISCONNECTED` means the device is
**confirmed gone** (a clean unplug signal was observed); `STATE_UNKNOWN` means connectivity was
lost in a way that leaves the device's actual condition genuinely indeterminate (most commonly, a
drop while `BUSY`). A consumer MUST NOT collapse the two — this is the connection-level analog of
FB-RAT-EXE-004's reconnect-uncertainty rule in the execution domain.

Structural encoding: `connection.schema.json` pins `stateReason` (non-blank required for every
state except `DISCOVERED`/`CONNECTING`/`READY` — see `$defs` note in the schema) and
`exclusiveLock` (non-null iff `BUSY`) via `allOf`/`if`/`then`; `DeviceConnectionState` in
`DeviceContracts.kt` is the plain-enum wire counterpart (no sealed-interface twin — unlike
`DeviceOperationState`, this is a long-lived cyclic machine, not a one-shot run, matching
`WorkspaceProviderState`'s precedent of staying a plain enum too).

## 4. The flash contract (FB-RAT-DEV-004/005/006/007)

The single normative procedure every `FLASH`/`ERASE` `DeviceOperation` MUST follow, structurally
encoded as far as a JSON Schema and a Kotlin `init{}` can reach, with the remaining cross-object
checks stated here as explicit provider-gate obligations.

| Step | What it checks | Failure mode |
|---|---|---|
| 1. Discover/classify with confidence | `DeviceIdentity.confidence` is established (§2.1.1) — USB VID/PID match, protocol probe, or explicit user selection | Confidence stays `UNCERTAIN`/`UNKNOWN`; a destructive operation's `preconditions.identityConfidenceAtLeast` gate (below) is not satisfied |
| 2. Visible Android USB permission | `DeviceConnection.usbPermissionGranted` is `true` (a real, user-facing `UsbManager` grant, never implicit) | Connection stays at `PERMISSION_REQUIRED`; operation cannot leave `PENDING_PRECONDITIONS` |
| 3. Validate board/firmware/layout/power assumptions | `preconditions.boardFirmwareCompatibilityChecked` — `DeviceIdentity.board` cross-checked against `FirmwareArtifact.boardCompatibility` (FB-RAT-DEV-007); `preconditions.powerWiringAssumptionsAcknowledged`; `preconditions.identityConfidenceAtLeast` satisfied | **`FAILED_SAFE`** — blocked before any destructive action; receipt (if any) MUST record zero side effects (structurally enforced, `operation.schema.json`'s FAILED_SAFE if/then) |
| 4. Exclusive device-operation lock + explicit destructive authority | `authority.exclusiveLockId` references a broker-granted `DeviceLock` (§1) currently held by this operation's principal; `authority.destructiveActionExplicitlyAuthorized == true` | Operation cannot leave `AUTHORIZED`; a destructive `operationKind` that reaches `IN_PROGRESS`+ without this is a **structural rejection** (`operation.schema.json`'s third `allOf`/`if`/`then`) |
| 5. Bootloader/transfer with protocol progress + recorded possible side effects | `protocol.progressPercent` advances; `protocol.sideEffectsPossible` flips `true` the instant the destructive transfer actually begins | A disconnect from this point on is governed by step 6's failure modes below, never `FAILED_SAFE` |
| 6. Verify (protocol response, read-back hash, version handshake, or self-test) | `verification.state` transitions to `VERIFIED`, `FAILED`, or stays `SKIPPED`/`NOT_PERFORMED`, via one of the four `method` values | A verification that cannot genuinely be performed (device gone) MUST NOT be recorded as `VERIFIED` — see `fixtures/devices/adversarial/disconnect-mid-flash.adversarial.json` |
| 7. Receipt; last-known-good updates only after verification | `DeviceOperation.receipt` is written (terminal-only); `DeviceSnapshot.lastKnownGood` is set `true` **only** when that snapshot's own `verification.state == VERIFIED` (structurally enforced, FB-RAT-DEV-005) | A receipt claiming `SUCCEEDED` without `verification.state == VERIFIED` is a **structural rejection** (`operation.schema.json`'s second `allOf`/`if`/`then`; see `fixtures/devices/invalid/operation-succeeded-without-verification.invalid.json`) |

**`DeviceOperation.state` lifecycle** (nine values — four non-terminal, five terminal), mapping
1:1 onto the seven steps above:

| From state | Triggering event | To state |
|---|---|---|
| — | Operation created | `PENDING_PRECONDITIONS` |
| `PENDING_PRECONDITIONS` | Any precondition fails (wrong board, no USB permission, layout mismatch) | **`FAILED_SAFE`** |
| `PENDING_PRECONDITIONS` | All preconditions pass; lock + explicit destructive authority granted | `AUTHORIZED` |
| `AUTHORIZED` | Bootloader entry / transfer begins | `IN_PROGRESS` |
| `IN_PROGRESS` | Transfer completes; verification begins | `VERIFYING` |
| `IN_PROGRESS` | Disconnect/failure after a destructive transfer began | **`FAILED_SIDE_EFFECTS_POSSIBLE`** or **`TARGET_STATE_UNKNOWN`** |
| `VERIFYING` | Verification passes | **`SUCCEEDED`** |
| `VERIFYING` | Verification fails, or disconnect during verification | **`FAILED_SIDE_EFFECTS_POSSIBLE`** or **`TARGET_STATE_UNKNOWN`** |
| any non-terminal | User/agent cancels | **`CANCELLED`** |

**FB-RAT-DEV-005 — ACCEPTED.** Verification before good state: update last-known-good only after
protocol/read-back/version/self-test verification. Mechanism: `DeviceSnapshot.lastKnownGood`
(§2.4) and `DeviceOperation.state == SUCCEEDED` (above) both structurally require
`verification.state == VERIFIED` — there is no path to either "good state" claim without a
genuine, recorded verification pass. Unlike `ExecutionReceipt`'s `SUCCEEDED`/`SUCCEEDED_UNVERIFIED`
two-tier split, `DeviceOperation` has **no** "succeeded unverified" terminal state at all — an
unverified completion is `FAILED_SIDE_EFFECTS_POSSIBLE` or `TARGET_STATE_UNKNOWN`, never a silent
`SUCCEEDED`. This is a deliberate, stricter choice than the execution domain's: a device that may
now be running unknown firmware is a safety-relevant unknown in a way a merely-unverified CI job
output is not.

**FB-RAT-DEV-006 — ACCEPTED.** Partial-side-effect semantics: disconnect after erase but before
verification is `FAILED_SIDE_EFFECTS_POSSIBLE` or `STATE_UNKNOWN`, never generic failure.
Mechanism: `DeviceOperationState`'s nine-value enum has **no generic `FAILED` value at all** —
see `fixtures/devices/invalid/operation-state-generic-failure-rejected.invalid.json`, where
`state: "FAILED"` is rejected by the enum itself, making "generic failure" structurally
unrepresentable, not merely discouraged. The choice between the two permitted outcomes is a
provider-side judgment call (does the broker have enough information to confirm side effects
occurred, or not), not something this schema arbitrates — but a `FAILED_SIDE_EFFECTS_POSSIBLE`
receipt's `sideEffects` list MUST be non-empty (structurally enforced) and a `TARGET_STATE_UNKNOWN`
operation MUST carry a non-null receipt (structurally enforced). See
`fixtures/devices/adversarial/disconnect-mid-flash.adversarial.json` for the deeper adversarial
case this cannot fully close on its own: nothing stops a producer from claiming `SUCCEEDED` +
`VERIFIED` outright after a disconnect, since JSON Schema cannot authenticate that a claimed
verification method was genuinely performed against a still-connected device — that authenticity
check is a Device Broker provider-gate obligation, not a schema-checkable one.

**FB-RAT-DEV-007 — ACCEPTED.** Wrong-board block: incompatible board/artifact combinations fail
BEFORE erase. Mechanism: `preconditions.boardFirmwareCompatibilityChecked` (step 3, above) MUST be
evaluated, and MUST resolve any mismatch to `FAILED_SAFE`, before `protocol.sideEffectsPossible`
is ever set `true`. **What JSON Schema cannot enforce:** the actual cross-object match between
`DeviceIdentity.board` (one document) and `FirmwareArtifact.boardCompatibility` (a different
document) — `boardFirmwareCompatibilityChecked` is a boolean a producer can set `true` regardless
of whether the underlying check actually ran or actually passed. See
`fixtures/devices/adversarial/wrong-board-attempt.adversarial.json`: a `FLASH` operation
structurally valid in isolation, `boardFirmwareCompatibilityChecked: true`, already `IN_PROGRESS`
with `sideEffectsPossible: true` — against a firmware artifact whose id names an entirely
different board family than the target device's own `board`. A real Device Broker MUST perform the
match itself (matching on `(catalogRef, catalogId)` when both sides have a non-null `catalogId`,
falling back to `displayName` only when either side is `UNCATALOGED`) before ever granting
`authority.destructiveActionExplicitlyAuthorized`.

## 5. Embedded ecosystem interoperability (FB-RAT-DEV-009)

**FB-RAT-DEV-009 — ACCEPTED.** Interoperate with Arduino CLI/PlatformIO metadata, never own a
separate board/package universe.

`BoardRef.catalogRef` (`ARDUINO_CLI | PLATFORMIO | UNCATALOGED`) plus `catalogId` (the catalog's
own identifier — an Arduino CLI FQBN like `arduino:avr:uno`, or a PlatformIO board id like
`esp32dev`) is the entire board-identity surface this domain defines. `UNCATALOGED` is the honest
fallback when neither toolchain recognizes a board — it is explicitly **not** a third catalog this
constellation grows over time; a board that stays `UNCATALOGED` forever is expected, not a defect
to eventually "complete." `displayName` is present purely for UI, never identity.

## 6. Initial USB family set (FB-RAT-DEV-010 — EXPERIMENTAL)

**FB-RAT-DEV-010 — EXPERIMENTAL.** Cross-reference `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`
(owned by a separate agent/session; not restated here beyond what this document's own schemas need
to cite). The nine-family initial USB target matrix, as named in the task brief:

| Family | What it is |
|---|---|
| `CDC` | USB Communications Device Class — generic virtual-serial, the most common transport |
| `FTDI` | FTDI FT23x/FT231x-family USB-serial chips |
| `CP210X` | Silicon Labs CP2102/CP2104-family USB-serial chips |
| `CH34X` | WCH CH340/CH341-family USB-serial chips (very common on inexpensive clone boards) |
| `STK500` | Atmel/Microchip AVR ISP bootloader protocol (classic Arduino boards) |
| `UF2` | Microsoft UF2 mass-storage bootloader (RP2040/Pico family and others) |
| `DFU` | USB Device Firmware Upgrade class |
| `ESP_BOOTLOADER` | Espressif ROM/second-stage bootloader protocol (ESP32/ESP8266 family) |
| `CMSIS_DAP` | ARM CMSIS-DAP debug-access-port protocol — see §7, a **research spike**, not a shipped-parity target |

`UsbFamily` (`schemas/devices/device-identity.schema.json` `$defs/UsbFamily`, duplicated locally
into `operation.schema.json`'s `$defs/OperationProtocol.family` per `COMMON_CONVENTIONS.md` §5's
shared-sub-shape convention) is this nine-value closed enum. Real coverage of any given family is
decided by hardware availability, not by this document — this is a **target matrix**, not a
completion claim.

## 7. Embedded debugger release — CMSIS-DAP research spike (FB-RAT-DEV-011 — DEFERRED)

**FB-RAT-DEV-011 — DEFERRED.** Cross-reference `docs/non_ratified/DEFERRED_DECISIONS.md` (owned by
a separate agent/session). Stated plainly here, per an explicit validation correction this task
brief carries forward: **CMSIS-DAP/OpenOCD support on Android has zero precedent.** No Android port
of OpenOCD or pyOCD exists; CMSIS-DAP v1 is a USB-HID protocol, and Android's `UsbManager` does not
expose USB-HID devices to apps the way it exposes bulk/CDC endpoints — a working implementation
would need a from-scratch NDK+libusb HID stack with no existing reference to build from. This is
recorded here as a **research spike**, at a clearly lower confidence tier than the well-precedented
flash/serial families (§6) — `CMSIS_DAP` appearing in the `UsbFamily` enum is a target-matrix
placeholder (so the type is forward-compatible with a future implementation), never a claim of
present or even near-term support, and this document does not present it in the same feature-table
row as `STK500`/`UF2`/`DFU`/`ESP_BOOTLOADER`.

## 8. Device Gate Checklist

**Not something this session can close** — flagged here as an `OWNER_GATES.md` item
(FB-RAT-DEV-008, below), for whichever future session owns that release-governance document:

- **20 repeated flash cycles per reference board**, on real hardware, verifying: (a) a clean flash
  reaches `SUCCEEDED` with `lastKnownGood: true` every time; (b) an interrupted flash (physically
  unplugging the board mid-transfer, at several different `protocol.progressPercent` points) always
  resolves to `FAILED_SIDE_EFFECTS_POSSIBLE` or `TARGET_STATE_UNKNOWN`, never leaves the operation
  hung in a non-terminal state and never silently reports `SUCCEEDED`; (c) a wrong-board attempt
  (mismatched `FirmwareArtifact.boardCompatibility` against the connected `DeviceIdentity.board`)
  is blocked at `FAILED_SAFE` before any USB traffic that could alter flash contents.
- At minimum one reference board per §6 family with real, currently-available hardware — this
  document does not itself commit to which specific boards, that is an owner/device-availability
  decision.
- **CLAUDE.md "Environment honesty":** this build container has no device, emulator, board, or SSH
  host — none of the above can be run here. The five schemas + `DeviceContracts.kt` in this domain
  are the JVM-testable structural half (§9); this checklist is the device-only half.

**FB-RAT-DEV-008 — ACCEPTED.** Real-device conformance: protocol unit tests insufficient;
reference-board cycles are release gates. This section operationalizes that decision as a
checklist; it does not (and, per environment honesty, cannot) execute it.

## 9. Conformance test-class coverage

Following `COMMON_CONVENTIONS.md` §11's eight-class table, applied to this domain's five schemas.

| # | Test class | Coverage in this domain | JVM-testable |
|---|---|---|---|
| 1 | Golden serialization | `fixtures/devices/valid/*.json` — nine fixtures covering every schema, including both a cataloged-confident and an uncataloged-uncertain `DeviceIdentity`, both a `READY` and a locked `BUSY` `DeviceConnection`, a `SUCCEEDED`+verified and a non-terminal `DeviceOperation`, and both a `lastKnownGood` and a not-yet-verified `DeviceSnapshot`. | **YES** |
| 2 | State transition | §3's `DeviceConnection` table and §4's `DeviceOperation` table are directly assertable as pure-Kotlin state-machine unit tests once `contracts/kotlin/` is wired into a Gradle module; structurally, the `allOf`/`if`/`then` rules in `connection.schema.json` and `operation.schema.json` are the snapshot-level rules a single-document schema *can* enforce. | **YES** for snapshot-level rules; **PARTIAL** for true cross-snapshot transition legality (provider-side obligation, mirrors `EXECUTION_CONTRACT.md` §7's identical caveat). |
| 3 | Adversarial | `fixtures/devices/adversarial/*` — five fixtures: the two REQUIRED (`wrong-board-attempt`, `disconnect-mid-flash`), one privilege-escalation (`operation-authority-without-current-lock`), one secret-leakage (`connection-lock-holder-secret-leak`), and one DIST-002 policy violation (`play-manifest-declares-download-and-exec`, validated against `schemas/common/capability-manifest.schema.json`, cross-referenced from `docs/ratified/DISTRIBUTION_CAPABILITY_SPLIT.md`). Ten required-by-brief structural rejections live in `invalid/` instead (they ARE structurally catchable). | **YES** |
| 4 | Provider conformance | `DeviceOperation`/`DeviceConnection` shapes are JVM-testable against fixtures; no `DeviceBroker`/`DeviceProvider` interface is specified in this pass (not in the WP-1 task brief's deliverable list for this domain) — a future work package's job. Live USB/flash provider behavior is owner-verified only (`CLAUDE.md` "Environment honesty"). | **PARTIAL** |
| 5 | Recovery | `DeviceSnapshot`'s append-only, `lastKnownGood`-gated-on-verification shape is JVM-testable structurally (§5). Actual device-disconnect/reconnect recovery is device-only — §8. | **PARTIAL** |
| 6 | Performance | No device/emulator/board exists in this build container; §8's 20-cycle reference-board gate is exactly this test class's real content. | **NO** |
| 7 | Accessibility | `DeviceConnection.stateReason` non-blank requirement on non-plain-connected states (§3) is JVM-testable structurally; actual screen-reader/assistive-tech rendering of device state is device-only. | **PARTIAL** |
| 8 | Compatibility | Every top-level shape here inherits `ContractEnvelope`'s `schemaVersion`/`unknownFields` handling from `COMMON_CONVENTIONS.md` §3 — no separate compatibility mechanism is introduced in this domain. | **YES** (inherited) |

## 10. Schema + fixture inventory

All five files below are draft 2020-12, all carry a `$comment` LICENSE-PENDING marker, all
`Draft202012Validator.check_schema()`-clean. See the WP-1 handoff report (this domain's final
message) for the full validation transcript.

- `schemas/devices/device-identity.schema.json` — `DeviceIdentity`
- `schemas/devices/connection.schema.json` — `DeviceConnection`
- `schemas/devices/firmware-artifact.schema.json` — `FirmwareArtifact`
- `schemas/devices/snapshot.schema.json` — `DeviceSnapshot`
- `schemas/devices/operation.schema.json` — `DeviceOperation`

`contracts/kotlin/DeviceContracts.kt` mirrors all five field-for-field, plus the
`DeviceOperationLifecycleState` sealed interface (the in-memory state-machine encoding, mirroring
`ExecutionContracts.kt`'s `ExecutionLifecycleState` precedent — this domain owns a real state
machine, unlike `CommonContracts.kt`'s shared wire shapes).

`fixtures/devices/{valid,invalid,adversarial}/` — 24 files (9 valid + 10 invalid + 5 adversarial
JSON, each adversarial fixture with a sibling `.expected.txt`).

---

## Forward pointers (owned elsewhere, not restated here)

- **`FB-RAT-DEV-010`'s numeric/hardware-availability calibration** — `docs/non_ratified/
  EXPERIMENTAL_DECISIONS.md`, owned by a separate agent/session (§6).
- **`FB-RAT-DEV-011`'s CMSIS-DAP research-spike scoping/timeline** — `docs/non_ratified/
  DEFERRED_DECISIONS.md`, owned by a separate agent/session (§7).
- **`FB-RAT-DEV-008`'s 20-cycle reference-board gate** — an `OWNER_GATES.md` item (§8), not a file
  this session creates or closes.
- **A `DeviceBroker`/`DeviceProvider` runtime interface** — not specified in this work package;
  `core-engine/src/main/java/dev/aarso/data/DeviceRepo.kt` + `domain/device` (WP-0 survey §1(e))
  are the real substrate a future implementation would wrap.
- **`docs/ratified/DISTRIBUTION_CAPABILITY_SPLIT.md`** — this document's sibling in the
  device+distribution work package; no dependency either direction, grouped by owning agent only.
