package dev.fonebrew.domain.language

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * WP-9's "URI mapping": workspace-relative path ⇄ LSP `DocumentUri`, round-trip-safe including
 * spaces and non-ASCII path segments. LSP requires a `DocumentUri` be a proper RFC 3986 URI with
 * reserved/non-ASCII characters percent-encoded — a bare workspace-relative path is not itself a
 * legal LSP URI, and naive string concatenation (`"$root/$path"`) silently produces an illegal
 * one the moment a filename contains a space.
 */
object LanguageUriMapper {

    /** [workspaceRootUri] MUST already be an absolute `file://` URI with no trailing slash. */
    fun toLspUri(workspaceRootUri: String, workspaceRelativePath: String): String {
        require(workspaceRootUri.startsWith("file://")) { "LanguageUriMapper.toLspUri: workspaceRootUri must start with 'file://', got '$workspaceRootUri'." }
        require(!workspaceRootUri.endsWith("/")) { "LanguageUriMapper.toLspUri: workspaceRootUri must not end with '/', got '$workspaceRootUri'." }
        require(!workspaceRelativePath.startsWith("/")) { "LanguageUriMapper.toLspUri: workspaceRelativePath must be relative (no leading '/'), got '$workspaceRelativePath'." }
        require(workspaceRelativePath.isNotBlank()) { "LanguageUriMapper.toLspUri: workspaceRelativePath must be non-blank." }
        val segments = workspaceRelativePath.split("/").filter { it.isNotEmpty() }
        require(segments.none { it == ".." || it == "." }) {
            "LanguageUriMapper.toLspUri: workspaceRelativePath must not contain '.' or '..' segments (LOOP-adjacent principle: no path escaping the workspace root), got '$workspaceRelativePath'."
        }
        return workspaceRootUri + "/" + segments.joinToString("/") { encodeSegment(it) }
    }

    /** Inverse of [toLspUri]. Throws if [lspUri] does not lie under [workspaceRootUri] -- a URI outside the workspace has no workspace-relative path to return. */
    fun toWorkspaceRelativePath(workspaceRootUri: String, lspUri: String): String {
        val prefix = "$workspaceRootUri/"
        require(lspUri.startsWith(prefix)) { "LanguageUriMapper.toWorkspaceRelativePath: '$lspUri' does not lie under workspace root '$workspaceRootUri'." }
        return lspUri.removePrefix(prefix).split("/").joinToString("/") { decodeSegment(it) }
    }

    private fun encodeSegment(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun decodeSegment(segment: String): String = URLDecoder.decode(segment, "UTF-8")
}
