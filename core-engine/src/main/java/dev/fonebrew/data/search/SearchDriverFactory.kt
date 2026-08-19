package dev.fonebrew.data.search

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver

/**
 * Opens [SearchDatabase] — the FTS5 search index (Doc `FONEBREW_SEARCH_SPEC.md` D1) — over
 * `androidx.sqlite:sqlite-bundled` (SQLite 3.50.1, `SQLITE_ENABLE_FTS5`; AOSP's system SQLite
 * has no FTS5 at all, which is why this is bundled rather than the platform default). This is a
 * **separate SQLite file from Room's `AppDatabase`** (the message tree) — two SQL toolchains
 * coexist in `:core-engine` on purpose (Room has no `@Fts5`, only `@Fts3`/`@Fts4`).
 *
 * [createInMemory] is what makes this JVM-testable without a device or Robolectric:
 * `sqlite-bundled` ships native binaries for the JVM host platform too (that's the point of the
 * KMP artifact — "byte-identical SQLite on Android and iOS" extends to the JVM host used by
 * this repo's unit tests), so `BundledSQLiteDriver()` opens a real SQLite connection here, not a
 * stub. `SearchDatabaseFactoryTest` runs an actual `MATCH`/`bm25()` query against it.
 */
object SearchDriverFactory {

    /** Production: a per-app-data-dir file-backed database. */
    fun create(context: Context): SearchDatabaseHandle {
        val path = context.getDatabasePath("search.db").absolutePath
        return open(AndroidxSqliteDatabaseType.File(path))
    }

    /** JVM-testable / ephemeral: in-memory database, same driver artifact as production. */
    fun createInMemory(): SearchDatabaseHandle = open(AndroidxSqliteDatabaseType.Memory)

