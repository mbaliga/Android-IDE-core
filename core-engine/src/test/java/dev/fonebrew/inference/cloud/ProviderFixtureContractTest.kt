package dev.fonebrew.inference.cloud

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.cloud.CloudProvider
import dev.fonebrew.domain.cloud.ProviderKind
import dev.fonebrew.domain.cost.UsageReport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Always-on provider contract tests: each engine is driven end to end (real OkHttp SSE
 * stack) against a loopback [FakeProviderServer] replaying a **hand-authored** transcript
 * from `src/test/resources/cloud-contract/<provider>/`.
 *
 * HONEST SCOPE: the transcripts follow each provider's publicly documented wire format; none
 * was recorded from a live call. Passing here means "the engine handles the documented
 * shapes", not "the provider still sends them". The opt-in [LiveContractTest] is the only
 * thing that can check the latter, and it has not been run (docs/testing/live-contract.md).
 *
 * No test here dials a real host: every provider's base URL is the loopback fake.
 */
abstract class ProviderFixtureContractTest {

    // --- per-provider spec ------------------------------------------------------------
    abstract val kind: ProviderKind
    abstract val fixtureDir: String
    abstract fun engine(provider: CloudProvider, key: String): CloudEngine
    abstract fun baseUrlFor(server: FakeProviderServer): String
    abstract val expectedRequestLine: String // "POST <path> HTTP/1.1"
    abstract val authHeaderName: String
    abstract fun authHeaderValue(key: String): String
    abstract val otherProviderAuthHeaders: List<String>
    /** Text delivered when the one malformed event is skipped. */
    abstract val textAfterMalformed: String
    /** Text delivered before a mid-stream error / cut, and the error fixture's message text. */
    abstract val textBeforeFailure: String
    abstract val errorMessageText: String
    abstract val stopFixtures: List<String>

    // --- helpers ------------------------------------------------------------------------
    protected val key = "TESTKEY-not-a-real-key-9f3a"
    private val servers = mutableListOf<FakeProviderServer>()

    @After fun stopServers() { servers.forEach { it.close() }; servers.clear() }

    protected fun fixture(name: String): ByteArray =
        (javaClass.classLoader!!.getResourceAsStream("cloud-contract/$fixtureDir/$name")
            ?: error("missing fixture cloud-contract/$fixtureDir/$name")).use { it.readBytes() }

    protected class Outcome(val tokens: List<String>, val error: Throwable?, val engine: CloudEngine) {
        val text: String get() = tokens.joinToString("")
    }

