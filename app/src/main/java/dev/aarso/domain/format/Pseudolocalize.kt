package dev.aarso.domain.format

/**
 * **Pseudolocalize** — the i18n smoke alarm that goes off *before* a single translation exists.
 *
 * Doc 00 §3.3 asks for **pseudolocalization in CI**: a deterministic transform that takes a
 * source (English) string and returns a "fake translation" which is (a) **visibly accented** so an
 * untranslated string can never hide in plain sight, (b) **~40% longer** so a layout that will clip
 * or truncate German/Finnish/Tamil does so *today*, against English copy, in a screenshot test —
 * and (c) optionally **forced right-to-left** so the Urdu canary path is exercised without an Urdu
 * translator. This is how you ship enterprise-grade i18n *without yet having translations*: the
 * truncation, clipping and RTL-mirroring bugs surface in CI, the translators land later into a
 * layout that already survives them.
 *
 * **Design constraints (on-thesis: legible + testable):**
 *  - **Pure and deterministic.** No randomness — the expansion is a *fixed function of length*, so
 *    a JVM unit test (and a CI snapshot) gets the same output every run. No Android imports, no
 *    locale/default reads; safe to call from anywhere in `domain/`.
 *  - **Readable, not garbled.** The accent map is a look-alike substitution (`a`→`á`, `e`→`é`, …),
 *    so a reviewer can still *read* the string and tell it apart from a real, missing translation.
 *  - **Placeholder-safe (the critical invariant).** Format placeholders are left **byte-for-byte
 *    untouched**: ICU `{count}` / `{0}` braces and printf-style `%s` / `%d` / `%1$s` tokens are
 *    never accented, never split by the expansion filler, never reordered. A pseudolocalized
 *    string therefore still `MessageFormat`/`String.format`s correctly. [isPlaceholderSafe] is the
 *    CI assertion that proves it for a given (source → pseudo) pair.
 *
 * Pure domain, JVM-tested. Companion to [LocaleFormat] in the same i18n family.
 */
object Pseudolocalize {

    /**
     * The bracket pair that frames every expanded string. Distinctive, single-glyph, and absent
     * from ordinary copy, so a CI screenshot reviewer (and [isPlaceholderSafe]'s caller) can spot
     * a pseudolocalized run at a glance, and so the expansion filler has unambiguous boundaries.
     */
    private const val OPEN = '⟦' // ⟦ MATHEMATICAL LEFT WHITE SQUARE BRACKET
    private const val CLOSE = '⟧' // ⟧ MATHEMATICAL RIGHT WHITE SQUARE BRACKET

    /** The middle-dot used to pad the string out to the expansion ratio. */
    private const val FILLER = '·' // · MIDDLE DOT

    /** Unicode RIGHT-TO-LEFT EMBEDDING — opens a forced-RTL run. */
    private const val RLE = '‫'

    /** Unicode POP DIRECTIONAL FORMATTING — closes the [RLE] run. */
    private const val PDF = '‬'

    /**
     * Target expansion ratio. Pseudolocalized output is padded so that its *visible* length
     * (accented body + filler, excluding the framing brackets and any RTL marks) is at least
     * **1.40×** the visible length of the input — the canonical "~40% longer" budget that
     * approximates the worst-case real-language expansion (German/Finnish/Tamil). The filler run
     * is therefore `ceil(visibleLen * 0.40)` middle-dots, deterministic in the input length.
     */
    const val EXPANSION_RATIO: Double = 1.40

    /**
     * Accent look-alike map for the ASCII letters. Each entry is a visually similar accented glyph,
     * chosen so the result is still *legible* (a reviewer reads "Sénd" and recognises "Send"). Any
     * character not in this map — digits, punctuation, whitespace, already-accented or non-Latin
     * glyphs, and **every character inside a format placeholder** — is passed through unchanged.
     */
    private val ACCENTS: Map<Char, Char> = mapOf(
        'a' to 'á', 'b' to 'ƀ', 'c' to 'ç', 'd' to 'ð', 'e' to 'é', 'f' to 'ƒ', 'g' to 'ĝ',
        'h' to 'ĥ', 'i' to 'í', 'j' to 'ĵ', 'k' to 'ķ', 'l' to 'ĺ', 'm' to 'ɱ', 'n' to 'ñ',
        'o' to 'ó', 'p' to 'ƥ', 'q' to 'ɋ', 'r' to 'ŕ', 's' to 'š', 't' to 'ţ', 'u' to 'ú',
        'v' to 'ʋ', 'w' to 'ŵ', 'x' to 'ẋ', 'y' to 'ý', 'z' to 'ž',
        'A' to 'Á', 'B' to 'Ɓ', 'C' to 'Ç', 'D' to 'Ð', 'E' to 'É', 'F' to 'Ƒ', 'G' to 'Ĝ',
        'H' to 'Ĥ', 'I' to 'Í', 'J' to 'Ĵ', 'K' to 'Ķ', 'L' to 'Ĺ', 'M' to 'Ṁ', 'N' to 'Ñ',
        'O' to 'Ó', 'P' to 'Ƥ', 'Q' to 'Ɋ', 'R' to 'Ŕ', 'S' to 'Š', 'T' to 'Ţ', 'U' to 'Ú',
        'V' to 'Ʋ', 'W' to 'Ŵ', 'X' to 'Ẋ', 'Y' to 'Ý', 'Z' to 'Ž',
    )

