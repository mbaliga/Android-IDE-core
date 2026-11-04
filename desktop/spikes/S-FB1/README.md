# S-FB1 — Room-KMP + `BundledSQLiteDriver` on a plain `jvm()` target, at the current pins

**Verdict: PROVEN** on `CONTAINER-BUILD-ONLY` evidence (JVM x86_64, Linux). Scope limits are in
"What this does not show". Plan step: `docs/PORTING_PLAN.md` §6.1 **LX-2 / S-FB1**.

## The question

Can a Room database — entity + DAO + type converter + foreign keys + a recursive CTE — be built on a
plain desktop JVM through Room's KMP API and `androidx.sqlite:sqlite-bundled`'s `BundledSQLiteDriver`,
**without moving any of the repo's do-not-regress pins** (Kotlin 2.1.0, KSP 2.1.0-1.0.29, Room 2.7.1)?
If not, the finding is the minimum pins that do work (the plan's P12 cost check). It does work, so no
higher pin is needed and none was searched for (the *lowest* working pins were not bisected).

## Evidence

```bash
# from the repo root; needs a JDK 17+ and nothing else (no Android SDK, no submodules)
export LANG=C.UTF-8 LC_ALL=C.UTF-8
./gradlew -p desktop/spikes/S-FB1 --no-daemon jvmTest
./gradlew -p desktop/spikes/S-FB1 --no-daemon jvmTest -PsqliteBundled=2.5.1   # the cross-check below
```

Result on 2026-10-14 in the build container: **13 tests, 0 failures** (both commands).

| Part | What runs | Tests |
|---|---|---|
| A | a trimmed copy of core's shape (`@Database`, `@TypeConverters`, `@ConstructedBy`): core's real `MessageNodeEntity` + `MessageNodeDao` (copied, package renamed only) and a converter-probe entity, on a **real database file** via the context-free builder | 10 |
| B | core's **real** `AppDatabase` shape: all **17 entities, 17 DAOs and `Converters.kt`**, mapped **byte-for-byte** from `core-engine/` into `build/mapped-core/` by `Sync` tasks (no Gradle dependency on `:core-engine`, nothing under `core-engine/` is edited) | 3 |

What the 13 tests assert (each opens a real file through the builder; no `Context`, no Robolectric):

- insert → `getById` roundtrip incl. non-ASCII / emoji; data survives close and reopen of the same file
- append-only: a duplicate-id insert throws `androidx.sqlite.SQLiteException` and leaves the first row intact
- the recursive-CTE `pathToRoot` returns leaf-first ancestry; a `Flow` query emits in `createdAt` order
- foreign keys are **on**: an orphan child insert is rejected and `ON DELETE CASCADE` removes a subtree
- a `@TypeConverter` column roundtrips, including separator-like characters
- `exportSchema = true` under the Room Gradle plugin writes `build/room-schemas/.../SpikeDbExported/1.json`
  containing both tables, and the `exportSchema = false` database (what core declares today) writes none
- the engine is the bundled one: `sqlite_version()` = **3.50.1**
- Part B: all **17 tables** are created with exactly the names of core's `@Entity(tableName=…)` values;
  a `TaskEntity` roundtrips through the real converters (enum + `List<String>`), update, `Flow`, delete;
  a `ByteArray` BLOB column and the embeddings DAO work

Resolved versions (from `./gradlew dependencies --configuration jvmRuntimeClasspath`):
`androidx.room:room-runtime-jvm:2.7.1`, `androidx.sqlite:sqlite-bundled-jvm:2.7.0`,
`androidx.sqlite:sqlite-jvm:2.7.0` (Room 2.7.1 declares `2.5.0`; resolved **up** to the repo's `sqliteBundled`
pin), `kotlinx-coroutines-core-jvm:1.10.1`, `kotlin-stdlib:2.1.20` (see F2). With `-PsqliteBundled=2.5.1`
the bundled/sqlite artifacts resolve to `2.5.1`, `kotlin-stdlib` to `2.1.10`, and the same 13 tests pass.

## Findings (version / toolchain truth the port will meet)

| # | Finding | Evidence |
|---|---|---|
| F1 | **Room-KMP works on `jvm()` at Kotlin 2.1.0 / KSP 2.1.0-1.0.29 / Room 2.7.1.** KSP's `kspJvm` generated the `*_Impl` classes and the `actual object …Constructor`s for all three `@Database` classes without error; Room 2.7.1's klib/jar metadata (built with Kotlin 2.1.x) is readable by the 2.1.0 compiler. No pin has to move. | this spike |
| F2 | The JVM runtime classpath resolves **`kotlin-stdlib` 2.1.20**, one patch above the 2.1.0 compiler, because `androidx.sqlite:sqlite-bundled-jvm:2.7.0` is itself built on 2.1.20. Harmless here; it matters if a consumer insists on an exact stdlib. | `dependencies` output |
| F3 | Compiler warning, not an error, on every Room-KMP `expect object … : RoomDatabaseConstructor` and its generated `actual`: `'expect'/'actual' classes (including interfaces, objects, annotations, enums, and 'actual' typealiases) are in Beta. You can use -Xexpect-actual-classes flag to suppress this warning. Also see: https://youtrack.jetbrains.com/issue/KT-61573`. The spike passes `-Xexpect-actual-classes`; the module that LX-4 creates will need the same. | first build log |
| F4 | **The builder spelling differs from core's.** KMP: `Room.databaseBuilder<Db>(name = path).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).fallbackToDestructiveMigration(dropAllTables = true).build()` (a driver is mandatory off-Android). Core's `AppContainer` calls the Android form `Room.databaseBuilder(context, …).fallbackToDestructiveMigration().build()`. Whether the no-arg `fallbackToDestructiveMigration()` also resolves in common code was **not tested**. | `SpikeDbFactory.kt` |
| F5 | **`Converters.kt` cannot live in `commonMain`.** It imports `org.json` (Android platform API, absent on a JVM classpath — the spike adds `org.json:json:20231013`, the version core's tests use, and that artifact's licence has **not** been checked against `config/allowed-licenses.json`) and `java.nio.ByteBuffer`. `grep` over `core-engine/src/main`: **61** files `import org.json`, **22** of them under `data/`. Part A therefore ships a dependency-free converter; Part B maps the real one into `jvmMain`. | static `grep` + Part B |
| F6 | **Part B's DB class had to be declared in `jvmMain` with `factory = { FullAppDatabase_Impl() }`**, not `@ConstructedBy` in `commonMain`, precisely because of F5 (`@TypeConverters(Converters::class)` must be resolvable where the `@Database` is). Both Room-KMP construction styles work. | `FullAppDatabase.kt` |
| F7 | **The real schema compiles and runs under Room-KMP.** All 17 entities and 17 DAOs (66 DAO functions: **52 `suspend`, 14 `Flow`-returning, 0 other** by a static scan) pass KSP+Room unchanged, and `@Upsert/@Insert/@Update/@Delete`, `ForeignKey`, `Index`, `ByteArray`, default-valued constructor params and the `WITH RECURSIVE` query all behave. The Room-KMP restriction "non-Android DAOs must be `suspend`/`Flow`" is therefore **not** a blocker for this schema. | Part B + scan |
| F8 | **`AppDatabase` is at `version = 10`, not v8** as the plan's text says (v9 added `thread_markers`/`delegation_events`, v10 dropped `Watchlist`). Part B uses 10. There are no `Migration` objects (core rides `fallbackToDestructiveMigration()`), so migration behaviour is untested here by construction. | `AppDatabase.kt` |
| F9 | Schema export works with the **Room Gradle plugin 2.7.1** applied next to KMP + KSP at these pins (`room { schemaDirectory(…) }`). Core's `exportSchema = false` stays valid under KMP and writes nothing. | schema-export test |
| F10 | `commonMain` cleanliness is **not proven by the compiler**: with a single `jvm()` target Gradle registers no metadata compilation (`Task 'compileCommonMainKotlinMetadata' not found in root project 'S-FB1'`), so the entity/DAO/enum files — mapped into `commonMain` — were compiled only as part of the JVM compilation. A static `grep` shows their only imports are `androidx.room.*`, `kotlinx.coroutines.flow.Flow` and 9 dependency-free domain enums; a second target (e.g. `androidTarget`) would turn the real check on. | build log |

## What this does not show

- **`androidTarget` + `jvm()` together** (the LX-4 module shape, with AGP 8.9.1) — not built. The Android gate was not touched.
- **Room 2.8.x** — OQ-17-gated, not tried.
- **Any non-JVM target** (Kotlin/Native, iOS) — Room-KMP supports them; nothing here exercises one.
- **Migrations**, concurrency stress, WAL behaviour, large data, or performance.
- That the *app* works on a desktop — this is one database layer on one JVM in a test.

## Layout

`settings.gradle.kts` / `build.gradle.kts` / `gradle.properties` (own isolated build; pins mirror
`gradle/libs.versions.toml` and are checked by `../check_pins.py`) · `src/commonMain` (Part A entity/DAO/db) ·
`src/jvmMain` (builders; Part B `full/FullAppDatabase.kt`) · `src/jvmTest` (the 13 tests). `build/` is git-ignored.
`gradle.properties` selects `kotlin.compiler.execution.strategy=in-process`: the shared Kotlin daemon ran out
of heap when several builds on the same machine joined it (see `../README.md`).
