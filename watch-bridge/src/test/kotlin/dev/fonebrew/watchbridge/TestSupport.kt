package dev.fonebrew.watchbridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** A literal v1 envelope as text (so wire-format tests do not depend on the encoder under test). */
internal fun env(type: String, payload: String, v: String = "1", contract: String = "\"watchbrew\""): String =
    """{"contract":$contract,"v":$v,"type":"$type","payload":$payload}"""

internal fun decodeAny(text: String): DecodeResult = WatchBridgeCodec.decode(text)

internal fun decodedMessage(text: String): WatchMessage {
    val result = decodeAny(text)
    assertTrue("expected Decoded but was $result for: $text", result is DecodeResult.Decoded)
    return (result as DecodeResult.Decoded).message
}

internal inline fun <reified T : WatchMessage> decodedAs(text: String): T {
    val message = decodedMessage(text)
    assertTrue("expected ${T::class.java.simpleName} but was $message", message is T)
    return message as T
}

internal fun malformed(text: String): DecodeResult.Malformed {
    val result = decodeAny(text)
    assertTrue("expected Malformed but was $result for: $text", result is DecodeResult.Malformed)
    val m = result as DecodeResult.Malformed
    assertTrue("a Malformed result must explain itself", m.reason.isNotBlank())
    return m
}

/** Expect [block] to throw [IllegalArgumentException] (an impossible message built by a caller). */
internal fun assertRejected(block: () -> Unit) {
    try {
        block()
    } catch (e: IllegalArgumentException) {
        assertTrue("rejection must explain itself", !e.message.isNullOrBlank())
        return
    }
    fail("expected IllegalArgumentException")
}

/** Round-trips through text AND bytes, asserting equality both ways. */
internal fun assertRoundTrips(message: WatchMessage) {
    val text = WatchBridgeCodec.encode(message)
    assertEquals("text round-trip of $message", DecodeResult.Decoded(message), WatchBridgeCodec.decode(text))
    val bytes = WatchBridgeCodec.encodeToBytes(message)
    assertEquals("byte round-trip of $message", DecodeResult.Decoded(message), WatchBridgeCodec.decode(bytes))
}

/** One of every message shape, plus the variants that exercise each invariant branch. */
internal val ALL_SAMPLES: List<WatchMessage> = listOf(
    ContractHello(contractVersion = 1, phoneAppVersion = "0.3.1"),
    ContractHello.forThisBuild("0.3.1-debug+30"),

    AskRequest("req-1", "What's on my plate today?"),
    AskRequest("req-2", "quotes \" backslash \\ slash / newline\n tab\t ctrl\u0001 </script> line-sep\u2028 naive-ï 日本語 🙂"),

    AskChunk.token("req-1", "Hel", engineId = "local-model-a", watchedObject = false),
    AskChunk.token("req-1", "", engineId = "local-model-a", watchedObject = false),
    AskChunk.token("req-1", " \n ", engineId = "cloud-model-a", watchedObject = true),
    AskChunk.terminal("req-1", "", "local-model-a", false, Usage.engineReported(12, 34)),
    AskChunk.terminal("req-1", "lo.", "cloud-model-a", true, Usage.engineReported(0, 0)),
    AskChunk.terminal("req-1", "", "local-model-a", false, Usage.estimate(12, 34)),
    AskChunk.terminal("req-1", "", "local-model-a", false, Usage.estimate(null, 34)),
    AskChunk.terminal("req-1", "", "local-model-a", false, Usage.unknown()),
    AskChunk.terminal("req-1", "", "x", true, Usage.engineReported(Long.MAX_VALUE, Long.MAX_VALUE)),

    AskError("req-1", "The phone is out of memory for this model."),

    TaskSnapshot(tasks = emptyList(), pendingCount = 0),
    TaskSnapshot(
        tasks = listOf(
            TaskEntry("t1", "Buy milk", WatchTaskState.TODO),
            TaskEntry("t2", "Write spec", WatchTaskState.DOING),
            TaskEntry("t3", "Wait on legal", WatchTaskState.BLOCKED),
            TaskEntry("t4", "", WatchTaskState.DONE),
        ),
        pendingCount = 3,
    ),
    // Truncated to fit the Data Layer's size limit: more pending exist than are listed.
    TaskSnapshot(tasks = listOf(TaskEntry("t1", "Buy milk", WatchTaskState.TODO)), pendingCount = 17),

    TaskToggleRequest("t1", done = true),
    TaskToggleRequest("t1", done = false),
    TaskToggleResult.Applied("t1", done = true),
    TaskToggleResult.Applied("t1", done = false),
    TaskToggleResult.Rejected("t9", "That task no longer exists on the phone."),

    LoopRunSnapshot("run-1", "Refine", StateGlyphKeys.RUNNING, stepName = "critic", spend = Spend(120, "USD", estimated = true)),
    LoopRunSnapshot("run-2", "Refine", StateGlyphKeys.COMPLETE, stepName = null, spend = null),
    LoopRunSnapshot("run-3", "Local only", StateGlyphKeys.STOPPED, stepName = "proposer", spend = Spend(0, "INR", estimated = false)),
    LoopRunSnapshot("run-4", "Big", StateGlyphKeys.FAILED, stepName = "x", spend = Spend(Long.MAX_VALUE, "JPY", estimated = false)),
    LoopRunSnapshot("run-5", "Future", "paused_for_review", stepName = null, spend = null),
)

// ---- tiny JSON path helpers for generic mutation tests -------------------------------------------

internal fun parse(text: String): JSONObject = JSONObject(text)

/** Walks [path] (String keys / Int indexes) and returns the container that holds the last step. */
internal fun container(root: JSONObject, path: List<Any>): Any {
    var cur: Any = root
    for (step in path.dropLast(1)) {
        cur = when (step) {
            is String -> (cur as JSONObject).get(step)
            is Int -> (cur as JSONArray).get(step)
            else -> error("bad path step")
        }
    }
    return cur
}

internal fun removeAt(root: JSONObject, path: List<Any>) {
    val last = path.last()
    val c = container(root, path)
    when {
        c is JSONObject && last is String -> c.remove(last)
        c is JSONArray && last is Int -> c.remove(last)
        else -> error("bad path")
    }
}

internal fun setAt(root: JSONObject, path: List<Any>, value: Any?) {
    val last = path.last()
    val c = container(root, path)
    val v = value ?: JSONObject.NULL
    when {
        c is JSONObject && last is String -> c.put(last, v)
        c is JSONArray && last is Int -> c.put(last, v)
        else -> error("bad path")
    }
}
