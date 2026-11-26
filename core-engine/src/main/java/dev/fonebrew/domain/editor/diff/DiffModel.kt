package dev.fonebrew.domain.editor.diff

import dev.fonebrew.domain.diff.Decision
import dev.fonebrew.domain.diff.LineDiff
import dev.fonebrew.domain.diff.ReviewSession

/** What a row is, with a glyph and a label so state never rides on hue alone. */
enum class RowKind(val glyph: Char, val label: String) {
    CONTEXT(' ', "unchanged"), ADD('+', "added"), REMOVE('-', "removed"),
}

/** One row of the unified view. [oldNo] / [newNo] are 1-based gutter numbers (null where the side has no line). */
data class UnifiedRow(
    val kind: RowKind,
    val oldNo: Int?,
    val newNo: Int?,
    val text: String,
    val hunkId: String?,
    val spans: List<Span> = emptyList(),
)

/** One side's cell in the side-by-side view. */
data class Cell(val lineNo: Int, val text: String, val kind: RowKind, val spans: List<Span> = emptyList())

/** [left] is the old side, [right] the new; a null cell is a blank filler row opposite an unpaired line. */
data class SideRow(val left: Cell?, val right: Cell?, val hunkId: String?)

/**
 * A maximal run of changed lines. [index] is the position in [DiffModel.hunks] and equals the index of
 * the same hunk in a zero-context [ReviewSession]. [id] is content-derived (old-side anchor + hash of the
 * removed/added lines), so it survives re-folding and unrelated edits elsewhere in the file.
 * Row ranges are inclusive indices into [DiffModel.unified] and [DiffModel.side].
 */
data class DiffHunk(
    val id: String,
    val index: Int,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val firstRow: Int,
    val lastRow: Int,
    val firstSideRow: Int,
    val lastSideRow: Int,
)

/**
 * The display model of one file's change: unified rows, side-by-side rows, hunks. Built on [LineDiff]
 * (run with enough context to get one ordered stream), so it agrees with the unified diff the review
 * sheet already prints. Pairing of changed lines is positional within a hunk (the i-th removed line
 * against the i-th added line); the rest get blank fillers. A change that is only the final newline
 * is invisible to [LineDiff]; [eofNewlineChanged] reports it.
 */
