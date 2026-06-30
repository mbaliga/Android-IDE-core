package dev.aarso.domain.net

/**
 * Drives an [OperationQueue] against real handlers (P5 worker). It is the loop a data-layer
 * service runs: load the queue → take the due ops → run each handler → record success/failure
 * (with backoff) → persist. Kept pure over a load/save seam + a clock so the whole drain
 * lifecycle — including retry, give-up, and missing-handler — is JVM-tested without a network.
 *
 * A handler returns [Result]; a failure is retried (backoff) unless [isRetryable] says otherwise
 * (e.g. a 401 is permanent — park it for the user). Nothing is ever silently dropped (THE LAW).
 */
fun interface OpHandler {
    suspend fun handle(payload: String): Result<Unit>
}

class OperationWorker(
    private val load: () -> OperationQueue,
    private val save: (OperationQueue) -> Unit,
    private val handlers: Map<String, OpHandler>,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val isRetryable: (Throwable) -> Boolean = { true },
    private val newId: () -> String = { "op_" + java.util.UUID.randomUUID() },
) {
    /** Enqueue an operation to run when due. Returns its id. */
    fun enqueue(kind: String, payload: String): String {
        val id = newId()
        save(load().enqueue(id, kind, payload, now()))
        return id
    }

    /** Process every currently-due op once; returns how many were attempted. */
    suspend fun drainOnce(): Int {
        var q = load()
        var attempted = 0
        for (op in q.due(now())) {
            q = q.markInFlight(op.id); save(q)
            val handler = handlers[op.kind]
            val result =
                if (handler == null) Result.failure(IllegalStateException("no handler for '${op.kind}'"))
                else runCatching { handler.handle(op.payload).getOrThrow() }
            q = result.fold(
                onSuccess = { q.markSucceeded(op.id) },
                onFailure = { e ->
                    // A missing handler is permanent; otherwise defer to isRetryable.
                    val retry = handler != null && isRetryable(e)
                    q.markFailed(op.id, e.message ?: "failed", now(), retryable = retry)
                },
            )
            save(q)
            attempted++
        }
        return attempted
    }

    /** Re-arm a parked (FAILED) op for an immediate retry. */
    fun retry(id: String) = save(load().retryNow(id, now()))

    fun discard(id: String) = save(load().discard(id))
}