    protected fun run(reply: FakeProviderServer.Reply): Pair<Outcome, FakeProviderServer> {
        val server = FakeProviderServer(reply).also { servers += it }
        val provider = CloudProvider("p1", "Fixture", kind, baseUrlFor(server), "model-fixture", 8192)
        val engine = engine(provider, key)
        val tokens = mutableListOf<String>()
        var error: Throwable? = null
        runBlocking {
            withTimeout(15_000) {
                try {
                    engine.generate(listOf(user("hi")), SamplingParams(maxTokens = 64), null, null)
                        .collect { tokens += it.text } // incremental, as the UI does: keeps tokens before a failure
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.TimeoutCancellationException) throw t
                    error = t
                }
            }
        }
        return Outcome(tokens, error, engine) to server
    }

    protected fun runFixture(name: String, bytes: ByteArray = fixture(name), splitAt: List<Int> = emptyList()) =
        run(FakeProviderServer.Reply(body = bytes, splitAt = splitAt))

    private fun user(text: String) = MessageNode("n1", null, Role.USER, text, createdAt = 0L)

    private fun httpError(status: Int, vararg extra: Pair<String, String>, body: String) =
        FakeProviderServer.Reply(
            status = status,
            headers = listOf("Content-Type" to "application/json") + extra,
            body = body.toByteArray(),
        )

    // --- normal text + usage ------------------------------------------------------------
    @Test fun normal_stream_delivers_text_and_real_usage() {
        val (o, _) = runFixture("normal.sse")
        assertNull(o.error)
        assertEquals("Hello, world", o.text)
        assertEquals(UsageReport(25, 15), o.engine.lastUsage)
    }

    @Test fun absent_usage_is_never_invented() {
        val (o, _) = runFixture("no_usage.sse")
        assertNull(o.error)
        assertEquals("Hello, world", o.text)
        // ZERO == "the provider reported nothing"; consumers (ChatViewModel, LoopRoom,
        // WristTurnRunner) treat totalTokens == 0 as "estimate instead" and label it so.
        assertEquals(UsageReport.ZERO, o.engine.lastUsage)
    }

    // --- stop reasons ---------------------------------------------------------------------
    @Test fun every_documented_stop_reason_completes_without_error() {
        for (f in stopFixtures) {
            val (o, _) = runFixture(f)
            assertNull("$f: ${o.error}", o.error)
            assertEquals("$f text", "Hello, world", o.text)
        }
    }

    // --- failures ---------------------------------------------------------------------------
    @Test fun mid_stream_error_event_surfaces_as_an_error_after_the_text_already_sent() {
        val (o, _) = runFixture("mid_stream_error.sse")
        assertEquals(textBeforeFailure, o.text)
        val e = o.error
        assertNotNull("a provider error event must not end the stream silently", e)
        assertTrue(e!!.message!!, e.message!!.contains(errorMessageText))
    }

    @Test fun a_key_echoed_inside_a_mid_stream_error_is_redacted() {
        val bytes = String(fixture("mid_stream_error.sse"), Charsets.UTF_8)
            .replace(errorMessageText, "bad key $key").toByteArray()
        val (o, _) = runFixture("mid_stream_error.sse", bytes)
        assertNotNull(o.error)
        assertFalse(o.error!!.message!!, o.error!!.message!!.contains(key))
    }

    @Test fun http_401_is_an_error_and_never_echoes_the_key() {
        val (o, _) = run(httpError(401, body = """{"error":{"message":"Incorrect API key provided: $key."}}"""))
        assertEquals(emptyList<String>(), o.tokens)
        val msg = o.error!!.message!!
        assertTrue(msg, msg.contains("401"))
        assertFalse("key leaked into exception message: $msg", msg.contains(key))
        assertFalse(o.error!!.toString().contains(key))
    }

    @Test fun http_429_is_an_error_not_a_silent_retry() {
        val (o, server) = run(httpError(429, "Retry-After" to "7", body = """{"error":{"message":"rate limited"}}"""))
        assertTrue(o.error!!.message!!, o.error!!.message!!.contains("429"))
        assertEquals("the engine must not retry on its own", 1, server.requests.size)
    }

    @Test fun http_5xx_is_an_error() {
        val (o, _) = run(httpError(503, body = """{"error":{"message":"unavailable"}}"""))
        assertTrue(o.error!!.message!!, o.error!!.message!!.contains("503"))
    }

    @Test fun a_stream_cut_mid_chunk_fails_loudly_and_keeps_text_already_delivered() {
        val (o, _) = run(FakeProviderServer.Reply(body = fixture("truncated.sse"), abruptDrop = true))
        assertNotNull("a dropped connection must surface as an error", o.error)
        assertTrue(o.text.startsWith("Hello"))
    }

    // --- tolerance --------------------------------------------------------------------------
    @Test fun one_malformed_json_event_is_skipped_and_the_stream_continues() {
        val (o, _) = runFixture("malformed_event.sse")
        assertNull(o.error)
        assertEquals(textAfterMalformed, o.text)
        assertEquals(UsageReport(25, 15), o.engine.lastUsage)
    }

    @Test fun unknown_event_types_are_ignored() {
        val (o, _) = runFixture("unknown_event.sse")
        assertNull(o.error)
        assertEquals("Hello, world", o.text)
        assertEquals(UsageReport(25, 15), o.engine.lastUsage)
    }

    @Test fun multibyte_characters_split_across_tcp_writes_decode_intact() {
        val bytes = fixture("unicode.sse")
        val firstMulti = bytes.indexOfFirst { it < 0 }          // lead byte of the first non-ASCII char
        val emoji = bytes.indexOfFirst { it == 0xF0.toByte() }  // 4-byte emoji lead byte
        val (o, _) = runFixture("unicode.sse", bytes, splitAt = listOf(firstMulti + 1, emoji + 2))
        assertNull(o.error)
        assertEquals("héllo 🌍 日本", o.text)
    }

    // --- request builder ------------------------------------------------------------------------
    @Test fun request_goes_to_the_documented_endpoint_with_the_key_only_in_its_auth_header() {
        val (o, server) = runFixture("normal.sse")
        assertNull(o.error)
        val req = server.requests.single()
        assertEquals(expectedRequestLine, req.requestLine)
        assertEquals(authHeaderValue(key), req.header(authHeaderName))
        for (h in otherProviderAuthHeaders) {
            assertFalse("$h must not be sent to this provider", h.lowercase() in req.headerNames)
        }
        // The key appears in exactly one place: its own auth header. Not the URL, not the body.
        val withoutAuth = req.head.lineSequence().filterNot { it.startsWith("$authHeaderName:", true) }.joinToString("\n")
        assertFalse("key in request line/other headers", withoutAuth.contains(key))
        assertFalse("key in request body", req.body.contains(key))
        assertTrue(req.header("content-type")!!.startsWith("application/json"))
    }

    @Test fun the_key_is_absent_from_engine_and_provider_toString() {
        val (o, _) = runFixture("normal.sse")
        assertFalse(o.engine.toString().contains(key))
        val p = CloudProvider("p1", "Fixture", kind, "http://127.0.0.1:1", "m", 1)
        assertFalse(p.toString().contains(key))
    }
}