class DiffModel private constructor(
    val oldLines: List<String>,
    val newLines: List<String>,
    val oldEndsWithNewline: Boolean,
    val newEndsWithNewline: Boolean,
    val unified: List<UnifiedRow>,
    val side: List<SideRow>,
    val hunks: List<DiffHunk>,
) {
    val eofNewlineChanged: Boolean get() = oldEndsWithNewline != newEndsWithNewline
    val isIdentical: Boolean get() = hunks.isEmpty() && !eofNewlineChanged

    fun hunkIndex(id: String): Int = hunks.indexOfFirst { it.id == id }

    /** Gutter digit widths (old, new) so a renderer can right-align numbers. */
    fun gutterWidths(): Pair<Int, Int> = oldLines.size.toString().length to newLines.size.toString().length

    /** Lines of the old side as reconstructed from the rows (a check that nothing was dropped). */
    fun oldFromRows(): List<String> = unified.filter { it.kind != RowKind.ADD }.map { it.text }
    fun newFromRows(): List<String> = unified.filter { it.kind != RowKind.REMOVE }.map { it.text }

    /**
     * The text after applying the hunks decided [Decision.APPROVED] in [decisions] (keyed by hunk id;
     * absent or PENDING counts as not applied, exactly like [ReviewSession.applied]). Unlike that
     * method this tracks the final newline, so all-approved reproduces the new text byte for byte.
     */
    fun apply(decisions: Map<String, Decision>, eofFromNew: Boolean = hunks.all { decisions[it.id] == Decision.APPROVED }): String {
        val out = ArrayList<String>()
        var lastWasHunkApproved: Boolean? = null
        for (r in unified) {
            val h = r.hunkId
            if (h == null) { out += r.text; lastWasHunkApproved = null; continue }
            val approved = decisions[h] == Decision.APPROVED
            if (approved && r.kind == RowKind.ADD) out += r.text
            if (!approved && r.kind == RowKind.REMOVE) out += r.text
            lastWasHunkApproved = approved
        }
        if (out.isEmpty()) return ""
        val endNl = when {
            unified.isEmpty() -> false
            unified.last().hunkId != null -> if (lastWasHunkApproved == true) newEndsWithNewline else oldEndsWithNewline
            else -> if (eofFromNew) newEndsWithNewline else oldEndsWithNewline
        }
        return out.joinToString("\n") + if (endNl) "\n" else ""
    }

    fun applyAll(): String = apply(hunks.associate { it.id to Decision.APPROVED }, eofFromNew = true)

    /** The text with every hunk applied except [hunkId], which is reverted to the old lines. */
    fun revert(hunkId: String): String =
        apply(hunks.associate { it.id to if (it.id == hunkId) Decision.REJECTED else Decision.APPROVED }, eofFromNew = true)

    /** True if this model's hunks line up 1:1 with a zero-context [ReviewSession] over the same texts. */
    fun matches(session: ReviewSession): Boolean =
        session.hunks.size == hunks.size && session.hunks.zip(hunks).all { (s, h) ->
            s.oldCount == h.oldCount && s.newCount == h.newCount &&
                (s.oldCount == 0 || s.oldStart == h.oldStart) && (s.newCount == 0 || s.newStart == h.newStart)
        }

    companion object {
        private fun splitLines(s: String): List<String> {
            if (s.isEmpty()) return emptyList()
            val parts = s.split('\n')
            return if (parts.last().isEmpty()) parts.subList(0, parts.size - 1) else parts
        }

        fun of(old: String, new: String, intraLine: Boolean = true): DiffModel {
            val oldLines = splitLines(old)
            val newLines = splitLines(new)
            val ctx = oldLines.size + newLines.size + 1
            val lines: List<LineDiff.Line> = LineDiff.diff(old, new, ctx).firstOrNull()?.lines
                ?: oldLines.map { LineDiff.Line.Context(it) }
            val unified = ArrayList<UnifiedRow>(lines.size)
            val side = ArrayList<SideRow>(lines.size)
            val hunks = ArrayList<DiffHunk>()
            val seenIds = HashSet<String>()
            var o = 0
            var nw = 0
            var k = 0
            while (k < lines.size) {
                val l = lines[k]
                if (l is LineDiff.Line.Context) {
                    o++; nw++
                    unified += UnifiedRow(RowKind.CONTEXT, o, nw, l.text, null)
                    side += SideRow(Cell(o, l.text, RowKind.CONTEXT), Cell(nw, l.text, RowKind.CONTEXT), null)
                    k++
                    continue
                }
                // a maximal run of non-context lines: removes first, then adds (LineDiff's order)
                val rem = ArrayList<String>(); val add = ArrayList<String>()
                var e = k
                while (e < lines.size && lines[e] !is LineDiff.Line.Context) {
                    val x = lines[e]
                    if (x is LineDiff.Line.Remove) rem += x.text else add += x.text
                    e++
                }
                val oldAnchor = o
                val newAnchor = nw
                val id = uniqueId(hash(oldAnchor, rem, add), seenIds)
                val firstRow = unified.size
                val firstSide = side.size
                val pairs = minOf(rem.size, add.size)
                val spans = Array(pairs) { p ->
                    if (intraLine) IntraLineDiff.spans(rem[p], add[p]) else emptyList<Span>() to emptyList()
                }
                for ((p, t) in rem.withIndex()) {
                    unified += UnifiedRow(RowKind.REMOVE, oldAnchor + p + 1, null, t, id, if (p < pairs) spans[p].first else emptyList())
                }
                for ((p, t) in add.withIndex()) {
                    unified += UnifiedRow(RowKind.ADD, null, newAnchor + p + 1, t, id, if (p < pairs) spans[p].second else emptyList())
                }
                for (p in 0 until maxOf(rem.size, add.size)) {
                    side += SideRow(
                        if (p < rem.size) Cell(oldAnchor + p + 1, rem[p], RowKind.REMOVE, if (p < pairs) spans[p].first else emptyList()) else null,
                        if (p < add.size) Cell(newAnchor + p + 1, add[p], RowKind.ADD, if (p < pairs) spans[p].second else emptyList()) else null,
                        id,
                    )
                }
                hunks += DiffHunk(
                    id = id, index = hunks.size,
                    oldStart = if (rem.isEmpty()) 0 else oldAnchor + 1, oldCount = rem.size,
                    newStart = if (add.isEmpty()) 0 else newAnchor + 1, newCount = add.size,
                    firstRow = firstRow, lastRow = unified.size - 1,
                    firstSideRow = firstSide, lastSideRow = side.size - 1,
                )
                o += rem.size; nw += add.size
                k = e
            }
            return DiffModel(oldLines, newLines, old.endsWith("\n"), new.endsWith("\n"), unified, side, hunks)
        }

        private fun hash(anchor: Int, rem: List<String>, add: List<String>): String {
            var h = -0x340d631b7bdddcdbL   // FNV-1a 64 offset basis
            fun mix(s: String) { for (c in s) { h = (h xor c.code.toLong()) * 0x100000001b3L }; h = (h xor 0x0AL) * 0x100000001b3L }
            mix(anchor.toString()); for (r in rem) mix("-$r"); for (a in add) mix("+$a")
            return "h" + java.lang.Long.toHexString(h).takeLast(12)
        }

        private fun uniqueId(base: String, seen: MutableSet<String>): String {
            var id = base
            var n = 2
            while (!seen.add(id)) { id = "$base-$n"; n++ }
            return id
        }
    }
}
