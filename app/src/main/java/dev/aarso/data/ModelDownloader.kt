package dev.aarso.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads a GGUF over HTTPS into [LocalModelStore]'s directory (handoff §0: a
 * user-initiated model download is sanctioned egress). Streams progress; writes to
 * a .part file and renames on success so a partial download is never mistaken for
 * a usable model.
 *
 * Interrupted downloads resume: the .part is kept on any failure and a retry asks
 * the server for the remainder via a Range request ([DownloadResume] decides how
 * to treat the answer). The .part is deleted only on explicit user cancel.
 */
class ModelDownloader(
    private val targetDir: File,
    private val onSaved: () -> Unit,
) {

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val done: Boolean = false,
        val error: String? = null,
        /** Non-zero when this run continued an interrupted .part. */
        val resumedFrom: Long = 0,
    ) {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun partFile(fileName: String): File = File(targetDir, "$fileName.part")

    /** Explicit user cancel is the one moment the partial file is discarded. */
    fun discardPart(fileName: String) {
        runCatching { partFile(fileName).delete() }
    }

    /**
     * Download [url] into a file named [fileName]. Emits progress; the terminal
     * emission has [Progress.done] true (success) or [Progress.error] set. Cancel
     * by cancelling collection — the .part file is left for a future resume.
     */
    fun download(url: String, fileName: String): Flow<Progress> = flow {
        val target = File(targetDir, fileName)
        val part = partFile(fileName)
        val partBytes = if (part.exists()) part.length() else 0L
        val plan = DownloadResume.plan(partBytes)

        val request = try {
            Request.Builder().url(url)
                .apply { plan.rangeHeader?.let { header("Range", it) } }
                .build()
        } catch (e: Exception) {
            emit(Progress(0, 0, error = "bad URL: ${e.message}"))
            return@flow
        }

        try {
            client.newCall(request).execute().use { response ->
                val outcome = DownloadResume.interpret(
                    response.code,
                    response.header("Content-Range"),
                    partBytes,
                )
                if (outcome.error != null) {
                    emit(Progress(partBytes, 0, error = outcome.error)) // .part kept
                    return@use
                }
                if (outcome.alreadyComplete) {
                    if (part.renameTo(target)) {
                        onSaved()
                        emit(Progress(partBytes, partBytes, done = true, resumedFrom = partBytes))
                    } else {
                        emit(Progress(partBytes, partBytes, error = "could not finalize file"))
                    }
                    return@use
                }

                val body = response.body ?: run {
                    emit(Progress(partBytes, 0, error = "empty response"))
                    return@use
                }
                val resumedFrom = outcome.startAt
                val append = resumedFrom > 0
                var downloaded = resumedFrom
                val total = body.contentLength().let { if (it >= 0) it + resumedFrom else it }
                body.byteStream().use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buf = ByteArray(1 shl 16)
                        var lastEmit = downloaded
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            // Throttle emissions to ~every 2 MB to avoid flooding.
                            if (downloaded - lastEmit >= 2_000_000L) {
                                lastEmit = downloaded
                                emit(Progress(downloaded, total, resumedFrom = resumedFrom))
                            }
                        }
                    }
                }
                if (part.renameTo(target)) {
                    onSaved()
                    emit(Progress(downloaded, total, done = true, resumedFrom = resumedFrom))
                } else {
                    emit(Progress(downloaded, total, error = "could not finalize file"))
                }
            }
        } catch (e: Exception) {
            // Never let a network/IO failure crash the app — surface it. The .part
            // stays on disk so a retry resumes instead of starting over.
            emit(Progress(partBytes, 0, error = e.message ?: "download failed"))
        }
    }.flowOn(Dispatchers.IO)
}
