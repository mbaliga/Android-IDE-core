package dev.fonebrew.domain.thread

import dev.fonebrew.domain.contracts.IdGenerator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Round-trip tests for every [ThreadCodec] encode/decode pair, mirroring
 * `dev.fonebrew.domain.contracts.EnvelopeCodecTest` — real encode-decode-re-encode cycles, not just
 * a compile-time shape check. The fixture-parsing tests embed the REAL `fixtures/thread/valid/`
 * fixtures verbatim, same rationale `IntegrationsCodecTest`'s own header comment states: this
 * module's JVM test working directory isn't established as reading repo-root fixture files
 * anywhere else in this codebase, so embedding keeps this test self-contained.
 */
class ThreadCodecTest {

    // ---- ThreadMarker ------------------------------------------------------------------------

    private fun marker(kind: ThreadMarkerKind = ThreadMarkerKind.CHAPTER) = ThreadMarker(
        id = "mk_" + IdGenerator.generate(),
        rootId = "root-1",
        anchorMsgId = if (kind == ThreadMarkerKind.CHAPTER) "msg-42" else null,
        kind = kind,
        label = if (kind == ThreadMarkerKind.CHAPTER) "Auth flow rewrite" else null,
        note = "some note",
        at = Instant.parse("2026-08-10T09:14:31Z").toEpochMilli(),
        source = ThreadMarkerSource.USER,
    )

    @Test fun `ThreadMarker round-trips through encode and decode`() {
        val original = marker()
        val decoded = ThreadCodec.decodeThreadMarker(ThreadCodec.encodeThreadMarker(original))
        assertEquals(original, decoded)
    }

    @Test fun `ThreadMarker round-trip preserves an unknown field end to end`() {
        val original = marker()
        val json = ThreadCodec.encodeThreadMarker(original)
        json.put("futureField", "from a newer minor version")
        val decoded = ThreadCodec.decodeThreadMarker(json)
        assertEquals("from a newer minor version", decoded.unknownFields["futureField"])
        val reEncoded = ThreadCodec.encodeThreadMarker(decoded)
        assertEquals("from a newer minor version", reEncoded.getString("futureField"))
    }

    @Test fun `ThreadMarker with null anchor and label round-trips for SESSION_START`() {
        val original = marker(ThreadMarkerKind.SESSION_START)
        val decoded = ThreadCodec.decodeThreadMarker(ThreadCodec.encodeThreadMarker(original))
        assertEquals(original, decoded)
        assertEquals(null, decoded.anchorMsgId)
        assertEquals(null, decoded.label)
    }

