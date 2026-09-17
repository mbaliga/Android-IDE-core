package dev.fonebrew.domain.search.query

import java.time.ZoneId

/**
 * Parses a raw search-box string into a [ParsedQuery] (Doc §6.1/§6.2). **Never throws** — every
 * malformed input (unterminated quote, unknown field, bad date) degrades to a best-effort parse
 * plus a [Diagnostic], per the spec's hard rule that a search box that throws is a search box
 * people stop using.
 *
 * Two-stage: [tokenize] turns the raw string into a flat token list (tolerant of the same
 * malformed input — an unterminated quote is treated as closed at end-of-input), then
 * [Parser] is a small recursive-descent parser over that list implementing the grammar's
 * precedence: `OR` lowest, then `AND`/juxtaposition, then unary `NOT`/`-`, then primaries.
 */
object QueryParser {

    fun parse(rawText: String, nowMillis: Long = 0L, zone: ZoneId = ZoneId.of("UTC")): ParsedQuery {
        val diagnostics = mutableListOf<Diagnostic>()
        val tokens = tokenize(rawText, diagnostics)
        val root = Parser(tokens, diagnostics, nowMillis, zone).parseQuery()
        val facets = collectFacets(root)
        val semanticText = collectFirstSemantic(root)
        return ParsedQuery(
            rawText = rawText,
            root = root,
            ftsExpression = QueryCompiler.compileFts(root),
            facets = facets,
            semanticText = semanticText,
            diagnostics = diagnostics,
            chips = ChipBuilder.build(root),
        )
    }

    private fun collectFacets(node: QueryNode?): List<QueryNode.Facet> {
        if (node == null) return emptyList()
        return when (node) {
            is QueryNode.Facet -> listOf(node)
            is QueryNode.And -> node.children.flatMap(::collectFacets)
            is QueryNode.Or -> node.children.flatMap(::collectFacets)
            is QueryNode.Not -> collectFacets(node.child)
            else -> emptyList()
        }
    }

    private fun collectFirstSemantic(node: QueryNode?): String? {
        if (node == null) return null
        return when (node) {
            is QueryNode.Semantic -> node.text
            is QueryNode.And -> node.children.firstNotNullOfOrNull(::collectFirstSemantic)
            is QueryNode.Or -> node.children.firstNotNullOfOrNull(::collectFirstSemantic)
            is QueryNode.Not -> collectFirstSemantic(node.child)
            else -> null
        }
    }
}

// ============================== tokenizer ==============================

private data class Token(val kind: Kind, val text: String, val start: Int) {
    enum class Kind { WORD, FACET, PHRASE, REGEX, SEMANTIC, LPAREN, RPAREN, AND, OR, NOT }
}

private fun isWordChar(c: Char) = !c.isWhitespace() && c != '(' && c != ')' && c != '"'

