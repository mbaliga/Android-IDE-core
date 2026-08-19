package dev.fonebrew.domain.search

import java.util.Locale

/**
 * **Word-boundary segmentation** for the search index (Doc `FONEBREW_SEARCH_SPEC.md` §D2/M0).
 * This is a plain Android object — this codebase is not Kotlin Multiplatform (single
 * `:core-engine` Android module; confirmed during planning), so there is no `expect`/`actual`
 * split here, unlike the spec's original framing.
 *
 * ### The problem this exists to fix
 * A naive Unicode "word" definition (FTS5 `unicode61`'s default categories) treats an entire
 * unbroken Han/Khmer/Lao/Myanmar run as **one token** — `東京駅で会った` would be unsearchable by
 * anything shorter than the whole string — and treats Devanagari matras / the virama / Arabic
 * harakat as separators, shattering words mid-syllable (`कि` → `क` + break). [segment] fixes
 * both by doing **dictionary-aware word breaking in Kotlin**, before any text reaches an FTS5
 * column, so the FTS5 table only ever sees pre-segmented, space-joined tokens.
 *
 * ### Two boundary sources, one contract
 * The actual boundary detection is behind [BoundarySource] so this class is JVM-testable:
 * - [IcuBoundarySource] — `android.icu.text.BreakIterator`, ICU4C on-device. This is what ships
 *   to users; it has genuine dictionary data for Chinese/Japanese/Thai/Lao/Khmer/Myanmar. It is
 *   **not exercised by plain JVM unit tests** — the Android SDK stub throws outside a real
 *   device/emulator/Robolectric, none of which this repo uses (see CLAUDE.md's environment
 *   honesty rule). [defaultSource] detects this automatically and falls back so callers never
 *   need to think about it.
 * - [JavaTextBoundarySource] — `java.text.BreakIterator`, part of the standard JDK, runs
 *   identically on the JVM and on Android. Empirically (see `SegmenterTest`), it correctly
 *   handles Devanagari clusters, Arabic harakat, Thai dictionary words, ZWNJ/ZWJ, and
 *   mixed-script text — everything this file's tests assert. It does **not** have real
 *   dictionary data for pure Han runs (a lone-script Chinese sentence stays one token on it,
 *   same failure D2 exists to fix) — that gap is closed on-device by [IcuBoundarySource] alone,
 *   and is therefore **owner-verified**, not JVM-tested.
 *
 * ### The offset contract (must not be broken — see §3.4)
 * Each [TokenSpan] carries `startOriginal`/`endOriginal` into the **original, un-normalized**
 * text, plus the already-normalized token string. Normalization (NFKC via
 * [LexicalSearch.normalize]) can change a token's *length* (`ﬁ` → `fi`, `①` → `1`, half-width
 * katakana → full-width) without changing where it sits in the original text — offsets always
 * come from the boundary source over the original string, before normalization runs, so this
 * holds by construction.
 */
object Segmenter {

    /**
     * One segmented word: [startOriginal] (inclusive) / [endOriginal] (exclusive) index the
     * *original* text passed to [segment]; [normalized] is that substring after
     * [LexicalSearch.normalize] (NFKC + lowercase + whitespace collapse — a no-op for a single
     * word beyond the NFKC/lowercase folding).
     */
    data class TokenSpan(
        val startOriginal: Int,
        val endOriginal: Int,
        val normalized: String,
    )

    /** A source of Unicode word-boundary offsets into [text], `BreakIterator`-style: sorted,
     *  starting at `0` and ending at `text.length`, each consecutive pair delimiting one
     *  candidate word/separator unit ([segment] filters out the non-word ones). */
    fun interface BoundarySource {
        fun boundaries(text: String, locale: Locale): List<Int>
    }

    /** Production default: `android.icu.text.BreakIterator` (ICU4C), dictionary-aware for
     *  Chinese/Japanese/Thai/Lao/Khmer/Myanmar. Real-device behaviour only — see the class KDoc. */
    object IcuBoundarySource : BoundarySource {
        override fun boundaries(text: String, locale: Locale): List<Int> =
            collectIcuBoundaries(text) { android.icu.text.BreakIterator.getWordInstance(locale) }
    }

