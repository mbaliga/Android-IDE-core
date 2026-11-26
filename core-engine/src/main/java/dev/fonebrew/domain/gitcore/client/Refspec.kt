package dev.fonebrew.domain.gitcore.client

import dev.fonebrew.domain.gitcore.GitErrorKind
import dev.fonebrew.domain.gitcore.GitException
import dev.fonebrew.domain.gitcore.RefName

/**
 * A fetch refspec, in the two shapes the engine writes and reads: a wildcard heads-to-remote-tracking
 * mapping and a single-ref mapping (`+refs/heads/b:refs/remotes/o/b`). Anything else is rejected, not guessed at.
 */
class Refspec(val src: String, val dst: String) {
    val wildcard: Boolean = src.endsWith("/*")

    /** The local ref [remoteRef] maps to, or null when this spec does not select it. */
    fun map(remoteRef: String): String? = when {
        wildcard -> src.removeSuffix("*").let { p -> if (remoteRef.startsWith(p) && remoteRef.length > p.length) dst.removeSuffix("*") + remoteRef.substring(p.length) else null }
        remoteRef == src -> dst
        else -> null
    }

    fun text(): String = "+$src:$dst"

    companion object {
        fun parse(text: String): Refspec {
            val t = text.removePrefix("+")
            val c = t.indexOf(':')
            if (c <= 0) throw GitException(GitErrorKind.UNSUPPORTED, "unsupported fetch refspec")
            val src = t.substring(0, c); val dst = t.substring(c + 1)
            if (src.endsWith("*") != dst.endsWith("*") || src.count { it == '*' } > 1 || dst.count { it == '*' } > 1) throw GitException(GitErrorKind.UNSUPPORTED, "unsupported fetch refspec")
            if (src.contains('*') && !src.endsWith("/*") || dst.contains('*') && !dst.endsWith("/*")) throw GitException(GitErrorKind.UNSUPPORTED, "unsupported fetch refspec")
            if (!dst.startsWith("refs/") || !RefName.isValid(dst.removeSuffix("/*") + if (dst.endsWith("*")) "/x" else "")) throw GitException(GitErrorKind.UNSUPPORTED, "unsupported fetch refspec")
            return Refspec(src, dst)
        }

        fun defaultFor(remote: String) = Refspec("refs/heads/*", "refs/remotes/$remote/*")
    }
}
