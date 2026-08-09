package dev.aarso.domain.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageUriMapperTest {

    private val root = "file:///storage/emulated/0/Fonebrew/workspaces/my-project"

    @Test
    fun `a simple nested path round-trips exactly`() {
        val uri = LanguageUriMapper.toLspUri(root, "src/main/App.kt")
        assertEquals("$root/src/main/App.kt", uri)
        assertEquals("src/main/App.kt", LanguageUriMapper.toWorkspaceRelativePath(root, uri))
    }

    @Test
    fun `a path segment containing a space is percent-encoded, and decodes back to the exact original`() {
        val uri = LanguageUriMapper.toLspUri(root, "My Documents/notes file.md")
        assertTrue("%20" in uri)
        assertTrue(" " !in uri)
        assertEquals("My Documents/notes file.md", LanguageUriMapper.toWorkspaceRelativePath(root, uri))
    }

    @Test
    fun `a path segment containing non-ASCII characters round-trips exactly`() {
        val original = "café/résumé.py"
        val uri = LanguageUriMapper.toLspUri(root, original)
        assertTrue("é" !in uri) // must be percent-encoded, not left raw
        assertEquals(original, LanguageUriMapper.toWorkspaceRelativePath(root, uri))
    }

    @Test
    fun `a URI outside the workspace root has no workspace-relative path`() {
        try {
            LanguageUriMapper.toWorkspaceRelativePath(root, "file:///storage/emulated/0/Fonebrew/workspaces/other-project/App.kt")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `dot and dot-dot segments are rejected -- no escaping the workspace root`() {
        try {
            LanguageUriMapper.toLspUri(root, "../outside.txt")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            LanguageUriMapper.toLspUri(root, "./same-dir.txt")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `a leading slash on the relative path is rejected -- it is not actually relative`() {
        try {
            LanguageUriMapper.toLspUri(root, "/etc/passwd")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }
}
