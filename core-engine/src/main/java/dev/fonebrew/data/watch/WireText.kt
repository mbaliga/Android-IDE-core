package dev.fonebrew.data.watch

/**
 * Text hygiene for what crosses to the wrist. The `:watch-bridge` contract refuses a string with an
 * unpaired UTF-16 surrogate (it has no UTF-8 encoding, so it would be silently replaced on the wire),
 * and that refusal is an `IllegalArgumentException` at message construction. User task titles, loop
 * names and model output are all free text the phone does not control, so everything is passed
 * through here BEFORE a contract message is built: a stray surrogate becomes U+FFFD, visibly, instead
 * of crashing the relay or being corrupted in flight.
 */
internal const val REPLACEMENT_CHAR: Char = '�'

/** [this] with every unpaired surrogate replaced by U+FFFD. Well-formed text is returned unchanged (same instance). */
internal fun String.toWireSafe(): String {
    var out: StringBuilder? = null
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            out?.append(c)?.append(this[i + 1])
            i += 2
        } else if (c.isSurrogate()) {
            if (out == null) out = StringBuilder(length).append(this, 0, i)
            out.append(REPLACEMENT_CHAR)
            i++
        } else {
            out?.append(c)
            i++
        }
    }
    return out?.toString() ?: this
}

/**
 * [this] made wire-safe and at most [maxChars] UTF-16 units, cut with a trailing "…" when shortened
 * (and never between the halves of a surrogate pair). The ellipsis makes the cut visible: a truncated
 * title is never passed off as the whole one.
 */
internal fun String.toWireTruncated(maxChars: Int): String {
    require(maxChars >= 2) { "maxChars must leave room for the ellipsis" }
    val safe = toWireSafe()
    if (safe.length <= maxChars) return safe
    var cut = maxChars - 1
    if (safe[cut - 1].isHighSurrogate()) cut-- // do not strand the first half of a pair
    return safe.substring(0, cut) + "…"
}
