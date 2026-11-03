package dev.fonebrew.watchbridge

/**
 * Every message the v1 contract can carry. Sealed on purpose: the codec's `when` over it is
 * exhaustive, so adding a message without teaching the codec (and the tests) is a compile error.
 *
 * Constructors enforce each message's invariants with `require` (an [IllegalArgumentException]
 * means a *caller* tried to build an impossible message — a bug on the sending side). The decoder
 * never lets that escape: an incoming message that violates an invariant decodes to
 * [DecodeResult.Malformed]. Primary constructors deliberately have **no default arguments** — a
 * defaulted field is how an honest-looking `0` or `false` gets invented.
 */
public sealed interface WatchMessage

// ---------------------------------------------------------------------------------------------
// Handshake
// ---------------------------------------------------------------------------------------------

/**
 * The handshake: "this is the contract version I speak and the Fonebrew build I am." Sent by the
 * phone side. [contractVersion] is the version the *sender's app* speaks, independent of the
 * envelope `v` that describes how this very message is shaped — which is what lets a newer phone
 * still send an older-shaped hello that an older watch can read and then correctly call
 * [compatibility] on. (An envelope whose `v` this build does not know never gets this far: it
 * decodes to [DecodeResult.Incompatible].)
 */
public data class ContractHello(
    val contractVersion: Int,
    /** The Fonebrew app version string, shown on the watch when versions disagree. */
    val phoneAppVersion: String,
) : WatchMessage {
    init {
        require(contractVersion >= 1) { "contractVersion must be >= 1 (was $contractVersion)" }
        requireWireText("phoneAppVersion", phoneAppVersion, nonBlank = true)
    }

    /**
     * Whether this peer's hello can talk to a reader at [localVersion]. v1 is strict equality —
     * no range, no negotiation — so anything else is an [HelloCompatibility.Incompatible] that
     * names which side is newer.
     */
    public fun compatibility(localVersion: Int = WatchBridge.CONTRACT_VERSION): HelloCompatibility =
        if (contractVersion == localVersion) {
            HelloCompatibility.Compatible
        } else {
            HelloCompatibility.Incompatible(VersionMismatch(peerVersion = contractVersion, localVersion = localVersion))
        }

    public companion object {
        /** A hello stating this build's own [WatchBridge.CONTRACT_VERSION]. */
        public fun forThisBuild(phoneAppVersion: String): ContractHello =
            ContractHello(contractVersion = WatchBridge.CONTRACT_VERSION, phoneAppVersion = phoneAppVersion)
    }
}

// ---------------------------------------------------------------------------------------------
// Ask (the chat relay)
// ---------------------------------------------------------------------------------------------

/** Watch -> phone: a prompt typed or spoken on the wrist. Lands in the phone's tree as a normal turn. */
public data class AskRequest(
    /** Correlates every [AskChunk]/[AskError] answering this prompt. Minted by the watch. */
    val requestId: String,
    val prompt: String,
) : WatchMessage {
    init {
        requireWireText("requestId", requestId, nonBlank = true)
        requireWireText("prompt", prompt, nonBlank = true)
    }
}

/**
 * Phone -> watch: one piece of a streamed reply. A reply is a run of non-terminal chunks followed
 * by exactly one terminal chunk.
 *
 * **Invariant (enforced here and on decode): [usage] is present if and only if [terminal].** Token
 * usage is only knowable once generation ends; a non-terminal chunk carrying it would be a guess,
 * and a terminal chunk without it would let a reply end without declaring whether its numbers are
 * real. The terminal chunk's [text] may be empty (it can be a pure end marker).
 *
 * [text] is exactly the token text — no trimming, may be empty or whitespace. It must be
 * well-formed UTF-16: a chunk boundary must not split a surrogate pair (the relay buffers a lone
 * high surrogate into the next chunk), otherwise the wire would silently corrupt it.
 */
public data class AskChunk(
    val requestId: String,
    val text: String,
    val terminal: Boolean,
    /** Which engine answered (e.g. a model id). The wrist never hides who answered. */
    val engineId: String,
    /** `true` when a cloud provider answered: the watch shows the watched-object glyph. On-device is `false`. */
    val watchedObject: Boolean,
    /** Present only on the terminal chunk. */
    val usage: Usage?,
) : WatchMessage {
    init {
        requireWireText("requestId", requestId, nonBlank = true)
        requireWireText("text", text, nonBlank = false)
        requireWireText("engineId", engineId, nonBlank = true)
        require(terminal == (usage != null)) {
            if (terminal) {
                "a terminal chunk must carry usage (with estimated=true when the engine reported nothing)"
            } else {
                "usage may appear only on the terminal chunk"
            }
        }
    }

    public companion object {
        /** A non-terminal chunk (no usage, by construction). */
        public fun token(requestId: String, text: String, engineId: String, watchedObject: Boolean): AskChunk =
            AskChunk(requestId, text, terminal = false, engineId = engineId, watchedObject = watchedObject, usage = null)

        /** The terminal chunk, which must declare its [usage] honestly. */
        public fun terminal(requestId: String, text: String, engineId: String, watchedObject: Boolean, usage: Usage): AskChunk =
            AskChunk(requestId, text, terminal = true, engineId = engineId, watchedObject = watchedObject, usage = usage)
    }
}

