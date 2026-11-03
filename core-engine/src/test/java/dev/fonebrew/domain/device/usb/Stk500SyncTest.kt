package dev.fonebrew.domain.device.usb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A link that records *how* it was asked, not just what was written — the reset/sync fix is about
 * timing and budgets, and those are invisible to a fake that only replays bytes.
 *
 * Deliberately not named `FakeLink`: `Stk500Test` already declares a file-private class by that
 * name in this same package, and two file-private classes with one name collide as JVM classes.
 */
private class RecordingLink(script: List<Int>) : SerialLink {
    val written = ArrayList<Byte>()
    val dtr = ArrayList<Boolean>()
    val readTimeouts = ArrayList<Long>()
    var drains = 0
    private val buf = ArrayDeque<Byte>().apply { script.forEach { add(it.toByte()) } }
    override suspend fun write(bytes: ByteArray) { written.addAll(bytes.toList()) }
    override suspend fun read(n: Int): ByteArray = ByteArray(n) {
        if (buf.isEmpty()) error("read past end"); buf.removeFirst()
    }
    override suspend fun read(n: Int, timeoutMs: Long): ByteArray {
        readTimeouts += timeoutMs
        return read(n)
    }
    override suspend fun drain() { drains++ }
    override suspend fun setDtr(asserted: Boolean) { dtr.add(asserted) }
}

class Stk500SyncTest {

    @Test fun `the reset waits before the first sync byte`() = runBlocking {
        val delays = ArrayList<Long>()
        val link = RecordingLink(listOf(0x14, 0x10))
        Stk500(link, delayMs = { delays += it }).connect(retries = 1)
        // Pulse then settle — the shipped version had neither, which is why sync landed while the
        // chip was still in reset.
        assertEquals(listOf(Stk500.RESET_PULSE_MS, Stk500.RESET_SETTLE_MS), delays)
        assertEquals(listOf(true, false), link.dtr)
    }

    @Test fun `sync reads on its own short budget, not the transport default`() = runBlocking {
        val link = RecordingLink(listOf(0x14, 0x10))
        Stk500(link, delayMs = {}).connect(retries = 1)
        assertTrue("sync must ask for a bounded read", link.readTimeouts.isNotEmpty())
        assertTrue(link.readTimeouts.all { it == Stk500.SYNC_TIMEOUT_MS })
    }

    @Test fun `a failed attempt drains and retries within the same reset`() = runBlocking {
        // Garbage where INSYNC belongs, then a real INSYNC/OK: attempt 1 fails, attempt 2 lands.
        val link = RecordingLink(listOf(0x00, 0x14, 0x10))
        Stk500(link, delayMs = {}).connect(retries = 2)
        assertEquals("each attempt clears stale bytes first", 2, link.drains)
        assertEquals("one reset for the whole sync phase, not one per attempt", listOf(true, false), link.dtr)
    }

    @Test fun `only the handshake overrides the read budget`() = runBlocking {
        val link = RecordingLink(listOf(0x14, 0x10, 0x14, 0x1E, 0x95, 0x0F, 0x10))
        val stk = Stk500(link, delayMs = {})
        stk.connect(retries = 1)
        val afterSync = link.readTimeouts.size
        stk.readSignature()
        // A signature read (like a page write) wants the transport's own longer default; borrowing
        // the sync budget would make it fail on a board that is merely slow rather than absent.
        assertEquals(afterSync, link.readTimeouts.size)
    }
}
