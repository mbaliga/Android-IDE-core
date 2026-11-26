package dev.fonebrew.data.search.semantic

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EmbeddingModelFile(val file: File) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
}

sealed interface ImportResult {
    data class Imported(val model: EmbeddingModelFile) : ImportResult
    data class Rejected(val reason: String) : ImportResult
}

/**
 * Embedding GGUFs live in their own directory, not in [dev.fonebrew.data.LocalModelStore]: that
 * store feeds the chat-model picker, where an embedding model could be chosen and then cannot chat.
 * Nothing here touches the network; downloads are started only by an explicit user action elsewhere.
 */
class EmbeddingModelStore(val dir: File) {

    /** file name -> expected sha256, for downloads the user just started (only these are checked). */
    private val expected = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Set when a finished download failed its checksum and was deleted. */
    val verifyFailure = MutableStateFlow<String?>(null)

    private val _models = MutableStateFlow(scan())
    val models: StateFlow<List<EmbeddingModelFile>> = _models.asStateFlow()

    init { dir.mkdirs() }

    fun refresh() { _models.value = scan() }

    fun fileFor(name: String): File = File(dir, name)

    fun delete(name: String) {
        fileFor(name).delete()
        refresh()
    }

    /** Copy a user-picked file in. Validates the name and the GGUF magic; refuses anything else. */
    fun importFrom(displayName: String, input: InputStream): ImportResult {
        val base = displayName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isEmpty() || base.startsWith(".") || !base.endsWith(".gguf", ignoreCase = true)) {
            return ImportResult.Rejected("Not a .gguf file")
        }
        dir.mkdirs()
        val part = File(dir, "$base.part")
        try {
            input.use { src ->
                val magic = ByteArray(4)
                var n = 0
                while (n < 4) {
                    val r = src.read(magic, n, 4 - n)
                    if (r < 0) break
                    n += r
                }
                if (n < 4 || String(magic, Charsets.US_ASCII) != "GGUF") return ImportResult.Rejected("Not a GGUF model file")
                part.outputStream().use { out ->
                    out.write(magic)
                    src.copyTo(out)
                }
            }
            val target = File(dir, base)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) return ImportResult.Rejected("Could not save the file")
            refresh()
            return ImportResult.Imported(EmbeddingModelFile(target))
        } catch (e: Exception) {
            return ImportResult.Rejected("Could not read the file")
        } finally {
            part.delete()
        }
    }

    fun expectVerification(fileName: String, sha256: String) { expected[fileName] = sha256 }

    /** Called after any download finishes: checks the files the user just asked for, deleting a mismatch. */
    fun afterDownload() {
        for ((name, sha) in expected.entries.toList()) {
            val f = fileFor(name)
            if (!f.isFile) continue
            expected.remove(name)
            if (!verifySha256(f, sha)) {
                f.delete()
                verifyFailure.value = "$name failed its checksum and was deleted"
            }
        }
        refresh()
    }

    /** True when [file]'s SHA-256 equals [expected] (hex, any case). */
    fun verifySha256(file: File, expected: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = s.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }

    private fun scan(): List<EmbeddingModelFile> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }?.sortedBy { it.name }?.map { EmbeddingModelFile(it) } ?: emptyList()
}
