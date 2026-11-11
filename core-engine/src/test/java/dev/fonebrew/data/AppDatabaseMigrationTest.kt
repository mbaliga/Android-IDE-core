package dev.fonebrew.data

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * The real, JVM-only Room migration test (CLAUDE.md "Environment honesty"/binding rule 6 — no
 * device or Robolectric here, and none is needed).
 *
 * Room 2.7's [androidx.room.RoomDatabase.Builder.setDriver] with `androidx.sqlite:sqlite-bundled-jvm`
 * (already on the test classpath for [dev.fonebrew.data.search.SearchDatabaseFactoryTest]) runs the
 * real generated [AppDatabase] against real SQLite on the host JVM. The one Android dependency,
 * `Context.getDatabasePath(name)`, is satisfied by a [ContextWrapper] overriding only that method.
 *
 * The v10 fixture is NOT built from today's entities: it is created from the committed
 * `core-engine/schemas/dev.fonebrew.data.AppDatabase/10.json` (the Room export of the v10 entity
 * set, re-derived from the tree before the v10->v11 bump) by executing that file's own `createSql`,
 * index and `setupQueries` statements and stamping `PRAGMA user_version = 10`. One representative
 * row is seeded into EVERY table the file declares, the database is reopened through
 * [MIGRATION_10_11], and every row must come back byte-identical. When a future bump changes a
 * table, add `<N>.json` and build that bump's test from it the same way.
 */
class AppDatabaseMigrationTest {

    private lateinit var dbFile: File

    /** Overrides only what driver-based Room's `RoomConnectionManager.resolveFileName` needs —
     *  see the class KDoc for why nothing else is reachable. */
    private val fakeContext = object : ContextWrapper(null) {
        override fun getDatabasePath(name: String): File = File(name)
    }

    @Before
    fun setUp() {
        dbFile = File.createTempFile("app-database-migration-test", ".db")
        // Room/SQLite must create the file themselves on first open.
        dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()
    }

    /** [AppDatabase]/[RoomDatabase] isn't [Closeable][java.io.Closeable] (just a plain `close()`
     *  method), so Kotlin's stdlib `use {}` doesn't apply — this is the inline equivalent. */
    private suspend inline fun <R> AppDatabase.useAndClose(block: suspend (AppDatabase) -> R): R {
        try {
            return block(this)
        } finally {
            close()
        }
    }

    private fun openDatabase(migrations: List<androidx.room.migration.Migration>): AppDatabase =
        Room.databaseBuilder(fakeContext, AppDatabase::class.java, dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            // AUTOMATIC (the default) resolves via Context.getSystemService(ActivityManager),
            // which the fake Context above deliberately doesn't implement — pin a mode that
            // short-circuits before touching Context at all.
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*migrations.toTypedArray())
            .build()

    private fun schemaJson(version: Int): JSONObject {
        // Gradle runs unit tests with the module directory as the working directory.
        val f = File("schemas/dev.fonebrew.data.AppDatabase/$version.json")
        assertTrue("missing committed schema export ${f.absolutePath}", f.isFile)
        return JSONObject(f.readText()).getJSONObject("database")
    }

    /** One seeded row: column name -> value (null = SQL NULL). */
    private class Seed(val table: String, val values: Map<String, Any?>)