/**
 * Token usage for a finished reply. **Real numbers only.**
 *
 * - `estimated = false` — the engine reported these counts; both must be present.
 * - `estimated = true` — the engine reported nothing (or only part). Any count present is the
 *   phone's approximation, labelled as such; a count the phone could not even approximate is
 *   `null` = *unknown*. Unknown is never encoded as `0`: a real `0` and "don't know" are
 *   different facts and the type keeps them apart.
 *
 * `estimated` has no default on purpose — the sender must say which it is.
 */
public data class Usage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val estimated: Boolean,
) {
    init {
        inputTokens?.let { require(it >= 0) { "inputTokens must be >= 0 (was $it)" } }
        outputTokens?.let { require(it >= 0) { "outputTokens must be >= 0 (was $it)" } }
        if (!estimated) {
            require(inputTokens != null && outputTokens != null) {
                "usage marked estimated=false (engine-reported) must carry both counts; if the engine reported nothing, send estimated=true"
            }
        }
    }

    public companion object {
        /** Counts the engine itself reported. */
        public fun engineReported(inputTokens: Long, outputTokens: Long): Usage =
            Usage(inputTokens, outputTokens, estimated = false)

        /** The phone's approximation, flagged as such (either count may be unknown). */
        public fun estimate(inputTokens: Long?, outputTokens: Long?): Usage =
            Usage(inputTokens, outputTokens, estimated = true)

        /** The engine reported nothing and the phone has no approximation: unknown, not zero. */
        public fun unknown(): Usage = Usage(inputTokens = null, outputTokens = null, estimated = true)
    }
}

/** Phone -> watch: the request could not be answered, in words a person can read on a wrist. */
public data class AskError(
    val requestId: String,
    /** Legible reason (e.g. "Phone is out of memory for this model"), not a stack trace or code. */
    val reason: String,
) : WatchMessage {
    init {
        requireWireText("requestId", requestId, nonBlank = true)
        requireWireText("reason", reason, nonBlank = true)
    }
}

// ---------------------------------------------------------------------------------------------
// Tasks
// ---------------------------------------------------------------------------------------------

/**
 * A task's stage, mirrored from the phone's `TaskState` so the wrist can render all four honestly
 * (the free floor only moves TODO <-> DONE; DOING/BLOCKED exist for the paid Board lens and still
 * reach the wrist as what they are). Each renders as a distinct shape/label, never by hue.
 */
public enum class WatchTaskState(internal val wire: String) {
    TODO("todo"),
    DOING("doing"),
    BLOCKED("blocked"),
    DONE("done"),
    ;

    internal companion object {
        fun fromWire(wire: String): WatchTaskState? = entries.firstOrNull { it.wire == wire }
    }
}

/** One row of a [TaskSnapshot]. */
public data class TaskEntry(
    val id: String,
    /** May legitimately be empty (the phone's store does not forbid it); shown as-is. */
    val title: String,
    val state: WatchTaskState,
) {
    init {
        requireWireText("task id", id, nonBlank = true)
        requireWireText("task title", title, nonBlank = false)
    }
}

/**
 * Phone -> watch: the free floor's tasks at a glance.
 *
 * [pendingCount] is the phone's *true* number of not-done tasks and is separate from [tasks] on
 * purpose: the Data Layer's message-size limit (~100 KB) means the relay may send only the first N
 * tasks, and the complication's pending count must still be right. Invariants: ids are distinct,
 * and [pendingCount] is at least the number of not-done entries actually listed (equal when the
 * list is complete). The list is not defensively copied — treat the instance as immutable.
 */
public data class TaskSnapshot(
    val tasks: List<TaskEntry>,
    val pendingCount: Int,
) : WatchMessage {
    init {
        require(pendingCount >= 0) { "pendingCount must be >= 0 (was $pendingCount)" }
        val ids = HashSet<String>()
        for (task in tasks) {
            require(ids.add(task.id)) { "duplicate task id in snapshot: \"${task.id.take(40)}\"" }
        }
        val listedPending = tasks.count { it.state != WatchTaskState.DONE }
        require(pendingCount >= listedPending) {
            "pendingCount ($pendingCount) is less than the $listedPending not-done tasks listed"
        }
    }
}

/**
 * Watch -> phone: "please set this task's done-ness to [done]". A *desired state*, not a flip, so a
 * retried or duplicated request is harmless. The phone's store remains the single writer.
 */
public data class TaskToggleRequest(
    val taskId: String,
    val done: Boolean,
) : WatchMessage {
    init {
        requireWireText("taskId", taskId, nonBlank = true)
    }
}

