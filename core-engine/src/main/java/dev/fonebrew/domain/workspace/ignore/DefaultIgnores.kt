package dev.fonebrew.domain.workspace.ignore

/**
 * Tooling directories that almost nobody wants in a tree, a quick-open list or a search result:
 * build outputs, dependency caches, IDE state. A per-root toggle (`RootSettings.useDefaultIgnores`)
 * turns the whole set off; the list is written as ordinary gitignore lines so the user can read
 * exactly what is being hidden (legibility) and so one engine evaluates it.
 */
object DefaultIgnores {
    val PATTERNS: List<String> = listOf(
        ".git/",
        ".gradle/",
        ".cxx/",
        "build/",
        "node_modules/",
        "__pycache__/",
        ".idea/",
        ".DS_Store",
    )
}
