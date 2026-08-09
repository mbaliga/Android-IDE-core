package dev.aarso.domain.authority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The WP-4 brief's "secret-redaction sentinel test" -- a real scan against real live secret values, not a regex heuristic. */
class SecretRedactionScannerTest {

    private val liveSecrets = listOf("sk-live-fake-secret-abc123", "ghp_fakeTokenValueXYZ789")

    @Test
    fun `zero hits -- a properly redacted excerpt contains none of the live secret values`() {
        val excerpt = "Build succeeded. Pushed to remote using the configured credential."
        assertTrue(SecretRedactionScanner.isClean(excerpt, liveSecrets))
        assertEquals(emptyList<String>(), SecretRedactionScanner.scanForLeaks(excerpt, liveSecrets))
    }

    @Test
    fun `the scanner is not vacuous -- it genuinely detects a leaked secret when one is present`() {
        val leaked = "curl -H 'Authorization: Bearer sk-live-fake-secret-abc123' https://api.example.com"
        assertTrue(!SecretRedactionScanner.isClean(leaked, liveSecrets))
        assertEquals(listOf("sk-live-fake-secret-abc123"), SecretRedactionScanner.scanForLeaks(leaked, liveSecrets))
    }

    @Test
    fun `detects multiple distinct leaked secrets in the same text`() {
        val leaked = "sk-live-fake-secret-abc123 and also ghp_fakeTokenValueXYZ789 both appear here"
        val hits = SecretRedactionScanner.scanForLeaks(leaked, liveSecrets)
        assertEquals(2, hits.size)
    }

    @Test
    fun `an empty live-secrets set never flags anything, including suspicious-looking text`() {
        assertTrue(SecretRedactionScanner.isClean("Bearer sk-anything-at-all", emptyList()))
    }

    @Test
    fun `receipt logs excerpt produced by LocalProcessExecutionProvider for a benign echo carries no secret-shaped leak`() = kotlinx.coroutines.runBlocking {
        val provider = dev.aarso.domain.execution.LocalProcessExecutionProvider()
        val request = dev.aarso.contracts.execution.ExecutionRequest(
            id = "req_redaction", targetId = "target-local",
            operation = dev.aarso.contracts.execution.TypedOperation(dev.aarso.contracts.execution.OperationClass.ONE_SHOT_COMMAND, "echo build-ok"),
            workingRevision = null, environment = dev.aarso.contracts.execution.RequestEnvironment(), secretHandles = emptyList(),
            budget = dev.aarso.contracts.execution.ExecutionBudget(dev.aarso.contracts.execution.SessionReauthorization(60, false)),
            authorityGrant = dev.aarso.contracts.execution.AuthorityGrantRef("grant_1", listOf("fb.exec.local_process")),
            expectedOutputs = emptyList(), idempotencyKey = null, sideEffectExternal = false,
            fgsType = dev.aarso.contracts.execution.FgsType.NONE, openEnded = false
        )
        val prepared = provider.prepare(request)
        val handle = provider.start(prepared)
        val receipt = kotlinx.coroutines.withTimeout(5_000) {
            provider.observe(handle).let { flow ->
                var found: dev.aarso.contracts.execution.ExecutionReceipt? = null
                flow.collect { e -> if (e is dev.aarso.contracts.execution.ExecutionEvent.ReceiptReady) found = e.receipt }
                found
            }
        }
        assertTrue(SecretRedactionScanner.isClean(receipt!!.logs.excerpt ?: "", liveSecrets))
    }
}
