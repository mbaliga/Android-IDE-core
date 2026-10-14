package dev.fonebrew.watchbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The path table is a wire surface: both consumers address these exact strings. */
class WatchPathsTest {

    @Test
    fun everyPath_isUnderThePrefix_andDistinct() {
        assertEquals(WatchPaths.ALL.size, WatchPaths.ALL.toSet().size)
        for (p in WatchPaths.ALL) {
            assertTrue("$p must start with ${WatchPaths.PREFIX}", p.startsWith(WatchPaths.PREFIX))
            assertTrue("$p must be recognised by isWatchBrewPath", WatchPaths.isWatchBrewPath(p))
        }
    }

    @Test
    fun paths_areSimpleAbsoluteSlugs_noQueryNoFragmentNoSpaces() {
        // A Data Layer path is a URI path: keep to [a-z/] so no escaping question ever arises.
        val slug = Regex("/[a-z]+(/[a-z]+)*")
        for (p in WatchPaths.ALL) assertTrue("$p is not a plain lowercase path", slug.matches(p))
    }

    @Test
    fun paths_carryNoContractVersion_theVersionLivesInTheEnvelope() {
        // See WatchPaths KDoc: a versioned path would silently hide a newer watch from an older phone.
        for (p in WatchPaths.ALL) assertFalse("$p must not embed a version segment", Regex("/v\\d+(/|$)").containsMatchIn(p))
    }

    @Test
    fun pathLiterals_arePinned() {
        // Literal pins so a rename is a deliberate, reviewed wire change (it bumps CONTRACT_VERSION).
        assertEquals("/watchbrew/", WatchPaths.PREFIX)
        assertEquals("/watchbrew/sync", WatchPaths.SYNC)
        assertEquals("/watchbrew/ask", WatchPaths.ASK)
        assertEquals("/watchbrew/tasks/toggle", WatchPaths.TASK_TOGGLE)
        assertEquals("/watchbrew/hello", WatchPaths.HELLO)
        assertEquals("/watchbrew/tasks", WatchPaths.TASKS)
        assertEquals("/watchbrew/tasks/toggle/result", WatchPaths.TASK_TOGGLE_RESULT)
        assertEquals("/watchbrew/loops", WatchPaths.LOOPS)
        assertEquals("/watchbrew/ask/reply", WatchPaths.ASK_REPLY)
    }

    @Test
    fun isWatchBrewPath_rejectsForeignAndLookalikePaths() {
        assertFalse(WatchPaths.isWatchBrewPath("/other/ask"))
        assertFalse(WatchPaths.isWatchBrewPath("/watchbrew")) // no trailing slash: not under the prefix
        assertFalse(WatchPaths.isWatchBrewPath("watchbrew/ask"))
        assertFalse(WatchPaths.isWatchBrewPath(""))
        assertFalse(WatchPaths.isWatchBrewPath("/WatchBrew/ask"))
    }
}
