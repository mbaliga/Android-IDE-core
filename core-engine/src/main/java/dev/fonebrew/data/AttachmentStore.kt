package dev.fonebrew.data

import android.content.Context
import android.net.Uri
import dev.fonebrew.domain.tree.Attachments.Attachment
import java.io.File
import java.util.UUID

/**
 * Saves user-node attachments (daily-driver.md W1 — vision input) to private app storage
 * and lists/deletes them. Copies [ImageStore]'s exact shape — `filesDir/attachments/<uuid>.<ext>`
 * naming, same save()/newPath()/list()/delete() API — plus [saveFromUri] since attachments
 * arrive from a gallery picker or camera content Uri, not raw generator bytes. Nodes are
 * never deleted (append-only tree), so orphan-file cleanup is a non-problem, same as ImageStore.
 */
class AttachmentStore(private val context: Context) {
    val dir: File = File(context.filesDir, "attachments").apply { mkdirs() }

    fun save(bytes: ByteArray, ext: String = "jpg"): String {
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /** A fresh path for a writer (e.g. the downscale/re-encode step writes a JPEG to it). */
    fun newPath(ext: String = "jpg"): String = File(dir, "${UUID.randomUUID()}.$ext").absolutePath

    /**
     * Copies [uri]'s bytes into the store (SAF gallery pick or camera `FileProvider` Uri),
     * returning the saved [Attachment] (absolute path + resolved mime type), or null if the
     * Uri couldn't be read.
     */
    fun saveFromUri(uri: Uri): Attachment? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val path = save(bytes, extensionFor(mime))
        return Attachment(path = path, mime = mime)
    }

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/jpeg" -> "jpg"
        else -> "jpg"
    }
}
