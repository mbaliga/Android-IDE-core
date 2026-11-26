package dev.fonebrew.domain.editor.lex

/**
 * Markdown, line-based. Fenced code carries a state across lines; inline spans (code, emphasis,
 * links) are recognised within one line only. Emphasis is expressed as PLAIN text with a bold /
 * italic / strike style hint, links as FUNCTION + underline, so none of it depends on colour.
 */
class MarkdownLexer : LineLexer {
    override val id = "markdown"
    override fun categoryAt(state: LexState) = if (state.code == 1) TokenCategory.STRING else TokenCategory.PLAIN

    private val hr = Regex("^ {0,3}([-*_])( *\\1){2,} *$")
    private val list = Regex("^( *)([-*+]|\\d{1,9}[.)])( +|$)")

    override fun lexLine(line: String, start: LexState, sink: TokenSink): LexState {
        val n = line.length
        if (start.code == 1) {
            val marker = start.tag ?: "```"
            val t = line.trim()
            if (t.length >= marker.length && t.all { it == marker[0] }) { sink.add(0, n, TokenCategory.PUNCTUATION); return LexState.INITIAL }
            sink.add(0, n, TokenCategory.STRING)
            return start
        }
        var ind = 0
        while (ind < n && ind < 4 && line[ind] == ' ') ind++
        if (ind < 4 && ind < n) {
            val c = line[ind]
            if (c == '`' || c == '~') {
                var k = ind
                while (k < n && line[k] == c) k++
                if (k - ind >= 3 && !(c == '`' && line.indexOf('`', k) >= 0)) {
                    sink.add(ind, k, TokenCategory.PUNCTUATION)
                    sink.add(k, n, TokenCategory.TYPE)
                    return LexState(1, 0, line.substring(ind, k))
                }
            }
            if (c == '#') {
                var k = ind
                while (k < n && line[k] == '#') k++
                if (k - ind <= 6 && (k == n || line[k] == ' ')) { sink.add(ind, n, TokenCategory.KEYWORD); return LexState.INITIAL }
            }
            if (hr.matches(line)) { sink.add(ind, n, TokenCategory.PUNCTUATION); return LexState.INITIAL }
        }
        var i = 0
        if (ind < n && line[ind] == '>') {
            var k = ind
            while (k < n && (line[k] == '>' || line[k] == ' ')) k++
            sink.add(ind, k, TokenCategory.PUNCTUATION); i = k
        } else {
            list.find(line)?.let { m ->
                val markerStart = m.groupValues[1].length
                sink.add(markerStart, markerStart + m.groupValues[2].length, TokenCategory.PUNCTUATION)
                i = m.range.last + 1
            }
        }
        inlineSpans(line, i, n, sink)
        return LexState.INITIAL
    }

    private fun inlineSpans(line: String, from: Int, to: Int, sink: TokenSink) {
        var i = from
        while (i < to) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < to && !line[i + 1].isLetterOrDigit() -> { sink.add(i, i + 2, TokenCategory.ESCAPE); i += 2 }
                c == '`' -> {
                    var k = i
                    while (k < to && line[k] == '`') k++
                    val ticks = line.substring(i, k)
                    val e = line.indexOf(ticks, k)
                    if (e in k until to + 1 && e >= 0 && e + ticks.length <= to) { sink.add(i, e + ticks.length, TokenCategory.STRING); i = e + ticks.length }
                    else i = k
                }
                (c == '*' || c == '_') -> {
                    val strong = i + 1 < to && line[i + 1] == c
                    val open = if (strong) 2 else 1
                    val bodyStart = i + open
                    val boundaryOk = c == '*' || i == 0 || !line[i - 1].isLetterOrDigit()
                    if (boundaryOk && bodyStart < to && !line[bodyStart].isWhitespace()) {
                        val closer = c.toString().repeat(open)
                        val e = line.indexOf(closer, bodyStart + 1)
                        if (e > 0 && e + open <= to && !line[e - 1].isWhitespace()) {
                            sink.add(i, e + open, TokenCategory.PLAIN, if (strong) StyleHint.BOLD else StyleHint.ITALIC)
                            i = e + open
                            continue
                        }
                    }
                    i += open
                }
                c == '~' && line.startsWith("~~", i) -> {
                    val e = line.indexOf("~~", i + 2)
                    if (e > i + 2 && e + 2 <= to) { sink.add(i, e + 2, TokenCategory.PLAIN, StyleHint.STRIKE); i = e + 2 } else i += 2
                }
                c == '[' || (c == '!' && i + 1 < to && line[i + 1] == '[') -> {
                    val b = if (c == '!') i + 1 else i
                    val close = line.indexOf(']', b + 1)
                    if (close in 0 until to && close + 1 < to && line[close + 1] == '(') {
                        val pe = line.indexOf(')', close + 2)
                        if (pe in 0 until to) {
                            sink.add(i, b + 1, TokenCategory.PUNCTUATION)
                            sink.add(b + 1, close, TokenCategory.FUNCTION, StyleHint.UNDERLINE)
                            sink.add(close, close + 2, TokenCategory.PUNCTUATION)
                            sink.add(close + 2, pe, TokenCategory.STRING)
                            sink.add(pe, pe + 1, TokenCategory.PUNCTUATION)
                            i = pe + 1
                            continue
                        }
                    }
                    i++
                }
                c == '<' -> {
                    val e = line.indexOf('>', i)
                    if (e in 0 until to && line.substring(i + 1, e).matches(Regex("[A-Za-z][A-Za-z0-9+.-]*:[^\\s<>]*"))) { sink.add(i, e + 1, TokenCategory.STRING); i = e + 1 }
                    else i++
                }
                else -> i++
            }
        }
    }
}

