package dev.fonebrew.watch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallPiecesTest {

    // ---- Destination: the exported Activity's extra is untrusted ----

    @Test
    fun knownDestinationsResolve_everythingElseIsHome() {
        assertEquals(Destination.ASK, Destination.fromExtra("ask"))
        assertEquals(Destination.TASKS, Destination.fromExtra("tasks"))
        assertEquals(Destination.LOOPS, Destination.fromExtra("loops"))
        assertEquals(Destination.HOME, Destination.fromExtra("home"))
        for (junk in listOf(null, "", "ASK", " ask", "../ask", "settings", "ask;drop")) {
            assertEquals("'$junk'", Destination.HOME, Destination.fromExtra(junk))
        }
    }

    @Test
    fun theTileAndComplicationExtras_arePartOfTheClosedSet() {
        // MainActivity.DEST_* are what the Tile and complication put in the extra; they must resolve to real screens.
        assertEquals(Destination.ASK, Destination.fromExtra(dev.fonebrew.watch.MainActivity.DEST_ASK))
        assertEquals(Destination.TASKS, Destination.fromExtra(dev.fonebrew.watch.MainActivity.DEST_TASKS))
    }

    // ---- reply paging ----

    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun shortText_isOnePage() {
        assertEquals(listOf("hello there"), ReplyPaging.pages("hello there"))
        assertTrue(ReplyPaging.pages("").isEmpty())
        assertTrue(ReplyPaging.pages("  \n\n  ").isEmpty())
    }

    @Test
    fun paragraphsBecomeSeparatePages() {
        assertEquals(listOf("one", "two", "three"), ReplyPaging.pages("one\n\ntwo\n  \nthree"))
    }

    @Test
    fun longText_isCutAtWordBoundaries_noPageExceedsTheLimit_andNoWordIsLostOrReordered() {
        val text = (1..200).joinToString(" ") { "word$it" }
        val pages = ReplyPaging.pages(text, maxChars = 60)
        assertTrue(pages.size > 5)
        assertTrue(pages.all { it.length <= 60 })
        assertEquals(words(text), pages.flatMap { words(it) })
    }

    @Test
    fun aWordLongerThanTheLimit_isHardCut_withoutLosingCharacters() {
        val long = "x".repeat(50)
        val pages = ReplyPaging.pages(long, maxChars = 20)
        assertTrue(pages.all { it.length <= 20 })
        assertEquals(long, pages.joinToString(""))
    }

    @Test
    fun paging_neverSplitsASurrogatePair() {
        val emoji = "😀"
        val text = emoji.repeat(40) // no spaces, so every cut is a hard cut
        val pages = ReplyPaging.pages(text, maxChars = 15)
        for (page in pages) {
            assertFalse("page ends in a lone high surrogate", page.last().isHighSurrogate())
            assertFalse("page starts with a lone low surrogate", page.first().isLowSurrogate())
        }
        assertEquals(text, pages.joinToString(""))
    }

    // ---- link failure classification ----

    @Test
    fun statusCodes_mapToTheirFailureClass() {
        assertEquals(LinkFailure.NO_NODE, LinkFailureClassifier.fromStatusCode(4000))
        for (code in listOf(1, 2, 3, 9, 16, 17)) assertEquals("code $code", LinkFailure.API_UNAVAILABLE, LinkFailureClassifier.fromStatusCode(code))
        for (code in listOf(null, 0, 8, 13, 4005, 4006, -1)) assertEquals("code $code", LinkFailure.OTHER, LinkFailureClassifier.fromStatusCode(code))
    }
}
