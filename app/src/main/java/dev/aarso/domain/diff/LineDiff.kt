package dev.aarso.domain.diff

/**
 * A small, dependency-free line diff that emits **standard unified diff** — the
 * "review the change before you commit" surface for the coding assistant
 * (docs/design/coding-assistant.md, build-order step 3). Pure Kotlin: no Android,
 * no network, no model. Because the output is the same `--- / +++ / @@` format git
 * and every patch tool read, a proposed edit is legible and portable — never a
 * black box the user is asked to trust. That is the thesis (legibility, human in
 * the loop) applied to code.
 *
 * Algorithm: longest-common-subsequence over whole lines (the classic, exact diff),
 * with the common prefix/suffix trimmed first so the O(n·m) table only ever covers
 * the genuinely-changed region — a one-line edit in a large file stays cheap. A
 * safety valve falls back to a whole-file replace if the changed region is still
 * pathologically large, so the table can never blow a phone's memory.
 *
 * Lines are compared without their trailing newline; a final newline is not treated
 * as an extra empty line, and the "\ No newline at end of file" marker is not
 * emitted (a review tool, not a byte-exact patcher).
 */
object LineDiff {

    /** One line of a diff: unchanged [Context], inserted [Add], or deleted [Remove]. */
    sealed interface Line {
        val text: String
        data class Context(override val text: String) : Line
        data class Add(override val text: String) : Line
        data class Remove(override val text: String) : Line
    }

    /**
     * A contiguous changed region with its surrounding context, addressed exactly as
     * a unified-diff `@@` header: 1-based start lines and line counts on each side.
     * A pure insertion has [oldStart]==0/[oldCount]==0 (a new file); a pure deletion
     * has [newStart]==0/[newCount]==0.
     */
    data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<Line>,
    )

    /** A glanceable summary (for a "+12 −3" badge on a proposed edit). */
    data class Stat(val added: Int, val removed: Int) {
        val changed: Boolean get() = added > 0 || removed > 0
    }

    /** An aligned line with the 0-based index it occupies on each side (-1 = absent). */
    private data class Op(val line: Line, val oldIdx: Int, val newIdx: Int)

    /** The change as hunks (empty when [old] and [new] are identical). */
    fun diff(old: String, new: String, context: Int = 3): List<Hunk> =
        hunks(align(splitLines(old), splitLines(new)), context.coerceAtLeast(0))

    /** Added/removed line counts, without building hunks. */
    fun stat(old: String, new: String): Stat {
        var added = 0
        var removed = 0
        for (op in align(splitLines(old), splitLines(new))) {
            when (op.line) {
                is Line.Add -> added++
                is Line.Remove -> removed++
                else -> {}
            }
        }
        return Stat(added, removed)
    }

    /**
     * The full unified-diff text (or "" when nothing changed). [oldPath]/[newPath]
     * become the `---`/`+++` headers — pass e.g. `a/<path>` and `b/<path>` to match
     * git exactly.
     */
    fun unified(
        old: String,
        new: String,
        oldPath: String = "a",
        newPath: String = "b",
        context: Int = 3,
    ): String {
        val hunks = diff(old, new, context)
        if (hunks.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("--- ").append(oldPath).append('\n')
        sb.append("+++ ").append(newPath).append('\n')
        for (h in hunks) {
            sb.append("@@ -").append(range(h.oldStart, h.oldCount))
                .append(" +").append(range(h.newStart, h.newCount)).append(" @@\n")
            for (l in h.lines) {
                sb.append(
                    when (l) {
                        is Line.Context -> ' '
                        is Line.Add -> '+'
                        is Line.Remove -> '-'
                    },
                ).append(l.text).append('\n')
            }
        }
        return sb.toString()
    }

    /** Unified-diff range: the count is omitted when it is 1, exactly as git prints. */
    private fun range(start: Int, count: Int): String = if (count == 1) "$start" else "$start,$count"

    private fun splitLines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val parts = s.split('\n')
        // a trailing newline yields a final "" element — it is not a real line
        return if (parts.last().isEmpty()) parts.subList(0, parts.size - 1) else parts
    }

    /** Align two line lists into one ordered op stream (context/add/remove). */
    private fun align(a: List<String>, b: List<String>): List<Op> {
        // Trim the common prefix/suffix so the LCS table only spans the changed core.
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++

        val out = ArrayList<Op>(a.size + b.size)
        for (k in 0 until p) out += Op(Line.Context(a[k]), k, k)
        out += lcs(a.subList(p, a.size - s), b.subList(p, b.size - s), p)
        for (k in 0 until s) {
            val oi = a.size - s + k
            val ni = b.size - s + k
            out += Op(Line.Context(a[oi]), oi, ni)
        }
        return out
    }

    /** LCS alignment of the already-trimmed middle; side indices offset by [off]. */
    private fun lcs(a: List<String>, b: List<String>, off: Int): List<Op> {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return List(m) { Op(Line.Add(b[it]), -1, off + it) }
        if (m == 0) return List(n) { Op(Line.Remove(a[it]), off + it, -1) }
        // Safety valve: never allocate a pathological table on-device — replace wholesale.
        if (n.toLong() * m.toLong() > MAX_CELLS) {
            return List(n) { Op(Line.Remove(a[it]), off + it, -1) } +
                List(m) { Op(Line.Add(b[it]), -1, off + it) }
        }

        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val out = ArrayList<Op>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += Op(Line.Context(a[i]), off + i, off + j); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += Op(Line.Remove(a[i]), off + i, -1); i++ }
                else -> { out += Op(Line.Add(b[j]), -1, off + j); j++ }
            }
        }
        while (i < n) { out += Op(Line.Remove(a[i]), off + i, -1); i++ }
        while (j < m) { out += Op(Line.Add(b[j]), -1, off + j); j++ }
        return out
    }

    /** Group changes into hunks, padding each with [context] lines and merging the
     *  ones whose context regions would touch or overlap. */
    private fun hunks(ops: List<Op>, context: Int): List<Hunk> {
        val n = ops.size
        val intervals = ArrayList<IntArray>()
        for (c in 0 until n) {
            if (ops[c].line is Line.Context) continue
            val lo = maxOf(0, c - context)
            val hi = minOf(n - 1, c + context)
            val last = intervals.lastOrNull()
            if (last != null && lo <= last[1] + 1) last[1] = maxOf(last[1], hi)
            else intervals += intArrayOf(lo, hi)
        }
        return intervals.map { toHunk(ops, it[0], it[1]) }
    }

    private fun toHunk(ops: List<Op>, lo: Int, hi: Int): Hunk {
        val slice = ops.subList(lo, hi + 1)
        val oldStart = slice.firstOrNull { it.oldIdx >= 0 }?.let { it.oldIdx + 1 } ?: 0
        val newStart = slice.firstOrNull { it.newIdx >= 0 }?.let { it.newIdx + 1 } ?: 0
        return Hunk(
            oldStart = oldStart,
            oldCount = slice.count { it.oldIdx >= 0 },
            newStart = newStart,
            newCount = slice.count { it.newIdx >= 0 },
            lines = slice.map { it.line },
        )
    }

    /** ~8M cells ≈ 32 MB of ints — well past any sane single-file review. */
    private const val MAX_CELLS = 8_000_000L
}
