package dev.fonebrew.domain.gitcore

import java.util.Locale

/**
 * A small `.git/config` reader/editor. Reads the subset of git-config syntax the engine needs
 * (sections with optional quoted subsection, `key = value`, `#`/`;` comments, quoted values with
 * `\n \t \\ \"` escapes, trailing-backslash continuation, bare keys meaning `true`) and edits the
 * file textually, so unrelated lines and comments survive a write.
 *
 * Not supported (named follow-up): `[include]`/`includeIf`, conditional includes, multi-valued
 * edit semantics beyond "replace all values of this key with one".
 */
class GitConfig private constructor(private var lines: MutableList<String>) {
    private data class Entry(val section: String, val sub: String?, val key: String, val value: String)

    private var cache: List<Entry>? = null

    private fun entries(): List<Entry> = cache ?: parse(lines).also { cache = it }

    fun getAll(section: String, sub: String?, key: String): List<String> =
        entries().filter { it.section == section.lowercase(Locale.ROOT) && it.sub == sub && it.key == key.lowercase(Locale.ROOT) }.map { it.value }

    fun get(section: String, sub: String?, key: String): String? = getAll(section, sub, key).lastOrNull()
    fun getBoolean(section: String, sub: String?, key: String, default: Boolean): Boolean =
        when (get(section, sub, key)?.lowercase(Locale.ROOT)) {
            null -> default
            "true", "yes", "on", "1", "" -> true
            "false", "no", "off", "0" -> false
            else -> default
        }

    val userName: String? get() = get("user", null, "name")
    val userEmail: String? get() = get("user", null, "email")
    fun remoteUrl(remote: String): String? = get("remote", remote, "url")
    fun remoteNames(): List<String> = entries().filter { it.section == "remote" && it.sub != null }.mapNotNull { it.sub }.distinct()
    fun branchRemote(branch: String): String? = get("branch", branch, "remote")
    fun branchMerge(branch: String): String? = get("branch", branch, "merge")
    val isBare: Boolean get() = getBoolean("core", null, "bare", false)
    val repositoryFormatVersion: Int get() = get("core", null, "repositoryformatversion")?.toIntOrNull() ?: 0
    val objectFormat: String get() = get("extensions", null, "objectformat")?.lowercase(Locale.ROOT) ?: "sha1"

    /** Replaces every value of the key with a single [value]. */
    fun set(section: String, sub: String?, key: String, value: String) {
        validateName(section, key)
        sub?.let { if (it.indexOf('\u0000') >= 0 || it.indexOf('\n') >= 0) throw GitException(GitErrorKind.INVALID_ARGUMENT, "bad config subsection") }
        unsetInternal(section, sub, key)
        val header = sectionHeader(section, sub)
        val line = "\t$key = ${encode(value)}"
        val at = findSectionEnd(section, sub)
        if (at < 0) { lines.add(header); lines.add(line) } else lines.add(at, line)
        cache = null
    }

    fun unset(section: String, sub: String?, key: String) { unsetInternal(section, sub, key); cache = null }

    /** Removes the whole `[section "sub"]` (for branch/remote rename and delete). */
    fun removeSection(section: String, sub: String?) {
        var i = 0
        val out = ArrayList<String>()
        var skipping = false
        while (i < lines.size) {
            val l = lines[i]
            val h = parseHeader(l)
            if (h != null) skipping = h.first == section.lowercase(Locale.ROOT) && h.second == sub
            if (!skipping) out.add(l)
            i++
        }
        lines = out
        cache = null
    }

    fun toText(): String = lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n"

    private fun validateName(section: String, key: String) {
        if (!section.all { it.isLetterOrDigit() || it == '-' || it == '.' } || section.isEmpty() ||
            !key.all { it.isLetterOrDigit() || it == '-' } || key.isEmpty() || !key[0].isLetter()
        ) throw GitException(GitErrorKind.INVALID_ARGUMENT, "bad config name")
    }

    private fun sectionHeader(section: String, sub: String?) =
        if (sub == null) "[$section]" else "[$section \"${sub.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"

    private fun encode(v: String): String {
        val needsQuote = v.isEmpty().not() && (v.first().isWhitespace() || v.last().isWhitespace() || v.any { it == '#' || it == ';' })
        val esc = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")
        return if (needsQuote) "\"$esc\"" else esc
    }

    private fun findSectionEnd(section: String, sub: String?): Int {
        var lastHeader = -1
        var end = -1
        for ((i, l) in lines.withIndex()) {
            val h = parseHeader(l)
            if (h != null) {
                if (lastHeader >= 0 && end < 0) end = i
                lastHeader = if (h.first == section.lowercase(Locale.ROOT) && h.second == sub) i else -1
                if (lastHeader >= 0) end = -1
            }
        }
        if (lastHeader < 0 && end < 0) return -1
        return if (end >= 0) end else lines.size
    }

