package dev.fonebrew.domain.workspace.ignore

/**
 * Glob matcher implementing the documented gitignore / gitattributes pattern language
 * (`man gitignore`, "PATTERN FORMAT"): `?` one non-slash char, `*` any run of non-slash chars,
 * `[...]` a class (ranges, `!`/`^` negation, POSIX `[:alpha:]` names, never matches `/` in
 * pathname mode), `\x` a literal, and the three `**` forms: a leading `**` + `/` and a middle
 * slash, two stars, slash in the middle match zero or more directories, a trailing slash plus two stars matches everything inside. Any other
 * `**` behaves like `*`. Written from the public documentation, not ported from any source.
 *
 * Patterns are compiled once into tokens; matching is a memoised walk so a pattern like
 * `a*a*a*a*b` cannot go exponential on a long non-matching name.
 */
class Wildmatch private constructor(private val tokens: List<Token>, private val pathname: Boolean) {

    internal sealed interface Token {
        data class Lit(val c: Char) : Token
        object AnyOne : Token
        object Star : Token
        /** `**` followed by `/` at the start or after a `/`: zero or more whole directories. */
        object DirsSlash : Token
        /** Trailing `**` after a `/` (or the whole pattern): everything, slashes included. */
        object Rest : Token
        data class Cls(val negate: Boolean, val items: List<ClsItem>) : Token
        /** Unterminated `[`: git aborts the match, so it can never match. */
        object Never : Token
    }

    internal sealed interface ClsItem {
        data class Range(val lo: Char, val hi: Char) : ClsItem
        data class Named(val name: String) : ClsItem
    }

    fun matches(text: String): Boolean {
        val n = tokens.size
        val m = text.length
        // memo[t * (m + 1) + i]: 0 unknown, 1 true, 2 false
        val memo = ByteArray((n + 1) * (m + 1))
        return go(0, 0, text, memo)
    }

    private fun go(t: Int, i: Int, text: String, memo: ByteArray): Boolean {
        val key = t * (text.length + 1) + i
        val seen = memo[key]
        if (seen.toInt() != 0) return seen.toInt() == 1
        val r = step(t, i, text, memo)
        memo[key] = if (r) 1 else 2
        return r
    }

    private fun step(t: Int, i: Int, text: String, memo: ByteArray): Boolean {
        if (t == tokens.size) return i == text.length
        when (val tok = tokens[t]) {
            is Token.Lit -> return i < text.length && text[i] == tok.c && go(t + 1, i + 1, text, memo)
            Token.AnyOne -> return i < text.length && !(pathname && text[i] == '/') && go(t + 1, i + 1, text, memo)
            Token.Star -> {
                var j = i
                while (true) {
                    if (go(t + 1, j, text, memo)) return true
                    if (j >= text.length || (pathname && text[j] == '/')) return false
                    j++
                }
            }
            Token.DirsSlash -> {
                // zero directories, or consume one `segment/` and stay on this token.
                if (go(t + 1, i, text, memo)) return true
                var j = i
                while (j < text.length && text[j] != '/') j++
                if (j >= text.length) return false
                return go(t, j + 1, text, memo)
            }
            Token.Rest -> return true
            is Token.Cls -> {
                if (i >= text.length) return false
                val c = text[i]
                if (pathname && c == '/') return false
                if (classMatches(tok, c)) return go(t + 1, i + 1, text, memo)
                return false
            }
            Token.Never -> return false
        }
    }

    private fun classMatches(cls: Token.Cls, c: Char): Boolean {
        var hit = false
        for (item in cls.items) {
            val ok = when (item) {
                is ClsItem.Range -> c in item.lo..item.hi
                is ClsItem.Named -> named(item.name, c)
            }
            if (ok) { hit = true; break }
        }
        return hit != cls.negate
    }

    private fun named(name: String, c: Char): Boolean = when (name) {
        "alpha" -> c.isLetter()
        "digit" -> c in '0'..'9'
        "alnum" -> c.isLetterOrDigit()
        "upper" -> c.isUpperCase()
        "lower" -> c.isLowerCase()
        "space" -> c == ' ' || c in '\t'..'\r'
        "blank" -> c == ' ' || c == '\t'
        "punct" -> c.code in 33..126 && !c.isLetterOrDigit()
        "xdigit" -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        "cntrl" -> c.code < 32 || c.code == 127
        "print" -> c.code in 32..126
        "graph" -> c.code in 33..126
        else -> false
    }

    companion object {
        fun compile(pattern: String, pathname: Boolean): Wildmatch = Wildmatch(tokenize(pattern), pathname)

        fun matches(pattern: String, text: String, pathname: Boolean): Boolean =
            compile(pattern, pathname).matches(text)

        private val NAMES = setOf(
            "alpha", "digit", "alnum", "upper", "lower", "space", "blank", "punct", "xdigit",
            "cntrl", "print", "graph",
        )

        internal fun tokenize(p: String): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    c == '\\' -> {
                        if (i + 1 < p.length) { out.add(Token.Lit(p[i + 1])); i += 2 }
                        else { out.add(Token.Never); i++ } // a lone trailing backslash matches nothing
                    }
                    c == '?' -> { out.add(Token.AnyOne); i++ }
                    c == '*' -> {
                        var j = i
                        while (j < p.length && p[j] == '*') j++
                        val run = j - i
                        val atStartOrAfterSlash = i == 0 || p[i - 1] == '/'
                        if (run >= 2 && atStartOrAfterSlash && j < p.length && p[j] == '/') {
                            out.add(Token.DirsSlash); i = j + 1
                        } else if (run >= 2 && atStartOrAfterSlash && j == p.length) {
                            out.add(Token.Rest); i = j
                        } else {
                            out.add(Token.Star); i = j
                        }
                    }
                    c == '[' -> {
                        val parsed = parseClass(p, i)
                        if (parsed == null) { out.add(Token.Never); i = p.length } else { out.add(parsed.first); i = parsed.second }
                    }
                    else -> { out.add(Token.Lit(c)); i++ }
                }
            }
            return out
        }

        private fun parseClass(p: String, start: Int): Pair<Token, Int>? {
            var i = start + 1
            var negate = false
            if (i < p.length && (p[i] == '!' || p[i] == '^')) { negate = true; i++ }
            val items = ArrayList<ClsItem>()
            var first = true
            while (i < p.length) {
                var c = p[i]
                if (c == ']' && !first) return Token.Cls(negate, items) to (i + 1)
                first = false
                if (c == '[' && i + 1 < p.length && p[i + 1] == ':') {
                    val end = p.indexOf(":]", i + 2)
                    if (end > 0) {
                        val name = p.substring(i + 2, end)
                        if (name in NAMES) { items.add(ClsItem.Named(name)); i = end + 2; continue }
                    }
                }
                if (c == '\\') {
                    i++
                    if (i >= p.length) return null
                    c = p[i]
                }
                if (i + 2 < p.length && p[i + 1] == '-' && p[i + 2] != ']') {
                    var hi = p[i + 2]
                    var adv = 3
                    if (hi == '\\') {
                        if (i + 3 >= p.length) return null
                        hi = p[i + 3]; adv = 4
                    }
                    items.add(ClsItem.Range(c, hi)); i += adv
                } else {
                    items.add(ClsItem.Range(c, c)); i++
                }
            }
            return null
        }
    }
}
