package dev.aarso.domain.curation

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * `org.json` encode/decode for [Receipt] — the payload [dev.aarso.ui.ChatViewModel.runCompaction]
 * hands [dev.aarso.data.ReceiptStore.append] wrapped in a
 * `dev.aarso.contracts.common.ContractEnvelope<Receipt>` (same shape
 * [dev.aarso.domain.authority.AuditedAuthorityEngine] uses for its own receipts), and decodes
 * back via `dev.aarso.domain.contracts.EnvelopeCodec.decode` whenever a later turn's
 * [CompactionBoundary.effectivePath] needs a prior run's fates to reconstruct the truncated
 * prompt.
 *
 * Deliberately no `schemas/thread/` counterpart and no `unknownFields` round-trip: this shape is
 * private-repo-only per THREAD_TOPOLOGY_PLAN.md's placement decision (compaction semantics are
 * under the owner's patent hold, decision D-V — "nothing compaction-semantic goes to public
 * repos"), the same reason [CompactionEngine] itself lives here and not in a `schemas/` contract.
 */
object CompactionReceiptCodec {

    fun encodeReceipt(receipt: Receipt): JSONObject = JSONObject().apply {
        put("runAt", Instant.ofEpochMilli(receipt.runAt).toString())
        put("entries", JSONArray(receipt.entries.map(::encodeCompactedMessage)))
        put("directivesHonored", receipt.directivesHonored)
        put("directivesTotal", receipt.directivesTotal)
    }

    fun decodeReceipt(json: JSONObject): Receipt = Receipt(
        runAt = Instant.parse(json.getString("runAt")).toEpochMilli(),
        entries = json.getJSONArray("entries").let { arr ->
            (0 until arr.length()).map { decodeCompactedMessage(arr.getJSONObject(it)) }
        },
        directivesHonored = json.getInt("directivesHonored"),
        directivesTotal = json.getInt("directivesTotal"),
    )

    private fun encodeCompactedMessage(message: CompactedMessage): JSONObject = JSONObject().apply {
        put("msgId", message.msgId)
        put("fate", message.fate.name)
        put("fidelity", message.resolution.fidelity.name)
        put("mustInclude", message.resolution.mustInclude)
        put("isFailureTombstone", message.resolution.isFailureTombstone)
        put("reason", message.resolution.reason.name)
        // org.json.JSONObject.put(key, null) removes the key rather than storing JSON null, so a
        // genuinely-null text (DROPPED) is represented by the key's absence — decodeCompactedMessage
        // below reads that back as null via `json.has("text")`, not as the *string* "null".
        message.text?.let { put("text", it) }
    }

    private fun decodeCompactedMessage(json: JSONObject): CompactedMessage = CompactedMessage(
        msgId = json.getString("msgId"),
        fate = MessageFate.valueOf(json.getString("fate")),
        resolution = ResolvedFidelity(
            fidelity = Fidelity.valueOf(json.getString("fidelity")),
            mustInclude = json.getBoolean("mustInclude"),
            isFailureTombstone = json.getBoolean("isFailureTombstone"),
            reason = FidelityReason.valueOf(json.getString("reason")),
        ),
        text = if (json.has("text")) json.getString("text") else null,
    )
}