    /**
     * Builds the on-disk database exactly as the v10 schema export describes it (create
     * statements, indices, Room's master table + identity hash, `user_version = 10`) and seeds
     * one row per table. Values are derived from each column's declared affinity; `parentId` is
     * left NULL (root node) and every `nodeId` points at the seeded message node so the
     * foreign keys the export declares are satisfied.
     */
    private fun createV10Database(): List<Seed> {
        val db = schemaJson(10)
        assertEquals(10, db.getInt("version"))
        val entities = db.getJSONArray("entities")
        val seeds = ArrayList<Seed>()
        val connection = BundledSQLiteDriver().open(dbFile.absolutePath)
        try {
            fun exec(sql: String) {
                val s = connection.prepare(sql)
                try { s.step() } finally { s.close() }
            }
            exec("PRAGMA foreign_keys = ON")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                exec(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val idx = e.optJSONArray("indices")
                for (j in 0 until (idx?.length() ?: 0)) {
                    exec(idx!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = db.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) exec(setup.getString(i))

            // message_nodes first: token_counts / message_embeddings reference it.
            val ordered = (0 until entities.length()).map { entities.getJSONObject(it) }
                .sortedBy { if (it.getString("tableName") == "message_nodes") 0 else 1 }
            for (e in ordered) {
                val table = e.getString("tableName")
                val fields = e.getJSONArray("fields")
                val values = LinkedHashMap<String, Any?>()
                for (k in 0 until fields.length()) {
                    val f = fields.getJSONObject(k)
                    val col = f.getString("columnName")
                    values[col] = when {
                        col == "parentId" -> null
                        col == "nodeId" || (table == "message_nodes" && col == "id") -> "node-1"
                        f.getString("affinity") == "TEXT" -> "seed-$table-$col"
                        f.getString("affinity") == "INTEGER" -> 7L
                        f.getString("affinity") == "REAL" -> 1.5
                        else -> byteArrayOf(1, 2, 3, 4)
                    }
                }
                val cols = values.keys.joinToString(",") { "`$it`" }
                val marks = values.keys.joinToString(",") { "?" }
                val stmt = connection.prepare("INSERT INTO `$table` ($cols) VALUES ($marks)")
                try {
                    values.values.forEachIndexed { n, v ->
                        val p = n + 1
                        when (v) {
                            null -> stmt.bindNull(p)
                            is String -> stmt.bindText(p, v)
                            is Long -> stmt.bindLong(p, v)
                            is Double -> stmt.bindDouble(p, v)
                            is ByteArray -> stmt.bindBlob(p, v)
                            else -> error("unseedable $v")
                        }
                    }
                    stmt.step()
                } finally {
                    stmt.close()
                }
                seeds += Seed(table, values)
            }
            exec("PRAGMA user_version = 10")
        } finally {
            connection.close()
        }
        return seeds
    }

    /** Asserts [table] holds exactly one row and that it equals [expected], column by column. */
    private fun assertSingleRowEquals(table: String, expected: Map<String, Any?>) {
        val columns = expected.keys.toList()
        val connection = BundledSQLiteDriver().open(dbFile.absolutePath)
        try {
            val stmt = connection.prepare("SELECT ${columns.joinToString(",") { "`$it`" }} FROM `$table`")
            try {
                assertTrue("$table lost its row in the migration", stmt.step())
                columns.forEachIndexed { i, c ->
                    val label = "$table.$c"
                    when (val want = expected[c]) {
                        null -> assertTrue(label, stmt.isNull(i))
                        is String -> assertEquals(label, want, stmt.getText(i))
                        is Long -> assertEquals(label, want, stmt.getLong(i))
                        is Double -> assertEquals(label, want, stmt.getDouble(i), 0.0)
                        is ByteArray -> assertTrue(label, want.contentEquals(stmt.getBlob(i)))
                        else -> error("unexpected seed type for $label")
                    }
                }
                assertFalse("$table gained rows in the migration", stmt.step())
            } finally {
                stmt.close()
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `the committed v10 and v11 schema exports differ only in version number`() {
        // This is the evidence behind MIGRATION_10_11 being a no-op: if a future edit makes the
        // entity set drift without a real migration, this fails.
        val v10 = schemaJson(10)
        val v11 = schemaJson(11)
        assertEquals(v10.getString("identityHash"), v11.getString("identityHash"))
        assertEquals(v10.getJSONArray("entities").toString(), v11.getJSONArray("entities").toString())
        assertEquals(v10.getJSONArray("setupQueries").toString(), v11.getJSONArray("setupQueries").toString())
    }

    @Test
    fun `every table in the real v10 schema keeps its seeded row through the v10 to v11 migration`() = runTest {
        val seeds = createV10Database()
        assertTrue("fixture should seed every declared table", seeds.size >= 17)

        // Opening runs MIGRATION_10_11 and Room's post-migration schema validation against v11.
        openDatabase(migrations = listOf(MIGRATION_10_11)).useAndClose { db ->
            // Force the open (and therefore the migration) to happen.
            assertNotNull(db.messageNodeDao().getById("node-1"))
        }

        for (seed in seeds) assertSingleRowEquals(seed.table, seed.values)
    }

    @Test
    fun `message tree rows seeded on the real v10 schema are readable through the DAOs after migration`() = runTest {
        createV10Database()
        openDatabase(migrations = listOf(MIGRATION_10_11)).useAndClose { db ->
            val node = db.messageNodeDao().getById("node-1")
            assertNotNull("message node must survive the v10->v11 migration", node)
            assertEquals("seed-message_nodes-role", node!!.role)
            assertEquals("seed-message_nodes-content", node.content)
            assertEquals(7L, node.createdAt)
            assertEquals(1, db.messageNodeDao().observeAll().first().size)
            assertEquals(1, db.tokenCountDao().forNode("node-1").size)
            assertNotNull(db.embeddingDao().forNode("node-1"))
        }
    }

    @Test
    fun `opening the real v10 database without the migration fails instead of wiping it`() = runTest {
        // The no-destructive-fallback policy (AppContainer.kt): a missing migration path must
        // throw, never silently recreate the file.
        createV10Database()
        val failure = runCatching {
            openDatabase(migrations = emptyList()).useAndClose { db -> db.messageNodeDao().getById("node-1") }
        }.exceptionOrNull()
        assertNotNull("opening v10 -> v11 with no migration must throw", failure)
        // And the row is still on disk.
        assertSingleRowEquals("message_nodes", mapOf("id" to "node-1"))
    }
}