    /**
     * A single regex that recognises the two placeholder families we must protect verbatim:
     *  - **ICU / `MessageFormat`** braces: `{0}`, `{count}`, `{count, plural, ...}` — matched as a
     *    balanced single-level `{...}` run (the level used across the app's templates).
     *  - **printf / `String.format`** tokens: `%s`, `%d`, `%1$s`, `%02d`, `%.2f`, and the literal
     *    `%%` escape — the standard conversion grammar (optional argument index, flags, width,
     *    precision, conversion letter).
     *
     * Anything this regex matches is copied through the transform untouched: not accented, and not
     * interrupted by expansion filler. Everything *between* matches is the translatable text.
     */
    private val PLACEHOLDER = Regex(
        // ICU single-level {...}                     printf %... (or %% escape)
        "\\{[^{}]*}" + "|" + "%(?:%|(?:\\d+\\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z])"
    )

    /**
     * Pseudolocalize [s].
     *
     * Pipeline (each step pure, the whole deterministic in [s]):
     *  1. **Segment** [s] into placeholder runs (left intact) and translatable runs.
     *  2. **Accent** each translatable run via [ACCENTS]; placeholders pass through verbatim.
     *  3. **Expand** (when [expand]) by appending a [FILLER] run sized to reach [EXPANSION_RATIO]
     *     of the visible length, then frame the whole accented+filler body in [OPEN]…[CLOSE]
     *     brackets — so a clipped layout reveals itself and the framing is unmistakable in CI.
     *  4. **Force RTL** (when [rtl]) by wrapping the result in [RLE]…[PDF] embedding marks — the
     *     Urdu canary, without an Urdu string.
     *
     * The empty string maps to the empty string when [expand] is false; with [expand] it becomes an
     * empty bracketed run (`⟦⟧`) — still zero translatable characters, still placeholder-safe.
     *
     * @param s the source (English) string.
     * @param expand pad to [EXPANSION_RATIO] and frame in brackets (default `true`).
     * @param rtl wrap in [RLE]…[PDF] to force right-to-left rendering (default `false`).
     */
    fun transform(s: String, expand: Boolean = true, rtl: Boolean = false): String {
        val accented = accentPreservingPlaceholders(s)

        val body = if (expand) {
            val visibleLen = accented.length
            // Fixed function of length: pad up to ceil(visibleLen * RATIO).
            val target = Math.ceil(visibleLen * EXPANSION_RATIO).toInt()
            val fillerCount = (target - visibleLen).coerceAtLeast(0)
            val sb = StringBuilder(target + 2)
            sb.append(OPEN).append(accented)
            repeat(fillerCount) { sb.append(FILLER) }
            sb.append(CLOSE)
            sb.toString()
        } else {
            accented
        }

        return if (rtl) "$RLE$body$PDF" else body
    }

    /**
     * Accent every translatable character of [s] while copying each matched [PLACEHOLDER] run
     * through unchanged. This is the placeholder-safety guarantee in code: the only characters ever
     * substituted are the ones *outside* a placeholder match.
     */
    private fun accentPreservingPlaceholders(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        var index = 0
        for (match in PLACEHOLDER.findAll(s)) {
            // Accent the translatable gap before this placeholder…
            if (match.range.first > index) {
                accentInto(sb, s, index, match.range.first)
            }
            // …then copy the placeholder verbatim.
            sb.append(match.value)
            index = match.range.last + 1
        }
        // Trailing translatable tail after the last placeholder.
        if (index < s.length) accentInto(sb, s, index, s.length)
        return sb.toString()
    }

    /** Append `s[from, until)` to [sb], substituting each char via [ACCENTS] (unmapped → as-is). */
    private fun accentInto(sb: StringBuilder, s: String, from: Int, until: Int) {
        var i = from
        while (i < until) {
            val c = s[i]
            sb.append(ACCENTS[c] ?: c)
            i++
        }
    }

    /**
     * CI assertion helper: does [pseudo] carry **exactly the same multiset of format placeholders**
     * as [original]? This is the invariant a pseudolocalization step must never break — if a `{0}`,
     * `%s` or `%1$d` were accented, split by filler, dropped or duplicated, the eventual
     * `MessageFormat`/`String.format` would throw or mis-substitute at runtime. A string with **no**
     * placeholders is trivially safe (both sides yield the empty multiset → `true`).
     *
     * Compares as a *sorted multiset* (not a set), so duplicate placeholders (`"{0} and {0}"`) must
     * survive in the same count. Order is intentionally **not** required — the transform never
     * reorders today, but a future locale-aware reordering would still be placeholder-safe as long
     * as the same tokens are present the same number of times.
     */
    fun isPlaceholderSafe(original: String, pseudo: String): Boolean {
        val a = PLACEHOLDER.findAll(original).map { it.value }.sorted().toList()
        val b = PLACEHOLDER.findAll(pseudo).map { it.value }.sorted().toList()
        return a == b
    }
}