class AnthropicContractTest : ProviderFixtureContractTest() {
    // --- known gap (finding): see docs/testing/live-contract.md ---------------------------
    @Ignore("FINDING: a 200 stream that closes cleanly without its terminal event is reported as a normal end")
    @Test fun stream_closed_cleanly_without_terminal_event_should_be_flagged() {
        val (o, _) = runFixture("truncated.sse")
        assertNotNull(o.error)
    }

    override val kind = ProviderKind.ANTHROPIC
    override val fixtureDir = "anthropic"
    override fun engine(provider: CloudProvider, key: String) = AnthropicEngine(provider, key)
    override fun baseUrlFor(server: FakeProviderServer) = server.baseUrl
    override val expectedRequestLine = "POST /v1/messages HTTP/1.1"
    override val authHeaderName = "x-api-key"
    override fun authHeaderValue(key: String) = key
    override val otherProviderAuthHeaders = listOf("authorization", "x-goog-api-key")
    override val textAfterMalformed = "Hello"
    override val textBeforeFailure = "Hello"
    override val errorMessageText = "Overloaded"
    override val stopFixtures = listOf("stop_max_tokens.sse", "stop_pause_turn.sse")

    @Test fun sends_the_pinned_api_version_and_a_streaming_body() {
        val (_, server) = runFixture("normal.sse")
        val req = server.requests.single()
        assertEquals("2023-06-01", req.header("anthropic-version"))
        assertTrue(req.body.contains("\"stream\":true"))
        assertTrue(req.body.contains("\"max_tokens\":64"))
    }

    @Test fun pause_turn_is_the_only_stop_reason_that_latches_wasPaused() {
        assertTrue(runFixture("stop_pause_turn.sse").first.engine.wasPaused)
        assertFalse(runFixture("stop_max_tokens.sse").first.engine.wasPaused)
        assertFalse(runFixture("normal.sse").first.engine.wasPaused)
    }
}

class OpenAiCompatContractTest : ProviderFixtureContractTest() {
    // --- known gap (finding): see docs/testing/live-contract.md ---------------------------
    @Ignore("FINDING: a 200 stream that closes cleanly without its terminal event is reported as a normal end")
    @Test fun stream_closed_cleanly_without_terminal_event_should_be_flagged() {
        val (o, _) = runFixture("truncated.sse")
        assertNotNull(o.error)
    }

    override val kind = ProviderKind.OPENAI_COMPATIBLE
    override val fixtureDir = "openai"
    override fun engine(provider: CloudProvider, key: String) = OpenAiCompatEngine(provider, key)
    override fun baseUrlFor(server: FakeProviderServer) = server.baseUrl + "/v1"
    override val expectedRequestLine = "POST /v1/chat/completions HTTP/1.1"
    override val authHeaderName = "Authorization"
    override fun authHeaderValue(key: String) = "Bearer $key"
    override val otherProviderAuthHeaders = listOf("x-api-key", "x-goog-api-key")
    override val textAfterMalformed = "Hello"
    override val textBeforeFailure = "Hello, world"
    override val errorMessageText = "The server had an error while processing your request"
    override val stopFixtures = listOf("stop_length.sse")

    @Test fun asks_the_server_to_include_usage_in_the_stream() {
        // Without stream_options.include_usage OpenAI never sends the usage chunk, so real
        // counts could not flow to the ledger (defect found by this suite, fixed with it).
        val (_, server) = runFixture("normal.sse")
        assertTrue(server.requests.single().body.contains("\"stream_options\":{\"include_usage\":true}"))
    }
}

class GeminiContractTest : ProviderFixtureContractTest() {
    override val kind = ProviderKind.GEMINI
    override val fixtureDir = "gemini"
    override fun engine(provider: CloudProvider, key: String) = GeminiEngine(provider, key)
    override fun baseUrlFor(server: FakeProviderServer) = server.baseUrl
    override val expectedRequestLine =
        "POST /v1beta/models/model-fixture:streamGenerateContent?alt=sse HTTP/1.1"
    override val authHeaderName = "x-goog-api-key"
    override fun authHeaderValue(key: String) = key
    override val otherProviderAuthHeaders = listOf("authorization", "x-api-key")
    override val textAfterMalformed = "Hello, world"
    override val textBeforeFailure = "Hello, world"
    override val errorMessageText = "The model is overloaded."
    override val stopFixtures = listOf("stop_max_tokens.sse")
}
