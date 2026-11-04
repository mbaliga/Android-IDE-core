package dev.fonebrew.watchbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Decode never throws and never half-decodes, however the bytes are damaged. */
class DecodeRobustnessTest {

    @Test
    fun everyStrictPrefixOfAValidMessage_isMalformed_andNeverAPartialMessage() {
        for (sample in ALL_SAMPLES) {
            val text = WatchBridgeCodec.encode(sample)
            // Skip the largest sample's prefixes beyond a stride so the suite stays fast; still dense near both ends.
            val stride = if (text.length > 4000) 97 else 1
            var cut = 0
            while (cut < text.length) {
                val r = decodeAny(text.substring(0, cut))
                assertTrue("prefix of length $cut of ${sample.wireType()} must be Malformed, was $r", r is DecodeResult.Malformed)
                cut += stride
            }
            for (cut2 in (text.length - 6).coerceAtLeast(0) until text.length) {
                assertTrue(decodeAny(text.substring(0, cut2)) is DecodeResult.Malformed)
            }
            assertEquals(DecodeResult.Decoded(sample), decodeAny(text))
        }
    }

    @Test
    fun seededMutationFuzz_neverThrows_andAnythingDecodedIsAFullyValidMessage() {
        val rnd = Random(0xC0FFEEL)
        val alphabet = "{}[]\",:\\0123456789-+.eEtrufalsn \n\t\u0000é\uD83D"
        var malformed = 0
        var decoded = 0
        var incompatible = 0
        val texts = ALL_SAMPLES.map { WatchBridgeCodec.encode(it) }.filter { it.length < 4000 }
        repeat(6000) {
            val base = texts[rnd.nextInt(texts.size)]
            val sb = StringBuilder(base)
            repeat(1 + rnd.nextInt(3)) {
                if (sb.isEmpty()) return@repeat
                val i = rnd.nextInt(sb.length)
                when (rnd.nextInt(4)) {
                    0 -> sb.deleteCharAt(i)
                    1 -> sb.setCharAt(i, alphabet[rnd.nextInt(alphabet.length)])
                    2 -> sb.insert(i, alphabet[rnd.nextInt(alphabet.length)])
                    else -> sb.insert(i, sb[i])
                }
            }
            val result = decodeAny(sb.toString()) // must not throw
            when (result) {
                is DecodeResult.Malformed -> {
                    malformed++
                    assertTrue(result.reason.isNotBlank())
                }
                is DecodeResult.Incompatible -> incompatible++
                is DecodeResult.Decoded -> {
                    decoded++
                    // Whatever got through must be a complete, re-encodable, stable message.
                    val again = decodeAny(WatchBridgeCodec.encode(result.message))
                    assertEquals(result, again)
                }
            }
        }
        assertEquals(6000, malformed + decoded + incompatible)
        assertTrue("fuzz should reject plenty (was $malformed)", malformed > 1000)
        assertTrue("fuzz should sometimes mutate only string content and still decode (was $decoded)", decoded > 0)
    }

    @Test
    fun invalidUtf8Bytes_areMalformed_notReplaced() {
        val good = WatchBridgeCodec.encodeToBytes(AskRequest("r", "prompt"))
        fun withPrompt(vararg inside: Int): ByteArray {
            val prefix = """{"contract":"watchbrew","v":1,"type":"AskRequest","payload":{"requestId":"r","prompt":"x""".toByteArray(Charsets.UTF_8)
            val suffix = """y"}}""".toByteArray(Charsets.UTF_8)
            return prefix + ByteArray(inside.size) { inside[it].toByte() } + suffix
        }
        malformed(withPrompt(0xC3, 0x28)) // bad continuation
        malformed(withPrompt(0xFF))
        malformed(withPrompt(0xC0, 0x80)) // overlong NUL
        malformed(withPrompt(0xED, 0xA0, 0x80)) // UTF-8-encoded surrogate
        malformed(withPrompt(0xF8, 0x88, 0x80, 0x80, 0x80))
        malformed(withPrompt(0xE2, 0x82)) // truncated multibyte
        // sanity: valid multibyte in the same position decodes, so the cases above fail for the right reason
        val ok = decodedAs<AskRequest>(String(withPrompt(0xC3, 0xA9), Charsets.UTF_8)) // e-acute
        assertEquals("xéy", ok.prompt)
        assertTrue(WatchBridgeCodec.decode(good) is DecodeResult.Decoded)
    }

    private fun malformed(bytes: ByteArray) {
        val r = WatchBridgeCodec.decode(bytes)
        assertTrue("expected Malformed for bytes, was $r", r is DecodeResult.Malformed)
        assertTrue((r as DecodeResult.Malformed).reason.isNotBlank())
    }

    @Test
    fun emptyAndTrivialByteInputs_areMalformed() {
        malformed(ByteArray(0))
        malformed(ByteArray(1))
        malformed(byteArrayOf(0x7B)) // "{"
        malformed(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // BOM only
    }

    @Test
    fun hostileNesting_isMalformed_notAStackOverflow() {
        malformed(decodeBytes("[".repeat(500_000)))
        malformed(decodeBytes("{\"a\":".repeat(500_000)))
        val deepInPayload = """{"contract":"watchbrew","v":1,"type":"AskRequest","payload":{"requestId":"r","prompt":"p","x":${"[".repeat(40)}${"]".repeat(40)}}}"""
        assertTrue(decodeAny(deepInPayload) is DecodeResult.Malformed)
        val shallowExtra = """{"contract":"watchbrew","v":1,"type":"AskRequest","payload":{"requestId":"r","prompt":"p","x":[[[[1]]]]}}"""
        assertTrue(decodeAny(shallowExtra) is DecodeResult.Decoded)
    }

    private fun decodeBytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun veryLongButValidStringsAndLists_decode() {
        val tasks = (1..2000).map { TaskEntry("t$it", "Task number $it", WatchTaskState.TODO) }
        assertRoundTrips(TaskSnapshot(tasks, pendingCount = 2000))
    }

    @Test
    fun decodeNeverThrows_forAssortedPathologicalInputs() {
        val inputs = listOf(
            "\u0000", "\u0000\u0000", "{\u0000}", "\uFFFF", "\uD83D", "\uDE42", "{\"\uD83D\":1}", "{\"contract\":\"\uD83D\"}",
            "{\"contract\":\"watchbrew\",\"v\":1,\"type\":\"AskRequest\",\"payload\":{\"requestId\":\"\uD83D\",\"prompt\":\"p\"}}",
            "9".repeat(10_000), "\"" + "a".repeat(100_000), "{" + "\"k\":1,".repeat(10_000) + "}",
        )
        for (text in inputs) {
            val r = decodeAny(text) // the assertion is that this returns at all
            assertTrue(r is DecodeResult.Malformed)
        }
    }
}
