package dev.fonebrew.domain.editor.buffer

import java.util.concurrent.atomic.AtomicInteger

/**
 * The editor's text store: an **immutable** balanced tree (a treap) of text chunks, so an edit
 * costs O(log n) node allocations plus at most one chunk copy, and a snapshot is just a reference
 * (the lexer, find and diff read snapshots while the buffer moves on). Every node caches its
 * length and its line-break count, which is the line index: [lineStart] / [lineOfOffset] are
 * O(log n) descents plus a scan inside one chunk.
 *
 * The rope holds the document's **raw characters**, line terminators included. A terminator is
 * `\n`, `\r\n` or a lone `\r`. Chunk seams never fall between `\r` and `\n` (see [concat]), so
 * each chunk's break count is self-contained and the sum is exact. Offsets are UTF-16 code units
 * throughout; code point and grapheme views live in [TextUnits] / [GraphemeBreaks].
 */
class Rope private constructor(private val root: Node?) {

    val length: Int get() = root?.len ?: 0

    /** Number of line terminators; the document has `lineBreaks + 1` lines (the last may be empty). */
    val lineBreaks: Int get() = root?.breaks ?: 0
    val lineCount: Int get() = lineBreaks + 1
    val isEmpty: Boolean get() = length == 0

    operator fun get(index: Int): Char {
        require(index in 0 until length) { "index $index out of 0 until $length" }
        var n = root!!
        var i = index
        while (true) {
            val ll = n.left?.len ?: 0
            if (i < ll) { n = n.left!!; continue }
            i -= ll
            if (i < n.text.length) return n.text[i]
            i -= n.text.length
            n = n.right!!
        }
    }

    fun substring(start: Int, end: Int): String {
        require(start in 0..end && end <= length) { "range $start..$end out of 0..$length" }
        if (start == end) return ""
        val sb = StringBuilder(end - start)
        appendRange(root, start, end, sb)
        return sb.toString()
    }

    override fun toString(): String = substring(0, length)

    /** A [CharSequence] view (O(log n) per char; scan with [substring] / [toString] for bulk work). */
    fun asCharSequence(): CharSequence = View(this)

    fun insert(offset: Int, text: String): Rope = replace(offset, offset, text)
    fun delete(start: Int, end: Int): Rope = replace(start, end, "")

    fun replace(start: Int, end: Int, text: String): Rope {
        require(start in 0..end && end <= length) { "range $start..$end out of 0..$length" }
        if (start == end && text.isEmpty()) return this
        val (a0, rest) = split(root, start)
        val (_, b0) = split(rest, end - start)
        var a = a0
        var b = b0
        var ins = text
        // Keep the chunk count bounded under keystroke-sized edits: fold small neighbours in.
        if (ins.length <= SMALL_EDIT) {
            val la = lastLeaf(a)
            if (la != null && la.text.length + ins.length <= MAX_CHUNK) {
                ins = la.text + ins
                a = split(a, (a?.len ?: 0) - la.text.length).first
            }
            val fb = firstLeaf(b)
            if (fb != null && fb.text.length + ins.length <= MAX_CHUNK) {
                ins += fb.text
                b = split(b, fb.text.length).second
            }
        }
        return Rope(concat(concat(a, leaves(ins)), b))
    }

    // ---- line index -------------------------------------------------------------------------

    /** Offset of the first character of [line] (0-based). */
    fun lineStart(line: Int): Int {
        require(line in 0..lineBreaks) { "line $line out of 0..$lineBreaks" }
        if (line == 0) return 0
        var n = root
        var k = line
        var base = 0
        while (n != null) {
            val lb = n.left?.breaks ?: 0
            if (k <= lb) { n = n.left; continue }
            k -= lb
            base += n.left?.len ?: 0
            if (k <= n.localBreaks) return base + nthBreakEnd(n.text, k)
            k -= n.localBreaks
            base += n.text.length
            n = n.right
        }
        error("line index inconsistent")
    }

