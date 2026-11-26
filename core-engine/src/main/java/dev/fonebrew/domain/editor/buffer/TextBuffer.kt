package dev.fonebrew.domain.editor.buffer

/** Outcome of opening bytes as an editor document. */
sealed interface OpenResult {
    data class Opened(val buffer: TextBuffer, val decision: OpenDecision) : OpenResult
    data class Refused(val reason: RefusalReason, val detail: String) : OpenResult
}

/**
 * The mutable holder around an immutable [Rope]: current text, a content [revision] that bumps on
 * every change, and the file facts needed to save it back unchanged ([encoding], and the line
 * ending census recomputed from the live text). [snapshot] is free.
 *
 * Read-only buffers (large files) refuse mutation by throwing; the editor checks [readOnly] first
 * and reports a typed rejection instead.
 */
class TextBuffer(
    initial: String = "",
    val encoding: TextEncoding = TextEncoding.UTF8,
    val readOnly: Boolean = false,
) {
    private var rope: Rope = Rope.of(initial)

    /** Bumps on every mutation (and on undo/redo, which are mutations). */
    var revision: Long = 0
        private set

    val length: Int get() = rope.length
    val lineCount: Int get() = rope.lineCount
    fun snapshot(): Rope = rope
    override fun toString(): String = rope.toString()

    fun substring(start: Int, end: Int): String = rope.substring(start, end)
    operator fun get(i: Int): Char = rope[i]

    fun replace(start: Int, end: Int, text: String) {
        check(!readOnly) { "buffer is read-only" }
        rope = rope.replace(start, end, text)
        revision++
    }

    /** Swap in a rope produced by applying a transaction (the editor's single mutation path). */
    internal fun replaceRope(next: Rope) {
        check(!readOnly) { "buffer is read-only" }
        rope = next
        revision++
    }

    /** Replace the whole content (used to apply an externally reloaded revision). */
    fun setAll(text: String) = replace(0, length, text)

    /** Census of the live text; recomputed on demand (O(n)), so call it per save/open, not per key. */
    fun lineEndings(): LineEndingProfile = LineEndings.profile(rope.asCharSequence())

    fun positionOf(offset: Int): Position = TextUnits.positionOf(rope, offset)
    fun offsetOf(line: Int, column: Int): Int = TextUnits.offsetOf(rope, line, column)

    /**
     * The bytes to write: the file's own encoding and BOM, raw characters untouched, so a no-op round
     * trip is byte-exact. Unrepresentable text (lone surrogates; non-Latin-1 in a Latin-1 file) is an
     * explicit refusal, never a substitution.
     */
    fun toBytes(): EncodeResult = TextEncodings.encode(rope.toString(), encoding)

    companion object {
        /** Decode [bytes] and apply the large-file guard to decide whether it may be edited. */
        fun open(bytes: ByteArray): OpenResult = when (val d = TextEncodings.decode(bytes)) {
            is DecodeResult.Refused -> OpenResult.Refused(d.reason, d.detail)
            is DecodeResult.Ok -> {
                val decision = OpenPolicy.decide(bytes.size.toLong())
                OpenResult.Opened(TextBuffer(d.text, d.encoding, decision.mode != OpenMode.EDITABLE), decision)
            }
        }
    }
}
