package dev.fonebrew.watch.core

/**
 * Splits reply text into screen-sized pieces. A `ScalingLazyColumn` item taller than the viewport
 * cannot be scrolled *within*, so a long reply must be many items, not one. The split changes no
 * characters: it cuts at blank lines first, then at the last space before [maxChars] (a hard cut only
 * if a word is longer than the limit, and never inside a surrogate pair), and trims only the
 * whitespace at the cut itself. Rejoining the pieces with a space reproduces the text's words in order.
 */
object ReplyPaging {
    const val DEFAULT_MAX_CHARS: Int = 220

    fun pages(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        require(maxChars >= 8) { "maxChars too small to page: $maxChars" }
        val out = ArrayList<String>()
        for (paragraph in text.split(Regex("\n\\s*\n"))) {
            var rest = paragraph.trim()
            while (rest.length > maxChars) {
                var cut = rest.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 } ?: maxChars
                if (cut > 0 && rest[cut - 1].isHighSurrogate()) cut--
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
        }
        return out
    }
}
