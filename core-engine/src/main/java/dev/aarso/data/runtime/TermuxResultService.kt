package dev.aarso.data.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TermuxResultService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
        if (requestId < 0) return START_NOT_STICKY

        val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
        val result = if (bundle == null) {
            TermuxCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                internalErrorCode = 1,
                internalErrorMessage = "Termux result bundle missing",
            )
        } else {
            TermuxCommandResult(
                exitCode = bundle.getInt(EXTRA_EXIT_CODE, -1),
                stdout = bundle.getString(EXTRA_STDOUT, "") ?: "",
                stderr = bundle.getString(EXTRA_STDERR, "") ?: "",
                internalErrorCode = bundle.getInt(EXTRA_ERR, 0),
                internalErrorMessage = bundle.getString(EXTRA_ERRMSG, "") ?: "",
            )
        }

        pending.remove(requestId)?.complete(result)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_REQUEST_ID = "dev.aarso.runtime.REQUEST_ID"

        private const val EXTRA_RESULT_BUNDLE = "result"
        private const val EXTRA_STDOUT = "stdout"
        private const val EXTRA_STDERR = "stderr"
        private const val EXTRA_EXIT_CODE = "exitCode"
        private const val EXTRA_ERR = "err"
        private const val EXTRA_ERRMSG = "errmsg"

        private val nextId = AtomicInteger(10_000)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()

        fun nextRequestId(): Int = nextId.incrementAndGet()

        fun register(id: Int, result: CompletableDeferred<TermuxCommandResult>) {
            check(pending.putIfAbsent(id, result) == null) { "duplicate Termux request id $id" }
        }

        fun unregister(id: Int) {
            pending.remove(id)
        }
    }
}