    /** Offset just past the line's content (before its terminator). */
    fun lineContentEnd(line: Int): Int {
        val next = if (line < lineBreaks) lineStart(line + 1) else length
        if (line >= lineBreaks) return next
        // strip the terminator that ends this line
        return when {
            next >= 2 && this[next - 1] == '\n' && this[next - 2] == '\r' -> next - 2
            else -> next - 1
        }
    }

    /** Offset past the line including its terminator (== next line's start, or the length). */
    fun lineEndWithTerminator(line: Int): Int = if (line < lineBreaks) lineStart(line + 1) else length

    fun lineText(line: Int): String = substring(lineStart(line), lineContentEnd(line))

    /** Length in chars of the terminator that ends [line] ("" for the last line). */
    fun terminatorAt(line: Int): String = substring(lineContentEnd(line), lineEndWithTerminator(line))

    /**
     * The 0-based line containing [offset]. An offset between the `\r` and `\n` of a CRLF belongs to
     * the line that terminator ends; [length] belongs to the last line.
     */
    fun lineOfOffset(offset: Int): Int {
        require(offset in 0..length) { "offset $offset out of 0..$length" }
        var n = root
        var rem = offset
        var acc = 0
        while (n != null && rem > 0) {
            val ll = n.left?.len ?: 0
            if (rem < ll) { n = n.left; continue }
            acc += n.left?.breaks ?: 0
            rem -= ll
            if (rem >= n.text.length) {
                acc += n.localBreaks
                rem -= n.text.length
                n = n.right
            } else {
                acc += breaksEndedBefore(n.text, rem)
                break
            }
        }
        return acc
    }

    fun columnOfOffset(offset: Int): Int = offset - lineStart(lineOfOffset(offset))

    /** Calls [block] with each chunk in order (for bulk scans without per-char descents). */
    fun forEachChunk(block: (String) -> Unit) = walk(root, block)

    // ---- internals --------------------------------------------------------------------------

    private class View(val rope: Rope) : CharSequence {
        override val length: Int get() = rope.length
        override fun get(index: Int): Char = rope[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = rope.substring(startIndex, endIndex)
        override fun toString(): String = rope.toString()
    }

    private class Node(val left: Node?, val right: Node?, val text: String, val prio: Int) {
        val localBreaks: Int = countBreaks(text)
        val len: Int = (left?.len ?: 0) + text.length + (right?.len ?: 0)
        val breaks: Int = (left?.breaks ?: 0) + localBreaks + (right?.breaks ?: 0)
    }

