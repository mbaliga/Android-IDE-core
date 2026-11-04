package dev.fonebrew.watch.core

import dev.fonebrew.watchbridge.Spend
import dev.fonebrew.watchbridge.Usage
import dev.fonebrew.watchbridge.WatchTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A map-backed [KeyValueStore]; also what the controller tests use. */
internal class MapStore : KeyValueStore {
    val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

class SnapshotCacheTest {
    private val store = MapStore()
    private val cache = SnapshotCache(store)

    @Test
    fun tasks_roundTrip_withTheOriginalReceiptTime() {
        val snap = Fx.snapshot(Fx.task("a", "Buy milk"), Fx.task("b", "Ship", WatchTaskState.DONE), pending = 12)
        cache.saveTasks(snap, 4_242)
        val (loaded, at) = cache.loadTasks()!!
        assertEquals(snap, loaded)
        assertEquals(4_242L, at)
    }

    @Test
    fun loops_roundTrip_inOrder_includingEstimatedSpend() {
        val runs = listOf(
            LoopEntry(Fx.run("r1", step = "x", spend = Spend(9, "USD", estimated = true)), 10),
            LoopEntry(Fx.run("r2", glyph = "complete"), 20),
        )
        cache.saveLoops(runs)
        assertEquals(runs, cache.loadLoops())
    }

    @Test
    fun savingFewerLoops_neverResurrectsTheOldTail() {
        cache.saveLoops(listOf(LoopEntry(Fx.run("r1"), 1), LoopEntry(Fx.run("r2"), 2), LoopEntry(Fx.run("r3"), 3)))
        cache.saveLoops(listOf(LoopEntry(Fx.run("r9"), 9)))
        assertEquals(listOf("r9"), cache.loadLoops().map { it.snapshot.runId })
    }

    @Test
    fun lastReply_roundTrips_asATerminalChunk_keepingWatchedAndEstimatedHonest() {
        val reply = ReplyRecord("r", "hello", "cloud-x", watchedObject = true, usage = Usage.estimate(3, null), completedAtMs = 777)
        cache.saveLastReply(reply)
        assertEquals(reply, cache.loadLastReply())
    }

    @Test
    fun aLongReply_isCapped_withAnExplicitEllipsis_andNeverSplitsASurrogatePair() {
        val long = "a".repeat(SnapshotCache.MAX_CACHED_REPLY_CHARS - 2) + "😀" + "tail"
        cache.saveLastReply(ReplyRecord("r", long, "m", false, Usage.engineReported(1, 1), 1))
        val text = cache.loadLastReply()!!.text
        assertTrue(text.endsWith("…"))
        assertTrue(text.length <= SnapshotCache.MAX_CACHED_REPLY_CHARS)
        assertFalse(text.removeSuffix("…").last().isHighSurrogate())
        // a short reply is untouched
        cache.saveLastReply(ReplyRecord("r", "short", "m", false, Usage.engineReported(1, 1), 1))
        assertEquals("short", cache.loadLastReply()!!.text)
    }

    @Test
    fun corruptOrForeignEntries_areIgnored_notHalfTrusted() {
        store.put("cache.tasks", "garbage")
        store.put("cache.reply", "12\n{\"contract\":\"other\"}")
        store.put("cache.loops", "3")
        store.put("cache.loop.0", "notanumber\n{}")
        assertNull(cache.loadTasks())
        assertNull(cache.loadLastReply())
        assertTrue(cache.loadLoops().isEmpty())
    }

    @Test
    fun aValidMessageOfTheWrongKind_underAKey_isRejected() {
        // A loop-run snapshot stored under the tasks key must not load as tasks.
        cache.saveLoops(listOf(LoopEntry(Fx.run("r"), 1)))
        store.put("cache.tasks", store.get("cache.loop.0"))
        assertNull(cache.loadTasks())
    }

    @Test
    fun aNonTerminalChunkStoredAsTheReply_isRejected() {
        store.put("cache.reply", "5\n" + dev.fonebrew.watchbridge.WatchBridgeCodec.encode(Fx.token("partial")))
        assertNull(cache.loadLastReply())
    }

    @Test
    fun emptyStore_hydratesToNothing() {
        val h = cache.hydration()
        assertNull(h.tasks)
        assertTrue(h.loops.isEmpty())
        assertNull(h.lastReply)
    }

    @Test
    fun hydration_fillsGapsOnly_neverOverwritesNewerLiveData() {
        val live = WatchReducer.reduce(WatchState(), TaskEvent.SnapshotReceived(Fx.snapshot(Fx.task("live")), 9_000))
        val cached = Hydrated(Fx.snapshot(Fx.task("old")) to 1_000L, listOf(LoopEntry(Fx.run("r"), 1)), null)
        val merged = WatchReducer.reduce(live, cached)
        assertEquals("live", merged.tasks.snapshot!!.tasks.single().id)
        assertEquals(9_000L, merged.tasks.receivedAtMs)
        assertEquals(1, merged.loops.runs.size) // loops were empty, so the cache fills them
        val cold = WatchReducer.reduce(WatchState(), cached)
        assertEquals("old", cold.tasks.snapshot!!.tasks.single().id)
        assertEquals(1_000L, cold.tasks.receivedAtMs)
    }
}
