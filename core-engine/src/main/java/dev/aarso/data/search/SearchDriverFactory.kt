package dev.aarso.data.search

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
    fun create(context: Context): SearchDatabase {
        val path = context.getDatabasePath("search.db").absolutePath
        val driver = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.File(path),
            schema = SearchDatabase.Schema,
        )
        installSyncTriggers(driver)
        return SearchDatabase(driver)
    }

    /** JVM-testable / ephemeral: in-memory database, same driver artifact as production. */
    fun createInMemory(): SearchDatabase {
        val driver = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.Memory,
            schema = SearchDatabase.Schema,
        )
        installSyncTriggers(driver)
        return SearchDatabase(driver)
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
