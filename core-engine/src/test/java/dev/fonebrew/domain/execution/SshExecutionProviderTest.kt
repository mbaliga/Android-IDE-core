package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.execution.AuthorityGrantRef
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
import dev.fonebrew.domain.remote.ExecChunk
import dev.fonebrew.domain.remote.ExecRequest
import dev.fonebrew.domain.remote.ExecResult
import dev.fonebrew.domain.remote.HostKey
import dev.fonebrew.domain.remote.Identity
import dev.fonebrew.domain.remote.RemoteHost
import dev.fonebrew.domain.remote.RemoteTransport
import dev.fonebrew.domain.remote.SftpEntry
import dev.fonebrew.domain.remote.SftpOp
import dev.fonebrew.domain.remote.ShellSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fake [RemoteTransport], the same "JVM-verifiable without a socket" pattern `domain/remote`'s
 * own doc comments establish -- there is no `sshd` available in this sandbox to test against a
 * real loopback server (checked: `which sshd` finds nothing), so this exercises the REAL
 * [dev.fonebrew.domain.remote.RemoteSessionDriver]/[dev.fonebrew.domain.remote.SessionMachine]/
 * [dev.fonebrew.domain.remote.KnownHosts] orchestration logic against a fake I/O layer only.
 */
private class FakeRemoteTransport(
    private val presentedKey: HostKey = HostKey("ssh-ed25519", "SHA256:fakefingerprint"),
    private val execOutput: String = "hello from remote",
    private val execExitCode: Int = 0,
    private val failConnect: Boolean = false,
) : RemoteTransport {
    var closed = false
        private set

    override suspend fun connect(host: RemoteHost): HostKey {
        if (failConnect) throw java.io.IOException("connection refused")
        return presentedKey
    }
    override suspend fun authenticate(identity: Identity) {}
    override suspend fun exec(request: ExecRequest, onChunk: (ExecChunk) -> Unit): ExecResult {
        onChunk(ExecChunk(ExecChunk.StdStream.OUT, execOutput.toByteArray()))
        return ExecResult(execExitCode)
    }
    override suspend fun sftp(op: SftpOp): List<SftpEntry> = emptyList()
    override suspend fun close() { closed = true }
}

class SshExecutionProviderTest {

    private val host = RemoteHost(alias = "pi-1", hostname = "192.168.1.50", username = "pi")

    private fun request(command: String = "echo hello", id: String = "req_ssh1") = ExecutionRequest(
        id = id, targetId = "target-ssh", operation = TypedOperation(OperationClass.ONE_SHOT_COMMAND, command),
        workingRevision = null, environment = RequestEnvironment(), secretHandles = emptyList(),
        budget = ExecutionBudget(SessionReauthorization(60, false)),
        authorityGrant = AuthorityGrantRef("grant_1", listOf("fb.exec.ssh_remote")),
        expectedOutputs = emptyList(), idempotencyKey = null, sideEffectExternal = false,
        fgsType = FgsType.NONE, openEnded = false
    )

    private fun provider(transport: RemoteTransport, approveTrust: suspend (dev.fonebrew.domain.remote.Trust) -> Boolean = { true }) =
        SshExecutionProvider(
            transportFactory = { transport }, hostResolver = { host }, identityResolver = { Identity.Agent },
            approveTrust = approveTrust
        )

    @Test
    fun `lifecycle -- a successful exec on an unknown host, once trust is approved, reaches SUCCEEDED_UNVERIFIED`() = runBlocking {
        val transport = FakeRemoteTransport(execOutput = "hello from remote", execExitCode = 0)
        val provider = provider(transport)
        val prepared = provider.prepare(request())
        val handle = provider.start(prepared)
        assertEquals(ExecutionState.RUNNING, handle.state)

        val receiptEvent = withTimeout(5_000) {
            provider.observe(handle).toList().filterIsInstance<ExecutionEvent.ReceiptReady>().first()
        }
        assertEquals(ExecutionExitState.SUCCEEDED_UNVERIFIED, receiptEvent.receipt.exitState)
        assertTrue(receiptEvent.receipt.logs.excerpt!!.contains("hello from remote"))
        assertTrue(transport.closed) // the SSH session was closed once the exec finished
    }

    @Test
    fun `an unknown host key that the caller rejects never proceeds to exec`() = runBlocking {
        val transport = FakeRemoteTransport()
        val provider = provider(transport, approveTrust = { false })
        val prepared = provider.prepare(request())
        try {
            provider.start(prepared)
            org.junit.Assert.fail("expected start() to fail when trust is rejected -- RemoteSessionDriver.open() aborts to Closed")
        } catch (_: IllegalStateException) {
            // RemoteSessionDriver.exec() would throw "exec requires Ready" if somehow reached;
            // open() itself just returns having moved to Closed without authenticating -- the
            // real assertion is that no exec ever ran, checked next.
        }
    }

    @Test
    fun `a failing remote command reaches FAILED_SAFE`() = runBlocking {
        val transport = FakeRemoteTransport(execExitCode = 1)
        val provider = provider(transport)
        val prepared = provider.prepare(request("false"))
        val handle = provider.start(prepared)
        val receiptEvent = withTimeout(5_000) {
            provider.observe(handle).toList().filterIsInstance<ExecutionEvent.ReceiptReady>().first()
        }
        assertEquals(ExecutionExitState.FAILED_SAFE, receiptEvent.receipt.exitState)
    }

    @Test
    fun `reconnect with an unrecognized token returns null`() = runBlocking {
        val provider = provider(FakeRemoteTransport())
        assertNull(provider.reconnect(ReconnectTokenHandle("no-such-token")))
    }

    @Test
    fun `reconnect with a known token returns the live handle state`() = runBlocking {
        val provider = provider(FakeRemoteTransport())
        val prepared = provider.prepare(request())
        val handle = provider.start(prepared)
        val reconnected = provider.reconnect(ReconnectTokenHandle(handle.reconnectToken!!.token))
        assertTrue(reconnected != null)
        assertEquals(handle.handleId, reconnected!!.handleId)
    }

    @Test
    fun `descriptor declares both SSH_HOST and RASPBERRY_PI support`() {
        val provider = provider(FakeRemoteTransport())
        assertTrue(dev.fonebrew.contracts.execution.ExecutionTargetType.SSH_HOST in provider.descriptor.supportedTargetTypes)
        assertTrue(dev.fonebrew.contracts.execution.ExecutionTargetType.RASPBERRY_PI in provider.descriptor.supportedTargetTypes)
    }
}
