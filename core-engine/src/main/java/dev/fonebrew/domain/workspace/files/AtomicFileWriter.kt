package dev.fonebrew.domain.workspace.files

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Crash-safe replace: write a sibling temp file, `fsync` it, copy the target's permission bits,
 * then rename over the target. A reader (or a power cut) sees the old bytes or the new bytes,
 * never half of each. Temp files are removed on failure. Falls back to a non-atomic
 * replace-existing move only where the file system refuses `ATOMIC_MOVE` (the caller is told via
 * [Written.atomic]).
 */
object AtomicFileWriter {

    data class Written(val atomic: Boolean, val modePreserved: Boolean)

    fun write(target: Path, bytes: ByteArray): Written {
        val dir = target.parent ?: error("target has no parent directory")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".${target.fileName}.", ".tmp")
        var modePreserved = false
        try {
            FileOutputStream(tmp.toFile()).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (Files.exists(target)) {
                try {
                    Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target))
                    modePreserved = true
                } catch (_: UnsupportedOperationException) {
                    // non-POSIX file system: nothing to preserve
                }
            }
            val atomic = try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); true
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); false
            }
            return Written(atomic, modePreserved)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun write(target: File, bytes: ByteArray): Written = write(target.toPath(), bytes)
}
