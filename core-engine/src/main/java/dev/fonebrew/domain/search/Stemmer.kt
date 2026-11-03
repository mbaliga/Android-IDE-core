package dev.fonebrew.domain.search

/**
 * **English suffix stemming** for the search index (Doc `FONEBREW_SEARCH_SPEC.md` — see
 * [Segmenter] and [dev.fonebrew.data.search.SearchIndexer] for where this plugs in). A pure,
 * dependency-free, in-repo implementation of the Porter stemming algorithm (Porter, "An
 * Algorithm for Suffix Stripping", 1980) — `caresses`→`caress`, `running`→`run`,
 * `relational`→`relate` — so a query for "running" finds a document that only ever says "runs",
 * and vice versa.
 *
 * ### Why stemming, and why here
 * FTS5's own tokenizer (`unicode61`, see `Search.sq`) does word segmentation and case-folding,
 * not morphology — it has no notion that "runs" and "running" share a root. Without a stemmer,
 * `conv_fts`/`loop_fts`/`task_fts` can only ever do exact (or prefix-glob) token matching, so a
 * search misses every inflected form it didn't spell out. [stem] closes that gap entirely in
 * Kotlin, upstream of FTS5 — consistent with [Segmenter]'s own rationale for doing word-boundary
 * detection in Kotlin rather than leaning on the tokenizer for something it isn't built for.
 *
 * ### One deliberate deviation from the 1980 paper: Step 1c
 * Every step below (1a, 1b, 2, 3, 4, 5a, 5b) is the classic algorithm verbatim — [StemmerTest]
 * pins the paper's own worked examples (`caresses`→`caress`, `feed`→`feed`, `motoring`→`motor`,
 * `sing`→`sing`, `adjustable`→`adjust`, `probate`→`probat`, `cease`→`ceas`,
 * `controlling`→`control`, …) byte-for-byte — including the algorithm's own well-documented
 * "keeps going" property: a word a step transformed is not exempt from every later step, so a
 * few paper examples that look "finished" after the step that introduces them keep reducing
 * further (`agreed`→`agree` in Step 1b's own illustration, then Step 5a removes that same `e`
 * again → `agre`; `relational`→`relate` in Step 2, then Step 5a → `relat`;
 * `electriciti`→`electric` in Step 3, then Step 4 strips that same `ic` → `electr` — see
 * [StemmerTest] for the full derivation of each). Step 1c
 * (`Y`→`I`) is the one exception: the 1980 paper fires it whenever the stem *contains a vowel
 * anywhere* (`*v*`), which is well known to mangle extremely common English words ending in a
 * vowel-then-`y` — `deploy`→`deploi`, `display`→`displai`, `essay`→`essai`, `monkey`→`monkei`,
 * `journey`→`journei`. That is not a hypothetical: `deploy` is real vocabulary in this codebase's
 * own search-domain test fixtures, and a stemmer that turns it into `deploi` would silently break
 * exact-word search for it. [step1c] instead uses the narrower, later Porter2/Snowball condition
 * — replace a final `y`/`Y` with `i` only when the immediately preceding letter is a genuine
 * consonant that is *not* the word's first letter (`cry`→`cri`, `happy`→`happi`; `by`→`by`,
 * `say`→`say`, `deploy`→`deploy`, `monkey`→`monkey` all stay unchanged). This is the one place
 * this file departs from a pure 1980 reading, and it is a strict improvement for exactly this
 * codebase's own vocabulary — not an ad-hoc special case.
 *
 * ### Symmetric application (the actual point)
 * [maybeStem]/[stemJoined] are called from both directions of the pipeline so the same word
 * always reduces to the same stored/queried token:
 * - **Index side**: [dev.fonebrew.data.search.SearchProjector]/[dev.fonebrew.data.search.LoopSearchProjector]/
 *   [dev.fonebrew.data.search.TaskSearchProjector] each stem the already-[Segmenter]-tokenized,
 *   space-joined text before it is written into `conv_projection`/`loop_projection`/
 *   `task_projection` (and, via the external-content sync triggers, into `conv_fts`/`loop_fts`/
 *   `task_fts`).
 * - **Query side**: [dev.fonebrew.domain.search.query.QueryCompiler] stems every bare term and
 *   every word inside a quoted phrase before building the FTS5 `MATCH` expression, so a document
 *   and a query using different inflections of the same word still land on the identical stemmed
 *   token. An **explicit** user-typed prefix (`run*`) is never stemmed — a literal prefix-glob is
 *   a request for exactly that spelling, not a request to find its whole word family.
 *
 * ### ASCII-only, everything else passes through unchanged
 * [isStemmable] requires every character to be an ASCII letter. CJK, Devanagari, Arabic-script,
 * digits, and mixed alphanumeric tokens ([Segmenter]'s own multi-script territory) are untouched
 * — English suffix rules have no meaning for them, and touching them would be actively wrong
 * (the Latin-suffix rules would fire on accidental byte patterns in an unrelated script).
 *
 * ### The [Segmenter] offset contract still holds
 * [Segmenter.TokenSpan.startOriginal]/[Segmenter.TokenSpan.endOriginal] always index the
 * original, un-normalized text (see that class's KDoc) — this file never touches [Segmenter] or
 * a [Segmenter.TokenSpan]. Stemming only ever transforms the *stored/queried string* the
 * projectors and [dev.fonebrew.domain.search.query.QueryCompiler] build afterward; it has no
 * offsets to preserve or break.
 *
 * ### Explicitly out of scope: fuzzy matching
 * Stemming closes the *morphological* gap ("running" vs "runs"), not the *typo* gap ("gradel"
 * vs "gradle"). Query-side fuzzy/typo-tolerant matching needs its own design pass (edit-distance
 * budget, how it composes with phrase search, UI for "did you mean") and is not part of this
 * change. A trigram-tokenizer sidecar — the usual SQLite answer to fuzzy matching — was
 * considered and rejected here: `conv_fts`/`loop_fts`/`task_fts` contractually receive
 * pre-segmented text from [Segmenter] (Doc §3.2), and `detail = 'full'` (required for phrase
 * search, see `Search.sq`) is a per-table tokenizer-level setting a second, differently-tokenized
 * FTS5 table would have to duplicate or fight with. Recorded here, not silently dropped.
 */
