package dev.fonebrew.spike.fb1

import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S-FB1 evidence: Room-KMP + BundledSQLiteDriver on a plain jvm() target. Every test opens a REAL
 * database FILE through the context-free builder (no Context, no Robolectric, no emulator).
 */
class RoomKmpJvmRoundtripTest {

    private val tmp: File = Files.createTempDirectory("sfb1").toFile()
    private val opened = mutableListOf<AutoCloseable>()

    @AfterTest fun cleanup() {
        opened.forEach { runCatching { it.close() } }
        tmp.deleteRecursively()
    }

    private fun db(name: String = "t.db"): SpikeDb =
        SpikeDbFactory.openFile(File(tmp, name).path).also { opened += AutoCloseable { it.close() } }

    private fun node(id: String, parent: String?, content: String = "c-$id", at: Long = 0) =
        MessageNodeEntity(id = id, parentId = parent, role = "user", content = content, modelId = null, createdAt = at, metadataJson = null)

    @Test fun `entity and dao roundtrip on desktop JVM`() = runBlocking<Unit> {
        val dao = db().messageNodeDao()
        val n = MessageNodeEntity("n1", null, "assistant", "héllo — wörld 😀", "m-1", 42L, "{\"k\":\"v\"}")
        dao.insert(n)
        assertEquals(n, dao.getById("n1"))
        assertNull(dao.getById("absent"))
    }

    @Test fun `data survives close and reopen of the same file`() = runBlocking<Unit> {
        val path = File(tmp, "persist.db").path
        val first = SpikeDbFactory.openFile(path)
        first.messageNodeDao().insert(node("p1", null))
        first.close()
        val second = SpikeDbFactory.openFile(path).also { opened += AutoCloseable { it.close() } }
        assertNotNull(second.messageNodeDao().getById("p1"))
    }

    @Test fun `append-only insert aborts on id collision`() = runBlocking<Unit> {
        val dao = db().messageNodeDao()
        dao.insert(node("dup", null, content = "first"))
        assertFailsWith<SQLiteException> { dao.insert(node("dup", null, content = "second")) }
        assertEquals("first", dao.getById("dup")!!.content)
    }

    @Test fun `recursive CTE pathToRoot returns leaf-first ancestry`() = runBlocking<Unit> {
        val dao = db().messageNodeDao()
        dao.insert(node("root", null, at = 1))
        dao.insert(node("mid", "root", at = 2))
        dao.insert(node("leaf", "mid", at = 3))
        dao.insert(node("sibling", "root", at = 4))
        assertEquals(listOf("leaf", "mid", "root"), dao.pathToRoot("leaf").map { it.id })
    }

    @Test fun `Flow query emits the current rows in createdAt order`() = runBlocking<Unit> {
        val dao = db().messageNodeDao()
        dao.insert(node("b", null, at = 2))
        dao.insert(node("a", null, at = 1))
        assertEquals(listOf("a", "b"), dao.observeAll().first().map { it.id })
        assertEquals(emptyList(), dao.observeChildren("a").first())
    }

    @Test fun `foreign key ON DELETE CASCADE fires - the subtree goes with its parent`() = runBlocking<Unit> {
        val dao = db().messageNodeDao()
        dao.insert(node("r", null)); dao.insert(node("c1", "r")); dao.insert(node("g1", "c1")); dao.insert(node("other", null))
        assertEquals(4, dao.count())
        dao.deleteById("r")
        assertEquals(listOf("other"), dao.observeAll().first().map { it.id })
    }

    @Test fun `foreign key rejects an orphan child - proves PRAGMA foreign_keys is on`() = runBlocking<Unit> {
        val dao = db().messageNodeDao()
        assertFailsWith<SQLiteException> { dao.insert(node("orphan", "no-such-parent")) }
    }

    @Test fun `TypeConverter column roundtrips, including awkward characters`() = runBlocking<Unit> {
        val dao = db().converterProbeDao()
        val meta = linkedMapOf("tag" to "a", "k\\ey" to "v\u001fal\u001eue", "empty" to "")
        dao.insert(ConverterProbeEntity("c1", meta))
        assertEquals(meta, dao.get("c1")!!.metadata)
        dao.upsert(ConverterProbeEntity("c1", emptyMap()))
        assertEquals(emptyMap(), dao.get("c1")!!.metadata)
    }

    @Test fun `the connection is the bundled SQLite, version is reported`() = runBlocking<Unit> {
        val database = db()
        // touch the db so the driver connection exists, then ask the engine itself
        database.messageNodeDao().count()
        val version = database.useReaderConnection { c ->
            c.usePrepared("SELECT sqlite_version()") { st -> check(st.step()); st.getText(0) }
        }
        println("S-FB1 sqlite_version() = $version")
        val (major, minor) = version.split('.').map { it.toInt() }.take(2)
        assertTrue(major == 3 && minor >= 45, "unexpectedly old SQLite: $version")
    }

    @Test fun `exportSchema true under the Room Gradle plugin writes the schema JSON`() {
        val dir = File(System.getProperty("spike.schemaDir") ?: error("spike.schemaDir not set"))
        val exported = dir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue(exported.isNotEmpty(), "no schema JSON under $dir (exportSchema=true was ignored?)")
        val json = exported.single { it.path.contains("SpikeDbExported") }.readText()
        assertTrue("\"tableName\": \"message_nodes\"" in json, "exported schema lacks message_nodes")
        assertTrue("\"tableName\": \"converter_probe\"" in json, "exported schema lacks converter_probe")
        // the non-exporting database (core's current setting) must NOT have produced a file
        assertTrue(exported.none { it.path.contains("SpikeDb/") }, "exportSchema=false still wrote a schema")
        // and the exported database actually opens + works through the same builder
        val e = SpikeDbFactory.openExportedFile(File(tmp, "exp.db").path).also { opened += AutoCloseable { it.close() } }
        runBlocking {
            e.messageNodeDao().insert(node("e1", null))
            assertNotNull(e.messageNodeDao().getById("e1"))
        }
    }
}
