package dev.fonebrew.data.terminal

import dev.fonebrew.domain.terminal.SnapshotMeta
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalHistoryStoreTest {
    private fun meta(t: Long) = SnapshotMeta("t", null, t, "ended")
    private fun store(max: Int = 3, body: Int = 100) =
        TerminalHistoryStore(Files.createTempDirectory("hist").toFile(), max, body)

    @Test fun `save list read delete round trip, newest first`() {
        val s = store()
        assertTrue(s.save("a", meta(1), "one\n")); assertTrue(s.save("b", meta(2), "two\n"))
        assertEquals(listOf("b", "a"), s.list().map { it.slot })
        assertEquals("two\n", s.read("b")!!.text)
        assertTrue(s.delete("b")); assertNull(s.read("b"))
    }

    @Test fun `same slot is overwritten, not accumulated`() {
        val s = store()
        s.save("a", meta(1), "x\n"); s.save("a", meta(2), "y\n")
        assertEquals(1, s.list().size); assertEquals("y\n", s.read("a")!!.text)
    }

    @Test fun `entry count is bounded and each file is size capped`() {
        val s = store(max = 3, body = 100)
        for (i in 1..8) { s.save("s$i", meta(i.toLong()), "z".repeat(5000) + "\n"); Thread.sleep(5) }
        assertTrue(s.list().size <= 3)
        s.list().forEach { assertTrue(it.sizeBytes < 100 + 1024) }
    }

    @Test fun `a slot cannot escape the directory and deleteAll empties it`() {
        val s = store()
        s.save("../../evil", meta(1), "x\n")
        assertEquals(1, s.list().size)
        s.deleteAll(); assertEquals(0, s.list().size)
    }
}