/**
 * HTML / XML. Tag names are KEYWORD, attribute names PROPERTY, attribute values STRING, entities
 * ESCAPE. A tag, comment, CDATA section, or `<script>`/`<style>` body may span lines (state).
 * Script and style bodies are left PLAIN: embedded-language lexing is out of scope.
 */
class MarkupLexer : LineLexer {
    override val id = "markup"

    private companion object {
        const val COMMENT = 1
        const val IN_TAG = 2
        const val CDATA = 3
        const val RAW = 4
    }

    override fun categoryAt(state: LexState) = when (state.code) {
        COMMENT -> TokenCategory.COMMENT
        CDATA -> TokenCategory.STRING
        else -> TokenCategory.PLAIN
    }

    override fun lexLine(line: String, start: LexState, sink: TokenSink): LexState = Run(line, sink, start).go()

    private class Run(val line: String, val sink: TokenSink, var st: LexState) {
        val n = line.length
        var i = 0

        fun go(): LexState {
            when (st.code) {
                COMMENT -> {
                    val e = line.indexOf("-->")
                    if (e < 0) { sink.add(0, n, TokenCategory.COMMENT); return st }
                    sink.add(0, e + 3, TokenCategory.COMMENT); i = e + 3; st = LexState.INITIAL
                }
                CDATA -> {
                    val e = line.indexOf("]]>")
                    if (e < 0) { sink.add(0, n, TokenCategory.STRING); return st }
                    sink.add(0, e + 3, TokenCategory.STRING); i = e + 3; st = LexState.INITIAL
                }
                RAW -> {
                    val close = "</" + (st.tag ?: "")
                    val e = line.indexOf(close, ignoreCase = true)
                    if (e < 0) return st
                    i = e; st = LexState.INITIAL
                }
                IN_TAG -> { attributes(st.tag, st.arg) }
            }
            while (i < n) {
                if (st.code == IN_TAG) { attributes(st.tag, st.arg); continue }
                val c = line[i]
                when {
                    line.startsWith("<!--", i) -> {
                        val e = line.indexOf("-->", i + 4)
                        if (e < 0) { sink.add(i, n, TokenCategory.COMMENT); return LexState(COMMENT) }
                        sink.add(i, e + 3, TokenCategory.COMMENT); i = e + 3
                    }
                    line.startsWith("<![CDATA[", i) -> {
                        val e = line.indexOf("]]>", i + 9)
                        if (e < 0) { sink.add(i, n, TokenCategory.STRING); return LexState(CDATA) }
                        sink.add(i, e + 3, TokenCategory.STRING); i = e + 3
                    }
                    c == '<' && i + 1 < n && (line[i + 1] == '!' || line[i + 1] == '?') -> {
                        sink.add(i, i + 2, TokenCategory.PUNCTUATION); i += 2
                        var j = i
                        while (j < n && (line[j].isLetterOrDigit() || line[j] == '-' || line[j] == ':')) j++
                        sink.add(i, j, TokenCategory.KEYWORD); i = j
                        st = LexState(IN_TAG, 0, null)
                    }
                    c == '<' && i + 1 < n && (line[i + 1] == '/' || line[i + 1].isLetter() || line[i + 1] == '_') -> {
                        val closing = line[i + 1] == '/'
                        val ns = i + if (closing) 2 else 1
                        sink.add(i, ns, TokenCategory.PUNCTUATION)
                        var j = ns
                        while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_:.")) j++
                        sink.add(ns, j, TokenCategory.KEYWORD)
                        i = j
                        val name = line.substring(ns, j).lowercase()
                        st = LexState(IN_TAG, 0, if (!closing && (name == "script" || name == "style")) name else null)
                    }
                    c == '&' -> {
                        val e = line.indexOf(';', i)
                        if (e in (i + 2)..(i + 12) && line.substring(i + 1, e).all { it.isLetterOrDigit() || it == '#' }) { sink.add(i, e + 1, TokenCategory.ESCAPE); i = e + 1 }
                        else i++
                    }
                    else -> {
                        var j = i + 1
                        while (j < n && line[j] != '<' && line[j] != '&') j++
                        i = j
                    }
                }
            }
            return st
        }

