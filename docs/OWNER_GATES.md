# Owner Gates

> **License:** `LICENSE-PENDING` — see `docs/non_ratified/LICENSE_PENDING.md`.

**What this is:** the single consolidated list of everything across WP-0 through WP-10 that
needs a real human — real hardware, a real owner decision, or a real GitHub-account-level action
— rather than more code from a future session. Written at WP-11 closeout (2026-08-07). Nothing
below is something this session can close; that is the point of collecting it here rather than
leaving it scattered across ten gate reports.

## A. Real-hardware release gates

**`docs/DEVICE_GATE_CHECKLIST.md`** (WP-10, operationalizing `FB-RAT-DEV-008`) — 20 repeated
clean-flash cycles, interrupted-flash-at-multiple-progress-points, and wrong-board-block tests,
per reference board across the nine `UsbFamily` values. Zero of this is executable in this
sandbox (no device/emulator/board exists here). Owner needs real boards, at minimum one per
family with actual hardware availability (§1 of that checklist is an empty table waiting on
owner input).

**Android USB permission flow** (`WP10_GATE_REPORT.md` §5) — `UsbManager`/`PendingIntent`/
`BroadcastReceiver` wiring around `DeviceConnectionMachine`'s `PERMISSION_REQUIRED` state has no
JVM equivalent; needs a real Android instrumented test, owner-run, once that wiring exists (it
doesn't yet — no `androidTest` source set is established for this domain).

**Every prior WP's own "owner-verified only" runtime claim** — the full list lives in each
`docs/WP*_GATE_REPORT.md`'s own gaps section; the load-bearing ones, consolidated:
- Real on-device launch/render/gesture behavior for the entire app (`CLAUDE.md` "Environment
  honesty" — the container has no device, full stop).
- WP-10's `Uf2Codec`/`DfuStatus`/`SlipFraming` — real, tested byte-level codecs, but their exact
  protocol constants are sourced from general public documentation, not cross-checked against a
  real ESP32/RP2040/DFU device or an official spec text in this sandbox.
- `Stk500`'s pre-existing STK500 implementation — same caveat, inherited from before this
  session, not new here.

## B. GitHub / account-level actions

**`mbaliga/Android-IDE-Studio` PR #86** — draft, open, `mergeable_state: unstable`, CI red on a
private cross-repo submodule access issue (`mbaliga/Android-IDE-core` returns "Repository not
found" to the default `GITHUB_TOKEN` when Studio's CI tries to clone the `core` submodule).
Diagnosed in that PR's own comment thread as an owner-level fix: either a PAT/deploy-key secret
granting Studio's CI access to the private `android-ide-core` repo, or temporarily making
`android-ide-core` public. **Not something further code changes in either repo can resolve** —
local verification of both repos' real Gradle gates is green regardless of this CI-infra gap.

**`mbaliga/Android-IDE-core` PR #16** — draft, open, tracks `claude/fonebrew-development-clzu43`.
CI (`build-test`) was in progress at WP-11 closeout time (workflow run triggered by the WP-10
push, `native-assemble` correctly skipped per this repo's own `if: false` native-assemble
convention). No action needed unless/until a run actually goes red — if it does, check whether
it's the same GitHub-Actions-billing-block pattern `HANDOFF_STATE.md`'s build-environment note
already documents (a red `build-test` that dies in 3-6s with no runner assigned is a minutes/
spending-limit issue, not a code problem) before assuming a code regression.

## C. Standing owner decisions

Restated from `HANDOFF_STATE.md`'s "Open threads" and `docs/NEXT_SESSIONS.md`'s closing section —
not duplicated in full here, just indexed:

1. **Differentiator-first vs. substrate-first work-package reordering** — nobody has ruled
   otherwise, so substrate-first (the order this session actually followed, WP-0→WP-10) stands.
2. **The bounded-backend amendment (`MKT-001`)** — needs the supersession record, a legal/Play
   review, and a phasing decision before any hosted-marketplace work could begin.
3. **`FB-RAT-PHN-011`** (undo/redo, `docs/ratified/loops/LOOP_PHONE_AUTHORING_SPEC.md` §14) —
   implemented as real code (WP-8b, `LoopDraftUndoStack`), never ratified. Accept, reject, or fold
   into an existing ID.
4. **`FB-RAT-WS-NEW-1`** (git library choice — JGit + libgit2-JNI evaluation, WP-3) and
   **`FB-RAT-LANG-NEW-1`** (toolchain delivery-mechanism/flavor legality table, WP-9) — both
   PROPOSED in `docs/non_ratified/EXPERIMENTAL_DECISIONS.md`'s open queue, neither self-ratified.
5. **Licensing** — `android-ide-core`'s license is undecided (`LICENSE-PENDING` throughout this
   entire corpus); every file emitted across WP-0 through WP-10 carries that marker pending an
   owner decision, per `03_CONSTELLATION_CONTEXT.md`'s own note that this blocks publishing new
   public contract text with a real license header.
6. **Two new-domain contracts with no `FB-RAT-*` home** (`docs/TRACEABILITY_MATRIX.md` Part C) —
   `SearchContracts.kt` (WP-6) and `LanguageLaneContracts.kt` (WP-9) are real, tested, but were
   never part of the original 109-ID ratification register. Formalize with a proper decision-ID
   sequence, or consciously leave as implementation-only — either is fine, but nobody has chosen.

## D. Full-harness verification, as of this closeout

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest :core-engine:testPlayDebugUnitTest :core-engine:checkLicense
```
is the real CI gate command (`.github/workflows/ci.yml`'s `build-test` job) — see
`docs/WP11_GATE_REPORT.md` for this closeout's own run of the complete command (not just the
`testFullDebugUnitTest` slice every per-WP gate report above ran individually) and its result.
