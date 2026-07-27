package dev.aarso.inference.cloud

import dev.aarso.domain.MessageNode
import dev.aarso.domain.Role
import dev.aarso.domain.SamplingParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-JSON coverage for [buildOpenAiCompatRequestBody] — the pure body-building
 * function `OpenAiCompatEngine.buildRequest` delegates to (that method itself is
 * `protected` on [CloudEngine] and unreachable from a JVM test that isn't a
 * subclass). W0 fix under test: `max_tokens` must always be sent — previously
 * the body omitted it entirely and every reply relied on the server's default cap.
 */
class OpenAiCompatEngineTest {

    private fun node(role: Role, content: String, id: String) = MessageNode(
        id = id,
        parentId = null,
        role = role,
        content = content,
        createdAt = 0L,
    )

    @Test fun `max_tokens flows from SamplingParams into the top-level field`() {
        val messages = listOf(node(Role.USER, "Hello", "n1"))
        val params = SamplingParams(maxTokens = 4096)

        val body = buildOpenAiCompatRequestBody(
            messages, params, model = "gpt-5", supportsSampling = true,
        )

        // Structured assertion...
        assertEquals(4096, body.getInt("max_tokens"))
        // ...and the literal wire-shape assertion the task calls for.
        assertTrue(
            "expected \"max_tokens\":4096 in the serialized body, got: $body",
            body.toString().contains("\"max_tokens\":4096"),
        )
    }

    @Test fun `default SamplingParams max_tokens (8192) flows through unchanged`() {
        val body = buildOpenAiCompatRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(),
            model = "gpt-5",
            supportsSampling = true,
        )

        assertEquals(8192, body.getInt("max_tokens"))
    }

    @Test fun `model, messages and stream are shaped correctly`() {
        val messages = listOf(
            node(Role.SYSTEM, "You are a helpful assistant.", "n0"),
            node(Role.USER, "Hello", "n1"),
            node(Role.ASSISTANT, "Hi there!", "n2"),
            node(Role.USER, "How are you?", "n3"),
        )
        val params = SamplingParams(maxTokens = 2048)

        val body = buildOpenAiCompatRequestBody(
            messages, params, model = "gpt-5", supportsSampling = true,
        )

        assertEquals("gpt-5", body.getString("model"))
        assertTrue(body.getBoolean("stream"))

        // Unlike Anthropic, system turns stay inline in `messages` here.
        val msgs = body.getJSONArray("messages")
        assertEquals(4, msgs.length())
        val expected = listOf(
            "system" to "You are a helpful assistant.",
            "user" to "Hello",
            "assistant" to "Hi there!",
            "user" to "How are you?",
        )
        expected.forEachIndexed { i, (role, content) ->
            val m = msgs.getJSONObject(i)
            assertEquals(role, m.getString("role"))
            assertEquals(content, m.getString("content"))
        }
    }

    @Test fun `sampling knobs are sent when the provider kind supports them`() {
        val body = buildOpenAiCompatRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(maxTokens = 1024, temperature = 0.5f, topP = 0.9f),
            model = "gpt-5",
            supportsSampling = true,
        )

        assertEquals(0.5, body.getDouble("temperature"), 0.0001)
        assertEquals(0.9, body.getDouble("top_p"), 0.0001)
        // max_tokens is unconditional, unlike the sampling knobs.
        assertEquals(1024, body.getInt("max_tokens"))
    }

    @Test fun `sampling knobs are omitted when the provider kind rejects them, max_tokens still sent`() {
        val body = buildOpenAiCompatRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(maxTokens = 1024),
            model = "gpt-5",
            supportsSampling = false,
        )

        assertTrue(!body.has("temperature"))
        assertTrue(!body.has("top_p"))
        assertEquals(1024, body.getInt("max_tokens"))
    }

    /** Round-trips through [JSONObject]'s own parser to guard against a malformed literal. */
    @Test fun `serialized body is valid JSON`() {
        val body = buildOpenAiCompatRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(maxTokens = 3000),
            model = "gpt-5",
            supportsSampling = true,
        )

        val reparsed = JSONObject(body.toString())
        assertEquals(3000, reparsed.getInt("max_tokens"))
    }
}
