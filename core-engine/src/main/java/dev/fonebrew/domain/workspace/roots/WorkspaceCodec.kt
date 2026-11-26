package dev.fonebrew.domain.workspace.roots

import dev.fonebrew.domain.workspace.fuzzy.FrecencyEntry
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class WorkspaceFormatException(message: String) : Exception(message)

/**
 * Versioned JSON for [WorkspaceSnapshot]. Version 1 is the only one; a document with a higher
 * (or missing) version is refused rather than half-read, so the store can quarantine it instead
 * of silently dropping the user's roots. Nothing secret is stored here (paths, names, UI state).
 */
object WorkspaceCodec {
    const val VERSION = 1

    fun encode(s: WorkspaceSnapshot): String {
        val roots = JSONArray()
        for (r in s.roots) {
            roots.put(JSONObject().put("id", r.id).put("name", r.name).put("path", r.path).put("kind", r.kind.name)
                .put("settings", JSONObject()
                    .put("excludes", JSONArray(r.settings.excludes))
                    .put("useDefaultIgnores", r.settings.useDefaultIgnores)
                    .put("useGitignore", r.settings.useGitignore)))
        }
        val ui = JSONObject()
        for ((id, u) in s.ui) {
            ui.put(id, JSONObject()
                .put("expanded", JSONArray(u.expandedDirs))
                .put("lastOpen", JSONArray(u.lastOpenFiles))
                .put("frecency", JSONArray().also { a -> u.frecency.forEach { f -> a.put(JSONObject().put("p", f.path).put("s", f.score).put("t", f.lastAtMs)) } }))
        }
        return JSONObject().put("version", VERSION).put("roots", roots).put("ui", ui).toString(2)
    }

    fun decode(text: String): WorkspaceSnapshot {
        try {
            val o = JSONObject(text)
            val v = o.optInt("version", -1)
            if (v != VERSION) throw WorkspaceFormatException("unsupported workspace version $v")
            val roots = ArrayList<WorkspaceRoot>()
            val ra = o.getJSONArray("roots")
            val seen = HashSet<String>()
            for (i in 0 until ra.length()) {
                val r = ra.getJSONObject(i)
                val id = r.getString("id")
                if (id.isBlank() || !seen.add(id)) throw WorkspaceFormatException("blank or duplicate root id")
                val st = r.optJSONObject("settings") ?: JSONObject()
                val ex = st.optJSONArray("excludes") ?: JSONArray()
                roots.add(WorkspaceRoot(
                    id = id, name = r.getString("name"), path = r.getString("path"),
                    kind = RootKind.valueOf(r.optString("kind", "LOCAL")),
                    settings = RootSettings(
                        excludes = (0 until ex.length()).map { ex.getString(it) },
                        useDefaultIgnores = st.optBoolean("useDefaultIgnores", true),
                        useGitignore = st.optBoolean("useGitignore", true),
                    ),
                ))
            }
            val ui = HashMap<String, RootUiState>()
            val uo = o.optJSONObject("ui") ?: JSONObject()
            for (id in uo.keys()) {
                if (id !in seen) continue
                val u = uo.getJSONObject(id)
                fun strs(k: String): List<String> = u.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                val fa = u.optJSONArray("frecency") ?: JSONArray()
                ui[id] = RootUiState(
                    expandedDirs = strs("expanded").take(Workspace.MAX_EXPANDED),
                    lastOpenFiles = strs("lastOpen").take(Workspace.MAX_LAST_OPEN),
                    frecency = (0 until fa.length()).map { fa.getJSONObject(it) }
                        .map { FrecencyEntry(it.getString("p"), it.getDouble("s"), it.getLong("t")) }.take(Workspace.MAX_FRECENCY),
                )
            }
            return WorkspaceSnapshot(roots, ui)
        } catch (e: WorkspaceFormatException) {
            throw e
        } catch (e: JSONException) {
            throw WorkspaceFormatException("malformed workspace JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw WorkspaceFormatException("bad value in workspace JSON: ${e.message}")
        }
    }
}
