package dev.aarso.data.kindle

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import dev.aarso.domain.kindle.ProvisioningPackage
import kotlinx.coroutines.delay

/** Delegates bytes and storage to Android DownloadManager and returns only its content handle. */
class ApprovedPackageDownloader(context: Context) {
    private val manager = context.applicationContext.getSystemService(DownloadManager::class.java)

    fun enqueue(value: ProvisioningPackage): Long = manager.enqueue(
        DownloadManager.Request(Uri.parse(value.sourceUrl))
            .setTitle("Kindle provisioning · ${value.id} ${value.version}")
            .setDescription("Manifest-approved package; Fylz verifies SHA-256 before staging")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false),
    )

    suspend fun await(id: Long, pollMillis: Long = 500): Uri {
        require(id >= 0 && pollMillis in 100..10_000)
        while (true) {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    when (cursor.int(DownloadManager.COLUMN_STATUS)) {
                        DownloadManager.STATUS_SUCCESSFUL -> return requireNotNull(manager.getUriForDownloadedFile(id)) {
                            "Download completed without a readable content handle."
                        }
                        DownloadManager.STATUS_FAILED -> error(
                            "Approved package download failed (${cursor.int(DownloadManager.COLUMN_REASON)}).",
                        )
                    }
                }
            }
            delay(pollMillis)
        }
    }

    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
}
