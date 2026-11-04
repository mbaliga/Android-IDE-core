package dev.fonebrew.domain.bridge

import dev.fonebrew.domain.provenance.ProvenanceState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Insert-time metadata codec for a "bridge" [dev.fonebrew.domain.MessageNode]
 * (THREAD_TOPOLOGY_PLAN.md's `bridge`/`bridge.payload` node-metadata keys). A bridge node's
 * *functional* content (fed to future model calls) is free-form prose; its render-time display
 * ([dev.fonebrew.ui.components.SummaryNodeCard]) instead wants the deterministic structural payload —
 * header + verbatim carry-forward bullets + author attribution — which is exactly [SummaryBridge].
 * This codec is how that structural payload survives the append-only round-trip: written once at
 * insert into [BRIDGE_PAYLOAD_KEY] (binding constraint 5 — metadata minted at insert, never
 * edited), decoded back by the render layer on every recomposition.
 *
 * [BRIDGE_KEY] separately names *which* producer wrote the node (`"interaction_switch"` |
 * `"spawn"`) — purely descriptive; this file never branches on it, callers may.
 *
 * `org.json`-based, same discipline as [dev.fonebrew.domain.thread.ThreadCodec] — pure JVM, no
 * Android. [decode] returns null (never throws) on malformed input: a bridge card that can't be
 * reconstructed degrades to "don't render the card" for its caller, not a crash.
 */
object BridgeCodec {

    const val BRIDGE_KEY = "bridge"
    const val BRIDGE_PAYLOAD_KEY = "bridge.payload"

    fun encode(bridge: SummaryBridge): String {
        val obj = JSONObject()
        obj.put("header", bridge.header)
        obj.put("carriedForward", JSONArray(bridge.carriedForward))
        obj.put("fullPriorAvailable", bridge.fullPriorAvailable)
        bridge.authorModel?.let { obj.put("authorModel", it) }
        obj.put("authorProvenance", bridge.authorProvenance.name)
        return obj.toString()
    }

    fun decode(json: String): SummaryBridge? = runCatching {
        val obj = JSONObject(json)
        val carried = obj.optJSONArray("carriedForward")
            ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            ?: emptyList()
        SummaryBridge(
            header = obj.getString("header"),
            carriedForward = carried,
            fullPriorAvailable = obj.optBoolean("fullPriorAvailable", false),
            authorModel = if (obj.has("authorModel") && !obj.isNull("authorModel")) obj.getString("authorModel") else null,
            authorProvenance = ProvenanceState.valueOf(obj.getString("authorProvenance")),
        )
    }.getOrNull()
}
