package dev.fonebrew.domain.gitcore.textmerge

/**
 * Three-way line merge (diff3). Pure and deterministic.
 *
 * Algorithm: diff base->ours and base->theirs into hunks ([LineDiffEngine]); sweep both hunk lists
 * in base order, chaining hunks into a group while the next hunk starts at or before the group's
 * end (hunks that merely TOUCH conflict, as in git: editing line 2 on one side and line 3 on the
 * other is a conflict). A group touched by one side is that side's text; a group touched by both
 * is clean only if both produce identical text (an identical edit), else a conflict carrying the
 * base, ours and theirs lines. Every base line is therefore either unchanged on both sides, taken
 * from the single side that changed it, identical on both, or inside a conflict that holds both
 * versions - nothing is ever dropped silently.
 *
 * Marker styles then post-process conflicts: MERGE splits a conflict wherever ours and theirs agree
 * (then re-joins conflicts separated by 3 or fewer common lines, or by lines with no letter or
 * digit, like git's "zealous alnum" level); DIFF3 leaves them whole; ZDIFF3 trims the lines both
 * sides share at the start and end of each conflict.
 */
object TextMerge {
    fun merge(base: ByteArray, ours: ByteArray, theirs: ByteArray, options: TextMergeOptions = TextMergeOptions()): TextMergeResult {
        for ((name, b) in listOf("base" to base, "ours" to ours, "theirs" to theirs)) {
            if (b.size > options.maxInputBytes) return unmergeable(options, UnmergeableReason.TOO_LARGE, "$name is ${b.size} bytes (limit ${options.maxInputBytes})")
            if (RawText.looksBinary(b)) return unmergeable(options, UnmergeableReason.BINARY, "$name looks binary")
        }
        return mergeLines(
            RawText.splitLines(RawText.fromBytes(base)),
            RawText.splitLines(RawText.fromBytes(ours)),
            RawText.splitLines(RawText.fromBytes(theirs)),
            options,
        )
    }

    private fun unmergeable(o: TextMergeOptions, r: UnmergeableReason, d: String) = TextMergeResult(emptyList(), Unmergeable(r, d), o)

    /** An unrefined conflict while the merge is being assembled. */
    private class Raw(
        val base: List<String>?, val ours: List<String>, val theirs: List<String>,
        val baseSpan: LineSpan?, val oursSpan: LineSpan, val theirsSpan: LineSpan,
    )

    private sealed class Item {
        class Clean(val lines: List<String>, val changed: Boolean = false) : Item()
        class Conf(val c: Raw) : Item()
    }

