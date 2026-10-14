package dev.fonebrew.watchbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the actual JSON on the wire with HAND-WRITTEN literals, so a bug that is symmetric in the
 * encoder and decoder (a renamed key, a changed type) cannot hide behind a passing round-trip.
 */
class WireFormatTest {

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    // ---- hand-written wire literals decode to the expected values ------------------------------

    @Test
    fun literal_hello() {
        val m = decodedAs<ContractHello>(env("ContractHello", """{"contractVersion":1,"phoneAppVersion":"0.3.1"}"""))
        assertEquals(ContractHello(1, "0.3.1"), m)
    }

    @Test
    fun literal_askRequest() {
        assertEquals(AskRequest("r1", "hi"), decodedAs<AskRequest>(env("AskRequest", """{"requestId":"r1","prompt":"hi"}""")))
    }

    @Test
    fun literal_askChunk_nonTerminal_and_terminal() {
        val tok = decodedAs<AskChunk>(
            env("AskChunk", """{"requestId":"r1","text":"He","terminal":false,"engineId":"local-model-a","watchedObject":false}"""),
        )
        assertEquals(AskChunk.token("r1", "He", "local-model-a", false), tok)

        val end = decodedAs<AskChunk>(
            env(
                "AskChunk",
                """{"requestId":"r1","text":"","terminal":true,"engineId":"cloud-model-b","watchedObject":true,
                    "usage":{"inputTokens":10,"outputTokens":20,"estimated":false}}""",
            ),
        )
        assertEquals(AskChunk.terminal("r1", "", "cloud-model-b", true, Usage(10, 20, false)), end)
    }

    @Test
    fun literal_askChunk_terminalEstimatedWithNothingReported() {
        val end = decodedAs<AskChunk>(
            env("AskChunk", """{"requestId":"r1","text":"","terminal":true,"engineId":"local-model-a","watchedObject":false,"usage":{"estimated":true}}"""),
        )
        assertEquals(Usage(null, null, true), end.usage)
    }

    @Test
    fun literal_askError() {
        assertEquals(AskError("r1", "no model loaded"), decodedAs<AskError>(env("AskError", """{"requestId":"r1","reason":"no model loaded"}""")))
    }

    @Test
    fun literal_taskSnapshot_allFourStates() {
        val snap = decodedAs<TaskSnapshot>(
            env(
                "TaskSnapshot",
                """{"tasks":[{"id":"a","title":"A","state":"todo"},{"id":"b","title":"B","state":"doing"},
                    {"id":"c","title":"C","state":"blocked"},{"id":"d","title":"D","state":"done"}],"pendingCount":3}""",
            ),
        )
        assertEquals(listOf(WatchTaskState.TODO, WatchTaskState.DOING, WatchTaskState.BLOCKED, WatchTaskState.DONE), snap.tasks.map { it.state })
        assertEquals(3, snap.pendingCount)
    }

    @Test
    fun literal_taskToggle() {
        assertEquals(TaskToggleRequest("t", true), decodedAs<TaskToggleRequest>(env("TaskToggleRequest", """{"taskId":"t","done":true}""")))
        assertEquals(
            TaskToggleResult.Applied("t", true),
            decodedAs<TaskToggleResult>(env("TaskToggleResult", """{"taskId":"t","outcome":"applied","done":true}""")),
        )
        assertEquals(
            TaskToggleResult.Rejected("t", "gone"),
            decodedAs<TaskToggleResult>(env("TaskToggleResult", """{"taskId":"t","outcome":"rejected","reason":"gone"}""")),
        )
    }

    @Test
    fun literal_loopRunSnapshot() {
        val m = decodedAs<LoopRunSnapshot>(
            env(
                "LoopRunSnapshot",
                """{"runId":"r","loopName":"Refine","stateGlyphKey":"running","stepName":"critic",
                    "spend":{"amountMinor":120,"currency":"USD","estimated":true}}""",
            ),
        )
        assertEquals(LoopRunSnapshot("r", "Refine", "running", "critic", Spend(120, "USD", true)), m)
    }

    // ---- what the encoder emits ----------------------------------------------------------------

    @Test
    fun encodedEnvelope_hasExactlyTheFourEnvelopeKeys() {
        for (sample in ALL_SAMPLES) {
            val o = JSONObject(WatchBridgeCodec.encode(sample))
            assertEquals(setOf("contract", "v", "type", "payload"), keys(o))
            assertEquals("watchbrew", o.getString("contract"))
            assertEquals(1, o.getInt("v"))
            assertEquals(sample.wireType().wire, o.getString("type"))
        }
    }

