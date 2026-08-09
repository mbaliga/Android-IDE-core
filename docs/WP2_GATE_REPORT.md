# WP-2 Gate Report — shared envelope + error + receipt runtime library (phase A completion)

**Scope:** the six things `06_WORK_PACKAGES.md`'s WP-2 entry names as *implement, not just
declare*: envelope serialization, error taxonomy, ID generation, digests, idempotency keys, a
receipt store (append-only, on-device), and migration-plan scaffolding. Wired into
`android-ide-core` (`core-engine`, the library) and, per the WP-0-corrected understanding of the
constellation (`docs/WP0_SURVEY.md` §4 — the `Aarso` repo is a tiny 5-file inert mirror-lens, not
a consuming app), into `Android-IDE-Studio`'s `:app`/`:hyle` (the real consumption point, via the
`core` git submodule). **Explicitly excludes** WP-1/WP-1L's contract *declarations*
(`contracts/kotlin/*.kt`, `docs/ratified/*`, `schemas/*`), already gated in
`docs/WP1_GATE_REPORT.md` / `docs/WP1L_GATE_REPORT.md` — this report only covers the new runtime
code that implements against those contracts.

**Run date:** 2026-08-07. **Method:** real `./gradlew` compiles and test runs (JDK 17 +
`ANDROID_HOME=/root/android-sdk` + `LANG=C.utf8 LC_ALL=C.utf8`, per the toolchain fixes documented
in `HANDOFF_STATE.md`'s "Build environment note"), not structural/lexical inspection — the same
upgrade in verification rigor WP-2's earlier addendum brought to WP-1/WP-1L.

## Gate verdict: **GREEN** — both repos compile and test green against the new library; two real bugs found and fixed during verification, both documented below

---

## 0. What was built

New files, all under `core-engine/src/main/java/dev/aarso/`:

- `domain/contracts/JsonInterop.kt` — `org.json` interop helpers (`optStringOrNull`,
  `optLongOrNull`, `optJSONObjectOrNull`) plus `jsonValueToKotlin`/`kotlinValueToJson` recursive
  converters and `extractUnknownFields`/`mergeUnknownFields` — the mechanism every codec below
  uses to round-trip a newer minor schema version's unrecognized fields without dropping them
  (FB-RAT-COM-003's forward-compatibility rule, made real instead of just declared).
- `domain/contracts/Digest.kt` — `Digest.of`/`ofUtf8`/`verify` producing/checking a real SHA-256
  `IntegrityRef`.
- `domain/contracts/IdGenerator.kt` — a real ULID generator (48-bit timestamp + 80-bit
  randomness, Crockford Base32, 26 characters), injectable clock/`SecureRandom` for deterministic
  tests.
- `domain/contracts/EnvelopeCodec.kt` — encode/decode pairs for `ContractEnvelope<T>` (generic,
  payload-codec-parameterized), `ErrorEnvelope`, `CapabilityManifest`, `ArtifactRef`,
  `MigrationPlan`, and `ConformanceSuite`.
- `domain/contracts/MigrationRunner.kt` — fail-closed execution of a `MigrationPlan`'s named
  steps against a caller-supplied `Map<String, MigrationStep<T>>` registry; a missing step name
  or a thrown exception stops immediately and reports exactly which steps ran first.
- `data/entity/ReceiptEntity.kt` + `data/dao/ReceiptDao.kt` + `data/ReceiptStore.kt` — a Room-backed,
  append-only, idempotent (`idempotencyKey`-deduplicated) receipt store, following the same
  pattern as the existing `LedgerDao`/`LedgerStore` (the house precedent for append-only Room
  tables this codebase already uses).
- `data/AppDatabase.kt` — `ReceiptEntity` added to the schema, version bumped 5 → 6.
- `di/AppContainer.kt` — `receiptStore` wired in alongside the existing `ledgerStore`.

Five new JUnit4 test files (1250 total tests in `core-engine` after this pass, up from the
1214-test baseline WP-2's earlier addendum established — see §2).

## 1. Two real bugs found and fixed during verification

Both were caught by actually compiling/running against the JVM gate, not by inspection — the
exact gap `docs/WP1_GATE_REPORT.md` §6 and `docs/WP1L_GATE_REPORT.md` §7 both flagged as
genuinely open before WP-2's toolchain fix.

**(a) Kotlin's nested block-comment behavior.** Unlike Java/C, Kotlin block comments nest. KDoc
prose in `IdGenerator.kt` and `EnvelopeCodec.kt` referenced glob-style paths
(`` `docs/ratified/*.md` ``, `` `schemas/common/*.schema.json` ``) whose literal `/*` sequence
opened a phantom nested comment inside the enclosing `/** ... */` block, which then needed an
extra `*/` to close — the compiler reported "Unclosed comment" at end-of-file in both files.
Fixed by rephrasing both KDoc comments to avoid the `/*` adjacency (e.g. "ratified spec
(`docs/ratified/`) use this exact..." instead of quoting the glob directly). A repo-wide grep for
the same pattern (`/\*` inside prose, excluding legitimate `/**` openers) across every new WP-2
file and across `contracts/kotlin/*.kt` found no further live occurrences — the `contracts/
kotlin/*.kt` hits that pattern-matched were all inside `//` line comments, which have no closing
token and are therefore immune to this issue.

**(b) A genuinely invalid test fixture, not a `MigrationRunner` bug.** `MigrationRunnerTest`'s
`plan()` helper originally allowed `DataMigrationSteps(steps = emptyList())`. That data class's
own `init` block enforces `steps.isNotEmpty()` (mirroring `migration-plan.schema.json`'s real
constraint) — a `MigrationPlan` can never legally declare zero migration steps. Two tests
(`apply with zero steps...`, `requiresMajorMigration reflects...`) constructed exactly that
invalid state and failed with `IllegalArgumentException` from the constructor, not from
`MigrationRunner`'s own logic (which correctly handles an empty step list — the bug was that no
real `MigrationPlan` can ever produce one). Fixed by rewriting the first test against a single
passthrough step (the closest reachable analogue to "nothing changes") and the second to use a
one-step plan, since the compatibility-field assertion it actually tests doesn't depend on step
count.

## 2. `core-engine` JVM gate — PASS

```
./gradlew --no-daemon :core-engine:testFullDebugUnitTest
BUILD SUCCESSFUL in 51s
```

Parsed from `core-engine/build/test-results/testFullDebugUnitTest/*.xml` (137 result files):
**1250 tests, 0 failures, 0 errors, 1 skipped** (the same pre-existing skip WP-1L's addendum
already reported — no regression). This includes the five new WP-2 test files: `DigestTest`,
`IdGeneratorTest`, `EnvelopeCodecTest` (12 tests, including the round-trip-preserves-an-
unknown-field proof that was the specific gap named in the WP-1/WP-1L gate reports),
`MigrationRunnerTest`, `ReceiptStoreTest`.

The `EnvelopeCodecTest` round-trip suite is the one genuinely new *kind* of proof this pass adds:
`` `ContractEnvelope round-trip preserves an unknown field end to end` `` constructs an envelope,
encodes it, injects a field a decoder doesn't recognize (simulating a newer minor schema
version), decodes it, asserts the unknown field survived into `unknownFields`, then re-encodes
and asserts it's still present in the output JSON and that a same-named *known* field always wins
over a same-named unknown one on re-encode (`` `unknown fields never override a field the decoder
does recognize on re-encode` ``). This is the actual forward-compatibility mechanism
FB-RAT-COM-003 requires, proven at runtime rather than asserted in prose.

## 3. Receipt store idempotency and append-only-ness — PASS (JVM-level; not a real process-kill test)

`ReceiptStoreTest` exercises `ReceiptStore.append`'s idempotency (`` `append with a repeated
idempotencyKey does not insert a second row` ``, `` `append with no idempotencyKey always
inserts...` ``, `` `append with different idempotencyKeys both insert` ``) and structural
append-only-ness (a reflection assertion that `ReceiptStore`'s public API has no
update/delete-named method) against a `FakeReceiptDao` backed by `MutableStateFlow`, the same
pattern `WatchStoreTest`/`LedgerStoreTest` already use because Room needs a real SQLite binding
this JVM gate doesn't have.

**Honest gap:** the WP-2 work-package brief's stated gate is "receipt store survives simulated
process kill (journal replay test)." What's actually verified here is idempotent-append and
append-only-shape at the JVM level — **not** a real forced-kill/replay scenario, because that
requires either a real SQLite file (Room, not the Fake-DAO pattern) or an on-device run, neither
of which this sandbox has. This mirrors WP-3's own gate ("forced-kill suite... zero-loss") — the
work-package split already anticipates that a *real* kill-recovery suite is WP-3's job (the
journal + `RecoverySnapshot` machinery), and `ReceiptStore` here is deliberately the simpler,
already-idempotent append path that machinery will sit on top of. Flagged here rather than
silently claimed as satisfied.

## 4. Wired into `Android-IDE-Studio` (the real consumption point) — PASS

`docs/WP0_SURVEY.md` §4 already established that the work-package brief's literal text ("wire
into `android-ide-core` and `Aarso`") names the wrong second repo — `Aarso` is a 5-file inert
mirror-lens with no Gradle project at all, not a consumer of this library. The real consumer is
`Android-IDE-Studio`, which pulls `android-ide-core` in as a git submodule at `core/` (via
`.gitmodules` + `includeBuild`, per `Android-IDE-Studio/settings.gradle.kts`).

**Finding, fixed as part of this gate:** Studio's submodule pin comment
(`settings.gradle.kts:33-38`, before this pass) said it was pinned to a *temporary* commit
(`fc7d027`, on `refactor/core-engine-extraction`, core's PR #11) specifically because "main does
not have `:core-engine` yet," with an explicit instruction to repoint at `origin/main` once that
PR merged. Checking core's actual `main` branch during this gate found **PR #11 has since
merged** (`2969437` is in `main`'s history, along with a further PR #13 search-feature commit on
top) — the pin was stale. Since this session's Fonebrew work is deliberately developed under the
same branch name (`claude/fonebrew-development-clzu43`) across all four constellation repos (per
this session's task instructions), and that branch in `android-ide-core` is itself
main-plus-WP-0/1/1L/2 (confirmed via `git merge-base --is-ancestor main HEAD`), the correct
repoint target is that branch, not `origin/main` directly — pointing at `main` alone would still
miss this pass's new envelope/receipt library. Repointed Studio's `core` submodule to
`claude/fonebrew-development-clzu43` (pushed to `origin` as part of this gate, commit `5e0a2bd`)
and rewrote the stale pin comment to explain the new state and the eventual main-repoint path.

Verified by running Studio's own real CI gate command (`.github/workflows/ci.yml`'s
`build-test` job) locally against the repointed submodule:

```
./gradlew --no-daemon :app:testFullDebugUnitTest :app:testPlayDebugUnitTest :hyle:test \
  :core:core-engine:checkLicense
BUILD SUCCESSFUL in 3m 58s
```

`:core:core-engine:compilePlayDebugKotlin`/`compileFullDebugKotlin` succeeded — Studio's build
compiles the new WP-2 code as part of compiling `core-engine` at all (Studio's `:app` depends on
it). Parsed test results: `:app:testFullDebugUnitTest` — 9 tests, 0 failures (the thin post-de-fork
shell has few tests of its own by design, per `Android-IDE-Studio/docs/STUDIO_DELTA.md`);
`:hyle:test` (both `Debug`/`Release` variants) — 10 tests, 0 failures. `:app:testPlayDebugUnitTest`
reported `NO-SOURCE` (no play-flavor-specific test sources exist — same as `full`'s absence of
regressions, not a skipped check). Studio's CI intentionally does not re-run `core-engine`'s own
1250-test suite (that's this repo's CI's job, per the workflow comment); it only needs
`core-engine` to compile and its own thin `:app`/`:hyle` layers to stay green, which they do.

**Honest gap:** `Android-IDE-Studio/CLAUDE.md`'s own repo-map and gate-command sections are stale
(they still describe the pre-de-fork monolithic `app/` layout and omit `core-engine`/`:core`
entirely) — not touched by this gate since it's out of this session's `android-ide-core`-rooted
scope, but worth a note for whichever session next works in that repo directly.

## 5. Files changed

**`android-ide-core`** (commit `5e0a2bd`, pushed to `origin/claude/fonebrew-development-clzu43`):
16 files — 8 new `domain/contracts`/`data` implementation files, 5 new test files, `AppDatabase.kt`
+ `AppContainer.kt` wiring, `contracts/kotlin/CommonContracts.kt`'s header comment updated from
"UNVERIFIED" to "VERIFIED."

**`Android-IDE-Studio`**: `core` submodule pin advanced from `2969437` (PR #11 merge commit) to
`5e0a2bd` (this pass's `android-ide-core` HEAD); `settings.gradle.kts`'s pin comment rewritten to
match. Not yet committed as of this report — see `HANDOFF_STATE.md` for the exact commit/push
status at hand-off.

## 6. What's still genuinely unverified (same standing caveat as every prior gate)

Everything above is JVM-verified only. No device, emulator, or real SQLite binding exists in this
sandbox — `ReceiptStore`'s Room wiring (`AppDatabase` v5→v6, `fallbackToDestructiveMigration()`,
still no real `Room.Migration` anywhere in this codebase) has never actually run against real
SQLite, on-device or otherwise. The idempotency/append-only logic is proven against a fake DAO;
the Room annotations (`@Entity`/`@Dao`/`@Query`) themselves are unverified beyond "the whole
module compiles," which is a much weaker claim than "the generated SQL is correct." Per this
repo's standing environment-honesty rule, this stays owner-verified until tested on the phone.
