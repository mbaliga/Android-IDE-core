// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.aarso.domain.contracts

import dev.aarso.contracts.workspace.BufferJournalEntry
import dev.aarso.contracts.workspace.BufferSnapshotEntry
import dev.aarso.contracts.workspace.ByteRange
import dev.aarso.contracts.workspace.JournalOpType
import dev.aarso.contracts.workspace.LineEndings
import dev.aarso.contracts.workspace.OriginKind
import dev.aarso.contracts.workspace.RecoverySnapshot
import dev.aarso.contracts.workspace.RecoverySnapshotReason
import dev.aarso.contracts.workspace.ResourceProvider
import dev.aarso.contracts.workspace.ResourceUri
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Encode/decode for the `dev.aarso.contracts.workspace` shapes [BufferJournalEntry] and
 * [RecoverySnapshot] (WP-3) -- the same unknown-field-preserving pattern [EnvelopeCodec]
 * established for the `common` domain in WP-2, applied here so `RoomWorkspaceJournal` (WP-3) has
 * a JSON representation to persist a journal entry / snapshot as a single Room column, the same
 * way every other append-only Room row in this codebase stores its payload (`ReceiptEntity.
 * payloadJson`, WP-2) rather than fanning every nested field out into its own SQL column.
 */
object WorkspaceCodec {

    // -------------------------------------------------------------------------------------
    // ResourceUri
    // -------------------------------------------------------------------------------------

    private val RESOURCE_URI_KNOWN_KEYS = setOf("provider", "raw", "displayPath")

    fun encodeResourceUri(uri: ResourceUri): JSONObject {
        val obj = JSONObject()
        obj.put("provider", uri.provider.name)
        obj.put("raw", uri.raw)
        obj.put("displayPath", uri.displayPath)
        return obj
    }

    fun decodeResourceUri(json: JSONObject): ResourceUri = ResourceUri(
        provider = ResourceProvider.valueOf(json.getString("provider")),
        raw = json.getString("raw"),
        displayPath = json.optStringOrNull("displayPath")
    )

    // -------------------------------------------------------------------------------------
    // BufferJournalEntry -- schemas/workspace/buffer-journal-entry.schema.json
    // -------------------------------------------------------------------------------------

    private val JOURNAL_ENTRY_KNOWN_KEYS = setOf(
        "sequence", "bufferId", "resourceUri", "opType", "recordedAtUtc", "originKind",
        "originPrincipal", "byteRange", "content"
    )

    fun encodeBufferJournalEntry(entry: BufferJournalEntry): JSONObject {
        val obj = JSONObject()
        obj.put("sequence", entry.sequence)
        obj.put("bufferId", entry.bufferId)
        obj.put("resourceUri", encodeResourceUri(entry.resourceUri))
        obj.put("opType", entry.opType.name)
        obj.put("recordedAtUtc", entry.recordedAtUtc.toString())
        obj.put("originKind", entry.originKind.name)
        obj.put("originPrincipal", entry.originPrincipal)
        entry.byteRange?.let {
            obj.put("byteRange", JSONObject().apply {
                put("startByte", it.startByte)
                put("endByteExclusive", it.endByteExclusive)
            })
        }
        obj.put("content", entry.content)
        mergeUnknownFields(obj, entry.unknownFields)
        return obj
    }

    fun decodeBufferJournalEntry(json: JSONObject): BufferJournalEntry {
        val byteRangeJson = json.optJSONObjectOrNull("byteRange")
        return BufferJournalEntry(
            sequence = json.getLong("sequence"),
            bufferId = json.getString("bufferId"),
            resourceUri = decodeResourceUri(json.getJSONObject("resourceUri")),
            opType = JournalOpType.valueOf(json.getString("opType")),
            recordedAtUtc = Instant.parse(json.getString("recordedAtUtc")),
            originKind = OriginKind.valueOf(json.getString("originKind")),
            originPrincipal = json.getString("originPrincipal"),
            byteRange = byteRangeJson?.let { ByteRange(it.getLong("startByte"), it.getLong("endByteExclusive")) },
            content = json.optStringOrNull("content"),
            unknownFields = extractUnknownFields(json, JOURNAL_ENTRY_KNOWN_KEYS)
        )
    }

    // -------------------------------------------------------------------------------------
    // RecoverySnapshot -- schemas/workspace/recovery-snapshot.schema.json
    // -------------------------------------------------------------------------------------

    private val BUFFER_SNAPSHOT_ENTRY_KNOWN_KEYS = setOf(
        "bufferId", "resourceUri", "journalSequence", "dirty", "encoding", "lineEndings", "contentDigest"
    )
    private val RECOVERY_SNAPSHOT_KNOWN_KEYS = setOf(
        "snapshotId", "workspaceId", "takenAtUtc", "reason", "lastJournalSequence", "bufferSnapshots"
    )

    fun encodeBufferSnapshotEntry(entry: BufferSnapshotEntry): JSONObject = JSONObject().apply {
        put("bufferId", entry.bufferId)
        put("resourceUri", encodeResourceUri(entry.resourceUri))
        put("journalSequence", entry.journalSequence)
        put("dirty", entry.dirty)
        put("encoding", entry.encoding)
        put("lineEndings", entry.lineEndings.name)
        put("contentDigest", EnvelopeCodec.encodeIntegrityRef(entry.contentDigest))
    }

    fun decodeBufferSnapshotEntry(json: JSONObject): BufferSnapshotEntry = BufferSnapshotEntry(
        bufferId = json.getString("bufferId"),
        resourceUri = decodeResourceUri(json.getJSONObject("resourceUri")),
        journalSequence = json.getLong("journalSequence"),
        dirty = json.getBoolean("dirty"),
        encoding = json.getString("encoding"),
        lineEndings = LineEndings.valueOf(json.getString("lineEndings")),
        contentDigest = EnvelopeCodec.decodeIntegrityRef(json.getJSONObject("contentDigest"))
    )

    fun encodeRecoverySnapshot(snapshot: RecoverySnapshot): JSONObject {
        val obj = JSONObject()
        obj.put("snapshotId", snapshot.snapshotId)
        obj.put("workspaceId", snapshot.workspaceId)
        obj.put("takenAtUtc", snapshot.takenAtUtc.toString())
        obj.put("reason", snapshot.reason.name)
        obj.put("lastJournalSequence", snapshot.lastJournalSequence)
        obj.put("bufferSnapshots", JSONArray().apply {
            snapshot.bufferSnapshots.forEach { put(encodeBufferSnapshotEntry(it)) }
        })
        mergeUnknownFields(obj, snapshot.unknownFields)
        return obj
    }

    fun decodeRecoverySnapshot(json: JSONObject): RecoverySnapshot {
        val bufferSnapshotsJson = json.getJSONArray("bufferSnapshots")
        val bufferSnapshots = (0 until bufferSnapshotsJson.length()).map {
            decodeBufferSnapshotEntry(bufferSnapshotsJson.getJSONObject(it))
        }
        return RecoverySnapshot(
            snapshotId = json.getString("snapshotId"),
            workspaceId = json.getString("workspaceId"),
            takenAtUtc = Instant.parse(json.getString("takenAtUtc")),
            reason = RecoverySnapshotReason.valueOf(json.getString("reason")),
            lastJournalSequence = json.getLong("lastJournalSequence"),
            bufferSnapshots = bufferSnapshots,
            unknownFields = extractUnknownFields(json, RECOVERY_SNAPSHOT_KNOWN_KEYS)
        )
    }
}
