// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.aarso.domain.contracts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small helpers shared by every codec in this package. `org.json` is the house JSON library
 * (see `docs/WP0_SURVEY.md` §3 / the WP-2 codebase-map: `GitContentsApi.kt`, `GitTreeApi.kt`,
 * `TreeArchive.kt`, `RemoteCatalog.kt` all use it; no kotlinx.serialization/Gson/Moshi exists
 * anywhere in this module's dependency graph) — this file is deliberately not a wrapper around
 * a different JSON library.
 */

/** Null-safe `optString` — `org.json`'s own overload cannot express "absent or JSON null → null". */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

/** Null-safe `optLong` returning `Long?` rather than a sentinel primitive default. */
internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

/** Null-safe nested-object accessor. */
internal fun JSONObject.optJSONObjectOrNull(key: String): JSONObject? =
    if (has(key) && !isNull(key)) getJSONObject(key) else null

/**
 * Converts an `org.json` value (as returned by [JSONObject.get] / [JSONArray.get]) into a plain
 * Kotlin value safe to store in a [ContractEnvelope.unknownFields]-shaped map: `JSONObject.NULL`
 * becomes `null`, nested `JSONObject`/`JSONArray` are converted recursively into `Map`/`List`,
 * everything else (String/Number/Boolean) passes through unchanged. This is what makes
 * unknown-field round-trip preservation (FB-RAT-COM-003) possible for arbitrarily nested unknown
 * data, not just flat scalar fields.
 */
internal fun jsonValueToKotlin(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> {
        val map = LinkedHashMap<String, Any?>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = jsonValueToKotlin(value.get(k))
        }
        map
    }
    is JSONArray -> (0 until value.length()).map { jsonValueToKotlin(value.get(it)) }
    else -> value
}

/** The inverse of [jsonValueToKotlin] — used when re-encoding a preserved unknown field. */
internal fun kotlinValueToJson(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> {
        val obj = JSONObject()
        for ((k, v) in value) obj.put(k.toString(), kotlinValueToJson(v))
        obj
    }
    is List<*> -> {
        val arr = JSONArray()
        for (v in value) arr.put(kotlinValueToJson(v))
        arr
    }
    else -> value
}

/**
 * Extracts every top-level key of [json] NOT in [knownKeys] into an unknown-fields map, in the
 * shape [ContractEnvelope.unknownFields] / every other contract's `unknownFields` field expects.
 * Called once per decode, right after every known field has been read.
 */
internal fun extractUnknownFields(json: JSONObject, knownKeys: Set<String>): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        if (k !in knownKeys) result[k] = jsonValueToKotlin(json.get(k))
    }
    return result
}

/**
 * Merges a previously-extracted unknown-fields map back into [target] during encode, WITHOUT
 * overwriting any key the encoder already wrote as a known field (a known field always wins —
 * `unknownFields` exists for forward-compatibility with a newer schema MINOR, never as a way to
 * override this reader's own understanding of a field it does recognize).
 */
internal fun mergeUnknownFields(target: JSONObject, unknownFields: Map<String, Any?>) {
    for ((k, v) in unknownFields) {
        if (!target.has(k)) target.put(k, kotlinValueToJson(v))
    }
}
