package dev.aarso.domain.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM unit tests for [PartialReply]'s org.json codec (daily-driver.md W3). */
class PartialReplyTest {

    @Test
    fun `round trips through encode and decode`() {
        val original = PartialReply(
            userNodeId = "u-1",
            modelId = "local:qwen",
            text = "The answer starts here and is still stream",
            updatedAt = 1_700_000_000_123L,
        )
        val decoded = PartialReply.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `round trips an empty text (checkpoint written before the first token)`() {
        val original = PartialReply(userNodeId = "u-1", modelId = "cloud:claude", text = "", updatedAt = 0L)
        assertEquals(original, PartialReply.decode(original.encode()))
    }

    @Test
    fun `round trips text containing newlines and quotes without corruption`() {
        val original = PartialReply(
            userNodeId = "u-1",
            modelId = "m",
            text = "line one\nline two with \"quotes\" and a backslash \\ here",
            updatedAt = 42L,
        )
        assertEquals(original, PartialReply.decode(original.encode()))
    }

    @Test
    fun `decode returns null for malformed json rather than throwing`() {
        assertNull(PartialReply.decode("not json"))
        assertNull(PartialReply.decode("{broken"))
        assertNull(PartialReply.decode(""))
    }

    @Test
    fun `decode returns null when userNodeId is missing or blank`() {
        assertNull(PartialReply.decode("""{"modelId":"m","text":"t","updatedAt":1}"""))
        assertNull(PartialReply.decode("""{"userNodeId":"","modelId":"m","text":"t","updatedAt":1}"""))
    }

    @Test
    fun `decode tolerates missing modelId, text, and updatedAt with sensible defaults`() {
        val decoded = PartialReply.decode("""{"userNodeId":"u-1"}""")
        assertEquals(PartialReply(userNodeId = "u-1", modelId = "", text = "", updatedAt = 0L), decoded)
    }
}