object Stemmer {

    /** True when every character of [token] is an ASCII letter (`a`-`z`/`A`-`Z`) and [token] is
     *  non-empty — the only tokens [stem] is meaningful for (see class KDoc). */
    fun isStemmable(token: String): Boolean =
        token.isNotEmpty() && token.all { it in 'a'..'z' || it in 'A'..'Z' }

    /** [stem]s [token] if [isStemmable], otherwise returns it unchanged. The single entry point
     *  both the indexer and [dev.fonebrew.domain.search.query.QueryCompiler] should use for one
     *  word — never call [stem] directly on unchecked input. */
    fun maybeStem(token: String): String {
        if (!isStemmable(token)) return token
        val stemmed = stem(token)
        // Defensive only: no real English word reduces to nothing (the shortest possible input
        // Step 1a can hollow out is a lone "s" -> ""), but an empty stemmed token would silently
        // vanish from a space-joined string rather than degrade visibly — fall back to the
        // original token instead of ever emitting one.
        return stemmed.ifEmpty { token }
    }

    /**
     * Stems every whitespace-delimited token in [text] independently ([maybeStem] per token,
     * ASCII-alphabetic tokens only), rejoining with single spaces. This is the shape both
     * callers actually need: the indexer hands it [Segmenter.tokenizeForIndex]'s already
     * space-joined output, and [dev.fonebrew.domain.search.query.QueryCompiler] hands it a
     * phrase's raw text — in both cases "stem each word, keep word order" is exactly the
     * transformation, and FTS5's phrase search only cares about token sequence, not the original
     * whitespace run length.
     */
    fun stemJoined(text: String): String =
        text.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ") { maybeStem(it) }

    private val WHITESPACE = Regex("\\s+")

