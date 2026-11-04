package dev.fonebrew.domain.mirror

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingSink : AarsoEventSink {
    val lines = mutableListOf<String>()
    override suspend fun append(line: String) { lines += line }
}

class AarsoEventLogTest {

    @Test fun `a disabled signal is silently skipped, never appended`() = runTest {
        val sink = RecordingSink()
        val log = AarsoEventLog(sink) { AarsoCaptureSettings.OFF }
        log.record(AarsoEventKind.VERDICT, """{"grade":2}""", now = 1L)
        assertTrue(sink.lines.isEmpty())
    }

    @Test fun `an enabled signal is appended as one JSONL line`() = runTest {
        val sink = RecordingSink()
        val log = AarsoEventLog(sink) { AarsoCaptureSettings(setOf(AarsoEventKind.VERDICT)) }
        log.record(AarsoEventKind.VERDICT, """{"grade":2}""", sessionRef = "conv1", now = 100L)
        assertEquals(1, sink.lines.size)
        val obj = JSONObject(sink.lines.single())
        assertEquals(100L, obj.getLong("t"))
        assertEquals("verdict", obj.getString("kind"))
        assertEquals(2, obj.getJSONObject("payload").getInt("grade"))
        assertEquals("conv1", obj.getString("sessionRef"))
    }

    @Test fun `only the enabled kinds are captured, others of the same call sequence are skipped`() = runTest {
        val sink = RecordingSink()
        val log = AarsoEventLog(sink) { AarsoCaptureSettings(setOf(AarsoEventKind.BOOKMARK)) }
        log.record(AarsoEventKind.VERDICT, "{}", now = 1L)
        log.record(AarsoEventKind.BOOKMARK, "{}", now = 2L)
        log.record(AarsoEventKind.VERSION, "{}", now = 3L)
        assertEquals(1, sink.lines.size)
        assertEquals("bookmark", JSONObject(sink.lines.single()).getString("kind"))
    }

    @Test fun `settings are re-read on every call, so toggling mid-session takes effect immediately`() = runTest {
        val sink = RecordingSink()
        var enabled = false
        val log = AarsoEventLog(sink) { if (enabled) AarsoCaptureSettings(setOf(AarsoEventKind.REWIND)) else AarsoCaptureSettings.OFF }
        log.record(AarsoEventKind.REWIND, "{}", now = 1L)
        assertTrue(sink.lines.isEmpty())
        enabled = true
        log.record(AarsoEventKind.REWIND, "{}", now = 2L)
        assertEquals(1, sink.lines.size)
    }

    @Test fun `sessionRef is omitted from the line when absent, not written as null`() = runTest {
        val sink = RecordingSink()
        val log = AarsoEventLog(sink) { AarsoCaptureSettings(setOf(AarsoEventKind.MODEL_CHOICE)) }
        log.record(AarsoEventKind.MODEL_CHOICE, """{"modelId":"x"}""", sessionRef = null, now = 1L)
        val obj = JSONObject(sink.lines.single())
        assertFalse(obj.has("sessionRef"))
    }

    @Test fun `AarsoCaptureSettings OFF has nothing enabled`() {
        for (kind in AarsoEventKind.entries) {
            assertFalse(AarsoCaptureSettings.OFF.isEnabled(kind))
        }
    }

    @Test fun `every spec-named event kind has its exact wire string`() {
        assertEquals("verdict", AarsoEventKind.VERDICT.wire)
        assertEquals("bookmark", AarsoEventKind.BOOKMARK.wire)
        assertEquals("version", AarsoEventKind.VERSION.wire)
        assertEquals("rewind", AarsoEventKind.REWIND.wire)
        assertEquals("roundtable_outcome", AarsoEventKind.ROUNDTABLE_OUTCOME.wire)
        assertEquals("model_choice", AarsoEventKind.MODEL_CHOICE.wire)
        assertEquals("compaction_run", AarsoEventKind.COMPACTION_RUN.wire)
        assertEquals("composer_edit_delta", AarsoEventKind.COMPOSER_EDIT_DELTA.wire)
    }
}
