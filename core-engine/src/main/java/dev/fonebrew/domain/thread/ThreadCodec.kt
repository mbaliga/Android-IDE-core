// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.thread

import dev.fonebrew.domain.contracts.extractUnknownFields
import dev.fonebrew.domain.contracts.mergeUnknownFields
import dev.fonebrew.domain.contracts.optStringOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * `org.json` encode/decode for every `dev.fonebrew.domain.thread` shape, mirroring the three
 * `schemas/thread/` documents (thread-event, thread-marker, thread-graph) field-for-field — same
 * discipline `dev.fonebrew.domain.contracts.EnvelopeCodec` uses for the `schemas/common/` ones, and reusing
 * that file's [dev.fonebrew.domain.contracts.extractUnknownFields]/[dev.fonebrew.domain.contracts.mergeUnknownFields]
 * helpers so unknown-field round-trip (FB-RAT-COM-003) works identically here.
 *
 * These types are pure-JVM and Room-independent (THREAD_TOPOLOGY_PLAN.md's placement decision —
 * "graph substrate lives in core, pure JVM, extraction-ready"); [ThreadMarker]/[DelegationEvent]
 * additionally get mapped to/from their Room entities by [dev.fonebrew.data.ThreadMarkerStore]/
 * [dev.fonebrew.data.DelegationStore] (WP1), a separate, simpler `Long`-epoch-millis mapping that
 * does not go through this codec at all — this file's job is the JSON wire contract only.
 */
object ThreadCodec {

    // -------------------------------------------------------------------------------------
    // ThreadMarker
    // -------------------------------------------------------------------------------------

    private val THREAD_MARKER_KNOWN_KEYS = setOf(
        "schemaVersion", "id", "rootId", "anchorMsgId", "kind", "label", "note", "at", "source", "payloadJson"
    )

