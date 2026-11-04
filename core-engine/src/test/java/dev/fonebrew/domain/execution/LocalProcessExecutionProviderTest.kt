package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.execution.AuthorityGrantRef
import dev.fonebrew.contracts.execution.CancelMode
import dev.fonebrew.contracts.execution.ExecutionBudget
import dev.fonebrew.contracts.execution.ExecutionEvent
import dev.fonebrew.contracts.execution.ExecutionExitState
import dev.fonebrew.contracts.execution.ExecutionRequest
import dev.fonebrew.contracts.execution.ExecutionState
import dev.fonebrew.contracts.execution.FgsType
import dev.fonebrew.contracts.execution.OperationClass
import dev.fonebrew.contracts.execution.ReconnectTokenHandle
import dev.fonebrew.contracts.execution.RequestEnvironment
import dev.fonebrew.contracts.execution.SessionReauthorization
import dev.fonebrew.contracts.execution.TypedOperation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real process spawn/exit/cancel/reconnect against `/bin/sh -c`, deliberately `runBlocking`
 * (real wall-clock time, real background thread) for the same reason
 * `LocalWorkspaceProviderTest`'s `watch()` test is -- see [LocalProcessExecutionProvider]'s own
 * doc comment on testing the real supervisor mechanism against a real JVM-native process.
 */
class LocalProcessExecutionProviderTest {

    private fun request(command: String, id: String = "req_" + command.hashCode()) = ExecutionRequest(
        id = id, targetId = "target-local", operation = TypedOperation(OperationClass.ONE_SHOT_COMMAND, command),
        workingRevision = null, environment = RequestEnvironment(), secretHandles = emptyList(),
        budget = ExecutionBudget(SessionReauthorization(maxSessionSeconds = 60, requiresUserReauth = false)),
        authorityGrant = AuthorityGrantRef(grantId = "grant_1", scopes = listOf("fb.exec.local_process")),
        expectedOutputs = emptyList(), idempotencyKey = null, sideEffectExternal = false,
        fgsType = FgsType.NONE, openEnded = false
    )

    @Test
    fun `lifecycle -- a successful command reaches SUCCEEDED_UNVERIFIED with a matching receipt`() = runBlocking {
        val provider = LocalProcessExecutionProvider()
        val prepared = provider.prepare(request("echo hello-world"))
        val handle = provider.start(prepared)
        assertEquals(ExecutionState.RUNNING, handle.state)

        val receiptEvent = withTimeout(5_000) {
            provider.observe(handle).toList().filterIsInstance<ExecutionEvent.ReceiptReady>().first()
        }
        val receipt = receiptEvent.receipt
        assertEquals(ExecutionExitState.SUCCEEDED_UNVERIFIED, receipt.exitState)
        assertTrue(receipt.logs.excerpt!!.contains("hello-world"))
        assertEquals(receipt.requestId, prepared.requestId)
        assertEquals(receipt.handleId, handle.handleId)
    }

    @Test
    fun `lifecycle -- a failing command reaches FAILED_SAFE`() = runBlocking {
        val provider = LocalProcessExecutionProvider()
        val prepared = provider.prepare(request("exit 7"))
        val handle = provider.start(prepared)

        val receiptEvent = withTimeout(5_000) {
            provider.observe(handle).toList().filterIsInstance<ExecutionEvent.ReceiptReady>().first()
        }
        assertEquals(ExecutionExitState.FAILED_SAFE, receiptEvent.receipt.exitState)
    }

    @Test
    fun `cancellation -- COOPERATIVE cancel stops a long-running process`() = runBlocking {
        val provider = LocalProcessExecutionProvider()
        val prepared = provider.prepare(request("sleep 30"))
        val handle = provider.start(prepared)

        val result = provider.cancel(handle, CancelMode.COOPERATIVE)
        assertTrue(result.accepted)
        assertEquals(ExecutionState.CANCELLED, result.resultingState)
    }

    @Test
    fun `cancellation -- FORCEFUL cancel also stops a long-running process`() = runBlocking {
        val provider = LocalProcessExecutionProvider()
        val prepared = provider.prepare(request("sleep 30"))
        val handle = provider.start(prepared)

        val result = provider.cancel(handle, CancelMode.FORCEFUL)
        assertTrue(result.accepted)
        assertEquals(ExecutionState.CANCELLED, result.resultingState)
    }

    @Test
    fun `reconnect -- a known token returns the current handle state`() = runBlocking {
        val provider = LocalProcessExecutionProvider()
        val prepared = provider.prepare(request("echo reconnect-me"))
        val handle = provider.start(prepared)

        val reconnected = provider.reconnect(ReconnectTokenHandle(handle.reconnectToken!!.token))
        assertTrue(reconnected != null)
        assertEquals(handle.handleId, reconnected!!.handleId)
        assertEquals(dev.fonebrew.contracts.execution.ReconnectOutcome.ESTABLISHED, reconnected.reconnectAttempt!!.outcome)
    }

    @Test
    fun `reconnect -- an unrecognized token returns null, not a fabricated handle`() = runBlocking {
        val provider = LocalProcessExecutionProvider()
        val reconnected = provider.reconnect(ReconnectTokenHandle("token-nobody-ever-issued"))
        assertNull(reconnected)
    }

    @Test
    fun `descriptor declares LOCAL_ANDROID support`() {
        val provider = LocalProcessExecutionProvider()
        assertTrue(dev.fonebrew.contracts.execution.ExecutionTargetType.LOCAL_ANDROID in provider.descriptor.supportedTargetTypes)
    }
}
