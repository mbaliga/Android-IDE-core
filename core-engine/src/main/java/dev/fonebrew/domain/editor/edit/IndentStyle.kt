package dev.fonebrew.domain.editor.edit

/**
 * Tabs-versus-spaces, detected from the file itself ([detected] false means "no evidence, default").
 * [width] is the number of spaces per level (and the tab stop used when spaces are inserted).
 */
data class IndentStyle(val useTabs: Boolean, val width: Int, val detected: Boolean) {
    val unit: String get() = if (useTabs) "\t" else " ".repeat(width)

    companion object {
        val DEFAULT = IndentStyle(useTabs = false, width = 4, detected = false)

        /** Looks at up to [maxLines] non-blank lines; ties and no evidence give [DEFAULT]. */
        fun detect(lines: Sequence<String>, maxLines: Int = 2000): IndentStyle {
            var tabs = 0; var spaces = 0
            val diffs = IntArray(9)
            var prevSpaces = 0
            var seen = 0
            for (l in lines) {
                if (l.isBlank()) continue
                if (++seen > maxLines) break
                when (l[0]) {
                    '\t' -> tabs++
                    ' ' -> {
                        var n = 0
                        while (n < l.length && l[n] == ' ') n++
                        if (n >= 2) {
                            spaces++
                            val d = n - prevSpaces
                            if (d in 2..8) diffs[d]++
                        }
                        prevSpaces = n
                    }
                    else -> prevSpaces = 0
                }
            }
            if (tabs == 0 && spaces == 0) return DEFAULT
            if (tabs > spaces) return IndentStyle(true, DEFAULT.width, true)
            var best = 4; var bestN = 0
            for (w in intArrayOf(4, 2, 8, 3)) if (diffs[w] > bestN) { best = w; bestN = diffs[w] }
            return IndentStyle(false, best, true)
        }
    }
}

/** Comment delimiters of a language; either may be null (JSON has neither). */
data class CommentSyntax(val line: String?, val blockOpen: String? = null, val blockClose: String? = null) {
    val hasBlock: Boolean get() = blockOpen != null && blockClose != null
    val supported: Boolean get() = line != null || hasBlock

    companion object {
        val NONE = CommentSyntax(null)
        val SLASH = CommentSyntax("//", "/*", "*/")
        val HASH = CommentSyntax("#")
        val BLOCK_CSS = CommentSyntax(null, "/*", "*/")
        val BLOCK_XML = CommentSyntax(null, "<!--", "-->")
    }
}
