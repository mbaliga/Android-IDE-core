package dev.fonebrew.domain.editor.buffer

enum class OpenMode { EDITABLE, READ_ONLY, CHUNKED_READ_ONLY }

data class OpenDecision(val mode: OpenMode, val reason: String)

/**
 * The large-file guard. Stated limits (conservative guesses, owner-tunable after device measurement):
 * up to [EDITABLE_MAX] bytes opens editable; up to [READ_ONLY_MAX] it opens read-only as one rope;
 * above that only a window at a time ([ChunkPlan]) is ever held, so no path allocates the whole file.
 */
object OpenPolicy {
    const val EDITABLE_MAX: Long = 4L * 1024 * 1024
    const val READ_ONLY_MAX: Long = 32L * 1024 * 1024

    fun decide(byteSize: Long): OpenDecision = when {
        byteSize < 0 -> OpenDecision(OpenMode.READ_ONLY, "unknown size: opened read-only")
        byteSize <= EDITABLE_MAX -> OpenDecision(OpenMode.EDITABLE, "within the editable limit")
        byteSize <= READ_ONLY_MAX -> OpenDecision(OpenMode.READ_ONLY, "larger than ${EDITABLE_MAX / 1024 / 1024} MiB: read-only")
        else -> OpenDecision(OpenMode.CHUNKED_READ_ONLY, "larger than ${READ_ONLY_MAX / 1024 / 1024} MiB: shown in windows, read-only")
    }
}

/** Byte windows for the chunked mode. Line alignment of each window is the reader's job (I/O side). */
object ChunkPlan {
    const val DEFAULT_WINDOW: Int = 1024 * 1024

    /** Consecutive `[start, endExclusive)` windows covering `[0, total)`. */
    fun windows(total: Long, window: Int = DEFAULT_WINDOW): List<LongRange> {
        require(window > 0)
        if (total <= 0) return emptyList()
        val out = ArrayList<LongRange>()
        var s = 0L
        while (s < total) { val e = minOf(total, s + window); out += s until e; s = e }
        return out
    }
}
