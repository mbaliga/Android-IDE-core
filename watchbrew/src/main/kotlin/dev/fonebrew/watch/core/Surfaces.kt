package dev.fonebrew.watch.core

/** What the complication shows. SHORT_TEXT: [text] is at most a handful of characters. */
data class ComplicationContent(val text: String, val title: String, val contentDescription: String)

/**
 * The pending-task-count complication, derived honestly. The count is the phone's *true* not-done
 * number (`TaskSnapshot.pendingCount`), which may exceed the rows the phone had room to send.
 *
 *  - **Unknown is `--`, never `0`**: no snapshot has ever arrived, so the watch does not know.
 *  - **A real zero is `0`.**
 *  - **Old is marked old**: a complication cannot show an age line, so a count older than
 *    [STALE_AFTER_MS] gets a trailing `?` and a spoken "may be out of date". The watch never presents
 *    a remembered number as live.
 *  - Counts above [CAP] read `99+` (true: more than ninety-nine).
 */
object ComplicationText {
    const val STALE_AFTER_MS: Long = 60 * 60_000L
    const val CAP: Int = 99
    private const val TITLE = "Tasks"

    fun derive(pendingCount: Int?, receivedAtMs: Long?, nowMs: Long): ComplicationContent {
        if (pendingCount == null) {
            return ComplicationContent("--", TITLE, "Pending tasks: not received from the phone yet")
        }
        val shown = if (pendingCount > CAP) "$CAP+" else pendingCount.toString()
        val noun = when (pendingCount) {
            0 -> "No pending tasks"
            1 -> "1 pending task"
            else -> "$pendingCount pending tasks"
        }
        val stale = receivedAtMs == null || nowMs - receivedAtMs > STALE_AFTER_MS
        return if (stale) {
            val asOf = receivedAtMs?.let { "as of ${RelativeTime.ago(it, nowMs)}" } ?: "from an unknown time"
            ComplicationContent("$shown?", TITLE, "$noun, $asOf. May be out of date.")
        } else {
            ComplicationContent(shown, TITLE, noun)
        }
    }
}

/** What the Tile shows: the Ask shortcut plus a snippet of the last finished reply. */
data class TileContent(
    val snippet: String?,
    val provenance: Provenance?,
    val age: String?,
    /** Non-null when the phone link is not usable right now (words, not hue). */
    val linkNote: String?,
    val contentDescription: String,
)

object TilePresenter {
    const val SNIPPET_CHARS: Int = 72
    const val NO_REPLY: String = "No reply yet"

    fun present(lastReply: ReplyRecord?, connection: Connection, nowMs: Long): TileContent {
        val link = ConnectionPresenter.present(connection)
        val note = if (link.blocking) link.headline else null
        if (lastReply == null) {
            return TileContent(null, null, null, note, listOfNotNull("Ask Fonebrew", NO_REPLY, note).joinToString(". "))
        }
        val snippet = snippet(lastReply.text)
        val provenance = ProvenancePresenter.of(lastReply.engineId, lastReply.watchedObject)
        val age = RelativeTime.ago(lastReply.completedAtMs, nowMs)
        return TileContent(
            snippet = snippet,
            provenance = provenance,
            age = age,
            linkNote = note,
            contentDescription = listOfNotNull("Ask Fonebrew", "Last reply: $snippet", provenance.spoken, age, note).joinToString(". "),
        )
    }

    /** One line, whitespace collapsed, cut at a word boundary with a visible "…" when shortened. */
    fun snippet(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return "(empty reply)"
        if (flat.length <= SNIPPET_CHARS) return flat
        var cut = SNIPPET_CHARS - 1
        val lastSpace = flat.lastIndexOf(' ', cut)
        if (lastSpace >= SNIPPET_CHARS / 2) cut = lastSpace
        if (cut > 0 && flat[cut - 1].isHighSurrogate()) cut-- // never split a surrogate pair
        return flat.substring(0, cut).trimEnd() + "…"
    }
}
