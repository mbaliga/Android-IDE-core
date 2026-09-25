package dev.aarso.data.runtime

import android.content.Context
import org.json.JSONObject

/**
 * Small retained terminal-result journal. The file channel can carry large output, but only a
 * bounded recovery excerpt belongs in SharedPreferences (Binder and XML preferences are not a
 * bulk-output transport).
 */
class TermuxResultStore(context: Context) {
    private val preferences = context.getSharedPreferences("termux_result_journal", Context.MODE_PRIVATE)

    @Synchronized fun put(result: TermuxCommandResult) {
        val stdout = result.stdout.take(MAX_RETAINED_CHARS_PER_STREAM)
        val stderr = result.stderr.take(MAX_RETAINED_CHARS_PER_STREAM)
        preferences.edit().putString(result.requestId, JSONObject()
            .put("requestId", result.requestId)
            .put("exitCode", result.exitCode)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("internalErrorCode", result.internalErrorCode)
            .put("internalErrorMessage", result.internalErrorMessage)
            .put("stdoutOriginalLength", result.stdoutOriginalLength)
            .put("stderrOriginalLength", result.stderrOriginalLength)
            .put("capturedViaFileChannel", result.capturedViaFileChannel)
            .toString()).apply()
    }

    @Synchronized fun get(requestId: String): TermuxCommandResult? =
        preferences.getString(requestId, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                TermuxCommandResult(
                    requestId = json.getString("requestId"),
                    exitCode = json.getInt("exitCode"),
                    stdout = json.getString("stdout"),
                    stderr = json.getString("stderr"),
                    internalErrorCode = json.getInt("internalErrorCode"),
                    internalErrorMessage = json.getString("internalErrorMessage"),
                    stdoutOriginalLength = json.getLong("stdoutOriginalLength"),
                    stderrOriginalLength = json.getLong("stderrOriginalLength"),
                    capturedViaFileChannel = json.optBoolean("capturedViaFileChannel", false),
                )
            }.getOrNull()
        }

    @Synchronized fun remove(requestId: String) { preferences.edit().remove(requestId).apply() }

    private companion object {
        const val MAX_RETAINED_CHARS_PER_STREAM = 64 * 1024
    }
}
