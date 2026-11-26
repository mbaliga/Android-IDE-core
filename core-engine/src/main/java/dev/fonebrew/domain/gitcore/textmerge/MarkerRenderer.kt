package dev.fonebrew.domain.gitcore.textmerge

internal object MarkerRenderer {
    fun render(
        segments: List<Segment>, style: MarkerStyle, size: Int,
        oursLabel: String, baseLabel: String, theirsLabel: String,
    ): String {
        val sb = StringBuilder()
        for (s in segments) when (s) {
            is Segment.Clean -> s.lines.forEach { sb.append(it) }
            is Segment.Conflict -> renderRegion(sb, s.region, style, size, oursLabel, baseLabel, theirsLabel)
        }
        return sb.toString()
    }

    fun marker(ch: Char, size: Int, label: String?, eol: String): String =
        ch.toString().repeat(size) + (if (label.isNullOrEmpty()) "" else " $label") + eol

    private fun appendLines(sb: StringBuilder, lines: List<String>, eol: String) {
        for (l in lines) sb.append(l)
        if (lines.isNotEmpty() && !lines.last().endsWith("\n")) sb.append(eol)
    }

    fun renderRegion(
        sb: StringBuilder, r: ConflictRegion, style: MarkerStyle, size: Int,
        oursLabel: String, baseLabel: String, theirsLabel: String,
    ) {
        val eol = if (r.crlf) "\r\n" else "\n"
        sb.append(marker('<', size, oursLabel, eol))
        appendLines(sb, r.ours, eol)
        if (style != MarkerStyle.MERGE && r.base != null) {
            sb.append(marker('|', size, baseLabel, eol))
            appendLines(sb, r.base, eol)
        }
        sb.append(marker('=', size, null, eol))
        appendLines(sb, r.theirs, eol)
        sb.append(marker('>', size, theirsLabel, eol))
    }
}

/**
 * Reads conflict markers back out of a file, so text edited by the user, a terminal or an agent
 * can re-enter the resolver. Strict: a structure that is not exactly one well-formed conflict per
 * `<<<<<<<` ... `>>>>>>>` pair is a [MarkerParseError], never a best guess.
 *
 * A marker line is `size` copies of the marker char followed by end-of-line or a space and a
 * label. A lone `=======` OUTSIDE a conflict is ordinary text (Markdown underlines) and is kept;
 * a stray `|||||||` or `>>>>>>>` outside a conflict is an error.
 */
object ConflictMarkers {
    private data class Mark(val ch: Char, val label: String?, val crlf: Boolean)

    private fun classify(line: String, size: Int): Mark? {
        val body = line.removeSuffix("\n").removeSuffix("\r")
        if (body.length < size) return null
        val ch = body[0]
        if (ch != '<' && ch != '|' && ch != '=' && ch != '>') return null
        for (i in 0 until size) if (body[i] != ch) return null
        if (body.length == size) return Mark(ch, null, line.endsWith("\r\n"))
        if (body[size] != ' ') return null           // a longer run of the same char is not a marker of this size
        val label = body.substring(size + 1)
        if (ch == '=') return null                    // a separator carries no label; "======= x" is text
        return Mark(ch, label, line.endsWith("\r\n"))
    }

    /** Lines (1-based) of anything that looks like a marker of [markerSize]; the validator for "no leftovers". */
    fun findMarkers(raw: String, markerSize: Int = 7): List<Pair<Int, Char>> {
        val out = ArrayList<Pair<Int, Char>>()
        RawText.splitLines(raw).forEachIndexed { i, l ->
            val m = classify(l, markerSize)
            if (m != null && m.ch != '=') out.add(i + 1 to m.ch)
        }
        // a separator only counts as a leftover when it sits inside an open conflict
        var open = false
        RawText.splitLines(raw).forEachIndexed { i, l ->
            val m = classify(l, markerSize) ?: return@forEachIndexed
            when (m.ch) {
                '<' -> open = true
                '>' -> open = false
                '=' -> if (open) out.add(i + 1 to '=')
            }
        }
        return out.sortedBy { it.first }
    }

    fun parse(raw: String, markerSize: Int = 7): ParsedMarkers {
        val lines = RawText.splitLines(raw)
        val segments = ArrayList<Segment>()
        val clean = ArrayList<String>()
        var oursLabel: String? = null; var baseLabel: String? = null; var theirsLabel: String? = null
        var i = 0
        fun flush() { if (clean.isNotEmpty()) { segments.add(Segment.Clean(ArrayList(clean))); clean.clear() } }
        var regionNo = 0
        while (i < lines.size) {
            val m = classify(lines[i], markerSize)
            when {
                m == null || m.ch == '=' -> { clean.add(lines[i]); i++ }
                m.ch == '|' || m.ch == '>' -> throw MarkerParseError(MarkerErrorKind.STRAY_MARKER, i + 1, "line ${i + 1}: '${m.ch}' marker with no open conflict")
                else -> {
                    // '<' : read one conflict
                    flush()
                    val startLine = i + 1
                    val ours = ArrayList<String>(); val base = ArrayList<String>(); val theirs = ArrayList<String>()
                    var sawBase = false; var sawSep = false; var closed = false
                    var crlf = m.crlf
                    oursLabel = oursLabel ?: m.label
                    i++
                    while (i < lines.size) {
                        val k = classify(lines[i], markerSize)
                        if (k == null) {
                            when {
                                sawSep -> theirs.add(lines[i])
                                sawBase -> base.add(lines[i])
                                else -> ours.add(lines[i])
                            }
                            i++; continue
                        }
                        when (k.ch) {
                            '<' -> throw MarkerParseError(MarkerErrorKind.NESTED_START, i + 1, "line ${i + 1}: conflict opened inside the conflict that starts at line $startLine")
                            '|' -> {
                                if (sawBase) throw MarkerParseError(MarkerErrorKind.OUT_OF_ORDER, i + 1, "line ${i + 1}: second base marker")
                                if (sawSep) throw MarkerParseError(MarkerErrorKind.OUT_OF_ORDER, i + 1, "line ${i + 1}: base marker after the separator")
                                sawBase = true; baseLabel = baseLabel ?: k.label
                            }
                            '=' -> {
                                if (sawSep) throw MarkerParseError(MarkerErrorKind.OUT_OF_ORDER, i + 1, "line ${i + 1}: second separator")
                                sawSep = true
                            }
                            '>' -> {
                                if (!sawSep) throw MarkerParseError(MarkerErrorKind.OUT_OF_ORDER, i + 1, "line ${i + 1}: end marker before the separator")
                                theirsLabel = theirsLabel ?: k.label
                                closed = true
                            }
                        }
                        i++
                        if (closed) break
                    }
                    if (!closed) throw MarkerParseError(MarkerErrorKind.UNTERMINATED, startLine, "conflict starting at line $startLine is never closed")
                    segments.add(Segment.Conflict(ConflictRegion(regionNo++, ours, theirs, if (sawBase) base else null, null, null, null, crlf)))
                }
            }
        }
        flush()
        return ParsedMarkers(segments, markerSize, oursLabel, baseLabel, theirsLabel)
    }
}
