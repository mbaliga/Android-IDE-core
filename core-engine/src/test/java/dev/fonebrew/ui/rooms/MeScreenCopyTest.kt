package dev.fonebrew.ui.rooms

import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-11-11: MeScreen's footnote said cost is not shown while the cards below render it
 *  (showCost = true). Pins that the two cannot contradict again. */
class MeScreenCopyTest {
    @Test fun `copy does not deny cost while the cost cards render`() {
        val src = java.io.File("src/main/java/dev/fonebrew/ui/rooms/MeScreen.kt").readText()
        assertTrue(src.contains("showCost = true"))
        assertTrue(!src.contains("Cost isn't shown here"))
    }
}
