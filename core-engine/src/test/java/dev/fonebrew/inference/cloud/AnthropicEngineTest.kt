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
 * Golden-JSON coverage for [buildAnthropicRequestBody] — the pure body-building
 * function `AnthropicEngine.buildRequest` delegates to (that method itself is
 * `protected` on [CloudEngine] and unreachable from a JVM test).
 *
 * Baseline (pre-W1) coverage: role/content are plain strings, no image blocks —
 * these cases all call the builder without [Boolean] `supportsVision`, so it
 * defaults to `false` and the shape is untouched. W1 vision content-block cases
 * are below.
 */
class AnthropicEngineTest {

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

    // --- W1: vision content blocks -----------------------------------------------------

    /** A real temp file so `contentOf`'s `File(path).readBytes()` has something to read. */
    private fun tempImageFile(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File.createTempFile("attachment", ".jpg").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    @Test fun `attachment plus text on a vision-capable model becomes an image-then-text block array`() {
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

        val body = buildAnthropicRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            model = "claude-opus-5",
            supportsVision = true,
        )

        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(2, content.length())

        // Image block first.
        val imageBlock = content.getJSONObject(0)
        assertEquals("image", imageBlock.getString("type"))
        val source = imageBlock.getJSONObject("source")
        assertEquals("base64", source.getString("type"))
        assertEquals("image/jpeg", source.getString("media_type"))
        assertEquals(Base64.getEncoder().encodeToString(imageBytes), source.getString("data"))
        // The data field itself is bare base64 — no "data:image/jpeg;base64," prefix.
        assertFalse(source.getString("data").startsWith("data:"))

        // Trailing text block last.
        val textBlock = content.getJSONObject(1)
        assertEquals("text", textBlock.getString("type"))
        assertEquals("What's in this photo?", textBlock.getString("text"))
    }

    @Test fun `attachment on a vision-blind model falls back to a plain string, silently`() {
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

        val body = buildAnthropicRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            model = "claude-3-blind-model",
            supportsVision = false,
        )

        val m = body.getJSONArray("messages").getJSONObject(0)
        assertTrue("content should fall back to a plain String, was ${m.get("content")::class}", m.get("content") is String)
        assertEquals("What's in this photo?", m.getString("content"))
    }

    @Test fun `no attachments plus supportsVision=true stays a plain string — cache-friendly`() {
        val messages = listOf(node(Role.USER, "Hello, Claude", "n1"))

        val body = buildAnthropicRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            model = "claude-opus-5",
            supportsVision = true,
        )

        val m = body.getJSONArray("messages").getJSONObject(0)
        assertTrue(m.get("content") is String)
        assertEquals("Hello, Claude", m.getString("content"))
    }

    // --- W2: web search ------------------------------------------------------------------

    @Test fun `webSearchEnabled=true adds the web_search tool with the correct shape`() {
        val messages = listOf(node(Role.USER, "What happened in the news today?", "n1"))

        val body = buildAnthropicRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            model = "claude-opus-5",
            webSearchEnabled = true,
        )

        assertTrue(body.has("tools"))
        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertEquals("web_search_20260209", tool.getString("type"))
        assertEquals("web_search", tool.getString("name"))
        assertEquals(3, tool.getInt("max_uses"))
    }

    @Test fun `webSearchEnabled=false omits tools entirely — byte-identical to a pre-W2 request`() {
        val messages = listOf(node(Role.USER, "Hello, Claude", "n1"))

        val withDefault = buildAnthropicRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            model = "claude-opus-5",
        )
        val withExplicitFalse = buildAnthropicRequestBody(
            messages,
            SamplingParams(maxTokens = 1024),
            model = "claude-opus-5",
            webSearchEnabled = false,
        )

        assertFalse(withDefault.has("tools"))
        assertFalse(withExplicitFalse.has("tools"))
        assertEquals(withDefault.toString(), withExplicitFalse.toString())
    }

    @Test fun `anthropicSourcesOf parses a realistic web_search_tool_result content_block_start`() {
        val data = """
            {
              "type": "content_block_start",
              "index": 1,
              "content_block": {
                "type": "web_search_tool_result",
                "tool_use_id": "srvtoolu_01",
                "content": [
                  {
                    "type": "web_search_result",
                    "url": "https://example.com/a",
                    "title": "Example A",
                    "encrypted_content": "abc123",
                    "page_age": "2 days ago"
                  },
                  {
                    "type": "web_search_result",
                    "url": "https://example.com/b",
                    "title": "Example B"
                  }
                ]
              }
            }
        """.trimIndent()

        val sources = anthropicSourcesOf("content_block_start", data)

        assertEquals(
            listOf(
                Source(title = "Example A", url = "https://example.com/a"),
                Source(title = "Example B", url = "https://example.com/b"),
            ),
            sources,
        )
    }

    @Test fun `anthropicSourcesOf returns null for events that aren't web search results`() {
        assertEquals(
            null,
            anthropicSourcesOf(
                "content_block_delta",
                """{"type":"content_block_delta","delta":{"text":"hi"}}""",
            ),
        )
        assertEquals(
            null,
            anthropicSourcesOf(
                "content_block_start",
                """{"type":"content_block_start","content_block":{"type":"text"}}""",
            ),
        )
    }

    @Test fun `anthropicSourcesOf falls back to an empty list rather than throwing on a malformed shape`() {
        val data = """
            {
              "type": "content_block_start",
              "content_block": { "type": "web_search_tool_result" }
            }
        """.trimIndent()

        assertEquals(emptyList<Source>(), anthropicSourcesOf("content_block_start", data))
    }

    @Test fun `anthropicIsPaused is true for a pause_turn message_delta`() {
        val data = """{"type":"message_delta","delta":{"stop_reason":"pause_turn"},"usage":{"output_tokens":42}}"""

        assertTrue(anthropicIsPaused("message_delta", data))
    }

    @Test fun `anthropicIsPaused is false for a normal end_turn message_delta`() {
        val data = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}"""

        assertFalse(anthropicIsPaused("message_delta", data))
    }

    @Test fun `anthropicIsPaused is false for events that aren't message_delta`() {
        assertFalse(anthropicIsPaused("message_stop", "{}"))
        assertFalse(anthropicIsPaused(null, "{}"))
    }
}
