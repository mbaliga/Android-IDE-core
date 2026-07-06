package dev.aarso.domain.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved selected-projects sets (Doc 03 §4.2): conversation set → project default → null. */
class SelectedSetsTest {

    private val sets = SavedSelectedSets(
        perProjectDefaultSet = mapOf(
            "projA" to setOf("p1", "p2"),
            "projEmpty" to emptySet(),
        ),
        perConversationSet = mapOf(
            "convPinned" to setOf("p9"),
            "convEmpty" to emptySet(),
        ),
    )

    // ---- precedence ----------------------------------------------------------------------

    @Test
    fun conversationSet_wins_overProjectDefault() {
        val r = resolveSelectedProjects("convPinned", conversationProjectId = "projA", sets = sets)
        assertEquals(setOf("p9"), r)
    }

    @Test
    fun projectDefault_appliesWhenNoConversationSet() {
        val r = resolveSelectedProjects("freshConv", conversationProjectId = "projA", sets = sets)
        assertEquals(setOf("p1", "p2"), r)
    }

    @Test
    fun nullWhenNeitherLayerHasASet() {
        val r = resolveSelectedProjects("freshConv", conversationProjectId = "projUnknown", sets = sets)
        assertNull(r)
    }

    @Test
    fun nullWhenNoProjectAndNoConversationSet() {
        val r = resolveSelectedProjects("freshConv", conversationProjectId = null, sets = sets)
        assertNull(r)
    }

    // ---- empty-set is distinct from null -------------------------------------------------

    @Test
    fun emptyConversationSet_isReturned_notTreatedAsAbsent() {
        val r = resolveSelectedProjects("convEmpty", conversationProjectId = "projA", sets = sets)
        assertEquals(emptySet<String>(), r)
        assertTrue(r != null)
    }

    @Test
    fun emptyProjectDefaultSet_isReturned_notNull() {
        val r = resolveSelectedProjects("freshConv", conversationProjectId = "projEmpty", sets = sets)
        assertEquals(emptySet<String>(), r)
        assertTrue(r != null)
    }

    @Test
    fun loseConversation_skipsProjectLayer_evenIfProjectHasDefault() {
        // null projectId: the projA default must NOT leak in.
        val r = resolveSelectedProjects("freshConv", conversationProjectId = null, sets = sets)
        assertNull(r)
    }

    @Test
    fun resolution_isDeterministic() {
        val a = resolveSelectedProjects("freshConv", "projA", sets)
        val b = resolveSelectedProjects("freshConv", "projA", sets)
        assertEquals(a, b)
    }

    // ---- withConversationSet -------------------------------------------------------------

    @Test
    fun withConversationSet_isImmutable_andResolves() {
        val before = sets
        val after = withConversationSet(before, "newConv", setOf("p3", "p4"))
        assertNotSame(before, after)
        // Use a project with no default so this isolates "before has no newConv set"
        // (projA carries a project default that would legitimately apply).
        assertNull(resolveSelectedProjects("newConv", "projUnknown", before))
        assertEquals(setOf("p3", "p4"), resolveSelectedProjects("newConv", "projA", after))
    }

    @Test
    fun withConversationSet_canSaveEmpty() {
        val after = withConversationSet(sets, "newConv", emptySet())
        assertEquals(emptySet<String>(), resolveSelectedProjects("newConv", "projA", after))
    }

    @Test
    fun withConversationSet_replacesExisting() {
        val after = withConversationSet(sets, "convPinned", setOf("pX"))
        assertEquals(setOf("pX"), resolveSelectedProjects("convPinned", "projA", after))
    }

    // ---- withProjectDefaultSet -----------------------------------------------------------

    @Test
    fun withProjectDefaultSet_isImmutable_andResolves() {
        val before = sets
        val after = withProjectDefaultSet(before, "projNew", setOf("p7"))
        assertNotSame(before, after)
        assertNull(resolveSelectedProjects("c", "projNew", before))
        assertEquals(setOf("p7"), resolveSelectedProjects("c", "projNew", after))
    }

    @Test
    fun withProjectDefaultSet_doesNotOverrideConversationSet() {
        val after = withProjectDefaultSet(sets, "projA", setOf("zzz"))
        // convPinned still wins with its own set
        assertEquals(setOf("p9"), resolveSelectedProjects("convPinned", "projA", after))
        // but a fresh conv in projA picks up the new default
        assertEquals(setOf("zzz"), resolveSelectedProjects("freshConv", "projA", after))
    }

    @Test
    fun withProjectDefaultSet_replacesExisting() {
        val after = withProjectDefaultSet(sets, "projA", setOf("only"))
        assertEquals(setOf("only"), resolveSelectedProjects("freshConv", "projA", after))
    }
}
