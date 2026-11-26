package dev.fonebrew.domain.editor.lex

/** A string form: [open] ... [close]. Single-line forms end at the line end if unterminated. */
data class StringDelim(
    val open: String,
    val close: String = open,
    val escapes: Boolean = true,
    val multiline: Boolean = false,
)

/** Everything that distinguishes the C-family / scripting languages the generic engine handles. */
class CLikeConfig(
    val id: String,
    val keywords: Set<String>,
    val builtinTypes: Set<String> = emptySet(),
    val lineComments: List<String> = listOf("//"),
    val blockComment: Pair<String, String>? = "/*" to "*/",
    val nestedBlockComments: Boolean = false,
    /** Tried in order, so list longer openers (`"""`) before shorter ones. */
    val delims: List<StringDelim>,
    val stringPrefixes: Set<String> = emptySet(),
    val rustRaw: Boolean = false,
    val rustLifetimes: Boolean = false,
    val macroBang: Boolean = false,
    val annotations: Boolean = false,
    val preprocessor: Boolean = false,
    val shell: Boolean = false,
    val capitalisedAreTypes: Boolean = true,
    val operatorChars: String = "+-*/%=<>!&|^~?:",
    val punctuationChars: String = "(){}[];,.",
    val dollarInIdentifiers: Boolean = false,
)

/**
 * One configurable lexer for the languages that share C-style lexical structure (Kotlin, Java,
 * JS/TS, Python, shell, C/C++, Rust, Go, Gradle). It is a hand-written scanner: every loop advances
 * by at least one character, so an unterminated string or comment ends at the line end (or carries a
 * multi-line state) and can never spin.
 *
 * Heuristics, stated: a capitalised identifier with a lower-case letter is a TYPE; an identifier
 * followed by `(` is a FUNCTION; JS regex literals, C++ raw strings and string interpolation bodies
 * are not understood (they lex as the surrounding tokens).
 */
class CLikeLexer(private val cfg: CLikeConfig) : LineLexer {
    override val id: String get() = cfg.id

    private companion object {
        const val NORMAL = 0
        const val BLOCK = 1
        const val MULTI = 2
        const val RAW_HASH = 3
        const val HEREDOC = 5
    }

    override fun categoryAt(state: LexState): TokenCategory = when (state.code) {
        BLOCK -> TokenCategory.COMMENT
        MULTI, RAW_HASH, HEREDOC -> TokenCategory.STRING
        else -> TokenCategory.PLAIN
    }

    override fun lexLine(line: String, start: LexState, sink: TokenSink): LexState = Run(line, sink, start).lexAll()

