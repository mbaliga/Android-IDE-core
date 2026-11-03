// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.contracts

import java.security.SecureRandom

/**
 * Generates globally unique, stable, lexicographically-sortable object IDs (FB-RAT-COM-002:
 * "every durable object has a globally unique stable ID independent of display name/path").
 *
 * Format: [ULID](https://github.com/ulid/spec) — a 128-bit value (48-bit millisecond timestamp +
 * 80 bits of randomness) encoded as 26 Crockford Base32 characters. Chosen over a random UUIDv4
 * specifically because ULIDs sort by creation time as plain strings — every receipt/envelope
 * store in this corpus (`ReceiptDao`, mirroring the existing append-only `LedgerDao`) benefits
 * from `objectId`/`receiptId` values that are already in insertion order without a separate
 * timestamp column to sort by, and the handoff pack's own worked examples throughout every
 * ratified spec (`docs/ratified/`) use this exact `<prefix>_01J...` shape.
 *
 * Optionally prefixed (e.g. `"rcpt_" + IdGenerator.generate()`) — this class only generates the
 * bare 26-character ULID; prefixing is a per-domain naming convention applied by the caller.
 */
object IdGenerator {

    private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIMESTAMP_CHARS = 10
    private const val RANDOMNESS_CHARS = 16
    private const val MAX_TIMESTAMP_MILLIS = 281474976710655L // 2^48 - 1

    private val defaultRandom = SecureRandom()

    /**
     * Generates one ULID. [timestampMillis] and [random] are injectable so tests can assert on
     * exact output and on monotonic ordering across a fixed clock, rather than depending on wall
     * time or true randomness (matching this codebase's own `now: () -> Instant` seam pattern
     * used by `GraphRunner`).
     */
    fun generate(timestampMillis: Long = System.currentTimeMillis(), random: SecureRandom = defaultRandom): String {
        require(timestampMillis in 0..MAX_TIMESTAMP_MILLIS) {
            "IdGenerator timestamp must fit in 48 bits (0..$MAX_TIMESTAMP_MILLIS), got $timestampMillis."
        }
        val randomBytes = ByteArray(10)
        random.nextBytes(randomBytes)
        return encodeTimestamp(timestampMillis) + encodeRandomness(randomBytes)
    }

    private fun encodeTimestamp(timestampMillis: Long): String {
        val chars = CharArray(TIMESTAMP_CHARS)
        var remaining = timestampMillis
        for (i in TIMESTAMP_CHARS - 1 downTo 0) {
            chars[i] = CROCKFORD_ALPHABET[(remaining and 0x1F).toInt()]
            remaining = remaining ushr 5
        }
        return String(chars)
    }

    /** 80 bits (10 bytes) of randomness, packed 5 bits at a time into 16 Crockford characters. */
    private fun encodeRandomness(bytes: ByteArray): String {
        require(bytes.size == 10) { "ULID randomness must be exactly 10 bytes (80 bits), got ${bytes.size}." }
        var bitBuffer = 0L
        var bitsInBuffer = 0
        var byteIndex = 0
        val chars = CharArray(RANDOMNESS_CHARS)
        for (i in 0 until RANDOMNESS_CHARS) {
            while (bitsInBuffer < 5 && byteIndex < bytes.size) {
                bitBuffer = (bitBuffer shl 8) or (bytes[byteIndex].toLong() and 0xFF)
                bitsInBuffer += 8
                byteIndex++
            }
            val shift = bitsInBuffer - 5
            val index = ((bitBuffer ushr shift) and 0x1F).toInt()
            chars[i] = CROCKFORD_ALPHABET[index]
            bitsInBuffer -= 5
        }
        return String(chars)
    }

    /** True if [id] is a well-formed 26-character Crockford Base32 ULID (structural check only — does not verify it was ever actually issued). */
    fun isWellFormed(id: String): Boolean =
        id.length == TIMESTAMP_CHARS + RANDOMNESS_CHARS && id.all { it in CROCKFORD_ALPHABET }
}
