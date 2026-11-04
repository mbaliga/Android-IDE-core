package dev.fonebrew.data

import android.content.Context
import dev.fonebrew.domain.mirror.AarsoEventSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * On-device JSONL file append (STUDIO_UX_SPEC.md §10's `aarso/events.jsonl`). The primary
 * constructor takes a plain [File] rather than a [Context] so the append logic itself is
 * JVM-testable against a real temp file — no Robolectric/instrumented test needed (this gate
 * has neither); [forContext] is the one-line convenience for real callers.
 *
 * Deliberately excluded from the §9 backup archive by default (spec §10/§19) — this class does
 * not itself enforce that (the backup mechanism, PC-E, isn't built), it's an intentionally
 * separate file/directory (`files/aarso/`) so a future backup implementation can exclude it by
 * simple path convention.
 */
class AarsoFileEventSink(private val file: File) : AarsoEventSink {

    // Fixed per adversarial review: this used to run the blocking file I/O on whatever
    // dispatcher the caller was on (unconstrained, since AarsoEventLog.record has no dispatcher
    // of its own) — including Main, once Aarso capture is ever enabled.
    override suspend fun append(line: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, /* append = */ true).use { out ->
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        const val RELATIVE_PATH = "aarso/events.jsonl"

        fun forContext(context: Context): AarsoFileEventSink =
            AarsoFileEventSink(File(context.filesDir, RELATIVE_PATH))
    }
}
