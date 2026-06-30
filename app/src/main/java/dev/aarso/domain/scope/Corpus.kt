package dev.aarso.domain.scope

/**
 * The **corpus**: the attributed pool of knowledge a turn may draw on, before any
 * scoping or budgeting. Each [CorpusPiece] is a single, *attributed* unit — it always
 * knows where it came from ([CorpusSource]), which project it belongs to, how big it is
 * ([tokenCount]), and how it should be prioritised when the budget is tight ([pinned],
 * [recencyRank]).
 *
 * Attribution is the point. Doc 03's design thesis is legibility: the user can always
 * see exactly which files / conversations / memories / attachments were fed to the model
 * and which were cut. So a piece carries its provenance, not just its text — the
 * assembly engine ([ContextAssembly]) turns a corpus into an exact "included vs cut"
 * ledger, and that ledger is only as honest as the attribution here.
 *
 * Pure domain (no Android, no IO, no embedder), JVM-tested.
 */
data class CorpusPiece(
    /** Stable id — also the final, deterministic tie-breaker in prioritised truncation. */
    val id: String,
    /** Where this knowledge came from — its provenance for the legibility ledger. */
    val source: CorpusSource,
    /** The project this piece belongs to; scope filtering keys on this. */
    val projectId: String,
    /** Estimated token cost of including this piece verbatim. Treated as `>= 0`. */
    val tokenCount: Int,
    /**
     * Pinned pieces are user-promised: they are **always included**, surviving
     * prioritised truncation even when that pushes the corpus over budget (the meter then
     * reports the overage honestly). A pin is a commitment, not a hint.
     */
    val pinned: Boolean,
    /** 0 = most recent; larger = older. The recency ordering used after pins. */
    val recencyRank: Int,
    /** A short, human-facing description for the included/cut ledger. */
    val label: String,
)

/**
 * Provenance of a [CorpusPiece] — a sealed taxonomy so every unit of context is
 * attributable to a concrete origin in the user's own data.
 */
sealed interface CorpusSource {
    /** A file in a project repo, named by its repo-relative [path]. */
    data class RepoFile(val path: String) : CorpusSource

    /**
     * A conversation in the append-only message tree. [convId] names the conversation;
     * [nodeId] optionally pins a specific node (turn) within it, else the whole thread.
     */
    data class Conversation(val convId: String, val nodeId: String? = null) : CorpusSource

    /** A long-term memory entry, named by its [entryId]. */
    data class Memory(val entryId: String) : CorpusSource

    /** A user-supplied attachment, named by its file [name]. */
    data class Attachment(val name: String) : CorpusSource
}

/**
 * A holder over a flat list of [CorpusPiece]s with pure helpers. A [Corpus] is just the
 * raw pool; scoping and budgeting happen in [ContextAssembly] over a filtered corpus.
 */
data class Corpus(val pieces: List<CorpusPiece>) {

    /** Total token cost of every piece in this corpus (negative counts clamped to 0). */
    fun totalTokens(): Int = pieces.sumOf { maxOf(0, it.tokenCount) }

    /** The corpus restricted to pieces whose [CorpusPiece.projectId] is in [projectIds]. */
    fun filterToProjects(projectIds: Set<String>): Corpus =
        Corpus(pieces.filter { it.projectId in projectIds })

    /** Convenience: this corpus is empty when it holds no pieces. */
    fun isEmpty(): Boolean = pieces.isEmpty()

    companion object {
        /** An empty corpus — the honest result of an empty or unmatched scope. */
        val EMPTY: Corpus = Corpus(emptyList())
    }
}
