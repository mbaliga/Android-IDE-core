package dev.fonebrew.inference.cloud

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.cloud.CloudProvider
import dev.fonebrew.domain.cloud.ProviderKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * OPT-IN LIVE contract suite: calls the REAL provider, once, with a few-token request.
 *
 * It runs only when the developer sets BOTH `FONEBREW_LIVE_CONTRACT=1` and that provider's own
 * key variable (`FONEBREW_LIVE_ANTHROPIC_KEY`, `FONEBREW_LIVE_OPENAI_KEY`,
 * `FONEBREW_LIVE_GEMINI_KEY`). Otherwise every test here is SKIPPED via a JUnit assumption
 * (reported as skipped in the JUnit XML, never as passed). It is never run in CI and no
 * workflow or secret is wired to it. The key is read from the environment, handed only to the
 * engine for that provider, and never printed or put in an assertion message.
 *
 * Optional per-provider overrides: `FONEBREW_LIVE_<P>_MODEL`, and `FONEBREW_LIVE_OPENAI_BASE_URL`
 * (any OpenAI-compatible server). Default model ids are examples and may go stale; override them.
 *
 * STATUS: written without a key, so it has compiled and skipped but NEVER run against a live
 * provider. Live behaviour is UNVERIFIED. See docs/testing/live-contract.md.
 */
class LiveContractTest {

    private class Target(val kind: ProviderKind, val envName: String, val defaultModel: String)

    private val anthropic = Target(ProviderKind.ANTHROPIC, "ANTHROPIC", "claude-haiku-4-5")
    private val openai = Target(ProviderKind.OPENAI_COMPATIBLE, "OPENAI", "gpt-4o-mini")
    private val gemini = Target(ProviderKind.GEMINI, "GEMINI", "gemini-2.0-flash")

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    /** Skips (assumption failure) unless the explicit opt-in AND this provider's key are set. */
    private fun keyOrSkip(t: Target): String {
        assumeTrue("live contract suite not enabled (set FONEBREW_LIVE_CONTRACT=1)", env("FONEBREW_LIVE_CONTRACT") == "1")
        val key = env("FONEBREW_LIVE_${t.envName}_KEY")
        assumeTrue("no FONEBREW_LIVE_${t.envName}_KEY supplied", key != null)
        return key!!
    }

    private fun provider(t: Target) = CloudProvider(
        id = "live-${t.envName.lowercase()}",
        displayName = "Live ${t.envName}",
        kind = t.kind,
        baseUrl = (if (t == openai) env("FONEBREW_LIVE_OPENAI_BASE_URL") else null) ?: t.kind.defaultBaseUrl,
        model = env("FONEBREW_LIVE_${t.envName}_MODEL") ?: t.defaultModel,
        contextWindow = 8192,
        supportsVision = false,
    )

    private fun engineFor(t: Target, key: String): CloudEngine = when (t.kind) {
        ProviderKind.ANTHROPIC -> AnthropicEngine(provider(t), key)
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatEngine(provider(t), key)
        ProviderKind.GEMINI -> GeminiEngine(provider(t), key)
    }

    private fun ask(engine: CloudEngine): Pair<String, Throwable?> {
        val msg = MessageNode("n1", null, Role.USER, "Reply with the single word: ok", createdAt = 0L)
        val sb = StringBuilder()
        var err: Throwable? = null
        runBlocking {
            withTimeout(60_000) {
                try {
                    engine.generate(listOf(msg), SamplingParams(maxTokens = 16), null, null)
                        .collect { sb.append(it.text) }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.TimeoutCancellationException) throw t
                    err = t
                }
            }
        }
        return sb.toString() to err
    }

    private fun minimalRequestHonoursContract(t: Target) {
        val key = keyOrSkip(t)
        val engine = engineFor(t, key)
        val (text, err) = ask(engine)
        assertEquals("live ${t.envName} request failed: ${err?.message?.replace(key, "[key]")}", null, err)
        assertTrue("no text streamed back", text.isNotBlank())
        // Real counts must flow to the ledger (the same point the fixtures check).
        assertTrue("provider reported no usage; counts would be estimated", engine.lastUsage.totalTokens > 0)
        assertTrue(engine.lastUsage.inputTokens > 0)
    }

    private fun badKeyIsRejectedWithoutEcho(t: Target) {
        keyOrSkip(t) // same opt-in; the real key is NOT used, only a deliberately invalid one
        val bogus = "FONEBREW-INVALID-KEY-0000000000"
        val (text, err) = ask(engineFor(t, bogus))
        assertNotNull("an invalid key must be an error", err)
        assertEquals("", text)
        assertFalse("bogus key echoed in message", err!!.message!!.contains(bogus))
        // Gemini reports a bad key as 400 INVALID_ARGUMENT, others as 401/403: just require a 4xx.
        assertTrue(err.message!!, Regex("\\b4\\d\\d\\b").containsMatchIn(err.message!!))
    }

    @Test fun anthropic_minimal_request_honours_contract() = minimalRequestHonoursContract(anthropic)
    @Test fun openai_minimal_request_honours_contract() = minimalRequestHonoursContract(openai)
    @Test fun gemini_minimal_request_honours_contract() = minimalRequestHonoursContract(gemini)

    @Test fun anthropic_bad_key_is_rejected() = badKeyIsRejectedWithoutEcho(anthropic)
    @Test fun openai_bad_key_is_rejected() = badKeyIsRejectedWithoutEcho(openai)
    @Test fun gemini_bad_key_is_rejected() = badKeyIsRejectedWithoutEcho(gemini)
}
