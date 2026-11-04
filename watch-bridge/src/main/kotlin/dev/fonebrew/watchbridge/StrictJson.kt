package dev.fonebrew.watchbridge

/** An internal decode failure; carries the human-readable reason that becomes [DecodeResult.Malformed.reason]. */
internal class WireException(message: String) : Exception(message)

/**
 * A strict RFC 8259 validator for the envelope text, run **before** the platform's `org.json` ever
 * sees it. Why: Android's `org.json` parser is lenient (single-quoted strings, unquoted keys, `//`
 * `/* */` `#` comments, `;`/`=>` separators, trailing data after the value) and last-key-wins on
 * duplicates, while the reference parser used by JVM tests differs in each of those. Validating
 * first makes "malformed JSON fails whole" mean the same thing on the phone, on the watch and in the
 * tests, instead of depending on which platform parser happens to run.
 *
 * Also: the nesting depth is capped ([MAX_DEPTH]) so hostile input cannot overflow the stack, and
 * duplicate object keys are rejected (a repeated `"usage"` must not be resolved by platform luck).
 * Validation only — it builds nothing. Recursion is bounded by [MAX_DEPTH].
 */
internal object StrictJson {
    /** Deepest legal v1 message is envelope{payload{tasks[{...}]}} = 4; this leaves ample headroom. */
    const val MAX_DEPTH: Int = 16

    /** Throws [WireException] unless [text] is exactly one JSON object (plus optional whitespace). */
    fun requireSingleObject(text: String) {
        val parser = Parser(text)
        parser.skipWhitespace()
        if (parser.atEnd()) parser.fail("empty input")
        if (parser.peek() != '{') parser.fail("top-level JSON value must be an object")
        parser.value(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) parser.fail("unexpected data after the JSON object")
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= s.length
        fun peek(): Char = s[pos]

        fun fail(message: String): Nothing = throw WireException("malformed JSON: $message (at offset $pos)")

        fun skipWhitespace() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        fun value(depth: Int) {
            if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH levels")
            skipWhitespace()
            if (atEnd()) fail("unexpected end of input")
            when (val c = peek()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected character")
            }
        }

        private fun obj(depth: Int) {
            pos++ // {
            skipWhitespace()
            if (!atEnd() && peek() == '}') { pos++; return }
            val keys = HashSet<String>()
            while (true) {
                skipWhitespace()
                if (atEnd() || peek() != '"') fail("expected a quoted object key")
                val key = string()
                if (!keys.add(key)) fail("duplicate object key")
                skipWhitespace()
                if (atEnd() || peek() != ':') fail("expected ':' after object key")
                pos++
                value(depth + 1)
                skipWhitespace()
                if (atEnd()) fail("unterminated object")
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return }
                    else -> fail("expected ',' or '}' in object")
                }
            }
        }

        private fun arr(depth: Int) {
            pos++ // [
            skipWhitespace()
            if (!atEnd() && peek() == ']') { pos++; return }
            while (true) {
                value(depth + 1)
                skipWhitespace()
                if (atEnd()) fail("unterminated array")
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return }
                    else -> fail("expected ',' or ']' in array")
                }
            }
        }

        /** Parses a string literal and returns its decoded value (used for key de-duplication). */
        private fun string(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("truncated \\u escape")
                                var code = 0
                                repeat(4) {
                                    // ASCII hex only: Character.digit would also accept non-ASCII digits.
                                    val h = s[pos++]
                                    val d = when (h) {
                                        in '0'..'9' -> h - '0'
                                        in 'a'..'f' -> h - 'a' + 10
                                        in 'A'..'F' -> h - 'A' + 10
                                        else -> fail("invalid \\u escape")
                                    }
                                    code = code * 16 + d
                                }
                                out.append(code.toChar())
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("unescaped control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, pos)) fail("invalid literal")
            pos += word.length
        }

        private fun number() {
            if (peek() == '-') pos++
            if (atEnd()) fail("invalid number")
            if (peek() == '0') {
                pos++
            } else if (peek() in '1'..'9') {
                while (!atEnd() && peek() in '0'..'9') pos++
            } else {
                fail("invalid number")
            }
            if (!atEnd() && peek() == '.') {
                pos++
                if (atEnd() || peek() !in '0'..'9') fail("invalid number fraction")
                while (!atEnd() && peek() in '0'..'9') pos++
            }
            if (!atEnd() && (peek() == 'e' || peek() == 'E')) {
                pos++
                if (!atEnd() && (peek() == '+' || peek() == '-')) pos++
                if (atEnd() || peek() !in '0'..'9') fail("invalid number exponent")
                while (!atEnd() && peek() in '0'..'9') pos++
            }
        }
    }
}
