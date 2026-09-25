package dev.aarso.data.kindle

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class FylzOperationKind { RECURSIVE_BACKUP, STAGE_PACKAGE, EXTRACT_PACKAGE, COPY, SHA256 }
enum class FylzOperationState { QUEUED, RUNNING, SUCCEEDED, FAILED, INTERRUPTED, CANCELLED }

data class FylzOperationReceipt(
    val requestId: String,
    val state: FylzOperationState,
    val completedBytes: Long,
    val totalBytes: Long?,
    val sha256: String?,
    val outputUri: String?,
    val errorCode: String?,
)

object FylzOperationContract {
    const val VERSION = 1
    const val FYLZ_PACKAGE = "io.github.mbaliga.fylz"
    const val ACTION_EXECUTE = "io.github.mbaliga.fylz.action.EXECUTE_OPERATION"
    const val ACTION_CANCEL = "io.github.mbaliga.fylz.action.CANCEL_OPERATION"
    const val STATUS_AUTHORITY = "io.github.mbaliga.fylz.operations"
    const val EXTRA_VERSION = "io.github.mbaliga.fylz.extra.OPERATION_CONTRACT_VERSION"
    const val EXTRA_REQUEST_ID = "io.github.mbaliga.fylz.extra.REQUEST_ID"
    const val EXTRA_KIND = "io.github.mbaliga.fylz.extra.OPERATION_KIND"
    const val EXTRA_SOURCE_URI = "io.github.mbaliga.fylz.extra.SOURCE_URI"
    const val EXTRA_DESTINATION_TREE_URI = "io.github.mbaliga.fylz.extra.DESTINATION_TREE_URI"
    const val EXTRA_DESTINATION_RELATIVE_PATH = "io.github.mbaliga.fylz.extra.DESTINATION_RELATIVE_PATH"
    const val EXTRA_EXPECTED_SHA256 = "io.github.mbaliga.fylz.extra.EXPECTED_SHA256"
    const val EXTRA_CALLBACK = "io.github.mbaliga.fylz.extra.CALLBACK"
    const val EXTRA_STATE = "io.github.mbaliga.fylz.extra.STATE"
    const val EXTRA_COMPLETED_BYTES = "io.github.mbaliga.fylz.extra.COMPLETED_BYTES"
    const val EXTRA_TOTAL_BYTES = "io.github.mbaliga.fylz.extra.TOTAL_BYTES"
    const val EXTRA_SHA256 = "io.github.mbaliga.fylz.extra.SHA256"
    const val EXTRA_OUTPUT_URI = "io.github.mbaliga.fylz.extra.OUTPUT_URI"
    const val EXTRA_ERROR_CODE = "io.github.mbaliga.fylz.extra.ERROR_CODE"

    fun statusUri(requestId: String): Uri = Uri.Builder()
        .scheme("content").authority(STATUS_AUTHORITY)
        .appendPath("status").appendPath(requestId).build()
}

