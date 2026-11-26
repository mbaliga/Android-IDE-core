package dev.fonebrew.domain.loop

import dev.fonebrew.domain.bpmn.BpmnEdge
import dev.fonebrew.domain.bpmn.BpmnNode
import dev.fonebrew.domain.bpmn.BpmnNodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionEvaluatorTest {

    private fun eval(cond: String, out: String): Boolean {
        val c = ConditionSyntax.parse(cond)
        assertNotNull("should parse: $cond", c)
        return ConditionEvaluator.evaluate(c!!, out)
    }

    /** (condition, output, expected) — one row per behaviour, boundaries included. */
    private val table = listOf(
        Triple("approved", "APPROVE ok", true),
        Triple("approved", "  approve ok", true),
        Triple("approved", "not yet", false),
        Triple("!approved", "not yet", true),
        Triple("!approved", "APPROVE", false),
        Triple("not approved", "x", true),
        Triple("contains(cat)", "a cat sat", true),
        Triple("contains(cat)", "a CAT sat", false),
        Triple("contains-ci(cat)", "a CAT sat", true),
        Triple("contains(cat)", "", false),
        Triple("!contains(cat)", "dog", true),
        Triple("!contains(cat)", "cat", false),
        Triple("!contains-ci(cat)", "CAT", false),
        Triple("!contains-ci(cat)", "dog", true),
        Triple("contains(a.*b)", "a.*b", true),       // literal, not a pattern
        Triple("contains(a.*b)", "axxb", false),
        Triple("contains(a(b))", "xa(b)x", true),     // parens inside the text
        Triple("equals(done)", "done", true),
        Triple("equals(done)", "  done \n", true),    // output trimmed
        Triple("equals(done)", "Done", false),
        Triple("equals-ci(done)", "DONE", true),
        Triple("equals(done)", "done!", false),
        Triple("startsWith(Yes)", "Yes, sure", true),
        Triple("startsWith(Yes)", "  Yes", true),
        Triple("startsWith(Yes)", "yes", false),
        Triple("startsWith-ci(Yes)", "yes", true),
        Triple("startsWith(Yes)", "No. Yes", false),
        Triple("length>3", "abcd", true),
        Triple("length>3", "abc", false),             // boundary: equal is not greater
        Triple("length<3", "ab", true),
        Triple("length<3", "abc", false),             // boundary
        Triple("length>0", "", false),
        Triple("length<1", "", true),
        Triple("length>2", "😀😀😀", true),            // code points: 3, not 6 UTF-16 units
        Triple("length>3", "😀😀😀", false),
        Triple("is-empty", "", true),
        Triple("is-empty", "  \n", true),
        Triple("is-empty", "x", false),
        Triple("is-not-empty", "x", true),
        Triple("is-not-empty", "", false),
        Triple("contains(é)", "café", true),
        Triple("contains-ci(É)", "café", true),
        Triple("equals(日本語)", "日本語", true),
    )

    @Test fun `every row of the condition table`() {
        for ((cond, out, expected) in table) {
            assertEquals("$cond on \"$out\"", expected, eval(cond, out))
        }
    }

    @Test fun `format and parse round-trip every condition shape`() {
        val samples = listOf(
            "approved", "!approved", "contains(a b)", "contains-ci(x)", "!contains(x)", "!contains-ci(x)",
            "equals(x)", "equals-ci(x)", "startsWith(x)", "startsWith-ci(x)", "length>0", "length<65536",
            "is-empty", "is-not-empty",
        )
        for (s in samples) {
            val c = ConditionSyntax.parse(s)!!
            assertEquals(s, ConditionSyntax.format(c))
            assertEquals(c, ConditionSyntax.parse(ConditionSyntax.format(c)))
        }
    }

    @Test fun `unparseable conditions are null, never throw`() {
        val bad = listOf(
            "", "  ", "contains()", "contains(", "contains)", "contain(x)", "length>", "length>abc", "length>-1",
            "length>99999999999999999999", "length>65537", "regex(.*)", "matches(a+)", "contains(${"x".repeat(201)})",
            "approved && x", "x > 3", "length=3", "equals", "is-empty(x)",
        )
        for (b in bad) assertNull("should not parse: '$b'", ConditionSyntax.parse(b))
        assertNull(ConditionSyntax.parse(null))
    }

    @Test fun `text at the cap parses and one over does not`() {
        assertNotNull(ConditionSyntax.parse("contains(${"x".repeat(200)})"))
        assertNull(ConditionSyntax.parse("contains(${"x".repeat(201)})"))
    }

    @Test fun `oversize input is bounded and the cap is stated behaviour`() {
        val huge = "a".repeat(ConditionEvaluator.INPUT_CAP + 50_000) + "NEEDLE"
        // Beyond the cap is invisible: contains cannot see it...
        assertFalse(eval("contains(NEEDLE)", huge))
        // ...and a length bound at or above the cap is never true.
        assertFalse(eval("length>${ConditionEvaluator.INPUT_CAP}", huge))
        assertTrue(eval("length>${ConditionEvaluator.INPUT_CAP - 1}", huge))
        // A needle inside the cap is found.
        assertTrue(eval("contains(NEEDLE)", "a".repeat(1000) + "NEEDLE" + "b".repeat(ConditionEvaluator.INPUT_CAP)))
    }

    @Test fun `a pattern-looking input cannot cause backtracking (no regex is ever compiled)`() {
        val evil = "a".repeat(60_000) + "!"
        val t0 = System.nanoTime()
        assertFalse(eval("contains((a+)+\$)", evil))
        assertFalse(eval("equals((a+)+\$)", evil))
        assertTrue("linear-time evaluation", (System.nanoTime() - t0) < 2_000_000_000L)
    }

    @Test fun `evaluation is deterministic`() {
        repeat(3) { assertTrue(eval("contains-ci(Hello)", "oh HELLO there")) }
    }

    // ── the gateway policy built on it ───────────────────────────────────────────

    private val gate = BpmnNode("g", BpmnNodeKind.EXCLUSIVE_GATEWAY)

    @Test fun `the policy takes the first edge whose condition holds and says why`() {
        val edges = listOf(
            BpmnEdge("a", "g", "x", condition = "contains(alpha)"),
            BpmnEdge("b", "g", "y", condition = "contains(beta)"),
            BpmnEdge("c", "g", "z"),
        )
        val c = ConditionGatewayPolicy.decide(gate, "has beta in it", edges)
        assertEquals("b", c.edge!!.id)
        assertTrue(c.reason, c.reason.startsWith("condition matched"))
    }

    @Test fun `an unmatched gateway takes the default branch and logs the reason`() {
        val edges = listOf(
            BpmnEdge("a", "g", "x", condition = "contains(alpha)"),
            BpmnEdge("c", "g", "z"),
        )
        val c = ConditionGatewayPolicy.decide(gate, "nothing relevant", edges)
        assertEquals("c", c.edge!!.id)
        assertEquals("no condition matched - default branch", c.reason)
    }

    @Test fun `a legacy condition the evaluator cannot read behaves exactly as before - default branch`() {
        val edges = listOf(
            BpmnEdge("a", "g", "x", condition = "score > 0.8"),
            BpmnEdge("c", "g", "z"),
        )
        assertEquals("c", ConditionGatewayPolicy.choose(gate, "anything", edges)!!.id)
        // With no unconditioned edge the first edge is the default, as ever.
        val only = listOf(BpmnEdge("a", "g", "x", condition = "score > 0.8"), BpmnEdge("b", "g", "y", condition = "other"))
        assertEquals("a", ConditionGatewayPolicy.choose(gate, "anything", only)!!.id)
    }

    @Test fun `approved and not approved behave exactly as before`() {
        val edges = listOf(
            BpmnEdge("ok", "g", "x", name = "approve", condition = "approved"),
            BpmnEdge("no", "g", "y", name = "refine", condition = "!approved"),
        )
        assertEquals("ok", ConditionGatewayPolicy.choose(gate, "APPROVE fine", edges)!!.id)
        assertEquals("no", ConditionGatewayPolicy.choose(gate, "needs work", edges)!!.id)
    }

    @Test fun `label-only edges still branch by name`() {
        val edges = listOf(BpmnEdge("ok", "g", "x", name = "approve"), BpmnEdge("no", "g", "y", name = "refine"))
        assertEquals("ok", ConditionGatewayPolicy.choose(gate, "APPROVE", edges)!!.id)
        assertEquals("no", ConditionGatewayPolicy.choose(gate, "meh", edges)!!.id)
    }
}
