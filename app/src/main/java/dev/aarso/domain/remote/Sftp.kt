package dev.aarso.domain.remote

/**
 * The SFTP file-transfer model — put / get / list over an established session. This is how
 * code gets pushed to a Raspberry Pi (Sprint 3) before a remote-exec recipe runs it. Pure
 * descriptors and a path helper; the bytes move behind [RemoteTransport]. JVM-tested.
 */

/** One remote directory entry. [size] in bytes; [mode] is the raw POSIX mode (rendered later). */
data class SftpEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val mode: Int = 0,
) {
    val isFile: Boolean get() = !isDirectory
}

/** An SFTP operation to perform — the pure description the transport executes. */
sealed interface SftpOp {
    /** Upload [bytesId] (a content handle the data layer resolves) to [remotePath]. */
    data class Put(val remotePath: String, val bytesId: String) : SftpOp

    /** Download [remotePath] (the data layer receives the bytes). */
    data class Get(val remotePath: String) : SftpOp

    /** List [remotePath]. */
    data class List(val remotePath: String) : SftpOp

    /** Make a directory (and parents). */
    data class Mkdir(val remotePath: String) : SftpOp

    /** Remove a file. */
    data class Remove(val remotePath: String) : SftpOp
}

/** Pure POSIX-ish path joining so callers don't hand-roll slashes. */
object RemotePath {
    fun join(vararg parts: String): String {
        val cleaned = parts.filter { it.isNotEmpty() }.map { it.trim('/') }.filter { it.isNotEmpty() }
        val joined = cleaned.joinToString("/")
        return if (parts.firstOrNull()?.startsWith("/") == true) "/$joined" else joined
    }

    /** The parent directory of [path], or "" for a top-level name. */
    fun parent(path: String): String {
        val trimmed = path.trimEnd('/')
        val i = trimmed.lastIndexOf('/')
        return if (i <= 0) (if (i == 0) "/" else "") else trimmed.substring(0, i)
    }

    /** The final component of [path]. */
    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')
}