    private inner class Run(val line: String, val sink: TokenSink, var state: LexState) {
        val n = line.length
        var i = 0
        var pendingHeredoc: String? = null

        fun lexAll(): LexState {
            if (state.code != NORMAL) resume()
            while (i < n) step()
            val h = pendingHeredoc
            if (h != null && state.code == NORMAL) state = LexState(HEREDOC, 0, h)
            return state
        }

        fun resume() {
            when (state.code) {
                BLOCK -> blockComment(0, state.arg)
                MULTI -> {
                    val d = cfg.delims[state.arg]
                    stringBody(0, d.close, d.escapes, TokenCategory.STRING)?.let { i = it; state = LexState.INITIAL }
                        ?: run { i = n }
                }
                RAW_HASH -> {
                    val close = "\"" + "#".repeat(state.arg)
                    stringBody(0, close, false, TokenCategory.STRING)?.let { i = it; state = LexState.INITIAL } ?: run { i = n }
                }
                HEREDOC -> {
                    val tag = state.tag ?: ""
                    sink.add(0, n, TokenCategory.STRING)
                    if (line.trim() == tag.removeSuffix("-")) state = LexState.INITIAL
                    i = n
                }
                else -> state = LexState.INITIAL
            }
        }

        /** Emit a comment from [from]; sets state when it runs past the line. */
        fun blockComment(from: Int, depth0: Int) {
            val (open, close) = cfg.blockComment!!
            var depth = depth0.coerceAtLeast(1)
            var j = from
            while (j < n) {
                if (cfg.nestedBlockComments && line.startsWith(open, j)) { depth++; j += open.length }
                else if (line.startsWith(close, j)) {
                    j += close.length; depth--
                    if (depth == 0) { sink.add(from, j, TokenCategory.COMMENT); i = j; state = LexState.INITIAL; return }
                } else j++
            }
            sink.add(from, n, TokenCategory.COMMENT)
            i = n
            state = LexState(BLOCK, depth)
        }

        /**
         * Emit string/escape tokens for the body starting at [from] up to and including [close];
         * returns the index after [close], or null when the line ended first.
         */
        fun stringBody(from: Int, close: String, escapes: Boolean, cat: TokenCategory): Int? {
            var segStart = from
            var j = from
            while (j < n) {
                val c = line[j]
                if (escapes && c == '\\') {
                    if (j > segStart) sink.add(segStart, j, cat)
                    val e = escapeEnd(j)
                    sink.add(j, e, TokenCategory.ESCAPE)
                    j = e; segStart = e
                    continue
                }
                if (line.startsWith(close, j)) {
                    j += close.length
                    sink.add(segStart, j, cat)
                    return j
                }
                j++
            }
            sink.add(segStart, n, cat)
            return null
        }

        fun escapeEnd(at: Int): Int {
            var j = at + 1
            if (j >= n) return n
            val c = line[j]
            j++
            val max = when (c) { 'x' -> 2; 'u' -> 4; 'U' -> 8; else -> 0 }
            var k = 0
            while (k < max && j < n && Character.digit(line[j], 16) >= 0) { j++; k++ }
            if (c == 'u' && j < n && line[j] == '{' ) { while (j < n && line[j] != '}') j++; if (j < n) j++ }
            return j
        }

        fun isIdentStart(c: Char) = Character.isLetter(c) || c == '_' || (cfg.dollarInIdentifiers && c == '$')
        fun isIdentPart(c: Char) = Character.isLetterOrDigit(c) || c == '_' || (cfg.dollarInIdentifiers && c == '$')

        fun startsLineComment(at: Int): Boolean {
            for (lc in cfg.lineComments) {
                if (line.startsWith(lc, at)) {
                    if (cfg.shell && lc == "#" && at > 0 && !line[at - 1].isWhitespace() && line[at - 1] != ';') return false
                    return true
                }
            }
            return false
        }

        fun step() {
            val c = line[i]
            if (c.isWhitespace()) { i++; return }
            if (startsLineComment(i)) { sink.add(i, n, TokenCategory.COMMENT); i = n; return }
            val bc = cfg.blockComment
            if (bc != null && line.startsWith(bc.first, i)) {
                // `/**/` and friends: the opener's own characters are scanned by blockComment
                blockCommentFromOpener()
                return
            }
            if (cfg.preprocessor && c == '#' && line.substring(0, i).isBlank()) { preprocessor(); return }
            if (cfg.shell && c == '$') { shellVar(); return }
            if (cfg.shell && c == '<' && line.startsWith("<<", i) && heredoc()) return
            if (cfg.rustLifetimes && c == '\'' && i + 1 < n && isIdentStart(line[i + 1]) && !(i + 2 < n && line[i + 2] == '\'')) {
                var j = i + 1
                while (j < n && isIdentPart(line[j])) j++
                sink.add(i, j, TokenCategory.TYPE); i = j; return
            }
            for ((idx, d) in cfg.delims.withIndex()) if (line.startsWith(d.open, i)) { stringAt(i, i, idx, d); return }
            if (c.isDigit() || (c == '.' && i + 1 < n && line[i + 1].isDigit())) {
                val e = numberEnd(i)
                sink.add(i, e, TokenCategory.NUMBER); i = e; return
            }
            if (isIdentStart(c)) { identifier(); return }
            if (cfg.annotations && c == '@' && i + 1 < n && isIdentStart(line[i + 1])) {
                var j = i + 1
                while (j < n && (isIdentPart(line[j]) || line[j] == '.')) j++
                sink.add(i, j, TokenCategory.TYPE); i = j; return
            }
            if (c in cfg.operatorChars) { sink.add(i, i + 1, TokenCategory.OPERATOR); i++; return }
            if (c in cfg.punctuationChars) { sink.add(i, i + 1, TokenCategory.PUNCTUATION); i++; return }
            if (c.code < 0x20 || c.code == 0x7F) { sink.add(i, i + 1, TokenCategory.INVALID); i++; return }
            i++   // any other symbol is plain text
        }

        fun blockCommentFromOpener() {
            val (open, _) = cfg.blockComment!!
            val from = i
            // scan after the opener with depth 1; the opener itself is part of the comment token
            val (_, close) = cfg.blockComment
            var depth = 1
            var j = from + open.length
            while (j < n) {
                if (cfg.nestedBlockComments && line.startsWith(open, j)) { depth++; j += open.length }
                else if (line.startsWith(close, j)) { j += close.length; depth--; if (depth == 0) { sink.add(from, j, TokenCategory.COMMENT); i = j; return } }
                else j++
            }
            sink.add(from, n, TokenCategory.COMMENT)
            i = n
            state = LexState(BLOCK, depth)
        }

        fun stringAt(tokStart: Int, openAt: Int, idx: Int, d: StringDelim) {
            val bodyStart = openAt + d.open.length
            val next = stringBody(bodyStart, d.close, d.escapes, TokenCategory.STRING, tokStart, openAt)
            if (next != null) { i = next; return }
            i = n
            if (d.multiline) state = LexState(MULTI, idx)
        }

        /** [stringBody] variant that first emits the opener (and any prefix) as part of the string. */
        fun stringBody(from: Int, close: String, escapes: Boolean, cat: TokenCategory, tokStart: Int, openAt: Int): Int? {
            // emit prefix+opener as STRING, then the body
            sink.add(tokStart, from, cat)
            return stringBody(from, close, escapes, cat)
        }

        fun numberEnd(at: Int): Int {
            var j = at
            if (line[j] == '0' && j + 1 < n && line[j + 1] in "xXbBoO") {
                j += 2
                while (j < n && (Character.isLetterOrDigit(line[j]) || line[j] == '_')) j++
                return j
            }
            while (j < n && (line[j].isDigit() || line[j] == '_')) j++
            if (j + 1 < n && line[j] == '.' && line[j + 1].isDigit()) {
                j++
                while (j < n && (line[j].isDigit() || line[j] == '_')) j++
            }
            if (j < n && (line[j] == 'e' || line[j] == 'E')) {
                var k = j + 1
                if (k < n && (line[k] == '+' || line[k] == '-')) k++
                if (k < n && line[k].isDigit()) { while (k < n && line[k].isDigit()) k++; j = k }
            }
            if (j < n && line[j].isLetter()) { while (j < n && (line[j].isLetterOrDigit() || line[j] == '_')) j++ }
            return j
        }

        fun identifier() {
            val s = i
            var j = i + 1
            while (j < n && isIdentPart(line[j])) j++
            val word = line.substring(s, j)
            // string prefixes: Python r"", f"", b""; Rust r#""#, b""
            if (j < n && (line[j] == '"' || line[j] == '\'' || (cfg.rustRaw && line[j] == '#'))) {
                if (cfg.rustRaw && (word == "r" || word == "br")) {
                    var h = 0
                    var k = j
                    while (k < n && line[k] == '#') { h++; k++ }
                    if (k < n && line[k] == '"') {
                        val close = "\"" + "#".repeat(h)
                        sink.add(s, k + 1, TokenCategory.STRING)
                        val next = stringBody(k + 1, close, false, TokenCategory.STRING)
                        if (next != null) i = next else { i = n; state = LexState(RAW_HASH, h) }
                        return
                    }
                }
                if (word.lowercase() in cfg.stringPrefixes) {
                    for ((idx, d) in cfg.delims.withIndex()) if (line.startsWith(d.open, j)) {
                        val raw = word.contains('r', ignoreCase = true)
                        stringAt(s, j, idx, if (raw) d.copy(escapes = false) else d)
                        return
                    }
                }
            }
            var k = j
            while (k < n && line[k] == ' ') k++
            val cat = when {
                word in cfg.keywords -> TokenCategory.KEYWORD
                word in cfg.builtinTypes -> TokenCategory.TYPE
                k < n && line[k] == '(' -> TokenCategory.FUNCTION
                cfg.macroBang && j < n && line[j] == '!' && k + 1 < n && line[k + 1] in "([{" -> { j++; TokenCategory.FUNCTION }
                cfg.capitalisedAreTypes && word[0].isUpperCase() && word.any { it.isLowerCase() } -> TokenCategory.TYPE
                else -> TokenCategory.PLAIN
            }
            sink.add(s, j, cat)
            i = j
        }

        fun preprocessor() {
            var j = i + 1
            while (j < n && line[j].isWhitespace()) j++
            while (j < n && isIdentPart(line[j])) j++
            sink.add(i, j, TokenCategory.KEYWORD)
            val directive = line.substring(i, j).replace(" ", "")
            i = j
            if (directive == "#include") {
                while (i < n && line[i].isWhitespace()) i++
                if (i < n && line[i] == '<') {
                    val e = line.indexOf('>', i)
                    val end = if (e < 0) n else e + 1
                    sink.add(i, end, TokenCategory.STRING); i = end
                }
            }
        }

        fun shellVar() {
            var j = i + 1
            if (j >= n) { sink.add(i, i + 1, TokenCategory.OPERATOR); i = j; return }
            val c = line[j]
            when {
                c == '{' -> { val e = line.indexOf('}', j); j = if (e < 0) n else e + 1 }
                c == '(' -> { sink.add(i, j + 1, TokenCategory.PUNCTUATION); i = j + 1; return }
                isIdentStart(c) -> { while (j < n && isIdentPart(line[j])) j++ }
                c.isDigit() || c in "#?$!@*-" -> j++
                else -> { sink.add(i, i + 1, TokenCategory.OPERATOR); i++; return }
            }
            sink.add(i, j, TokenCategory.PROPERTY)
            i = j
        }

        /** `<<EOF`, `<<-'EOF'`: remember the tag so the following lines lex as a string. */
        fun heredoc(): Boolean {
            var j = i + 2
            if (j < n && line[j] == '<') return false
            var dash = false
            if (j < n && line[j] == '-') { dash = true; j++ }
            while (j < n && line[j] == ' ') j++
            val quote = if (j < n && (line[j] == '"' || line[j] == '\'')) line[j++] else null
            val ws = j
            while (j < n && (Character.isLetterOrDigit(line[j]) || line[j] == '_')) j++
            if (j == ws || line[ws].isDigit()) return false
            val tag = line.substring(ws, j)
            if (quote != null && j < n && line[j] == quote) j++
            sink.add(i, j, TokenCategory.OPERATOR)
            pendingHeredoc = tag + if (dash) "-" else ""
            i = j
            return true
        }
    }
}