    fun mergeLines(base: List<String>, ours: List<String>, theirs: List<String>, options: TextMergeOptions = TextMergeOptions()): TextMergeResult {
        if (ours == theirs) return TextMergeResult(if (ours.isEmpty()) emptyList() else listOf(Segment.Clean(ours)), null, options)
        if (base == ours) return TextMergeResult(if (theirs.isEmpty()) emptyList() else listOf(Segment.Clean(theirs)), null, options)
        if (base == theirs) return TextMergeResult(if (ours.isEmpty()) emptyList() else listOf(Segment.Clean(ours)), null, options)

        val ho = LineDiffEngine.hunks(base, ours)
        val ht = LineDiffEngine.hunks(base, theirs)
        val items = ArrayList<Item>()
        var pos = 0                 // next unconsumed base line
        var io = 0; var it = 0
        var deltaO = 0; var deltaT = 0   // ours/theirs index = base index + delta (for hunks already consumed)
        fun clean(lines: List<String>, changed: Boolean = false) { if (lines.isNotEmpty() || changed) items.add(Item.Clean(lines, changed)) }
        while (io < ho.size || it < ht.size) {
            val nextO = ho.getOrNull(io); val nextT = ht.getOrNull(it)
            val gs = minOf(nextO?.aStart ?: Int.MAX_VALUE, nextT?.aStart ?: Int.MAX_VALUE)
            clean(base.subList(pos, gs))
            var ge = gs
            val gO = ArrayList<Hunk>(); val gT = ArrayList<Hunk>()
            while (true) {
                val a = ho.getOrNull(io); val b = ht.getOrNull(it)
                val takeO = a != null && a.aStart <= ge
                val takeT = b != null && b.aStart <= ge
                if (!takeO && !takeT) break
                if (takeO) { gO.add(a!!); ge = maxOf(ge, a.aEnd); io++ }
                if (takeT) { gT.add(b!!); ge = maxOf(ge, b.aEnd); it++ }
            }
            val oursStart = gs + deltaO; val theirsStart = gs + deltaT
            val oursLines = side(base, ours, gs, ge, gO)
            val theirsLines = side(base, theirs, gs, ge, gT)
            deltaO += gO.sumOf { it.bLen - it.aLen }
            deltaT += gT.sumOf { it.bLen - it.aLen }
            when {
                gT.isEmpty() -> clean(oursLines, true)
                gO.isEmpty() -> clean(theirsLines, true)
                oursLines == theirsLines -> clean(oursLines, true)
                else -> items.add(Item.Conf(Raw(
                    base.subList(gs, ge), oursLines, theirsLines,
                    LineSpan(gs, ge), LineSpan(oursStart, oursStart + oursLines.size), LineSpan(theirsStart, theirsStart + theirsLines.size),
                )))
            }
            pos = ge
        }
        clean(base.subList(pos, base.size))
        return finish(items, ours, theirs, options)
    }

    /** The side's text for base range [gs, ge): its own hunk output, base lines between hunks. */
    private fun side(base: List<String>, side: List<String>, gs: Int, ge: Int, hunks: List<Hunk>): List<String> {
        if (hunks.isEmpty()) return base.subList(gs, ge)
        val out = ArrayList<String>()
        var p = gs
        for (h in hunks) {
            for (i in p until h.aStart) out.add(base[i])
            for (j in h.bStart until h.bEnd) out.add(side[j])
            p = h.aEnd
        }
        for (i in p until ge) out.add(base[i])
        return out
    }

    private fun finish(items0: List<Item>, ours: List<String>, theirs: List<String>, o: TextMergeOptions): TextMergeResult {
        var items = coalesce(items0)
        when (o.style) {
            MarkerStyle.MERGE -> { items = coalesce(refine(items)); items = simplify(items) }
            MarkerStyle.ZDIFF3 -> items = trim(items)
            MarkerStyle.DIFF3 -> {}
        }
        val segs = ArrayList<Segment>()
        var n = 0
        for (it in items) when (it) {
            is Item.Clean -> if (it.lines.isNotEmpty()) segs.add(Segment.Clean(it.lines))
            is Item.Conf -> {
                val c = it.c
                segs.add(Segment.Conflict(ConflictRegion(
                    n++, c.ours, c.theirs, c.base, c.oursSpan, c.theirsSpan, c.baseSpan,
                    needsCr(ours, c.oursSpan.start) && needsCr(theirs, c.theirsSpan.start),
                )))
            }
        }
        return TextMergeResult(segs, null, o)
    }

    private fun coalesce(items: List<Item>): List<Item> {
        val out = ArrayList<Item>()
        for (i in items) {
            val last = out.lastOrNull()
            if (i is Item.Clean && last is Item.Clean) out[out.size - 1] = Item.Clean(last.lines + i.lines, last.changed || i.changed) else out.add(i)
        }
        return out
    }

    /**
     * git's rule: the marker lines end in CRLF when the line before the conflict (or the first line,
     * when the conflict starts the file) ends in CRLF on BOTH sides. A side that is empty, or whose
     * reference line has no terminator at all, does not object.
     */
    private fun needsCr(lines: List<String>, start: Int): Boolean {
        val l = lines.getOrNull(if (start > 0) start - 1 else 0) ?: return true
        return !l.endsWith("\n") || RawText.isCrlf(l)
    }

