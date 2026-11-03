package dev.fonebrew.watchbridge

import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The one entry point both sides use: [WatchMessage] <-> the versioned JSON envelope described in
 * [WatchBridge]. Encoding is total for any constructible message; decoding **never throws** and
 * returns a [DecodeResult] (Decoded / Incompatible / Malformed), all-or-nothing.
 *
 * Transport-agnostic: this class knows nothing about the Wearable Data Layer. A consumer hands the
 * text/bytes it received to [decode] and sends what [encode]/[encodeToBytes] returns.
 */
public object WatchBridgeCodec {

    /** Serializes [message] to one self-describing envelope string (strict JSON, UTF-16 well-formed). */
    public fun encode(message: WatchMessage): String =
        JSONObject().apply {
            put(KEY_CONTRACT, WatchBridge.CONTRACT_NAME)
            put(KEY_VERSION, WatchBridge.CONTRACT_VERSION)
            put(KEY_TYPE, message.wireType().wire)
            put(KEY_PAYLOAD, PayloadCodecs.encode(message))
        }.toString()

    /** [encode] as UTF-8 bytes — what a Data Layer `MessageClient`/`ChannelClient` carries. */
    public fun encodeToBytes(message: WatchMessage): ByteArray = encode(message).toByteArray(Charsets.UTF_8)

    /**
     * Strict UTF-8: a malformed byte sequence is [DecodeResult.Malformed], never silently replaced
     * with U+FFFD (a replaced byte is a half-decode — the text would "work" but not be what was sent).
     */
    public fun decode(bytes: ByteArray): DecodeResult {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            return DecodeResult.Malformed("input is not valid UTF-8")
        }
        return decode(text)
    }

    /** Decodes one envelope. Never throws; see [DecodeResult]. */
    public fun decode(text: String): DecodeResult =
        try {
            decodeOrThrow(text)
        } catch (e: WireException) {
            DecodeResult.Malformed(e.message ?: "malformed message")
        } catch (e: JSONException) {
            // Unreachable after StrictJson validation in practice; kept so the "never throws" promise
            // does not rest on that reasoning alone.
            DecodeResult.Malformed("unreadable JSON: ${e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            DecodeResult.Malformed("internal decode failure: ${e.javaClass.simpleName}")
        }

    private fun decodeOrThrow(text: String): DecodeResult {
        StrictJson.requireSingleObject(text)
        val root = Fields(JSONObject(text), "envelope")

        val contract = root.string(KEY_CONTRACT)
        if (contract != WatchBridge.CONTRACT_NAME) root.fail("not a WatchBrew message (contract \"${contract.take(24)}\")")

        val version = root.int(KEY_VERSION)
        if (version < 1) root.fail("\"$KEY_VERSION\" must be >= 1 (was $version)")
        if (version != WatchBridge.CONTRACT_VERSION) {
            // The payload belongs to another version and is deliberately not read.
            val claimedType = runCatching { root.optString(KEY_TYPE) }.getOrNull()?.take(MAX_CLAIMED_TYPE)
            return DecodeResult.Incompatible(VersionMismatch(peerVersion = version, localVersion = WatchBridge.CONTRACT_VERSION), claimedType)
        }

        val typeName = root.string(KEY_TYPE)
        val type = WireType.fromWire(typeName) ?: root.fail("unknown message type \"${typeName.take(MAX_CLAIMED_TYPE)}\"")
        val payload = root.obj(KEY_PAYLOAD)
        return DecodeResult.Decoded(PayloadCodecs.decode(type, payload))
    }

    private const val KEY_CONTRACT = "contract"
    private const val KEY_VERSION = "v"
    private const val KEY_TYPE = "type"
    private const val KEY_PAYLOAD = "payload"
    private const val MAX_CLAIMED_TYPE = 40
}
