package dev.aarso.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Full-flavor only: downloads an APK from the user's own Git host and fires an
 * `ACTION_INSTALL_PACKAGE` intent via [FileProvider]. The play flavor ships a no-op
 * stub — Play forbids `REQUEST_INSTALL_PACKAGES`.
 *
 * Downloads go ONLY to URLs that came from [BuildsRepo]/[BuildsApi], which always
 * point to the user's own configured host. There is no third-party CDN involved.
 *
 * The download is a suspend function that emits [Progress] values via a progress
 * callback — same pattern as [ModelDownloader] (2 MB throttle, .part file, rename on
 * success, keep .part on failure for future resume).
 *
 * Runtime behaviour is owner-verified — there is no device in CI.
 */
class ApkInstaller(private val context: Context) {

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val done: Boolean = false,
        val error: String? = null,
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val installDir: File
        get() = File(context.cacheDir, "apk-install").also { it.mkdirs() }

    /**
     * Download the APK at [url] (must be a URL returned by [BuildsRepo.findApkUrl]) and
     * fire an install intent when complete. [onProgress] is called on the main thread as
     * bytes arrive; the final call will have [Progress.done] = true or [Progress.error]
     * set. Cancellation is cooperative: cancel the calling coroutine to stop the download
     * (the .part file is left for a future resume).
     */
    suspend fun downloadAndInstall(
        url: String,
        fileName: String,
        onProgress: suspend (Progress) -> Unit,
    ) {
        val target = File(installDir, fileName)
        val part = File(installDir, "$fileName.part")
        val partBytes = if (part.exists()) part.length() else 0L

        val request = try {
            Request.Builder().url(url)
                .apply { if (partBytes > 0) header("Range", "bytes=$partBytes-") }
                .build()
        } catch (e: Exception) {
            onProgress(Progress(0L, 0L, error = "bad URL: ${e.message}"))
            return
        }

        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code !in 200..299) {
                        onProgress(Progress(partBytes, 0L, error = "HTTP ${response.code}"))
                        return@withContext
                    }
                    val body = response.body ?: run {
                        onProgress(Progress(partBytes, 0L, error = "empty response"))
                        return@withContext
                    }
                    // Determine whether we're resuming or starting fresh.
                    val resumeAt: Long
                    val append: Boolean
                    if (response.code == 206) {
                        val rangeStart = response.header("Content-Range")
                            ?.removePrefix("bytes ")
                            ?.substringBefore('-')
                            ?.toLongOrNull() ?: 0L
                        resumeAt = if (rangeStart == partBytes) partBytes else 0L
                        append = resumeAt > 0L
                    } else {
                        // 200: full response — start from scratch
                        resumeAt = 0L
                        append = false
                    }

                    var downloaded = resumeAt
                    val total = body.contentLength().let { cl -> if (cl >= 0L) cl + resumeAt else -1L }
                    var lastEmit = downloaded

                    body.byteStream().use { input ->
                        FileOutputStream(part, append).use { output ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                downloaded += n
                                // Throttle: ~2 MB between emissions (same as ModelDownloader).
                                if (downloaded - lastEmit >= 2_000_000L) {
                                    lastEmit = downloaded
                                    withContext(Dispatchers.Main) {
                                        onProgress(Progress(downloaded, total))
                                    }
                                }
                            }
                        }
                    }

                    if (part.renameTo(target)) {
                        withContext(Dispatchers.Main) {
                            onProgress(Progress(downloaded, total, done = true))
                            launchInstall(target)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onProgress(Progress(downloaded, total, error = "could not finalize APK"))
                        }
                    }
                }
            } catch (e: Exception) {
                // .part is kept for a future resume attempt.
                withContext(Dispatchers.Main) {
                    onProgress(Progress(partBytes, 0L, error = e.message ?: "download failed"))
                }
            }
        }
    }

    /** Fire the system install intent for an already-downloaded APK file. */
    fun launchInstall(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** Delete a cached .part file (explicit user cancel). */
    fun discardPart(fileName: String) {
        runCatching { File(installDir, "$fileName.part").delete() }
    }
}