    /** Runs the full Porter algorithm (see class KDoc for the one Step 1c deviation) on [word].
     *  Case-insensitive: lowercases first (matching what [LexicalSearch.normalize] already
     *  produces upstream) and always returns lowercase, since that is what every caller — the
     *  already-lowercased index text, and FTS5's own case-folding tokenizer — expects. Prefer
     *  [maybeStem] unless the caller has already verified [isStemmable] itself. */
    fun stem(word: String): String {
        if (word.isEmpty()) return word
        var s = word.lowercase()
        s = step1a(s)
        s = step1b(s)
        s = step1c(s)
        s = step2(s)
        s = step3(s)
        s = step4(s)
        s = step5a(s)
        s = step5b(s)
        return s
    }

    // ============================== consonant/vowel + measure ==============================
    //
    // Porter's own definitions (1980 §2): a consonant is any letter other than a/e/i/o/u, and
    // other than Y when Y is preceded by a consonant (so Y is a consonant at the start of a word
    // or right after a vowel, e.g. TOY's T and Y; a vowel right after a consonant, e.g.
    // SYZYGY's first and second Y). "Measure" m counts VC transitions in [C](VC)^m[V].

    private fun isConsonant(s: String, i: Int): Boolean = when (s[i]) {
        'a', 'e', 'i', 'o', 'u' -> false
        'y' -> if (i == 0) true else !isConsonant(s, i - 1)
        else -> true
    }

    private fun measure(s: String): Int {
        var i = 0
        val n = s.length
        while (i < n && isConsonant(s, i)) i++
        var m = 0
        while (i < n) {
            while (i < n && !isConsonant(s, i)) i++
            if (i >= n) break
            while (i < n && isConsonant(s, i)) i++
            m++
        }
        return m
    }

    /** `*v*` — the stem contains a vowel anywhere. */
    private fun containsVowel(s: String): Boolean = s.indices.any { !isConsonant(s, it) }

    /** `*d` — the stem ends with a double consonant (e.g. -TT, -SS, -LL). */
    private fun endsWithDoubleConsonant(s: String): Boolean =
        s.length >= 2 && s[s.length - 1] == s[s.length - 2] && isConsonant(s, s.length - 1)

    /** `*o` — the stem ends cvc (consonant-vowel-consonant), where the second consonant is not
     *  W, X, or Y (distinguishes "short" one-syllable stems like -WIL, -HOP from e.g. -OW). */
    private fun endsCvc(s: String): Boolean {
        val n = s.length
        if (n < 3) return false
        return isConsonant(s, n - 3) && !isConsonant(s, n - 2) && isConsonant(s, n - 1) && s[n - 1] !in "wxy"
    }

    // ==================================== Step 1a ====================================
    // SSES -> SS, IES -> I, SS -> SS, S -> (unconditional suffix pattern, no m-check)

    private fun step1a(s: String): String = when {
        s.endsWith("sses") -> s.dropLast(2)
        s.endsWith("ies") -> s.dropLast(2)
        s.endsWith("ss") -> s
        s.endsWith("s") -> s.dropLast(1)
        else -> s
    }

    // ==================================== Step 1b ====================================
    // (m>0) EED -> EE ; (*v*) ED -> ; (*v*) ING -> ; then, only if the ED/ING branch fired,
    // the AT/BL/IZ -> +E / double-consonant-undouble / (m=1 and *o) +E cleanup.

    private fun step1b(s: String): String = when {
        s.endsWith("eed") -> {
            val stem = s.dropLast(3)
            if (measure(stem) > 0) stem + "ee" else s
        }
        s.endsWith("ed") && containsVowel(s.dropLast(2)) -> step1bCleanup(s.dropLast(2))
        s.endsWith("ing") && containsVowel(s.dropLast(3)) -> step1bCleanup(s.dropLast(3))
        else -> s
    }

    private fun step1bCleanup(stem: String): String = when {
        stem.endsWith("at") || stem.endsWith("bl") || stem.endsWith("iz") -> stem + "e"
        endsWithDoubleConsonant(stem) && stem.last() !in "lsz" -> stem.dropLast(1)
        measure(stem) == 1 && endsCvc(stem) -> stem + "e"
        else -> stem
    }

