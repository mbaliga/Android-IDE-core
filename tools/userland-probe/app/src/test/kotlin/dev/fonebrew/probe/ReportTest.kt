package dev.fonebrew.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    @Test fun formatCarriesGlyphAndWordNeverColour() {
        val t = ReportFormatter.format(listOf("Device" to "x"), listOf(
            CheckResult("ii", "a", Status.PASS, "ok"), CheckResult("iii", "b", Status.FAIL, "errno=13"), CheckResult("v", "c", Status.UNKNOWN, "?")))
        assertTrue(t.contains("[+ PASS] ii a"))
        assertTrue(t.contains("[x FAIL] iii b"))
        assertTrue(t.contains("[? UNKNOWN] v c"))
        assertTrue(t.contains("    errno=13"))
        assertTrue(t.contains("Summary: 1 PASS, 1 FAIL, 1 UNKNOWN of 3 checks"))
        assertTrue(t.contains("Device: x"))
    }
    @Test fun reportStatesItIsLocalAndUnverified() {
        val t = ReportFormatter.format(emptyList(), emptyList())
        assertTrue(t.contains("nothing in this report was uploaded"))
        assertTrue(t.contains("none is verified by the author"))
    }
    @Test fun nativeVerdictParsesKnownHeads() {
        assertEquals(Status.PASS to "traceme=ok", NativeVerdict.parse("PASS traceme=ok"))
        assertEquals(Status.FAIL to "errno=1 (Operation not permitted)", NativeVerdict.parse("FAIL errno=1 (Operation not permitted)"))
    }
    @Test fun nativeVerdictUnknownKeepsRawText() {
        assertEquals(Status.UNKNOWN to "garbage here", NativeVerdict.parse("garbage here"))
        assertEquals(Status.UNKNOWN, NativeVerdict.parse(null).first)
        assertEquals(Status.UNKNOWN, NativeVerdict.parse("  ").first)
    }
    @Test fun execPassNeedsExitZeroAndMarker() {
        assertEquals(Status.PASS, ExecClassifier.classify(ExecResult(0, "probe_exec ok\nuname Linux", null, false)).first)
        assertEquals(Status.FAIL, ExecClassifier.classify(ExecResult(0, "something else", null, false)).first)
        assertEquals(Status.FAIL, ExecClassifier.classify(ExecResult(126, "permission denied", null, false)).first)
    }
    @Test fun execExceptionIsKeptRawAsFail() {
        val (s, d) = ExecClassifier.classify(ExecResult(null, "", "java.io.IOException: error=13, Permission denied", false))
        assertEquals(Status.FAIL, s); assertTrue(d.contains("error=13, Permission denied"))
    }
    @Test fun execTimeoutIsUnknownNotFail() {
        assertEquals(Status.UNKNOWN, ExecClassifier.classify(ExecResult(null, "partial", null, true)).first)
    }
    @Test fun cpuFeaturesTakesFirstLine() {
        assertEquals("fp asimd evtstrm", CpuInfo.features("processor : 0\nFeatures\t: fp asimd evtstrm\nFeatures : other"))
        assertNull(CpuInfo.features("processor : 0"))
        assertNull(CpuInfo.features("Features :"))
    }
    @Test fun selinuxContextStripsNul() {
        assertEquals("u:r:untrusted_app:s0:c1,c2", SelinuxContext.clean("u:r:untrusted_app:s0:c1,c2\u0000"))
    }
    @Test fun storageThresholdBoundary() {
        assertEquals(Status.FAIL, StorageVerdict.verdict(StorageVerdict.THRESHOLD_BYTES - 1).first)
        assertEquals(Status.PASS, StorageVerdict.verdict(StorageVerdict.THRESHOLD_BYTES).first)
        assertTrue(StorageVerdict.verdict(0).second.contains("heuristic"))
    }
    @Test fun bridgeProtocolAnswersOnlyValidPing() {
        assertEquals("PONG abc-1_", BridgeProtocol.reply("PING abc-1_\n"))
        assertEquals("ERR", BridgeProtocol.reply("PING a b"))
        assertEquals("ERR", BridgeProtocol.reply("PING $(id)"))
        assertEquals("ERR", BridgeProtocol.reply("HELLO x"))
        assertEquals("ERR", BridgeProtocol.reply("PING " + "a".repeat(65)))
    }
}

class ForkSurveyTest {
    private fun s(spawned: Int = 40, alive: Int = 40, dead: List<Int> = emptyList(), fg: Boolean = true, fail: String? = null) =
        ForkSurvey(40, spawned, fail, alive, dead, 10, fg, "FOREGROUND_SERVICE")

    @Test fun allSurvivingIsPass() = assertEquals(Status.PASS, s().verdict().first)
    @Test fun killsAreFailAndCounted() {
        val v = s(alive = 30, dead = List(10) { 137 })
        assertEquals(Status.FAIL, v.verdict().first)
        assertEquals(10, s(alive = 30, dead = List(10) { 137 }).killed)
        assertTrue(v.verdict().second.contains("exit 137: 10"))
    }
    @Test fun spawnFailureIsFailWithRawText() {
        val v = s(spawned = 31, alive = 31, fail = "child 32: java.io.IOException: error=11, Resource temporarily unavailable").verdict()
        assertEquals(Status.FAIL, v.first); assertTrue(v.second.contains("error=11"))
    }
    @Test fun nothingSpawnedIsFail() = assertEquals(Status.FAIL, s(spawned = 0, alive = 0, fail = "x").verdict().first)
    @Test fun noForegroundServiceMeansUnknownNeverPass() {
        val v = s(fg = false).verdict()
        assertEquals(Status.UNKNOWN, v.first); assertFalse(v.second.isEmpty())
    }
}
