package dev.aarso.data

import androidx.room.TypeConverter
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
