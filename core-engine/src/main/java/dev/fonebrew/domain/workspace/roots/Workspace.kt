package dev.fonebrew.domain.workspace.roots

import dev.fonebrew.domain.workspace.fuzzy.FrecencyEntry
import java.io.File
import java.util.UUID

enum class RootKind { LOCAL }   // SAF is a named follow-up behind the same provider interface

/** Per-root behaviour switches. [excludes] are gitignore-syntax lines evaluated relative to the root. */
data class RootSettings(
    val excludes: List<String> = emptyList(),
    val useDefaultIgnores: Boolean = true,
    val useGitignore: Boolean = true,
)

/** One root of a workspace. [path] is an absolute directory on this device. */
data class WorkspaceRoot(
    val id: String,
    val name: String,
    val path: String,
    val kind: RootKind = RootKind.LOCAL,
    val settings: RootSettings = RootSettings(),
)

/** UI state the later tree UI restores per root. All lists are bounded (see [Workspace]). */
data class RootUiState(
    val expandedDirs: List<String> = emptyList(),
    val lastOpenFiles: List<String> = emptyList(),
    val frecency: List<FrecencyEntry> = emptyList(),
)

/** What gets persisted: roots in order plus UI state keyed by root id. */
data class WorkspaceSnapshot(
    val roots: List<WorkspaceRoot> = emptyList(),
    val ui: Map<String, RootUiState> = emptyMap(),
)

enum class WorkspaceRefusal { BLANK_NAME, DUPLICATE_NAME, NOT_A_DIRECTORY, DUPLICATE_ROOT, OVERLAPPING_ROOT, TOO_MANY_ROOTS, UNKNOWN_ROOT, BAD_ORDER }

sealed interface WorkspaceEdit {
    data class Ok(val root: WorkspaceRoot?) : WorkspaceEdit
    data class Refused(val reason: WorkspaceRefusal, val detail: String) : WorkspaceEdit
}

/**
 * Ordered, named roots over the unified [WorkspacePath] space. Thread-safe; every mutation either
 * applies fully or is [WorkspaceEdit.Refused] with a reason. Two roots may not be the same
 * directory nor nested in one another (a path would then have two identities), and names are
 * unique so a picker is unambiguous. [resolve] hands out the guarded provider for a root.
 */
