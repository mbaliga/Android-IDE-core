package dev.aarso

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures an uncaught crash to a file so a **device-only launch crash** (which CI never sees —
 * CI runs unit tests, never launches the app) becomes diagnosable: the next launch reads it and
 * shows a recovery screen instead of bricking. Best-effort and self-contained — every op is
 * guarded so the crash handler can never itself crash. Local only; nothing is sent anywhere.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun write(context: Context, throwable: Throwable) {
        runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val body = buildString {
                append("Aarso crash\n")
                append("when: ").append(System.currentTimeMillis()).append('\n')
                append("thread: ").append(Thread.currentThread().name).append("\n\n")
                append(sw.toString())
            }
            File(context.applicationContext.filesDir, FILE).writeText(body)
            android.util.Log.e("AarsoCrash", "captured launch/runtime crash", throwable)
        }
    }

    fun read(context: Context): String? =
        runCatching {
            File(context.applicationContext.filesDir, FILE).takeIf { it.exists() }?.readText()
        }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
    }
}
