package dev.fonebrew.watchbridge

/**
 * The outcome of [WatchBridgeCodec.decode] — exactly one of three typed results, never an
 * exception (see the decode contract in [WatchBridge]).
 */
public sealed interface DecodeResult {

    /** The whole message parsed and every invariant held. */
    public data class Decoded(val message: WatchMessage) : DecodeResult

    /**
     * A well-formed WatchBrew envelope whose `v` is not [WatchBridge.CONTRACT_VERSION]. The payload
     * was **not** parsed — its shape belongs to another version. [claimedType] is the envelope's
     * `type` string if it had one, unverified, purely so a UI/log can say what kind of message
     * could not be read.
     */
    public data class Incompatible(val mismatch: VersionMismatch, val claimedType: String?) : DecodeResult {
        /** A sentence a person can read: which side is older and what to update. */
        public val reason: String get() = mismatch.reason
    }

    /** Not a valid v1 WatchBrew message. All-or-nothing: nothing was salvaged. [reason] is for logs/debug, not the wrist UI. */
    public data class Malformed(val reason: String) : DecodeResult
}
