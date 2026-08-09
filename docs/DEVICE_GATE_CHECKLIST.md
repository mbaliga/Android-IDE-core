# Device Gate Checklist

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**What this is:** the owner-run, real-hardware release gate `DEVICE_STATE_AND_SAFETY_SPEC.md` §8
names as `FB-RAT-DEV-008` ("real-device conformance: protocol unit tests insufficient;
reference-board cycles are release gates") and WP-10's brief asks this pass to emit. **This
session cannot run any item on this checklist** — the build container has no device, emulator,
board, or SSH host (`CLAUDE.md` "Environment honesty," binding rule 6) — so this document is the
checklist itself, not a report of it having been executed. Everything below operationalizes the
JVM-testable half this session *did* build (`docs/WP10_GATE_REPORT.md`) into the concrete,
owner-run procedure that closes the loop on real hardware.

**Prerequisite:** `core-engine`'s JVM gate is green (`docs/WP10_GATE_REPORT.md`) — the protocol
codecs, state machines, and `FlashOperationDriver` this checklist exercises on real hardware are
already proven correct in isolation, against fakes. This checklist is not re-testing that logic;
it is testing the parts that logic cannot reach from a JVM: real USB transport, real bootloader
timing, real disconnect behavior, real `UsbManager` permission UX.

## 1. Reference boards — one per §6 USB family, minimum

| Family | Reference board (owner to select/confirm) | Status |
|---|---|---|
| `CDC` | — | not yet selected |
| `FTDI` | — | not yet selected |
| `CP210X` | — | not yet selected |
| `CH34X` | — | not yet selected (common on inexpensive clone boards — a good first target) |
| `STK500` | — | not yet selected (classic Arduino Uno/Nano; `Stk500`/`IntelHex` already exist in `domain/device/usb/`) |
| `UF2` | — | not yet selected (RP2040/Pico family; `Uf2Codec` built this pass) |
| `DFU` | — | not yet selected (`DfuStatus` built this pass, download-path only) |
| `ESP_BOOTLOADER` | — | not yet selected (ESP32/ESP8266; `SlipFraming` built this pass) |
| `CMSIS_DAP` | out of scope — `FB-RAT-DEV-011` DEFERRED, a research spike, not a release-gated family (see `DEVICE_STATE_AND_SAFETY_SPEC.md` §7) | N/A |

Real coverage of any given family is decided by hardware availability, not this document — a
board that stays untested is expected, not a defect to eventually "complete" (mirrors §5's
`UNCATALOGED` posture for board identity itself).

## 2. Per-board test: 20 repeated clean flash cycles

For **each** reference board in §1:

1. Flash a small, known-good test firmware 20 times in a row, unplugging/replugging the board
   between each cycle (a fresh `DeviceConnection` per FB-RAT-DEV-003's own model — one physical
   device produces a new `connectionId` on every reconnect).
2. **Pass condition, every one of the 20 cycles:** the operation reaches `SUCCEEDED`, the
   resulting `DeviceOperation.verification.state == VERIFIED`, and the follow-on `DeviceSnapshot`
   has `lastKnownGood == true`. Any cycle that reaches `SUCCEEDED` without a genuine verified
   read-back is a **release blocker**, not a flaky-test retry — `FlashOperationDriver` structurally
   cannot construct that combination (see `docs/WP10_GATE_REPORT.md` §2), so a failure here means
   either the real transport/verification wiring around the driver is wrong, or (far more
   seriously) a genuine device-level firmware corruption the JVM layer cannot see.
3. Record: board, firmware size, total wall-clock time for the 20 cycles, and any anomaly even if
   the cycle still reported `SUCCEEDED` (unusually slow verification, a retry that worked on the
   second attempt, etc. — near-misses are exactly what this gate exists to surface before they
   become field failures).

## 3. Per-board test: interrupted flash, at multiple progress points

For **each** reference board in §1:

1. Begin a flash, then physically unplug the board at several different points along
   `OperationProtocol.progressPercent` — at minimum: immediately after the destructive transfer
   begins (near 0%), at roughly the midpoint (~50%), and near the end but before verification
   starts (~95%).
2. **Pass condition, every interruption point:** the operation resolves to exactly one of
   `FAILED_SIDE_EFFECTS_POSSIBLE` or `TARGET_STATE_UNKNOWN` (FB-RAT-DEV-006) — **never**:
   - left hanging in a non-terminal state (`IN_PROGRESS`/`VERIFYING`) indefinitely,
   - silently reported as `SUCCEEDED`,
   - reported as a generic/unstructured failure (structurally impossible per
     `DeviceOperationState`'s own closed enum, but the real transport/broker wiring around it
     could still fail to reach a terminal state at all if a disconnect isn't detected).
3. After each interrupted cycle, attempt a fresh flash (new connection, full precondition
   re-check) and confirm it succeeds cleanly — an interrupted prior attempt must never poison the
   next one.

## 4. Wrong-board block test

1. Connect a real board from family X (e.g. an STK500 Arduino Uno).
2. Attempt a `FLASH` operation using a `FirmwareArtifact` whose `boardCompatibility` list names
   only a different, incompatible board (e.g. an ESP32 artifact).
3. **Pass condition:** the operation reaches `FAILED_SAFE` — before any USB traffic capable of
   altering flash contents. `WrongBoardPreflight` (built this pass, `docs/WP10_GATE_REPORT.md`
   §2) already proves the *matcher logic* is correct against JVM fixtures; this step proves the
   real broker wiring actually calls that matcher **before** authorizing the destructive transfer,
   on real hardware, not just in a unit test.
4. Repeat with a same-catalog-different-id mismatch (e.g. Uno vs. Nano, both `ARDUINO_CLI`) and an
   `UNCATALOGED` display-name mismatch, to cover both branches of the matcher's fallback rule.

## 5. Android USB permission flow — real device, real `UsbManager`

**Not built or testable this pass** (`docs/WP10_GATE_REPORT.md` §5) — the domain-side
`PERMISSION_REQUIRED` state (`DeviceConnectionMachine`, §3) is JVM-tested; the actual
`UsbManager.requestPermission()` / `PendingIntent` / `BroadcastReceiver` wiring is Android
instrumented-test territory with no JVM equivalent, and this repo has no `androidTest` source set
established yet for this domain. Owner-run steps once that wiring exists:

1. Connect a device for the first time (no prior grant) — confirm the OS permission dialog
   appears, and that declining it puts the connection at `DISCONNECTED` with a non-blank
   `stateReason` (never silently retries the request in a loop).
2. Confirm a granted permission persists across app restarts for the same physical device (Android
   remembers USB permission grants per app+device), and that revoking it via system settings is
   correctly observed on the next connection attempt.

## 6. Sign-off

This checklist is complete when every reference board in §1 has a filled-in §2/§3/§4 result, and
§5's flow has been exercised at least once per Android version the app targets. Record results
(pass/fail, anomalies, board/firmware versions) alongside this file or in whatever release-tracking
location the owner uses — this document does not prescribe where that record lives, only what it
must contain.
