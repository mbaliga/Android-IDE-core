# Handoff — extract **Hyle** into its own repository

> Goal: move the Hyle design system out of the app monorepo into a standalone repo
> (`hyle`), published as a consumable artifact, so it can version, test, and render on its
> own cadence — and eventually be reused beyond Aarso/Workbench. The `:hyle` module was
> *built to graduate* (see its `build.gradle.kts` header). This is the migration plan.

## 1. What "Hyle" is today (current state)

Three things wear the name; the extraction must keep them straight.

| Piece | Where | What it is | Goes to new repo? |
|---|---|---|---|
| `:hyle` module (`dev.aarso.hyle`) | `hyle/` | **Render-side tokens + contract** — `Finish`, `Pulse`, `RadiantHues`. Pure data, JVM-tested (`FinishTest`). Android library, no Compose. | **Yes — core** |
| `:hyle-probe` app (`dev.aarso.hyleprobe`) | `hyle-probe/` | Standalone **render harness** APK: `FerrofluidProbe`, `AeonAtomsProbe`, `LensProbe`, `RadiantGlowProbe`, `GlassSandProbe`. The device-verification surface for the look. | **Yes — the gallery** |
| Compose components | app `ui/aeon/Aeon.kt`, `ui/theme/*`, `ui/wire/*` | The actual `Hyle*` widgets (`HyleButton`, `HyleChip`, `HyleField`, `HyleDropdownField`, `HyleTitle`, `HyleNavChip`), `AeonColors`/`AarsoTheme`/`ThemePicker`, the wire primitives. | **Phased** — see §4 |
| The **semantic** | app `domain/material` + `material-language.md` | "local vs from-elsewhere" meaning (a watched object reads differently). App-specific policy, **not** render. | **No — stays in app** |

Consumers/wiring today: `settings.gradle.kts` `include(":hyle")`, `include(":hyle-probe")`;
`app/build.gradle.kts` `implementation(project(":hyle"))`. `./gradlew :hyle:test` is in the
green-gate. Toolchain: AGP 8.9.1, Kotlin 2.1.0, Compose BoM 2025.01.00, JDK 17, `minSdk 31`,
`compileSdk 36`.

## 2. Target shape of the new repo

```
hyle/                         # new standalone repo
  settings.gradle.kts         # foojay JDK resolver (copy from app), include(":hyle", ":probe")
  gradle/libs.versions.toml   # the versions Hyle needs (subset of the app's catalog)
  hyle/                       # the library (was :hyle) — publishable AAR
    build.gradle.kts          # + maven-publish
  probe/                      # the render harness (was :hyle-probe)
  docs/material-language.md   # the render half of the language (copy; the semantic stays in app)
```

Group/artifact: `dev.aarso:hyle:<version>` to start (zero churn). The neutral rename
(`dev.aarso.hyle` → e.g. `dev.hyle` / `io.hyle`) is **deferred** to align with the app's own
Sprint R package rename — do it once, later, not now.

## 3. Consumption model — pick one (recommendation: B then A)

- **A. Maven publication (end state).** New repo publishes `dev.aarso:hyle` to **GitHub Packages**
  (private, same account) or Maven Local for dev. App swaps `implementation(project(":hyle"))` →
  `implementation("dev.aarso:hyle:<v>")` + the repo in `dependencyResolutionManagement`. Clean
  versioning; the app pins a version. Best once the API has stabilised.
- **B. Composite build (bridge, do this first).** App keeps building Hyle from source during the
  transition via `includeBuild("../hyle")` (Gradle composite build) **or** a git **submodule** at
  `hyle/`. Zero artifact plumbing, full source debuggability, history preserved. Flip to A when
  the API stops moving.
- **C. git subtree** (no submodule pointer) — viable but A/B are cleaner for a Gradle library.

## 4. The Compose components question (the real decision)

`:hyle` today is pure data; the `Hyle*` **Compose** widgets still live in the app (`ui/aeon`,
`ui/wire`) — they were always meant to land in Hyle once they render well on device (the module
header says so). Two-phase:

