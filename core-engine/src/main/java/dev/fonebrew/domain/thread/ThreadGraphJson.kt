// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.thread

import dev.fonebrew.contracts.common.ContractEnvelope
import dev.fonebrew.contracts.common.ProducerRef
import dev.fonebrew.domain.contracts.EnvelopeCodec
import org.json.JSONObject
import java.time.Instant

/**
 * **WP9** — the `ContractEnvelope<ThreadGraph>` wire wrapper `schemas/thread/thread-graph.schema.json`
 * names ("Carried as a ContractEnvelope<ThreadGraph> payload, FB-RAT-COM-011"). [ThreadCodec]
 * (WP0) already owns the bare `ThreadGraph` <-> JSON mapping; this file only adds the envelope
 * layer on top, the same split [EnvelopeCodec] draws for every other `schemas/common/`-wrapped
 * payload — it is a thin composition of the two, not a second encoder.
 *
 * [ThreadGraphProjectorTest]/[ThreadGraphJsonTest] both validate against the real
 * `fixtures/thread/valid/thread-graph-valid.json` WP0 fixture (embedded verbatim, same rationale
 * [ThreadCodecTest]'s header comment states — this module's JVM test working directory isn't
 * established as reading repo-root fixture files).
 */
object ThreadGraphJson {

    /** Wraps [graph] in a fresh envelope. [objectId]/[producer] are caller-supplied (a projection
     *  has no natural durable id of its own — it's a point-in-time snapshot, not a stored object,
     *  per the schema's own `generatedAtUtc` doc comment) so two projections of the same tree at
     *  different times are never mistaken for the same envelope. */
    fun envelope(
        graph: ThreadGraph,
        objectId: String,
        producer: ProducerRef,
        createdAtUtc: Instant = graph.generatedAtUtc,
    ): ContractEnvelope<ThreadGraph> = ContractEnvelope(
        schemaVersion = "1.0.0",
        objectId = objectId,
        createdAtUtc = createdAtUtc,
        producer = producer,
        payload = graph,
    )

    fun encode(envelope: ContractEnvelope<ThreadGraph>): JSONObject =
        EnvelopeCodec.encode(envelope, ThreadCodec::encodeThreadGraph)

    fun decode(json: JSONObject): ContractEnvelope<ThreadGraph> =
        EnvelopeCodec.decode(json, ThreadCodec::decodeThreadGraph)

    /** Convenience for a caller that only wants the bare payload's JSON text (no envelope) — e.g.
     *  a future WP11 `__graphRoom.load(json)` bridge call, which takes a graph, not an envelope. */
    fun toJsonString(graph: ThreadGraph): String = ThreadCodec.encodeThreadGraph(graph).toString()

    fun fromJsonString(json: String): ThreadGraph = ThreadCodec.decodeThreadGraph(JSONObject(json))
}
