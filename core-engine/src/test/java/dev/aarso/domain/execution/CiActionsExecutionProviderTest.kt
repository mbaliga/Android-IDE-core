package dev.aarso.domain.execution

import dev.aarso.contracts.execution.AuthorityGrantRef
import dev.aarso.contracts.execution.ExecutionBudget
import dev.aarso.contracts.execution.ExecutionEvent
import dev.aarso.contracts.execution.ExecutionExitState
import dev.aarso.contracts.execution.ExecutionRequest
import dev.aarso.contracts.execution.ExecutionState
import dev.aarso.contracts.execution.ExternalDuplicateBehavior
import dev.aarso.contracts.execution.FgsType
import dev.aarso.contracts.execution.OperationClass
import dev.aarso.contracts.execution.RequestEnvironment
import dev.aarso.contracts.execution.SessionReauthorization
import dev.aarso.contracts.execution.TypedOperation
import dev.aarso.data.GitTransport
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import dev.aarso.domain.git.GitRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake [GitTransport] returning recorded HTTP fixtures -- **never a live network call**, per
 * the WP-5 brief's own words. Records a realistic GitHub Actions `workflow_runs` response
 * sequence (queued -> in_progress -> completed/success), matching the real shape
 * `dev.aarso.domain.builds.CiTrigger.parseRuns` parses.
 */
private class FakeGitTransport(private val finalConclusion: String = "success") : GitTransport() {
    val listRunsCalls = AtomicInteger(0)
    val dispatchCalls = AtomicInteger(0)

    private fun runJson(status: String, conclusion: String?) = """
        {"workflow_runs": [{
          "id": 42, "name": "CI", "display_title": "CI run", "head_branch": "main",
          "event": "workflow_dispatch", "status": "$status",
          ${if (conclusion != null) "\"conclusion\": \"$conclusion\"," else ""}
          "created_at": "2026-08-07T12:00:00Z", "updated_at": "2026-08-07T12:00:05Z",
          "html_url": "https://github.com/org/repo/actions/runs/42"
        }]}
    """.trimIndent()

    override suspend fun execute(req: GitRequest): Resp {
        return when {
            req.method == "POST" && req.url.contains("/dispatches") -> {
                dispatchCalls.incrementAndGet()
                Resp(204, "")
            }
            req.method == "GET" && req.url.contains("/runs") -> {
                val call = listRunsCalls.incrementAndGet()
                when {
                    call <= 1 -> Resp(200, runJson("queued", null))
                    call <= 3 -> Resp(200, runJson("in_progress", null))
                    else -> Resp(200, runJson("completed", finalConclusion))
                }
            }
            else -> Resp(404, "")
        }
    }
}

class CiActionsExecutionProviderTest {

    private val githubHost = GitHost(
        id = "gh-1", displayName = "GitHub", kind = GitHostKind.GITHUB, baseUrl = "", owner = "org", repo = "repo",
        branch = "main", authorName = "Fonebrew Bot", authorEmail = "bot@example.com"
    )

    private fun request(command: String = "ci.yml") = ExecutionRequest(
        id = "req_ci1", targetId = "target-gh-actions", operation = TypedOperation(OperationClass.CI_DISPATCH, command),
        workingRevision = "main", environment = RequestEnvironment(), secretHandles = emptyList(),
        budget = ExecutionBudget(SessionReauthorization(300, false)),
        authorityGrant = AuthorityGrantRef("grant_1", listOf("fb.ci.dispatch")),
        expectedOutputs = emptyList(), idempotencyKey = "idem-ci-1", sideEffectExternal = true,
        fgsType = FgsType.NONE, openEnded = false, externalDuplicateBehavior = ExternalDuplicateBehavior.REJECT_DUPLICATE
    )

    @Test
    fun `lifecycle -- dispatch then poll to a successful conclusion reaches SUCCEEDED_UNVERIFIED`() = runBlocking {
        val transport = FakeGitTransport(finalConclusion = "success")
        val provider = CiActionsExecutionProvider(githubHost, token = "tok", transport = transport, pollIntervalMillis = 10)

        val prepared = provider.prepare(request())
        val handle = provider.start(prepared)
        assertEquals(ExecutionState.RUNNING, handle.state)
        assertEquals(1, transport.dispatchCalls.get())

        val receiptEvent = withTimeout(5_000) {
            provider.observe(handle).toList().filterIsInstance<ExecutionEvent.ReceiptReady>().first()
        }
        assertEquals(ExecutionExitState.SUCCEEDED_UNVERIFIED, receiptEvent.receipt.exitState)
        assertTrue(receiptEvent.receipt.sideEffects.isNotEmpty()) // a CI dispatch is always an external side effect
    }

    @Test
    fun `lifecycle -- a failed conclusion reaches FAILED_SIDE_EFFECTS_POSSIBLE, never FAILED_SAFE`() = runBlocking {
        val transport = FakeGitTransport(finalConclusion = "failure")
        val provider = CiActionsExecutionProvider(githubHost, token = "tok", transport = transport, pollIntervalMillis = 10)

        val prepared = provider.prepare(request())
        val handle = provider.start(prepared)
        val receiptEvent = withTimeout(5_000) {
            provider.observe(handle).toList().filterIsInstance<ExecutionEvent.ReceiptReady>().first()
        }
        assertEquals(ExecutionExitState.FAILED_SIDE_EFFECTS_POSSIBLE, receiptEvent.receipt.exitState)
        assertTrue(receiptEvent.receipt.sideEffects.isNotEmpty())
    }

    @Test
    fun `cancel is honestly UNSUPPORTED -- no run-cancel request builder exists yet`() = runBlocking {
        val transport = FakeGitTransport()
        val provider = CiActionsExecutionProvider(githubHost, token = "tok", transport = transport, pollIntervalMillis = 10)
        val prepared = provider.prepare(request())
        val handle = provider.start(prepared)
        assertEquals(dev.aarso.contracts.execution.CancellationMode.UNSUPPORTED, handle.cancellationMode)
        val result = provider.cancel(handle, dev.aarso.contracts.execution.CancelMode.COOPERATIVE)
        assertEquals(false, result.accepted)
    }

    @Test
    fun `prepare rejects a request that does not declare sideEffectExternal -- CI dispatch is always external`() = runBlocking {
        val provider = CiActionsExecutionProvider(githubHost, token = "tok", transport = FakeGitTransport())
        val nonExternal = request().copy(sideEffectExternal = false, idempotencyKey = null, externalDuplicateBehavior = null)
        try {
            provider.prepare(nonExternal)
            org.junit.Assert.fail("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("external"))
        }
    }

    @Test
    fun `descriptor declares GITHUB_ACTIONS for a GitHub host and GITEA_ACTIONS for a Gitea host`() {
        val githubProvider = CiActionsExecutionProvider(githubHost, "tok", FakeGitTransport())
        assertEquals(listOf(dev.aarso.contracts.execution.ExecutionTargetType.GITHUB_ACTIONS), githubProvider.descriptor.supportedTargetTypes)

        val giteaHost = githubHost.copy(kind = GitHostKind.GITEA, baseUrl = "https://gitea.example.com")
        val giteaProvider = CiActionsExecutionProvider(giteaHost, "tok", FakeGitTransport())
        assertEquals(listOf(dev.aarso.contracts.execution.ExecutionTargetType.GITEA_ACTIONS), giteaProvider.descriptor.supportedTargetTypes)
    }
}