    @Test
    fun wireTypeNames_arePinnedLiterals() {
        // Hard-coded on purpose: these are the protocol, not the class names (R8 must not change them).
        assertEquals(
            listOf("ContractHello", "AskRequest", "AskChunk", "AskError", "TaskSnapshot", "TaskToggleRequest", "TaskToggleResult", "LoopRunSnapshot"),
            WireType.entries.map { it.wire },
        )
    }

    @Test
    fun payloadKeySets_areExactlyTheSpecifiedFields() {
        fun payloadKeys(m: WatchMessage) = keys(JSONObject(WatchBridgeCodec.encode(m)).getJSONObject("payload"))

        assertEquals(setOf("contractVersion", "phoneAppVersion"), payloadKeys(ContractHello(1, "v")))
        assertEquals(setOf("requestId", "prompt"), payloadKeys(AskRequest("r", "p")))
        assertEquals(
            setOf("requestId", "text", "terminal", "engineId", "watchedObject"),
            payloadKeys(AskChunk.token("r", "t", "e", false)),
        )
        assertEquals(
            setOf("requestId", "text", "terminal", "engineId", "watchedObject", "usage"),
            payloadKeys(AskChunk.terminal("r", "t", "e", false, Usage.engineReported(1, 2))),
        )
        assertEquals(setOf("requestId", "reason"), payloadKeys(AskError("r", "why")))
        assertEquals(setOf("tasks", "pendingCount"), payloadKeys(TaskSnapshot(emptyList(), 0)))
        assertEquals(setOf("taskId", "done"), payloadKeys(TaskToggleRequest("t", true)))
        assertEquals(setOf("taskId", "outcome", "done"), payloadKeys(TaskToggleResult.Applied("t", true)))
        assertEquals(setOf("taskId", "outcome", "reason"), payloadKeys(TaskToggleResult.Rejected("t", "why")))
        assertEquals(
            setOf("runId", "loopName", "stateGlyphKey", "stepName", "spend"),
            payloadKeys(LoopRunSnapshot("r", "L", "running", "s", Spend(1, "USD", true))),
        )
        assertEquals(setOf("runId", "loopName", "stateGlyphKey"), payloadKeys(LoopRunSnapshot("r", "L", "running", null, null)))
    }

    @Test
    fun usageAndSpendKeySets() {
        val usage = JSONObject(WatchBridgeCodec.encode(AskChunk.terminal("r", "", "e", false, Usage.engineReported(1, 2))))
            .getJSONObject("payload").getJSONObject("usage")
        assertEquals(setOf("inputTokens", "outputTokens", "estimated"), keys(usage))
        assertEquals(1L, usage.getLong("inputTokens"))
        assertFalse(usage.getBoolean("estimated"))

        val spend = JSONObject(WatchBridgeCodec.encode(LoopRunSnapshot("r", "L", "running", null, Spend(120, "USD", true))))
            .getJSONObject("payload").getJSONObject("spend")
        assertEquals(setOf("amountMinor", "currency", "estimated"), keys(spend))
        assertEquals(120L, spend.getLong("amountMinor"))
    }

    @Test
    fun noColorOrCredentialFieldsAnywhereOnTheWire() {
        // Owner is red-green colorblind: state is glyph/label/shape, never hue. And no secret may ride
        // this link (keys live in the phone's Keystore only). Check every key of every sample.
        val forbidden = Regex("(?i)colou?r|hue|rgb|hex|palette|api.?key|secret|password|credential|bearer|auth")
        fun walk(o: JSONObject) {
            for (k in keys(o)) {
                assertFalse("forbidden wire field name: $k", forbidden.containsMatchIn(k))
                (o.opt(k) as? JSONObject)?.let(::walk)
                (o.opt(k) as? org.json.JSONArray)?.let { arr ->
                    for (i in 0 until arr.length()) (arr.opt(i) as? JSONObject)?.let(::walk)
                }
            }
        }
        for (sample in ALL_SAMPLES) walk(JSONObject(WatchBridgeCodec.encode(sample)))
    }

    @Test
    fun glyphKeyVocabulary_namesNoColor() {
        val colorWords = Regex("(?i)red|green|amber|yellow|orange|blue|hue|colou?r")
        assertTrue(StateGlyphKeys.V1.isNotEmpty())
        for (k in StateGlyphKeys.V1) {
            assertTrue("v1 glyph key must be valid: $k", StateGlyphKeys.isValid(k))
            assertFalse("glyph key must not name a color: $k", colorWords.containsMatchIn(k))
        }
    }

    @Test
    fun encodedText_isStrictJsonAccordingToOurOwnValidator() {
        for (sample in ALL_SAMPLES) StrictJson.requireSingleObject(WatchBridgeCodec.encode(sample))
    }
}
