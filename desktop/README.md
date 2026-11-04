# desktop/ — the JVM host lane over the Android core's pure Kotlin

> **Status: LX-1 only (porting plan `docs/PORTING_PLAN.md` §6.1).** This directory holds a *build and
> test lane*, not a desktop app. There is no Compose Desktop head, no Room-KMP, no native engine, no
> packaging, no secret custody, no Linux (or any desktop) release here, and nothing in it has been run
> on a desktop machine anyone uses. Evidence class for everything below: **`CONTAINER-BUILD-ONLY`**
> locally and **`CI (hosted VM)`** once `.github/workflows/desktop-host-core.yml` has run — JVM x86_64
> compile and unit tests, nothing more.

## What it does

`desktop/` is its **own Gradle build** (its own `settings.gradle.kts`, wrapper and version catalog). The
repo-root build does not include it and is unchanged. It needs **a JDK and nothing else** — no Android
Gradle plugin, no Android SDK, no `ANDROID_HOME`, no submodules, no network beyond Maven Central.

`:core-host` compiles these **in place, unchanged** (nothing is copied or moved):

| mapped | from |
|---|---|
| pure-Kotlin domain | `core-engine/src/main/java/dev/fonebrew/domain/` |
| contract corpus | `contracts/kotlin/` (the same root `:core-engine` adds as an extra srcDir) |
| domain tests | `core-engine/src/test/java/dev/fonebrew/domain/` |

…and runs the tests on the host JDK. The mapping, and every exclusion with the reason it exists, is in
[`core-host/mapping.txt`](core-host/mapping.txt) — the single source read by both Gradle and the scripts.

```bash
cd desktop
./gradlew --no-daemon :core-host:test                          # needs JDK 17+; auto-provisions 17 if absent
python3 -I scripts/reconcile_tests.py --results core-host/build/test-results/test
```

Set a UTF-8 locale first (`export LANG=C.UTF-8 LC_ALL=C.UTF-8`); several domain tests have non-ASCII
backticked names and the Kotlin compiler cannot write their class files under a POSIX locale.

## Exclusions (what the lane does *not* compile, and why)

The plan names two `domain/ -> data/` back-edges. Building the lane found a third thing the plan does
not name, plus one test-only edge. All are enforced by `:core-host:verifyMapping`, which fails if an
excluded file vanished or **no longer contains its witness back-edge** — an exclusion cannot silently
outlive its reason (e.g. when LX-3 inverts the dependency, the build tells you to delete the line).

| excluded | edge | named by the plan? |
|---|---|---|
| `domain/authority/AuditedAuthorityEngine.kt` (+ its test) | → `data/ReceiptStore` | **yes** (§2, §6.1) |
| `domain/execution/CiActionsExecutionProvider.kt` (+ its test) | → `data/GitTransport` | **yes** (§2, §6.1) |
| `domain/mode/GestureModeDefaults.kt` (+ its test) | → shared-libraries `dev.aarso.interactionmode.InteractionMode` | **no — post-plan drift; finding for LX-3** |
| `LoopTemplateCatalogTest.kt` (test only; its subject *is* mapped) | → Context-bound `data/LoopTemplateAssets` | no |

## One thing the lane adds: a labelled compile-time stand-in

The plan says the domain tree has "no `android.*`/`androidx.*` import (grep)". True of `import` lines;
`domain/search/Segmenter.kt` references `android.icu.text.BreakIterator` **fully qualified** (five code
lines), so an import-grep cannot see it and it cannot compile on a plain JVM.
`core-host/src/hostShim/kotlin/android/icu/text/BreakIterator.kt` is a stand-in whose factory **throws**
— exactly what `android.jar`'s stub does in the Android unit-test lane — and `Segmenter.defaultSource`
already probes-and-falls-back to `java.text`. So the mapped search tests take the same branch here as in
the Android gate. It has **no behaviour by design**; it is not an ICU, and it is listed here so nobody
mistakes the lane for proof that ICU segmentation works off Android. IOS-1 should replace the pattern
with an `expect`/`actual` `BoundarySource`; this file is then deleted.

## Count reconciliation (the plan's "reconciled against the 1549 baseline")