    private fun unsetInternal(section: String, sub: String?, key: String) {
        val out = ArrayList<String>()
        var inSec = false
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val h = parseHeader(l)
            if (h != null) inSec = h.first == section.lowercase(Locale.ROOT) && h.second == sub
            else if (inSec) {
                val t = l.trim()
                if (t.isNotEmpty() && t[0] != '#' && t[0] != ';') {
                    val name = t.takeWhile { it != '=' && !it.isWhitespace() }.lowercase(Locale.ROOT)
                    if (name == key.lowercase(Locale.ROOT)) {
                        // swallow continuation lines of a multi-line value
                        var cont = l.trimEnd().endsWith("\\")
                        while (cont && i + 1 < lines.size) { i++; cont = lines[i].trimEnd().endsWith("\\") }
                        i++
                        continue
                    }
                }
            }
            out.add(l); i++
        }
        lines = out
    }

    companion object {
        const val MAX_BYTES = 1L shl 20

        fun empty() = GitConfig(mutableListOf())

        fun parseText(text: String): GitConfig = GitConfig(text.replace("\r\n", "\n").split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }.toMutableList())

        private fun parseHeader(line: String): Pair<String, String?>? {
            val t = line.trim()
            if (!t.startsWith("[")) return null
            val close = t.indexOf(']')
            if (close < 0) return null
            val inner = t.substring(1, close).trim()
            val q = inner.indexOf('"')
            if (q < 0) {
                val dot = inner.indexOf('.')
                return if (dot < 0) inner.lowercase(Locale.ROOT) to null else inner.substring(0, dot).lowercase(Locale.ROOT) to inner.substring(dot + 1).lowercase(Locale.ROOT)
            }
            val sec = inner.substring(0, q).trim().lowercase(Locale.ROOT)
            val sb = StringBuilder()
            var i = q + 1
            while (i < inner.length && inner[i] != '"') {
                if (inner[i] == '\\' && i + 1 < inner.length) i++
                sb.append(inner[i]); i++
            }
            return sec to sb.toString()
        }

        private fun parse(lines: List<String>): List<Entry> {
            val out = ArrayList<Entry>()
            var section: String? = null
            var sub: String? = null
            var i = 0
            while (i < lines.size) {
                val raw = lines[i]
                val h = parseHeader(raw)
                if (h != null) {
                    section = h.first; sub = h.second
                    // `[s] key = v` on one line
                    val rest = raw.trim().substringAfter(']', "").trim()
                    if (rest.isNotEmpty() && rest[0] != '#' && rest[0] != ';') addKv(out, section, sub, rest)
                    i++; continue
                }
                val t = raw.trim()
                if (section != null && t.isNotEmpty() && t[0] != '#' && t[0] != ';') {
                    var joined = t
                    while (joined.endsWith("\\") && i + 1 < lines.size) { i++; joined = joined.dropLast(1) + lines[i].trim() }
                    addKv(out, section, sub, joined)
                }
                i++
            }
            return out
        }

        private fun addKv(out: MutableList<Entry>, section: String, sub: String?, text: String) {
            val eq = text.indexOf('=')
            val key = (if (eq < 0) text else text.substring(0, eq)).trim().lowercase(Locale.ROOT)
            if (key.isEmpty()) return
            val value = if (eq < 0) "" else decodeValue(text.substring(eq + 1).trim())
            out.add(Entry(section, sub, key, value))
        }

        private fun decodeValue(v: String): String {
            val sb = StringBuilder()
            var inQuote = false
            var keep = 0 // length that must survive the trailing-whitespace trim (quoted text)
            var i = 0
            while (i < v.length) {
                val c = v[i]
                when {
                    c == '\\' && i + 1 < v.length -> {
                        i++
                        sb.append(when (v[i]) { 'n' -> '\n'; 't' -> '\t'; 'b' -> '\b'; else -> v[i] })
                        keep = sb.length
                    }
                    c == '"' -> inQuote = !inQuote
                    !inQuote && (c == '#' || c == ';') -> break
                    else -> { sb.append(c); if (inQuote) keep = sb.length }
                }
                i++
            }
            if (inQuote) return sb.toString()
            var end = sb.length
            while (end > keep && sb[end - 1].isWhitespace()) end--
            return sb.substring(0, end)
        }

        fun load(fs: FileSystemPort, path: String): GitConfig {
            if (fs.stat(path)?.kind != FileKind.FILE) return empty()
            return parseText(String(fs.readBytes(path, MAX_BYTES), Charsets.UTF_8))
        }
    }

    fun save(fs: FileSystemPort, path: String) = fs.writeAtomic(path, toText().toByteArray(Charsets.UTF_8))
}
