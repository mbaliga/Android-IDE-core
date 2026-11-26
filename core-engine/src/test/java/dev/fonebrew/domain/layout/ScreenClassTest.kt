package dev.fonebrew.domain.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenClassTest {
    private fun cls(w: Float, h: Float, ext: Boolean = false, kb: Boolean = false, ptr: Boolean = false) =
        ScreenClassResolver.resolve(ScreenInputs(w, h, ext, kb, ptr))

    @Test fun `typical phone portrait and landscape are PHONE`() {
        assertEquals(ScreenClass.PHONE, cls(412f, 915f))
        assertEquals(ScreenClass.PHONE, cls(915f, 412f))
    }

    @Test fun `10-inch tablet is TABLET both ways without a docked context`() {
        assertEquals(ScreenClass.TABLET, cls(800f, 1280f))
        assertEquals(ScreenClass.TABLET, cls(1280f, 800f))
    }

    @Test fun `keyboard alone or pointer alone is not a desktop context`() {
        assertEquals(ScreenClass.TABLET, cls(1280f, 800f, kb = true))
        assertEquals(ScreenClass.TABLET, cls(1280f, 800f, ptr = true))
        assertEquals(ScreenClass.DESKTOP, cls(1280f, 800f, kb = true, ptr = true))
    }

    /** 1920x1080 external monitor at densities 1.0 .. 2.0 -> dp table. */
    @Test fun `1080p monitor at densities 1 to 2`() {
        val table = listOf(
            // density, expected dp size, expected class
            Triple(1.0f, 1920f to 1080f, ScreenClass.DESKTOP),
            Triple(1.25f, 1536f to 864f, ScreenClass.DESKTOP),
            Triple(1.5f, 1280f to 720f, ScreenClass.DESKTOP),
            Triple(1.75f, 1097.1f to 617.1f, ScreenClass.DESKTOP),
            Triple(2.0f, 960f to 540f, ScreenClass.TABLET), // below DESKTOP_MIN_WIDTH, above DOCKED_MIN_WIDTH
        )
        for ((d, dp, expected) in table) {
            val got = ScreenClassResolver.resolveFromPixels(1920, 1080, (d * 160).toInt(), true, false, false)
            assertEquals("density $d -> ${dp.first}x${dp.second}dp", expected, got)
        }
    }

    /** The owner's G9: 5120x1440. Densities a phone might plausibly report for it. */
    @Test fun `G9 dp table at plausible densities`() {
        data class Row(val density: Float, val wDp: Float, val hDp: Float, val expected: ScreenClass)
        val rows = listOf(
            Row(0.7f, 7314f, 2057f, ScreenClass.ULTRAWIDE), // ~109 ppi native, if the OS honoured it
            Row(1.0f, 5120f, 1440f, ScreenClass.ULTRAWIDE),
            Row(1.5f, 3413f, 960f, ScreenClass.ULTRAWIDE),
            Row(2.0f, 2560f, 720f, ScreenClass.ULTRAWIDE),
            Row(2.5f, 2048f, 576f, ScreenClass.ULTRAWIDE), // height < 600 yet NOT a phone: external + wide
            Row(3.0f, 1707f, 480f, ScreenClass.ULTRAWIDE), // just over the ultrawide width floor
            Row(3.5f, 1463f, 411f, ScreenClass.DESKTOP), // under the ultrawide width floor
            Row(4.0f, 1280f, 360f, ScreenClass.DESKTOP),
            Row(5.0f, 1024f, 288f, ScreenClass.DESKTOP),
        )
        for (r in rows) {
            val got = ScreenClassResolver.resolveFromPixels(5120, 1440, (r.density * 160).toInt(), true, false, false)
            assertEquals("G9 @${r.density} (~${r.wDp}x${r.hDp}dp)", r.expected, got)
            val dp = ScreenInputs(5120 / r.density, 1440 / r.density, true, false, false)
            assertEquals(r.wDp, dp.widthDp, 1.5f)
            assertEquals(r.hDp, dp.heightDp, 1.5f)
        }
    }

    @Test fun `G9 aspect ratio alone does not make a handset window ULTRAWIDE`() {
        // 20:9 phone landscape is 2.2; even a stretched 2.5 handset window with no dock stays PHONE.
        assertEquals(ScreenClass.PHONE, cls(1000f, 400f))
        // wide, external, but too narrow for the ultrawide floor -> DESKTOP not ULTRAWIDE
        assertEquals(ScreenClass.DESKTOP, cls(1500f, 400f, ext = true))
        // wide enough but a normal 16:9 aspect -> DESKTOP
        assertEquals(ScreenClass.DESKTOP, cls(2560f, 1440f, ext = true))
    }

    @Test fun `same pixels different density resolve differently - pixels alone never decide`() {
        val low = ScreenClassResolver.resolveFromPixels(2560, 1440, 160, true, false, false)
        val high = ScreenClassResolver.resolveFromPixels(2560, 1440, 560, true, false, false)
        assertEquals(ScreenClass.DESKTOP, low) // 2560x1440 dp, 16:9
        assertEquals(ScreenClass.PHONE, high) // 731x411 dp, external but narrower than DOCKED_MIN_WIDTH
    }

    @Test fun `small external window is still PHONE`() {
        assertEquals(ScreenClass.PHONE, cls(700f, 400f, ext = true))
    }

    @Test fun `thresholds are ordered sanely`() {
        val t = ScreenClassThresholds
        assertTrue(t.TABLET_MIN_SMALLEST_DP < t.DOCKED_MIN_WIDTH_DP)
        assertTrue(t.DOCKED_MIN_WIDTH_DP < t.DESKTOP_MIN_WIDTH_DP)
        assertTrue(t.DESKTOP_MIN_WIDTH_DP < t.ULTRAWIDE_MIN_WIDTH_DP)
    }

    @Test fun `zero height does not divide by zero`() {
        assertEquals(ScreenClass.PHONE, cls(500f, 0f))
    }
}