`1549` is `docs/WP11_GATE_REPORT.md`'s **whole-module** figure (`:core-engine:testFullDebugUnitTest`,
every test under `core-engine/src/test`, one flavor) from 2026-08-07, quoted by the plan on 2026-10-06
without a re-run. It cannot be this lane's pass bar: the lane maps only `domain/` tests, of today's tree.
The real, checkable invariant is **the lane executes exactly the tests that exist in the mapped test
sources.** `scripts/reconcile_tests.py` derives that number from the sources (it counts `@Test` after
masking comments, strings and backticked names), reads the JUnit XML Gradle wrote, and fails on any
difference. It also prints the whole-module source-derived figure so the delta from 1549 is explained
from data rather than asserted. Re-run it for current numbers — they are deliberately not frozen here.

## Files

| path | purpose |
|---|---|
| `settings.gradle.kts`, `gradle/`, `gradlew*` | the lane's own build (wrapper identical to the root's; same Gradle 8.14.3) |
| `gradle/libs.versions.toml` | pins mirroring the root's Kotlin 2.1.0 / coroutines / JUnit / org.json; `scripts/check_pins.py` proves parity |
| `core-host/build.gradle.kts`, `core-host/mapping.txt` | srcDir mapping + exclusion filter + `verifyMapping` |
| `core-host/src/hostShim/` | the one stand-in above |
| `scripts/reconcile_tests.py` | test-count reconciliation |
| `scripts/portability_inventory.py` → `PORTABILITY.md` | **IOS-1 groundwork** (below) |
| `scripts/check_root_unchanged.sh` | the plan's root-unchanged check (history + tree) |
| `scripts/check_pins.py`, `scripts/lanelib.py` | pin parity; shared parsing + Kotlin comment/string masking |

## IOS-1 groundwork (clearly *not* IOS-1)

[`PORTABILITY.md`](PORTABILITY.md) is a generated static inventory of JVM-only API usage in the mapped
sources — `org.json`, `java.time`, `java.text`, `WatchService`, `ProcessBuilder`, `Base64` — with
per-package counts, a per-API file list, the Android-runtime references an `import`-grep misses, and a
mechanical list of every other `java.*`/`javax.*` import. It is the work list IOS-1's `commonMain`
migration will consume. **It migrates nothing**, links no `kotlinx-*` library, and IOS-1 is gated
(OQ-2, OQ-38, "P-LX seam green") and not started. Regenerate with
`python3 -I desktop/scripts/portability_inventory.py --write`; `--check` fails if it is stale (CI only
warns, so an unrelated domain edit never reds this lane).

## Licence note — read before relying on `org.json`

On Android, `org.json` is a platform class. On a plain JVM it must be declared, so this lane declares
`org.json:json:20231013` as a **runtime** (`api`) dependency — the same version the Android unit tests
already use, but there it is *test-only*, so `:core-engine:checkLicense` has never scanned it. This
build has **no license gate yet**. The artifact's POM declares `Public Domain` (the jar itself ships no
licence text, only the POM, which points at the upstream `LICENSE`), and `Public Domain` is an entry on
`config/allowed-licenses.json`. Whether that POM string is an adequate statement of the upstream
licence for a *shipped* desktop dependency is a legal judgement this directory does not make and the
owner has not made. Treat `org.json` as **unresolved for any distributable desktop build** (the plan's
Q14 / `FB-RAT-DIST-003` territory). The other runtime dependencies are the Kotlin stdlib and
`kotlinx-coroutines-core` (Apache-2.0); test scope only (never shipped): JUnit 4 (EPL-1.0) and its
`hamcrest-core`, and `kotlinx-coroutines-test`.

## What stays gated

This lane executes only the plan's additive, no-device steps (LX-1) on the owner's 2026-10-13 build-out
instruction. The plan's formal LX entry gate — v0.13 device verification and OQ-7a (does a desktop
Fonebrew square with the device-independence brief) — is owner territory and **is not claimed
satisfied**. LX-3 onward edit Android-side files and stay gated; LX-2 spikes live elsewhere under
`desktop/spikes/` and are not part of this lane.
