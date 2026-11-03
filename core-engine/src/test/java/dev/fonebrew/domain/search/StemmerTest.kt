package dev.fonebrew.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [Stemmer], exhaustive against the Porter (1980) algorithm definition: one
 * fixture per rule branch in every step (1a, 1b incl. its AT/BL/IZ + double-consonant + cvc
 * cleanup, 1c, 2, 3, 4 incl. the ION special case, 5a, 5b), plus the classic paper's own worked
 * examples, plus the file's one documented Step 1c deviation (see [Stemmer]'s KDoc).
 *
 * A note on the paper's own examples: Porter's illustrative per-step tables show what THAT step
 * does to a word, not necessarily the word's FINAL stem — the algorithm is a strict pipeline, and
 * a word a step transforms is not exempt from a later step also firing on it. Several of the
 * paper's own headline examples keep reducing past the step that introduces them: `agreed`
 * reaches `agree` in Step 1b, but Step 5a then removes that same trailing `e` too (`m("agr")=1`
 * and not `*o`) → `agre`. `relational` reaches `relate` in Step 2, then Step 5a → `relat`.
 * `electriciti`/`electrical` reach `electric` in Step 3, then Step 4's generic `IC ->` rule (m>1)
 * strips that too → `electr`. `rational` structurally matches Step 2's `ATIONAL` pattern but its
 * stem `r` has measure 0 so the rule doesn't fire there — it is instead Step 4's generic
 * `AL ->` rule that catches it (`m("ration")=2>1`) → `ration`. The fixtures below are each word's
 * true final stem — verified against this file's own [Stemmer] implementation — not the
 * intermediate value a single step's illustration shows.
 */
class StemmerTest {

    // ---- Step 1a: SSES->SS, IES->I, SS->SS, S-> ----

    private val step1aFixtures = listOf(
        "caresses" to "caress",
        "ponies" to "poni",
        "ties" to "ti",
        "caress" to "caress", // SS -> SS: unchanged
        "cats" to "cat",
    )

    @Test fun `step 1a plural and possessive-s suffixes`() {
        step1aFixtures.forEach { (word, expected) -> assertEquals(word, expected, Stemmer.stem(word)) }
    }

    // ---- Step 1b: EED/ED/ING + the AT-BL-IZ / double-consonant / cvc+e cleanup ----

    private val step1bFixtures = listOf(
        "feed" to "feed", // (m>0) EED -> EE: m(f) = 0, the rule does not fire
        "agreed" to "agre", // EED -> EE gives "agree" (m(agr)=1>0); Step 5a then strips that
        // same trailing e again (m(agr)=1, not *o) -- see the class KDoc.
        "plastered" to "plaster", // (*v*) ED ->
        "bled" to "bled", // stem "bl" has no vowel: the ED rule does not fire
        "conflated" to "conflat", // cleanup AT -> ATE gives "conflate" (m(confl)=1, ATE survives
        // Step 4's m>1 gate); Step 5a then strips the e anyway (m(conflat)=2>1) -> "conflat".
        "troubled" to "troubl", // cleanup BL -> BLE gives "trouble"; Step 5a strips its e too
        // (m(troubl)=2>1) -> "troubl".
        "sized" to "size", // cleanup IZ -> IZE gives "size"; Step 5a leaves it alone here
        // (m(siz)=1 AND *o is true -- "siz" itself ends cvc) -- contrast with troubled/conflated.
        "tanned" to "tan", // cleanup: double consonant (not L/S/Z) -> single letter
        "fizzed" to "fizz", // cleanup: double consonant IS Z -> kept doubled
        "motoring" to "motor", // (*v*) ING ->
        "sing" to "sing", // stem "s" has no vowel: the ING rule does not fire
        "hopping" to "hop", // cleanup: double consonant (not L/S/Z) -> single letter
        "falling" to "fall", // cleanup: double consonant IS L -> kept doubled
        "hissing" to "hiss", // cleanup: double consonant IS S -> kept doubled
        "failing" to "fail", // cleanup: m=1 but *o is false (fail ends vowel-vowel-consonant) -> no +e
        "filing" to "file", // cleanup: m=1 and *o true (fil ends cvc) -> +e; Step 5a leaves it (same *o guard)
        "controlling" to "control", // Step 1b leaves "controll" (LL kept, *d and *L); Step 5b finishes the job
    )

    @Test fun `step 1b past-tense and gerund suffixes, with cleanup`() {
        step1bFixtures.forEach { (word, expected) -> assertEquals(word, expected, Stemmer.stem(word)) }
    }

    // ---- Step 1c: Y -> I (this file's one documented deviation from the 1980 paper) ----

