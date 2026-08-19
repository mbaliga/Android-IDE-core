# WP-8 Gate Report — loop engineering lifecycle around GraphRunner

**Scope:** `06_WORK_PACKAGES.md`'s WP-8 entry: wire the WP-1L v2.1 loop contract corpus's real
run-lifecycle machinery — specifically `LOOP_ENGINEERING_SPEC_V2.1.md` §9's 17-state
`RunState` machine (`contracts/kotlin/LoopRuntimeContracts.kt`) — around the **already-real**
`GraphRunner` engine (`domain/loop/GraphRunner.kt`, pre-existing, CLAUDE.md's own repo map: "loop
(GraphRunner)"). Same "adapter over existing code, not a rewrite" shape as WP-3/WP-5/WP-6. WP-8a
(packages/transfer/import/activation) and WP-8b (phone authoring surfaces) are separate, later
work packages per the pack's own numbering — not attempted here.

**Run date:** 2026-08-07. **Method:** the same real-compile-and-test standard every gate since
WP-2 — `./gradlew :core-engine:testFullDebugUnitTest`.

## Gate verdict: **GREEN** — compiled and passed on the first real build attempt

---

## 0. What was built

**`domain/loop/LoopRunDriver.kt`** — wraps `GraphRunner` (untouched) and drives the real §9
state machine around it:

- Emits the full, structurally-valid event sequence every run passes through — `CREATED →
  PREFLIGHT → WAITING_BINDING → WAITING_AUTHORITY → READY → RUNNING → <terminal>` — using
  `RunState.isValidTransition` (already real, from WP-1L) as the actual gate: an illegal jump is
  not just untested, it is **uncompilable**, since `RunEvent`'s own constructor calls that
  function and throws.
- Translates `GraphRunner`'s flat `GraphRunResult` (steps + `stoppedBecause` + `reachedEnd`) into
  the full `LoopRun` audit record: one `NodeAttempt` per executed `GraphStep`, a correctly
  §9-categorized `TerminalReason`, and `RunOutputs` carrying the final step's output.
- A mid-run cancellation correctly routes `RUNNING → CANCELLING → CANCELLED` (two events, not
  one) — `RUNNING`'s own outgoing set in the transition table does not include `CANCELLED`
  directly, only `CANCELLING`; getting this wrong would have made `LoopRunDriver` itself violate
  the contract it exists to enforce.
- `resolveBinding`/`resolveAuthority` are explicit, documented seams for `WAITING_BINDING`/
  `WAITING_AUTHORITY` — see §2 for why they default to auto-resolve rather than being deeply
  wired to WP-10's Device Broker / WP-4's `AuthorityEngine` this pass.

One new test file, 8 new tests.

## 1. `core-engine` JVM gate — PASS, first real attempt

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 1m 6s
```

**1402 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip every gate since
WP-1L has reported), up from WP-7's 1394-test baseline.

## 2. Honest scope boundary: `WAITING_BINDING`/`WAITING_AUTHORITY` are auto-resolved, not deeply integrated

§9's transition table makes `WAITING_AUTHORITY` (reached via `PREFLIGHT` or `WAITING_BINDING`)
the **only** path to `READY` — every run structurally visits it, even one that needs no real
device target or explicit grant. `GraphRunner` itself has no device-binding or authority concept
at all (it calls an abstract `dev.fonebrew.domain.council.Generator`, already resolved by the
caller) — so for the runs this pass can actually exercise, both states are genuinely "nothing to
wait for." `LoopRunDriver` models this honestly with two seams (`resolveBinding`/
`resolveAuthority`, both `suspend () -> Boolean`, defaulting to `{ true }`) rather than silently
skipping the states outright (which would violate §9's structure) or fabricating a fake
integration with WP-10's Device Broker (not built yet) or WP-4's real `AuthorityEngine` (built,
but never actually consulted here — wiring a specific `fb.*` capability check into every loop run
is a real design decision belonging to whichever pass first needs a loop run to actually gate on
authority, not invented speculatively in this one). `` `an unresolved binding cancels the run
before ever reaching WAITING_AUTHORITY` ``/`` `a declined authority resolution cancels the run via
CANCELLING...` `` prove both seams are real, live decision points, not vestigial parameters.

## 3. Interpretation choice: `SUCCEEDED_UNVERIFIED` maps to `REQUIRED_VERIFIER_INCOMPLETE`

`GraphRunner` performs no result verification of its own — a graph reaching its end event always
produces `SUCCEEDED_UNVERIFIED`, never `SUCCEEDED_VERIFIED`. The closed `TerminalReasonCategory`
vocabulary (§9, `LoopRuntimeContracts.kt`) has `ALL_REQUIRED_VERIFIERS_PASSED` for the verified
case but no explicit "no verifiers were ever configured" category — `REQUIRED_VERIFIER_INCOMPLETE`
was chosen as the closest fit (verification is, in a real sense, incomplete — it never started).
Flagged here as an interpretation, not a citation, the same honesty standard WP-4's
`RequireStrongerAuthority`/`escalateToRung` design note and WP-6's `SearchKind.TEXT` reuse both
already set.

## 4. Structural correctness proven two ways, not one

`LoopRunDriverTest`'s `assertLegalEventSequence` helper re-derives legality from the full visited
state sequence (every `RunEvent.fromState → toState` pair checked against
`RunState.isValidTransition`), run on every test case — belt-and-suspenders on top of the fact
that `RunEvent`'s own constructor already makes an individually-illegal transition uncompilable.
This catches a class of bug neither the constructor check nor a single-event unit test would: a
DRIVER-LEVEL sequencing bug (e.g. skipping a required intermediate state) that produces only
individually-legal pairwise transitions but an illegal overall path. None were found, but the
mid-run-cancellation case (§0) is exactly the kind of bug this class of check exists to catch.

## 5. What's still genuinely unverified / honestly out of scope

- **No real device binding or authority integration** — see §2. A future pass wiring
  `resolveAuthority` to `AuthorityEngine.evaluate()` for a real `fb.loop.run`-shaped capability
  (not yet in `CapabilityRegistry`) is a natural next step once a real caller needs it.
- **`WAITING_USER`/`SUSPENDED`/`UserIntervention`** — §9's two additional non-terminal states for
  a run that pauses for human input or an explicit suspend are not exercised: `GraphRunner` has
  no notion of pausing mid-graph for user input today (a real gap in the underlying engine, not
  something this pass's driver can paper over without changing `GraphRunner` itself, which was
  deliberately left untouched).
- **No persistence** — `LoopRunDriver.run()` returns a complete `LoopRun` value; nothing writes
  it anywhere (no Room table, no `ReceiptStore` entry). Matches the "no consumer yet" pattern
  every WP since WP-2 has left for comparable pieces.
- **`targetDecisions`/`authorityGrants`/`sideEffectState`** on the resulting `LoopRun` are always
  empty/null this pass — real values require the binding/authority integration named above.
