package dev.aarso.data

/**
 * Pure decision logic for resuming interrupted downloads from a `.part` file via
 * HTTP Range requests. The IO lives in [ModelDownloader]; keeping the decisions
 * here makes the resume behaviour JVM-testable.
 */
object DownloadResume {

    /** What to ask the server for, given how much of the file we already have. */
    data class Plan(val rangeHeader: String?) {
        val resuming: Boolean get() = rangeHeader != null
    }

    fun plan(partBytes: Long): Plan =
        if (partBytes > 0) Plan("bytes=$partBytes-") else Plan(null)

    /**
     * How to treat the server's answer.
     *
     * @property startAt byte offset to write from: `partBytes` (append) when the
     *   server honoured the range, 0 (truncate, full restart) when it did not.
     * @property alreadyComplete the `.part` already holds the whole file
     *   (416 with a matching total) — finalize without downloading.
     * @property error a non-resumable failure; the `.part` is kept for a retry.
     */
    data class Outcome(
        val startAt: Long,
        val alreadyComplete: Boolean = false,
        val error: String? = null,
    )

    fun interpret(httpCode: Int, contentRange: String?, partBytes: Long): Outcome = when {
        httpCode == 206 -> {
            val start = parseRangeStart(contentRange)
            if (start != null && start == partBytes) {
                Outcome(startAt = partBytes)
            } else {
                // The server answered a different range than we hold — restart clean.
                Outcome(startAt = 0)
            }
        }
        httpCode == 200 -> Outcome(startAt = 0) // range ignored; full body follows
        httpCode == 416 -> {
            // Requested range not satisfiable: complete if our .part is exactly the
            // advertised total ("bytes */<total>"), otherwise restart clean.
            val total = parseUnsatisfiedTotal(contentRange)
            if (total != null && total == partBytes) {
                Outcome(startAt = partBytes, alreadyComplete = true)
            } else {
                Outcome(startAt = 0)
            }
        }
        else -> Outcome(startAt = 0, error = "HTTP $httpCode")
    }

    /** "bytes 1234-9999/10000" → 1234 */
    private fun parseRangeStart(contentRange: String?): Long? =
        contentRange
            ?.removePrefix("bytes ")
            ?.substringBefore('-')
            ?.trim()
            ?.toLongOrNull()

    /** Total from the unsatisfied-range form ("bytes" star slash total) → 10000. */
    private fun parseUnsatisfiedTotal(contentRange: String?): Long? =
        contentRange
            ?.removePrefix("bytes ")
            ?.takeIf { it.startsWith("*/") }
            ?.substringAfter('/')
            ?.trim()
            ?.toLongOrNull()
}
