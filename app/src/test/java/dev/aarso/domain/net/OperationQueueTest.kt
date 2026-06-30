package dev.aarso.domain.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationQueueTest {

    @Test fun `backoff grows exponentially and caps`() {
        assertEquals(2000, Backoff.delayMs(1, 2000, 60000))
        assertEquals(4000, Backoff.delayMs(2, 2000, 60000))
        assertEquals(8000, Backoff.delayMs(3, 2000, 60000))
        assertEquals(60000, Backoff.delayMs(20, 2000, 60000)) // capped, no overflow
    }

    @Test fun `an enqueued op is due immediately and shown as pending`() {
        val q = OperationQueue().enqueue("1", "board.move", "{}", now = 100)
        assertEquals(listOf("1"), q.due(100).map { it.id })
        assertEquals(1, q.pending().size)
    }

    @Test fun `success removes the op from the queue`() {
        val q = OperationQueue().enqueue("1", "k", "p", now = 0).markInFlight("1").markSucceeded("1")
        assertTrue(q.isEmpty)
        assertTrue(q.pending().isEmpty())
    }

    @Test fun `a retryable failure reschedules with backoff and is not due yet`() {
        var q = OperationQueue(baseMs = 2000, maxMs = 60000).enqueue("1", "k", "p", now = 0)
        q = q.markInFlight("1").markFailed("1", "network down", now = 1000) // attempts now 1
        assertTrue(q.due(1000).isEmpty())                 // backed off
        assertEquals(listOf("1"), q.due(1000 + 2000).map { it.id }) // due after base delay
        val op = q.ops.first()
        assertEquals(OpStatus.PENDING, op.status)
        assertEquals("network down", op.lastError)
    }

    @Test fun `backoff widens with each attempt`() {
        var q = OperationQueue(baseMs = 1000, maxMs = 100000).enqueue("1", "k", "p", now = 0)
        q = q.markInFlight("1").markFailed("1", "e", now = 0) // attempt 1 → delay 1000
        assertEquals(1000, q.ops.first().nextAttemptAt)
        q = q.markInFlight("1").markFailed("1", "e", now = 1000) // attempt 2 → delay 2000
        assertEquals(1000 + 2000, q.ops.first().nextAttemptAt)
    }

    @Test fun `it gives up after maxAttempts and parks as FAILED, never dropped`() {
        var q = OperationQueue(maxAttempts = 2, baseMs = 1, maxMs = 10).enqueue("1", "k", "p", now = 0)
        q = q.markInFlight("1").markFailed("1", "e", 0) // attempt 1 → pending
        q = q.markInFlight("1").markFailed("1", "e", 0) // attempt 2 == max → FAILED
        assertEquals(OpStatus.FAILED, q.ops.first().status)
        assertTrue(q.hasFailures)
        assertFalse(q.isEmpty) // parked for the user, not silently dropped
    }

    @Test fun `a non-retryable failure parks immediately`() {
        var q = OperationQueue().enqueue("1", "k", "p", now = 0).markInFlight("1")
        q = q.markFailed("1", "401 unauthorized", now = 0, retryable = false)
        assertEquals(OpStatus.FAILED, q.ops.first().status)
    }

    @Test fun `retryNow re-arms a failed op and clears its error`() {
        var q = OperationQueue(maxAttempts = 1).enqueue("1", "k", "p", 0).markInFlight("1").markFailed("1", "e", 0)
        assertEquals(OpStatus.FAILED, q.ops.first().status)
        q = q.retryNow("1", now = 500)
        assertEquals(OpStatus.PENDING, q.ops.first().status)
        assertEquals(listOf("1"), q.due(500).map { it.id })
    }

    @Test fun `duplicate ids are rejected`() {
        var threw = false
        try {
            OperationQueue().enqueue("1", "k", "p", 0).enqueue("1", "k", "p", 0)
        } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test fun `discard removes an op`() {
        val q = OperationQueue().enqueue("1", "k", "p", 0).discard("1")
        assertTrue(q.isEmpty)
    }
}