- **Phase 1 (now):** extract `:hyle` (tokens/contract) + `:hyle-probe` only. App is unchanged
  except the dependency source (project → includeBuild/artifact). Low risk, fully testable.
- **Phase 2 (after device-verify):** move the render layer into the new repo as a
  `hyle-compose` artifact: `ui/aeon/Aeon.kt` (the `Hyle*` atoms), `ui/theme/AeonColors`,
  `AccentRamp`, `ThemePicker`, `ui/wire/*`. App then depends on `dev.aarso:hyle-compose`.
  **Keep in the app:** anything that knows about *Aarso domain* (watched-object semantic,
  `LocalHyleColors` wiring to `SessionStore`, screen-specific composition). Hyle ships the
  *vocabulary*; the app composes sentences.

## 5. Migration steps (history-preserving)

1. **Carve with history:** `git subtree split -P hyle -b hyle-only` and `… -P hyle-probe -b probe-only`
   (or `git filter-repo --path hyle --path hyle-probe`) → push those branches into the new `hyle`
   repo as `hyle/` and `probe/`. Preserves authorship/blame.
2. **New repo scaffolding:** copy `gradle/`, `gradlew`, `settings.gradle.kts` (foojay resolver +
   the two includes), and a trimmed `libs.versions.toml` (only what Hyle/probe reference).
3. **Add `maven-publish`** to `hyle/build.gradle.kts` (groupId `dev.aarso`, artifactId `hyle`,
   version from a `gradle.properties`). Wire a `publishToMavenLocal` smoke test.
4. **CI in the new repo:** `./gradlew :hyle:test` (JVM) on every push; `:probe:assembleDebug` to
   prove the harness still builds; optionally publish on tag.
5. **Point the app at it:** Phase 1 → `includeBuild("../hyle")` (or submodule) in the app's
   `settings.gradle.kts`, drop `include(":hyle")`/`include(":hyle-probe")`. Keep
   `implementation(project(":hyle"))` working via the composite substitution, **or** switch to the
   published coordinate.
6. **Green-gate update:** the app's gate (`:app:testFullDebugUnitTest :app:testPlayDebugUnitTest`)
   stays; `:hyle:test` now runs in the Hyle repo's CI. Add a note in app `CLAUDE.md` that Hyle is
   external.
7. **Probe distribution:** the probe APK can ride its own `apk-dist`-style branch in the Hyle
   repo, mirroring the app's APK delivery, for on-device look checks.

## 6. Risks & guards
- **Version skew:** Hyle and app must agree on Compose BoM + Kotlin. Pin the same `libs.versions`
  values; bump in lockstep until A is stable. A mismatched Compose compiler is the classic break.
- **No app regression:** after the swap, `:app:assembleFullDebug` + both unit-test variants must
  stay green and the APK byte-diff should be render-neutral (tokens unchanged).
- **Don't rename packages during the move** — one disruptive change at a time. Rename is Sprint R.
- **The semantic stays home:** resist pulling `domain/material` (local-vs-elsewhere) into Hyle —
  that's Aarso policy, not a render token, and it would couple the design system to the app.
- **Probe is the proof:** Hyle has no automated render test (AGSL/Compose are device-verified).
  The probe app *is* the acceptance test — keep it building and run it on device per change.

## 7. Definition of done
- [ ] `hyle` repo exists with `hyle/` (lib) + `probe/` (harness), history preserved.
- [ ] `:hyle:test` green in the new repo's CI; `:probe:assembleDebug` builds.
- [ ] `hyle` publishes `dev.aarso:hyle:<v>` (Maven Local at minimum; GitHub Packages ideally).
- [ ] App consumes it (composite build first), green on both flavors, render-neutral.
- [ ] App `CLAUDE.md` + `docs/status.md` note Hyle as an external dependency.
- [ ] (Phase 2, later) `hyle-compose` extracted; `ui/aeon`/`ui/wire` Hyle atoms moved out.