    @Test fun `ThreadMarker rejects an unsupported major schemaVersion before construction`() {
        val json = ThreadCodec.encodeThreadMarker(marker())
        json.put("schemaVersion", "2.0.0")
        try {
            ThreadCodec.decodeThreadMarker(json)
            org.junit.Assert.fail("expected IllegalArgumentException for an unsupported major schemaVersion")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("major version 1"))
        }
    }

    @Test fun `ThreadMarker rejects a CHAPTER with a blank label`() {
        try {
            ThreadMarker(
                id = "mk_1", rootId = "root-1", anchorMsgId = "msg-1",
                kind = ThreadMarkerKind.CHAPTER, label = null, at = 0L, source = ThreadMarkerSource.USER,
            )
            org.junit.Assert.fail("expected IllegalArgumentException for a CHAPTER marker without a label")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("CHAPTER requires"))
        }
    }

    // ---- ThreadEvent ---------------------------------------------------------------------------

    private fun forkEvent() = ThreadEvent(
        eventId = "ev_" + IdGenerator.generate(),
        kind = ThreadEventKind.FORK_CREATED,
        occurredAtUtc = Instant.parse("2026-08-10T09:20:00Z"),
        rootId = "root-2",
        anchorMsgId = "msg-42",
        srcRootId = "root-1",
        srcNodeId = "msg-42",
        newRootId = "root-2",
    )

    private fun delegationEvent(outcome: DelegationOutcome = DelegationOutcome.KEPT) = ThreadEvent(
        eventId = "ev_" + IdGenerator.generate(),
        kind = ThreadEventKind.DELEGATION,
        occurredAtUtc = Instant.parse("2026-08-10T09:25:00Z"),
        rootId = "root-1",
        delegationId = "dg_1",
        delegationKind = DelegationKind.MODEL_PICK_BRANCH,
        chosenRef = "msg-51",
        alternatives = listOf("msg-52", "msg-53"),
        outcome = outcome,
        outcomeAt = if (outcome == DelegationOutcome.PENDING) null else Instant.parse("2026-08-10T09:40:00Z"),
    )

    @Test fun `ThreadEvent FORK_CREATED round-trips`() {
        val original = forkEvent()
        val decoded = ThreadCodec.decodeThreadEvent(ThreadCodec.encodeThreadEvent(original))
        assertEquals(original, decoded)
    }

    @Test fun `ThreadEvent DELEGATION round-trips including alternatives and outcome`() {
        val original = delegationEvent()
        val decoded = ThreadCodec.decodeThreadEvent(ThreadCodec.encodeThreadEvent(original))
        assertEquals(original, decoded)
        assertEquals(listOf("msg-52", "msg-53"), decoded.alternatives)
    }

    @Test fun `ThreadEvent DELEGATION with PENDING outcome round-trips with a null outcomeAt`() {
        val original = delegationEvent(DelegationOutcome.PENDING)
        val decoded = ThreadCodec.decodeThreadEvent(ThreadCodec.encodeThreadEvent(original))
        assertEquals(original, decoded)
        assertEquals(null, decoded.outcomeAt)
    }

    @Test fun `ThreadEvent round-trip preserves an unknown field end to end`() {
        val original = forkEvent()
        val json = ThreadCodec.encodeThreadEvent(original)
        json.put("futureField", 99)
        val decoded = ThreadCodec.decodeThreadEvent(json)
        assertEquals(99, decoded.unknownFields["futureField"])
        val reEncoded = ThreadCodec.encodeThreadEvent(decoded)
        assertEquals(99, reEncoded.getInt("futureField"))
    }

    @Test fun `ThreadEvent construction rejects FORK_CREATED missing lineage fields`() {
        try {
            ThreadEvent(
                eventId = "ev_1", kind = ThreadEventKind.FORK_CREATED,
                occurredAtUtc = Instant.parse("2026-08-10T09:20:00Z"), rootId = "root-2",
            )
            org.junit.Assert.fail("expected IllegalArgumentException for FORK_CREATED without lineage fields")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("srcRootId"))
        }
    }

    @Test fun `ThreadEvent construction rejects a resolved outcome without outcomeAt`() {
        try {
            ThreadEvent(
                eventId = "ev_1", kind = ThreadEventKind.DELEGATION,
                occurredAtUtc = Instant.parse("2026-08-10T09:25:00Z"),
                delegationId = "dg_1", delegationKind = DelegationKind.AUTO_DEFAULT,
                outcome = DelegationOutcome.KEPT, outcomeAt = null,
            )
            org.junit.Assert.fail("expected IllegalArgumentException for a resolved outcome with a null outcomeAt")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("outcomeAt"))
        }
    }

    // ---- ThreadGraph -----------------------------------------------------------------------

    private fun graph() = ThreadGraph(
        generatedAtUtc = Instant.parse("2026-08-12T00:00:00Z"),
        nodes = listOf(
            ThreadGraphNode(id = "root-1", kind = ThreadNodeKind.MESSAGE, rootId = "root-1", parentId = null, at = Instant.parse("2026-08-09T10:00:00Z")),
            ThreadGraphNode(id = "msg-42", kind = ThreadNodeKind.MESSAGE, rootId = "root-1", parentId = "root-1", at = Instant.parse("2026-08-09T10:05:00Z")),
            ThreadGraphNode(id = "root-2", kind = ThreadNodeKind.FORK_ROOT, rootId = "root-2", parentId = "msg-42", at = Instant.parse("2026-08-10T09:20:00Z")),
        ),
        edges = listOf(
            ThreadGraphEdge(from = "root-1", to = "msg-42", kind = ThreadEdgeKind.REPLY),
            ThreadGraphEdge(from = "msg-42", to = "root-2", kind = ThreadEdgeKind.FORK),
        ),
    )

    @Test fun `ThreadGraph round-trips its nodes and edges`() {
        val original = graph()
        val decoded = ThreadCodec.decodeThreadGraph(ThreadCodec.encodeThreadGraph(original))
        assertEquals(original, decoded)
        assertEquals(3, decoded.nodes.size)
        assertEquals(2, decoded.edges.size)
    }

    @Test fun `ThreadGraph round-trip preserves an unknown field end to end`() {
        val original = graph()
        val json = ThreadCodec.encodeThreadGraph(original)
        json.put("futureField", "future")
        val decoded = ThreadCodec.decodeThreadGraph(json)
        assertEquals("future", decoded.unknownFields["futureField"])
    }

    @Test fun `ThreadGraph with no nodes or edges round-trips to empty lists`() {
        val original = ThreadGraph(generatedAtUtc = Instant.parse("2026-08-12T00:00:00Z"))
        val decoded = ThreadCodec.decodeThreadGraph(ThreadCodec.encodeThreadGraph(original))
        assertEquals(emptyList<ThreadGraphNode>(), decoded.nodes)
        assertEquals(emptyList<ThreadGraphEdge>(), decoded.edges)
    }

    // ---- Fixture parsing (REAL fixtures/thread/valid/ content, embedded verbatim) -----------

    // Verbatim copy of fixtures/thread/valid/thread-marker-chapter-valid.json
    private val threadMarkerChapterValidJson = """
        {
          "schemaVersion": "1.0.0",
          "id": "01J9M0000000000000000CH1",
          "rootId": "01J8Z0000000000000000RT1",
          "anchorMsgId": "01J8Z00000000000000MSG42",
          "kind": "CHAPTER",
          "label": "Auth flow rewrite",
          "note": "Everything above this belongs to the old cookie-based session design.",
          "at": "2026-08-10T09:14:31Z",
          "source": "USER",
          "payloadJson": null,
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `thread-marker-chapter-valid fixture decodes to the expected ThreadMarker`() {
        val decoded = ThreadCodec.decodeThreadMarker(JSONObject(threadMarkerChapterValidJson))
        assertEquals("01J9M0000000000000000CH1", decoded.id)
        assertEquals(ThreadMarkerKind.CHAPTER, decoded.kind)
        assertEquals("Auth flow rewrite", decoded.label)
        assertEquals(ThreadMarkerSource.USER, decoded.source)
        // Re-encoding a fixture that already validates against the schema must not change its
        // decoded meaning (a decode-then-re-encode-then-decode cycle is idempotent).
        val roundTripped = ThreadCodec.decodeThreadMarker(ThreadCodec.encodeThreadMarker(decoded))
        assertEquals(decoded, roundTripped)
    }

    // Verbatim copy of fixtures/thread/valid/thread-event-fork-created-valid.json
    private val threadEventForkCreatedValidJson = """
        {
          "schemaVersion": "1.0.0",
          "eventId": "01J9E0000000000000000FK1",
          "kind": "FORK_CREATED",
          "occurredAtUtc": "2026-08-10T09:20:00Z",
          "rootId": "01J8Z0000000000000000RT2",
          "anchorMsgId": "01J8Z00000000000000MSG42",
          "srcRootId": "01J8Z0000000000000000RT1",
          "srcNodeId": "01J8Z00000000000000MSG42",
          "newRootId": "01J8Z0000000000000000RT2",
          "markerId": null,
          "label": null,
          "delegationId": null,
          "delegationKind": null,
          "chosenRef": null,
          "alternatives": [],
          "outcome": null,
          "outcomeAt": null,
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `thread-event-fork-created-valid fixture decodes to the expected ThreadEvent`() {
        val decoded = ThreadCodec.decodeThreadEvent(JSONObject(threadEventForkCreatedValidJson))
        assertEquals(ThreadEventKind.FORK_CREATED, decoded.kind)
        assertEquals("01J8Z0000000000000000RT1", decoded.srcRootId)
        assertEquals("01J8Z0000000000000000RT2", decoded.newRootId)
        assertTrue(decoded.alternatives.isEmpty())
    }

    // Verbatim copy of fixtures/thread/valid/thread-event-delegation-kept-valid.json
    private val threadEventDelegationKeptValidJson = """
        {
          "schemaVersion": "1.0.0",
          "eventId": "01J9E0000000000000000DL1",
          "kind": "DELEGATION",
          "occurredAtUtc": "2026-08-10T09:25:00Z",
          "rootId": "01J8Z0000000000000000RT1",
          "anchorMsgId": "01J8Z00000000000000MSG50",
          "srcRootId": null,
          "srcNodeId": null,
          "newRootId": null,
          "markerId": null,
          "label": null,
          "delegationId": "01J9D0000000000000000DG1",
          "delegationKind": "MODEL_PICK_BRANCH",
          "chosenRef": "01J8Z00000000000000MSG51",
          "alternatives": ["01J8Z00000000000000MSG52", "01J8Z00000000000000MSG53"],
          "outcome": "KEPT",
          "outcomeAt": "2026-08-10T09:40:00Z",
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `thread-event-delegation-kept-valid fixture decodes with a resolved outcome`() {
        val decoded = ThreadCodec.decodeThreadEvent(JSONObject(threadEventDelegationKeptValidJson))
        assertEquals(DelegationOutcome.KEPT, decoded.outcome)
        assertEquals(Instant.parse("2026-08-10T09:40:00Z"), decoded.outcomeAt)
        assertEquals(2, decoded.alternatives.size)
    }

    // Verbatim copy of fixtures/thread/invalid/thread-event-fork-created-missing-lineage.invalid.json
    private val threadEventForkCreatedMissingLineageJson = """
        {
          "schemaVersion": "1.0.0",
          "eventId": "01J9E0000000000000000FK2",
          "kind": "FORK_CREATED",
          "occurredAtUtc": "2026-08-10T09:20:00Z",
          "rootId": "01J8Z0000000000000000RT2",
          "srcRootId": "01J8Z0000000000000000RT1",
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the invalid missing-lineage fixture is rejected by decode, not silently accepted`() {
        try {
            ThreadCodec.decodeThreadEvent(JSONObject(threadEventForkCreatedMissingLineageJson))
            org.junit.Assert.fail("expected IllegalArgumentException for FORK_CREATED missing srcNodeId/newRootId")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("srcRootId"))
        }
    }
}