/** Phone -> watch: what became of a [TaskToggleRequest]. Authoritative: the watch reconciles to it. */
public sealed interface TaskToggleResult : WatchMessage {
    public val taskId: String

    /** The phone applied it (or it was already so). [done] is what the phone's store now holds. */
    public data class Applied(override val taskId: String, val done: Boolean) : TaskToggleResult {
        init {
            requireWireText("taskId", taskId, nonBlank = true)
        }
    }

    /** The phone did not change anything; [reason] says why in words (e.g. the task no longer exists). */
    public data class Rejected(override val taskId: String, val reason: String) : TaskToggleResult {
        init {
            requireWireText("taskId", taskId, nonBlank = true)
            requireWireText("reason", reason, nonBlank = true)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Loops
// ---------------------------------------------------------------------------------------------

/**
 * Phone -> watch: a read-only glance at one loop run (from the phone's `GraphRunLedger`).
 *
 * [spend] is nullable on purpose: `null` means the ledger has no figure — which is a different
 * fact from a real zero (an on-device run's real cost *is* 0, and is sent as such).
 */
public data class LoopRunSnapshot(
    val runId: String,
    val loopName: String,
    /** A glyph *key* (see [StateGlyphKeys]); the watch maps it to a shape. Never a color. */
    val stateGlyphKey: String,
    /** The step currently or last run; `null` before any step has started. */
    val stepName: String?,
    val spend: Spend?,
) : WatchMessage {
    init {
        requireWireText("runId", runId, nonBlank = true)
        requireWireText("loopName", loopName, nonBlank = true)
        require(StateGlyphKeys.isValid(stateGlyphKey)) {
            "stateGlyphKey must be a lowercase slug ([a-z][a-z0-9_]{0,31}) (was \"${stateGlyphKey.take(40)}\")"
        }
        stepName?.let { requireWireText("stepName", it, nonBlank = true) }
    }
}

/**
 * A run's spend as the ledger recorded it: an integer in the currency's **minor unit** (cents,
 * paise — exactly the ledger's `estCostMinor`), never a float, so there is no major/minor
 * ambiguity and no rounding. [estimated] mirrors the ledger flag (`true` = a guess, `false` = real
 * provider usage) and, like [Usage.estimated], has no default.
 */
public data class Spend(
    val amountMinor: Long,
    /** ISO-4217 alphabetic code, upper case (e.g. "USD", "INR"). */
    val currency: String,
    val estimated: Boolean,
) {
    init {
        require(amountMinor >= 0) { "amountMinor must be >= 0 (was $amountMinor)" }
        require(CURRENCY_CODE.matches(currency)) { "currency must be a 3-letter upper-case code (was \"${currency.take(8)}\")" }
    }

    private companion object {
        val CURRENCY_CODE = Regex("[A-Z]{3}")
    }
}

/**
 * The v1 vocabulary of loop-state glyph keys. The *set is open*: [LoopRunSnapshot.stateGlyphKey]
 * accepts any well-formed slug so a newer phone can introduce a state without breaking an older
 * watch, which renders an unrecognized key with a generic shape plus the key as its label (legible,
 * not hidden). These constants are what the Loops presenter maps to distinct shapes; none of them
 * names a color, by rule.
 */
public object StateGlyphKeys {
    public const val RUNNING: String = "running"
    public const val COMPLETE: String = "complete"
    public const val STOPPED: String = "stopped"
    public const val FAILED: String = "failed"

    /** The v1 keys, for exhaustive presenters and tests. */
    public val V1: List<String> = listOf(RUNNING, COMPLETE, STOPPED, FAILED)

    private val SLUG = Regex("[a-z][a-z0-9_]{0,31}")

    /** Whether [key] is a well-formed glyph key (not whether it is a *known* one). */
    public fun isValid(key: String): Boolean = SLUG.matches(key)
}

// ---------------------------------------------------------------------------------------------
// Version mismatch + hello compatibility
// ---------------------------------------------------------------------------------------------

/** Two contract versions that cannot talk (v1: any two different versions). */
public data class VersionMismatch(val peerVersion: Int, val localVersion: Int) {
    init {
        require(peerVersion >= 1 && localVersion >= 1) { "versions must be >= 1" }
        require(peerVersion != localVersion) { "not a mismatch: both sides speak v$peerVersion" }
    }

    /** `true` when the peer speaks a newer contract (so *this* side should update). */
    public val peerIsNewer: Boolean get() = peerVersion > localVersion

    /** A sentence a person can read, naming which side is older. */
    public val reason: String
        get() = if (peerIsNewer) {
            "The other device speaks WatchBrew contract v$peerVersion; this one speaks v$localVersion. Update this app."
        } else {
            "The other device speaks WatchBrew contract v$peerVersion; this one speaks v$localVersion. Update the other device's app."
        }
}

/** The outcome of [ContractHello.compatibility]. */
public sealed interface HelloCompatibility {
    public data object Compatible : HelloCompatibility
    public data class Incompatible(val mismatch: VersionMismatch) : HelloCompatibility
}