    // ==================================== Step 1c ====================================
    // Porter2/Snowball's refinement of the 1980 rule — see class KDoc's "One deliberate
    // deviation" section for why: replace a final y/Y with i only when the letter immediately
    // before it is a genuine consonant that is not itself the first letter of the word.

    private fun step1c(s: String): String {
        if (!s.endsWith("y") || s.length < 2) return s
        val precedingIndex = s.length - 2
        return if (precedingIndex > 0 && isConsonant(s, precedingIndex)) s.dropLast(1) + "i" else s
    }

    // ==================================== Step 2 ====================================
    // (m>0) suffix -> replacement, longest suffix wins so e.g. ATIONAL is tried before TIONAL
    // (a word ending ATIONAL also structurally ends TIONAL) and IZATION before ATION.

    private val STEP2_SUFFIXES: List<Pair<String, String>> = listOf(
        "ational" to "ate", "tional" to "tion", "enci" to "ence", "anci" to "ance",
        "izer" to "ize", "abli" to "able", "alli" to "al", "entli" to "ent", "eli" to "e",
        "ousli" to "ous", "ization" to "ize", "ation" to "ate", "ator" to "ate", "alism" to "al",
        "iveness" to "ive", "fulness" to "ful", "ousness" to "ous", "aliti" to "al",
        "iviti" to "ive", "biliti" to "ble",
    ).sortedByDescending { it.first.length }

    private fun step2(s: String): String {
        for ((suffix, replacement) in STEP2_SUFFIXES) {
            if (s.endsWith(suffix)) {
                val stem = s.dropLast(suffix.length)
                return if (measure(stem) > 0) stem + replacement else s
            }
        }
        return s
    }

    // ==================================== Step 3 ====================================
    // (m>0) suffix -> replacement, same longest-match-wins shape as Step 2.

    private val STEP3_SUFFIXES: List<Pair<String, String>> = listOf(
        "icate" to "ic", "ative" to "", "alize" to "al", "iciti" to "ic", "ical" to "ic",
        "ful" to "", "ness" to "",
    ).sortedByDescending { it.first.length }

    private fun step3(s: String): String {
        for ((suffix, replacement) in STEP3_SUFFIXES) {
            if (s.endsWith(suffix)) {
                val stem = s.dropLast(suffix.length)
                return if (measure(stem) > 0) stem + replacement else s
            }
        }
        return s
    }

    // ==================================== Step 4 ====================================
    // (m>1) suffix -> (removed entirely); ION additionally requires the stem to end in S or T.

    private val STEP4_SUFFIXES: List<String> = listOf(
        "ement", "ance", "ence", "able", "ible", "ment",
        "ant", "ent", "ism", "ate", "iti", "ous", "ive", "ize",
        "al", "er", "ic", "ou",
    ).sortedByDescending { it.length }

    private fun step4(s: String): String {
        if (s.endsWith("ion")) {
            val stem = s.dropLast(3)
            if (measure(stem) > 1 && stem.isNotEmpty() && stem.last() in "st") return stem
        }
        for (suffix in STEP4_SUFFIXES) {
            if (s.endsWith(suffix)) {
                val stem = s.dropLast(suffix.length)
                return if (measure(stem) > 1) stem else s
            }
        }
        return s
    }

    // ==================================== Step 5a ====================================
    // (m>1) E -> ; (m=1 and not *o) E ->

    private fun step5a(s: String): String {
        if (!s.endsWith("e")) return s
        val stem = s.dropLast(1)
        val m = measure(stem)
        return if (m > 1 || (m == 1 && !endsCvc(stem))) stem else s
    }

    // ==================================== Step 5b ====================================
    // (m>1 and *d and *L) -> single letter — controll(ing) -> control, but roll -> roll (m=1).

    private fun step5b(s: String): String =
        if (s.endsWith("l") && endsWithDoubleConsonant(s) && measure(s) > 1) s.dropLast(1) else s
}
