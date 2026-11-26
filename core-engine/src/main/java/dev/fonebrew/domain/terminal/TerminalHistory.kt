package dev.fonebrew.domain.terminal

/** What a saved snapshot knows about its session. */
data class SnapshotMeta(
    val title: String,
    val cwd: String?,
    val savedAtMillis: Long,
    /** Why it was written: "ended", "background", "closed". */
    val reason: String,
)

class Snapshot(val meta: SnapshotMeta, val text: String, val truncated: Boolean)

/**
 * The on-disk format for "previous session history": a small text header and the plain-text
 * transcript (no attributes, soft-wrapped lines already joined). It is history only - a
 * snapshot never revives a shell, and the UI must label it "previous session history - not
 * live". Pure; bounded: [encode] keeps the *newest* lines so the body is at most [maxBodyBytes]
 * of UTF-8, and [decode] refuses anything larger than the cap plus header slack.
 */
object TerminalHistory {
    const val MAGIC = "FONEBREW-TERMINAL-HISTORY 1"
    const val DEFAULT_MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val HEADER_SLACK = 16 * 1024
    private const val SEPARATOR = "---"

    fun encode(meta: SnapshotMeta, text: String, maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES): ByteArray {
        val (body, truncated) = clip(text, maxBodyBytes)
        val header = buildString {
            append(MAGIC).append('\n')
            append("title: ").append(oneLine(meta.title)).append('\n')
            append("cwd: ").append(oneLine(meta.cwd.orEmpty())).append('\n')
            append("saved: ").append(meta.savedAtMillis).append('\n')
            append("reason: ").append(oneLine(meta.reason)).append('\n')
            append("truncated: ").append(truncated).append('\n')
            append(SEPARATOR).append('\n')
        }
        return (header + body).toByteArray(Charsets.UTF_8)
    }

    /** Null if the bytes are not a snapshot we wrote (wrong magic, malformed header, too large). */
    fun decode(bytes: ByteArray, maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES): Snapshot? {
        if (bytes.size > maxBodyBytes + HEADER_SLACK) return null
        val s = String(bytes, Charsets.UTF_8)
        val sep = s.indexOf("\n$SEPARATOR\n")
        if (sep < 0) return null
        val lines = s.substring(0, sep).split('\n')
        if (lines.firstOrNull() != MAGIC) return null
        fun field(name: String) = lines.firstOrNull { it.startsWith("$name: ") }?.substring(name.length + 2)
        val saved = field("saved")?.toLongOrNull() ?: return null
        val meta = SnapshotMeta(
            title = field("title") ?: "",
            cwd = field("cwd")?.ifEmpty { null },
            savedAtMillis = saved,
            reason = field("reason") ?: "",
        )
        return Snapshot(meta, s.substring(sep + SEPARATOR.length + 2), field("truncated") == "true")
    }

    /** Keeps the newest lines whose UTF-8 size fits in [maxBytes]; reports whether it dropped any. */
    internal fun clip(text: String, maxBytes: Int): Pair<String, Boolean> {
        if (maxBytes <= 0) return "" to text.isNotEmpty()
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text to false
        // Walk lines from the end, accumulating until the budget is spent.
        val lines = text.split('\n')
        var used = 0
        var from = lines.size
        while (from > 0) {
            val cost = lines[from - 1].toByteArray(Charsets.UTF_8).size + 1
            if (used + cost > maxBytes) break
            used += cost
            from--
        }
        val kept = lines.subList(from, lines.size).joinToString("\n")
        return kept.trimEnd('\n').let { if (it.isEmpty()) it else it + "\n" } to true
    }

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').take(512)
}
