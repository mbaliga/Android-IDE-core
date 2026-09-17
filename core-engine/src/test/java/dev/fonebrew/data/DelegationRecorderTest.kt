package dev.fonebrew.data

import dev.fonebrew.data.dao.DelegationEventDao
import dev.fonebrew.data.entity.DelegationEventEntity
import dev.fonebrew.domain.mirror.AarsoCaptureSettings
import dev.fonebrew.domain.mirror.AarsoEventKind
import dev.fonebrew.domain.mirror.AarsoEventLog
import dev.fonebrew.domain.mirror.AarsoEventSink
import dev.fonebrew.domain.thread.DelegationKind
import dev.fonebrew.domain.thread.DelegationOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same in-memory fake shape as [DelegationStoreTest]'s FakeDelegationEventDao — a separate,
 *  distinctly-named class rather than a reused one: two file-private top-level classes can't
 *  share a name within the same package even across files (a plain JVM name clash, not a
 *  visibility one), so each test file that needs this fake declares its own. */
private class RecorderFakeDelegationEventDao : DelegationEventDao {
    private val rows = MutableStateFlow<List<DelegationEventEntity>>(emptyList())
    override suspend fun insert(event: DelegationEventEntity) { rows.value = rows.value + event }
    override suspend fun update(event: DelegationEventEntity) { rows.value = rows.value.map { if (it.id == event.id) event else it } }
    override suspend fun getById(id: String): DelegationEventEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun forRoot(rootId: String): List<DelegationEventEntity> = rows.value.filter { it.rootId == rootId }.sortedBy { it.at }
    override fun observeAll(): Flow<List<DelegationEventEntity>> = rows
}

private class RecordingSink : AarsoEventSink {
    val lines = mutableListOf<String>()
    override suspend fun append(line: String) { lines += line }
}

class DelegationRecorderTest {

    private fun newRecorder(sink: RecordingSink = RecordingSink(), enabled: Boolean = true) = DelegationRecorder(
        store = DelegationStore(RecorderFakeDelegationEventDao()),
        log = AarsoEventLog(sink) { if (enabled) AarsoCaptureSettings(setOf(AarsoEventKind.DELEGATION)) else AarsoCaptureSettings.OFF },
    ) to sink

    @Test fun `record writes a PENDING row and an inert log line with matching delegationId`() = runTest {
        val (recorder, sink) = newRecorder()
        val event = recorder.record(
            kind = DelegationKind.MODEL_PICK_BRANCH,
            rootId = "root-1",
            anchorMsgId = "msg-1",
            chosenRef = "msg-2",
            alternatives = listOf("msg-3"),
            now = 10L,
        )
        assertEquals(DelegationOutcome.PENDING, event.outcome)
        assertEquals(1, sink.lines.size)
        val payload = JSONObject(sink.lines.single()).getJSONObject("payload")
        assertEquals("delegation", JSONObject(sink.lines.single()).getString("kind"))
        assertEquals(event.id, payload.getString("delegationId"))
        assertEquals("MODEL_PICK_BRANCH", payload.getString("delegationKind"))
        assertEquals("PENDING", payload.getString("outcome"))
        assertFalse(payload.has("outcomeAt"))
    }

    @Test fun `resolveOutcome writes the resolved outcome and stamps outcomeAt in the log line too`() = runTest {
        val (recorder, sink) = newRecorder()
        val event = recorder.record(kind = DelegationKind.COUNCIL_AUTOMERGE, rootId = "root-1", now = 1L)
        recorder.resolveOutcome(event, DelegationOutcome.KEPT, now = 50L)

        assertEquals(2, sink.lines.size)
        val resolvedPayload = JSONObject(sink.lines[1]).getJSONObject("payload")
        assertEquals("KEPT", resolvedPayload.getString("outcome"))
        assertTrue(resolvedPayload.has("outcomeAt"))
    }

    @Test fun `when the DELEGATION signal is off, the store still writes but the log stays silent`() = runTest {
        val (recorder, sink) = newRecorder(enabled = false)
        val event = recorder.record(kind = DelegationKind.GATEWAY_AUTO, now = 1L)
        assertTrue(sink.lines.isEmpty())
        assertEquals(DelegationOutcome.PENDING, event.outcome)
    }
}
