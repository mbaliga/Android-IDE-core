package dev.fonebrew.domain.thread

/**
 * A user- or system-placed marker on the conversation tree (THREAD_TOPOLOGY_PLAN.md's
 * `thread_markers` table; wire shape `schemas/thread/thread-marker.schema.json`) — a chapter
 * boundary, a fresh-session start, a compaction-run boundary, or a lineage-source pointer.
 *
 * Deliberately shaped like [dev.fonebrew.domain.curation.Version]/[dev.fonebrew.domain.curation.MessageBookmark]
 * (`at: Long` epoch millis, plain data class, no Room/Android dependency): this is a RETROACTIVE
 * fact about the append-only message tree (THREAD_TOPOLOGY_PLAN.md binding constraint 5 —
 * "metadata minted at insert, never edited; retroactive facts -> Room tables"), never a write to
 * `MessageNode.metadata`. [dev.fonebrew.data.ThreadMarkerStore] is its Room-backed home, the same
 * relationship [dev.fonebrew.data.CurationStore] has to `Version`.
 *
 * @property anchorMsgId the message this marker anchors to. Required non-null for [ThreadMarkerKind.CHAPTER];
 *   null is valid for [ThreadMarkerKind.SESSION_START] (a session boundary needs no prior message)
 *   and for the two system-written kinds until WP2/WP3 populate them.
 * @property label required non-blank for CHAPTER (the user-entered chapter name); null otherwise.
 */
data class ThreadMarker(
    val id: String,
    val rootId: String,
    val anchorMsgId: String? = null,
    val kind: ThreadMarkerKind,
    val label: String? = null,
    val note: String? = null,
    val at: Long,
    val source: ThreadMarkerSource,
    val payloadJson: String? = null,
    val schemaVersion: String = "1.0.0",
    val unknownFields: Map<String, Any?> = emptyMap(),
) {
    init {
        require(schemaVersion.matches(Regex("^1\\.\\d+\\.\\d+$"))) {
            "ThreadMarker.schemaVersion must be major version 1 (got '$schemaVersion') — " +
                "a reader MUST reject an unsupported major before construction (FB-RAT-COM-003)."
        }
        require(id.isNotBlank()) { "ThreadMarker.id must be non-blank (FB-RAT-COM-002)." }
        require(rootId.isNotBlank()) { "ThreadMarker.rootId must be non-blank." }
        if (kind == ThreadMarkerKind.CHAPTER) {
            require(!anchorMsgId.isNullOrBlank()) { "ThreadMarker: CHAPTER requires a non-blank anchorMsgId." }
            require(!label.isNullOrBlank()) { "ThreadMarker: CHAPTER requires a non-blank label." }
        }
    }
}

/** THREAD_TOPOLOGY_PLAN.md's four marker kinds, verbatim. */
enum class ThreadMarkerKind {
    CHAPTER,
    SESSION_START,
    COMPACTION_RUN,
    LINEAGE_SRC,
}

/** USER for a TurnActionsSheet action; SYSTEM for an automated writer (WP2 fork/spawn, WP3 compaction). */
enum class ThreadMarkerSource {
    USER,
    SYSTEM,
}
