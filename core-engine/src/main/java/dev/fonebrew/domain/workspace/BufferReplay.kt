package dev.fonebrew.domain.workspace

import dev.fonebrew.contracts.workspace.BufferJournalEntry
import dev.fonebrew.contracts.workspace.JournalOpType
import java.nio.charset.StandardCharsets

/**
 * Deterministic journal replay (WORKSPACE_KERNEL_SPEC.md §6.3's design rationale: "mirrors exactly
 * what a text editor's own undo/redo stack needs to replay an edit deterministically"). Pure,
 * side-effect-free -- this is the actual mechanism FB-RAT-WS-003 ("dirty buffers survive ...
 * until explicit save or discard") rests on: [RoomWorkspaceJournal.checkpoint] and a future
 * restart-recovery path both call [materialize] to turn a buffer's persisted entries back into
 * real content, never trusting an in-memory value that a forced kill could have lost.
 *
 * Content-bearing ops replay left-to-right over a UTF-8 byte buffer starting from `""`:
 * `SET_FULL_CONTENT` replaces the whole buffer (this is what a freshly-opened buffer's first
 * entry is expected to be -- its provider-loaded initial content -- so `OPEN` itself carries no
 * content). `INSERT`/`DELETE`/`REPLACE` apply their [dev.fonebrew.contracts.workspace.ByteRange]
 * against the buffer accumulated so far. `OPEN`/`CLOSE`/`DISCARD` are pure lifecycle markers with
 * no content effect, tracked instead by [ReplayedBuffer.isOpen].
 */
object BufferReplay {

    data class ReplayedBuffer(
        val content: String,
        val isOpen: Boolean,
        val lastSequence: Long,
    )

    /** Replays [entries] (assumed already sorted by `sequence` ascending) from an empty buffer. */
    fun materialize(entries: List<BufferJournalEntry>): ReplayedBuffer {
        var bytes = ByteArray(0)
        var open = false
        var lastSequence = -1L
        for (entry in entries) {
            bytes = applyContentOp(bytes, entry)
            when (entry.opType) {
                JournalOpType.OPEN -> open = true
                JournalOpType.CLOSE, JournalOpType.DISCARD -> open = false
                else -> Unit
            }
            lastSequence = entry.sequence
        }
        return ReplayedBuffer(String(bytes, StandardCharsets.UTF_8), open, lastSequence)
    }

    private fun applyContentOp(current: ByteArray, entry: BufferJournalEntry): ByteArray = when (entry.opType) {
        JournalOpType.SET_FULL_CONTENT ->
            requireNotNull(entry.content) { "SET_FULL_CONTENT entry must carry content." }.toByteArray(StandardCharsets.UTF_8)

        JournalOpType.INSERT -> {
            val range = requireNotNull(entry.byteRange) { "INSERT entry must carry a byteRange." }
            val insertBytes = requireNotNull(entry.content) { "INSERT entry must carry content." }.toByteArray(StandardCharsets.UTF_8)
            val at = range.startByte.toInt().coerceIn(0, current.size)
            current.sliceArray(0 until at) + insertBytes + current.sliceArray(at until current.size)
        }

        JournalOpType.DELETE -> {
            val range = requireNotNull(entry.byteRange) { "DELETE entry must carry a byteRange." }
            val start = range.startByte.toInt().coerceIn(0, current.size)
            val end = range.endByteExclusive.toInt().coerceIn(start, current.size)
            current.sliceArray(0 until start) + current.sliceArray(end until current.size)
        }

        JournalOpType.REPLACE -> {
            val range = requireNotNull(entry.byteRange) { "REPLACE entry must carry a byteRange." }
            val replacement = requireNotNull(entry.content) { "REPLACE entry must carry content." }.toByteArray(StandardCharsets.UTF_8)
            val start = range.startByte.toInt().coerceIn(0, current.size)
            val end = range.endByteExclusive.toInt().coerceIn(start, current.size)
            current.sliceArray(0 until start) + replacement + current.sliceArray(end until current.size)
        }

        JournalOpType.OPEN, JournalOpType.CLOSE, JournalOpType.DISCARD -> current
    }
}
