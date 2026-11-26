package dev.fonebrew.domain.gitcore.transport

import dev.fonebrew.domain.gitcore.GitErrorKind
import dev.fonebrew.domain.gitcore.GitException

/**
 * A parsed remote address. [display] never carries a password (parsing REFUSES one: credentials
 * belong in the credential store, not in `.git/config` or logs), so it is safe to print and store.
 */
sealed class RemoteUrl {
    abstract val display: String

    data class Http(val secure: Boolean, val host: String, val port: Int, val path: String, val usernameHint: String?) : RemoteUrl() {
        val scheme: String get() = if (secure) "https" else "http"
        private val defaultPort: Boolean get() = port == (if (secure) 443 else 80)
        val base: String get() = "$scheme://${hostForUrl()}${if (defaultPort) "" else ":$port"}$path"
        override val display: String get() = base
        private fun hostForUrl() = if (host.contains(':')) "[$host]" else host
    }

    data class Ssh(val host: String, val port: Int, val user: String?, val path: String) : RemoteUrl() {
        override val display: String get() = "ssh://${user?.let { "$it@" } ?: ""}${if (host.contains(':')) "[$host]" else host}${if (port == 22) "" else ":$port"}${if (path.startsWith("/")) path else "/$path"}"
    }

    companion object {
        fun parse(text: String): RemoteUrl {
            val t = text.trim()
            if (t.isEmpty() || t.any { it == '\n' || it == '\u0000' || it == ' ' }) bad("the address is empty or contains whitespace")
            val lower = t.lowercase()
            return when {
                lower.startsWith("http://") || lower.startsWith("https://") -> parseHttp(t)
                lower.startsWith("ssh://") -> parseSshUrl(t)
                lower.startsWith("git://") -> throw TransportException(TransportErrorKind.UNSUPPORTED, "the git:// protocol is not supported (it is unauthenticated and unencrypted); use https or ssh")
                lower.startsWith("file://") || t.startsWith("/") || t.startsWith("./") || t.startsWith("../") ->
                    throw TransportException(TransportErrorKind.UNSUPPORTED, "local paths cannot be used as a remote on this device")
                else -> parseScp(t)
            }
        }

        private fun bad(msg: String): Nothing = throw GitException(GitErrorKind.INVALID_ARGUMENT, "bad remote address: $msg")

        private fun parseHttp(t: String): Http {
            val uri = try { java.net.URI(t) } catch (_: Exception) { bad("it is not a valid URL") }
            val host = uri.host?.lowercase() ?: bad("no host")
            val ui = uri.rawUserInfo
            var hint: String? = null
            if (ui != null) {
                if (ui.contains(':')) bad("it contains a password; keep credentials in the credential store, not in the address")
                hint = java.net.URLDecoder.decode(ui, "UTF-8").takeIf { it.isNotEmpty() }
            }
            if (uri.rawQuery != null || uri.rawFragment != null) bad("query strings and fragments are not allowed")
            val secure = uri.scheme.equals("https", ignoreCase = true)
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val path = (uri.rawPath ?: "").trimEnd('/')
            return Http(secure, host, port, path, hint)
        }

        private fun parseSshUrl(t: String): Ssh {
            val uri = try { java.net.URI(t) } catch (_: Exception) { bad("it is not a valid URL") }
            val host = uri.host ?: bad("no host")
            val ui = uri.rawUserInfo
            if (ui != null && ui.contains(':')) bad("it contains a password; keep credentials in the credential store, not in the address")
            val path = uri.rawPath?.takeIf { it.isNotEmpty() && it != "/" } ?: bad("no repository path")
            return Ssh(host, if (uri.port > 0) uri.port else 22, ui?.let { java.net.URLDecoder.decode(it, "UTF-8") }, path)
        }

        private val SCP = Regex("^(?:([A-Za-z0-9._-]+)@)?([A-Za-z0-9._-]+):(?!//)(.+)$")

        private fun parseScp(t: String): Ssh {
            val m = SCP.matchEntire(t) ?: bad("expected https://host/path, ssh://user@host/path or user@host:path")
            return Ssh(m.groupValues[2], 22, m.groupValues[1].ifEmpty { null }, m.groupValues[3])
        }
    }
}
