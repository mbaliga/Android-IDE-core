package dev.fonebrew.domain.gitcore.client

/** Typed progress for clone / fetch / push. Every number here was measured, none estimated. */
sealed interface TransferEvent {
    data class Connecting(val remote: String) : TransferEvent

    /** The server's "Enumerating/Counting/Compressing objects" phase; [percent] only when it printed one. */
    data class Counting(val stage: String, val percent: Int?, val text: String) : TransferEvent

    /** Any other server progress line, sanitised and trimmed. */
    data class RemoteMessage(val text: String) : TransferEvent

    /** Pack bytes received so far; [totalObjects] is the count the pack header declared (unknown until it arrives). */
    data class Receiving(val bytes: Long, val totalObjects: Long?) : TransferEvent

    /** The whole pack is on disk; the engine is indexing and resolving deltas. */
    data class ResolvingDeltas(val totalObjects: Long?) : TransferEvent

    /** Checking that everything the new refs reach is present, before any ref moves. */
    object Verifying : TransferEvent

    /** Writing the work tree. The engine reports start and end; per-file progress is not exposed (follow-up). */
    data class CheckingOut(val done: Boolean, val files: Int?) : TransferEvent

    /** Push: objects chosen for the pack, then bytes sent. */
    data class Packing(val objects: Int) : TransferEvent
    data class Sending(val bytes: Long) : TransferEvent
}
