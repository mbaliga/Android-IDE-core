package dev.fonebrew.data.search.semantic

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddingModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun store() = EmbeddingModelStore(java.io.File(tmp.root, "embedding-models"))
    private fun gguf(extra: String = "payload") = ByteArrayInputStream(("GGUF" + extra).toByteArray())

    @Test fun `imports a gguf and lists it`() {
        val s = store()
        val r = s.importFrom("my-model.gguf", gguf())
        assertTrue(r is ImportResult.Imported)
        assertEquals(listOf("my-model.gguf"), s.models.value.map { it.name })
    }

    @Test fun `refuses non-gguf names and bad magic, leaving nothing behind`() {
        val s = store()
        assertTrue(s.importFrom("notes.txt", gguf()) is ImportResult.Rejected)
        assertTrue(s.importFrom("fake.gguf", ByteArrayInputStream("NOPE-not-gguf".toByteArray())) is ImportResult.Rejected)
        assertTrue(s.importFrom("short.gguf", ByteArrayInputStream("GG".toByteArray())) is ImportResult.Rejected)
        assertTrue(s.models.value.isEmpty())
        assertTrue(s.dir.listFiles()!!.isEmpty())
    }

    @Test fun `path components in the display name cannot escape the directory`() {
        val s = store()
        val r = s.importFrom("../../evil.gguf", gguf()) as ImportResult.Imported
        assertEquals("evil.gguf", r.model.name)
        assertEquals(s.dir.canonicalPath, r.model.file.parentFile!!.canonicalPath)
        assertTrue(s.importFrom(".hidden.gguf", gguf()) is ImportResult.Rejected)
    }

    @Test fun `importing the same name replaces the file`() {
        val s = store()
        s.importFrom("m.gguf", gguf("one"))
        s.importFrom("m.gguf", gguf("two-longer"))
        assertEquals(1, s.models.value.size)
        assertEquals("GGUFtwo-longer", s.fileFor("m.gguf").readText())
    }

    @Test fun `delete removes the file`() {
        val s = store()
        s.importFrom("m.gguf", gguf())
        s.delete("m.gguf")
        assertTrue(s.models.value.isEmpty())
    }

    @Test fun `checksum verification deletes a mismatching expected download and keeps others`() {
        val s = store()
        s.importFrom("dl.gguf", gguf("abc")); s.importFrom("mine.gguf", gguf("mine"))
        s.expectVerification("dl.gguf", "00".repeat(32))
        s.afterDownload()
        assertFalse(s.fileFor("dl.gguf").exists())
        assertTrue(s.fileFor("mine.gguf").exists())
        assertTrue(s.verifyFailure.value!!.contains("dl.gguf"))
    }

    @Test fun `checksum verification keeps a matching download`() {
        val s = store()
        s.importFrom("dl.gguf", gguf("abc"))
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest("GGUFabc".toByteArray()).joinToString("") { "%02x".format(it) }
        s.expectVerification("dl.gguf", sha)
        s.afterDownload()
        assertTrue(s.fileFor("dl.gguf").exists())
        assertEquals(null, s.verifyFailure.value)
    }

    @Test fun `catalog entry is the verified one and carries a license`() {
        val e = EmbeddingModelCatalog.entries.single()
        assertEquals(84_106_624L, e.sizeBytes)
        assertTrue(e.url.startsWith("https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/"))
        assertTrue(e.url.endsWith(e.fileName))
        assertEquals("Apache-2.0", e.license)
        assertEquals(64, e.sha256.length)
    }
}
