package dev.fonebrew.watchbridge

/**
 * The Wearable Data Layer **paths** both sides of the pair address (docs/design/watchbrew.md section 2).
 * Paths live in the contract module, not in either consumer, so the phone relay and the watch app
 * cannot drift apart on a string. They are pure constants: this module knows nothing about the
 * transport that carries them (the Data Layer lives in the two consumers, never here).
 *
 * ## Unversioned on purpose
 *
 * The contract version rides **inside the envelope** (`v`, see [WatchBridge.CONTRACT_VERSION]), not in
 * the path. If the path carried a version too, a watch speaking a newer contract would address a path
 * an older phone never subscribed to, the message would vanish silently, and the wrist could only say
 * "no reply" instead of "update Fonebrew". With one stable prefix the older phone still *receives* the
 * newer envelope, decodes it to [DecodeResult.Incompatible], and answers with its [ContractHello] so
 * the watch can say exactly which side is old. A path *rename* is therefore a breaking change like any
 * other and would bump [WatchBridge.CONTRACT_VERSION].
 *
 * ## Directions and carriers
 *
 * Each path has exactly one direction and one carrier. A receiver should reject a message whose
 * decoded type does not match the path it arrived on (an `AskRequest` is only ever valid on [ASK]).
 *
 * | path | direction | carrier | message |
 * |---|---|---|---|
 * | [SYNC] | watch to phone | message | none (empty body): "send me hello, tasks and loop runs" |
 * | [ASK] | watch to phone | message | [AskRequest] |
 * | [TASK_TOGGLE] | watch to phone | message | [TaskToggleRequest] |
 * | [HELLO] | phone to watch | message | [ContractHello] |
 * | [TASKS] | phone to watch | message | [TaskSnapshot] |
 * | [TASK_TOGGLE_RESULT] | phone to watch | message | [TaskToggleResult] |
 * | [LOOPS] | phone to watch | message | [LoopRunSnapshot], one message per run |
 * | [ASK_REPLY] | phone to watch | channel | [StreamFraming] frames of [AskChunk] then end, or one [AskError] |
 *
 * The "message" carrier is one self-contained envelope string as UTF-8 bytes
 * ([WatchBridgeCodec.encodeToBytes]). The "channel" carrier is a byte stream of length-prefixed
 * frames ([StreamFraming]) because a streamed reply is many ordered envelopes and a message-per-token
 * scheme has no ordering or flow-control guarantee.
 *
 * [SYNC] has no envelope on purpose: it is a pure "please send" trigger with nothing to version.
 * Compatibility is decided by the watch from the [HELLO] the phone answers with.
 */
public object WatchPaths {
    /** Every WatchBrew path starts with this; a listener subscribes to this prefix and nothing wider. */
    public const val PREFIX: String = "/watchbrew/"

    /** Watch to phone, message, empty body: ask the phone to push [HELLO], [TASKS] and [LOOPS]. */
    public const val SYNC: String = "/watchbrew/sync"

    /** Watch to phone, message: an [AskRequest]. */
    public const val ASK: String = "/watchbrew/ask"

    /** Watch to phone, message: a [TaskToggleRequest]. */
    public const val TASK_TOGGLE: String = "/watchbrew/tasks/toggle"

    /** Phone to watch, message: a [ContractHello]. */
    public const val HELLO: String = "/watchbrew/hello"

    /** Phone to watch, message: a [TaskSnapshot]. */
    public const val TASKS: String = "/watchbrew/tasks"

    /** Phone to watch, message: a [TaskToggleResult]. */
    public const val TASK_TOGGLE_RESULT: String = "/watchbrew/tasks/toggle/result"

    /** Phone to watch, message: one [LoopRunSnapshot] (the phone sends the most recent runs, one message each). */
    public const val LOOPS: String = "/watchbrew/loops"

    /** Phone to watch, channel: the streamed reply to one [AskRequest], as [StreamFraming] frames. */
    public const val ASK_REPLY: String = "/watchbrew/ask/reply"

    /** Every path above, for exhaustive listeners and tests. */
    public val ALL: List<String> = listOf(SYNC, ASK, TASK_TOGGLE, HELLO, TASKS, TASK_TOGGLE_RESULT, LOOPS, ASK_REPLY)

    /** Whether [path] belongs to WatchBrew at all (a listener should ignore everything else). */
    public fun isWatchBrewPath(path: String): Boolean = path.startsWith(PREFIX)
}
