package dev.aarso.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResumeTest {

    @Test
    fun planWithoutPartAsksForTheWholeFile() {
        val plan = DownloadResume.plan(0)
        assertNull(plan.rangeHeader)
        assertFalse(plan.resuming)
    }

    @Test
    fun planWithPartAsksForTheRemainder() {
        val plan = DownloadResume.plan(1_234)
        assertEquals("bytes=1234-", plan.rangeHeader)
        assertTrue(plan.resuming)
    }

    @Test
    fun honoredRangeAppendsAtPartSize() {
        val o = DownloadResume.interpret(206, "bytes 1234-9999/10000", 1_234)
        assertEquals(1_234, o.startAt)
        assertFalse(o.alreadyComplete)
        assertNull(o.error)
    }

    @Test
    fun mismatchedRangeStartRestartsClean() {
        val o = DownloadResume.interpret(206, "bytes 500-9999/10000", 1_234)
        assertEquals(0, o.startAt)
        assertNull(o.error)
    }

    @Test
    fun missingContentRangeOn206RestartsClean() {
        assertEquals(0, DownloadResume.interpret(206, null, 1_234).startAt)
    }

    @Test
    fun fullBodyResponseRestartsClean() {
        val o = DownloadResume.interpret(200, null, 1_234)
        assertEquals(0, o.startAt)
        assertNull(o.error)
    }

    @Test
    fun unsatisfiableRangeWithMatchingTotalIsComplete() {
        val o = DownloadResume.interpret(416, "bytes */1234", 1_234)
        assertTrue(o.alreadyComplete)
        assertNull(o.error)
    }

    @Test
    fun unsatisfiableRangeOtherwiseRestartsClean() {
        val o = DownloadResume.interpret(416, "bytes */99999", 1_234)
        assertFalse(o.alreadyComplete)
        assertEquals(0, o.startAt)
        assertNull(o.error)
    }

    @Test
    fun httpErrorsSurfaceAndKeepThePart() {
        val o = DownloadResume.interpret(503, null, 1_234)
        assertEquals("HTTP 503", o.error)
    }
}
