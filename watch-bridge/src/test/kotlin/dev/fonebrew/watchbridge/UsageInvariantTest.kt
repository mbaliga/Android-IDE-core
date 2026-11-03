package dev.fonebrew.watchbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "The engine never invents numbers": usage exists only on the terminal chunk, `estimated` can
 * never be defaulted, and "unknown" is never encoded as a real zero.
 */
class UsageInvariantTest {

    private fun chunk(terminal: String, usage: String? = null): String {
        val u = if (usage == null) "" else ""","usage":$usage"""
        return env("AskChunk", """{"requestId":"r","text":"","terminal":$terminal,"engineId":"e","watchedObject":false$u}""")
    }

    // ---- usage is absent until the terminal chunk ---------------------------------------------

    @Test
    fun constructor_rejectsUsageOnANonTerminalChunk() {
        assertRejected { AskChunk("r", "t", terminal = false, engineId = "e", watchedObject = false, usage = Usage.engineReported(1, 1)) }
        assertRejected { AskChunk("r", "t", terminal = false, engineId = "e", watchedObject = false, usage = Usage.unknown()) }
    }

    @Test
    fun constructor_rejectsATerminalChunkWithoutUsage() {
        assertRejected { AskChunk("r", "t", terminal = true, engineId = "e", watchedObject = false, usage = null) }
    }

    @Test
    fun nonTerminalChunk_encodesWithNoUsageKeyAtAll() {
        val payload = JSONObject(WatchBridgeCodec.encode(AskChunk.token("r", "x", "e", false))).getJSONObject("payload")
        assertFalse("not even a JSON null", payload.has("usage"))
    }

    @Test
    fun decode_rejectsUsageOnANonTerminalChunk() {
        malformed(chunk("false", """{"inputTokens":1,"outputTokens":2,"estimated":false}"""))
        malformed(chunk("false", """{"estimated":true}"""))
    }

    @Test
    fun decode_rejectsATerminalChunkWithoutUsage() {
        malformed(chunk("true"))
        malformed(chunk("true", "null"))
        malformed(chunk("true", "{}"))
    }

    @Test
    fun decode_acceptsNullUsageOnNonTerminal_asAbsent() {
        val c = decodedAs<AskChunk>(chunk("false", "null"))
        assertNull(c.usage)
    }

    @Test
    fun onlyTheTerminalChunkOfAStream_carriesUsage() {
        val stream = listOf(
            AskChunk.token("r", "a", "e", false),
            AskChunk.token("r", "b", "e", false),
            AskChunk.terminal("r", "c", "e", false, Usage.engineReported(3, 3)),
        )
        val decoded = stream.map { decodedAs<AskChunk>(WatchBridgeCodec.encode(it)) }
        assertEquals(listOf(false, false, true), decoded.map { it.usage != null })
        assertEquals(listOf(false, false, true), decoded.map { it.terminal })
    }

    // ---- estimated can never be defaulted -------------------------------------------------------

    @Test
    fun decode_missingEstimatedFlag_isMalformed_neverDefaultedToReal() {
        malformed(chunk("true", """{"inputTokens":10,"outputTokens":20}"""))
        malformed(chunk("true", """{"estimated":null,"inputTokens":10,"outputTokens":20}"""))
    }

    @Test
    fun decode_estimatedMustBeARealBoolean() {
        for (bad in listOf("\"false\"", "\"true\"", "0", "1", "null", "[]", "{}")) {
            malformed(chunk("true", """{"inputTokens":10,"outputTokens":20,"estimated":$bad}"""))
        }
    }

    @Test
    fun decode_countsMustBeIntegers() {
        for (bad in listOf("\"10\"", "1.5", "true", "[]", "{}", "-1", "9223372036854775808")) {
            malformed(chunk("true", """{"inputTokens":$bad,"outputTokens":2,"estimated":true}"""))
            malformed(chunk("true", """{"inputTokens":1,"outputTokens":$bad,"estimated":true}"""))
        }
    }

