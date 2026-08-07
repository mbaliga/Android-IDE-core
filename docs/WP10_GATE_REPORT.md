# WP-10 Gate Report — Device Broker + flash safety contracts

**Scope:** `06_WORK_PACKAGES.md`'s WP-10 entry: "device object model, broker arbitration, flash
contract state machine, STK500/UF2/DFU/ESP protocol codecs against recorded transcripts,
wrong-board preflight; Android USB permission flow authored (instrumented tests, owner-run).
Gate: protocol/state suites green on JVM; `DEVICE_GATE_CHECKLIST.md` emitted." **Implements
already-ratified contracts** (`docs/ratified/DEVICE_STATE_AND_SAFETY_SPEC.md`, WP-1's output) —
the same posture WP-3/WP-4/WP-8/WP-8a took toward their own already-ratified specs. That document
is explicit that no `DeviceBroker`/flash-driver implementation was in WP-1's own deliverable list
("a future work package's job" — this pass is that work package).

**Run date:** 2026-08-07. **Method:** the same real-compile-and-test standard every gate since
WP-2 — `./gradlew :core-engine:testFullDebugUnitTest`.

## Gate verdict: **GREEN** — compiled and passed on the first real build attempt (one non-fatal compiler warning cleaned up after)

---

## 0. What was built

**Five new files under `domain/device/broker/`** (§1/§3/§4 of the ratified spec):

1. **`DeviceConnectionMachine.kt`** — §3's nine-state `DeviceConnection` lifecycle table
   (FB-RAT-DEV-003), made real. `STATE_UNKNOWN` and `DISCONNECTED` are kept structurally distinct
   throughout, per the spec's own warning never to collapse them.
2. **`DeviceOperationMachine.kt`** — §4's nine-state flash-contract table
   (FB-RAT-DEV-004/005/006/007). No generic `FAILED` value exists in the underlying enum at all
   (already true of WP-1's `DeviceOperationState`); this machine's own `Event` vocabulary mirrors
   that by construction — every disconnect/failure event requires the caller to name which of the
   two permitted outcomes applies, there is no generic-failure event to even offer.
3. **`WrongBoardPreflight.kt`** — FB-RAT-DEV-007's exact named matcher: `(catalogRef, catalogId)`
   when both sides are cataloged with a non-null id, `displayName` fallback otherwise. The
   dedicated regression test (`` `the same catalogId string under two DIFFERENT catalogs is NOT a
   match` `` — an Arduino CLI board and a PlatformIO board that happen to share the literal string
   `"esp32dev"`) targets exactly the trap the ratified spec names as the reason this can't be a
   naive string-equality check.
4. **`DeviceBroker.kt`** — FB-RAT-DEV-001's single-arbiter rule, closing the exact gap §1 names:
   "a real Device Broker implementation MUST re-validate... that `authority.exclusiveLockId`
   equals the referenced `DeviceConnection`'s CURRENT `exclusiveLock.lockId`" — `isLockCurrent()`
   is that re-validation, proven directly by a test that acquires then releases a lock and asserts
   the very same, previously-real `lockId` is no longer current afterward (a replayed old lock id
   is structurally indistinguishable from a live one unless this check runs).
5. **`FlashOperationDriver.kt`** — the centerpiece: drives the real seven-step flash contract
   around `DeviceOperationMachine` + `DeviceBroker` + `WrongBoardPreflight`, the same "adapter
   around already-real pieces" posture `LoopInstallationDriver` (WP-8a) established. The
   connection's exclusive lock is acquired before precondition evaluation and released in a
   `finally` block covering every exit path, including both disconnect-shaped terminals.

**Three new protocol codecs under `domain/device/usb/`** (STK500/IntelHex already existed;
UF2/DFU/ESP are new this pass):

6. **`Uf2Codec.kt`** — the Microsoft UF2 512-byte block format (RP2040/Pico family).
7. **`DfuStatus.kt`** — USB DFU 1.1's `DFU_GETSTATUS` 6-byte response codec + the download-path
   subset of the class's own state machine.
8. **`SlipFraming.kt`** — RFC 1055 SLIP byte-stuffing, the framing layer under the ESP ROM
   bootloader protocol (esptool.py's own wire format).

**`docs/DEVICE_GATE_CHECKLIST.md`** — the WP-10 brief's own explicitly-named deliverable:
operationalizes `DEVICE_STATE_AND_SAFETY_SPEC.md` §8's 20-cycle/interrupted-flash/wrong-board gate
into a concrete, owner-run procedure. This session cannot execute a single line of it (no device
in this sandbox) — it is the checklist itself, not a report of having run it.

60 new tests across 8 test classes.

## 1. `core-engine` JVM gate — GREEN, first real attempt

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 1m 2s
```

**1549 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip every gate since
WP-1L has reported), up from WP-9's 1489-test baseline — exactly the 60 new tests, zero
regressions elsewhere. One non-fatal Kotlin compiler warning (a reified-generic type-inference
note on an `arrayOf(...)`-based `hashCode()` in `Uf2Codec.Block`) was cleaned up after the first
green run by switching to `java.util.Objects.hash(...)`; a second full run confirmed both zero
warnings and the identical 1549/0/0/1 result:

| Test class | Tests |
|---|---|
| `DeviceConnectionMachineTest` | 8 |
| `DeviceOperationMachineTest` | 7 |
| `WrongBoardPreflightTest` | 6 |
| `DeviceBrokerTest` | 7 |
| `FlashOperationDriverTest` | 9 |
| `Uf2CodecTest` | 7 |
| `DfuStatusTest` | 8 |
| `SlipFramingTest` | 8 |

## 2. `DeviceOperation`'s own strict `init{}` invariants did real correctness work again

Same "the contract's own strictness is a correctness check no fixture could replicate" pattern
WP-4/WP-7/WP-8a's gate reports already demonstrated for their own domains: `DeviceOperation`
requires `state=SUCCEEDED` ⟹ `verification.state=VERIFIED`; `state=FAILED_SAFE` ⟹ zero side
effects; `state=FAILED_SIDE_EFFECTS_POSSIBLE` ⟹ non-empty side effects; a destructive operation
past `PENDING_PRECONDITIONS` ⟹ `authority.destructiveActionExplicitlyAuthorized=true`. Writing
`FlashOperationDriver` against these already-real constraints meant a genuine construction mistake
(e.g. forgetting to mark `protocol.sideEffectsPossible` true once a transfer had actually begun,
even on a branch with zero *confirmed* side effects) would either fail to compile-and-run at all
or fail a specific, already-written test — which is exactly what caught it: the first draft
derived `sideEffectsPossible` from `sideEffects.isNotEmpty()`, which is wrong for the
`TARGET_STATE_UNKNOWN` branch (a transfer that genuinely began, but confirmed zero side effects in
the receipt) — caught and fixed *before* ever running Gradle, by re-reading the spec's own
wording ("flips true the instant the destructive transfer actually begins") against the draft
logic, the same "re-read carefully before trusting a first draft" discipline WP-5's
`SshExecutionProvider` bug was caught with.

## 3. Fail-closed proven per-branch, not just by final-state assertion

- `` `a wrong-board artifact is blocked at FAILED_SAFE before any transfer, zero side effects,
  transfer callback never invoked` `` asserts the injected `performTransfer` closure was never
  called, not just that the final state was correct.
- `` `declining explicit destructive authorization cancels the operation, never reaching
  IN_PROGRESS` `` — same style of proof for the authorization-declined path.
- `` `the connection is always released back to READY, in every terminal outcome including both
  disconnect shapes` `` iterates all three transfer-outcome shapes (completed, disconnected-with-
  confirmed-effects, disconnected-without) and asserts `DeviceBroker`'s registry returns to
  `READY` every time — the `finally`-block release discipline proven directly, not assumed from
  the absence of an exception.
- `DeviceBrokerTest`'s stale-lock-release test asserts the connection **stays BUSY** after a
  rejected release attempt — a rejected operation must not have silently side-effected state.

## 4. Protocol codecs: real, but honestly bounded confidence

`Uf2Codec`/`DfuStatus`/`SlipFraming` are implemented from general, widely-referenced public
protocol documentation (the UF2 spec's own 512-byte struct layout; USB DFU 1.1's `GetStatus`
response shape and state diagram; RFC 1055 SLIP framing plus esptool.py's own documented use of
it) — **not independently verified against real hardware, an official spec PDF, or a captured
device transcript in this sandbox** (`CLAUDE.md` "Environment honesty" — no device exists here).
Each file's own KDoc states this explicitly, the same posture `Stk500`'s pre-existing header
already takes toward `avrdude`'s `stk500.h`. What IS verified: the encode/decode round-trip
correctness of each codec's own byte-level structure (magic numbers, field widths, escaping) via
real, deterministic JVM tests — that is a genuinely different and weaker claim than "this matches
a real ESP32/RP2040/DFU device's actual bytes," and this report does not conflate the two.
`DfuStatus`'s state machine is explicitly scoped to the download-path subset only
(`APP_IDLE`/`APP_DETACH`/`DFU_UPLOAD_IDLE` are out of scope) — flagged in the file's own KDoc, not
silently narrowed.

## 5. What's still genuinely unverified / honestly out of scope

- **`docs/DEVICE_GATE_CHECKLIST.md`'s entire content is unexecuted** — by construction, per
  environment honesty; it is the deliverable this WP was asked to *emit*, not a report of having
  run it. See that file for what an owner with real hardware runs next.
- **Android USB permission flow (`UsbManager`/`PendingIntent`/`BroadcastReceiver`) is not built
  or tested** — the WP-10 brief itself scopes this as "instrumented tests, owner-run," and this
  repo has no `androidTest` source set established for this domain yet (a WP-0-survey-confirmed
  gap, not new to this pass). `DeviceConnectionMachine`'s `PERMISSION_REQUIRED` state is the
  JVM-testable domain-layer half; the real Android wiring around it is a flagged follow-up.
- **`DeviceBroker` is in-memory only** — no Room-backed connection/lock registry, matching the
  "no consumer yet" pattern every WP since WP-2 has left for a comparable new piece; a real UI
  surface needing broker state to survive process death is the natural trigger to build one.
- **No real serial/USB transport wired to any codec** — `Stk500` already establishes the
  `SerialLink` seam pattern this pass's three new codecs would need an equivalent of; none of
  `Uf2Codec`/`DfuStatus`/`SlipFraming` has a device-facing driver class analogous to `Stk500`
  itself yet (they are pure frame codecs, one layer below where `Stk500` sits) — a natural
  next-pass addition once real transport wiring exists to test against.
- **`FlashOperationDriver` has no real `performTransfer`/`performVerification` implementation** —
  both are caller-supplied seams, the same "state-machine plumbing is real, the provider behind
  the seam is not" shape `LoopInstallationDriver`'s `resolveBindings`/`resolveAuthority` and
  `LoopRunDriver`'s `resolveBinding`/`resolveAuthority` already left as honest gaps.