    /** Splits each conflict wherever ours and theirs agree. */
    private fun refine(items: List<Item>): List<Item> {
        val out = ArrayList<Item>()
        for (it in items) {
            if (it !is Item.Conf || it.c.ours.isEmpty() || it.c.theirs.isEmpty()) { out.add(it); continue }
            val c = it.c
            val hunks = LineDiffEngine.hunks(c.ours, c.theirs)
            if (hunks.isEmpty()) { out.add(Item.Clean(c.ours, true)); continue }
            if (hunks.size == 1 && hunks[0].aLen == c.ours.size && hunks[0].bLen == c.theirs.size) { out.add(it); continue }
            var po = 0
            for (h in hunks) {
                if (h.aStart > po) out.add(Item.Clean(c.ours.subList(po, h.aStart)))
                out.add(Item.Conf(Raw(
                    null, c.ours.subList(h.aStart, h.aEnd), c.theirs.subList(h.bStart, h.bEnd), null,
                    LineSpan(c.oursSpan.start + h.aStart, c.oursSpan.start + h.aEnd),
                    LineSpan(c.theirsSpan.start + h.bStart, c.theirsSpan.start + h.bEnd),
                )))
                po = h.aEnd
            }
            if (po < c.ours.size) out.add(Item.Clean(c.ours.subList(po, c.ours.size)))
        }
        return out
    }

    private fun hasAlnum(l: String) = l.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    /** Re-joins conflicts separated by at most 3 common lines, or by common lines without any letter/digit. */
    private fun simplify(items: List<Item>): List<Item> {
        val out = ArrayList<Item>()
        var i = 0
        while (i < items.size) {
            val cur = items[i]
            if (cur is Item.Conf) {
                var c = cur.c
                while (i + 2 < items.size && items[i + 1] is Item.Clean && items[i + 2] is Item.Conf) {
                    val run = (items[i + 1] as Item.Clean).lines
                    if ((items[i + 1] as Item.Clean).changed || !(run.size <= 3 || run.none { hasAlnum(it) })) break
                    val d = (items[i + 2] as Item.Conf).c
                    c = Raw(
                        if (c.base != null && d.base != null) c.base + run + d.base else null,
                        c.ours + run + d.ours, c.theirs + run + d.theirs,
                        if (c.baseSpan != null && d.baseSpan != null) LineSpan(c.baseSpan.start, d.baseSpan.end) else null,
                        LineSpan(c.oursSpan.start, d.oursSpan.end), LineSpan(c.theirsSpan.start, d.theirsSpan.end),
                    )
                    i += 2
                }
                out.add(Item.Conf(c))
            } else out.add(cur)
            i++
        }
        return out
    }

    /** zdiff3: move the shared leading and trailing lines of ours/theirs out of each conflict. */
    private fun trim(items: List<Item>): List<Item> {
        val out = ArrayList<Item>()
        for (it in items) {
            if (it !is Item.Conf) { out.add(it); continue }
            val c = it.c
            var pre = 0
            while (pre < c.ours.size && pre < c.theirs.size && c.ours[pre] == c.theirs[pre]) pre++
            var suf = 0
            while (suf < c.ours.size - pre && suf < c.theirs.size - pre && c.ours[c.ours.size - 1 - suf] == c.theirs[c.theirs.size - 1 - suf]) suf++
            if (pre == 0 && suf == 0) { out.add(it); continue }
            if (pre > 0) out.add(Item.Clean(c.ours.subList(0, pre)))
            val ou = c.ours.subList(pre, c.ours.size - suf); val th = c.theirs.subList(pre, c.theirs.size - suf)
            if (ou.isNotEmpty() || th.isNotEmpty()) out.add(Item.Conf(Raw(
                c.base, ou, th, c.baseSpan,
                LineSpan(c.oursSpan.start + pre, c.oursSpan.end - suf), LineSpan(c.theirsSpan.start + pre, c.theirsSpan.end - suf),
            )))
            if (suf > 0) out.add(Item.Clean(c.ours.subList(c.ours.size - suf, c.ours.size)))
        }
        return out
    }
}
