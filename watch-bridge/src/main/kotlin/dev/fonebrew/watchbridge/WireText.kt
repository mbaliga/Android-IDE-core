package dev.fonebrew.watchbridge

/**
 * Validates a string field of a message. [nonBlank] fields reject empty/whitespace-only values;
 * every field rejects an unpaired UTF-16 surrogate, because such a string has no UTF-8 encoding and
 * would be silently replaced (`?`/U+FFFD) on the wire — a quiet corruption of what the model said or
 * the user typed. Throws [IllegalArgumentException]; the decoder maps that to a malformed result.
 */
internal fun requireWireText(field: String, value: String, nonBlank: Boolean) {
    if (nonBlank) require(value.isNotBlank()) { "$field must not be blank" }
    require(isWellFormedUtf16(value)) { "$field contains an unpaired surrogate (not encodable as UTF-8)" }
}

internal fun isWellFormedUtf16(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c.isHighSurrogate() -> {
                if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return false
                i += 2
            }
            c.isLowSurrogate() -> return false
            else -> i++
        }
    }
    return true
}
