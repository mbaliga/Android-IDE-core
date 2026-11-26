package dev.fonebrew.domain.gitcore

/** What went wrong, as a closed set a caller can branch on (never a bare crash). */
enum class GitErrorKind {
    /** Bytes do not parse as the claimed git structure. */
    MALFORMED,
    /** A configured ceiling (size, depth, count, chain) was exceeded. */
    LIMIT_EXCEEDED,
    NOT_FOUND,
    /** A path that could escape the work tree or touch `.git`. */
    UNSAFE_PATH,
    /** A trailer / CRC / object-id check failed. */
    CHECKSUM,
    /** A `.lock` file is held. */
    LOCKED,
    /** Compare-and-swap on a ref lost. */
    REF_CONFLICT,
    INVALID_REF_NAME,
    /** Local changes would be overwritten; [GitException.paths] lists them. */
    DIRTY_TREE,
    UNSUPPORTED,
    IO,
    INVALID_ARGUMENT,
}

/**
 * The one exception type the engine throws for bad input or refused operations. Hostile data
 * produces this (typed) and nothing else: no ArrayIndexOutOfBounds, no OutOfMemoryError.
 * Messages never carry secrets (the engine never holds any).
 */
class GitException(
    val kind: GitErrorKind,
    message: String,
    val paths: List<String> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal fun malformed(msg: String): Nothing = throw GitException(GitErrorKind.MALFORMED, msg)
internal fun limit(msg: String): Nothing = throw GitException(GitErrorKind.LIMIT_EXCEEDED, msg)

/** Tunable ceilings. Defaults are sized for a phone; every one is enforced, none is advisory. */
data class GitLimits(
    /** Largest object the engine will hold fully in memory (inflated). */
    val maxInMemoryObject: Long = 64L * 1024 * 1024,
    /** Total bytes the delta-base cache may retain. */
    val deltaCacheBytes: Long = 16L * 1024 * 1024,
    /** Hard cap on a delta chain length. */
    val maxDeltaChain: Int = 4096,
    val maxTreeEntries: Int = 1_000_000,
    val maxTreeDepth: Int = 256,
    val maxPackObjects: Long = 50_000_000L,
    /** Largest pack the indexer will accept from a stream. */
    val maxPackBytes: Long = 4L * 1024 * 1024 * 1024,
    /** Largest commit/tag body. */
    val maxCommitBytes: Int = 8 * 1024 * 1024,
    val maxPathBytes: Int = 4096,
    val maxIndexEntries: Int = 4_000_000,
    /** Blobs above this are streamed by [ObjectDatabase.streamTo] instead of materialised. */
    val streamThreshold: Long = 8L * 1024 * 1024,
)
