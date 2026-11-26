package dev.fonebrew.data.terminal

import java.io.File

/**
 * Reads just enough of `/proc` to name what is running under a shell, without guessing.
 * [root] is injectable so the parsing is JVM-tested against a fake tree. On Android 10+ an app
 * sees only its own uid's processes in `/proc` (hidepid), which is exactly the set of its own
 * children - and anything unreadable is reported as unknown, not as "nothing".
 */
class ProcInspector(private val root: File = File("/proc")) {

    /** `comm` (process name) of [pid], or null if unreadable. */
    fun comm(pid: Int): String? =
        runCatching { File(root, "$pid/comm").readText().trim().takeIf { it.isNotEmpty() } }.getOrNull()

    /**
     * Direct children of [pid] as (childPid, childPgid), or null if [pid]'s own `stat` cannot be
     * read (then the caller must say Unknown). An empty list means "readable, and none".
     */
    fun children(pid: Int): List<Pair<Int, Int>>? {
        if (stat(pid) == null) return null
        val dirs = root.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: return null
        val out = ArrayList<Pair<Int, Int>>()
        for (d in dirs) {
            val st = stat(d.name.toInt()) ?: continue
            if (st.ppid == pid) out.add(st.pid to st.pgrp)
        }
        return out
    }

    private class Stat(val pid: Int, val ppid: Int, val pgrp: Int)

    /** `/proc/<pid>/stat`: `pid (comm) state ppid pgrp ...`; comm may contain spaces/parens, so split at the last ')'. */
    private fun stat(pid: Int): Stat? = runCatching {
        val text = File(root, "$pid/stat").readText()
        val close = text.lastIndexOf(')')
        if (close < 0) return null
        val rest = text.substring(close + 1).trim().split(' ')
        // rest[0] = state, rest[1] = ppid, rest[2] = pgrp
        Stat(pid, rest[1].toInt(), rest[2].toInt())
    }.getOrNull()
}
