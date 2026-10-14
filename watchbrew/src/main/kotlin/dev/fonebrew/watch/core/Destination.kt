package dev.fonebrew.watch.core

/**
 * Where the app can open. The Activity is exported (the Tile and the complication launch it with an
 * extra), so the extra is **untrusted input**: [fromExtra] maps it through a closed set and anything
 * else — null, empty, unknown, a path trick — is [HOME].
 */
enum class Destination(val route: String) {
    HOME("home"),
    ASK("ask"),
    TASKS("tasks"),
    LOOPS("loops"),
    ;

    companion object {
        fun fromExtra(raw: String?): Destination = entries.firstOrNull { it.route == raw } ?: HOME
    }
}