private fun tokenize(input: String, diagnostics: MutableList<Diagnostic>): List<Token> {
    val tokens = ArrayList<Token>()
    var i = 0
    val n = input.length
    while (i < n) {
        val c = input[i]
        when {
            c.isWhitespace() -> i++
            c == '(' -> { tokens.add(Token(Token.Kind.LPAREN, "(", i)); i++ }
            c == ')' -> { tokens.add(Token(Token.Kind.RPAREN, ")", i)); i++ }
            // A `-` directly before a phrase / regex / group / semantic term negates it, e.g.
            // `-"build cache"`, `-/draft/`, `-(a OR b)`. A `-` before a word or a `field:value`
            // is NOT handled here: scanWordOrFacet has to read the whole run before it can tell
            // a negated facet from a negated word, so it owns that case (and emits the `-` as
            // part of the token it produces).
            c == '-' && i + 1 < n && (input[i + 1] == '"' || input[i + 1] == '/' || input[i + 1] == '(' || input[i + 1] == '?') -> {
                tokens.add(Token(Token.Kind.NOT, "-", i))
                i++
            }
            c == '"' -> {
                val start = i
                i++
                val sb = StringBuilder()
                var terminated = false
                while (i < n) {
                    if (input[i] == '"') { terminated = true; i++; break }
                    sb.append(input[i]); i++
                }
                if (!terminated) diagnostics.add(Diagnostic.UnterminatedQuote(start))
                tokens.add(Token(Token.Kind.PHRASE, sb.toString(), start))
            }
            c == '/' -> {
                val start = i
                i++
                val sb = StringBuilder()
                var terminated = false
                while (i < n && !input[i].isWhitespace()) {
                    if (input[i] == '/') { terminated = true; i++; break }
                    sb.append(input[i]); i++
                }
                if (terminated) {
                    var ignoreCase = false
                    if (i < n && input[i] == 'i') { ignoreCase = true; i++ }
                    tokens.add(Token(Token.Kind.REGEX, encodeRegex(sb.toString(), ignoreCase), start))
                } else {
                    diagnostics.add(Diagnostic.UnterminatedRegex(start))
                    // Degrade: the run we consumed becomes a plain term, never dropped.
                    tokens.add(Token(Token.Kind.WORD, "/" + sb.toString(), start))
                }
            }
            c == '?' -> {
                val start = i
                i++
                if (i < n && input[i] == '"') {
                    i++
                    val sb = StringBuilder()
                    var terminated = false
                    while (i < n) {
                        if (input[i] == '"') { terminated = true; i++; break }
                        sb.append(input[i]); i++
                    }
                    if (!terminated) diagnostics.add(Diagnostic.UnterminatedQuote(start))
                    tokens.add(Token(Token.Kind.SEMANTIC, sb.toString(), start))
                } else {
                    val sb = StringBuilder()
                    while (i < n && isWordChar(input[i])) { sb.append(input[i]); i++ }
                    tokens.add(Token(Token.Kind.SEMANTIC, sb.toString(), start))
                }
            }
            else -> i = scanWordOrFacet(input, i, tokens, diagnostics)
        }
    }
    return tokens
}

/** Handles bare words, `AND`/`OR`/`|`/`NOT` keywords, `field:value` facets (quoted or bare
 *  value), and a leading `-` negating either of the above. Returns the new scan position. */
private fun scanWordOrFacet(input: String, start: Int, tokens: MutableList<Token>, diagnostics: MutableList<Diagnostic>): Int {
    val n = input.length
    var i = start
    val negated = input[i] == '-' && i + 1 < n && (input[i + 1].isLetterOrDigit() || input[i + 1] == '_')
    if (negated) i++

    val identStart = i
    val ident = StringBuilder()
    while (i < n && (input[i].isLetterOrDigit() || input[i] == '_')) { ident.append(input[i]); i++ }

    if (ident.isNotEmpty() && i < n && input[i] == ':') {
        val field = ident.toString()
        i++
        val valueRaw: String
        if (i < n && input[i] == '"') {
            i++
            val sb = StringBuilder()
            var terminated = false
            while (i < n) {
                if (input[i] == '"') { terminated = true; i++; break }
                sb.append(input[i]); i++
            }
            if (!terminated) diagnostics.add(Diagnostic.UnterminatedQuote(start))
            valueRaw = "\"" + sb + "\""
        } else {
            val sb = StringBuilder()
            while (i < n && isWordChar(input[i])) { sb.append(input[i]); i++ }
            valueRaw = sb.toString()
        }
        val prefix = if (negated) "-" else ""
        tokens.add(Token(Token.Kind.FACET, "$prefix$field:$valueRaw", start))
        return i
    }

    // Not a facet after all: rewind and consume as a plain word (dash included, if any).
    i = start
    val sb = StringBuilder()
    while (i < n && isWordChar(input[i])) { sb.append(input[i]); i++ }
    if (sb.isEmpty()) return start + 1 // guard against an infinite loop on an unexpected char
    val word = sb.toString()
    when {
        word.equals("AND", ignoreCase = true) -> tokens.add(Token(Token.Kind.AND, word, start))
        word.equals("OR", ignoreCase = true) || word == "|" -> tokens.add(Token(Token.Kind.OR, word, start))
        word.equals("NOT", ignoreCase = true) -> tokens.add(Token(Token.Kind.NOT, word, start))
        else -> tokens.add(Token(Token.Kind.WORD, word, start))
    }
    return i
}

