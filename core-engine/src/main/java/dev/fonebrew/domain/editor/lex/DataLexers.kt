package dev.fonebrew.domain.editor.lex

/** JSON (with `//` and `/* */` comments tolerated, as in tsconfig-style JSONC). Keys are PROPERTY, strays INVALID. */
class JsonLexer : LineLexer {
    override val id = "json"
    override fun categoryAt(state: LexState) = if (state.code == 1) TokenCategory.COMMENT else TokenCategory.PLAIN

    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    override fun lexLine(line: String, start: LexState, sink: TokenSink): LexState {
        val n = line.length
        var i = 0
        if (start.code == 1) {
            val e = line.indexOf("*/")
            if (e < 0) { sink.add(0, n, TokenCategory.COMMENT); return start }
            sink.add(0, e + 2, TokenCategory.COMMENT); i = e + 2
        }
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                line.startsWith("//", i) -> { sink.add(i, n, TokenCategory.COMMENT); i = n }
                line.startsWith("/*", i) -> {
                    val e = line.indexOf("*/", i + 2)
                    if (e < 0) { sink.add(i, n, TokenCategory.COMMENT); return LexState(1) }
                    sink.add(i, e + 2, TokenCategory.COMMENT); i = e + 2
                }
                c == '"' -> i = string(line, i, sink)
                c == '-' || c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in ".+-")) j++
                    sink.add(i, j, if (number.matches(line.substring(i, j))) TokenCategory.NUMBER else TokenCategory.INVALID)
                    i = j
                }
                c.isLetter() -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    val w = line.substring(i, j)
                    sink.add(i, j, if (w == "true" || w == "false" || w == "null") TokenCategory.KEYWORD else TokenCategory.INVALID)
                    i = j
                }
                c in "{}[],:" -> { sink.add(i, i + 1, TokenCategory.PUNCTUATION); i++ }
                else -> { sink.add(i, i + 1, TokenCategory.INVALID); i++ }
            }
        }
        return LexState.INITIAL
    }

    private fun string(line: String, at: Int, sink: TokenSink): Int {
        val n = line.length
        var j = at + 1
        var end = -1
        while (j < n) {
            if (line[j] == '\\') { j += 2; continue }
            if (line[j] == '"') { end = j + 1; break }
            j++
        }
        if (end < 0) { sink.add(at, n, TokenCategory.INVALID); return n }
        var k = end
        while (k < n && line[k].isWhitespace()) k++
        val cat = if (k < n && line[k] == ':') TokenCategory.PROPERTY else TokenCategory.STRING
        emitWithEscapes(line, at, end, cat, sink)
        return end
    }
}

/** Emit `[s, e)` as [cat] with backslash escapes split out as ESCAPE tokens. */
internal fun emitWithEscapes(line: String, s: Int, e: Int, cat: TokenCategory, sink: TokenSink) {
    var seg = s
    var j = s
    while (j < e) {
        if (line[j] == '\\' && j + 1 < e) {
            if (j > seg) sink.add(seg, j, cat)
            var k = j + 2
            val max = when (line[j + 1]) { 'u' -> 4; 'x' -> 2; 'U' -> 8; else -> 0 }
            var m = 0
            while (m < max && k < e && Character.digit(line[k], 16) >= 0) { k++; m++ }
            sink.add(j, k, TokenCategory.ESCAPE)
            j = k; seg = k
        } else j++
    }
    if (seg < e) sink.add(seg, e, cat)
}

/**
 * YAML, line-based. Block scalars (`|`, `>`) carry the parent column in the state so their body lines
 * lex as strings; multi-line flow collections and multi-line quoted scalars are not tracked (each line
 * lexes on its own). Plain scalars are PLAIN unless they are wholly a number or a YAML keyword.
 */
class YamlLexer : LineLexer {
    override val id = "yaml"
    override fun categoryAt(state: LexState) = if (state.code == 1) TokenCategory.STRING else TokenCategory.PLAIN

    private val numberRe = Regex("[-+]?(\\d[\\d_]*)(\\.\\d+)?([eE][-+]?\\d+)?|0x[0-9a-fA-F]+|0o[0-7]+|[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)")
    private val keywords = setOf("true", "false", "null", "~", "True", "False", "Null", "TRUE", "FALSE", "NULL", "yes", "no", "on", "off", "Yes", "No", "On", "Off")

    override fun lexLine(line: String, start: LexState, sink: TokenSink): LexState {
        val n = line.length
        var ind = 0
        while (ind < n && line[ind] == ' ') ind++
        if (start.code == 1) {
            if (ind == n || ind > start.arg) { sink.add(0, n, TokenCategory.STRING); return start }
        }
        if (ind == n) return LexState.INITIAL
        if (line[ind] == '#') { sink.add(ind, n, TokenCategory.COMMENT); return LexState.INITIAL }
        if (ind == 0 && (line.startsWith("---") || line.startsWith("...")) && (n == 3 || line[3].isWhitespace())) {
            sink.add(0, 3, TokenCategory.PUNCTUATION)
            if (n > 3) comment(line, 3, sink)
            return LexState.INITIAL
        }
        var i = ind
        var col = ind
        // sequence dashes: "- ", possibly nested ("- - x")
        while (i < n && line[i] == '-' && (i + 1 == n || line[i + 1] == ' ')) {
            sink.add(i, i + 1, TokenCategory.PUNCTUATION)
            i++
            while (i < n && line[i] == ' ') i++
            col = i
        }
        if (i >= n) return LexState.INITIAL
        val ke = keyEnd(line, i)
        if (ke > 0) {
            // keyEnd is the index of the ':' after the key
            emitWithEscapes(line, i, trimEnd(line, i, ke), TokenCategory.PROPERTY, sink)
            sink.add(ke, ke + 1, TokenCategory.PUNCTUATION)
            i = ke + 1
        }
        return value(line, i, col, sink)
    }

    private fun trimEnd(line: String, s: Int, e: Int): Int { var k = e; while (k > s && line[k - 1] == ' ') k--; return k }

    private fun comment(line: String, from: Int, sink: TokenSink) {
        var k = from
        while (k < line.length && line[k] == ' ') k++
        if (k < line.length && line[k] == '#') sink.add(k, line.length, TokenCategory.COMMENT)
    }

    /** Index of the ':' ending a mapping key that starts at [from], or -1. */
    private fun keyEnd(line: String, from: Int): Int {
        val n = line.length
        var j = from
        val c = line[j]
        if (c == '"' || c == '\'') {
            j++
            while (j < n && line[j] != c) { if (c == '"' && line[j] == '\\') j++; j++ }
            if (j >= n) return -1
            j++
            while (j < n && line[j] == ' ') j++
            return if (j < n && line[j] == ':' && (j + 1 == n || line[j + 1] == ' ')) j else -1
        }
        if (c in "[{|>&*!#%@`,]}") return -1
        while (j < n) {
            if (line[j] == ':' && (j + 1 == n || line[j + 1] == ' ')) return if (j > from) j else -1
            if (line[j] == '#' && j > from && line[j - 1] == ' ') return -1
            j++
        }
        return -1
    }

    private fun value(line: String, from: Int, parentCol: Int, sink: TokenSink): LexState {
        val n = line.length
        var i = from
        var flow = 0
        while (i < n) {
            val c = line[i]
            when {
                c == ' ' || c == '\t' -> i++
                c == '#' && (i == 0 || line[i - 1] == ' ') -> { sink.add(i, n, TokenCategory.COMMENT); i = n }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && line[j] != c) { if (c == '"' && line[j] == '\\') j++; j++ }
                    val e = minOf(n, j + 1)
                    if (c == '"') emitWithEscapes(line, i, e, TokenCategory.STRING, sink) else sink.add(i, e, TokenCategory.STRING)
                    i = e
                }
                (c == '|' || c == '>') && flow == 0 && blockHeader(line, i) -> {
                    var j = i + 1
                    while (j < n && (line[j] in "+-" || line[j].isDigit())) j++
                    sink.add(i, j, TokenCategory.PUNCTUATION)
                    comment(line, j, sink)
                    return LexState(1, parentCol)
                }
                c == '&' || c == '*' || c == '!' -> {
                    var j = i + 1
                    while (j < n && !line[j].isWhitespace() && line[j] !in ",[]{}") j++
                    sink.add(i, j, TokenCategory.TYPE); i = j
                }
                c in "[]{}," -> { if (c == '[' || c == '{') flow++ else if (c != ',') flow = maxOf(0, flow - 1); sink.add(i, i + 1, TokenCategory.PUNCTUATION); i++ }
                c == ':' && flow > 0 -> { sink.add(i, i + 1, TokenCategory.PUNCTUATION); i++ }
                else -> {
                    var j = i
                    while (j < n) {
                        if (flow > 0 && line[j] in ",]}:") break
                        if (line[j] == '#' && j > i && line[j - 1] == ' ') break
                        j++
                    }
                    val e = trimEnd(line, i, j)
                    val w = line.substring(i, e)
                    sink.add(i, e, when {
                        w in keywords -> TokenCategory.KEYWORD
                        numberRe.matches(w) -> TokenCategory.NUMBER
                        else -> TokenCategory.PLAIN
                    })
                    i = maxOf(j, i + 1)
                }
            }
        }
        return LexState.INITIAL
    }

    private fun blockHeader(line: String, at: Int): Boolean {
        var j = at + 1
        while (j < line.length && (line[j] in "+-" || line[j].isDigit())) j++
        while (j < line.length && line[j] == ' ') j++
        return j == line.length || line[j] == '#'
    }
}

