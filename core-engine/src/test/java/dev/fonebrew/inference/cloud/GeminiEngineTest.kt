package dev.fonebrew.inference.cloud

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.cloud.Source
import dev.fonebrew.domain.tree.Attachments
import dev.fonebrew.domain.tree.Conversations
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Golden-JSON coverage for [buildGeminiRequestBody] — the pure body-building
 * function `GeminiEngine.buildRequest` delegates to (that method itself is
 * `protected` on [CloudEngine] and unreachable from a JVM test that isn't a
 * subclass). W0 fix under test: `generationConfig.maxOutputTokens` must always
 * be sent — previously the body carried no output-token cap at all and every
 * reply relied on the server's default.
 *
 * Baseline (pre-W1) coverage below calls the builder without [Boolean]
 * `supportsVision`, so it defaults to `false` and every `parts` array stays a
 * single `{"text":...}` entry. W1 vision `parts`-array cases are at the bottom.
 */
class GeminiEngineTest {

    private fun node(
        role: Role,
        content: String,
        id: String,
        metadata: Map<String, String> = emptyMap(),
    ) = MessageNode(
        id = id,
        parentId = null,
        role = role,
        content = content,
        createdAt = 0L,
        metadata = metadata,
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

    // --- W1: vision content blocks -----------------------------------------------------

    /** A real temp file so `partsFor`'s `File(path).readBytes()` has something to read. */
    private fun tempImageFile(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File.createTempFile("attachment", ".jpg").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    @Test fun `attachment plus text on a vision-capable model becomes an inline_data-then-text parts array`() {
        val imageBytes = byteArrayOf(-1, -40, -1, -32, 1, 2, 3) // arbitrary bytes, not a real JPEG
        val file = tempImageFile(imageBytes)
        val attachmentsJson = Attachments.encode(
            listOf(Attachments.Attachment(path = file.absolutePath, mime = "image/jpeg")),
        )
        val messages = listOf(
            node(
                Role.USER,
                "What's in this photo?",
                "n1",
                metadata = mapOf(Conversations.ATTACHMENTS_KEY to attachmentsJson),
            ),
        )

        val body = buildGeminiRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            supportsSampling = true,
            supportsVision = true,
        )

        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())

        // inline_data part first.
        val inlineDataPart = parts.getJSONObject(0)
        assertTrue(inlineDataPart.has("inline_data"))
        val inlineData = inlineDataPart.getJSONObject("inline_data")
        assertEquals("image/jpeg", inlineData.getString("mime_type"))
        assertEquals(Base64.getEncoder().encodeToString(imageBytes), inlineData.getString("data"))
        // The data field itself is bare base64 — no "data:image/jpeg;base64," prefix.
        assertFalse(inlineData.getString("data").startsWith("data:"))

        // Trailing text part last.
        val textPart = parts.getJSONObject(1)
        assertEquals("What's in this photo?", textPart.getString("text"))
    }

    @Test fun `attachment on a vision-blind model falls back to a plain text-only part, silently`() {
        val file = tempImageFile()
        val attachmentsJson = Attachments.encode(
            listOf(Attachments.Attachment(path = file.absolutePath, mime = "image/jpeg")),
        )
        val messages = listOf(
            node(
                Role.USER,
                "What's in this photo?",
                "n1",
                metadata = mapOf(Conversations.ATTACHMENTS_KEY to attachmentsJson),
            ),
        )

        val body = buildGeminiRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            supportsSampling = true,
            supportsVision = false,
        )

        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(1, parts.length())
        assertFalse("text-only part should not carry inline_data", parts.getJSONObject(0).has("inline_data"))
        assertEquals("What's in this photo?", parts.getJSONObject(0).getString("text"))
    }

    @Test fun `no attachments plus supportsVision=true stays a single text part — cache-friendly`() {
        val messages = listOf(node(Role.USER, "Hello", "n1"))

        val body = buildGeminiRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            supportsSampling = true,
            supportsVision = true,
        )

        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals("Hello", parts.getJSONObject(0).getString("text"))
    }

    // --- W2: web search --------------------------------------------------------------------

    @Test fun `webSearchEnabled=true adds the google_search tool with the correct shape`() {
        val messages = listOf(node(Role.USER, "What happened in the news today?", "n1"))

        val body = buildGeminiRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            supportsSampling = true,
            webSearchEnabled = true,
        )

        assertTrue(body.has("tools"))
        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertTrue(tool.has("google_search"))
        assertEquals(0, tool.getJSONObject("google_search").length())
    }

    @Test fun `webSearchEnabled=false omits tools entirely — byte-identical to a pre-W2 request`() {
        val messages = listOf(node(Role.USER, "Hello", "n1"))

        val withDefault = buildGeminiRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            supportsSampling = true,
        )
        val withExplicitFalse = buildGeminiRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            supportsSampling = true,
            webSearchEnabled = false,
        )

        assertFalse(withDefault.has("tools"))
        assertFalse(withExplicitFalse.has("tools"))
        assertEquals(withDefault.toString(), withExplicitFalse.toString())
    }

    @Test fun `geminiSourcesOf parses a realistic groundingChunks payload`() {
        val data = """
            {
              "candidates": [
                {
                  "content": { "role": "model", "parts": [ { "text": "Here's what happened." } ] },
                  "groundingMetadata": {
                    "webSearchQueries": [ "news today" ],
                    "groundingChunks": [
                      { "web": { "uri": "https://example.com/a", "title": "Example A" } },
                      { "web": { "uri": "https://example.com/b", "title": "Example B" } }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val sources = geminiSourcesOf(null, data)

        assertEquals(
            listOf(
                Source(title = "Example A", url = "https://example.com/a"),
                Source(title = "Example B", url = "https://example.com/b"),
            ),
            sources,
        )
    }

    @Test fun `geminiSourcesOf falls back to the older groundingAttributions shape`() {
        val data = """
            {
              "candidates": [
                {
                  "groundingMetadata": {
                    "groundingAttributions": [
                      { "web": { "url": "https://example.com/c", "title": "Example C" } }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val sources = geminiSourcesOf(null, data)

        assertEquals(listOf(Source(title = "Example C", url = "https://example.com/c")), sources)
    }

    @Test fun `geminiSourcesOf falls back to the url as title when no title is present`() {
        val data = """
            {
              "candidates": [
                {
                  "groundingMetadata": {
                    "groundingChunks": [ { "web": { "uri": "https://example.com/d" } } ]
                  }
                }
              ]
            }
        """.trimIndent()

        val sources = geminiSourcesOf(null, data)

        assertEquals(listOf(Source(title = "https://example.com/d", url = "https://example.com/d")), sources)
    }

    @Test fun `geminiSourcesOf returns null for a plain text chunk with no groundingMetadata`() {
        val data = """
            {
              "candidates": [
                { "content": { "role": "model", "parts": [ { "text": "hi" } ] } }
              ]
            }
        """.trimIndent()

        assertEquals(null, geminiSourcesOf(null, data))
    }

    @Test fun `geminiSourcesOf returns null when there are no candidates at all`() {
        assertEquals(null, geminiSourcesOf(null, """{"candidates":[]}"""))
        assertEquals(null, geminiSourcesOf(null, """{}"""))
    }

    @Test fun `geminiSourcesOf falls back to an empty list rather than throwing on a malformed shape`() {
        val data = """
            {
              "candidates": [
                { "groundingMetadata": { "webSearchQueries": [ "news today" ] } }
              ]
            }
        """.trimIndent()

        assertEquals(emptyList<Source>(), geminiSourcesOf(null, data))
    }
}
