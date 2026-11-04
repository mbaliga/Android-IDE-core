package dev.fonebrew.ui.regular

/**
 * The five bottom-tab destinations [RegularShell] hosts (bifurcation wave 1, lane R build step
 * 2). [entries] order IS the tab bar's left-to-right order — CHAT is always first, the home tab
 * system Back returns to before it ever exits the app (conventional bottom-nav contract). Tree
 * and Loops are deliberately NOT destinations here: they open from the Chat header affordance
 * and the Develop entry lane Q already added (same entry points ASOC's semantic-zoom pinch
 * settles on), not as peer tabs.
 */
enum class RegularDestination { CHAT, CHATS, PROJECTS, DEVELOP, SETTINGS }

/**
 * Pure index ⇄ destination mapping behind [RegularShell] — kept free of Compose so it's
 * JVM-testable on its own, this repo's "render is owner-verified on device, the decision logic
 * behind it is unit-tested" convention (see e.g. `RoomTabBarPosition`, `HyleModePickerLayout`
 * in the Hyle submodule).
 */
object RegularShellPresenter {

    /** Tab order == destination order. */
    val destinations: List<RegularDestination> = RegularDestination.entries

    /** The destination `HyleBottomTabBar`'s `onSelect(index)` should switch to, or null for an
     *  out-of-range index (defensive — `HyleBottomTabBar` never actually hands one back). */
    fun destinationForIndex(index: Int): RegularDestination? = destinations.getOrNull(index)

    /** The tab-bar index to highlight as selected for a given destination — the inverse of
     *  [destinationForIndex]. */
    fun indexForDestination(destination: RegularDestination): Int = destinations.indexOf(destination)

    /** Whether the shell's own Back handler should pop to the Chat tab — conventional bottom-nav
     *  semantics: Back always returns to the first/home tab before it ever falls through to the
     *  system default (process exit). False only for CHAT itself, where Back should fall through. */
    fun backTargetsChat(current: RegularDestination): Boolean = current != RegularDestination.CHAT
}
