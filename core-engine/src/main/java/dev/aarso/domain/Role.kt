package dev.aarso.domain

/**
 * The author of a message node. Mirrors the three roles in the message tree
 * spine (handoff §2). [wire] is the stable string persisted in the database and
 * sent to chat templates, kept separate from the enum name so renames never
 * migrate data.
 */
enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun fromWire(value: String): Role =
            entries.firstOrNull { it.wire == value }
                ?: error("Unknown role: $value")
    }
}
