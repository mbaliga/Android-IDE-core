package dev.aarso.domain.curation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionReceiptCodecTest {

    private fun compacted(msgId: String, fate: MessageFate, text: String?, fidelity: Fidelity = Fidelity.F1) = CompactedMessage(
        msgId = msgId,
        fate = fate,
        resolution = ResolvedFidelity(
            fidelity = fidelity,
            mustInclude = fidelity == Fidelity.F0,
            isFailureTombstone = fate == MessageFate.TOMBSTONE,
            reason = FidelityReason.DEFAULT_ORDINARY,
        ),
        text = text,
    )

    @Test fun `a receipt round-trips field-for-field through encode then decode`() {
        val receipt = Receipt(
            runAt = 1_700_000_000_000L,
            entries = listOf(
                compacted("m1", MessageFate.KEPT_VERBATIM, "exact text", Fidelity.F3),
                compacted("m2", MessageFate.GIST, "a gist", Fidelity.F1),
                compacted("m3", MessageFate.DROPPED, null, Fidelity.F0),
                compacted("m4", MessageFate.TOMBSTONE, "failed: wrong approach", Fidelity.F1),
            ),
            directivesHonored = 2,
            directivesTotal = 3,
        )
        val decoded = CompactionReceiptCodec.decodeReceipt(CompactionReceiptCodec.encodeReceipt(receipt))
        assertEquals(receipt, decoded)
    }

    @Test fun `a DROPPED entry's null text encodes without a text key, not the literal string null`() {
        val receipt = Receipt(runAt = 0L, entries = listOf(compacted("m1", MessageFate.DROPPED, null)), directivesHonored = 0, directivesTotal = 0)
        val json = CompactionReceiptCodec.encodeReceipt(receipt)
        val entry = json.getJSONArray("entries").getJSONObject(0)
        assertFalse(entry.has("text"))
    }

    @Test fun `decoding a missing text key yields null, not the string 'null'`() {
        val receipt = Receipt(runAt = 0L, entries = listOf(compacted("m1", MessageFate.DROPPED, null)), directivesHonored = 0, directivesTotal = 0)
        val decoded = CompactionReceiptCodec.decodeReceipt(CompactionReceiptCodec.encodeReceipt(receipt))
        assertNull(decoded.entries.single().text)
    }

    @Test fun `runAt encodes as an ISO instant string, not a raw epoch number`() {
        val receipt = Receipt(runAt = 1_700_000_000_000L, entries = emptyList(), directivesHonored = 0, directivesTotal = 0)
        val json = CompactionReceiptCodec.encodeReceipt(receipt)
        assertTrue(json.getString("runAt").contains("T")) // ISO-8601 date-time marker
    }

    @Test fun `an empty entries list round-trips to an empty list, not null`() {
        val receipt = Receipt(runAt = 5L, entries = emptyList(), directivesHonored = 0, directivesTotal = 0)
        val decoded = CompactionReceiptCodec.decodeReceipt(CompactionReceiptCodec.encodeReceipt(receipt))
        assertTrue(decoded.entries.isEmpty())
    }

    @Test fun `decodeReceipt reads back a hand-built JSON object, not just its own encoder's output`() {
        val json = JSONObject()
            .put("runAt", "2026-08-13T00:00:00Z")
            .put(
                "entries",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("msgId", "m1")
                        .put("fate", "FAITHFUL")
                        .put("fidelity", "F2")
                        .put("mustInclude", false)
                        .put("isFailureTombstone", false)
                        .put("reason", "BOOKMARKED")
                        .put("text", "hand-built"),
                ),
            )
            .put("directivesHonored", 1)
            .put("directivesTotal", 1)
        val receipt = CompactionReceiptCodec.decodeReceipt(json)
        assertEquals("m1", receipt.entries.single().msgId)
        assertEquals(MessageFate.FAITHFUL, receipt.entries.single().fate)
        assertEquals(Fidelity.F2, receipt.entries.single().resolution.fidelity)
        assertEquals(FidelityReason.BOOKMARKED, receipt.entries.single().resolution.reason)
        assertEquals("hand-built", receipt.entries.single().text)
    }
}