// The regex token carries its /i flag through the flat token list by suffixing the pattern
// with NUL + "i" (NUL can't appear in a typed query, so it can never collide with real
// pattern text). Written as the \u0000 ESCAPE, never as a literal NUL byte: a raw NUL here
// makes `file` report this .kt as "data", and grep/rg then skip it silently unless -a is
// passed — which is exactly how two audits missed this file entirely. Keep the escape.
private const val IGNORE_CASE_MARKER = "\u0000i"
private fun encodeRegex(pattern: String, ignoreCase: Boolean) = if (ignoreCase) pattern + IGNORE_CASE_MARKER else pattern
private fun decodeRegexPattern(encoded: String) = encoded.removeSuffix(IGNORE_CASE_MARKER)
private fun decodeRegexIgnoreCase(encoded: String) = encoded.endsWith(IGNORE_CASE_MARKER)

// ============================== parser ==============================

private class Parser(
    private val tokens: List<Token>,
    private val diagnostics: MutableList<Diagnostic>,
    private val nowMillis: Long,
    private val zone: ZoneId,
) {
    private var pos = 0
    private fun peek(): Token? = tokens.getOrNull(pos)
    private fun advance(): Token? = tokens.getOrNull(pos++)

    fun parseQuery(): QueryNode? = if (tokens.isEmpty()) null else parseOr()

    private fun parseOr(): QueryNode? {
        val first = parseAnd() ?: return null
        val children = mutableListOf(first)
        while (peek()?.kind == Token.Kind.OR) {
            advance()
            parseAnd()?.let(children::add)
        }
        return if (children.size == 1) children[0] else QueryNode.Or(children)
    }

    private fun parseAnd(): QueryNode? {
        val first = parseUnary() ?: return null
        val children = mutableListOf(first)
        while (true) {
            val t = peek() ?: break
            if (t.kind == Token.Kind.OR || t.kind == Token.Kind.RPAREN) break
            if (t.kind == Token.Kind.AND) advance() // explicit AND; juxtaposition (no token) also ANDs
            val next = parseUnary() ?: break
            children.add(next)
        }
        return if (children.size == 1) children[0] else QueryNode.And(children)
    }

    private fun parseUnary(): QueryNode? {
        val t = peek() ?: return null
        if (t.kind == Token.Kind.NOT) {
            advance()
            return parseUnary()?.let(QueryNode::Not)
        }
        if (t.kind == Token.Kind.WORD && t.text.length > 1 && t.text[0] == '-') {
            advance()
            val bare = t.text.substring(1)
            val prefix = bare.endsWith("*")
            return QueryNode.Not(QueryNode.Term(if (prefix) bare.dropLast(1) else bare, prefix))
        }
        if (t.kind == Token.Kind.FACET && t.text.startsWith("-")) {
            advance()
            return QueryNode.Not(parseFacetBody(t.text.substring(1), t.start))
        }
        return parsePrimary()
    }

    private fun parsePrimary(): QueryNode? {
        val t = peek() ?: return null
        return when (t.kind) {
            Token.Kind.LPAREN -> {
                advance()
                val inner = parseOr()
                if (peek()?.kind == Token.Kind.RPAREN) advance() // tolerate a missing close paren
                inner
            }
            Token.Kind.PHRASE -> { advance(); QueryNode.Phrase(t.text) }
            Token.Kind.REGEX -> {
                advance()
                QueryNode.Regex(decodeRegexPattern(t.text), decodeRegexIgnoreCase(t.text))
            }
            // `?term` still parses to a Semantic node — QueryCompiler degrades it to a plain
            // prefix term so the query keeps running (hard rule 1) — but the user is told out
            // loud that the semantic half didn't happen. Diagnostic.SemanticUnavailable and its
            // UI copy both already existed; nothing emitted it, so `?foo` silently ran as a
            // lexical search and looked like semantic search working.
            Token.Kind.SEMANTIC -> {
                advance()
                diagnostics.add(Diagnostic.SemanticUnavailable(t.text))
                QueryNode.Semantic(t.text)
            }
            Token.Kind.FACET -> { advance(); parseFacetBody(t.text, t.start) }
            Token.Kind.WORD -> {
                advance()
                val prefix = t.text.endsWith("*")
                QueryNode.Term(if (prefix) t.text.dropLast(1) else t.text, prefix)
            }
            // A stray close-paren, or a keyword in a position that isn't valid grammar (e.g. two
            // ORs in a row) — nothing more to parse here; the caller's loop just stops. Never throws.
            Token.Kind.RPAREN, Token.Kind.AND, Token.Kind.OR, Token.Kind.NOT -> null
        }
    }

    private fun parseFacetBody(raw: String, at: Int): QueryNode {
        val colon = raw.indexOf(':')
        val fieldRaw = raw.substring(0, colon)
        var valueRaw = raw.substring(colon + 1)
        if (valueRaw.length >= 2 && valueRaw.startsWith("\"") && valueRaw.endsWith("\"")) {
            valueRaw = valueRaw.substring(1, valueRaw.length - 1)
        }

        val field = Field.fromKey(fieldRaw)
        if (field == null) {
            diagnostics.add(Diagnostic.UnknownField(fieldRaw, suggestField(fieldRaw)))
            return QueryNode.Term("$fieldRaw:$valueRaw", prefix = false)
        }

        var op = Op.EQ
        var value = valueRaw
        when {
            value.startsWith(">=") -> { op = Op.GTE; value = value.removePrefix(">=") }
            value.startsWith("<=") -> { op = Op.LTE; value = value.removePrefix("<=") }
            value.startsWith(">") -> { op = Op.GT; value = value.removePrefix(">") }
            value.startsWith("<") -> { op = Op.LT; value = value.removePrefix("<") }
            value.contains("..") -> op = Op.RANGE
        }

        if (field == Field.BEFORE || field == Field.AFTER || field == Field.DURING) {
            val validDate = if (op == Op.RANGE) {
                val (from, to) = value.split("..", limit = 2).let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
                RelativeDate.resolveRange(from, nowMillis, zone) != null && RelativeDate.resolveRange(to, nowMillis, zone) != null
            } else {
                RelativeDate.resolveRange(value, nowMillis, zone) != null
            }
            if (!validDate) diagnostics.add(Diagnostic.InvalidDate(field, value))
        }

        // unbackedReason, not isBacked: isBacked answers "does a real predicate exist" (and must
        // keep saying yes for is:archived, or -is:archived would stop excluding anything the day
        // archiving is wired up), while this diagnostic is about "can this return anything
        // today". is:archived was the one facet that fell through the gap — real predicate, no
        // data, no explanation, silent zero.
        if (unbackedReason(field, value) != null) {
            diagnostics.add(Diagnostic.UnindexedFacet(field, value))
        }
        return QueryNode.Facet(field, op, value)
    }
}

private fun suggestField(typed: String): String? {
    val normalized = typed.lowercase()
    return Field.allKeys
        .map { it to levenshtein(it, normalized) }
        .filter { it.second <= 2 }
        .minByOrNull { it.second }
        ?.first
}

private fun levenshtein(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) {
                dp[i - 1][j - 1]
            } else {
                1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
    }
    return dp[a.length][b.length]
}
