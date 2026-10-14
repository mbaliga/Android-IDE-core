# S-FB2 — the FTS5 `SearchDatabase` and a Room-KMP database in ONE JVM process

**Verdict: PROVEN** on `CONTAINER-BUILD-ONLY` evidence (JVM x86_64, Linux): no native-library or version
clash at the current pins. Plan step: `docs/PORTING_PLAN.md` §6.1 **LX-2 / S-FB2**.

## The question

`:core-engine` keeps **two SQL toolchains** on purpose (`CLAUDE.md`): Room owns the append-only message tree;
SQLDelight over `androidx.sqlite:sqlite-bundled` owns the FTS5 search index in a separate file. On Android
they never share a process-wide native library with each other in a way that matters. On a desktop JVM both
reach the **same** `BundledSQLiteDriver` JNI library and the same `androidx.sqlite` jars. Do they open and
run side by side — a `MATCH` query and a Room query both succeeding in one test, in one process — without a
native-lib or version clash?

## Evidence

```bash
export LANG=C.UTF-8 LC_ALL=C.UTF-8
./gradlew -p desktop/spikes/S-FB2 --no-daemon jvmTest      # JDK only; no Android SDK, no submodules
```

Result on 2026-10-14 in the build container: **9 tests, 0 failures.**

The spike runs core's **real** code, not a lookalike: `Search.sq` and **`SearchDriverFactory.kt` are mapped
unchanged** from `core-engine/` into `build/mapped-core/` by `Sync` tasks, SQLDelight 2.1.0 generates the real
`SearchDatabase` from the real `.sq`, and the test opens the **production, file-backed** path
`SearchDriverFactory.create(context)` — including `PRAGMA foreign_keys = ON`, `ensureFacetColumns`,
`ensureLoopTaskTables` and the **raw-SQL external-content trigger workaround** (`installSyncTriggers`). The Room
half is core's real `MessageNodeEntity` + `MessageNodeDao` (mapped unchanged) in a `@Database` declared here.
There is no Gradle dependency on `:core-engine`; nothing under `core-engine/` is edited.

