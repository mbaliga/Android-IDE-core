package dev.aarso.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [DraftCodec] — the pure logic behind [SessionStore]'s draft round-trip +
 * keying, factored out precisely so it's testable without a real Context (see the file's doc).
 */
class DraftCodecTest {

    // ---- round-trip ----

    private companion object {
        /** The same key/value separator [DraftCodec] uses internally — named here purely so
         *  the "value contains the separator" tests below read as intentional, not as stray
         *  control characters in a string literal. */
        const val SEP = '\u0001'
    }

    @Test
    fun `empty map round trips`() {
        val encoded = DraftCodec.encode(emptyMap())
        assertEquals(emptySet<String>(), encoded)
        assertEquals(emptyMap<String, String>(), DraftCodec.decode(encoded))
    }

    @Test
    fun `single entry round trips`() {
        val original = mapOf("root-1" to "half-typed message")
        assertEquals(original, DraftCodec.decode(DraftCodec.encode(original)))
    }

    @Test
    fun `multiple entries round trip`() {
        val original = mapOf(
            "root-1" to "first draft",
            "root-2" to "second draft",
            "new" to "not sent yet",
        )
        assertEquals(original, DraftCodec.decode(DraftCodec.encode(original)))
    }

    @Test
    fun `draft text may itself contain the separator character without corrupting the key`() {
        // limit = 2 on decode means only the FIRST separator splits key from value, so a value
        // that happens to contain the separator stays intact rather than truncating the draft.
        val original = mapOf("root-1" to "line one${SEP}line two")
        assertEquals(original, DraftCodec.decode(DraftCodec.encode(original)))
    }

    // ---- "new" key (unstarted conversation) ----

    @Test
    fun `new key round trips like any other key`() {
        val original = mapOf("new" to "typing before the first send")
        assertEquals(original, DraftCodec.decode(DraftCodec.encode(original)))
    }

    @Test
    fun `new key coexists with real root ids`() {
        val original = mapOf("new" to "unstarted draft", "root-42" to "existing conversation draft")
        val decoded = DraftCodec.decode(DraftCodec.encode(original))
        assertEquals("unstarted draft", decoded["new"])
        assertEquals("existing conversation draft", decoded["root-42"])
    }

    // ---- update() semantics (SessionStore.setDraft) ----

    @Test
    fun `update sets a new key`() {
        val next = DraftCodec.update(emptyMap(), "new", "hello")
        assertEquals(mapOf("new" to "hello"), next)
    }

    @Test
    fun `update overwrites an existing key`() {
        val start = mapOf("root-1" to "old text")
        val next = DraftCodec.update(start, "root-1", "new text")
        assertEquals(mapOf("root-1" to "new text"), next)
    }

    @Test
    fun `update with blank text clears the key entirely rather than storing an empty string`() {
        val start = mapOf("root-1" to "some text", "root-2" to "untouched")
        val next = DraftCodec.update(start, "root-1", "")
        assertEquals(mapOf("root-2" to "untouched"), next)
        assertNull(next["root-1"])
    }

    @Test
    fun `update with whitespace-only text also clears the key, not just empty string`() {
        val start = mapOf("root-1" to "some text", "root-2" to "untouched")
        val next = DraftCodec.update(start, "root-1", "   ")
        assertEquals(mapOf("root-2" to "untouched"), next)
        assertNull(next["root-1"])
    }

    @Test
    fun `update leaves other keys untouched`() {
        val start = mapOf("new" to "a", "root-1" to "b", "root-2" to "c")
        val next = DraftCodec.update(start, "root-1", "b2")
        assertEquals(mapOf("new" to "a", "root-1" to "b2", "root-2" to "c"), next)
    }

    // ---- decode tolerance ----

    @Test
    fun `decode skips an entry missing the separator rather than throwing`() {
        assertEquals(emptyMap<String, String>(), DraftCodec.decode(setOf("no-separator-here")))
    }

    @Test
    fun `decode tolerates a mix of well-formed and malformed entries`() {
        val raw = setOf("root-1${SEP}good draft", "malformed", "root-2${SEP}also good")
        val decoded = DraftCodec.decode(raw)
        assertEquals(mapOf("root-1" to "good draft", "root-2" to "also good"), decoded)
    }
}
