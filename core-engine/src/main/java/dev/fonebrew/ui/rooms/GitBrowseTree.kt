package dev.fonebrew.ui.rooms

import dev.aarso.hyle.component.HyleContextMenuItem
import dev.aarso.hyle.component.HyleTreeIconKind
import dev.aarso.hyle.component.HyleTreeItem
import dev.fonebrew.data.GitBrowse

/**
 * Desktop-class kit §3 ("Agentic IDE / GitBrowse repo browser" → `HyleTree`) — pure mapping from
 * [GitBrowse]'s per-directory listings into a [HyleTreeItem] forest, with no Compose/Android
 * dependency so it's JVM-testable independent of `GitBrowser`'s Dialog.
 *
 * [childrenByPath] holds only the directories [GitBrowser] has actually fetched via
 * [GitBrowse.list] so far — `""` is the repo root (fetched eagerly on open); any other
 * directory is fetched lazily, the first time its chevron opens it (mirroring the *existing*
 * navigation/fetch GitBrowser already performed one level at a time — this only changes how the
 * result is laid out, not what's fetched or when). A directory whose path isn't yet a key in
 * [childrenByPath] still renders — `hasChildren = true` so its chevron shows — just with no
 * children under it until the fetch lands and recomposition picks up the new map entry.
 */
internal fun buildGitBrowseForest(childrenByPath: Map<String, List<GitBrowse.Entry>>): List<HyleTreeItem> {
    fun toItem(e: GitBrowse.Entry): HyleTreeItem = if (e.isDir) {
        HyleTreeItem(
            id = e.path,
            label = e.name,
            iconKind = HyleTreeIconKind.FOLDER,
            hasChildren = true,
            children = childrenByPath[e.path]?.map(::toItem).orEmpty(),
        )
    } else {
        HyleTreeItem(id = e.path, label = e.name, iconKind = HyleTreeIconKind.FILE, hasChildren = false)
    }
    return childrenByPath[""]?.map(::toItem).orEmpty()
}

/**
 * The long-press [dev.aarso.hyle.component.HyleContextMenu] for one tree row, gated on node
 * kind — mapped ONLY to operations `GitBrowser` already performs: a directory offers
 * Expand/Collapse (the same toggle its chevron drives), a file offers Open (the same
 * [GitBrowse.read] its row tap already does); both kinds offer Copy path, the same
 * `LocalClipboardManager` primitive already used for message copy in `ChatScreen.kt` — a
 * platform utility, not a new git/data operation. No New file/Rename/Delete: `GitBrowse`/
 * `GitEdit` expose no create, rename, or delete flow for this surface to call, so none are
 * offered (desktop-class-kit.md §3 gates those on the surface already having the flow).
 */
internal fun gitBrowseMenuItems(isDir: Boolean, expanded: Boolean): List<HyleContextMenuItem> = buildList {
    if (isDir) {
        add(HyleContextMenuItem(id = "toggle", label = if (expanded) "Collapse" else "Expand"))
    } else {
        add(HyleContextMenuItem(id = "open", label = "Open"))
    }
    add(HyleContextMenuItem(id = "copy_path", label = "Copy path"))
}
