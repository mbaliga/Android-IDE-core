# desktop/spikes/ — LX-2: the four port-risk spikes

> Porting plan `docs/PORTING_PLAN.md` §6.1 **LX-2** (on the porting branch). Each spike is a **tiny, isolated
> Gradle build** (own `settings.gradle.kts`, own pins) that asks one narrow question and answers it with a test that
> actually runs. A spike's job is to surface **version / toolchain truth** — every constraint a spike hit is recorded as
> a finding in its README, none was papered over. Evidence class for everything here: **`CONTAINER-BUILD-ONLY`**
> (Linux x86_64 build container: JVM/native build + tests). Nothing here shows device behaviour, desktop look and feel,
> or that anyone can use a desktop app; there is no desktop app.

| Spike | Question | Verdict | Tests (this container, 2026-10-14) | Details |
|---|---|---|---|---|
| **S-FB1** | Room-KMP + `BundledSQLiteDriver` on a plain `jvm()` target **at the current pins** (Kotlin 2.1.0, KSP 2.1.0-1.0.29, Room 2.7.1) | **PROVEN** — no pin has to move; core's *real* 17-entity / 17-DAO schema compiles and runs unchanged | 13 / 13 | [S-FB1/README.md](S-FB1/README.md) |
| **S-FB2** | FTS5 `SearchDatabase` (SQLDelight + bundled SQLite) and Room-KMP in **one JVM process** | **PROVEN** — one SQLite 3.50.1 native lib serves both; one `androidx.sqlite` 2.7.0 stack; core's real `SearchDriverFactory` runs unchanged | 9 / 9 | [S-FB2/README.md](S-FB2/README.md) |
| **S-FB3** | `System.load` a Linux x86_64 CPU build of the llama JNI shim (+ the plan's "unknown": llama **and** sd ggml copies in one JVM) | **PROVEN** — with one required patch recorded as a finding (`android/log.h`, LX-7) | part A 8 / 8 static + 8 / 8 shared; part B 3 / 3 | [S-FB3/README.md](S-FB3/README.md) |
| **S-FB4** | Compose Desktop draws one room; which Kotlin/CMP pairing works against the repo pins | **PROVEN at Kotlin 2.1.0 + CMP 1.8.2**; **CMP 1.7.3 FAILS** the pinned markdown renderer (`NoSuchMethodError`) | 8 / 8 at the default pairing; 4 pairings recorded | [S-FB4/README.md](S-FB4/README.md) |

`PROVEN` means the test that asserts the question passes on the stated evidence — not that the capability is
shippable. Each README ends with **"What this does not show"**; read it before citing a verdict.

## What the four spikes say together (the P12 cost input)

- **No do-not-regress pin has to move** for the storage layer: Room-KMP 2.7.1 + KSP 2.1.0-1.0.29 + Kotlin 2.1.0 +
  SQLDelight 2.1.0 + `sqlite-bundled` 2.7.0 coexist on a desktop JVM (S-FB1, S-FB2).
- **Compose is the pin that bites.** Compose Multiplatform **1.7.3** (Compose 1.7 line) cannot run the markdown renderer
  core pins (0.35.0): `NoSuchMethodError … BasicText-CL7eQgs(… TextAutoSize …)`, the *same* failure `CLAUDE.md` records for
  an old Compose BoM on Android. **CMP 1.8.2** (the Compose line of the repo's BoM 2025.05.01) works with the repo's
  **Kotlin 2.1.0** — so the desktop head does not, on this evidence, force the OQ-17 staged bump (2.1.20) or the OQ-38
  iOS pin (2.2.21); those pairings also passed (S-FB4) but were not required. Not an owner decision — a measurement.
- Things the extraction (LX-3/LX-4) and packaging (LX-7) will have to do, each with its evidence in a README:
  rewrite `Converters.kt` off `org.json` for `commonMain` (S-FB1 F5); pin an explicit **SQLDelight dialect** (S-FB2 F3);
  replace the single `Context` use in `SearchDriverFactory` with a paths seam (S-FB2 F5); guard `android/log.h` in the
  two JNI sources (S-FB3 F1); decide the CPU baseline (ggml's x86 defaults need **AVX2**, S-FB3 F3); allow the bundled
  driver's `java.io.tmpdir` extraction (S-FB2 F6).

## How to run

Prereqs: a JDK 17+ (Gradle auto-detects/provisions the 17 toolchain), `LANG=C.UTF-8`. **No Android SDK/NDK, no private
submodule.** S-FB3 also needs `cmake`, a C++17 compiler, `patch`, and the public `llama.cpp` (and, for part B,
`stable-diffusion.cpp`) submodules; S-FB4's window smoke needs `xvfb-run` + a font set.

```bash
export LANG=C.UTF-8 LC_ALL=C.UTF-8
./gradlew -p desktop/spikes/S-FB1 --no-daemon jvmTest
./gradlew -p desktop/spikes/S-FB2 --no-daemon jvmTest
git submodule update --init --depth 1 -- core-engine/src/main/cpp/llama.cpp
git submodule update --init --depth 1 --recursive -- sdengine/src/main/cpp/stable-diffusion.cpp   # part B only
./gradlew -p desktop/spikes/S-FB3 --no-daemon test testTwoLibs
./gradlew -p desktop/spikes/S-FB4 --no-daemon test                                   # add -Pfb4.requireXvfb=true to fail (not skip) without xvfb-run
python3 -I desktop/spikes/check_pins.py                                               # spike pins still equal gradle/libs.versions.toml
```

`./gradlew` is the **repo-root wrapper**; `-p <spike>` makes the spike's own settings file the build root, so the root
build is neither loaded nor modified. Run Gradle **unpiped** and check the exit code (`./gradlew … > log 2>&1; echo $?`).
`.github/workflows/desktop-linux.yml` runs the same commands in CI (see its header for what a green run does and does not mean).

## Rules these spikes follow

- **Additive only (R1/R2).** Everything lives under `desktop/spikes/` plus the one new workflow
  `.github/workflows/desktop-linux.yml`. No existing file is edited — including `core-engine/src/main/cpp/*.cpp`, which is
  *read* (mapped by `Sync` tasks or `add_subdirectory`) but never written; real core sources are mapped, never copied-and-edited.
  Running a spike leaves `git status` clean (the workflow asserts this).
- **Pins mirror the repo's** (`check_pins.py` fails on drift). No pin was changed; `PROPOSED-1..4` and the OQ-17 "Also"
  approvals (converting `:hyle` / `:cell-shell` / `:crash-recovery` to KMP) were **not** acted on.
- **No application identifier (R11).** No bundle id / reverse-DNS app id is set anywhere (`packageName = "spike-fb4"` is a
  spike image name). The Kotlin namespace `dev.fonebrew.…` is the code's existing one; JNI symbol names require it.
- **No telemetry (I-1)**, no Qt (I-11), no `continue-on-error` in the workflow.
- **Environment notes found the hard way:** (1) several Gradle builds sharing the machine's single Kotlin compile daemon
  ran it out of heap (`OutOfMemoryError: Java heap space` in the daemon log; a spike build then sat in `compileKotlinJvm`
  for 10+ minutes) — every spike therefore sets `kotlin.compiler.execution.strategy=in-process`; (2) stable-diffusion.cpp's
  `vocab.cpp` needs ~5 GB to compile (S-FB3 F7).

## Not done here (and why)

- **`HANDOFF_STATE.md` is not edited.** LX-2 says each spike is "recorded pass, fail or unknown in `HANDOFF_STATE.md`", but the lane
  brief limits this change to new paths. The record to paste there:
  `S-FB1 PROVEN · S-FB2 PROVEN · S-FB3 PROVEN (patch finding: android/log.h) · S-FB4 PROVEN at Kotlin 2.1.0 + CMP 1.8.2 (CMP 1.7.3 FAILS the markdown pin)`
  — all `CONTAINER-BUILD-ONLY`; the hosted-CI results are **unknown** until `desktop-linux.yml` has run.
- **Room 2.8, `androidTarget` + `jvm()` together, Kotlin/Native/iOS, GPU backends, real inference, packaging/signing,
  Windows/macOS** — out of scope; named in each README's "does not show".
- **Owner-gated items stay untouched:** OQ-17 (toolchain stage, Room 2.8, KMP conversion of the shared libs), OQ-22 (key
  custody), OQ-36 (Qt / Ubuntu Touch shape), PROPOSED-1..4. Nothing here pre-empts them; S-FB4's pairing table is *input* to OQ-17, not an answer.
