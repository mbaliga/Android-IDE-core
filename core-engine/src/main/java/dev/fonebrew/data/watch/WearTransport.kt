package dev.fonebrew.data.watch

import java.io.IOException

/**
 * The relay's view of the Wearable Data Layer: the smallest surface [WearRelayPresenter] needs, with
 * **no Android or Play Services types** so every line of relay logic is JVM-testable against a fake.
 * The real implementation is [GmsWearTransport] (device-only, deliberately thin and untested here).
 *
 * Why two carriers (docs/design/watchbrew.md section 2): a single request or snapshot is one
 * self-contained envelope, which is exactly what `MessageClient` is for. A streamed reply is many
 * ordered envelopes: `MessageClient` is fire-and-forget with no ordering or flow-control guarantee and
 * would turn a 40-token answer into 40 independent deliveries, so the reply travels on a
 * `ChannelClient` byte stream as `dev.fonebrew.watchbridge.StreamFraming` frames instead. The cost
 * (an extra open/close handshake per question, and a receiver on the watch for `onChannelOpened`) is
 * worth it for ordered, back-pressured delivery. Nothing about this port is GMS-specific, so a
 * sovereign transport (the design's parked BLE follow-up) would implement the same two interfaces.
 */
interface WearTransport {

    /** One envelope to [path] on the node [nodeId] (the `MessageClient` carrier). Throws [IOException] if it cannot be sent. */
    @Throws(IOException::class)
    suspend fun send(nodeId: String, path: String, payload: ByteArray)

    /** Open the streamed-reply channel to [nodeId] at [path]. Throws [IOException] if the watch is unreachable. */
    @Throws(IOException::class)
    suspend fun openReplyChannel(nodeId: String, path: String): ReplyChannel
}

/** One open reply stream. The relay writes frames, then [close]s; it never reads. */
interface ReplyChannel {

    /** Append one already-framed message. Throws [IOException] when the link is gone (the relay then stops generating). */
    @Throws(IOException::class)
    suspend fun write(frame: ByteArray)

    /** End the stream so the reader sees end-of-stream. Idempotent; never throws. */
    suspend fun close()
}
