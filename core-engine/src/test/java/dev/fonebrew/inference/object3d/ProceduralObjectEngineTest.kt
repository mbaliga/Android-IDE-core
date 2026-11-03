package dev.fonebrew.inference.object3d

import dev.fonebrew.domain.council.Generator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fakes [Generator] with a canned queue of completions, and records every (system, user) pair
 *  it was asked to complete — so a test can assert both the outcome AND that the retry prompt
 *  actually carried the validator's complaint back to the model (§4). */
private class QueueGenerator(private val responses: MutableList<String>) : Generator {
    val calls = mutableListOf<Pair<String, String>>()
    override suspend fun complete(system: String, user: String): String {
        calls += system to user
        return responses.removeAt(0)
    }
}

private const val VALID_DSL = """
Sure, here you go:
```object3d
{"schemaVersion":"1.0.0","ops":[{"id":"box1","kind":"BOX","transform":{"translate":{"x":0,"y":0,"z":0},"rotateDeg":{"x":0,"y":0,"z":0},"scale":{"x":1,"y":1,"z":1}},"colorHex":"#FF0000","params":{"width":1,"height":1,"depth":1}}]}
```
"""

private const val INVALID_DSL_MISSING_PARAM = """
```object3d
{"schemaVersion":"1.0.0","ops":[{"id":"box1","kind":"BOX","transform":{"translate":{"x":0,"y":0,"z":0},"rotateDeg":{"x":0,"y":0,"z":0},"scale":{"x":1,"y":1,"z":1}},"colorHex":"#FF0000","params":{}}]}
```
"""

private const val ADVERSARIAL_DSL_COLOR_URL = """
```object3d
{"schemaVersion":"1.0.0","ops":[{"id":"box1","kind":"BOX","transform":{"translate":{"x":0,"y":0,"z":0},"rotateDeg":{"x":0,"y":0,"z":0},"scale":{"x":1,"y":1,"z":1}},"colorHex":"https://evil.example/exfil","params":{"width":1,"height":1,"depth":1}}]}
```
"""

private const val VALID_OBJ = """
```object3d
v 0.0 0.0 0.0
v 1.0 0.0 0.0
v 0.0 1.0 0.0
f 1 2 3
```
"""

class ProceduralObjectEngineTest {

    @Test fun `valid DSL on the first try is not retried`() = runTest {
        val gen = QueueGenerator(mutableListOf(VALID_DSL))
        val outcome = ProceduralObjectEngine(gen).generate("a red box")

        assertTrue(outcome is ProceduralOutcome.Dsl)
        outcome as ProceduralOutcome.Dsl
        assertFalse(outcome.retried)
        assertEquals(1, outcome.scene.ops.size)
        assertEquals(1, gen.calls.size)
    }

    @Test fun `valid raw OBJ on the first try is accepted`() = runTest {
        val gen = QueueGenerator(mutableListOf(VALID_OBJ))
        val outcome = ProceduralObjectEngine(gen).generate("a flat triangle")

        assertTrue(outcome is ProceduralOutcome.RawObj)
        outcome as ProceduralOutcome.RawObj
        assertFalse(outcome.retried)
        assertEquals(3, outcome.stats.vertexCount)
        assertEquals(1, outcome.stats.faceCount)
    }

    @Test fun `an invalid first attempt retries once with the validator complaint, then succeeds`() = runTest {
        val gen = QueueGenerator(mutableListOf(INVALID_DSL_MISSING_PARAM, VALID_DSL))
        val outcome = ProceduralObjectEngine(gen).generate("a red box")

        assertTrue(outcome is ProceduralOutcome.Dsl)
        assertTrue((outcome as ProceduralOutcome.Dsl).retried)
        assertEquals(2, gen.calls.size)
        val retryUserPrompt = gen.calls[1].second
        assertTrue(
            "retry prompt should carry the validator's complaint",
            retryUserPrompt.contains("missing required param"),
        )
        assertTrue(retryUserPrompt.contains("a red box"))
    }

    @Test fun `two invalid attempts fail honestly with the last validator complaint`() = runTest {
        val gen = QueueGenerator(mutableListOf(INVALID_DSL_MISSING_PARAM, INVALID_DSL_MISSING_PARAM))
        val outcome = ProceduralObjectEngine(gen).generate("a red box")

        assertTrue(outcome is ProceduralOutcome.Failure)
        outcome as ProceduralOutcome.Failure
        assertTrue(outcome.reasons.any { it.contains("missing required param") })
        assertEquals(2, gen.calls.size) // exactly one retry, never more
    }

    @Test fun `a reply with no fenced block at all retries, then fails honestly if still missing`() = runTest {
        val gen = QueueGenerator(mutableListOf("sorry, I can't help with that", "still no block here"))
        val outcome = ProceduralObjectEngine(gen).generate("a red box")

        assertTrue(outcome is ProceduralOutcome.Failure)
        assertEquals(2, gen.calls.size)
        assertTrue((outcome as ProceduralOutcome.Failure).reasons.any { it.contains("no fenced") })
    }

    @Test fun `a DSL smuggling a URL into colorHex is refused by the validator, not accepted`() = runTest {
        // Adversarial minimum (§6, fixtures/object3d/adversarial's color-URL-smuggling case):
        // structurally valid JSON, but the validator must still refuse it.
        val gen = QueueGenerator(mutableListOf(ADVERSARIAL_DSL_COLOR_URL, ADVERSARIAL_DSL_COLOR_URL))
        val outcome = ProceduralObjectEngine(gen).generate("a box coloured like a link")

        assertTrue(outcome is ProceduralOutcome.Failure)
        assertTrue((outcome as ProceduralOutcome.Failure).reasons.any { it.contains("hex color") })
    }

    @Test fun `extractFence falls back to an untagged fence when the model omits the language tag`() {
        val text = "here:\n```\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n```"
        assertEquals("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3", ProceduralObjectEngine.extractFence(text))
    }

    @Test fun `extractFence prefers the object3d-tagged fence over a stray untagged one`() {
        val text = "```\nignore me\n```\n```object3d\nv 0 0 0\n```"
        assertEquals("v 0 0 0", ProceduralObjectEngine.extractFence(text))
    }
}
