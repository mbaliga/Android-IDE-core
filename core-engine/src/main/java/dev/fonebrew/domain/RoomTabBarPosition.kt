package dev.fonebrew.domain

/**
 * Pure semantics for the per-room tab-bar-position override map (persistence lives in
 * [dev.fonebrew.data.SessionStore]). A room absent from the map just follows the universal
 * default; passing a null position clears an existing override rather than storing one.
 */
object RoomTabBarPosition {

    /** [current] with [roomId] set to [position], or removed entirely when [position] is null. */
    fun set(current: Map<String, String>, roomId: String, position: String?): Map<String, String> =
        if (position == null) current - roomId else current + (roomId to position)

    /** The effective position for [roomId]: its own override if set, else [universal]. */
    fun effective(overrides: Map<String, String>, roomId: String, universal: String): String =
        overrides[roomId] ?: universal
}
