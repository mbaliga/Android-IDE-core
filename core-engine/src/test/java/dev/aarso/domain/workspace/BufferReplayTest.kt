package dev.aarso.domain.workspace

import dev.aarso.contracts.workspace.BufferJournalEntry
import dev.aarso.contracts.workspace.ByteRange
import dev.aarso.contracts.workspace.JournalOpType
import dev.aarso.contracts.workspace.OriginKind
import dev.aarso.contracts.workspace.ResourceProvider
import dev.aarso.contracts.workspace.ResourceUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BufferReplayTest {

    private val uri = ResourceUri(ResourceProvider.LOCAL, "local://a.txt")

    private fun entry(
        seq: Long, op: JournalOpType, content: String? = null, range: ByteRange? = null
    ) = BufferJournalEntry(
        sequence = seq, bufferId = "buf1", resourceUri = uri, opType = op,
        recordedAtUtc = Instant.parse("2026-08-07T00:00:00Z"), originKind = OriginKind.HUMAN,
        originPrincipal = "user:test", byteRange = range, content = content
    )

    @Test
    fun `empty entry list materializes to empty, closed`() {
        val result = BufferReplay.materialize(emptyList())
        assertEquals("", result.content)
        assertFalse(result.isOpen)
        assertEquals(-1L, result.lastSequence)
    }

    @Test
    fun `OPEN then SET_FULL_CONTENT materializes the loaded content, still open`() {
        val entries = listOf(
            entry(0, JournalOpType.OPEN),
            entry(1, JournalOpType.SET_FULL_CONTENT, content = "hello world"),
        )
        val result = BufferReplay.materialize(entries)
        assertEquals("hello world", result.content)
        assertTrue(result.isOpen)
        assertEquals(1L, result.lastSequence)
    }

    @Test
    fun `INSERT splices content at the given byte offset`() {
        val entries = listOf(
            entry(0, JournalOpType.OPEN),
            entry(1, JournalOpType.SET_FULL_CONTENT, content = "helloworld"),
            entry(2, JournalOpType.INSERT, content = " ", range = ByteRange(5, 5)),
        )
        assertEquals("hello world", BufferReplay.materialize(entries).content)
    }

    @Test
    fun `DELETE removes the given byte range`() {
        val entries = listOf(
            entry(0, JournalOpType.OPEN),
            entry(1, JournalOpType.SET_FULL_CONTENT, content = "hello world"),
            entry(2, JournalOpType.DELETE, range = ByteRange(5, 11)),
        )
        assertEquals("hello", BufferReplay.materialize(entries).content)
    }

    @Test
    fun `REPLACE substitutes the given byte range`() {
        val entries = listOf(
            entry(0, JournalOpType.OPEN),
            entry(1, JournalOpType.SET_FULL_CONTENT, content = "hello world"),
            entry(2, JournalOpType.REPLACE, content = "there", range = ByteRange(6, 11)),
        )
        assertEquals("hello there", BufferReplay.materialize(entries).content)
    }

    @Test
    fun `CLOSE marks the buffer no longer open, content unchanged`() {
        val entries = listOf(
            entry(0, JournalOpType.OPEN),
            entry(1, JournalOpType.SET_FULL_CONTENT, content = "final"),
            entry(2, JournalOpType.CLOSE),
        )
        val result = BufferReplay.materialize(entries)
        assertEquals("final", result.content)
        assertFalse(result.isOpen)
    }

    @Test
    fun `DISCARD also marks the buffer no longer open`() {
        val entries = listOf(
            entry(0, JournalOpType.OPEN),
            entry(1, JournalOpType.SET_FULL_CONTENT, content = "scratch"),
            entry(2, JournalOpType.DISCARD),
        )
        assertFalse(BufferReplay.materialize(entries).isOpen)
    }

    @Test
    fun `a full edit sequence replays deterministically to the exact expected result`() {
        // "The quick fox" -- insert " brown" right before the trailing " fox" (byte 9) to get
        // "The quick brown fox" (19 bytes), then replace bytes [16,19) ("fox") with "jumps".
        val entries = listOf(
            entry(0, JournalOpType.OPEN),
            entry(1, JournalOpType.SET_FULL_CONTENT, content = "The quick fox"),
            entry(2, JournalOpType.INSERT, content = " brown", range = ByteRange(9, 9)),
            entry(3, JournalOpType.REPLACE, content = "jumps", range = ByteRange(16, 19)),
        )
        assertEquals("The quick brown jumps", BufferReplay.materialize(entries).content)
    }
}