    @Test
    fun engineReported_requiresBothCounts() {
        assertRejected { Usage(inputTokens = null, outputTokens = 5, estimated = false) }
        assertRejected { Usage(inputTokens = 5, outputTokens = null, estimated = false) }
        assertRejected { Usage(inputTokens = null, outputTokens = null, estimated = false) }
        malformed(chunk("true", """{"outputTokens":5,"estimated":false}"""))
        malformed(chunk("true", """{"estimated":false}"""))
    }

    @Test
    fun negativeCounts_areImpossible() {
        assertRejected { Usage(-1, 0, estimated = false) }
        assertRejected { Usage(0, -1, estimated = true) }
        assertRejected { Usage.estimate(-5, null) }
    }

    // ---- unknown is not zero --------------------------------------------------------------------

    @Test
    fun unknownUsage_isEncodedWithNoCountKeys_notZero() {
        val usage = JSONObject(WatchBridgeCodec.encode(AskChunk.terminal("r", "", "e", false, Usage.unknown())))
            .getJSONObject("payload").getJSONObject("usage")
        assertFalse(usage.has("inputTokens"))
        assertFalse(usage.has("outputTokens"))
        assertTrue("estimated must be true when the engine reported nothing", usage.getBoolean("estimated"))
    }

    @Test
    fun realZero_staysZero_andUnknownStaysNull() {
        val zero = decodedAs<AskChunk>(chunk("true", """{"inputTokens":0,"outputTokens":0,"estimated":false}""")).usage!!
        assertEquals(0L, zero.inputTokens)
        assertFalse(zero.estimated)
        val unknown = decodedAs<AskChunk>(chunk("true", """{"estimated":true}""")).usage!!
        assertNull(unknown.inputTokens)
        assertNull(unknown.outputTokens)
        assertTrue(unknown.estimated)
    }

    @Test
    fun partialEstimate_isRepresentableAndStaysEstimated() {
        val usage = decodedAs<AskChunk>(chunk("true", """{"outputTokens":7,"estimated":true}""")).usage!!
        assertNull(usage.inputTokens)
        assertEquals(7L, usage.outputTokens)
        assertTrue(usage.estimated)
    }

    @Test
    fun factories_labelTheirOwnHonesty() {
        assertFalse(Usage.engineReported(1, 2).estimated)
        assertTrue(Usage.estimate(1, 2).estimated)
        assertTrue(Usage.estimate(null, null).estimated)
        assertTrue(Usage.unknown().estimated)
    }

    // ---- spend mirrors the same discipline -------------------------------------------------------

    @Test
    fun spend_estimatedHasNoDefault_andMissingFlagIsMalformed() {
        malformed(env("LoopRunSnapshot", """{"runId":"r","loopName":"L","stateGlyphKey":"running","spend":{"amountMinor":5,"currency":"USD"}}"""))
    }

    // ---- no defaulted constructor parameters anywhere (a default is where an invented 0/false hides) ----

    private class DefaultedProbe(val a: Int = 0)

    private fun hasDefaultedCtor(c: Class<*>): Boolean = c.declaredConstructors.any { ctor ->
        ctor.parameterTypes.any { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
    }

    @Test
    fun defaultedConstructorDetector_actuallyDetects() {
        // Guards the guard: if this stopped working, the next test would pass vacuously.
        assertTrue(hasDefaultedCtor(DefaultedProbe::class.java))
        assertFalse(hasDefaultedCtor(Usage::class.java))
    }

    @Test
    fun noMessageOrValueClass_hasADefaultedConstructorParameter() {
        val classes = listOf(
            ContractHello::class.java, AskRequest::class.java, AskChunk::class.java, Usage::class.java, AskError::class.java,
            TaskEntry::class.java, TaskSnapshot::class.java, TaskToggleRequest::class.java,
            TaskToggleResult.Applied::class.java, TaskToggleResult.Rejected::class.java,
            LoopRunSnapshot::class.java, Spend::class.java, VersionMismatch::class.java,
        )
        for (c in classes) {
            // Kotlin compiles a constructor with default arguments to an extra synthetic overload taking a
            // DefaultConstructorMarker. None of these classes may have one.
            assertFalse("${c.simpleName} has a defaulted constructor parameter", hasDefaultedCtor(c))
        }
    }
}
