package dev.fonebrew.spike.fb2

import android.content.Context
import androidx.room.useReaderConnection
import app.cash.sqldelight.db.QueryResult
import dev.fonebrew.data.entity.MessageNodeEntity
import dev.fonebrew.data.search.SearchDatabaseHandle
import dev.fonebrew.data.search.SearchDriverFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S-FB2 evidence: core's REAL SearchDriverFactory/SearchDatabase (FTS5, sqlite-bundled, SQLDelight)
 * and a Room-KMP database (sqlite-bundled via BundledSQLiteDriver) live in ONE JVM process and
 * are used at the same time. Both are real files on disk, opened through the production code paths.
 */
class RoomFts5CoexistTest {

    private val tmp: File = Files.createTempDirectory("sfb2").toFile()
    private val closers = mutableListOf<() -> Unit>()

    /** Stub standing in for android.content.Context: `create(context)` only asks for a db path. */
    private val ctx = object : Context() {
        override fun getDatabasePath(name: String): File = File(tmp, name)
    }

    @AfterTest fun cleanup() {
        closers.reversed().forEach { runCatching { it() } }
        tmp.deleteRecursively()
    }

    private fun room(name: String = "tree.db"): CoexistDb =
        CoexistFactory.openRoom(File(tmp, name).path).also { closers += { it.close() } }

    /** The production, file-backed path (SearchDriverFactory.create(context)). */
    private fun search(): SearchDatabaseHandle = SearchDriverFactory.create(ctx)

    private fun node(id: String, content: String = "c-$id") =
        MessageNodeEntity(id, null, "user", content, null, 1, null)

    private fun SearchDatabaseHandle.project(id: String, title: String, body: String) =
        database.searchQueries.upsertProjection(
            conv_id = id, title = title, snippet = body.take(40), body = body,
            title_raw = title, snippet_raw = body.take(40), body_raw = body,
            updated_at = 1_700_000_000_000L, created_at = 1_700_000_000_000L, projection_version = 1L,
        )

    private fun SearchDatabaseHandle.hits(q: String) =
        database.searchQueries.searchCandidates(ftsQuery = q, limit = 50).executeAsList().map { it.conv_id }

    @Test fun `a MATCH query and a Room query both succeed in the same process`() = runBlocking<Unit> {
        val r = room()
        val s = search()
        // both are open at once, then both are queried
        r.messageNodeDao().insert(node("n1", "the build cache was invalidated"))
        s.project("c1", "gradle build cache", "the build cache was invalidated every run")
        s.project("c2", "weather", "sunny today")

        assertEquals("the build cache was invalidated", r.messageNodeDao().getById("n1")!!.content)
        assertEquals(listOf("c1"), s.hits("cache"))
        assertEquals(listOf("c1"), s.hits("\"build cache\"")) // phrase query => detail='full' took effect
        assertEquals("n1", r.messageNodeDao().pathToRoot("n1").single().id)
    }

    @Test fun `interleaved concurrent use of both databases does not corrupt either`() = runBlocking<Unit> {
        val r = room()
        val s = search()
        val n = 40
        (0 until n).map { i ->
            async(Dispatchers.IO) {
                r.messageNodeDao().insert(node("n$i"))
                synchronized(s) { s.project("c$i", "title $i", "alpha beta gamma token$i") }
                assertEquals("n$i", r.messageNodeDao().getById("n$i")!!.id)
                synchronized(s) { assertEquals(listOf("c$i"), s.hits("token$i")) }
            }
        }.awaitAll()
        assertEquals(n, r.messageNodeDao().observeAll().first().size)
        assertEquals(n.toLong(), s.database.searchQueries.countProjections().executeAsOne())
        assertEquals(n, s.hits("alpha").size)
    }

