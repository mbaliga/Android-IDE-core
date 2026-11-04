package dev.fonebrew.spike.fb1

import androidx.room.TypeConverter

/**
 * Stand-in for core's `Converters` (core-engine/.../data/Converters.kt).
 *
 * Core's version builds the JSON with `org.json`, which is part of the Android platform but is
 * NOT on a plain-JVM classpath, and does not exist at all in a KMP `commonMain` metadata
 * compilation. An extracted Room-KMP module therefore cannot keep that implementation in common
 * code; this spike uses a dependency-free encoding to prove the converter *mechanism*
 * (`@TypeConverters` on a `@Database`, Map <-> String column) works under Room-KMP/JVM.
 * See README, finding F5.
 */
class Converters {

    @TypeConverter
    fun fromMetadata(map: Map<String, String>?): String? {
        if (map == null) return null
        return map.entries.joinToString("\u001e") { (k, v) -> "${esc(k)}\u001f${esc(v)}" }
    }

    @TypeConverter
    fun toMetadata(encoded: String?): Map<String, String> {
        if (encoded.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in encoded.split('\u001e')) {
            val (k, v) = pair.split('\u001f').let { it[0] to it[1] }
            out[unesc(k)] = unesc(v)
        }
        return out
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\u001e", "\\r").replace("\u001f", "\\u")
    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> sb.append('\\')
                    'r' -> sb.append('\u001e')
                    'u' -> sb.append('\u001f')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
