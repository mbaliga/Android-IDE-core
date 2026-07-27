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
 * Golden-JSON coverage for [buildGeminiRequestBody] — the pure body-building
 * function `GeminiEngine.buildRequest` delegates to (that method itself is
 * `protected` on [CloudEngine] and unreachable from a JVM test that isn't a
 * subclass). W0 fix under test: `generationConfig.maxOutputTokens` must always
 * be sent — previously the body carried no output-token cap at all and every
 * reply relied on the server's default.
 */
class GeminiEngineTest {

    private fun node(role: Role, content: String, id: String) = MessageNode(
        id = id,
        parentId = null,
        role = role,
        content = content,
        createdAt = 0L,
    )

    @Test fun `maxOutputTokens flows from SamplingParams into generationConfig`() {
        val messages = listOf(node(Role.USER, "Hello", "n1"))
        val params = SamplingParams(maxTokens = 4096)

        val body = buildGeminiRequestBody(messages, params, supportsSampling = true)

        // Structured assertion...
        val generationConfig = body.getJSONObject("generationConfig")
        assertEquals(4096, generationConfig.getInt("maxOutputTokens"))
        // ...and the literal wire-shape assertion the task calls for.
        assertTrue(
            "expected \"maxOutputTokens\":4096 nested under generationConfig, got: $body",
            body.toString().contains("\"maxOutputTokens\":4096"),
        )
    }

    @Test fun `default SamplingParams maxOutputTokens (8192) flows through unchanged`() {
        val body = buildGeminiRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(),
            supportsSampling = true,
        )

        assertEquals(8192, body.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test fun `maxOutputTokens is sent even when the provider kind rejects sampling knobs`() {
        val body = buildGeminiRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(maxTokens = 1024),
            supportsSampling = false,
        )

        val generationConfig = body.getJSONObject("generationConfig")
        assertEquals(1024, generationConfig.getInt("maxOutputTokens"))
        assertFalse(generationConfig.has("temperature"))
        assertFalse(generationConfig.has("topP"))
    }

    @Test fun `sampling knobs land inside the same generationConfig object, not a duplicate`() {
        val body = buildGeminiRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(maxTokens = 2048, temperature = 0.5f, topP = 0.9f),
            supportsSampling = true,
        )

        // Only one generationConfig key exists in the body...
        val keyCount = body.keys().asSequence().count { it == "generationConfig" }
        assertEquals(1, keyCount)
        // ...and it carries all three fields together.
        val generationConfig = body.getJSONObject("generationConfig")
        assertEquals(2048, generationConfig.getInt("maxOutputTokens"))
        assertEquals(0.5, generationConfig.getDouble("temperature"), 0.0001)
        assertEquals(0.9, generationConfig.getDouble("topP"), 0.0001)
    }

    @Test fun `contents and systemInstruction are shaped correctly`() {
        val messages = listOf(
            node(Role.SYSTEM, "You are a helpful assistant.", "n0"),
            node(Role.USER, "Hello", "n1"),
            node(Role.ASSISTANT, "Hi there!", "n2"),
            node(Role.USER, "How are you?", "n3"),
        )
        val params = SamplingParams(maxTokens = 2048)

        val body = buildGeminiRequestBody(messages, params, supportsSampling = true)

        // System turns are excluded from `contents`; roles map user->"user", assistant->"model".
        val contents = body.getJSONArray("contents")
        assertEquals(3, contents.length())
        val expected = listOf(
            "user" to "Hello",
            "model" to "Hi there!",
            "user" to "How are you?",
        )
        expected.forEachIndexed { i, (role, text) ->
            val c = contents.getJSONObject(i)
            assertEquals(role, c.getString("role"))
            assertEquals(text, c.getJSONArray("parts").getJSONObject(0).getString("text"))
        }

        assertEquals(
            "You are a helpful assistant.",
            body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"),
        )
    }

    @Test fun `no system turn omits systemInstruction entirely`() {
        val body = buildGeminiRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(maxTokens = 1024),
            supportsSampling = true,
        )

        assertFalse(body.has("systemInstruction"))
    }

    /** Round-trips through [JSONObject]'s own parser to guard against a malformed literal. */
    @Test fun `serialized body is valid JSON`() {
        val body = buildGeminiRequestBody(
            listOf(node(Role.USER, "Hi", "n1")),
            SamplingParams(maxTokens = 3000),
            supportsSampling = true,
        )

        val reparsed = JSONObject(body.toString())
        assertEquals(3000, reparsed.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }
}