/** TOML: tables, dotted keys, basic/literal and multi-line strings, numbers and dates, comments. */
class TomlLexer : LineLexer {
    override val id = "toml"
    override fun categoryAt(state: LexState) = if (state.code != 0) TokenCategory.STRING else TokenCategory.PLAIN

    private fun bare(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

    override fun lexLine(line: String, start: LexState, sink: TokenSink): LexState {
        val n = line.length
        var i = 0
        if (start.code != 0) {
            val close = if (start.code == 1) "\"\"\"" else "'''"
            val e = line.indexOf(close)
            if (e < 0) { sink.add(0, n, TokenCategory.STRING); return start }
            var end = e + 3
            while (end < n && line[end] == (if (start.code == 1) '"' else '\'') && end < e + 5) end++
            if (start.code == 1) emitWithEscapes(line, 0, end, TokenCategory.STRING, sink) else sink.add(0, end, TokenCategory.STRING)
            i = end
        }
        var first = line.substring(0, i).isBlank()
        while (i < n) {
            val c = line[i]
            if (c.isWhitespace()) { i++; continue }
            if (c == '#') { sink.add(i, n, TokenCategory.COMMENT); return LexState.INITIAL }
            if (c == '[' && first && line.substring(0, i).isBlank()) {
                val e = line.indexOf(']', i)
                var end = if (e < 0) n else e + 1
                if (e >= 0 && end < n && line[end] == ']') end++
                sink.add(i, end, TokenCategory.TYPE); i = end; first = false; continue
            }
            first = false
            if (line.startsWith("\"\"\"", i) || line.startsWith("'''", i)) {
                val basic = c == '"'
                val close = line.substring(i, i + 3)
                val e = line.indexOf(close, i + 3)
                if (e < 0) {
                    if (basic) emitWithEscapes(line, i, n, TokenCategory.STRING, sink) else sink.add(i, n, TokenCategory.STRING)
                    return LexState(if (basic) 1 else 2)
                }
                if (basic) emitWithEscapes(line, i, e + 3, TokenCategory.STRING, sink) else sink.add(i, e + 3, TokenCategory.STRING)
                i = e + 3; continue
            }
            val ke = keyEnd(line, i)
            if (ke > 0) { emitKey(line, i, ke, sink); i = ke; continue }
            when {
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && line[j] != c) { if (c == '"' && line[j] == '\\') j++; j++ }
                    val e = minOf(n, j + 1)
                    if (c == '"') emitWithEscapes(line, i, e, TokenCategory.STRING, sink) else sink.add(i, e, TokenCategory.STRING)
                    i = e
                }
                c.isDigit() || ((c == '+' || c == '-') && i + 1 < n && (line[i + 1].isDigit() || line[i + 1] == 'i' || line[i + 1] == 'n')) -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] in ".:_+-")) j++
                    // a date and time may be separated by a single space
                    if (j + 1 < n && line[j] == ' ' && line[j + 1].isDigit() && line.substring(i, j).matches(Regex("\\d{4}-\\d\\d-\\d\\d"))) {
                        j++
                        while (j < n && (line[j].isLetterOrDigit() || line[j] in ".:_+-")) j++
                    }
                    val w = line.substring(i, j)
                    sink.add(i, j, if (w.length > 1 || w[0].isDigit()) TokenCategory.NUMBER else TokenCategory.INVALID)
                    i = j
                }
                c.isLetter() -> {
                    var j = i + 1
                    while (j < n && bare(line[j])) j++
                    val w = line.substring(i, j)
                    sink.add(i, j, if (w == "true" || w == "false" || w == "inf" || w == "nan") TokenCategory.KEYWORD else TokenCategory.PLAIN)
                    i = j
                }
                c == '=' -> { sink.add(i, i + 1, TokenCategory.OPERATOR); i++ }
                c in "{}[],." -> { sink.add(i, i + 1, TokenCategory.PUNCTUATION); i++ }
                else -> { sink.add(i, i + 1, TokenCategory.INVALID); i++ }
            }
        }
        return LexState.INITIAL
    }

    /** End (exclusive) of a possibly dotted key starting at [from] that is followed by `=`, else -1. */
    private fun keyEnd(line: String, from: Int): Int {
        val n = line.length
        var j = from
        while (true) {
            if (j >= n) return -1
            val c = line[j]
            if (c == '"' || c == '\'') {
                j++
                while (j < n && line[j] != c) j++
                if (j >= n) return -1
                j++
            } else if (bare(c)) {
                while (j < n && bare(line[j])) j++
            } else return -1
            var k = j
            while (k < n && line[k] == ' ') k++
            if (k < n && line[k] == '.') { j = k + 1; while (j < n && line[j] == ' ') j++; continue }
            return if (k < n && line[k] == '=') j else -1
        }
    }

    private fun emitKey(line: String, s: Int, e: Int, sink: TokenSink) {
        var j = s
        while (j < e) {
            val c = line[j]
            if (c == '.') { sink.add(j, j + 1, TokenCategory.PUNCTUATION); j++ }
            else if (c == ' ') j++
            else if (c == '"' || c == '\'') { val q = line.indexOf(c, j + 1); val end = if (q < 0 || q >= e) e else q + 1; sink.add(j, end, TokenCategory.PROPERTY); j = end }
            else { var k = j; while (k < e && bare(line[k])) k++; sink.add(j, maxOf(k, j + 1), TokenCategory.PROPERTY); j = maxOf(k, j + 1) }
        }
    }
}
