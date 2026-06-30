package dev.aarso.domain.net

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationWorkerTest {

    private class Holder(var q: OperationQueue = OperationQueue(baseMs = 10, maxMs = 100, maxAttempts = 3))

    private fun worker(
        h: Holder,
        handlers: Map<String, OpHandler>,
        clock: () -> Long,
        retryable: (Throwable) -> Boolean = { true },
    ) = OperationWorker(
        load = { h.q }, save = { h.q = it }, handlers = handlers, now = clock, isRetryable = retryable,
        newId = { "op1" },
    )

    @Test fun `a successful op is run once and removed`() = runTest {
        val h = Holder()
        var ran = 0
        val w = worker(h, mapOf("k" to OpHandler { ran++; Result.success(Unit) }), { 0 })
        w.enqueue("k", "payload")
        assertEquals(1, w.drainOnce())
        assertEquals(1, ran)
        assertTrue(h.q.isEmpty)
    }

    @Test fun `the handler receives the payload`() = runTest {
        val h = Holder()
        var seen: String? = null
        val w = worker(h, mapOf("k" to OpHandler { seen = it; Result.success(Unit) }), { 0 })
        w.enqueue("k", "the-payload")
        w.drainOnce()
        assertEquals("the-payload", seen)
    }

    @Test fun `a retryable failure backs off and is not due immediately`() = runTest {
        val h = Holder()
        var t = 0L
        val w = worker(h, mapOf("k" to OpHandler { Result.failure(RuntimeException("offline")) }), { t })
        w.enqueue("k", "p")
        assertEquals(1, w.drainOnce())          // attempt 1 fails → backoff
        assertEquals(OpStatus.PENDING, h.q.ops.first().status)
        assertEquals(0, w.drainOnce())          // still backed off at t=0
        t = 1000
        assertEquals(1, w.drainOnce())          // due again later
    }

    @Test fun `a missing handler parks the op permanently`() = runTest {
        val h = Holder()
        val w = worker(h, emptyMap(), { 0 })
        w.enqueue("unknown", "p")
        w.drainOnce()
        assertEquals(OpStatus.FAILED, h.q.ops.first().status)
        assertTrue(h.q.hasFailures)
    }

    @Test fun `a non-retryable failure parks immediately`() = runTest {
        val h = Holder()
        val w = worker(
            h,
            mapOf("k" to OpHandler { Result.failure(IllegalStateException("401")) }),
            { 0 },
            retryable = { false },
        )
        w.enqueue("k", "p")
        w.drainOnce()
        assertEquals(OpStatus.FAILED, h.q.ops.first().status)
    }

    @Test fun `retry re-arms a parked op so the next drain runs it`() = runTest {
        val h = Holder()
        var failFirst = true
        val w = worker(
            h,
            mapOf("k" to OpHandler { if (failFirst) Result.failure(IllegalStateException("x")) else Result.success(Unit) }),
            { 0 },
            retryable = { false },
        )
        val id = w.enqueue("k", "p")
        w.drainOnce()
        assertEquals(OpStatus.FAILED, h.q.ops.first().status)
        failFirst = false
        w.retry(id)
        assertEquals(1, w.drainOnce())
        assertTrue(h.q.isEmpty)
    }
}