    fun encodeThreadMarker(marker: ThreadMarker): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", marker.schemaVersion)
        obj.put("id", marker.id)
        obj.put("rootId", marker.rootId)
        marker.anchorMsgId?.let { obj.put("anchorMsgId", it) }
        obj.put("kind", marker.kind.name)
        marker.label?.let { obj.put("label", it) }
        marker.note?.let { obj.put("note", it) }
        obj.put("at", Instant.ofEpochMilli(marker.at).toString())
        obj.put("source", marker.source.name)
        marker.payloadJson?.let { obj.put("payloadJson", it) }
        mergeUnknownFields(obj, marker.unknownFields)
        return obj
    }

    fun decodeThreadMarker(json: JSONObject): ThreadMarker = ThreadMarker(
        id = json.getString("id"),
        rootId = json.getString("rootId"),
        anchorMsgId = json.optStringOrNull("anchorMsgId"),
        kind = ThreadMarkerKind.valueOf(json.getString("kind")),
        label = json.optStringOrNull("label"),
        note = json.optStringOrNull("note"),
        at = Instant.parse(json.getString("at")).toEpochMilli(),
        source = ThreadMarkerSource.valueOf(json.getString("source")),
        payloadJson = json.optStringOrNull("payloadJson"),
        schemaVersion = json.getString("schemaVersion"),
        unknownFields = extractUnknownFields(json, THREAD_MARKER_KNOWN_KEYS),
    )

    // -------------------------------------------------------------------------------------
    // ThreadEvent
    // -------------------------------------------------------------------------------------

    private val THREAD_EVENT_KNOWN_KEYS = setOf(
        "schemaVersion", "eventId", "kind", "occurredAtUtc", "rootId", "anchorMsgId",
        "srcRootId", "srcNodeId", "newRootId", "markerId", "label",
        "delegationId", "delegationKind", "chosenRef", "alternatives", "outcome", "outcomeAt",
    )

    fun encodeThreadEvent(event: ThreadEvent): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", event.schemaVersion)
        obj.put("eventId", event.eventId)
        obj.put("kind", event.kind.name)
        obj.put("occurredAtUtc", event.occurredAtUtc.toString())
        event.rootId?.let { obj.put("rootId", it) }
        event.anchorMsgId?.let { obj.put("anchorMsgId", it) }
        event.srcRootId?.let { obj.put("srcRootId", it) }
        event.srcNodeId?.let { obj.put("srcNodeId", it) }
        event.newRootId?.let { obj.put("newRootId", it) }
        event.markerId?.let { obj.put("markerId", it) }
        event.label?.let { obj.put("label", it) }
        event.delegationId?.let { obj.put("delegationId", it) }
        event.delegationKind?.let { obj.put("delegationKind", it.name) }
        event.chosenRef?.let { obj.put("chosenRef", it) }
        if (event.alternatives.isNotEmpty()) obj.put("alternatives", JSONArray(event.alternatives))
        event.outcome?.let { obj.put("outcome", it.name) }
        event.outcomeAt?.let { obj.put("outcomeAt", it.toString()) }
        mergeUnknownFields(obj, event.unknownFields)
        return obj
    }

    fun decodeThreadEvent(json: JSONObject): ThreadEvent = ThreadEvent(
        eventId = json.getString("eventId"),
        kind = ThreadEventKind.valueOf(json.getString("kind")),
        occurredAtUtc = Instant.parse(json.getString("occurredAtUtc")),
        rootId = json.optStringOrNull("rootId"),
        anchorMsgId = json.optStringOrNull("anchorMsgId"),
        srcRootId = json.optStringOrNull("srcRootId"),
        srcNodeId = json.optStringOrNull("srcNodeId"),
        newRootId = json.optStringOrNull("newRootId"),
        markerId = json.optStringOrNull("markerId"),
        label = json.optStringOrNull("label"),
        delegationId = json.optStringOrNull("delegationId"),
        delegationKind = json.optStringOrNull("delegationKind")?.let(DelegationKind::valueOf),
        chosenRef = json.optStringOrNull("chosenRef"),
        alternatives = json.optJSONArrayOrEmpty("alternatives"),
        outcome = json.optStringOrNull("outcome")?.let(DelegationOutcome::valueOf),
        outcomeAt = json.optStringOrNull("outcomeAt")?.let(Instant::parse),
        schemaVersion = json.getString("schemaVersion"),
        unknownFields = extractUnknownFields(json, THREAD_EVENT_KNOWN_KEYS),
    )

    // -------------------------------------------------------------------------------------
    // ThreadGraph
    // -------------------------------------------------------------------------------------

    private val THREAD_GRAPH_KNOWN_KEYS = setOf("schemaVersion", "generatedAtUtc", "nodes", "edges")
    private val THREAD_GRAPH_NODE_KNOWN_KEYS = setOf("id", "kind", "rootId", "parentId", "at", "label", "outcome", "confidence", "sha", "repoRef")
    private val THREAD_GRAPH_EDGE_KNOWN_KEYS = setOf("from", "to", "kind", "derivation", "because")

    fun encodeThreadGraph(graph: ThreadGraph): JSONObject {
        val obj = JSONObject()
        obj.put("schemaVersion", graph.schemaVersion)
        obj.put("generatedAtUtc", graph.generatedAtUtc.toString())
        obj.put("nodes", JSONArray(graph.nodes.map(::encodeThreadGraphNode)))
        obj.put("edges", JSONArray(graph.edges.map(::encodeThreadGraphEdge)))
        mergeUnknownFields(obj, graph.unknownFields)
        return obj
    }

    fun decodeThreadGraph(json: JSONObject): ThreadGraph = ThreadGraph(
        generatedAtUtc = Instant.parse(json.getString("generatedAtUtc")),
        nodes = json.optJSONArray("nodes")?.let { arr -> (0 until arr.length()).map { decodeThreadGraphNode(arr.getJSONObject(it)) } } ?: emptyList(),
        edges = json.optJSONArray("edges")?.let { arr -> (0 until arr.length()).map { decodeThreadGraphEdge(arr.getJSONObject(it)) } } ?: emptyList(),
        schemaVersion = json.getString("schemaVersion"),
        unknownFields = extractUnknownFields(json, THREAD_GRAPH_KNOWN_KEYS),
    )

    private fun encodeThreadGraphNode(node: ThreadGraphNode): JSONObject = JSONObject().apply {
        put("id", node.id)
        put("kind", node.kind.name)
        put("rootId", node.rootId)
        node.parentId?.let { put("parentId", it) }
        put("at", node.at.toString())
        node.label?.let { put("label", it) }
        node.outcome?.let { put("outcome", it.name) }
        node.confidence?.let { put("confidence", it) }
        node.sha?.let { put("sha", it) }
        node.repoRef?.let { put("repoRef", it) }
    }

    private fun decodeThreadGraphNode(json: JSONObject): ThreadGraphNode = ThreadGraphNode(
        id = json.getString("id"),
        kind = ThreadNodeKind.valueOf(json.getString("kind")),
        rootId = json.getString("rootId"),
        parentId = json.optStringOrNull("parentId"),
        at = Instant.parse(json.getString("at")),
        label = json.optStringOrNull("label"),
        outcome = json.optStringOrNull("outcome")?.let(DelegationOutcome::valueOf),
        confidence = if (json.has("confidence") && !json.isNull("confidence")) json.getDouble("confidence") else null,
        sha = json.optStringOrNull("sha"),
        repoRef = json.optStringOrNull("repoRef"),
    )

    private fun encodeThreadGraphEdge(edge: ThreadGraphEdge): JSONObject = JSONObject().apply {
        put("from", edge.from)
        put("to", edge.to)
        put("kind", edge.kind.name)
        edge.derivation?.let { put("derivation", it.name) }
        edge.because?.let { put("because", it) }
    }

    private fun decodeThreadGraphEdge(json: JSONObject): ThreadGraphEdge = ThreadGraphEdge(
        from = json.getString("from"),
        to = json.getString("to"),
        kind = ThreadEdgeKind.valueOf(json.getString("kind")),
        // 1.2.0: tolerant of absence (old 1.0.x/1.1.x edges predate the field — see
        // ThreadGraphEdge's own KDoc), but a present-and-unrecognized value still throws
        // (EdgeDerivation.valueOf), and ThreadGraphEdge's init block rejects a half-populated
        // pair or a blank `because` — same "tolerant of absence, strict about garbage" split
        // decodeThreadGraphNode's `outcome`/`confidence` already use.
        derivation = json.optStringOrNull("derivation")?.let(EdgeDerivation::valueOf),
        because = json.optStringOrNull("because"),
    )
}

/** Null-safe string-list reader for a JSON array of strings, defaulting to empty (mirrors [dev.fonebrew.domain.contracts.optStringOrNull]'s "absent -> default" shape for a list-typed field). */
private fun JSONObject.optJSONArrayOrEmpty(key: String): List<String> {
    if (!has(key) || isNull(key)) return emptyList()
    val arr = getJSONArray(key)
    return (0 until arr.length()).map { arr.getString(it) }
}