class FylzOperationClient(private val context: Context) {
    fun execute(
        kind: FylzOperationKind,
        sourceUri: Uri,
        destinationTreeUri: Uri? = null,
        destinationRelativePath: String? = null,
        expectedSha256: String? = null,
    ): String {
        require(sourceUri.scheme == "content") { "Fylz operations require an opaque content URI." }
        require(destinationTreeUri == null || destinationTreeUri.scheme == "content") {
            "Fylz destination must be an opaque content tree URI."
        }
        require(expectedSha256 == null || SHA256.matches(expectedSha256)) { "Expected SHA-256 is invalid." }
        val requestId = UUID.randomUUID().toString()
        val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.grantUriPermission(FylzOperationContract.FYLZ_PACKAGE, sourceUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        destinationTreeUri?.let {
            context.grantUriPermission(FylzOperationContract.FYLZ_PACKAGE, it, grantFlags)
        }
        val callbackIntent = Intent(context, FylzOperationResultReceiver::class.java)
            .putExtra(FylzOperationContract.EXTRA_REQUEST_ID, requestId)
        val callbackFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(context, requestId.hashCode(), callbackIntent, callbackFlags)
        val clip = ClipData.newRawUri("Fylz source", sourceUri).apply {
            destinationTreeUri?.let { addItem(ClipData.Item(it)) }
        }
        val request = Intent(FylzOperationContract.ACTION_EXECUTE)
            .setPackage(FylzOperationContract.FYLZ_PACKAGE)
            .setClipData(clip)
            .addFlags(grantFlags)
            .putExtra(FylzOperationContract.EXTRA_VERSION, FylzOperationContract.VERSION)
            .putExtra(FylzOperationContract.EXTRA_REQUEST_ID, requestId)
            .putExtra(FylzOperationContract.EXTRA_KIND, kind.name)
            .putExtra(FylzOperationContract.EXTRA_SOURCE_URI, sourceUri.toString())
            .putExtra(FylzOperationContract.EXTRA_DESTINATION_TREE_URI, destinationTreeUri?.toString())
            .putExtra(FylzOperationContract.EXTRA_DESTINATION_RELATIVE_PATH, destinationRelativePath)
            .putExtra(FylzOperationContract.EXTRA_EXPECTED_SHA256, expectedSha256?.lowercase())
            .putExtra(FylzOperationContract.EXTRA_CALLBACK, callback)
        context.sendBroadcast(request)
        return requestId
    }

    fun cancel(requestId: String) {
        context.sendBroadcast(
            Intent(FylzOperationContract.ACTION_CANCEL)
                .setPackage(FylzOperationContract.FYLZ_PACKAGE)
                .putExtra(FylzOperationContract.EXTRA_VERSION, FylzOperationContract.VERSION)
                .putExtra(FylzOperationContract.EXTRA_REQUEST_ID, requestId),
        )
    }

    /** Durable recovery path when the PendingIntent callback was missed across process death. */
    fun status(requestId: String): FylzOperationReceipt? = context.contentResolver.query(
        FylzOperationContract.statusUri(requestId), STATUS_COLUMNS, null, null, null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        FylzOperationReceipt(
            requestId = cursor.getString(0),
            state = FylzOperationState.valueOf(cursor.getString(1)),
            completedBytes = cursor.getLong(2),
            totalBytes = cursor.getLong(3).takeIf { !cursor.isNull(3) },
            sha256 = cursor.getString(4),
            outputUri = cursor.getString(5),
            errorCode = cursor.getString(6),
        )
    }

    suspend fun awaitTerminal(
        requestId: String,
        pollIntervalMillis: Long = 500,
        onProgress: (FylzOperationReceipt) -> Unit = {},
    ): FylzOperationReceipt {
        require(pollIntervalMillis in 100..10_000)
        while (true) {
            status(requestId)?.let { receipt ->
                onProgress(receipt)
                if (receipt.state in TERMINAL_STATES) return receipt
            }
            delay(pollIntervalMillis)
        }
    }

    companion object {
        private val SHA256 = Regex("^[a-fA-F0-9]{64}$")
        private val TERMINAL_STATES = setOf(
            FylzOperationState.SUCCEEDED, FylzOperationState.FAILED,
            FylzOperationState.INTERRUPTED, FylzOperationState.CANCELLED,
        )
        private val STATUS_COLUMNS = arrayOf(
            "request_id", "state", "completed_bytes", "total_bytes", "sha256",
            "output_uri", "error_code", "updated_at_millis",
        )
    }
}

class FylzOperationResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(FylzOperationContract.EXTRA_REQUEST_ID) ?: return
        val state = runCatching {
            FylzOperationState.valueOf(intent.getStringExtra(FylzOperationContract.EXTRA_STATE).orEmpty())
        }.getOrNull() ?: return
        FylzOperationResults.publish(
            FylzOperationReceipt(
                requestId = requestId,
                state = state,
                completedBytes = intent.getLongExtra(FylzOperationContract.EXTRA_COMPLETED_BYTES, 0L),
                totalBytes = intent.getLongExtra(FylzOperationContract.EXTRA_TOTAL_BYTES, -1L).takeIf { it >= 0 },
                sha256 = intent.getStringExtra(FylzOperationContract.EXTRA_SHA256),
                outputUri = intent.getStringExtra(FylzOperationContract.EXTRA_OUTPUT_URI),
                errorCode = intent.getStringExtra(FylzOperationContract.EXTRA_ERROR_CODE),
            ),
        )
    }
}

object FylzOperationResults {
    private val mutable = MutableSharedFlow<FylzOperationReceipt>(replay = 16, extraBufferCapacity = 64)
    val receipts: SharedFlow<FylzOperationReceipt> = mutable.asSharedFlow()
    fun publish(receipt: FylzOperationReceipt) { mutable.tryEmit(receipt) }
}
