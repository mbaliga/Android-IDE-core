package dev.aarso.domain.a11y

/**
 * Accessibility linearization for the spatial UI (Doc 00 §3.5 / §2.4): a screen
 * reader cannot perceive a *room model* — it walks a single, ordered list of stops.
 * This file is the bridge: it flattens the Chat origin + four cardinal rooms + the
 * Z-axis (Tree/Loops) into a **deterministic reading order** (room announce → header →
 * navigation hint → content) and names each room change ("Conversations room") so
 * TalkBack can traverse a space that has no top/left/right in its own model.
 *
 * Legibility by construction: the same compass adjacency the sighted user sees as a
 * direction becomes a spoken hint, and Back from anywhere returns to the Chat origin
 * (Doc 00 §2.7) — the cardinal rooms are peers, not a stack. RTL mirrors only the
 * horizontal pair (Conversations/Settings); vertical stays put (Doc 00 §3.3).
 *
 * Pure domain (no Android, no `View`, no `AccessibilityNodeInfo`) — every function is
 * a deterministic mapping, JVM-tested. The UI layer renders these stops into real
 * accessibility nodes; this is the order + the words.
 */

/** The cardinal rooms. CHAT is the origin; the other four are its compass peers. */
enum class Room { CHAT, CONVERSATIONS, SETTINGS, PROJECT, DEVELOP }

/**
 * The Z-axis depth at a room. ORIGIN is the room itself (Chat at the centre); TREE
 * and LOOPS are the two Z-views layered over the current conversation.
 */
enum class Depth { TREE, ORIGIN, LOOPS }

/** A point in the spatial model: which room, at which Z-depth (origin by default). */
data class SpatialPosition(val room: Room, val depth: Depth = Depth.ORIGIN)

/**
 * One stop in the flattened reading order. [kind] tells the UI how to render it
 * (a polite live-region announce, a heading, a hint, ordinary content); [label] is
 * the spoken text.
 */
data class ReadingStop(val kind: Kind, val label: String) {
    enum class Kind { ROOM_ANNOUNCE, HEADER, NAV_HINT, CONTENT }
}

/** The deterministic spatial → linear model. Every function is a pure mapping. */
object SpatialLinearization {

    /** Compass direction keys, in a fixed spoken order (left, right, up, down). */
    private const val LEFT = "left"
    private const val RIGHT = "right"
    private const val UP = "up"
    private const val DOWN = "down"

    /**
     * The room-change announcement spoken when focus enters a room (live region).
     * Deterministic and stable — TalkBack reads this verbatim. Chat names itself the
     * home; the cardinal rooms announce as "<Name> room".
     */
    fun announce(room: Room): String = when (room) {
        Room.CHAT -> "Chat, home"
        Room.CONVERSATIONS -> "Conversations room"
        Room.SETTINGS -> "Settings room"
        Room.PROJECT -> "Project room"
        Room.DEVELOP -> "Develop room"
    }

    /**
     * The full announce for a [SpatialPosition]: at ORIGIN it is the room announce;
     * a Z-view names the layer over the current conversation instead.
     */
    fun announce(pos: SpatialPosition): String = when (pos.depth) {
        Depth.ORIGIN -> announce(pos.room)
        Depth.TREE -> "Tree view of current conversation"
        Depth.LOOPS -> "Loop graph"
    }

    /**
     * The compass adjacency from a room, keyed by direction. Only CHAT (the origin)
     * has cardinal neighbours — left/right/up/down to its four peers. From a cardinal
     * room the only move is Back to the Chat origin, so its neighbour map is empty
     * (see [backTarget]); the rooms are peers, not a chain.
     */
    fun neighbors(room: Room): Map<String, Room> = when (room) {
        Room.CHAT -> linkedMapOf(
            LEFT to Room.CONVERSATIONS,
            RIGHT to Room.SETTINGS,
            UP to Room.PROJECT,
            DOWN to Room.DEVELOP,
        )
        else -> emptyMap()
    }

    /**
     * Where Back goes from [pos]. A Z-view (depth != ORIGIN) drops back to its room's
     * origin; a cardinal room returns to the Chat origin; Chat origin stays put
     * (Doc 00 §2.7 — Back from any room returns to the Chat origin).
     */
    fun backTarget(pos: SpatialPosition): SpatialPosition = when {
        pos.depth != Depth.ORIGIN -> SpatialPosition(pos.room, Depth.ORIGIN)
        pos.room != Room.CHAT -> SpatialPosition(Room.CHAT, Depth.ORIGIN)
        else -> SpatialPosition(Room.CHAT, Depth.ORIGIN)
    }

    /**
     * Flatten a position into the deterministic TalkBack reading order:
     *  1. a [ReadingStop.Kind.ROOM_ANNOUNCE] (from [announce]),
     *  2. the [ReadingStop.Kind.HEADER] ([headerLabel]),
     *  3. a [ReadingStop.Kind.NAV_HINT] describing the moves available here
     *     (cardinal directions at the Chat origin, "Back to Chat" elsewhere),
     *  4. one [ReadingStop.Kind.CONTENT] per [contentLabels] entry, in input order.
     *
     * Order and counts are fixed: `3 + contentLabels.size` stops, content order
     * preserved exactly. No content collapses or reorders.
     */
    fun linearize(
        pos: SpatialPosition,
        headerLabel: String,
        contentLabels: List<String>,
    ): List<ReadingStop> {
        val stops = ArrayList<ReadingStop>(3 + contentLabels.size)
        stops += ReadingStop(ReadingStop.Kind.ROOM_ANNOUNCE, announce(pos))
        stops += ReadingStop(ReadingStop.Kind.HEADER, headerLabel)
        stops += ReadingStop(ReadingStop.Kind.NAV_HINT, navHint(pos))
        for (label in contentLabels) {
            stops += ReadingStop(ReadingStop.Kind.CONTENT, label)
        }
        return stops
    }

    /**
     * The spoken navigation hint for [pos]. A Z-view offers Back to its room; a
     * cardinal room offers Back to Chat; the Chat origin spells out its four compass
     * directions in fixed order.
     */
    private fun navHint(pos: SpatialPosition): String {
        if (pos.depth != Depth.ORIGIN) return "Back to ${roomName(pos.room)}"
        val n = neighbors(pos.room)
        if (n.isEmpty()) return "Back to Chat"
        return n.entries.joinToString(", ") { (dir, room) -> "$dir to ${roomName(room)}" }
    }

    /** The plain room name used inside hints. */
    private fun roomName(room: Room): String = when (room) {
        Room.CHAT -> "Chat"
        Room.CONVERSATIONS -> "Conversations"
        Room.SETTINGS -> "Settings"
        Room.PROJECT -> "Project"
        Room.DEVELOP -> "Develop"
    }

    /**
     * Mirror the compass for RTL (Doc 00 §3.3): the horizontal pair swaps — what was
     * left (Conversations) becomes right and vice-versa — while vertical (up/down)
     * is unchanged. Any non-left/right key passes through untouched. Deterministic.
     */
    fun rtlMirror(neighbors: Map<String, Room>): Map<String, Room> {
        val mirrored = LinkedHashMap<String, Room>(neighbors.size)
        for ((dir, room) in neighbors) {
            val key = when (dir) {
                LEFT -> RIGHT
                RIGHT -> LEFT
                else -> dir
            }
            mirrored[key] = room
        }
        return mirrored
    }
}
