package dev.fonebrew.domain.loop

import dev.fonebrew.domain.loop.authoring.ConditionDraft
import dev.fonebrew.domain.loop.authoring.ConditionKind
import dev.fonebrew.domain.loop.authoring.GatewayConditionPresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionDraftTest {

    @Test fun `every kind builds a condition the runner can read`() {
        for (k in ConditionKind.entries) {
            val d = ConditionDraft(k, text = if (k.takesNumber) "5" else if (k.takesText) "abc" else "")
            val s = d.toText()
            assertNotNull("$k", s)
            assertNotNull("runner can parse $k -> $s", ConditionSyntax.parse(s))
            assertEquals(d, ConditionDraft.from(s))
        }
    }

    @Test fun `case choice is explicit in the saved text`() {
        assertEquals("contains(x)", ConditionDraft(ConditionKind.CONTAINS, "x", ignoreCase = false).toText())
        assertEquals("contains-ci(x)", ConditionDraft(ConditionKind.CONTAINS, "x", ignoreCase = true).toText())
    }

    @Test fun `an incomplete or invalid draft cannot be committed and says why`() {
        assertNull(ConditionDraft(ConditionKind.CONTAINS, "").toText())
        assertNotNull(ConditionDraft(ConditionKind.CONTAINS, "").problem())
        assertNull(ConditionDraft(ConditionKind.LENGTH_GREATER, "abc").toText())
        assertNull(ConditionDraft(ConditionKind.LENGTH_GREATER, "999999").toText())
        assertNull(ConditionDraft(ConditionKind.CONTAINS, "x".repeat(201)).toText())
        assertNull(ConditionDraft(null).toText())
    }

    @Test fun `a legacy free-text condition does not open in the structured editor`() {
        assertNull(ConditionDraft.from("score > 0.8"))
    }

    @Test fun `the Wave 0 not-evaluated note follows the real parser`() {
        assertNull(GatewayConditionPresenter.notEvaluatedNote("contains(x)"))
        assertNull(GatewayConditionPresenter.notEvaluatedNote("length>10"))
        assertNull(GatewayConditionPresenter.notEvaluatedNote(null))
        assertNotNull(GatewayConditionPresenter.notEvaluatedNote("score > 0.8"))
        assertTrue(GatewayConditionPresenter.notEvaluatedNote("score > 0.8")!!.contains("not evaluated"))
        assertFalse(GatewayConditionPresenter.isEvaluable("matches(a+)"))
    }
}
