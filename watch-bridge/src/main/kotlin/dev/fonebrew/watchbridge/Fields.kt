package dev.fonebrew.watchbridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Type-exact field access over a parsed `org.json` object. Exists because the platform accessors
 * disagree: Android's `getString` coerces a number to a string and `getInt` truncates `1.9` to `1`,
 * the reference library throws — a codec built on them would half-accept on one platform what it
 * rejects on the other. Every accessor here checks the actual JSON type and throws
 * [WireException] naming the field's full path (`AskChunk.usage.estimated`).
 *
 * "Absent" and JSON `null` are the same thing (the `EnvelopeCodec` `optStringOrNull` idiom).
 */
internal class Fields(private val obj: JSONObject, private val path: String) {

    private fun raw(key: String): Any? {
        val v = obj.opt(key)
        return if (v == null || v === JSONObject.NULL) null else v
    }

    fun has(key: String): Boolean = raw(key) != null

    fun fail(message: String): Nothing = throw WireException("$path: $message")

    private fun missing(key: String): Nothing = fail("missing required field \"$key\"")
    private fun wrongType(key: String, expected: String): Nothing = fail("field \"$key\" must be $expected")

    fun string(key: String): String = optString(key) ?: missing(key)
    fun boolean(key: String): Boolean = optBoolean(key) ?: missing(key)
    fun int(key: String): Int = optInt(key) ?: missing(key)
    fun long(key: String): Long = optLong(key) ?: missing(key)
    fun obj(key: String): Fields = optObj(key) ?: missing(key)
    fun array(key: String): JSONArray = optArray(key) ?: missing(key)

    fun optString(key: String): String? {
        val v = raw(key) ?: return null
        return v as? String ?: wrongType(key, "a string")
    }

    fun optBoolean(key: String): Boolean? {
        val v = raw(key) ?: return null
        return v as? Boolean ?: wrongType(key, "a boolean")
    }

    /** A JSON integer that fits in 32 bits. A fractional or out-of-range number is rejected, never truncated. */
    fun optInt(key: String): Int? {
        val v = raw(key) ?: return null
        return when {
            v is Int -> v
            v is Long && v >= Int.MIN_VALUE && v <= Int.MAX_VALUE -> v.toInt()
            else -> wrongType(key, "a 32-bit integer")
        }
    }

    /** A JSON integer that fits in 64 bits. A fractional number is rejected, never truncated. */
    fun optLong(key: String): Long? {
        val v = raw(key) ?: return null
        return when (v) {
            is Int -> v.toLong()
            is Long -> v
            else -> wrongType(key, "an integer")
        }
    }

    fun optObj(key: String): Fields? {
        val v = raw(key) ?: return null
        return Fields(v as? JSONObject ?: wrongType(key, "an object"), "$path.$key")
    }

    fun optArray(key: String): JSONArray? {
        val v = raw(key) ?: return null
        return v as? JSONArray ?: wrongType(key, "an array")
    }

    /** Element [index] of [array] as an object; anything else is rejected. */
    fun element(array: JSONArray, key: String, index: Int): Fields {
        val v = array.opt(index)
        return Fields(v as? JSONObject ?: fail("\"$key\"[$index] must be an object"), "$path.$key[$index]")
    }
}
