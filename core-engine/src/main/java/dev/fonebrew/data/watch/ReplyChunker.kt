package dev.fonebrew.data.watch

import dev.fonebrew.watchbridge.AskChunk
import dev.fonebrew.watchbridge.Usage

/**
 * Turns one turn's token stream into the contract's chunk sequence: zero or more non-terminal
 * [AskChunk]s then exactly one terminal chunk carrying the turn's [Usage]. Pure and stateful per
 * reply (one instance per question).
 *
 * What it guarantees, each pinned by `ReplyChunkerTest`:
 *
 *  - **Token text is passed through verbatim** (no trim, no case change): the wrist shows what the
 *    engine said. Empty tokens produce no chunk, so the wire is not padded with no-ops.
 *  - **A chunk boundary never splits a surrogate pair.** Byte-level tokenizers routinely emit an
 *    emoji as two tokens; a lone high surrogate at the end of a token is held back and joined to the
 *    next token's text (the contract requires this of the relay). A surrogate that can never be
 *    paired (stream ends right after it, or a stray low surrogate) becomes U+FFFD in the visible text
 *    rather than crashing the relay or being corrupted on the wire.
 *  - **Oversized tokens are split** at [maxChunkChars] (also never mid-pair) so no frame approaches
 *    the framing limit even if a provider sends a whole paragraph as one delta.
 *  - **Every chunk carries `engineId` and `watchedObject`**, so the wrist never hides who answered
 *    and the cloud glyph is present on the first chunk, not only the last.
 *  - **Usage appears only on the terminal chunk** and is whatever the caller passes; this class never
 *    computes or defaults a count (the engine bridge decides, honestly, what is real and what is estimated).
 */
class ReplyChunker(
    private val requestId: String,
    private val engineId: String,
    private val watchedObject: Boolean,
    private val maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS,
) {
    init {
        require(maxChunkChars >= 2) { "maxChunkChars must be at least 2 so a surrogate pair always fits" }
    }

    /** A held-back lone high surrogate (0 or 1 char), awaiting its partner in the next token. */
    private var held: String = ""
    private var finished = false

    /** The non-terminal chunks for one more token of the answer (possibly none). */
    fun onToken(text: String): List<AskChunk> {
        check(!finished) { "token after the terminal chunk" }
        if (text.isEmpty()) return emptyList()
        val joined = held + text
        val holdBack = joined.last().isHighSurrogate()
        held = if (holdBack) joined.last().toString() else ""
        val ready = (if (holdBack) joined.dropLast(1) else joined).toWireSafe()
        return split(ready).map { AskChunk.token(requestId, it, engineId, watchedObject) }
    }

    /** The terminal chunk, carrying [usage]. A still-held lone high surrogate surfaces as U+FFFD. */
    fun finish(usage: Usage): AskChunk {
        check(!finished) { "terminal chunk already produced" }
        finished = true
        val tail = if (held.isEmpty()) "" else REPLACEMENT_CHAR.toString()
        held = ""
        return AskChunk.terminal(requestId, tail, engineId, watchedObject, usage)
    }

    private fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxChunkChars) return listOf(text)
        val parts = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxChunkChars, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end-- // keep the pair whole
            parts += text.substring(start, end)
            start = end
        }
        return parts
    }

    companion object {
        /** Far below the framing limit even at worst-case JSON escaping (6 bytes per char) while keeping frames small. */
        const val DEFAULT_MAX_CHUNK_CHARS: Int = 2048
    }
}