        /** Lex attributes until `>` / `/>` / `?>` or the line ends (state carries the open quote). */
        fun attributes(rawTag: String?, quote0: Int) {
            var quote = quote0
            while (i < n) {
                if (quote != 0) {
                    val q = if (quote == 1) '"' else '\''
                    val e = line.indexOf(q, i)
                    if (e < 0) { sink.add(i, n, TokenCategory.STRING); i = n; st = LexState(IN_TAG, quote, rawTag); return }
                    sink.add(i, e + 1, TokenCategory.STRING); i = e + 1; quote = 0
                    continue
                }
                val c = line[i]
                when {
                    c.isWhitespace() -> i++
                    c == '>' || line.startsWith("/>", i) || line.startsWith("?>", i) -> {
                        val len = if (c == '>') 1 else 2
                        sink.add(i, i + len, TokenCategory.PUNCTUATION)
                        val selfClosing = c != '>'
                        i += len
                        st = if (!selfClosing && rawTag != null) LexState(RAW, 0, rawTag) else LexState.INITIAL
                        if (st.code == RAW) {
                            val e = line.indexOf("</$rawTag", i, ignoreCase = true)
                            if (e >= 0) { i = e; st = LexState.INITIAL } else i = n
                        }
                        return
                    }
                    c == '=' -> { sink.add(i, i + 1, TokenCategory.OPERATOR); i++ }
                    c == '"' || c == '\'' -> {
                        val q = c
                        val e = line.indexOf(q, i + 1)
                        if (e < 0) { sink.add(i, n, TokenCategory.STRING); i = n; st = LexState(IN_TAG, if (q == '"') 1 else 2, rawTag); return }
                        sink.add(i, e + 1, TokenCategory.STRING); i = e + 1
                    }
                    else -> {
                        var j = i + 1
                        while (j < n && !line[j].isWhitespace() && line[j] !in "=>/\"'?") j++
                        val prevEq = lastNonSpace(i) == '='
                        sink.add(i, j, if (prevEq) TokenCategory.STRING else TokenCategory.PROPERTY)
                        i = j
                    }
                }
            }
            st = LexState(IN_TAG, 0, rawTag)
        }

        fun lastNonSpace(before: Int): Char? { var k = before - 1; while (k >= 0 && line[k] == ' ') k--; return if (k >= 0) line[k] else null }
    }
}

/**
 * CSS. A bit mask in the state tracks, per open brace, whether the block holds declarations or nested
 * rules, so property names and selectors are told apart across lines. Comments carry a flag in `code`.
 */
class CssLexer : LineLexer {
    override val id = "css"
    override fun categoryAt(state: LexState) = if (state.code == 1) TokenCategory.COMMENT else TokenCategory.PLAIN

    private val containers = Regex("^@(-[a-z]+-)?(media|supports|layer|container|document|keyframes|scope)\\b")
    private val hex = Regex("#[0-9a-fA-F]{3,8}")