    @Test fun `both engines report the same bundled SQLite and FTS5 is compiled in`() = runBlocking<Unit> {
        val r = room()
        r.messageNodeDao().getById("warm-up")
        val roomVersion = r.useReaderConnection { c -> c.usePrepared("SELECT sqlite_version()") { st -> check(st.step()); st.getText(0) } }
        val roomOptions = r.useReaderConnection { c ->
            c.usePrepared("PRAGMA compile_options") { st -> buildList { while (st.step()) add(st.getText(0)) } }
        }
        val s = search()
        val sdVersion = s.driver.executeQuery(null, "SELECT sqlite_version()", { cur ->
            check(cur.next().value); QueryResult.Value(cur.getString(0)!!)
        }, 0).value
        println("S-FB2 room sqlite_version=$roomVersion search sqlite_version=$sdVersion")
        println("S-FB2 compile_options has ENABLE_FTS5: ${"ENABLE_FTS5" in roomOptions}")
        assertEquals(roomVersion, sdVersion)
        assertTrue("ENABLE_FTS5" in roomOptions, "FTS5 missing from the shared engine: $roomOptions")
    }

    @Test fun `one native sqlite library serves both - no second copy is mapped`() = runBlocking<Unit> {
        val r = room(); r.messageNodeDao().getById("warm-up")
        val s = search(); s.project("c", "t", "b"); s.hits("b")
        val maps = File("/proc/self/maps")
        if (!maps.exists()) { println("S-FB2 /proc/self/maps unavailable - native-mapping check skipped (non-Linux)"); return@runBlocking }
        // maps line: "start-end perms offset dev inode   /path/to/lib" (+ " (deleted)" if unlinked after load)
        val lines = maps.readLines().filter { "sqlite" in it.lowercase() }
        lines.forEach { println("S-FB2 maps: $it") }
        val mapped = lines.mapNotNull { Regex("""\s(/\S.*?)(?:\s+\(deleted\))?$""").find(it)?.groupValues?.get(1) }.toSortedSet()
        println("S-FB2 mapped sqlite native libraries: $mapped")
        assertEquals(1, mapped.size, "expected exactly one native sqlite library mapped, got $mapped")
    }

    @Test fun `exactly one androidx sqlite stack is on the runtime classpath`() {
        val cp = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).name }
        val bundled = cp.filter { it.startsWith("sqlite-bundled") }
        val core = cp.filter { it.startsWith("sqlite-jvm") || it == "sqlite.jar" }
        println("S-FB2 classpath sqlite-bundled=$bundled sqlite=$core")
        assertEquals(1, bundled.size, "sqlite-bundled jars: $bundled")
        assertEquals(1, core.size, "androidx.sqlite core jars: $core")
        assertTrue(bundled.single().contains("2.7.0"), "resolved a different bundled version than core's pin: $bundled")
    }

    @Test fun `raw-SQL sync triggers keep the external-content index consistent`() {
        val s = search()
        s.project("c1", "alpha title", "the quick brown fox")
        assertEquals(listOf("c1"), s.hits("fox"))
        s.project("c1", "alpha title", "the slow green turtle")          // UPDATE path -> _au trigger
        assertEquals(emptyList(), s.hits("fox"))
        assertEquals(listOf("c1"), s.hits("turtle"))
        s.database.searchQueries.deleteProjection("c1")                  // DELETE path -> _ad trigger
        assertEquals(emptyList(), s.hits("turtle"))
        assertFalse(s.verifyAndRepairIfNeeded(), "healthy index reported as needing repair")
    }

    @Test fun `foreign_keys pragma is on - facets cascade with their projection`() {
        val s = search()
        s.project("c1", "t", "b")
        s.database.searchQueries.upsertFacets("c1", 1, 0, null, "m", 1, 1, 0, 0, 0, null, null, null, 0, 0)
        assertEquals(1, s.database.searchQueries.selectFacets("c1").executeAsList().size)
        s.database.searchQueries.deleteProjection("c1")
        assertEquals(0, s.database.searchQueries.selectFacets("c1").executeAsList().size)
    }

    @Test fun `closing the Room database leaves the search database usable, and vice versa`() = runBlocking<Unit> {
        val r = room("a.db")
        val s = search()
        s.project("c1", "t", "persistent term")
        r.messageNodeDao().insert(node("n1"))
        r.close()
        assertEquals(listOf("c1"), s.hits("persistent"))
        val r2 = room("a.db") // reopen the same Room file after closing; search handle still open
        assertEquals("n1", r2.messageNodeDao().getById("n1")!!.id)
    }

    @Test fun `search data persists across reopen of the file-backed database`() {
        search().also { it.project("c1", "t", "durable"); (it.driver as AutoCloseable).close() }
        assertEquals(listOf("c1"), search().hits("durable"))
    }
}
