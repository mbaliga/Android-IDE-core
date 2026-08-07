# WP-8a Gate Report — packages, transfer, import, activation

**Scope:** `06_WORK_PACKAGES.md`'s WP-8a entry: implements (WP-1L already ratified the contracts)
`LOOP_ENGINEERING_SPEC_V2.1.md` §21's transfer/activation boundary and
`LOOP_IMPORT_ACTIVATION_CONTRACT.md`'s ten-step import grammar over its real eleven-state
`InstallationState` machine (`contracts/kotlin/LoopActivationContracts.kt`). §20's package
**build** pipeline (freeze/canonicalize/scan/sign/build-receipt, the *authoring*-side
counterpart) is a separate, larger undertaking this pass does not attempt — flagged in §4, not
silently folded in.

**Run date:** 2026-08-07. **Method:** the same real-compile-and-test standard every gate since
WP-2 — `./gradlew :core-engine:testFullDebugUnitTest`.

## Gate verdict: **GREEN** — compiled and passed on the first real build attempt

---

## 0. What was built

**`domain/loop/LoopInstallationDriver.kt`** — drives the real, already-implemented
`InstallationState.isValidTransition` (WP-1L) through the ten-step main path: `ACQUIRING →
SNAPSHOTTED → CONTAINER_VERIFIED → PARSED_VALIDATED → COMPATIBILITY_EVALUATED → PREVIEWED →
WAITING_BINDINGS → WAITING_AUTHORITY → READY_TO_SIMULATE → INSTALLABLE → INSTALLED`, or one of
its five terminal off-ramps (`REJECTED_UNSAFE`, `BLOCKED_INCOMPATIBLE`, `CANCELLED`,
`REJECTED_POLICY`, `FAILED_SAFE`). Genuinely enforces FB-RAT-IMP-002 ("transfer moves inert
bytes... no model, tool, shell, remote, device, or side-effecting node may execute during
parsing, validation, preview, or installation") **by construction**, not just by convention:
everything through `PREVIEWED` is pure byte/JSON inspection (digest comparison, JSON parsing,
compatibility evaluation over already-parsed data); the only injected `suspend` seams
(`resolveBindings`/`resolveAuthority`/`runSimulation`) are never called before `WAITING_BINDINGS`.

One new test file, 8 new tests.

## 1. `core-engine` JVM gate — PASS, first real attempt

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 53s
```

**1410 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip every gate since
WP-1L has reported), up from WP-8's 1402-test baseline.

## 2. Fail-closed at every real decision point, proven per-branch

- **A digest mismatch** (`TransferEnvelope.expectedDigest` ≠ the actual SHA-256 of the received
  bytes) is `REJECTED_UNSAFE` *before the manifest is ever parsed* — `` `a digest mismatch is
  REJECTED_UNSAFE before the manifest is ever parsed` `` asserts `parseManifest` was never even
  called, not just that the outcome was correct.
- **An unparseable manifest** is `REJECTED_UNSAFE` (a real thrown exception from the caller's
  `parseManifest` is caught, not left to propagate and crash the driver).
- **A `BLOCKED`/`UNSUPPORTED` compatibility outcome** is `BLOCKED_INCOMPATIBLE`, proven to never
  reach the binding-resolution seam at all (`` `...never proceeding to a binding prompt` ``).
- **A declined binding or authority resolution** cancels the install (`CANCELLED`) — and declining
  authority is proven to never reach `runSimulation` (`` `...never reaching simulation` ``).
- **Every transition the driver performs is checked against `InstallationState.isValidTransition`
  internally** (`reject()`'s own `require()`) — a bug that produced an illegal transition would
  fail LOUDLY at the point it happens, not silently construct a contract-violating record. This
  caught nothing this pass (unlike WP-8's `LoopRunDriver`, where the same style of internal check
  would have caught the mid-run-cancellation sequencing bug had one existed here), but is the same
  belt-and-suspenders discipline, applied consistently rather than only where a bug happened to
  already exist.

## 3. `LoopInstallation`'s own strict `init{}` invariants did real work as a correctness check

`LoopInstallation` requires `compatibilityReportRef` non-null from `COMPATIBILITY_EVALUATED`
onward, and `bindingProfileRef` + a `TERMINAL_IMPORTED_STATES`-member `signatureState` + non-empty
`receipts` specifically at `INSTALLED`. Writing `LoopInstallationDriver` against these
already-real, already-strict constraints meant a genuine implementation mistake (e.g. forgetting
to attach the compatibility ref before rejecting at `BLOCKED_INCOMPATIBLE`) would have failed to
compile-and-run at all, not silently produced an invalid record — the same "the contract's own
strictness does verification work no fixture could" pattern WP-4/WP-7's gate reports already
demonstrated for their own domains.

## 4. What's still genuinely unverified / honestly out of scope

- **§20's package build pipeline** (freeze a draft revision, canonicalize, secret-scan, run
  fixtures, generate provenance, inventory + content-digest, optionally sign, emit a build
  receipt) is the *authoring*-side counterpart to this pass's *import*-side work and is **not
  built this pass** — a real implementation needs a canonical-JSON serializer matching WP-1L-G0's
  `canonicalization.v1.json` profile and a real secret-scanning pass, both non-trivial enough to
  warrant their own scoped pass rather than a rushed addition here.
- **No real compatibility evaluator** — `evaluateCompatibility` is a caller-supplied function;
  this pass does not itself implement `LOOP_COMPATIBILITY_CONTRACT.md`'s real semantic-version-
  range / capability-declaration comparison logic, only the state-machine plumbing that consumes
  its verdict.
- **No real binding/authority resolution UI or persistence** — `resolveBindings`/
  `resolveAuthority`/`runSimulation` are seams; nothing calls `AuthorityEngine.evaluate()` (WP-4)
  or a real slot-to-provider matcher yet. Same honest-gap shape as WP-8's `LoopRunDriver`.
- **No signature verification** — an unsigned package always resolves to
  `IMPORTED_UNSIGNED_NARROWED_GRANTS`; a "signed" package (per `manifest.signatureRef != null`)
  is marked `IMPORTED_SIGNED_VERIFIED` without this pass actually verifying a real cryptographic
  signature against a publisher key — `security/KeystoreSecret.kt` exists in this codebase but
  isn't wired to this flow yet.
- **No persistence** — `LoopInstallationDriver.install()` returns a complete
  `InstallationOutcome`; nothing writes it to Room or `ReceiptStore`. Matches the "no consumer
  yet" pattern every WP since WP-2 has left for comparable pieces.
