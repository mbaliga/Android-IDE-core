package dev.fonebrew.domain.workspace.roots

import dev.fonebrew.contracts.workspace.ResourceProvider
import dev.fonebrew.contracts.workspace.ResourceUri

/** Why a raw path string was refused before it ever touched a file system. */
enum class PathRefusal { EMPTY_ROOT_ID, ABSOLUTE, PARENT_SEGMENT, NUL_BYTE, DRIVE_PREFIX, TOO_LONG }

/** Result of parsing a user- or agent-supplied path into the unified path space. */
sealed interface PathParse {
    data class Ok(val path: WorkspacePath) : PathParse
    data class Refused(val reason: PathRefusal, val raw: String) : PathParse
}

/**
 * The unified path space of a multi-root [Workspace]: a root id plus a normalised root-relative
 * path (`""` is the root itself, otherwise `a/b/c` with no leading or trailing slash and no
 * `.` / empty segments). Construct through [parse]; the constructor is private so an
 * un-normalised or escaping path cannot exist as a value.
 *
 * Lexical rules, applied before any symlink check ([PathGuard] does that): an absolute path, a
 * drive prefix, a NUL byte and EVERY `..` segment are refused outright (even `a/../b`, which would
 * stay inside: refusing is simpler to audit than proving containment of a rewritten path).
 */
class WorkspacePath private constructor(val rootId: String, val rel: String) {

    val isRoot: Boolean get() = rel.isEmpty()
    val name: String get() = rel.substringAfterLast('/')
    val parent: WorkspacePath? get() = if (isRoot) null else WorkspacePath(rootId, rel.substringBeforeLast('/', ""))

    fun child(segment: String): PathParse = parse(rootId, if (rel.isEmpty()) segment else "$rel/$segment")

    /** `local://<rootId>/<rel>`: the Workspace-level identity (a root's own provider sees `local://<rel>`). */
    fun toResourceUri(): ResourceUri = ResourceUri(ResourceProvider.LOCAL, "local://$rootId/$rel", displayPath = rel.ifEmpty { "/" })

    override fun equals(other: Any?): Boolean = other is WorkspacePath && other.rootId == rootId && other.rel == rel
    override fun hashCode(): Int = 31 * rootId.hashCode() + rel.hashCode()
    override fun toString(): String = "$rootId:$rel"

    companion object {
        const val MAX_LENGTH = 4096

        fun root(rootId: String): WorkspacePath {
            require(rootId.isNotBlank()) { "rootId must be non-blank" }
            return WorkspacePath(rootId, "")
        }

        fun parse(rootId: String, raw: String): PathParse {
            if (rootId.isBlank()) return PathParse.Refused(PathRefusal.EMPTY_ROOT_ID, raw)
            if (raw.length > MAX_LENGTH) return PathParse.Refused(PathRefusal.TOO_LONG, raw.take(64))
            if (raw.indexOf('\u0000') >= 0) return PathParse.Refused(PathRefusal.NUL_BYTE, raw)
            if (raw.startsWith("/") || raw.startsWith("\\")) return PathParse.Refused(PathRefusal.ABSOLUTE, raw)
            if (raw.length >= 2 && raw[1] == ':' && raw[0].isLetter()) return PathParse.Refused(PathRefusal.DRIVE_PREFIX, raw)
            val kept = ArrayList<String>()
            for (seg in raw.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> return PathParse.Refused(PathRefusal.PARENT_SEGMENT, raw)
                    else -> kept.add(seg)
                }
            }
            return PathParse.Ok(WorkspacePath(rootId, kept.joinToString("/")))
        }

        /** Convenience for tests and trusted internal callers; throws on a refused path. */
        fun of(rootId: String, raw: String): WorkspacePath = when (val r = parse(rootId, raw)) {
            is PathParse.Ok -> r.path
            is PathParse.Refused -> throw IllegalArgumentException("path refused (${r.reason})")
        }
    }
}
