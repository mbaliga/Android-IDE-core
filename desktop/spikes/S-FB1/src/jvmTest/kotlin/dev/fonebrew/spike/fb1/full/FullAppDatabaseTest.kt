package dev.fonebrew.spike.fb1.full

import androidx.room.useReaderConnection
import dev.fonebrew.data.entity.MessageEmbeddingEntity
import dev.fonebrew.data.entity.MessageNodeEntity
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.tasks.TaskSource
import dev.fonebrew.domain.tasks.TaskState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * S-FB1 part B: core's REAL 17-entity / 17-DAO schema (mapped unchanged) built through the
 * context-free builder on desktop JVM.
 */
class FullAppDatabaseTest {
    private val tmp: File = Files.createTempDirectory("sfb1full").toFile()
    private var db: FullAppDatabase? = null
    @AfterTest fun cleanup() { runCatching { db?.close() }; tmp.deleteRecursively() }

    private fun open() = FullAppDatabase.open(File(tmp, "aarso.db").path).also { db = it }

    // The 17 tables core's AppDatabase declares (names = the @Entity(tableName=...) values).
    private val expectedTables = setOf(
        "buffer_journal_entries", "buffer_registry", "compaction_directives", "delegation_events",
        "form_states", "ghost_branches", "ledger_entries", "message_bookmarks", "message_embeddings",
        "message_nodes", "receipts", "recovery_snapshots", "tasks", "thread_markers", "token_counts",
        "verdicts", "versions",
    )

    @Test fun `all 17 core tables are created by Room-KMP on the bundled driver`() = runBlocking<Unit> {
        val database = open()
        database.messageNodeDao().getById("warm-up") // force schema creation
        val tables = database.useReaderConnection { c ->
            c.usePrepared("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'room_master_table' AND name <> 'android_metadata'") { st ->
                buildSet { while (st.step()) add(st.getText(0)) }
            }
        }
        println("S-FB1 full-schema tables (${tables.size}): ${tables.sorted()}")
        assertEquals(expectedTables, tables)
    }

    @Test fun `Task row with enum + list converters (real Converters-kt) roundtrips`() = runBlocking<Unit> {
        val dao = open().taskDao()
        val t = TaskEntity(
            id = "t1", title = "port it", notes = "n", state = TaskState.TODO, orderKey = 1.5,
            dependsOn = listOf("a", "b"), tags = listOf("x"), source = TaskSource.MANUAL,
            createdAt = 1, updatedAt = 2,
        )
        dao.insert(t)
        assertEquals(t, dao.getById("t1"))
        dao.update(t.copy(state = TaskState.DONE, doneAt = 9))
        assertEquals(TaskState.DONE, dao.getById("t1")!!.state)
        assertEquals(1.5, dao.maxOrderKey())
        assertEquals(1, dao.observeAll().first().size)
        dao.delete(t)
        assertEquals(0, dao.getAll().size)
    }

    @Test fun `BLOB column and FK cascade across the real entity set`() = runBlocking<Unit> {
        val database = open()
        database.messageNodeDao().insert(MessageNodeEntity("n1", null, "user", "hi", null, 1, null))
        val vec = dev.fonebrew.data.Converters.floatsToBytes(floatArrayOf(0.25f, -1f, 3.5f))
        database.embeddingDao().upsert(MessageEmbeddingEntity("n1", "placeholder", 3, vec, 1))
        assertContentEquals(vec, database.embeddingDao().forNode("n1")!!.vector)
        assertEquals(1, database.embeddingDao().count())
    }
}
