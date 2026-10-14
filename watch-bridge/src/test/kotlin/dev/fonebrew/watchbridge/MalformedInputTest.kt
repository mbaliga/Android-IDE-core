package dev.fonebrew.watchbridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode is all-or-nothing: every defect below must yield Malformed (with a reason), never a
 * partially-filled message, a defaulted field, or an exception.
 */
class MalformedInputTest {

    // ---- envelope-level defects ----------------------------------------------------------------

    @Test
    fun notJsonAtAll() {
        for (text in listOf("", "   ", "\n", "hello", "nul", "{", "}", "{]", "[]", "[{}]", "\"str\"", "42", "true", "null")) {
            malformed(text)
        }
    }

    @Test
    fun jsonThatIsNotAnObject_isRejected() {
        malformed("""["watchbrew",1,"AskRequest",{}]""")
        malformed(""""watchbrew"""")
    }

    @Test
    fun trailingDataAfterTheObject_isRejected() {
        val good = WatchBridgeCodec.encode(AskRequest("r", "p"))
        malformed("$good x")
        malformed("$good {}")
        malformed("$good$good")
        malformed("$good,")
        malformed("$good}")
        // trailing WHITESPACE is fine
        decodedMessage("$good \n\t\r ")
    }

    @Test
    fun leadingJunkOrBom_isRejected() {
        val good = WatchBridgeCodec.encode(AskRequest("r", "p"))
        malformed("x$good")
        malformed("\uFEFF$good")
        malformed("\u00a0$good")
    }

    @Test
    fun missingEnvelopeFields() {
        val payload = """{"requestId":"r","prompt":"p"}"""
        malformed("""{"v":1,"type":"AskRequest","payload":$payload}""") // no contract
        malformed("""{"contract":"watchbrew","type":"AskRequest","payload":$payload}""") // no v
        malformed("""{"contract":"watchbrew","v":1,"payload":$payload}""") // no type
        malformed("""{"contract":"watchbrew","v":1,"type":"AskRequest"}""") // no payload
        malformed("{}")
    }

    @Test
    fun wrongContractMarker() {
        val payload = """{"requestId":"r","prompt":"p"}"""
        malformed(env("AskRequest", payload, contract = "\"fonebrew\""))
        malformed(env("AskRequest", payload, contract = "\"WatchBrew\""))
        malformed(env("AskRequest", payload, contract = "1"))
        malformed(env("AskRequest", payload, contract = "null"))
    }

    @Test
    fun wrongEnvelopeFieldTypes() {
        val payload = """{"requestId":"r","prompt":"p"}"""
        for (v in listOf("\"1\"", "1.0", "1.5", "true", "null", "[]", "{}", "0", "-1", "2147483648", "4294967297", "99999999999999999999")) {
            malformed(env("AskRequest", payload, v = v))
        }
        malformed("""{"contract":"watchbrew","v":1,"type":7,"payload":$payload}""")
        malformed("""{"contract":"watchbrew","v":1,"type":null,"payload":$payload}""")
        malformed("""{"contract":"watchbrew","v":1,"type":"AskRequest","payload":"nope"}""")
        malformed("""{"contract":"watchbrew","v":1,"type":"AskRequest","payload":[]}""")
        malformed("""{"contract":"watchbrew","v":1,"type":"AskRequest","payload":null}""")
    }

    @Test
    fun unknownOrMiscasedMessageType() {
        val payload = """{"requestId":"r","prompt":"p"}"""
        malformed(env("Unknown", payload))
        malformed(env("askrequest", payload))
        malformed(env("ASK_REQUEST", payload))
        malformed(env("", payload))
        malformed(env("AskRequest ", payload))
    }

    @Test
    fun duplicateKeys_areRejectedNotResolvedByPlatformLuck() {
        malformed("""{"contract":"watchbrew","v":1,"type":"AskRequest","payload":{"requestId":"a","requestId":"b","prompt":"p"}}""")
        malformed("""{"contract":"watchbrew","contract":"watchbrew","v":1,"type":"AskRequest","payload":{"requestId":"a","prompt":"p"}}""")
        // A duplicate hidden behind an escape is still a duplicate.
        malformed("""{"contract":"watchbrew","v":1,"type":"AskRequest","payload":{"requestId":"a","\u0072equestId":"b","prompt":"p"}}""")
    }

    @Test
    fun lenientParserDialects_areRejectedOnEveryPlatform() {
        // Accepted by Android's org.json (and some by the reference one); not valid JSON.
        malformed("{'contract':'watchbrew','v':1,'type':'AskRequest','payload':{'requestId':'r','prompt':'p'}}")
        malformed("""{contract:"watchbrew",v:1,type:"AskRequest",payload:{requestId:"r",prompt:"p"}}""")
        malformed("""{"contract":"watchbrew";"v":1}""")
        malformed("""{"contract"="watchbrew"}""")
        malformed("""{"contract":"watchbrew","v":1,"type":"AskRequest","payload":{"requestId":"r","prompt":"p"},}""")
        malformed("""{"contract":"watchbrew","v":1, // comment
            "type":"AskRequest","payload":{"requestId":"r","prompt":"p"}}""")
        malformed("""{"contract":"watchbrew","v":1,/* c */"type":"AskRequest","payload":{"requestId":"r","prompt":"p"}}""")
        malformed("""# note
            {"contract":"watchbrew","v":1,"type":"AskRequest","payload":{"requestId":"r","prompt":"p"}}""")
        malformed("""{"contract":"watchbrew","v":0x1,"type":"AskRequest","payload":{"requestId":"r","prompt":"p"}}""")
        malformed("""{"contract":"watchbrew","v":01,"type":"AskRequest","payload":{"requestId":"r","prompt":"p"}}""")
        malformed("""{"contract":"watchbrew","v":+1,"type":"AskRequest","payload":{"requestId":"r","prompt":"p"}}""")
        malformed("""{"contract":"watchbrew","v":NaN,"type":"AskRequest","payload":{"requestId":"r","prompt":"p"}}""")
    }

    // ---- generic per-message mutation: remove / null / retype each field -----------------------

    private enum class K { S, B, I, L, O, A }

    private class Case(
        val type: String,
        val base: String,
        /** Required fields: path -> kind. */
        val required: List<Pair<List<Any>, K>>,
        /** Optional fields (absent or null == not present): path -> kind. */
        val optional: List<Pair<List<Any>, K>> = emptyList(),
    )

    private val cases = listOf(
        Case(
            "ContractHello", """{"contractVersion":1,"phoneAppVersion":"0.3.1"}""",
            required = listOf(listOf<Any>("contractVersion") to K.I, listOf<Any>("phoneAppVersion") to K.S),
        ),
        Case(
            "AskRequest", """{"requestId":"r","prompt":"p"}""",
            required = listOf(listOf<Any>("requestId") to K.S, listOf<Any>("prompt") to K.S),
        ),
        Case(
            "AskChunk", """{"requestId":"r","text":"t","terminal":false,"engineId":"e","watchedObject":false}""",
            required = listOf(
                listOf<Any>("requestId") to K.S, listOf<Any>("text") to K.S, listOf<Any>("terminal") to K.B,
                listOf<Any>("engineId") to K.S, listOf<Any>("watchedObject") to K.B,
            ),
        ),
        Case(
            "AskChunk", """{"requestId":"r","text":"","terminal":true,"engineId":"e","watchedObject":true,"usage":{"inputTokens":3,"outputTokens":4,"estimated":true}}""",
            required = listOf(
                listOf<Any>("requestId") to K.S, listOf<Any>("text") to K.S, listOf<Any>("terminal") to K.B,
                listOf<Any>("engineId") to K.S, listOf<Any>("watchedObject") to K.B,
                listOf<Any>("usage") to K.O, listOf<Any>("usage", "estimated") to K.B,
            ),
            optional = listOf(listOf<Any>("usage", "inputTokens") to K.L, listOf<Any>("usage", "outputTokens") to K.L),
        ),
        Case(
            "AskError", """{"requestId":"r","reason":"why"}""",
            required = listOf(listOf<Any>("requestId") to K.S, listOf<Any>("reason") to K.S),
        ),
        Case(
            "TaskSnapshot", """{"tasks":[{"id":"t1","title":"A","state":"todo"},{"id":"t2","title":"","state":"done"}],"pendingCount":1}""",
            required = listOf(
                listOf<Any>("tasks") to K.A, listOf<Any>("pendingCount") to K.I,
                listOf<Any>("tasks", 0, "id") to K.S, listOf<Any>("tasks", 0, "title") to K.S, listOf<Any>("tasks", 0, "state") to K.S,
                listOf<Any>("tasks", 1, "id") to K.S, listOf<Any>("tasks", 1, "title") to K.S, listOf<Any>("tasks", 1, "state") to K.S,
            ),
        ),
        Case(
            "TaskToggleRequest", """{"taskId":"t","done":true}""",
            required = listOf(listOf<Any>("taskId") to K.S, listOf<Any>("done") to K.B),
        ),
        Case(
            "TaskToggleResult", """{"taskId":"t","outcome":"applied","done":true}""",
            required = listOf(listOf<Any>("taskId") to K.S, listOf<Any>("outcome") to K.S, listOf<Any>("done") to K.B),
        ),
        Case(
            "TaskToggleResult", """{"taskId":"t","outcome":"rejected","reason":"gone"}""",
            required = listOf(listOf<Any>("taskId") to K.S, listOf<Any>("outcome") to K.S, listOf<Any>("reason") to K.S),
        ),
        Case(
            "LoopRunSnapshot",
            """{"runId":"r","loopName":"L","stateGlyphKey":"running","stepName":"s","spend":{"amountMinor":120,"currency":"USD","estimated":true}}""",
            required = listOf(
                listOf<Any>("runId") to K.S, listOf<Any>("loopName") to K.S, listOf<Any>("stateGlyphKey") to K.S,
                listOf<Any>("spend", "amountMinor") to K.L, listOf<Any>("spend", "currency") to K.S, listOf<Any>("spend", "estimated") to K.B,
            ),
            optional = listOf(listOf<Any>("stepName") to K.S, listOf<Any>("spend") to K.O),
        ),
    )

    /** JSON values of each kind that are the WRONG type for a field of kind [k]. */
    private fun wrongValues(k: K): List<Any> {
        val string = "x"
        val int = 1
        val double = 1.5
        val bool = true
        val obj = JSONObject()
        val arr = JSONArray()
        return when (k) {
            K.S -> listOf(int, double, bool, obj, arr)
            K.B -> listOf(string, "true", int, 0, double, obj, arr)
            // 4294967297 = 2^32 + 1 would TRUNCATE to a perfectly valid 1 if a reader narrowed Long -> Int silently.
            K.I -> listOf(string, "1", double, bool, obj, arr, 2147483648L, 4294967297L, -2147483649L)
            K.L -> listOf(string, "1", double, bool, obj, arr)
            K.O -> listOf(string, int, double, bool, arr)
            K.A -> listOf(string, int, double, bool, obj)
        }
    }

    private fun mutated(case: Case, edit: (JSONObject) -> Unit): String {
        val payload = JSONObject(case.base)
        edit(payload)
        return env(case.type, payload.toString())
    }

    @Test
    fun baseCases_decodeBeforeAnyMutation() {
        for (case in cases) decodedMessage(env(case.type, case.base))
    }

    @Test
    fun everyRequiredField_removedOrNulled_failsWhole() {
        for (case in cases) {
            for ((path, _) in case.required) {
                malformed(mutated(case) { removeAt(it, path) })
                malformed(mutated(case) { setAt(it, path, null) })
            }
        }
    }

    @Test
    fun everyField_withTheWrongJsonType_failsWhole() {
        for (case in cases) {
            for ((path, kind) in case.required + case.optional) {
                for (wrong in wrongValues(kind)) {
                    val text = mutated(case) { setAt(it, path, wrong) }
                    val r = decodeAny(text)
                    assertTrue("field $path of ${case.type} accepted wrong type ${wrong.javaClass.simpleName}=$wrong -> $r", r is DecodeResult.Malformed)
                }
            }
        }
    }

    @Test
    fun everyOptionalField_absentOrNull_stillDecodes() {
        for (case in cases) {
            for ((path, _) in case.optional) {
                decodedMessage(mutated(case) { removeAt(it, path) })
                decodedMessage(mutated(case) { setAt(it, path, null) })
            }
        }
    }

    @Test
    fun malformedResults_neverLeakInputTextUnbounded() {
        val huge = "Z".repeat(10_000)
        val r = malformed(env(huge, """{"requestId":"r","prompt":"p"}"""))
        assertTrue("reason must stay bounded, was ${r.reason.length} chars", r.reason.length < 300)
    }

    // ---- semantic invariants: well-typed JSON that describes an impossible message --------------

    @Test
    fun blankIdentifiersAndReasons_areRejected() {
        // "\\t\\n" is the JSON ESCAPE text (valid JSON that decodes to whitespace), not raw control chars.
        for (blank in listOf("", " ", "   ", "\\t\\n")) {
            malformed(env("AskRequest", """{"requestId":"$blank","prompt":"p"}"""))
            malformed(env("AskRequest", """{"requestId":"r","prompt":"$blank"}"""))
            malformed(env("AskError", """{"requestId":"$blank","reason":"why"}"""))
            malformed(env("AskError", """{"requestId":"r","reason":"$blank"}"""))
            malformed(env("TaskToggleRequest", """{"taskId":"$blank","done":true}"""))
            malformed(env("ContractHello", """{"contractVersion":1,"phoneAppVersion":"$blank"}"""))
            malformed(env("AskChunk", """{"requestId":"$blank","text":"","terminal":false,"engineId":"e","watchedObject":false}"""))
            malformed(env("AskChunk", """{"requestId":"r","text":"","terminal":false,"engineId":"$blank","watchedObject":false}"""))
            malformed(env("LoopRunSnapshot", """{"runId":"$blank","loopName":"L","stateGlyphKey":"running"}"""))
            malformed(env("LoopRunSnapshot", """{"runId":"r","loopName":"$blank","stateGlyphKey":"running"}"""))
            malformed(env("LoopRunSnapshot", """{"runId":"r","loopName":"L","stateGlyphKey":"running","stepName":"$blank"}"""))
        }
    }

    @Test
    fun helloVersionBelowOne_isRejected() {
        malformed(env("ContractHello", """{"contractVersion":0,"phoneAppVersion":"1"}"""))
        malformed(env("ContractHello", """{"contractVersion":-3,"phoneAppVersion":"1"}"""))
    }

    @Test
    fun unpairedSurrogates_areRejectedNotSilentlyCorrupted() {
        malformed(env("AskRequest", """{"requestId":"r","prompt":"a\ud83dz"}"""))
        malformed(env("AskRequest", """{"requestId":"r","prompt":"a\ude42z"}"""))
        malformed(env("AskChunk", """{"requestId":"r","text":"\ud83d","terminal":false,"engineId":"e","watchedObject":false}"""))
        // a proper pair is fine
        decodedMessage(env("AskRequest", """{"requestId":"r","prompt":"a\ud83d\ude42z"}"""))
    }

    @Test
    fun taskSnapshot_invariants() {
        val t1 = """{"id":"a","title":"A","state":"todo"}"""
        malformed(env("TaskSnapshot", """{"tasks":[$t1,$t1],"pendingCount":2}""")) // duplicate id
        malformed(env("TaskSnapshot", """{"tasks":[],"pendingCount":-1}"""))
        malformed(env("TaskSnapshot", """{"tasks":[$t1],"pendingCount":0}""")) // fewer pending than listed
        malformed(env("TaskSnapshot", """{"tasks":[{"id":"a","title":"A","state":"DONE"}],"pendingCount":0}""")) // wire is lowercase
        malformed(env("TaskSnapshot", """{"tasks":[{"id":"a","title":"A","state":"paused"}],"pendingCount":1}"""))
        malformed(env("TaskSnapshot", """{"tasks":[{"id":"","title":"A","state":"todo"}],"pendingCount":1}"""))
        malformed(env("TaskSnapshot", """{"tasks":[1],"pendingCount":0}"""))
        malformed(env("TaskSnapshot", """{"tasks":[null],"pendingCount":0}"""))
        malformed(env("TaskSnapshot", """{"tasks":[[]],"pendingCount":0}"""))
        // listed DONE tasks do not count as pending; a larger true total is legal (truncated list)
        decodedMessage(env("TaskSnapshot", """{"tasks":[{"id":"a","title":"A","state":"done"}],"pendingCount":0}"""))
        decodedMessage(env("TaskSnapshot", """{"tasks":[$t1],"pendingCount":40}"""))
    }

    @Test
    fun taskToggleResult_outcomeShapes() {
        malformed(env("TaskToggleResult", """{"taskId":"t","outcome":"applied","done":true,"reason":"also"}"""))
        malformed(env("TaskToggleResult", """{"taskId":"t","outcome":"rejected","done":true,"reason":"gone"}"""))
        malformed(env("TaskToggleResult", """{"taskId":"t","outcome":"rejected"}"""))
        malformed(env("TaskToggleResult", """{"taskId":"t","outcome":"rejected","reason":""}"""))
        malformed(env("TaskToggleResult", """{"taskId":"t","outcome":"applied"}"""))
        malformed(env("TaskToggleResult", """{"taskId":"t","outcome":"APPLIED","done":true}"""))
        malformed(env("TaskToggleResult", """{"taskId":"t","outcome":"maybe","done":true}"""))
    }

    @Test
    fun loopRunSnapshot_invariants() {
        fun loop(glyph: String = "running", spend: String = """{"amountMinor":1,"currency":"USD","estimated":true}""") =
            env("LoopRunSnapshot", """{"runId":"r","loopName":"L","stateGlyphKey":"$glyph","spend":$spend}""")

        for (bad in listOf("", "Running", "RUNNING", "with space", "1abc", "_x", "a-b", "x".repeat(33), "é")) malformed(loop(glyph = bad))
        // an unrecognized-but-well-formed key is forward compatible
        assertEquals("paused_for_review", decodedAs<LoopRunSnapshot>(loop(glyph = "paused_for_review")).stateGlyphKey)
        assertEquals("x".repeat(32), decodedAs<LoopRunSnapshot>(loop(glyph = "x".repeat(32))).stateGlyphKey)

        malformed(loop(spend = """{"amountMinor":-1,"currency":"USD","estimated":true}"""))
        malformed(loop(spend = """{"amountMinor":1.5,"currency":"USD","estimated":true}"""))
        malformed(loop(spend = """{"amountMinor":"1","currency":"USD","estimated":true}"""))
        malformed(loop(spend = """{"amountMinor":1,"currency":"usd","estimated":true}"""))
        malformed(loop(spend = """{"amountMinor":1,"currency":"US","estimated":true}"""))
        malformed(loop(spend = """{"amountMinor":1,"currency":"USDX","estimated":true}"""))
        malformed(loop(spend = """{"amountMinor":1,"currency":"","estimated":true}"""))
        malformed(loop(spend = """{"amountMinor":1,"currency":"US1","estimated":true}"""))
        malformed(loop(spend = """{"amountMinor":1,"currency":"USD"}""")) // estimated must not default
        malformed(loop(spend = """{"currency":"USD","estimated":true}"""))
        malformed(loop(spend = """{"amountMinor":9223372036854775808,"currency":"USD","estimated":true}""")) // > Long
    }

    @Test
    fun extraUnknownFields_areIgnoredNotFatal_butDoNotChangeTheMessage() {
        val withExtras = """{"contract":"watchbrew","v":1,"type":"AskRequest","future":{"a":[1,2,{"b":null}]},
            "payload":{"requestId":"r","prompt":"p","futureField":true}}"""
        assertEquals(AskRequest("r", "p"), decodedAs<AskRequest>(withExtras))

        val usageExtra = env(
            "AskChunk",
            """{"requestId":"r","text":"","terminal":true,"engineId":"e","watchedObject":false,"usage":{"estimated":true,"future":1}}""",
        )
        assertEquals(Usage.unknown(), decodedAs<AskChunk>(usageExtra).usage)
    }
}