    private val step1cFixtures = listOf(
        "happy" to "happi", // preceded by a genuine mid-word consonant ('p')
        "cry" to "cri", // preceded by a genuine mid-word consonant ('r')
        "by" to "by", // preceding consonant IS the first letter -> excluded, unchanged
        "say" to "say", // preceded by a vowel -> excluded, unchanged
        "deploy" to "deploy", // preceded by a vowel ('o') -> unchanged; this is the deviation's payoff:
        // the 1980 paper's literal (*v*) condition would produce "deploi" here (the stem "deplo"
        // contains a vowel elsewhere), silently corrupting a word this very codebase's own search
        // tests use as a query (SearchQueryTypeFacetTest). See Stemmer's class KDoc.
        "monkey" to "monkey", // preceded by a vowel ('e') -> unchanged
        "sky" to "ski", // Porter2's own worked example: preceded by 'k', a genuine mid-word consonant
    )

    @Test fun `step 1c y-to-i, narrowed to a genuine preceding consonant`() {
        step1cFixtures.forEach { (word, expected) -> assertEquals(word, expected, Stemmer.stem(word)) }
    }

    // ---- Step 2: (m>0) suffix -> replacement ----

    private val step2Fixtures = listOf(
        "relational" to "relat", // ATIONAL -> ATE gives "relate" (m(rel)=1>0); Step 5a -> "relat"
        "conditional" to "condit", // TIONAL -> TION gives "condition"; Step 4's ION-special case
        // (m(condit)=2>1, stem ends T) strips that too -> "condit"
        "rational" to "ration", // ATIONAL structurally matches but m(r)=0 so Step 2 does NOT fire
        // here -- it is instead Step 4's generic AL -> rule that catches it (m(ration)=2>1)
        "valenci" to "valenc", // ENCI -> ENCE gives "valence"; Step 4's ENCE -> then strips it too
        "hesitanci" to "hesit", // ANCI -> ANCE gives "hesitance"; Step 4's ANCE -> strips it too
        "digitizer" to "digit", // IZER -> IZE gives "digitize"; Step 4's IZE -> strips it too
        "conformabli" to "conform", // ABLI -> ABLE gives "conformable"; Step 4's ABLE -> strips it too
        "radicalli" to "radic", // ALLI -> AL gives "radical"; Step 4's AL -> strips it too
        "differentli" to "differ", // ENTLI -> ENT gives "different"; Step 4's ENT -> strips it too
        "vileli" to "vile", // ELI -> E gives "vile"; m(vil)=1, not >1, so Step 5a leaves it
        "analogousli" to "analog", // OUSLI -> OUS gives "analogous"; Step 4's OUS -> strips it too
        "vietnamization" to "vietnam", // IZATION -> IZE gives "vietnamize"; Step 4's IZE -> strips it too
        "predication" to "predic", // ATION -> ATE gives "predicate"; Step 4's ATE -> strips it too
        "operator" to "oper", // ATOR -> ATE gives "operate"; Step 4's ATE -> strips it too
        "feudalism" to "feudal", // ALISM -> AL gives "feudal"; m(feud)=1, not >1, so Step 4 leaves it
        "decisiveness" to "decis", // IVENESS -> IVE gives "decisive"; Step 4's IVE -> strips it too
        "hopefulness" to "hope", // FULNESS -> FUL gives "hopeful"; Step 3's FUL -> then strips it too
        "callousness" to "callous", // OUSNESS -> OUS gives "callous"; m(callous)=2 but no further
        // suffix in Steps 3/4/5 matches "callous", so it survives
        "formaliti" to "formal", // ALITI -> AL gives "formal"; m(form)=1, not >1, so Step 4 leaves it
        "sensitiviti" to "sensit", // IVITI -> IVE gives "sensitive"; Step 4's IVE -> strips it too
        "sensibiliti" to "sensibl", // BILITI -> BLE gives "sensible"; Step 5a strips its e too
        // (m(sensibl)=2>1) -> "sensibl"
    )

    @Test fun `step 2 double-suffix reduction`() {
        step2Fixtures.forEach { (word, expected) -> assertEquals(word, expected, Stemmer.stem(word)) }
    }

    // ---- Step 3: (m>0) suffix -> replacement ----

    private val step3Fixtures = listOf(
        "triplicate" to "triplic", // ICATE -> IC
        "formative" to "form", // ATIVE ->
        "formalize" to "formal", // ALIZE -> AL
        "electriciti" to "electr", // ICITI -> IC gives "electric"; Step 4's IC -> strips it too
        // (m(electr)=2>1) -> "electr"
        "electrical" to "electr", // ICAL -> IC gives "electric"; same Step 4 IC -> cascade -> "electr"
        "hopeful" to "hope", // FUL ->
        "goodness" to "good", // NESS ->
    )

    @Test fun `step 3 suffix reduction`() {
        step3Fixtures.forEach { (word, expected) -> assertEquals(word, expected, Stemmer.stem(word)) }
    }

    // ---- Step 4: (m>1) suffix -> (removed), ION requiring a trailing S or T ----

