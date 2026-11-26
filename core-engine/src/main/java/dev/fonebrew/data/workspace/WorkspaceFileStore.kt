package dev.fonebrew.data.workspace

import dev.fonebrew.domain.workspace.files.AtomicFileWriter
import dev.fonebrew.domain.workspace.roots.WorkspaceCodec
import dev.fonebrew.domain.workspace.roots.WorkspaceFormatException
import dev.fonebrew.domain.workspace.roots.WorkspaceSnapshot
import java.io.File
import java.io.IOException

sealed interface WorkspaceLoad {
    /** No file yet: a first run. */
    object Fresh : WorkspaceLoad
    data class Loaded(val snapshot: WorkspaceSnapshot) : WorkspaceLoad
    /** The file was unreadable or from an unknown version; it was set aside (not deleted) and an empty workspace is returned. */
    data class Recovered(val snapshot: WorkspaceSnapshot, val reason: String, val quarantined: File?) : WorkspaceLoad
}

/**
 * Persists the workspace as one versioned JSON file in app storage (atomic temp+rename writes via
 * [AtomicFileWriter]). A corrupt, truncated or newer-version file is renamed to
 * `<name>.corrupt-<n>` and reported as [WorkspaceLoad.Recovered]: the user's data is never
 * deleted by a load, and the app starts with an empty workspace instead of crashing.
 */
class WorkspaceFileStore(private val file: File) {

    fun load(): WorkspaceLoad {
        if (!file.exists()) return WorkspaceLoad.Fresh
        val text = try { file.readText(Charsets.UTF_8) } catch (e: IOException) {
            return quarantine("unreadable: ${e.javaClass.simpleName}")
        }
        return try {
            WorkspaceLoad.Loaded(WorkspaceCodec.decode(text))
        } catch (e: WorkspaceFormatException) {
            quarantine(e.message ?: "format error")
        }
    }

    fun save(snapshot: WorkspaceSnapshot) {
        file.parentFile?.mkdirs()
        AtomicFileWriter.write(file, WorkspaceCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
    }

    private fun quarantine(reason: String): WorkspaceLoad {
        var n = 1
        var dest = File(file.parentFile, "${file.name}.corrupt-$n")
        while (dest.exists()) { n++; dest = File(file.parentFile, "${file.name}.corrupt-$n") }
        val moved = try { file.renameTo(dest) } catch (_: SecurityException) { false }
        return WorkspaceLoad.Recovered(WorkspaceSnapshot(), reason, if (moved) dest else null)
    }
}
