package dev.aarso.inference.cloud

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.SamplingParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-JSON coverage for [buildAnthropicRequestBody] — the pure body-building
 * function `AnthropicEngine.buildRequest` delegates to (that method itself is
 * `protected` on [CloudEngine] and unreachable from a JVM test).
 *
 * Baseline (pre-W1) coverage only: role/content are plain strings, no image
 * blocks. W1 adds vision content-block shapes on top of this.
 */
class AnthropicEngineTest {

    private fun node(role: Role, content: String, id: String) = MessageNode(
        id = id,
        parentId = null,
        role = role,
        content = content,
        createdAt = 0L,
    )

    @Test fun `max_tokens flows from SamplingParams into the top-level field`() {
        val messages = listOf(
            node(Role.SYSTEM, "You are a helpful assistant.", "n0"),
            node(Role.USER, "Hello, Claude", "n1"),
        )
        val params = SamplingParams(maxTokens = 4096)

        val body = buildAnthropicRequestBody(messages, params, model = "claude-opus-5")

        // Structured assertion...
        assertEquals(4096, body.getInt("max_tokens"))
        // ...and the literal wire-shape assertion the task calls for.
        assertTrue(
            "expected \"max_tokens\":4096 in the serialized body, got: $body",
            body.toString().contains("\"max_tokens\":4096"),
        )
    }

    @Test fun `model, messages and system are shaped correctly — non-vision baseline`() {
        val messages = listOf(
            node(Role.SYSTEM, "You are a helpful assistant.", "n0"),
            node(Role.USER, "Hello, Claude", "n1"),
            node(Role.ASSISTANT, "Hi there!", "n2"),
            node(Role.USER, "How are you?", "n3"),
        )
        val params = SamplingParams(maxTokens = 4096)

        val body = buildAnthropicRequestBody(messages, params, model = "claude-opus-5")

        assertEquals("claude-opus-5", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("You are a helpful assistant.", body.getString("system"))

        // System turns are excluded from `messages`; only user/assistant remain,
        // in order, each a plain {role, content} string pair (no content-block array).
        val msgs = body.getJSONArray("messages")
        assertEquals(3, msgs.length())

        val expected = listOf(
            "user" to "Hello, Claude",
            "assistant" to "Hi there!",
            "user" to "How are you?",
        )
        expected.forEachIndexed { i, (role, content) ->
            val m = msgs.getJSONObject(i)
            assertEquals(role, m.getString("role"))
            // Baseline (pre-W1) shape: content is a bare string, not a block array.
            assertTrue("messages[$i].content should be a String, was ${m.get("content")::class}", m.get("content") is String)
            assertEquals(content, m.getString("content"))
        }
    }

    @Test fun `no system turn omits the system field entirely`() {
        val messages = listOf(
            node(Role.USER, "Hello, Claude", "n1"),
        )

        val body = buildAnthropicRequestBody(messages, SamplingParams(maxTokens = 1024), model = "claude-opus-5")

        assertFalse(body.has("system"))
        assertEquals(1, body.getJSONArray("messages").length())
    }

    @Test fun `blank-only system turns also omit the field`() {
        val messages = listOf(
            node(Role.SYSTEM, "   ", "n0"),
            node(Role.USER, "Hi", "n1"),
        )

        val body = buildAnthropicRequestBody(messages, SamplingParams(maxTokens = 1024), model = "claude-opus-5")

        assertFalse(body.has("system"))
    }

    @Test fun `multiple system turns are joined with a blank line`() {
        val messages = listOf(
            node(Role.SYSTEM, "First instruction.", "n0"),
            node(Role.SYSTEM, "Second instruction.", "n1"),
            node(Role.USER, "Hi", "n2"),
        )

        val body = buildAnthropicRequestBody(messages, SamplingParams(maxTokens = 1024), model = "claude-opus-5")

        assertEquals("First instruction.\n\nSecond instruction.", body.getString("system"))
    }

    @Test fun `default SamplingParams max_tokens (8192) flows through unchanged`() {
        val body = buildAnthropicRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(),
            model = "claude-opus-5",
        )

        assertEquals(8192, body.getInt("max_tokens"))
    }

    /** Round-trips through [JSONObject]'s own parser to guard against a malformed literal. */
    @Test fun `serialized body is valid JSON`() {
        val body = buildAnthropicRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(maxTokens = 2048),
            model = "claude-opus-5",
        )

        val reparsed = JSONObject(body.toString())
        assertEquals(2048, reparsed.getInt("max_tokens"))
    }
}
