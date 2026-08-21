package dev.fonebrew.inference.cloud

import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.SamplingParams
import dev.fonebrew.domain.tree.Attachments
import dev.fonebrew.domain.tree.Conversations
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/**
 * Golden-JSON coverage for [buildOpenAiCompatRequestBody] — the pure body-building
 * function `OpenAiCompatEngine.buildRequest` delegates to (that method itself is
 * `protected` on [CloudEngine] and unreachable from a JVM test that isn't a
 * subclass). W0 fix under test: `max_tokens` must always be sent — previously
 * the body omitted it entirely and every reply relied on the server's default cap.
 */
class OpenAiCompatEngineTest {

    @get:Rule val tmp = TemporaryFolder()

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
        val messages = listOf(node(Role.USER, "Hello", "n1"))
        val params = SamplingParams(maxTokens = 4096)

        val body = buildOpenAiCompatRequestBody(
            messages, params, model = "gpt-5", supportsSampling = true, supportsVision = false,
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
            supportsVision = false,
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
            messages, params, model = "gpt-5", supportsSampling = true, supportsVision = false,
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
            supportsVision = false,
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
            supportsVision = false,
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
            supportsVision = false,
        )

        val reparsed = JSONObject(body.toString())
        assertEquals(3000, reparsed.getInt("max_tokens"))
    }

    /** daily-driver.md W1: vision-on + an attachment on the message → `content` becomes
     *  an `image_url`+`text` block array, image block(s) first, text block last. */
    @Test fun `vision-on message with an attachment becomes a data-URI content block array`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = tmp.newFile("photo.jpg").apply { writeBytes(bytes) }
        val attachmentsJson = Attachments.encode(
            listOf(Attachments.Attachment(path = file.absolutePath, mime = "image/jpeg")),
        )
        val messages = listOf(
            node(
                Role.USER, "What's in this photo?", "n1",
                metadata = mapOf(Conversations.ATTACHMENTS_KEY to attachmentsJson),
            ),
        )

        val body = buildOpenAiCompatRequestBody(
            messages, SamplingParams(), model = "gpt-5",
            supportsSampling = true, supportsVision = true,
        )

        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(2, content.length())

        val imageBlock = content.getJSONObject(0)
        assertEquals("image_url", imageBlock.getString("type"))
        val expectedDataUri =
            "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
        assertEquals(expectedDataUri, imageBlock.getJSONObject("image_url").getString("url"))

        val textBlock = content.getJSONObject(1)
        assertEquals("text", textBlock.getString("type"))
        assertEquals("What's in this photo?", textBlock.getString("text"))
    }

    /** Same node, but the model is vision-blind: `content` stays the plain string it
     *  always was — byte-identical, cache-friendly request. */
    @Test fun `vision-off message with an attachment falls back to a plain string content`() {
        val file = tmp.newFile("photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val attachmentsJson = Attachments.encode(
            listOf(Attachments.Attachment(path = file.absolutePath, mime = "image/jpeg")),
        )
        val messages = listOf(
            node(
                Role.USER, "What's in this photo?", "n1",
                metadata = mapOf(Conversations.ATTACHMENTS_KEY to attachmentsJson),
            ),
        )

        val body = buildOpenAiCompatRequestBody(
            messages, SamplingParams(), model = "gpt-5",
            supportsSampling = true, supportsVision = false,
        )

        val m = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("What's in this photo?", m.getString("content"))
        assertFalse(m.get("content") is org.json.JSONArray)
    }

    /** No attachments at all: `supportsVision=true` alone must not turn `content` into
     *  an array — byte-identical, cache-friendly request when there's nothing to add. */
    @Test fun `vision-on with no attachments stays a plain string`() {
        val body = buildOpenAiCompatRequestBody(
            listOf(node(Role.USER, "Hello", "n1")),
            SamplingParams(), model = "gpt-5",
            supportsSampling = true, supportsVision = true,
        )

        val m = body.getJSONArray("messages").getJSONObject(0)
        assertTrue(m.get("content") is String)
        assertEquals("Hello", m.getString("content"))
    }
}