class Workspace(
    initial: WorkspaceSnapshot = WorkspaceSnapshot(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    private var roots: List<WorkspaceRoot> = initial.roots
    private var ui: Map<String, RootUiState> = initial.ui.filterKeys { k -> initial.roots.any { it.id == k } }
    private val providers = HashMap<String, LocalFileProvider>()

    fun snapshot(): WorkspaceSnapshot = synchronized(lock) { WorkspaceSnapshot(roots, ui) }
    fun roots(): List<WorkspaceRoot> = synchronized(lock) { roots }
    fun root(id: String): WorkspaceRoot? = synchronized(lock) { roots.firstOrNull { it.id == id } }
    fun uiState(id: String): RootUiState = synchronized(lock) { ui[id] ?: RootUiState() }

    fun addRoot(name: String, dir: File, settings: RootSettings = RootSettings()): WorkspaceEdit = synchronized(lock) {
        if (name.isBlank()) return WorkspaceEdit.Refused(WorkspaceRefusal.BLANK_NAME, "A root needs a name.")
        if (roots.size >= MAX_ROOTS) return WorkspaceEdit.Refused(WorkspaceRefusal.TOO_MANY_ROOTS, "At most $MAX_ROOTS roots.")
        if (!dir.isDirectory) return WorkspaceEdit.Refused(WorkspaceRefusal.NOT_A_DIRECTORY, "'${dir.path}' is not a directory.")
        if (roots.any { it.name == name.trim() }) return WorkspaceEdit.Refused(WorkspaceRefusal.DUPLICATE_NAME, "A root named '${name.trim()}' exists.")
        val real = dir.toPath().toRealPath()
        for (r in roots) {
            val other = File(r.path).toPath().let { runCatching { it.toRealPath() }.getOrDefault(it) }
            if (other == real) return WorkspaceEdit.Refused(WorkspaceRefusal.DUPLICATE_ROOT, "Already a root: '${r.name}'.")
            if (real.startsWith(other) || other.startsWith(real)) {
                return WorkspaceEdit.Refused(WorkspaceRefusal.OVERLAPPING_ROOT, "Overlaps root '${r.name}'; nested roots would give one file two identities.")
            }
        }
        val root = WorkspaceRoot(newId(), name.trim(), real.toString(), RootKind.LOCAL, settings)
        roots = roots + root
        WorkspaceEdit.Ok(root)
    }

    fun removeRoot(id: String): WorkspaceEdit = synchronized(lock) {
        val r = roots.firstOrNull { it.id == id } ?: return unknown(id)
        roots = roots.filter { it.id != id }
        ui = ui - id
        providers.remove(id)
        WorkspaceEdit.Ok(r)
    }

    fun renameRoot(id: String, newName: String): WorkspaceEdit = synchronized(lock) {
        val r = roots.firstOrNull { it.id == id } ?: return unknown(id)
        if (newName.isBlank()) return WorkspaceEdit.Refused(WorkspaceRefusal.BLANK_NAME, "A root needs a name.")
        if (roots.any { it.id != id && it.name == newName.trim() }) return WorkspaceEdit.Refused(WorkspaceRefusal.DUPLICATE_NAME, "A root named '${newName.trim()}' exists.")
        val upd = r.copy(name = newName.trim())
        roots = roots.map { if (it.id == id) upd else it }
        WorkspaceEdit.Ok(upd)
    }

    /** Moves root [id] to position [toIndex] (0-based, clamped check: out of range is refused). */
    fun moveRoot(id: String, toIndex: Int): WorkspaceEdit = synchronized(lock) {
        val r = roots.firstOrNull { it.id == id } ?: return unknown(id)
        if (toIndex !in roots.indices) return WorkspaceEdit.Refused(WorkspaceRefusal.BAD_ORDER, "Index $toIndex outside 0..${roots.size - 1}.")
        val rest = roots.filter { it.id != id }.toMutableList()
        rest.add(toIndex, r)
        roots = rest
        WorkspaceEdit.Ok(r)
    }

    fun updateSettings(id: String, settings: RootSettings): WorkspaceEdit = synchronized(lock) {
        val r = roots.firstOrNull { it.id == id } ?: return unknown(id)
        val upd = r.copy(settings = settings)
        roots = roots.map { if (it.id == id) upd else it }
        WorkspaceEdit.Ok(upd)
    }

    fun setExpandedDirs(id: String, dirs: Collection<String>) = mutateUi(id) {
        it.copy(expandedDirs = dirs.distinct().sorted().take(MAX_EXPANDED))
    }

    /** Most recent first, de-duplicated, bounded. */
    fun recordOpenFile(id: String, rel: String) = mutateUi(id) {
        it.copy(lastOpenFiles = (listOf(rel) + it.lastOpenFiles.filter { f -> f != rel }).take(MAX_LAST_OPEN))
    }

    fun forgetOpenFile(id: String, rel: String) = mutateUi(id) { it.copy(lastOpenFiles = it.lastOpenFiles.filter { f -> f != rel }) }

    fun setFrecency(id: String, entries: List<FrecencyEntry>) = mutateUi(id) { it.copy(frecency = entries.take(MAX_FRECENCY)) }

    private fun mutateUi(id: String, f: (RootUiState) -> RootUiState) = synchronized(lock) {
        if (roots.none { it.id == id }) return@synchronized
        ui = ui + (id to f(ui[id] ?: RootUiState()))
    }

    /** The guarded provider for the root that owns [path]; null when the root id is unknown. */
    fun provider(rootId: String): LocalFileProvider? = synchronized(lock) {
        val r = roots.firstOrNull { it.id == rootId } ?: return null
        providers.getOrPut(rootId) { LocalFileProvider(File(r.path)) }
    }

    fun resolve(path: WorkspacePath, followFinalSymlink: Boolean = true): Guarded {
        val p = provider(path.rootId) ?: return Guarded.Escape("unknown root '${path.rootId}'")
        return p.guard.resolve(path.rel, followFinalSymlink)
    }

    private fun unknown(id: String) = WorkspaceEdit.Refused(WorkspaceRefusal.UNKNOWN_ROOT, "No root '$id'.")

    companion object {
        const val MAX_ROOTS = 32
        const val MAX_EXPANDED = 500
        const val MAX_LAST_OPEN = 20
        const val MAX_FRECENCY = 200
    }
}
