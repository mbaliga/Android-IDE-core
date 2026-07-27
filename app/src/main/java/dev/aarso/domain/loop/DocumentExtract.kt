package dev.aarso.domain.loop

/**
 * Converts raw HTML into plain text suitable for feeding to the [Distiller] — the "paste a
 * URL instead of pasted text" intake (docs/design/loop-distillation.md). Strips script/style/
 * nav/header/footer and comments, drops all remaining tags (turning block-level closes into
 * newlines so paragraphs stay separated), decodes the common HTML entities, then normalises
 * whitespace and caps the length so a long article can't blow the model's context.
 *
 * Pure JVM Kotlin — regex/string ops only, no Android dependency, matching every other file
 * under `domain/`.
 */
object DocumentExtract {

    private const val MAX_CHARS = 20_000
    private const val TRUNCATION_MARKER = "\n\n[truncated — article continues]"

    private val STRIP_TAGS = listOf("script", "style", "noscript", "nav", "header", "footer")

    private val COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
    private val BLOCK_CLOSE_REGEX = Regex(
        "</(?:p|div|li|ul|ol|h[1-6]|section|article|blockquote|tr|table|pre)\\s*>",
        RegexOption.IGNORE_CASE,
    )
    private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("<[^>]*>")
    private val NUMERIC_ENTITY_REGEX = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
    private val BLANK_LINES_REGEX = Regex("\n{3,}")
    private val LINE_WHITESPACE_REGEX = Regex("[ \\t\\x0B\\f\\r]+")

    fun extractText(html: String): String {
        var text = COMMENT_REGEX.replace(html, "")
        for (tag in STRIP_TAGS) {
            text = stripTagAndContent(text, tag)
        }
        text = BR_REGEX.replace(text, "\n")
        text = BLOCK_CLOSE_REGEX.replace(text) { "\n" }
        text = TAG_REGEX.replace(text, "")
        text = decodeEntities(text)
        text = normalizeWhitespace(text)
        return truncate(text)
    }

    private fun stripTagAndContent(html: String, tag: String): String {
        val regex = Regex("<$tag\\b[^>]*>[\\s\\S]*?</$tag\\s*>", RegexOption.IGNORE_CASE)
        return regex.replace(html, "")
    }

    private fun decodeEntities(s: String): String {
        var out = s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        out = NUMERIC_ENTITY_REGEX.replace(out) { m ->
            val body = m.groupValues[1]
            val code = if (body.startsWith("x") || body.startsWith("X")) {
                body.substring(1).toIntOrNull(16)
            } else {
                body.toIntOrNull()
            }
            if (code != null && code in 0..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return out
    }

    private fun normalizeWhitespace(s: String): String {
        val lines = s.split("\n").map { line ->
            LINE_WHITESPACE_REGEX.replace(line, " ").trim()
        }
        val collapsed = BLANK_LINES_REGEX.replace(lines.joinToString("\n"), "\n\n")
        return collapsed.trim()
    }

    private fun truncate(s: String): String {
        if (s.length <= MAX_CHARS) return s
        val cut = s.lastIndexOf('\n', MAX_CHARS - 1)
        val head = if (cut > 0) s.substring(0, cut) else s.substring(0, MAX_CHARS)
        return head.trimEnd() + TRUNCATION_MARKER
    }
}
