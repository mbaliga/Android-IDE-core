# WP-9 Gate Report — language lane vertical slices: TypeScript, Python

**Scope:** `06_WORK_PACKAGES.md`'s WP-9 entry: "LanguagePack manifest + LSP lifecycle/capability
negotiation + URI mapping + diagnostics ownership; DAP host contract; task/launch typed
definitions; toolchain capsule manifest with an explicit `deliveryMechanism` field... Wire TS +
Python (interpreter lane) as far as JVM-verifiable; Rust/C++ lanes are contract +
REMOTE-mechanism only in this pass." **Genuinely greenfield, like WP-6's search domain**: no
ratified spec doc or schema corpus for language lanes existed before this pass —
`01_VALIDATION_REPORT.md` §E1 names "language pack, DAP host, capsules" as WP-level contract
additions rather than pre-authoring them in WP-1, and `04_ARCHITECTURE_CONTRACTS.md`'s own
artifact inventory has no language-lane section at all.

**Run date:** 2026-08-07. **Method:** the same real-compile-and-test standard every gate since
WP-2 — `./gradlew :core-engine:testFullDebugUnitTest`.

## Gate verdict: **GREEN** — one real test-authoring bug caught and fixed on the first attempt; clean on the second

---

## 0. What was built

**`contracts/kotlin/LanguageLaneContracts.kt`** (new domain, first contract file for this
package) — `LspCapability`/`DapCapability` (bounded, closed capability-ID sets, same
additive/MINOR `bumpRule` posture every other registry in this constellation uses),
`ToolchainDeliveryMechanism` (five members, grounded directly in `01_VALIDATION_REPORT.md`
§B1/§B3's W^X-exec-restriction and Play-interpreter-carve-out findings — not invented),
`LanguagePackManifest`, `ToolchainCapsuleManifest`, `TaskDefinition`, `LaunchConfiguration`, and
`BuiltInLanguagePacks` (concrete TypeScript/Python/Rust/C++ fixtures — TS and Python fully
declared per the brief's "interpreter lane," Rust/C++ intentionally bare, `lspCapsuleId`/
`dapCapsuleId` both null, "contract + REMOTE-mechanism only in this pass").

Five new files under `domain/language/`:

1. **`LspSessionMachine.kt`** — LSP client lifecycle (`NOT_STARTED → STARTING → NEGOTIATING →
   READY`, plus `DEGRADED`/`CRASHED`/`RESTARTING` recovery branches) derived directly from LSP's
   own `initialize`/`initialized` handshake, fail-closed in the same style as every state machine
   this build-out has written. `negotiate()` is a plain intersection of client-wanted and
   server-declared capabilities — no unilateral wish list wins.
2. **`DapSessionMachine.kt`** — the DAP host contract's session lifecycle, same derivation
   posture, adapted to DAP's `initialize`/`launch`/`attach`/breakpoint/`terminate` handshake.
3. **`LanguageUriMapper.kt`** — workspace-relative path ⇄ LSP `DocumentUri`, percent-encoding
   spaces and non-ASCII segments (LSP requires a legal RFC 3986 URI; naive string concatenation
   silently produces an illegal one the moment a filename contains a space).
4. **`DiagnosticsOwnership.kt`** — resolves which installed `LanguagePackManifest` owns
   diagnostics for a file extension; fails closed into `Conflicted` (never silently picks a
   winner by list order) when two packs claim the same extension.
5. **`ToolchainDeliveryLegality.kt`** — the per-mechanism, per-flavor legality table §B3
   requires ("the capability manifests must be per-mechanism"). **Proposed, not owner-ratified**
   — see §4.

34 new tests across 6 test classes.

## 1. `core-engine` JVM gate — GREEN on the second real attempt (one test bug caught, fixed, and re-verified)

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 40s
```

**1489 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip every gate since
WP-1L has reported), up from WP-8b's 1455-test baseline — exactly the 34 new tests, zero
regressions elsewhere:

| Test class | Tests |
|---|---|
| `LspSessionMachineTest` | 7 |
| `DapSessionMachineTest` | 5 |
| `LanguageUriMapperTest` | 6 |
| `DiagnosticsOwnershipTest` | 5 |
| `ToolchainDeliveryLegalityTest` | 5 |
| `LanguageLaneContractsTest` | 6 |

## 2. LSP/DAP capability negotiation + recovery suites, per the WP-9 gate criterion

Both session machines have a dedicated crash-and-restart test proving the full recovery cycle
returns to the exact same handshake states as a first start, not a shortcut:
`` `a full crash-and-restart recovery cycle returns to READY through the same handshake as first
start` `` (LSP) walks `READY → CRASHED → RESTARTING → STARTING → NEGOTIATING → READY`, explicitly
asserting the intermediate `STARTING` state is revisited rather than jumping straight back to
`READY`. Capability negotiation is proven against the real `BuiltInLanguagePacks.TYPESCRIPT`/
`.PYTHON` fixtures, not synthetic capability sets — e.g. `` `negotiate is the intersection of
client wants and server declares, never either side's unilateral wish list` `` asserts a
client-requested `WORKSPACE_SYMBOLS` is correctly dropped because TypeScript's real declared set
doesn't include it.

## 3. One real bug caught — in the test fixture, not the production code, caught by the first real Gradle run

`DiagnosticsOwnershipTest`'s `` `findAllConflicts surfaces every contested extension...` `` first
draft asserted the conflict set was exactly `{".h"}`. The real `BuiltInLanguagePacks.CPP` fixture
declares six extensions (`.cpp, .cc, .cxx, .h, .hpp, .c`), and the test's own `cLikeC` fixture
claims two of them (`.h` AND `.c`) — meaning `.c` was **also** genuinely contested, and the
production `findAllConflicts` correctly reported both. The test's expected value was wrong, not
`DiagnosticsOwnership`'s logic — caught by the first real Gradle run (`1 failed`), fixed by
correcting the assertion to `setOf(".h", ".c")` and adding an explicit `.c`-count check, re-run
green. Left as a code comment in the test file rather than silently corrected, per this session's
practice of naming a caught bug where it was caught rather than erasing the trail.

## 4. Honesty note on `ToolchainDeliveryLegality`'s per-flavor table

No `FB-RAT-*` decision ID exists for the delivery-mechanism-to-flavor legality mapping — no prior
spec names one, since this whole domain is greenfield this pass (§0 above). The table is grounded
in `01_VALIDATION_REPORT.md` §B1/§B3's actual findings (W^X exec restriction; Play's
interpreter/VM carve-out; "Play's own Dynamic Feature Delivery can also ship additional native
code through Play"; "the full/sideload SKU is not a policy-free escape valve") rather than
invented outright, and each mapping decision is explained inline in the file's own KDoc — but it
is a **reasoned proposal, not an owner ruling**, flagged here the same way WP-3 recorded
`FB-RAT-WS-NEW-1` and WP-8b recorded its `FB-RAT-PHN-011` implementation: real, tested code
against a documented rationale, not a silent invention presented as settled.

## 5. What's still genuinely unverified / honestly out of scope

- **No real LSP/DAP client implementation** — `LspSessionMachine`/`DapSessionMachine` are the
  state-shape law a real client must obey (same "adapter-ready seam" posture as
  `DocumentBufferMachine`/`WorkspaceProviderMachine`, WP-3); nothing here spawns
  `typescript-language-server`/`pyright`/`debugpy` as an actual subprocess and speaks the wire
  protocol. `LocalProcessExecutionProvider` (WP-4) is the natural adapter target for a future
  pass, the same way WP-5's `SshExecutionProvider` adapted an existing spine rather than
  reinventing process management.
- **No JSON wire-format codec** — unlike WP-2/WP-3/WP-4/WP-7's domains, WP-9's brief does not ask
  for envelope/persistence round-tripping for these manifests, so none was built; if a future pass
  needs to serialize a `LanguagePackManifest` to/from a package or a Room row, that codec doesn't
  exist yet, matching the "no consumer yet" pattern every WP since WP-2 has left for a comparable
  new piece.
- **No `AppContainer` wiring** — none of these five pieces has a live consumer yet.
- **Rust/C++ remain contract-only, no exception taken** — per the WP-9 brief's own instruction,
  not a gap this pass tries to close; `lldb-dap`/`codelldb`'s lack of a turnkey ARM64-Android
  build (`01_VALIDATION_REPORT.md` §5, competitive-picture section) is the specific blocker for
  ever giving C++ a local DAP capsule, not something a Kotlin-only pass could fix regardless.
- **`ToolchainDeliveryLegality`'s table is unratified** — see §4.
- **Toolchain capsule fetch/install/verify is not built** — `ToolchainCapsuleManifest` is a shape
  only; nothing here downloads, verifies a digest against, or installs a real capsule. That is
  closer to WP-8a's `LoopInstallationDriver` territory (import/activation of a package) than a
  language-lane concern specifically, and is left for whichever future pass actually needs it.
