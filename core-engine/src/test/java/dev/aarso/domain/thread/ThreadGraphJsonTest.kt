package dev.aarso.domain.thread

import dev.aarso.contracts.common.ProducerRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [ThreadGraphJson]'s own round-trip tests, plus decode of the real
 * `fixtures/thread/valid/thread-graph-valid.json` WP0 fixture (embedded verbatim — same rationale
 * [ThreadCodecTest]'s header comment states).
 */
class ThreadGraphJsonTest {

    private fun graph() = ThreadGraph(
        generatedAtUtc = Instant.parse("2026-08-12T00:00:00Z"),
        nodes = listOf(
            ThreadGraphNode(id = "root-1", kind = ThreadNodeKind.MESSAGE, rootId = "root-1", at = Instant.parse("2026-08-09T10:00:00Z")),
            ThreadGraphNode(id = "msg-42", kind = ThreadNodeKind.MESSAGE, rootId = "root-1", parentId = "root-1", at = Instant.parse("2026-08-09T10:05:00Z")),
        ),
        edges = listOf(ThreadGraphEdge(from = "root-1", to = "msg-42", kind = ThreadEdgeKind.REPLY)),
    )

    private fun producer() = ProducerRef(name = "dev.aarso.thread.ThreadGraphProjector", version = "1.0.0")

    @Test fun `envelope wraps a ThreadGraph and round-trips through encode and decode`() {
        val envelope = ThreadGraphJson.envelope(graph(), objectId = "obj-1", producer = producer())
        val decoded = ThreadGraphJson.decode(ThreadGraphJson.encode(envelope))
        assertEquals(envelope, decoded)
        assertEquals("obj-1", decoded.objectId)
        assertEquals(graph(), decoded.payload)
    }

    @Test fun `toJsonString and fromJsonString round-trip the bare payload with no envelope`() {
        val original = graph()
        val decoded = ThreadGraphJson.fromJsonString(ThreadGraphJson.toJsonString(original))
        assertEquals(original, decoded)
    }

    @Test fun `an envelope rejects an unsupported major schemaVersion before construction`() {
        try {
            dev.aarso.contracts.common.ContractEnvelope(
                schemaVersion = "2.0.0", objectId = "obj-1",
                createdAtUtc = Instant.parse("2026-08-12T00:00:00Z"), producer = producer(), payload = graph(),
            )
            org.junit.Assert.fail("expected IllegalArgumentException for an unsupported major schemaVersion")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("major version 1"))
        }
    }

    // Verbatim copy of fixtures/thread/valid/thread-graph-valid.json (WP0).
    private val threadGraphValidJson = """
        {
          "schemaVersion": "1.0.0",
          "generatedAtUtc": "2026-08-12T00:00:00Z",
          "nodes": [
            { "id": "01J8Z0000000000000000RT1", "kind": "MESSAGE", "rootId": "01J8Z0000000000000000RT1", "parentId": null, "at": "2026-08-09T10:00:00Z", "label": null },
            { "id": "01J8Z00000000000000MSG42", "kind": "MESSAGE", "rootId": "01J8Z0000000000000000RT1", "parentId": "01J8Z0000000000000000RT1", "at": "2026-08-09T10:05:00Z", "label": null },
            { "id": "01J8Z0000000000000000RT2", "kind": "FORK_ROOT", "rootId": "01J8Z0000000000000000RT2", "parentId": "01J8Z00000000000000MSG42", "at": "2026-08-10T09:20:00Z", "label": null },
            { "id": "01J9M0000000000000000CH1", "kind": "MARKER", "rootId": "01J8Z0000000000000000RT1", "parentId": "01J8Z00000000000000MSG42", "at": "2026-08-10T09:14:31Z", "label": "Auth flow rewrite" }
          ],
          "edges": [
            { "from": "01J8Z0000000000000000RT1", "to": "01J8Z00000000000000MSG42", "kind": "REPLY" },
            { "from": "01J8Z00000000000000MSG42", "to": "01J8Z0000000000000000RT2", "kind": "FORK" },
            { "from": "01J9M0000000000000000CH1", "to": "01J8Z00000000000000MSG42", "kind": "MARKER_ANCHOR" }
          ],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the thread-graph-valid WP0 fixture decodes to the expected ThreadGraph`() {
        val decoded = ThreadGraphJson.fromJsonString(threadGraphValidJson)
        assertEquals(4, decoded.nodes.size)
        assertEquals(3, decoded.edges.size)
        assertEquals(ThreadNodeKind.FORK_ROOT, decoded.nodes.single { it.id == "01J8Z0000000000000000RT2" }.kind)
        assertEquals(ThreadNodeKind.MARKER, decoded.nodes.single { it.id == "01J9M0000000000000000CH1" }.kind)
        assertTrue(ThreadGraphEdge("01J8Z00000000000000MSG42", "01J8Z0000000000000000RT2", ThreadEdgeKind.FORK) in decoded.edges)

        // Decode-then-re-encode-then-decode must be idempotent (the same discipline ThreadCodecTest
        // already asserts for the other two fixture kinds).
        val roundTripped = ThreadGraphJson.fromJsonString(ThreadGraphJson.toJsonString(decoded))
        assertEquals(decoded, roundTripped)
    }

    @Test fun `the fixture also round-trips through the full envelope wrapper`() {
        val payload = ThreadGraphJson.fromJsonString(threadGraphValidJson)
        val envelope = ThreadGraphJson.envelope(payload, objectId = "fixture-obj", producer = producer())
        val json = ThreadGraphJson.encode(envelope)
        assertEquals("fixture-obj", json.getString("objectId"))
        val decoded = ThreadGraphJson.decode(json)
        assertEquals(payload, decoded.payload)
    }

    @Test fun `encode produces a JSONObject with the fixture's own top-level shape`() {
        val payload = ThreadGraphJson.fromJsonString(threadGraphValidJson)
        val obj: JSONObject = ThreadCodec.encodeThreadGraph(payload)
        assertTrue(obj.has("nodes"))
        assertTrue(obj.has("edges"))
        assertEquals("1.0.0", obj.getString("schemaVersion"))
    }
}
