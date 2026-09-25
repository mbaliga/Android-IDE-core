package dev.aarso.data.runtime

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.util.Base64

class SafTermuxWorkspaceMirror(
    private val context: Context,
    private val bridge: TermuxRunCommandBridge,
) {
    data class MirrorPolicy(
        val maxFiles: Int = 10_000,
        val maxBytes: Long = 256L * 1024L * 1024L,
        val chunkBytes: Int = 160 * 1024,
        val reserveFreeBytes: Long = 512L * 1024L * 1024L,
        val staleAfterHours: Int = 24,
    )

    data class MirrorReceipt(
        val termuxPath: String,
        val filesCopied: Int,
        val bytesCopied: Long,
    )

    suspend fun materialize(
        treeUri: Uri,
        workspaceId: String,
        policy: MirrorPolicy = MirrorPolicy(),
    ): MirrorReceipt {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Unable to open workspace tree.")
        require(root.isDirectory) { "Workspace URI must identify a directory tree." }
        require(policy.reserveFreeBytes >= 0 && policy.staleAfterHours in 1..(24 * 30))

        val safeId = workspaceId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        require(safeId.isNotBlank()) { "workspaceId must contain at least one safe character." }
        val destination = TERMUX_HOME + "/fonebrew/workspaces/" + safeId

        cleanupStaleWorkspaces(policy.staleAfterHours)
        val available = freeSpaceBytes()
        check(available >= policy.reserveFreeBytes) {
            "Termux workspace has only $available bytes free; ${policy.reserveFreeBytes} bytes are reserved."
        }

        command("rm", listOf("-rf", destination))
        command("mkdir", listOf("-p", destination))

        var files = 0
        var bytes = 0L

        suspend fun copyDirectory(directory: DocumentFile, relative: String) {
            for (child in directory.listFiles()) {
                val name = safeName(child.name ?: continue)
                val childRelative = if (relative.isEmpty()) name else "$relative/$name"
                val childTarget = "$destination/$childRelative"

                if (child.isDirectory) {
                    command("mkdir", listOf("-p", childTarget))
                    copyDirectory(child, childRelative)
                    continue
                }
                if (!child.isFile) continue

                files++
                check(files <= policy.maxFiles) { "Workspace exceeds mirror file limit (${policy.maxFiles})." }

                val length = child.length().coerceAtLeast(0L)
                bytes += length
                check(bytes <= policy.maxBytes) { "Workspace exceeds mirror byte limit (${policy.maxBytes})." }
                check(bytes + policy.reserveFreeBytes <= available) {
                    "Workspace mirror would exhaust reserved Termux free space."
                }

                command("mkdir", listOf("-p", childTarget.substringBeforeLast('/', destination)))
                truncate(childTarget)

                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    val buffer = ByteArray(policy.chunkBytes)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        appendBase64(childTarget, buffer.copyOf(read))
                    }
                } ?: error("Unable to read $childRelative")
            }
        }

        copyDirectory(root, "")
        return MirrorReceipt(destination, files, bytes)
    }

    suspend fun pullArtifact(
        termuxWorkspacePath: String,
        relativePath: String,
        destinationTreeUri: Uri,
    ): Uri {
        val safeRelative = safeRelativePath(relativePath)
        val source = termuxWorkspacePath.trimEnd('/') + "/" + safeRelative
        val sizeResult = shell("stat -c %s \"\$1\"", listOf(source))
        check(sizeResult.exitCode == 0) { "Artifact does not exist: $safeRelative" }
        val size = sizeResult.stdout.trim().toLongOrNull()
            ?: error("Could not determine artifact size.")

        val output = ByteArrayOutputStream()
        var offset = 0L
        val blockSize = 64 * 1024
        while (offset < size) {
            val count = minOf(blockSize.toLong(), size - offset).toInt()
            val result = shell(
                "dd if=\"\$1\" bs=1 skip=\"\$2\" count=\"\$3\" 2>/dev/null | base64 -w0",
                listOf(source, offset.toString(), count.toString()),
            )
            check(result.exitCode == 0) { "Failed reading artifact chunk at $offset." }
            val encoded = result.stdout.trim()
            if (encoded.isEmpty()) break
            output.write(Base64.getDecoder().decode(encoded))
            offset += count
        }
        check(output.size().toLong() == size) {
            "Artifact transfer incomplete: expected $size bytes, got ${output.size()}."
        }

        val root = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: error("Unable to open destination tree.")
        val segments = safeRelative.split('/')
        var parent = root
        for (segment in segments.dropLast(1)) {
            parent = parent.findFile(segment)?.takeIf { it.isDirectory }
                ?: parent.createDirectory(segment)
                ?: error("Unable to create artifact directory $segment.")
        }
        val fileName = segments.last()
        parent.findFile(fileName)?.delete()
        val target = parent.createFile("application/octet-stream", fileName)
            ?: error("Unable to create artifact $fileName.")
        context.contentResolver.openOutputStream(target.uri, "w")?.use { stream ->
            output.writeTo(stream)
        } ?: error("Unable to write artifact $fileName.")
        return target.uri
    }

    suspend fun cleanupWorkspace(workspaceId: String) {
        val safeId = workspaceId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        require(safeId.isNotBlank())
        command("rm", listOf("-rf", "--", "$WORKSPACE_ROOT/$safeId"))
    }

    suspend fun cleanupStaleWorkspaces(maxAgeHours: Int = 24) {
        require(maxAgeHours in 1..(24 * 30))
        val result = shell(
            "mkdir -p \"\$1\" && find \"\$1\" -mindepth 1 -maxdepth 1 -type d -mmin +\"\$2\" -exec rm -rf -- {} +",
            listOf(WORKSPACE_ROOT, (maxAgeHours * 60).toString()),
        )
        check(result.exitCode == 0) { "Unable to clean stale mirrored workspaces." }
    }

    private suspend fun freeSpaceBytes(): Long {
        val result = shell("df -Pk \"\$1\" | awk 'NR==2 { print \$4 * 1024 }'", listOf(TERMUX_HOME))
        check(result.exitCode == 0)
        return result.stdout.trim().toLongOrNull() ?: error("Unable to determine Termux free space.")
    }

    private suspend fun truncate(path: String) {
        val result = shell(": > \"\$1\"", listOf(path))
        check(result.exitCode == 0) { "Unable to create mirrored file." }
    }

    private suspend fun appendBase64(path: String, bytes: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val result = bridge.run(
            executable = "\$PREFIX/bin/sh",
            args = listOf("-c", "base64 -d >> \"\$1\"", "_", path),
            stdin = encoded,
            timeoutMs = 60_000,
        )
        check(result.exitCode == 0 && result.internalErrorCode == android.app.Activity.RESULT_OK) {
            "Unable to mirror workspace chunk: " +
                result.internalErrorMessage.ifBlank { result.stderr }
        }
    }

    private suspend fun command(command: String, args: List<String>) {
        val result = bridge.run(
            executable = "\$PREFIX/bin/$command",
            args = args,
            timeoutMs = 60_000,
        )
        check(result.exitCode == 0 && result.internalErrorCode == android.app.Activity.RESULT_OK) {
            "$command failed: " + result.internalErrorMessage.ifBlank { result.stderr }
        }
    }

    private suspend fun shell(script: String, args: List<String>): TermuxCommandResult =
        bridge.run(
            executable = "\$PREFIX/bin/sh",
            args = listOf("-c", script, "_") + args,
            timeoutMs = 60_000,
        )

    private fun safeName(name: String): String {
        require(name.isNotBlank() && name != "." && name != "..") { "Unsafe workspace name." }
        require('/' !in name && '\\' !in name && '\u0000' !in name) { "Unsafe workspace name." }
        return name
    }

    private fun safeRelativePath(path: String): String {
        val normalized = path.replace('\\', '/').trim('/')
        require(normalized.isNotBlank()) { "Artifact path must be non-blank." }
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe artifact path." }
        return parts.joinToString("/")
    }

    companion object {
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val WORKSPACE_ROOT = "$TERMUX_HOME/fonebrew/workspaces"
    }
}
