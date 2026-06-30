package dev.aarso.domain.library

/**
 * **Conversations Room** (Doc 02): the left-room list of every chat you own. This is the
 * thin, pure summary the room renders one row per — *not* the message tree itself. The
 * tree is the spine; a [ConversationSummary] is a derived, denormalised projection used
 * only to filter, sort, group, and flair the list. Restoring/branching/etc. still happen
 * on the tree, never here.
 *
 * On-thesis legibility: the row carries enough fact to make the list self-explaining
 * without opening the chat — *what kind* of work it holds ([kinds]), *whether you pinned
 * it* ([starred]), *which project owns it* ([projectId]), and how alive it is ([branchCount],
 * [useCount], [lastActivityMillis]). The *model flairs* (which models touched it, with
 * on-device⇄cloud provenance) are derived separately from the usage ledger — see
 * [ModelFlairs] — so this record stays free of inference detail.
 *
 * A single chat may hold **both** text turns and image turns; [kinds] is therefore a set,
 * and the filter tabs use *contains* semantics (a mixed chat shows under Text **and**
 * Image **and** All). See [ConvFilter].
 *
 * Pure domain, JVM-tested. No Android, no clock, no I/O — every timestamp is supplied by
 * the writer, never read from `System.currentTimeMillis()` in here.
 */
data class ConversationSummary(
    /** Stable chat id (the message-tree root id). The deterministic tie-break key for sorts. */
    val id: String,
    /** Human title shown on the row. Collated locale-correctly when sorting by title. */
    val title: String,
    /** Epoch millis of the most recent turn — drives RECENT sort and group ordering. */
    val lastActivityMillis: Long,
    /** Epoch millis the chat was first created — drives CREATED sort. */
    val createdMillis: Long,
    /** Project this chat belongs to, or `null` for a loose chat (the "No project" bucket). */
    val projectId: String?,
    /** Which kinds of turn this chat contains. A chat may be BOTH [ConvKind.TEXT] and [ConvKind.IMAGE]. */
    val kinds: Set<ConvKind>,
    /** `true` if the user pinned this chat (the Starred tab). */
    val starred: Boolean,
    /** How many branches hang off this chat — drives MOST_BRANCHED sort. */
    val branchCount: Int,
    /** How many times the chat has been opened/used — drives MOST_USED sort. */
    val useCount: Int,
)

/**
 * What kind of work a chat holds. A chat is not pinned to one: an image chat that also
 * carries text turns is **both** [TEXT] and [IMAGE], which is why [ConversationSummary.kinds]
 * is a `Set` and the filter tabs use *contains* semantics.
 */
enum class ConvKind { TEXT, IMAGE }
