package dev.fonebrew.inference

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class LoadedModelSlotTest {

    private class FakeNative(
        private val loadDelayMs: Long = 0,
        var failNextLoad: Boolean = false,
        var returnZeroNextLoad: Boolean = false,
    ) : ModelNative {
        val events = CopyOnWriteArrayList<String>()
        private val next = AtomicInteger(100)
        private val inLoad = AtomicInteger(0)
        val maxConcurrentLoads = AtomicInteger(0)
        val loads = AtomicInteger(0)

        override fun load(modelPath: String, contextSize: Int): Long {
            val now = inLoad.incrementAndGet()
            maxConcurrentLoads.accumulateAndGet(now, ::maxOf)
            try {
                if (loadDelayMs > 0) Thread.sleep(loadDelayMs)
                loads.incrementAndGet()
                events += "load:$modelPath"
                if (failNextLoad) { failNextLoad = false; error("boom") }
                if (returnZeroNextLoad) { returnZeroNextLoad = false; return 0L }
                return next.incrementAndGet().toLong().also { live += it }
            } finally {
                inLoad.decrementAndGet()
            }
        }

        override fun free(handle: Long) { live -= handle; events += "free:$handle" }
        override fun requestStop(handle: Long) { events += "stop:$handle" }

        // Handle validity: a handle is live from load until free. Using a dead one is the
        // use-after-free the slot must make impossible; count slowly so a racing free would land
        // in the middle of a call.
        val live: MutableSet<Long> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
        val useAfterFree = AtomicInteger(0)
        var countDelayMs = 0L
        override fun countTokens(handle: Long, utf8: ByteArray): Int {
            if (handle !in live) useAfterFree.incrementAndGet()
            if (countDelayMs > 0) Thread.sleep(countDelayMs)
            if (handle !in live) useAfterFree.incrementAndGet()
            return utf8.size
        }
    }

    @Test fun `concurrent loads of one model load it exactly once`() = runBlocking {
        val native = FakeNative(loadDelayMs = 40)
        val slot = LoadedModelSlot(native)
        (1..8).map { async(Dispatchers.Default) { slot.load("/m/a.gguf", 4096) } }.awaitAll()
        assertEquals(1, native.loads.get())
        assertEquals(1, native.maxConcurrentLoads.get())
        assertTrue(slot.isLoaded)
        assertEquals("/m/a.gguf", slot.path)
    }

    @Test fun `concurrent loads of different models never overlap and free the loser`() = runBlocking {
        val native = FakeNative(loadDelayMs = 30)
        val slot = LoadedModelSlot(native)
        listOf("/m/a", "/m/b", "/m/c").map { p -> async(Dispatchers.Default) { slot.load(p, 4096) } }.awaitAll()
        assertEquals(1, native.maxConcurrentLoads.get())
        // Whatever order won, every model but the last-loaded one was freed: loads - frees == 1.
        val frees = native.events.count { it.startsWith("free:") }
        assertEquals(3 - 1, frees)
        assertTrue(slot.isLoaded)
    }

    @Test fun `swapping models frees the old handle before loading the new one`() = runBlocking {
        val native = FakeNative()
        val slot = LoadedModelSlot(native)
        slot.load("/m/a", 1)
        val first = slot.handle
        slot.load("/m/b", 1)
        val ev = native.events.filter { !it.startsWith("stop") }
        assertEquals(listOf("load:/m/a", "free:$first", "load:/m/b"), ev)
        assertEquals("/m/b", slot.path)
    }

    @Test fun `a failed load after a free leaves a clean unloaded slot`() = runBlocking {
        val native = FakeNative()
        val slot = LoadedModelSlot(native)
        slot.load("/m/a", 1)
        native.failNextLoad = true
        val t = runCatching { slot.load("/m/b", 1) }.exceptionOrNull()
        assertTrue(t is IllegalStateException)
        assertFalse(slot.isLoaded)
        assertEquals(0L, slot.handle)
        assertEquals(null, slot.path)
        // And it recovers: a later load works and nothing stale is freed twice.
        slot.load("/m/b", 1)
        assertTrue(slot.isLoaded)
        assertEquals(1, native.events.count { it.startsWith("free:") })
    }

    @Test fun `a zero handle from native is a load failure and leaves the slot unloaded`() = runBlocking {
        val native = FakeNative(returnZeroNextLoad = true)
        val slot = LoadedModelSlot(native)
        val t = runCatching { slot.load("/m/a", 1) }.exceptionOrNull()
        assertTrue(t is IllegalStateException)
        assertFalse(slot.isLoaded)
        assertEquals(null, slot.path)
    }

    @Test fun `unload frees once and is idempotent`() = runBlocking {
        val native = FakeNative()
        val slot = LoadedModelSlot(native)
        slot.load("/m/a", 1)
        slot.unload()
        slot.unload()
        assertEquals(1, native.events.count { it.startsWith("free:") })
        assertFalse(slot.isLoaded)
    }

    @Test fun `withHandle requires a loaded model`() = runBlocking {
        val slot = LoadedModelSlot(FakeNative())
        val t = runCatching { slot.withHandle { it } }.exceptionOrNull()
        assertTrue(t is IllegalStateException)
        assertEquals("No model loaded.", t!!.message)
    }

    @Test fun `unload during a generation asks it to stop and frees only after it finishes`() = runBlocking {
        val native = FakeNative()
        val slot = LoadedModelSlot(native)
        slot.load("/m/a", 1)
        val h = slot.handle
        val inside = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val gen = async(Dispatchers.Default) {
            slot.withHandle { handle ->
                assertEquals(h, handle)
                inside.complete(Unit)
                finish.await()
                native.events += "generation-done"
            }
        }
        inside.await()
        val unloading = launch(Dispatchers.Default) { slot.unload() }
        withTimeout(5_000) { while ("stop:$h" !in native.events) delay(5) }
        assertFalse("must not free while the decode holds the model", native.events.any { it.startsWith("free:") })
        finish.complete(Unit)
        gen.await()
        unloading.join()
        val ev = native.events.filter { it == "generation-done" || it.startsWith("free:") }
        assertEquals(listOf("generation-done", "free:$h"), ev)
    }

    @Test fun `loading the same model during a generation does not interrupt it`() = runBlocking {
        val native = FakeNative()
        val slot = LoadedModelSlot(native)
        slot.load("/m/a", 1)
        val h = slot.handle
        val inside = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val gen = async(Dispatchers.Default) { slot.withHandle { inside.complete(Unit); finish.await() } }
        inside.await()
        val again = launch(Dispatchers.Default) { slot.load("/m/a", 1) }
        delay(50)
        assertFalse("stop:$h" in native.events)
        finish.complete(Unit)
        gen.await(); again.join()
        assertEquals(1, native.loads.get())
        withContext(Dispatchers.Default) { slot.unload() }
    }

    @Test fun `countTokens is zero when nothing is loaded and counts when loaded`() = runBlocking {
        val slot = LoadedModelSlot(FakeNative())
        assertEquals(0, slot.countTokens("abc".toByteArray()))
        slot.load("/m/a.gguf", 4096)
        assertEquals(3, slot.countTokens("abc".toByteArray()))
        slot.unload()
        assertEquals(0, slot.countTokens("abc".toByteArray()))
    }

    @Test fun `unload waits for an in-flight count and never frees under it`() = runBlocking {
        val native = FakeNative().apply { countDelayMs = 150 }
        val slot = LoadedModelSlot(native)
        slot.load("/m/a.gguf", 4096)
        val counting = async(Dispatchers.Default) { slot.countTokens("hello".toByteArray()) }
        delay(40) // the count is now inside native
        slot.unload()
        assertEquals(5, counting.await())
        assertEquals(0, native.useAfterFree.get())
        assertFalse(slot.isLoaded)
    }

    @Test fun `hammering count against unload and reload never uses a freed handle`() = runBlocking {
        val native = FakeNative().apply { countDelayMs = 1 }
        val slot = LoadedModelSlot(native)
        slot.load("/m/a.gguf", 4096)
        val jobs = (1..4).map {
            async(Dispatchers.Default) { repeat(200) { slot.countTokens("x".toByteArray()) } }
        } + async(Dispatchers.Default) {
            repeat(40) { slot.unload(); slot.load("/m/a.gguf", 4096) }
        }
        withTimeout(20_000) { jobs.awaitAll() }
        assertEquals(0, native.useAfterFree.get())
    }

    @Test fun `countTokens from inside a running generation does not deadlock or wait for it`() = runBlocking {
        val native = FakeNative()
        val slot = LoadedModelSlot(native)
        slot.load("/m/a.gguf", 4096)
        val counted = CompletableDeferred<Int>()
        withTimeout(5_000) {
            slot.withHandle { _ ->
                // The collector of generate's own flow counts while the stream (lock) is held.
                counted.complete(withContext(Dispatchers.Default) { slot.countTokens("abcd".toByteArray()) })
            }
        }
        assertEquals(4, counted.await())
    }
}
