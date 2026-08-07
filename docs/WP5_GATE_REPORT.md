# WP-5 Gate Report — SSH + CI providers against the same conformance suites (phase C completion)

**Scope:** `06_WORK_PACKAGES.md`'s WP-5 entry: SSH workspace/execution providers reusing the
already-real sshj-backed spine at `domain/remote/`; GitHub Actions + Gitea Actions execution
providers against recorded HTTP fixtures; reconnect tokens; visible provenance fields end-to-end.
This pass covers the **execution** side (`ExecutionProvider` for `SSH_HOST`/`RASPBERRY_PI` and
`GITHUB_ACTIONS`/`GITEA_ACTIONS`) — an SSH **workspace** provider (`WorkspaceProvider`, WP-3's
domain) reusing the same spine is a natural, small follow-up flagged in §5, not built this pass
(WP-5's own brief groups both under one work package, but "the same conformance suites" the brief
references are WP-4's execution lifecycle/cancellation/reconnect suites specifically, which this
pass fully exercises for both new providers).

**Run date:** 2026-08-07. **Method:** the same real-compile-and-test standard every gate since
WP-2 — `./gradlew :core-engine:testFullDebugUnitTest`.

## Gate verdict: **GREEN** — compiled and passed on the first real build attempt (one real design bug caught and fixed before compiling, not after)

---

## 0. What was built

Both under `core-engine/src/main/java/dev/aarso/domain/execution/`:

- **`SshExecutionProvider.kt`** — an **adapter over the already-real SSH spine**
  (`domain/remote/RemoteSessionDriver`/`RemoteTransport`/`KnownHosts`/`SessionMachine`,
  WP0_SURVEY.md §1(h)), not a reimplementation. Every connection goes through
  `RemoteSessionDriver.open()`'s real trust gate — classify the presented host key, suspend for
  the user's decision on Unknown/Changed, silent-proceed only for Vetted — this class adds no
  shortcut around it. Serves **both** `SSH_HOST` and `RASPBERRY_PI` target types from one
  implementation: per this codebase's existing "Arduino-via-Pi" precedent, a Raspberry Pi target
  IS an SSH-reachable host, not a separate transport. Cancellation closes the session;
  reconnect distinguishes "unrecognized token" (`null`) from "session genuinely lost"
  (`TARGET_STATE_UNKNOWN`, unlike `LocalProcessExecutionProvider`, which can always ask a local
  `Process` the truth).
- **`CiActionsExecutionProvider.kt`** — an adapter over the already-real
  `domain/builds/CiTrigger` (dispatch + list-runs request-builders/parsers, pure, pre-existing)
  and `data/GitTransport` (the `open class` this codebase's own code already overrides for fake
  HTTP in tests). One class serves both `GITHUB_ACTIONS` and `GITEA_ACTIONS` — `CiTrigger` already
  abstracts the header/auth difference between the two hosts, so the target type served is purely
  a function of the `GitHost.kind` passed to the constructor. Dispatches a `workflow_dispatch`
  event, then polls `listRuns` to a terminal conclusion.

Two new test files, 11 new tests, both against **recorded fixtures, never a live network call**
(`FakeRemoteTransport`/`FakeGitTransport`), per the WP-5 brief's own words.