    companion object {
        /** Largest chunk the rope builds on purpose (a seam merge may exceed it by one chunk). */
        const val MAX_CHUNK = 1024
        private const val SMALL_EDIT = MAX_CHUNK / 2

        val EMPTY = Rope(null)

        private val prioSeed = AtomicInteger(0x2545F491)
        private fun nextPrio(): Int {
            // xorshift-style hash of a counter: deterministic sequence, no shared RNG state to race on
            var x = prioSeed.getAndAdd(0x9E3779B9.toInt())
            x = x xor (x ushr 16); x *= 0x45d9f3b; x = x xor (x ushr 16)
            return x
        }

        fun of(text: String): Rope = if (text.isEmpty()) EMPTY else Rope(leaves(text))

        private fun leaf(text: String): Node = Node(null, null, text, nextPrio())

        /** Chunk [text] into balanced leaves, never cutting a CRLF pair. */
        private fun leaves(text: String): Node? {
            if (text.isEmpty()) return null
            var acc: Node? = null
            var i = 0
            while (i < text.length) {
                var j = minOf(text.length, i + MAX_CHUNK)
                if (j < text.length && text[j - 1] == '\r' && text[j] == '\n') j++
                acc = merge(acc, leaf(text.substring(i, j)))
                i = j
            }
            return acc
        }

        private fun merge(a: Node?, b: Node?): Node? {
            if (a == null) return b
            if (b == null) return a
            return if (a.prio > b.prio) Node(a.left, merge(a.right, b), a.text, a.prio)
            else Node(merge(a, b.left), b.right, b.text, b.prio)
        }

        /** Concatenate, gluing a `\r` | `\n` seam into one chunk so chunks never split a CRLF. */
        private fun concat(l: Node?, r: Node?): Node? {
            if (l == null) return r
            if (r == null) return l
            val lt = lastLeaf(l)!!.text
            val ft = firstLeaf(r)!!.text
            if (lt.last() == '\r' && ft.first() == '\n') {
                val l2 = split(l, l.len - lt.length).first
                val r2 = split(r, ft.length).second
                return merge(merge(l2, leaf(lt + ft)), r2)
            }
            return merge(l, r)
        }

        private fun lastLeaf(n: Node?): Node? { var c = n ?: return null; while (c.right != null) c = c.right!!; return c }
        private fun firstLeaf(n: Node?): Node? { var c = n ?: return null; while (c.left != null) c = c.left!!; return c }

        /** Split into (first [off] chars, rest). May leave a CRLF seam: callers re-join with [concat]. */
        private fun split(n: Node?, off: Int): Pair<Node?, Node?> {
            if (n == null) return null to null
            val ll = n.left?.len ?: 0
            return when {
                off <= ll -> {
                    val (a, b) = split(n.left, off)
                    a to Node(b, n.right, n.text, n.prio)
                }
                off >= ll + n.text.length -> {
                    val (a, b) = split(n.right, off - ll - n.text.length)
                    Node(n.left, a, n.text, n.prio) to b
                }
                else -> {
                    val k = off - ll
                    merge(n.left, leaf(n.text.substring(0, k))) to merge(leaf(n.text.substring(k)), n.right)
                }
            }
        }

        private fun appendRange(n: Node?, s: Int, e: Int, sb: StringBuilder) {
            if (n == null || s >= e) return
            val ll = n.left?.len ?: 0
            if (s < ll) appendRange(n.left, s, minOf(e, ll), sb)
            val ts = ll
            val te = ll + n.text.length
            if (e > ts && s < te) sb.append(n.text, maxOf(s, ts) - ts, minOf(e, te) - ts)
            if (e > te) appendRange(n.right, maxOf(s, te) - te, e - te, sb)
        }

        private fun walk(n: Node?, block: (String) -> Unit) {
            if (n == null) return
            walk(n.left, block); block(n.text); walk(n.right, block)
        }

        /** Terminators in [t]: `\n`, `\r\n`, or `\r` not followed by `\n` (a chunk never ends mid-CRLF). */
        internal fun countBreaks(t: String): Int {
            var c = 0
            var i = 0
            while (i < t.length) {
                val ch = t[i]
                if (ch == '\n') c++
                else if (ch == '\r') { if (i + 1 < t.length && t[i + 1] == '\n') { c++; i++ } else c++ }
                i++
            }
            return c
        }

        /** Index just past the [k]th (1-based) terminator in [t]. */
        private fun nthBreakEnd(t: String, k: Int): Int {
            var c = 0
            var i = 0
            while (i < t.length) {
                val ch = t[i]
                if (ch == '\n') { c++; if (c == k) return i + 1 }
                else if (ch == '\r') {
                    if (i + 1 < t.length && t[i + 1] == '\n') { c++; if (c == k) return i + 2; i++ }
                    else { c++; if (c == k) return i + 1 }
                }
                i++
            }
            error("break $k not in chunk")
        }

        /** Terminators that END at or before [i] within [t]. */
        private fun breaksEndedBefore(t: String, i: Int): Int {
            var c = 0
            for (j in 0 until i) {
                val ch = t[j]
                if (ch == '\n') c++
                else if (ch == '\r' && !(j + 1 < t.length && t[j + 1] == '\n')) c++
            }
            return c
        }
    }
}
