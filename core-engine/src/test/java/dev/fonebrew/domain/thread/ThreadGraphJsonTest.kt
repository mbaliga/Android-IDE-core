package dev.fonebrew.domain.thread

import dev.fonebrew.contracts.common.ProducerRef
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

    private fun producer() = ProducerRef(name = "dev.fonebrew.thread.ThreadGraphProjector", version = "1.0.0")

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
            dev.fonebrew.contracts.common.ContractEnvelope(
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

    // Verbatim copy of fixtures/thread/valid/thread-graph-decision-and-run-root-valid.json (2026-08-29 audit, gaps 1+3).
    private val threadGraphDecisionAndRunRootValidJson = """
        {
          "schemaVersion": "1.1.0",
          "generatedAtUtc": "2026-08-29T00:00:00Z",
          "nodes": [
            { "id": "01J8Z0000000000000000RT1", "kind": "MESSAGE", "rootId": "01J8Z0000000000000000RT1", "parentId": null, "at": "2026-08-09T10:00:00Z", "label": null },
            { "id": "01J8Z00000000000000MSG42", "kind": "MESSAGE", "rootId": "01J8Z0000000000000000RT1", "parentId": "01J8Z0000000000000000RT1", "at": "2026-08-09T10:05:00Z", "label": null },
            { "id": "01J9D0000000000000DEC01", "kind": "DECISION", "rootId": "01J8Z0000000000000000RT1", "parentId": "01J8Z00000000000000MSG42", "at": "2026-08-10T09:14:31Z", "label": "Use SQLDelight for FTS5" },
            { "id": "01J9R0000000000000RUN01", "kind": "RUN_ROOT", "rootId": "01J9R0000000000000RUN01", "parentId": null, "at": "2026-08-11T08:00:00Z", "label": "Refine the search ranking prompt" }
          ],
          "edges": [
            { "from": "01J8Z0000000000000000RT1", "to": "01J8Z00000000000000MSG42", "kind": "REPLY" },
            { "from": "01J9D0000000000000DEC01", "to": "01J8Z00000000000000MSG42", "kind": "DECISION_ANCHOR" }
          ],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the decision-and-run-root fixture decodes with the new 1_1_0 node kinds`() {
        val decoded = ThreadGraphJson.fromJsonString(threadGraphDecisionAndRunRootValidJson)
        assertEquals(4, decoded.nodes.size)
        assertEquals(ThreadNodeKind.DECISION, decoded.nodes.single { it.id == "01J9D0000000000000DEC01" }.kind)
        assertEquals(ThreadNodeKind.RUN_ROOT, decoded.nodes.single { it.id == "01J9R0000000000000RUN01" }.kind)
        assertTrue(ThreadGraphEdge("01J9D0000000000000DEC01", "01J8Z00000000000000MSG42", ThreadEdgeKind.DECISION_ANCHOR) in decoded.edges)

        val roundTripped = ThreadGraphJson.fromJsonString(ThreadGraphJson.toJsonString(decoded))
        assertEquals(decoded, roundTripped)
    }

    // Verbatim copy of fixtures/thread/valid/thread-graph-delegation-outcome-and-confidence-valid.json (2026-08-29 audit, gaps 2+4).
    private val threadGraphOutcomeAndConfidenceValidJson = """
        {
          "schemaVersion": "1.1.0",
          "generatedAtUtc": "2026-08-29T00:00:00Z",
          "nodes": [
            { "id": "01J8Z0000000000000000RT1", "kind": "MESSAGE", "rootId": "01J8Z0000000000000000RT1", "parentId": null, "at": "2026-08-09T10:00:00Z", "label": null },
            { "id": "01J8Z00000000000000MSG42", "kind": "MESSAGE", "rootId": "01J8Z0000000000000000RT1", "parentId": "01J8Z0000000000000000RT1", "at": "2026-08-09T10:05:00Z", "label": null, "confidence": 0.82 },
            { "id": "01J9D0000000000000000DG1", "kind": "DELEGATION", "rootId": "01J8Z0000000000000000RT1", "parentId": "01J8Z00000000000000MSG42", "at": "2026-08-10T09:25:00Z", "label": "MODEL_PICK_BRANCH", "outcome": "KEPT" }
          ],
          "edges": [
            { "from": "01J8Z0000000000000000RT1", "to": "01J8Z00000000000000MSG42", "kind": "REPLY" },
            { "from": "01J9D0000000000000000DG1", "to": "01J8Z00000000000000MSG42", "kind": "DELEGATION_ANCHOR" }
          ],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the outcome-and-confidence fixture decodes with both new optional node fields`() {
        val decoded = ThreadGraphJson.fromJsonString(threadGraphOutcomeAndConfidenceValidJson)
        assertEquals(0.82, decoded.nodes.single { it.id == "01J8Z00000000000000MSG42" }.confidence!!, 1e-9)
        assertEquals(DelegationOutcome.KEPT, decoded.nodes.single { it.id == "01J9D0000000000000000DG1" }.outcome)
        // Every other node's outcome/confidence stays absent — never fabricated for a kind that
        // doesn't carry one.
        assertTrue(decoded.nodes.filter { it.kind != ThreadNodeKind.DELEGATION }.all { it.outcome == null })

        val roundTripped = ThreadGraphJson.fromJsonString(ThreadGraphJson.toJsonString(decoded))
        assertEquals(decoded, roundTripped)
    }

    // Verbatim copy of fixtures/thread/invalid/thread-graph-bad-outcome-value.invalid.json
    private val threadGraphBadOutcomeValueJson = """
        {
          "schemaVersion": "1.1.0",
          "generatedAtUtc": "2026-08-29T00:00:00Z",
          "nodes": [
            { "id": "01J8Z0000000000000000RT1", "kind": "MESSAGE", "rootId": "01J8Z0000000000000000RT1", "at": "2026-08-09T10:00:00Z" },
            { "id": "01J9D0000000000000000DG1", "kind": "DELEGATION", "rootId": "01J8Z0000000000000000RT1", "at": "2026-08-10T09:25:00Z", "outcome": "MAYBE" }
          ],
          "edges": [],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the invalid bad-outcome-value fixture is rejected by decode, not silently accepted`() {
        try {
            ThreadGraphJson.fromJsonString(threadGraphBadOutcomeValueJson)
            org.junit.Assert.fail("expected IllegalArgumentException for an unrecognized DelegationOutcome value")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("MAYBE"))
        }
    }

    // Verbatim copy of fixtures/thread/invalid/thread-graph-confidence-out-of-range.invalid.json
    private val threadGraphConfidenceOutOfRangeJson = """
        {
          "schemaVersion": "1.1.0",
          "generatedAtUtc": "2026-08-29T00:00:00Z",
          "nodes": [
            { "id": "01J8Z0000000000000000RT1", "kind": "MESSAGE", "rootId": "01J8Z0000000000000000RT1", "at": "2026-08-09T10:00:00Z" },
            { "id": "01J8Z00000000000000MSG42", "kind": "MESSAGE", "rootId": "01J8Z0000000000000000RT1", "parentId": "01J8Z0000000000000000RT1", "at": "2026-08-09T10:05:00Z", "confidence": 1.5 }
          ],
          "edges": [
            { "from": "01J8Z0000000000000000RT1", "to": "01J8Z00000000000000MSG42", "kind": "REPLY" }
          ],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the invalid out-of-range confidence fixture is rejected by decode, not silently accepted`() {
        try {
            ThreadGraphJson.fromJsonString(threadGraphConfidenceOutOfRangeJson)
            org.junit.Assert.fail("expected IllegalArgumentException for confidence outside [0,1]")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("confidence"))
        }
    }

    // Verbatim copy of fixtures/thread/valid/thread-graph-derivation-and-commit-anchor-valid.json (graph-wave lane A, 1.2.0)
    private val threadGraphDerivationAndCommitAnchorValidJson = """
        {
          "schemaVersion": "1.2.0",
          "generatedAtUtc": "2026-09-06T00:00:00Z",
          "nodes": [
            { "id": "01JB00000000000000000RT1", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "parentId": null, "at": "2026-09-01T10:00:00Z", "label": null },
            { "id": "01JB0000000000000MSG101", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "parentId": "01JB00000000000000000RT1", "at": "2026-09-01T10:05:00Z", "label": null },
            { "id": "01JB0000000000000MSG102", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "parentId": "01JB0000000000000MSG101", "at": "2026-09-01T10:06:00Z", "label": null },
            { "id": "01JB000000000000COMMIT1", "kind": "COMMIT", "rootId": "01JB00000000000000000RT1", "parentId": "01JB0000000000000MSG101", "at": "2026-09-01T10:07:00Z", "label": "Fix off-by-one in fixture indexer", "sha": "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678", "repoRef": "mbaliga/android-ide-core@main" }
          ],
          "edges": [
            { "from": "01JB00000000000000000RT1", "to": "01JB0000000000000MSG101", "kind": "REPLY", "derivation": "EXTRACTED", "because": "parent-child reply recorded in the message tree" },
            { "from": "01JB0000000000000MSG101", "to": "01JB0000000000000MSG102", "kind": "REPLY", "derivation": "INFERRED", "because": "a future analysis layer would derive this continuation relationship; no current projector emits it" },
            { "from": "01JB0000000000000MSG101", "to": "01JB000000000000COMMIT1", "kind": "COMMIT_ANCHOR", "derivation": "EXTRACTED", "because": "commit sha minted from this message's agent-run ChangeSet commit" }
          ],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the derivation-and-commit-anchor fixture decodes with the new 1_2_0 edge and COMMIT vocabulary`() {
        val decoded = ThreadGraphJson.fromJsonString(threadGraphDerivationAndCommitAnchorValidJson)
        assertEquals(4, decoded.nodes.size)
        assertEquals(3, decoded.edges.size)
        assertEquals(ThreadNodeKind.COMMIT, decoded.nodes.single { it.id == "01JB000000000000COMMIT1" }.kind)
        assertTrue(
            ThreadGraphEdge(
                "01JB0000000000000MSG101", "01JB000000000000COMMIT1", ThreadEdgeKind.COMMIT_ANCHOR,
                EdgeDerivation.EXTRACTED, "commit sha minted from this message's agent-run ChangeSet commit",
            ) in decoded.edges,
        )
        assertTrue(decoded.edges.any { it.derivation == EdgeDerivation.INFERRED })

        val roundTripped = ThreadGraphJson.fromJsonString(ThreadGraphJson.toJsonString(decoded))
        assertEquals(decoded, roundTripped)
    }

    @Test fun `the fixture's COMMIT_ANCHOR edge also round-trips through the full envelope wrapper`() {
        val payload = ThreadGraphJson.fromJsonString(threadGraphDerivationAndCommitAnchorValidJson)
        val envelope = ThreadGraphJson.envelope(payload, objectId = "fixture-obj-2", producer = producer())
        val decoded = ThreadGraphJson.decode(ThreadGraphJson.encode(envelope))
        assertEquals(payload, decoded.payload)
    }

    // Verbatim copy of fixtures/thread/invalid/thread-graph-edge-missing-derivation.invalid.json
    private val threadGraphEdgeMissingDerivationJson = """
        {
          "schemaVersion": "1.2.0",
          "generatedAtUtc": "2026-09-06T00:00:00Z",
          "nodes": [
            { "id": "01JB00000000000000000RT1", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "at": "2026-09-01T10:00:00Z" },
            { "id": "01JB0000000000000MSG101", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "parentId": "01JB00000000000000000RT1", "at": "2026-09-01T10:05:00Z" }
          ],
          "edges": [
            { "from": "01JB00000000000000000RT1", "to": "01JB0000000000000MSG101", "kind": "REPLY", "because": "parent-child reply recorded in the message tree" }
          ],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the invalid missing-derivation fixture is rejected by decode, not silently accepted`() {
        try {
            ThreadGraphJson.fromJsonString(threadGraphEdgeMissingDerivationJson)
            org.junit.Assert.fail("expected IllegalArgumentException for a because with no derivation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("derivation"))
        }
    }

    // Verbatim copy of fixtures/thread/invalid/thread-graph-edge-empty-because.invalid.json
    private val threadGraphEdgeEmptyBecauseJson = """
        {
          "schemaVersion": "1.2.0",
          "generatedAtUtc": "2026-09-06T00:00:00Z",
          "nodes": [
            { "id": "01JB00000000000000000RT1", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "at": "2026-09-01T10:00:00Z" },
            { "id": "01JB0000000000000MSG101", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "parentId": "01JB00000000000000000RT1", "at": "2026-09-01T10:05:00Z" }
          ],
          "edges": [
            { "from": "01JB00000000000000000RT1", "to": "01JB0000000000000MSG101", "kind": "REPLY", "derivation": "EXTRACTED", "because": "" }
          ],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the invalid empty-because fixture is rejected by decode, not silently accepted`() {
        try {
            ThreadGraphJson.fromJsonString(threadGraphEdgeEmptyBecauseJson)
            org.junit.Assert.fail("expected IllegalArgumentException for a blank because")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("because"))
        }
    }

    // Verbatim copy of fixtures/thread/invalid/thread-graph-edge-unknown-derivation-value.invalid.json
    private val threadGraphEdgeUnknownDerivationValueJson = """
        {
          "schemaVersion": "1.2.0",
          "generatedAtUtc": "2026-09-06T00:00:00Z",
          "nodes": [
            { "id": "01JB00000000000000000RT1", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "at": "2026-09-01T10:00:00Z" },
            { "id": "01JB0000000000000MSG101", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "parentId": "01JB00000000000000000RT1", "at": "2026-09-01T10:05:00Z" }
          ],
          "edges": [
            { "from": "01JB00000000000000000RT1", "to": "01JB0000000000000MSG101", "kind": "REPLY", "derivation": "GUESSED", "because": "a made-up derivation value that isn't EXTRACTED or INFERRED" }
          ],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the invalid unknown-derivation-value fixture is rejected by decode, not silently accepted`() {
        try {
            ThreadGraphJson.fromJsonString(threadGraphEdgeUnknownDerivationValueJson)
            org.junit.Assert.fail("expected IllegalArgumentException for an unrecognized EdgeDerivation value")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("GUESSED"))
        }
    }

    // Verbatim copy of fixtures/thread/invalid/thread-graph-commit-missing-sha.invalid.json
    private val threadGraphCommitMissingShaJson = """
        {
          "schemaVersion": "1.2.0",
          "generatedAtUtc": "2026-09-06T00:00:00Z",
          "nodes": [
            { "id": "01JB00000000000000000RT1", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "at": "2026-09-01T10:00:00Z" },
            { "id": "01JB000000000000COMMIT1", "kind": "COMMIT", "rootId": "01JB00000000000000000RT1", "parentId": "01JB00000000000000000RT1", "at": "2026-09-01T10:07:00Z", "label": "Fix off-by-one", "repoRef": "mbaliga/android-ide-core@main" }
          ],
          "edges": [],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the invalid commit-missing-sha fixture is rejected by decode, not silently accepted`() {
        try {
            ThreadGraphJson.fromJsonString(threadGraphCommitMissingShaJson)
            org.junit.Assert.fail("expected IllegalArgumentException for a COMMIT node without a sha")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("COMMIT"))
        }
    }

    // Verbatim copy of fixtures/thread/adversarial/thread-graph-inferred-edge-interpretive-because.adversarial.json —
    // structurally PASSES (see its own .expected.txt sibling for the semantic-layer obligation this documents).
    private val threadGraphInferredEdgeInterpretiveBecauseJson = """
        {
          "schemaVersion": "1.2.0",
          "generatedAtUtc": "2026-09-06T00:00:00Z",
          "nodes": [
            { "id": "01JB00000000000000000RT1", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "at": "2026-09-01T10:00:00Z" },
            { "id": "01JB0000000000000MSG101", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "parentId": "01JB00000000000000000RT1", "at": "2026-09-01T10:05:00Z" },
            { "id": "01JB0000000000000MSG102", "kind": "MESSAGE", "rootId": "01JB00000000000000000RT1", "parentId": "01JB0000000000000MSG101", "at": "2026-09-01T10:12:00Z" }
          ],
          "edges": [
            { "from": "01JB00000000000000000RT1", "to": "01JB0000000000000MSG101", "kind": "REPLY", "derivation": "EXTRACTED", "because": "parent-child reply recorded in the message tree" },
            { "from": "01JB0000000000000MSG101", "to": "01JB0000000000000MSG102", "kind": "REPLY", "derivation": "INFERRED", "because": "the user's phrasing here echoes a defensive, hedging pattern seen a few sessions ago -- this edge marks the probable idiolect drift between the two turns" }
          ],
          "unknownFields": {}
        }
    """.trimIndent()

    @Test fun `the adversarial inferred-edge fixture structurally decodes — the defect is semantic, per its own expected txt`() {
        // Mirrors this corpus's existing adversarial idiom (thread-marker-interpretive-note,
        // thread-event-delegation-secret-leak): JSON Schema / this codec cannot see that
        // `because`'s CONTENT interprets the user, only that it is a non-blank string paired with
        // a `derivation` — so decode succeeds. The obligation a real INFERRED-edge producer MUST
        // satisfy lives in the fixture's own `.expected.txt` sibling, not as a decode-time check.
        val decoded = ThreadGraphJson.fromJsonString(threadGraphInferredEdgeInterpretiveBecauseJson)
        assertEquals(EdgeDerivation.INFERRED, decoded.edges.single { it.kind == ThreadEdgeKind.REPLY && it.from == "01JB0000000000000MSG101" }.derivation)
    }
}
