package dev.fonebrew.domain.editor.edit

import dev.fonebrew.domain.editor.buffer.Rope

/** Replace `[start, end)` of the document as it was BEFORE the transaction with [text]. */
data class TextEdit(val start: Int, val end: Int, val text: String) {
    val removedLength: Int get() = end - start
    val delta: Int get() = text.length - (end - start)
}

/** Which side an offset sitting exactly on an insertion point takes when mapped through an edit. */
enum class Bias { BEFORE, AFTER }

sealed interface TransactionError {
    data class OutOfBounds(val edit: TextEdit, val length: Int) : TransactionError
    data class Overlap(val a: TextEdit, val b: TextEdit) : TransactionError
    data class Unsorted(val a: TextEdit, val b: TextEdit) : TransactionError
    object ReadOnly : TransactionError
}

/**
 * A set of edits applied atomically. Edits are in the coordinates of the document before the
 * transaction, sorted, and non-overlapping (two zero-width inserts at one offset are allowed and keep
 * list order). Its [invert] is exact by construction, which is what makes undo lossless.
 */
class Transaction(val edits: List<TextEdit>) {
    val isNoop: Boolean get() = edits.all { it.start == it.end && it.text.isEmpty() }
    val changedChars: Int get() = edits.sumOf { it.text.length + it.removedLength }

    fun validate(docLength: Int): TransactionError? {
        for (e in edits) if (e.start < 0 || e.end < e.start || e.end > docLength) return TransactionError.OutOfBounds(e, docLength)
        for (i in 1 until edits.size) {
            val a = edits[i - 1]; val b = edits[i]
            if (b.start < a.start) return TransactionError.Unsorted(a, b)
            if (b.start < a.end) return TransactionError.Overlap(a, b)
        }
        return null
    }

    /** Map [offset] (pre-transaction coordinates) to the post-transaction document. */
    fun map(offset: Int, bias: Bias): Int {
        var shift = 0
        for (e in edits) {
            when {
                offset < e.start -> return offset + shift
                offset == e.start && e.start == e.end -> {
                    if (bias == Bias.BEFORE) return offset + shift
                    shift += e.text.length   // AFTER: step past the insertion and keep going
                }
                offset == e.start -> return offset + shift
                offset < e.end -> return e.start + shift + if (bias == Bias.AFTER) e.text.length else 0
                else -> shift += e.delta
            }
        }
        return offset + shift
    }

    /** Apply to [rope]; returns the new rope and the exact inverse transaction. */
    fun applyTo(rope: Rope): Pair<Rope, Transaction> {
        var r = rope
        val inv = ArrayList<TextEdit>(edits.size)
        var shift = 0
        for (e in edits) {
            val s = e.start + shift
            inv += TextEdit(s, s + e.text.length, rope.substring(e.start, e.end))
            shift += e.delta
        }
        for (i in edits.indices.reversed()) r = r.replace(edits[i].start, edits[i].end, edits[i].text)
        return r to Transaction(inv)
    }

    companion object {
        val EMPTY = Transaction(emptyList())

        /** Merge overlapping or touching pure deletions (used by delete commands over many cursors). */
        fun unionDeletes(ranges: List<IntRange>): List<TextEdit> {
            val sorted = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
            val out = ArrayList<IntArray>()
            for (r in sorted) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last[1]) last[1] = maxOf(last[1], r.last + 1)
                else out += intArrayOf(r.first, r.last + 1)
            }
            return out.map { TextEdit(it[0], it[1], "") }
        }
    }
}