    private fun open(databaseType: AndroidxSqliteDatabaseType): SearchDatabaseHandle {
        val driver = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = databaseType,
            schema = SearchDatabase.Schema,
        )
        // SQLite disables foreign-key enforcement by default, per connection — it is NOT a
        // property of the database file. Without this, conv_facets's `ON DELETE CASCADE`
        // (Search.sq) silently never fires, and deleteProjection leaves an orphaned facets row
        // behind (verified empirically by SearchIndexerTest's "remove deletes..." case failing
        // without this line).
        driver.execute(identifier = null, sql = "PRAGMA foreign_keys = ON", parameters = 0, binders = null)
        ensureFacetColumns(driver)
        installSyncTriggers(driver)
        return SearchDatabaseHandle(SearchDatabase(driver), driver)
    }

    /** `conv_facets` columns added by THREAD_TOPOLOGY_PLAN.md WP6, name -> the DDL fragment
     *  after the name in an `ALTER TABLE ... ADD COLUMN` statement. Declared once so
     *  [ensureFacetColumns]'s "what to check for" and "what to add" can't drift apart. */
    private val FACET_COLUMNS_ADDED_WP6 = linkedMapOf(
        "lineage_parent" to "TEXT",
        "lineage_kind" to "TEXT",
        "chapter_count" to "INTEGER NOT NULL DEFAULT 0",
        "compaction_count" to "INTEGER NOT NULL DEFAULT 0",
    )

    /**
     * THREAD_TOPOLOGY_PLAN.md WP6's upgrade path: adds `conv_facets`'
     * `lineage_parent`/`lineage_kind`/`chapter_count`/`compaction_count` columns to an
     * **already-existing** database file, idempotently.
     *
     * Why this is needed even though `Search.sq`'s `CREATE TABLE conv_facets` already declares
     * these columns: [SearchDatabase.Schema] only runs that `CREATE TABLE` the first time a
     * database file is opened. There is no `.sqm`-versioned migration wired up for this schema —
     * this repo keeps every FTS5-virtual-table-adjacent statement as raw SQL specifically because
     * SQLDelight 2.1.0's analyzer `ClassCastException`s on it (see [installSyncTriggers]'s KDoc),
     * and a declared migration file would hit the exact same limitation for a schema this close
     * to `conv_fts`. Without this, a database file created by a build that predates WP6 would
     * open with the four columns simply missing, and the first `upsertFacets`/`selectFacets` call
     * would throw "no such column" at runtime.
     *
     * `PRAGMA table_info` first, so a column already present — every fresh [createInMemory] test
     * database, or a device database this has already run against once — is never re-added:
     * SQLite's `ALTER TABLE ADD COLUMN` has no `IF NOT EXISTS` form and throws `duplicate column
     * name` on a repeat, unlike the `CREATE TRIGGER IF NOT EXISTS` idiom [installSyncTriggers] gets
     * to use.
     */
    internal fun ensureFacetColumns(driver: SqlDriver) {
        val existing = HashSet<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info(conv_facets)",
            mapper = { cursor ->
                while (cursor.next().value) {
                    cursor.getString(1)?.let { existing += it } // column index 1 = "name"
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
            binders = null,
        )
        FACET_COLUMNS_ADDED_WP6.forEach { (name, ddl) ->
            if (name !in existing) {
                driver.execute(identifier = null, sql = "ALTER TABLE conv_facets ADD COLUMN $name $ddl", parameters = 0, binders = null)
            }
        }
    }

    /**
     * Creates the three FTS5 external-content sync triggers (Doc §4;
     * https://sqlite.org/fts5.html#external_content_tables) as raw SQL, executed directly
     * against [driver] rather than declared in `Search.sq`.
     *
     * Why raw SQL and not `.sq`-declared queries: verified empirically, SQLDelight 2.1.0's SQL
     * analyzer throws a `ClassCastException` (`SqlModuleColumnDefImpl` cannot be cast to
     * `ColumnDefMixin`) compiling ANY `INSERT INTO conv_fts(<column-list>)` — a sql-psi
     * limitation resolving an FTS5 virtual table's "module columns" as insert targets, not a
     * real SQLite problem (the exact same SQL executes correctly here, at runtime, against real
     * SQLite). Keeping this SQL as a plain string means the buggy compile-time analyzer never
     * sees it. `IF NOT EXISTS` makes this idempotent across repeated calls (schema creation +
     * every subsequent app start use the same driver-open path).
     */
    private fun installSyncTriggers(driver: SqlDriver) {
        listOf(
            """
            CREATE TRIGGER IF NOT EXISTS conv_projection_ai AFTER INSERT ON conv_projection BEGIN
              INSERT INTO conv_fts(rowid, title, snippet, body) VALUES (new.rowid, new.title, new.snippet, new.body);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS conv_projection_ad AFTER DELETE ON conv_projection BEGIN
              INSERT INTO conv_fts(conv_fts, rowid, title, snippet, body) VALUES('delete', old.rowid, old.title, old.snippet, old.body);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS conv_projection_au AFTER UPDATE ON conv_projection BEGIN
              INSERT INTO conv_fts(conv_fts, rowid, title, snippet, body) VALUES('delete', old.rowid, old.title, old.snippet, old.body);
              INSERT INTO conv_fts(rowid, title, snippet, body) VALUES (new.rowid, new.title, new.snippet, new.body);
            END
            """.trimIndent(),
        ).forEach { sql -> driver.execute(identifier = null, sql = sql, parameters = 0, binders = null) }
    }
}

/**
 * [database] plus the raw [SqlDriver] it wraps — [SearchDatabase] itself doesn't expose its
 * driver (the generated `Transacter` interface has no such accessor), and a couple of real
 * maintenance operations (here: [verifyAndRepairIfNeeded]) are raw SQL for the same reason the
 * sync triggers are (see [SearchDriverFactory]'s KDoc): FTS5's own `integrity-check`/`rebuild`
 * commands are special-command inserts targeting the `conv_fts` "module column" by name, which
 * hits the identical SQLDelight analyzer limitation if declared in `Search.sq`.
 */
class SearchDatabaseHandle(val database: SearchDatabase, internal val driver: SqlDriver) {

    /**
     * Runs FTS5's built-in consistency check (`INSERT INTO conv_fts(conv_fts) VALUES
     * ('integrity-check')`, which throws `SQLITE_CORRUPT_VTAB` if the shadow index has drifted
     * from `conv_projection`) and repairs via `('rebuild')` if it fails (Doc §13 test 18: "FTS5
     * external-content desync is detected and repaired via `('rebuild')`"). Returns `true` if a
     * repair was needed and performed.
     */
    fun verifyAndRepairIfNeeded(): Boolean {
        val corrupt = try {
            driver.execute(null, "INSERT INTO conv_fts(conv_fts) VALUES('integrity-check')", 0, null)
            false
        } catch (_: Exception) {
            true
        }
        if (corrupt) {
            driver.execute(null, "INSERT INTO conv_fts(conv_fts) VALUES('rebuild')", 0, null)
        }
        return corrupt
    }
}
