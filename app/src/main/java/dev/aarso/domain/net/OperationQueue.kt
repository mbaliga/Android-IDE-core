package dev.aarso.domain.net

/**
 * A durable, retrying operation queue (docs/build-plan.md, P5) so every network journey — a
 * board move, a scaffold publish, a Git backup, a loop run sync — survives the subway: the
 * action is enqueued, shown optimistically, and drained when connectivity returns, with bounded
 * exponential backoff. This is the pure state machine; a data-layer worker persists the queue
 * and runs due ops against the network (owner-verified). Surfacing queued/failed state
 * *materially* (not "Syncing…") is the design-gated UI. Pure Kotlin; JVM-tested.
 */

enum class OpStatus { PENDING, IN_FLIGHT, SUCCEEDED, FAILED }

/**
 * One queued operation. [kind] + [payload] are opaque to the queue — the worker interprets them
 * (e.g. kind="board.move", payload=JSON). [nextAttemptAt] gates when a PENDING op is due.
 */
data class QueuedOp(
    val id: String,
    val kind: String,
    val payload: String,
    val status: OpStatus = OpStatus.PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
)

/** Bounded exponential backoff: base · 2^(attempt-1), capped at [maxMs]. Deterministic (testable). */
object Backoff {
    fun delayMs(attempt: Int, baseMs: Long, maxMs: Long): Long {
        if (attempt <= 1) return baseMs.coerceAtMost(maxMs)
        // Guard against overflow on the shift for large attempt counts.
        val shifted = baseMs.toDouble() * Math.pow(2.0, (attempt - 1).toDouble())
        return shifted.coerceAtMost(maxMs.toDouble()).toLong()
    }
}

/**
 * Immutable queue: every transform returns a new [OperationQueue] the data layer persists. The
 * worker loop is: [due] → for each, [markInFlight] → run → [markSucceeded] or [markFailed].
 */
class OperationQueue(
    val ops: List<QueuedOp> = emptyList(),
    val maxAttempts: Int = 5,
    val baseMs: Long = 2_000,
    val maxMs: Long = 5 * 60_000,
) {
    private fun copyWith(ops: List<QueuedOp>) = OperationQueue(ops, maxAttempts, baseMs, maxMs)

    fun enqueue(id: String, kind: String, payload: String, now: Long): OperationQueue {
        require(ops.none { it.id == id }) { "duplicate op id: $id" }
        return copyWith(ops + QueuedOp(id, kind, payload, OpStatus.PENDING, attempts = 0, nextAttemptAt = now))
    }

    /** PENDING ops whose backoff has elapsed, oldest-scheduled first — the worker's work list. */
    fun due(now: Long): List<QueuedOp> =
        ops.filter { it.status == OpStatus.PENDING && it.nextAttemptAt <= now }.sortedBy { it.nextAttemptAt }

    /** Ops not yet successfully done (for optimistic UI: PENDING + IN_FLIGHT + FAILED). */
    fun pending(): List<QueuedOp> = ops.filter { it.status != OpStatus.SUCCEEDED }

    fun markInFlight(id: String): OperationQueue =
        update(id) { it.copy(status = OpStatus.IN_FLIGHT, attempts = it.attempts + 1) }

    /** Success: drop the op from the queue (nothing left to retry). */
    fun markSucceeded(id: String): OperationQueue = copyWith(ops.filterNot { it.id == id })

    /**
     * Failure. A [retryable] error reschedules with backoff until [maxAttempts] is spent; then
     * (or for a non-retryable error) the op is parked as [OpStatus.FAILED] for the user to see
     * and retry or discard manually — it is never silently dropped.
     */
    fun markFailed(id: String, error: String, now: Long, retryable: Boolean = true): OperationQueue =
        update(id) { op ->
            val giveUp = !retryable || op.attempts >= maxAttempts
            if (giveUp) {
                op.copy(status = OpStatus.FAILED, lastError = error)
            } else {
                op.copy(
                    status = OpStatus.PENDING,
                    nextAttemptAt = now + Backoff.delayMs(op.attempts, baseMs, maxMs),
                    lastError = error,
                )
            }
        }

    /** Manually re-arm a FAILED op for another try now. */
    fun retryNow(id: String, now: Long): OperationQueue =
        update(id) { it.copy(status = OpStatus.PENDING, nextAttemptAt = now, lastError = null) }

    /** Drop an op the user chooses to abandon. */
    fun discard(id: String): OperationQueue = copyWith(ops.filterNot { it.id == id })

    val isEmpty: Boolean get() = ops.isEmpty()
    val hasFailures: Boolean get() = ops.any { it.status == OpStatus.FAILED }

    private fun update(id: String, f: (QueuedOp) -> QueuedOp): OperationQueue =
        copyWith(ops.map { if (it.id == id) f(it) else it })
}
