package dev.aarso.data

import android.content.Context
import java.io.File
import java.util.UUID

/** Saves generated images to private app storage and lists/deletes them. */
class ImageStore(context: Context) {
    val dir: File = File(context.filesDir, "images").apply { mkdirs() }

    fun save(bytes: ByteArray, ext: String = "png"): String {
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /** A fresh path for a writer (e.g. the native SD engine writes a PNG to it). */
    fun newPath(ext: String = "png"): String = File(dir, "${UUID.randomUUID()}.$ext").absolutePath

    fun list(): List<File> =
        dir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }
}