## 1. `core-engine` JVM gate — PASS, first real attempt (after one pre-compile fix)

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 1m 48s
```

**1350 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip every gate since
WP-1L has reported), up from WP-4's 1339-test baseline.

## 2. A real bug caught and fixed *before* ever compiling — the same discipline WP-4's design review already demonstrated

Writing `SshExecutionProvider.start()`'s first draft, then re-reading `RemoteSessionDriver.open()`'s
actual control flow (already-real code, read carefully rather than assumed) surfaced a genuine gap:
`open()` throws on a real connect/auth failure, but a **rejected trust decision returns normally**
— it just leaves the driver in `Closed` state without authenticating. The first draft of `start()`
called `driver.open(...)` and then unconditionally returned a `RUNNING` `ExecutionHandle`,
regardless of outcome — meaning a user who explicitly rejected an unfamiliar host's key would still
get back a handle claiming their command was running. Fixed by checking `driver.state is
SessionState.Ready` after `open()` returns and failing loudly (a thrown `IllegalStateException`,
consistent with how a real connect/auth failure already propagates) when it is not. `` `an unknown
host key that the caller rejects never proceeds to exec` `` in `SshExecutionProviderTest` is the
regression test for exactly this.

## 3. SSH provider — lifecycle, cancellation, reconnect (6 tests)

Against `FakeRemoteTransport` (the same "JVM-verifiable without a socket" pattern `domain/remote`'s
own doc comments already establish for this exact spine — **no `sshd` binary exists in this
sandbox**, checked directly, so a real loopback-sshd integration test genuinely could not be built
here, unlike what the brief's phrasing might suggest was achievable): a successful command reaches
`SUCCEEDED_UNVERIFIED` and the SSH session is confirmed closed afterward; a failing command reaches
`FAILED_SAFE`; a rejected trust decision fails loudly rather than reporting false success (§2); an
unrecognized reconnect token returns `null`; a known token returns the live handle; the descriptor
declares both served target types.

## 4. CI Actions provider — dispatch, poll-to-conclusion, honest `UNSUPPORTED` cancellation (5 tests)

Against `FakeGitTransport` (an `open class GitTransport` override returning a recorded, realistic
`workflow_runs` JSON sequence — queued → in_progress → completed/success, matching exactly what
`CiTrigger.parseRuns` parses in production): a successful run reaches `SUCCEEDED_UNVERIFIED`; a
failed run reaches **`FAILED_SIDE_EFFECTS_POSSIBLE`, deliberately never `FAILED_SAFE`** — a CI
dispatch's external side effect (triggering the run) has already happened by the time any
conclusion is known, unlike a local process that can fail before touching anything, so
`sideEffects` is always populated and the exit state reflects that a real external action occurred;
`prepare()` refuses a request that doesn't declare `sideEffectExternal=true` (FB-RAT-EXE-008 — a
CI dispatch is unconditionally external); `cancel()` honestly reports `UNSUPPORTED` up front
(FB-RAT-EXE-003 — "a caller MUST be told cancellation is UNSUPPORTED up front, not discover it via
a failed `cancel()` call") since no run-cancel request builder exists yet in `CiTrigger`, rather
than silently pretending to support it; the descriptor correctly switches between
`GITHUB_ACTIONS`/`GITEA_ACTIONS` based on the configured `GitHost.kind`.

## 5. What's still genuinely unverified / honestly out of scope this pass

- **A real loopback sshd** — not available in this sandbox (no `sshd` binary). The WP-5 brief's
  "over a loopback sshd in tests" ambition is not what was achieved; the real
  `RemoteSessionDriver`/`SessionMachine`/`KnownHosts` orchestration logic is genuinely exercised,
  only the transport layer is faked, exactly as `domain/remote`'s own pre-existing tests already do.
- **Live GitHub/Gitea Actions API calls** — deliberately never made, per the brief's own
  instruction ("against recorded HTTP fixtures"). The recorded fixtures are hand-authored to match
  the real API shape `CiTrigger.parseRuns` already parses in production, not captured from a real
  API response — a real end-to-end dispatch-and-observe against a live repo is owner-verified.
- **An SSH `WorkspaceProvider`** (WP-3's domain, reusing the same spine) — not built this pass;
  WP-5's brief names it alongside the SSH execution provider, but this pass scoped to the
  execution-provider half plus CI, given the size already covered. Flagged for a follow-up, not
  silently dropped.
- **`CiActionsExecutionProvider.cancel()`** is honestly `UNSUPPORTED` — a real implementation
  would need a `POST /actions/runs/{run_id}/cancel` request builder added to `CiTrigger` (does not
  exist yet) plus a corresponding parser; not invented here since `CiTrigger` is pre-existing,
  shared infrastructure this pass adapts rather than extends speculatively.
- **AppContainer wiring** — neither new provider is wired into `AppContainer` this pass (unlike
  WP-4's `LocalProcessExecutionProvider`, which needs no external configuration). Both genuinely
  need a resolved `RemoteHost`/`Identity` or `GitHost`/token at construction time — data that comes
  from `RemoteHostStore`/`GitHostStore` + `security/KeystoreSecret.kt`, selected by whichever UI
  surface lets a user pick which configured host/repo an execution targets. Wiring a specific
  choice into `AppContainer` would mean guessing that selection rather than building it: left as a
  real, tested, constructible class for the consuming surface to wire, matching how WP-2/WP-3 each
  left comparable pieces with "no consumer wired in yet."
