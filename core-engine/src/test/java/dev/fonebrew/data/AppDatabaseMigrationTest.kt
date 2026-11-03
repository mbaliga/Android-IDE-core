package dev.fonebrew.data

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.fonebrew.data.entity.MessageEmbeddingEntity
import dev.fonebrew.data.entity.MessageNodeEntity
import dev.fonebrew.data.entity.TokenCountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The real, JVM-only Room migration test the data-loss-fix lane exists for (CLAUDE.md
 * "Environment honesty"/binding rule 6 — no device or Robolectric here, and none is needed).
 *
 * Earlier JVM tests in this package avoided Room's generated implementation entirely ("Room
 * needs a real SQLite binding this JVM gate doesn't have", see e.g. [CurationStoreTest]'s own
 * comment) and exercised stores against fake [dev.fonebrew.data.dao] implementations instead.
 * That workaround is correct for ordinary DAO logic, but it cannot prove a [Migration][androidx.room.migration.Migration]
 * actually runs against real SQLite — which is the whole point of this lane. The missing piece
 * turns out to already be on the test classpath: `androidx.sqlite:sqlite-bundled-jvm` (added for
 * [dev.fonebrew.data.search.SearchDatabaseFactoryTest]'s SQLDelight/FTS5 test) ships a real,
 * host-JVM SQLite engine that Room 2.7's [androidx.room.RoomDatabase.Builder.setDriver] can use
 * directly, bypassing the classic `SupportSQLiteOpenHelper`/`FrameworkSQLiteOpenHelperFactory`
 * path that needs a real Android `Context` (or Robolectric) to open a connection at all.
 *
 * The one remaining Android dependency, `Context.getDatabasePath(name)` (called internally by
 * Room's `RoomConnectionManager.resolveFileName`), is satisfied with a minimal hand-rolled
 * [ContextWrapper] that overrides only that one method — safe because driver-based Room never
 * reaches another `Context` method on a non-AUTOMATIC [RoomDatabase.JournalMode] with multi-
 * instance invalidation and asset/file pre-population unused (verified against the actual
 * room-runtime-2.7.1 bytecode, not assumed).
 *
 * The test: build a database with [AppDatabase]'s real schema (identical between v10 and v11 —
 * see [MIGRATION_10_11]'s KDoc), insert representative append-only message-tree rows (a node, its
 * token count, and its embedding), then force the on-disk `PRAGMA user_version` back down to 10
 * (an honest stand-in for "a database left over from the v10 release", since v10's and v11's
 * table structure do not differ at all) before reopening through [MIGRATION_10_11] — proving the
 * real migration path preserves user data end-to-end, which is exactly what
 * `fallbackToDestructiveMigration()` could never do.
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
            // short-circuits before touching Context at all (verified against the real
            // RoomDatabase.JournalMode.resolve$room_runtime_release bytecode).
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(*migrations.toTypedArray())
            .build()

    /** Downgrades the on-disk schema version to simulate "a v10 database, as it would exist on
     *  a real user's device before this upgrade" — legitimate here specifically because v10's
     *  and v11's table structure are identical (see [MIGRATION_10_11]'s KDoc); a future bump
     *  that actually changes a column/table needs its own fixture built from the real exported
     *  v-N schema instead of this shortcut. */
    private fun stampStoredVersion(version: Int) {
        val connection = BundledSQLiteDriver().open(dbFile.absolutePath)
        try {
            val statement = connection.prepare("PRAGMA user_version = $version")
            try {
                statement.step()
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `migration from v10 to v11 preserves the append-only message tree`() = runTest {
        val nodeId = "node-root-1"
        val tokenizerId = "cl100k_base"
        val vector = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        // Phase 1: a database that looks exactly like a shipped v10 install — built against
        // AppDatabase's real (v11) schema, which is byte-for-byte what v10 already was.
        openDatabase(migrations = emptyList()).useAndClose { db ->
            db.messageNodeDao().insert(
                MessageNodeEntity(
                    id = nodeId,
                    parentId = null,
                    role = "user",
                    content = "hello from before the v10->v11 upgrade",
                    modelId = "local-gguf-test",
                    createdAt = 1_700_000_000_000L,
                    metadataJson = """{"source":"migration-test"}""",
                ),
            )
            db.tokenCountDao().upsert(TokenCountEntity(nodeId = nodeId, tokenizerId = tokenizerId, count = 42))
            db.embeddingDao().upsert(
                MessageEmbeddingEntity(
                    nodeId = nodeId,
                    embedderId = "placeholder-embedder",
                    dim = vector.size,
                    vector = vector,
                    createdAt = 1_700_000_000_500L,
                ),
            )
        }
        stampStoredVersion(10)

        // Phase 2: reopen through the real migration path this lane exists to prove out.
        openDatabase(migrations = listOf(MIGRATION_10_11)).useAndClose { db ->
            val node = db.messageNodeDao().getById(nodeId)
            assertNotNull("message node must survive the v10->v11 migration", node)
            assertEquals("user", node!!.role)
            assertEquals("hello from before the v10->v11 upgrade", node.content)
            assertEquals("local-gguf-test", node.modelId)
            assertEquals(1_700_000_000_000L, node.createdAt)
            assertEquals("""{"source":"migration-test"}""", node.metadataJson)

            val allNodes = db.messageNodeDao().observeAll()
            assertEquals(1, kotlinx.coroutines.flow.first(allNodes).size)

            val tokenCount = db.tokenCountDao().forNode(nodeId)
            assertEquals(1, tokenCount.size)
            assertEquals(tokenizerId, tokenCount.first().tokenizerId)
            assertEquals(42, tokenCount.first().count)

            val embedding = db.embeddingDao().forNode(nodeId)
            assertNotNull("embedding must survive the v10->v11 migration", embedding)
            assertEquals("placeholder-embedder", embedding!!.embedderId)
            assertEquals(vector.size, embedding.dim)
            assertEquals(true, vector.contentEquals(embedding.vector))
        }
    }

    @Test
    fun `migration from v10 to v11 preserves multiple nodes and row counts`() = runTest {
        openDatabase(migrations = emptyList()).useAndClose { db ->
            repeat(5) { i ->
                db.messageNodeDao().insert(
                    MessageNodeEntity(
                        id = "node-$i",
                        parentId = if (i == 0) null else "node-${i - 1}",
                        role = if (i % 2 == 0) "user" else "assistant",
                        content = "message #$i",
                        modelId = null,
                        createdAt = 1_700_000_000_000L + i,
                        metadataJson = null,
                    ),
                )
            }
        }
        stampStoredVersion(10)

        openDatabase(migrations = listOf(MIGRATION_10_11)).useAndClose { db ->
            val all = kotlinx.coroutines.flow.first(db.messageNodeDao().observeAll())
            assertEquals(5, all.size)
            assertEquals((0..4).map { "message #$it" }, all.map { it.content })
        }
    }
}