    override fun lexLine(line: String, start: LexState, sink: TokenSink): LexState {
        val n = line.length
        var mask = if (start.arg == 0) 1 else start.arg
        var i = 0
        if (start.code == 1) {
            val e = line.indexOf("*/")
            if (e < 0) { sink.add(0, n, TokenCategory.COMMENT); return start }
            sink.add(0, e + 2, TokenCategory.COMMENT); i = e + 2
        }
        fun inDecl() = mask > 1 && (mask and 1) == 1
        var stmtStart = if (inDecl()) i else -1
        var prelude = i
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                line.startsWith("/*", i) -> {
                    val e = line.indexOf("*/", i + 2)
                    if (e < 0) { sink.add(i, n, TokenCategory.COMMENT); return LexState(1, mask) }
                    sink.add(i, e + 2, TokenCategory.COMMENT); i = e + 2
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && line[j] != c) { if (line[j] == '\\') j++; j++ }
                    val e = minOf(n, j + 1)
                    emitWithEscapes(line, i, e, TokenCategory.STRING, sink); i = e
                }
                c == '{' -> {
                    val pre = line.substring(prelude, i).trim()
                    val kind = if (containers.containsMatchIn(pre)) 0 else 1
                    if (mask < (1 shl 28)) mask = (mask shl 1) or kind
                    sink.add(i, i + 1, TokenCategory.PUNCTUATION); i++
                    prelude = i; stmtStart = if (inDecl()) i else -1
                }
                c == '}' -> {
                    if (mask > 1) mask = mask shr 1
                    sink.add(i, i + 1, TokenCategory.PUNCTUATION); i++
                    prelude = i; stmtStart = if (inDecl()) i else -1
                }
                c == ';' -> { sink.add(i, i + 1, TokenCategory.PUNCTUATION); i++; prelude = i; stmtStart = if (inDecl()) i else -1 }
                c == '@' -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '-')) j++
                    sink.add(i, j, TokenCategory.KEYWORD); i = j
                }
                c == '!' && line.startsWith("!important", i) -> { sink.add(i, i + 10, TokenCategory.KEYWORD); i += 10 }
                c == '#' && inDecl() && hex.matchesAt(line, i) -> {
                    var j = i + 1
                    while (j < n && Character.digit(line[j], 16) >= 0) j++
                    sink.add(i, j, TokenCategory.NUMBER); i = j
                }
                c.isDigit() || ((c == '.' || c == '-' || c == '+') && i + 1 < n && (line[i + 1].isDigit() || (line[i + 1] == '.' && i + 2 < n && line[i + 2].isDigit())) && inDecl()) -> {
                    var j = i + 1
                    while (j < n && (line[j].isDigit() || line[j] == '.')) j++
                    while (j < n && (line[j].isLetter() || line[j] == '%')) j++
                    sink.add(i, j, TokenCategory.NUMBER); i = j
                }
                c.isLetter() || c == '_' || c == '-' || c == '.' || c == '#' -> {
                    var j = i + 1
                    while (j < n && (line[j].isLetterOrDigit() || line[j] == '-' || line[j] == '_')) j++
                    var k = j
                    while (k < n && line[k] == ' ') k++
                    val isProp = inDecl() && stmtStart >= 0 && line.substring(stmtStart, i).isBlank() &&
                        k < n && line[k] == ':' && !(k + 1 < n && line[k + 1] == ':') && !selectorAhead(line, k)
                    val cat = when {
                        isProp -> TokenCategory.PROPERTY
                        k < n && line[k] == '(' && (c.isLetter() || c == '-') -> TokenCategory.FUNCTION
                        inDecl() && !selectorAhead(line, i) -> TokenCategory.PLAIN
                        else -> TokenCategory.TYPE
                    }
                    sink.add(i, j, cat); i = j
                }
                c in ">+~*" -> { sink.add(i, i + 1, TokenCategory.OPERATOR); i++ }
                c in "[]():,=" -> { sink.add(i, i + 1, TokenCategory.PUNCTUATION); i++ }
                else -> i++
            }
        }
        return if (mask == 1) LexState.INITIAL else LexState(0, mask)
    }

    /** True if a `{` follows on this line before any `;`: the text is a nested selector, not a declaration. */
    private fun selectorAhead(line: String, from: Int): Boolean {
        val b = line.indexOf('{', from)
        if (b < 0) return false
        val s = line.indexOf(';', from)
        return s < 0 || b < s
    }
}
