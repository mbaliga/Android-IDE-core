package dev.aarso.data

import android.content.Context
import java.io.File

/**
 * Play-flavor stub: in-app APK installation is sideload-only (Play forbids
 * `REQUEST_INSTALL_PACKAGES`). All methods are intentional no-ops; the UI should
 * gate the install flow behind [dev.aarso.flavor.InvocationFeatures.BUBBLE_AVAILABLE]
 * or a dedicated `APK_INSTALL_AVAILABLE` constant if needed.
 */
@Suppress("UnusedParameter")
class ApkInstaller(@Suppress("UNUSED_PARAMETER") context: Context) {

    data class Progress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val done: Boolean = false,
        val error: String? = null,
    ) {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    /** No-op in the play flavor — APK install is not available. */
    @Suppress("RedundantSuspendModifier")
    suspend fun downloadAndInstall(
        url: String,
        fileName: String,
        onProgress: suspend (Progress) -> Unit,
    ) {
        onProgress(Progress(0L, 0L, error = "APK install not available in this build"))
    }

    /** No-op in the play flavor. */
    fun launchInstall(apkFile: File) = Unit

    /** No-op in the play flavor. */
    fun discardPart(fileName: String) = Unit
}