    private val step4Fixtures = listOf(
        "revival" to "reviv", // AL ->
        "allowance" to "allow", // ANCE ->
        "inference" to "infer", // ENCE ->
        "airliner" to "airlin", // ER ->
        "gyroscopic" to "gyroscop", // IC ->
        "adjustable" to "adjust", // ABLE ->
        "defensible" to "defens", // IBLE ->
        "irritant" to "irrit", // ANT ->
        "replacement" to "replac", // EMENT ->
        "adjustment" to "adjust", // MENT ->
        "dependent" to "depend", // ENT ->
        "adoption" to "adopt", // ION, m>1 and stem ends in T
        "homologou" to "homolog", // OU ->
        "communism" to "commun", // ISM ->
        "activate" to "activ", // ATE ->
        "angulariti" to "angular", // ITI ->
        "homologous" to "homolog", // OUS ->
        "effective" to "effect", // IVE ->
        "bowdlerize" to "bowdler", // IZE ->
    )

    @Test fun `step 4 suffix removal`() {
        step4Fixtures.forEach { (word, expected) -> assertEquals(word, expected, Stemmer.stem(word)) }
    }

    // ---- Step 5a: final E removal ----

    private val step5aFixtures = listOf(
        "probate" to "probat", // m>1
        "rate" to "rate", // m=1 and *o (rat ends cvc) -> unchanged
        "cease" to "ceas", // m=1 and not *o (ceas does not end cvc) -> removed
        "gradle" to "gradl", // m=1 and not *o (gradl ends V,C,C not cvc) -> removed
        "cache" to "cach", // m=1 and not *o (cach ends V,C,C not cvc) -> removed
    )

    @Test fun `step 5a final e removal`() {
        step5aFixtures.forEach { (word, expected) -> assertEquals(word, expected, Stemmer.stem(word)) }
    }

    // ---- Step 5b: double-L undoubling ----

    private val step5bFixtures = listOf(
        "controlling" to "control", // via step1b's "controll" + step5b's m>1 double-L reduction
        "roll" to "roll", // m=1, not >1: double L stays
    )

    @Test fun `step 5b double-l undoubling`() {
        step5bFixtures.forEach { (word, expected) -> assertEquals(word, expected, Stemmer.stem(word)) }
    }

    // ---- case-insensitivity ----

    @Test fun `stemming is case-insensitive and always returns lowercase`() {
        assertEquals("gradl", Stemmer.stem("GRADLE"))
        assertEquals("gradl", Stemmer.stem("Gradle"))
        assertEquals("run", Stemmer.stem("RUNNING"))
    }

    // ---- ASCII-only gate: everything else passes through unchanged ----

    @Test fun `isStemmable is true only for non-empty all-ascii-letter tokens`() {
        assertTrue(Stemmer.isStemmable("gradle"))
        assertTrue(Stemmer.isStemmable("GRADLE"))
        assertFalse(Stemmer.isStemmable(""))
        assertFalse(Stemmer.isStemmable("gradle2")) // digit
        assertFalse(Stemmer.isStemmable("gradle_build")) // underscore
        assertFalse(Stemmer.isStemmable("東京駅")) // CJK
        assertFalse(Stemmer.isStemmable("किताब")) // Devanagari
        assertFalse(Stemmer.isStemmable("كتاب")) // Arabic
        assertFalse(Stemmer.isStemmable("42"))
        assertFalse(Stemmer.isStemmable("v2")) // mixed alnum
    }

    @Test fun `maybeStem passes non-ascii-alphabetic tokens through unchanged`() {
        assertEquals("東京駅", Stemmer.maybeStem("東京駅"))
        assertEquals("किताब", Stemmer.maybeStem("किताब"))
        assertEquals("كتاب", Stemmer.maybeStem("كتاب"))
        assertEquals("42", Stemmer.maybeStem("42"))
        assertEquals("gradle2", Stemmer.maybeStem("gradle2"))
        assertEquals("gradle_build", Stemmer.maybeStem("gradle_build"))
        assertEquals("", Stemmer.maybeStem(""))
    }

    @Test fun `maybeStem stems a plain ascii word`() {
        assertEquals("run", Stemmer.maybeStem("running"))
        assertEquals("caress", Stemmer.maybeStem("caresses"))
    }

    // ---- stemJoined: the shape both the indexer and QueryCompiler actually call ----

    @Test fun `stemJoined stems each whitespace-delimited token independently`() {
        assertEquals("run test everi night", Stemmer.stemJoined("running tests every night"))
    }

    @Test fun `stemJoined leaves non-stemmable tokens untouched among stemmed ones`() {
        assertEquals("gradl 東京駅 build v2", Stemmer.stemJoined("gradle 東京駅 building v2"))
    }

    @Test fun `stemJoined collapses irregular whitespace to single spaces`() {
        assertEquals("run test", Stemmer.stemJoined("running   tests"))
    }

    @Test fun `stemJoined of blank text is empty`() {
        assertEquals("", Stemmer.stemJoined(""))
        assertEquals("", Stemmer.stemJoined("   "))
    }

    // ---- symmetry: the whole reason this exists ----

    @Test fun `running and runs share a stem`() {
        assertEquals(Stemmer.maybeStem("running"), Stemmer.maybeStem("runs"))
    }

    @Test fun `motoring and motor share a stem`() {
        assertEquals(Stemmer.maybeStem("motoring"), Stemmer.maybeStem("motor"))
    }
}