| Test | Shows |
|---|---|
| a MATCH query and a Room query both succeed in the same process | both file-backed databases open at once; FTS5 `MATCH` (word + quoted phrase, so `detail='full'` took effect) and a Room `getById` / recursive-CTE query all return correct rows |
| interleaved concurrent use | 40 coroutines on `Dispatchers.IO` write+read both databases at once; both end with exactly 40 rows and each token search finds only its own row |
| both engines report the same bundled SQLite | `sqlite_version()` = **3.50.1** from Room's connection **and** from the SQLDelight driver; `PRAGMA compile_options` contains `ENABLE_FTS5` |
| one native sqlite library serves both | `/proc/self/maps` shows **exactly one** sqlite JNI library mapped: `/tmp/androidx_sqliteJni<random>.tmp` |
| exactly one androidx sqlite stack on the classpath | `sqlite-bundled-jvm-2.7.0.jar` and `sqlite-jvm-2.7.0.jar`, once each (the repo's `sqliteBundled` pin) |
| raw-SQL sync triggers | insert / update / delete on `conv_projection` keep `conv_fts` consistent; `verifyAndRepairIfNeeded()` reports a healthy index |
| foreign keys on | deleting a projection cascades its `conv_facets` row (needs the `PRAGMA` that `SearchDriverFactory` issues) |
| close independence | closing Room leaves the search database usable, and Room reopens the same file afterwards |
| persistence | search rows survive closing and reopening the file-backed database |

## Findings

| # | Finding | Evidence |
|---|---|---|
| F1 | **No clash at the current pins.** Room-KMP 2.7.1 (declares `androidx.sqlite` 2.5.0) and `com.eygraber:sqldelight-androidx-driver:0.0.1` (declares `androidx.sqlite` 2.5.0-alpha12, SQLDelight runtime 2.0.2) both resolve to the repo's `androidx.sqlite` **2.7.0** (`2.5.0` and `2.5.0-alpha12` are overridden upward) and SQLDelight runtime **2.1.0** (`2.0.2` overridden upward), with `kotlin-stdlib` **2.1.20**; one native library is mapped. | tests + `dependencies --configuration jvmRuntimeClasspath` |
| F2 | **`com.eygraber:sqldelight-androidx-driver:0.0.1` ships a JVM variant** and works on a desktop JVM (core's own note about it being "wrapped for a plain Android module" does not stop it resolving here). | build |
| F3 | **SQLDelight's SQL dialect must be pinned explicitly off Android.** In `:core-engine` the SQLDelight *Android* plugin picks the dialect from `minSdk` — read from the plugin's bytecode (`gradle-plugin-2.1.0.jar`, `android/PackageNameKt.sqliteVersion`): `minSdk` ≥ 34 → `sqlite-3-38`, ≥ 31 → `sqlite-3-30`, ≥ 30 → `sqlite-3-25`, else `sqlite-3-18`; core's `minSdk 31` therefore means **`sqlite-3-30`** (inferred from that bytecode, not from a core build log). A KMP/JVM module has no `minSdk` and falls back to `sqlite-3-18`, which rejects `Search.sq`: `Search.sq:120:0 ',' expected, got 'ON'` on `ON CONFLICT(conv_id) DO UPDATE SET` (UPSERT needs SQLite 3.24+), logged as `Compiling with dialect app.cash.sqldelight.dialects.sqlite_3_18.SqliteDialect`. The spike adds `dialect("app.cash.sqldelight:sqlite-3-30-dialect:2.1.0")`; the extracted module needs the same line. | first failing build |
| F4 | **SQLDelight's `srcDirs` drops the producing task's dependency.** Pointing `srcDirs` at a `Sync` task's output left `generateCommonMainSearchDatabaseInterface` as `NO-SOURCE` (and `SearchDatabase` unresolved); an explicit `dependsOn(mapSq)` fixed it. Only relevant to this spike's mapping trick. | build log |
| F5 | **The real `SearchDriverFactory.kt` compiles unchanged on a JVM with a one-method stand-in for `android.content.Context`** (`src/jvmMain/kotlin/android/content/Context.kt`, labelled as a stub). Its only Android dependency is `create(context)` calling `context.getDatabasePath("search.db")` — i.e. LX-3(b)'s "paths interface in place of `Context`" is a one-method seam here. | build |
| F6 | **The bundled driver extracts its JNI library to `java.io.tmpdir`** at runtime (`/tmp/androidx_sqliteJni<random>.tmp`, mapped from there). A desktop package must allow that (a `noexec` `/tmp`, or a sandbox with a different tmp, is a packaging question for LX-7; not tested). | `/proc/self/maps` |
| F7 | The unmodified SQL needs **no** FTS5-specific toolchain beyond `sqlite-bundled`: the three SQLDelight-analyzer workarounds documented in `Search.sq` (`ORDER BY bm25(...)`, raw-SQL triggers, no `snippet()`) carry over verbatim to the JVM target. | tests |

## What this does not show

- The two databases here are **separate files** (as in core). Nothing about sharing one file, cross-database
  transactions, WAL contention under load, or large corpora.
- No Android build was involved or changed; this says nothing about Android behaviour.
- Search *quality* (`SearchIndexer`, `LexicalSearch`, the `Segmenter`'s `android.icu` use — see
  `desktop/PORTABILITY.md` if present) is out of scope; only the storage coexistence is.
- A Kotlin/Native or iOS target — not exercised.

## Layout

`settings.gradle.kts` / `build.gradle.kts` / `gradle.properties` (isolated build; pins mirror
`gradle/libs.versions.toml`, checked by `../check_pins.py`) · `src/commonMain` (`CoexistDb`) · `src/jvmMain`
(`CoexistFactory`, the `Context` stub) · `src/jvmTest/.../RoomFts5CoexistTest.kt`. `build/` is git-ignored.