    /** JVM-portable fallback: `java.text.BreakIterator`. Used automatically by [defaultSource]
     *  when the ICU4C-backed Android API isn't available (plain JVM unit tests), and directly by
     *  `SegmenterTest` for deterministic, environment-independent assertions. */
    object JavaTextBoundarySource : BoundarySource {
        override fun boundaries(text: String, locale: Locale): List<Int> =
            collectJdkBoundaries(text) { java.text.BreakIterator.getWordInstance(locale) }
    }

    /** [IcuBoundarySource] on a real Android runtime, [JavaTextBoundarySource] otherwise —
     *  detected once by probing the ICU4C API and catching the stub failure a plain JVM unit
     *  test throws. Callers that want to force one or the other (tests) pass [BoundarySource]
     *  explicitly to [segment] instead of relying on this. */
    val defaultSource: BoundarySource by lazy {
        try {
            android.icu.text.BreakIterator.getWordInstance(Locale.ROOT).also { it.setText("a") }
            IcuBoundarySource
        } catch (_: Throwable) {
            // No real android.icu on this classpath (plain JVM unit test, unmocked Android
            // stub) — fall back so every caller gets a working segmenter without special-casing.
            JavaTextBoundarySource
        }
    }

    /** Segment [text] into words, dropping whitespace/punctuation-only runs. Empty for blank
     *  input. [boundarySource] defaults to [defaultSource] (real ICU on Android, JDK elsewhere). */
    fun segment(text: String, locale: Locale = Locale.ROOT, boundarySource: BoundarySource = defaultSource): List<TokenSpan> {
        if (text.isEmpty()) return emptyList()
        val bounds = boundarySource.boundaries(text, locale)
        val spans = ArrayList<TokenSpan>(bounds.size)
        for (i in 0 until bounds.size - 1) {
            val start = bounds[i]
            val end = bounds[i + 1]
            if (start >= end) continue
            val raw = text.substring(start, end)
            val normalized = LexicalSearch.normalize(raw)
            // Filter on the *normalized* form, not raw: some word-like symbols (e.g. ① "No"
            // category) aren't letters/digits themselves but NFKC-fold to ones that are (e.g.
            // "1") — checking post-normalization is what actually decides "is this a word".
            if (normalized.isNotEmpty() && normalized.any { it.isLetterOrDigit() }) {
                spans.add(TokenSpan(start, end, normalized))
            }
        }
        return spans
    }

    /** [segment] then space-join the normalized tokens — what actually gets written into an
     *  FTS5 `title`/`snippet`/`body` column (Doc §3.2: "pre-segmented, space-joined tokens"). */
    fun tokenizeForIndex(text: String, locale: Locale = Locale.ROOT, boundarySource: BoundarySource = defaultSource): String =
        segment(text, locale, boundarySource).joinToString(" ") { it.normalized }

    private inline fun collectJdkBoundaries(text: String, newIterator: () -> java.text.BreakIterator): List<Int> {
        val it = newIterator()
        it.setText(text)
        val out = ArrayList<Int>()
        var b = it.first()
        out.add(b)
        while (b != java.text.BreakIterator.DONE) {
            val next = it.next()
            if (next == java.text.BreakIterator.DONE) break
            out.add(next)
            b = next
        }
        if (out.last() != text.length) out.add(text.length)
        return out
    }

    // android.icu.text.BreakIterator has the identical first()/next()/DONE shape as
    // java.text.BreakIterator but is not a subtype of it, so IcuBoundarySource needs its own
    // copy rather than reusing the helper above (kept as a distinctly-named twin, not an
    // overload — Kotlin can't disambiguate two `() -> BreakIterator`-shaped overloads by their
    // different `BreakIterator` erasure alone).
    private inline fun collectIcuBoundaries(text: String, newIterator: () -> android.icu.text.BreakIterator): List<Int> {
        val it = newIterator()
        it.setText(text)
        val out = ArrayList<Int>()
        var b = it.first()
        out.add(b)
        while (b != android.icu.text.BreakIterator.DONE) {
            val next = it.next()
            if (next == android.icu.text.BreakIterator.DONE) break
            out.add(next)
            b = next
        }
        if (out.last() != text.length) out.add(text.length)
        return out
    }
}
