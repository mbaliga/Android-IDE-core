package dev.aarso.data

import androidx.room.TypeConverter
import dev.aarso.domain.curation.BookmarkKind
import dev.aarso.domain.curation.Fidelity
import dev.aarso.domain.curation.GhostReason
import dev.aarso.domain.tasks.TaskSource
import dev.aarso.domain.tasks.TaskState
import dev.aarso.domain.thread.DelegationKind
import dev.aarso.domain.thread.DelegationOutcome
import dev.aarso.domain.thread.ThreadMarkerKind
import dev.aarso.domain.thread.ThreadMarkerSource
import dev.aarso.domain.watch.WatchKind
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Room type converters. Kept dependency-light on purpose (org.json ships with
 * Android; no extra serialization library) to honour the local-first, minimal-
 * surface stance.
 */
class Converters {

    /** metadata map <-> JSON object string. */
    @TypeConverter
    fun fromMetadata(map: Map<String, String>?): String? {
        if (map.isNullOrEmpty()) return null
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        return obj.toString()
    }

    @TypeConverter
    fun toMetadata(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val obj = JSONObject(json)
        val out = LinkedHashMap<String, String>()
        for (key in obj.keys()) out[key] = obj.getString(key)
        return out
    }

    /** String list <-> JSON array string (Task's [dependsOn]/[tags]). */
    @TypeConverter
    fun fromStringList(values: List<String>?): String? {
        if (values.isNullOrEmpty()) return null
        val arr = JSONArray()
        for (v in values) arr.put(v)
        return arr.toString()
    }

    @TypeConverter
    fun toStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = JSONArray(json)
        return List(arr.length()) { arr.getString(it) }
    }

    @TypeConverter
    fun fromTaskState(state: TaskState): String = state.name

    @TypeConverter
    fun toTaskState(name: String): TaskState = TaskState.valueOf(name)

    @TypeConverter
    fun fromTaskSource(source: TaskSource): String = source.name

    @TypeConverter
    fun toTaskSource(name: String): TaskSource = TaskSource.valueOf(name)

    @TypeConverter
    fun fromWatchKind(kind: WatchKind): String = kind.name

    @TypeConverter
    fun toWatchKind(name: String): WatchKind = WatchKind.valueOf(name)

    @TypeConverter
    fun fromBookmarkKind(kind: BookmarkKind): String = kind.name

    @TypeConverter
    fun toBookmarkKind(name: String): BookmarkKind = BookmarkKind.valueOf(name)

    @TypeConverter
    fun fromFidelity(fidelity: Fidelity): String = fidelity.name

    @TypeConverter
    fun toFidelity(name: String): Fidelity = Fidelity.valueOf(name)

    @TypeConverter
    fun fromGhostReason(reason: GhostReason): String = reason.name

    @TypeConverter
    fun toGhostReason(name: String): GhostReason = GhostReason.valueOf(name)

    @TypeConverter
    fun fromThreadMarkerKind(kind: ThreadMarkerKind): String = kind.name

    @TypeConverter
    fun toThreadMarkerKind(name: String): ThreadMarkerKind = ThreadMarkerKind.valueOf(name)

    @TypeConverter
    fun fromThreadMarkerSource(source: ThreadMarkerSource): String = source.name

    @TypeConverter
    fun toThreadMarkerSource(name: String): ThreadMarkerSource = ThreadMarkerSource.valueOf(name)

    @TypeConverter
    fun fromDelegationKind(kind: DelegationKind): String = kind.name

    @TypeConverter
    fun toDelegationKind(name: String): DelegationKind = DelegationKind.valueOf(name)

    @TypeConverter
    fun fromDelegationOutcome(outcome: DelegationOutcome): String = outcome.name

    @TypeConverter
    fun toDelegationOutcome(name: String): DelegationOutcome = DelegationOutcome.valueOf(name)

    companion object {
        /** float32 array -> little-endian BLOB, for embedding vectors. */
        fun floatsToBytes(values: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (v in values) buf.putFloat(v)
            return buf.array()
        }

        fun bytesToFloats(bytes: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(bytes.size / Float.SIZE_BYTES)
            for (i in out.indices) out[i] = buf.float
            return out
        }
    }
}
