package dev.fonebrew.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceKeeperTest {
    private class Fake : ForegroundServiceController {
        val calls = ArrayList<String>()
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
    }

    @Test fun `starts once when the first session goes live and stops when the last closes`() {
        val f = Fake(); val k = ServiceKeeper(f)
        k.onLiveCount(0); k.onLiveCount(1); k.onLiveCount(2); k.onLiveCount(1); k.onLiveCount(0); k.onLiveCount(0)
        assertEquals(listOf("start", "stop"), f.calls)
        assertFalse(k.running)
    }

    @Test fun `a platform-killed service restarts on the next live count`() {
        val f = Fake(); val k = ServiceKeeper(f)
        k.onLiveCount(1)
        k.onServiceDestroyed()
        k.onLiveCount(1)
        assertEquals(listOf("start", "start"), f.calls)
        assertTrue(k.running)
    }

    @Test fun `notification text is a count only`() {
        assertEquals("1 terminal session running", ServiceKeeper.notificationText(1))
        assertEquals("3 terminal sessions running", ServiceKeeper.notificationText(3))
    }
}
