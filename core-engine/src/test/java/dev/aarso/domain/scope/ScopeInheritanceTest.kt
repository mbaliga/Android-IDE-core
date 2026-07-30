package dev.aarso.domain.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scope inheritance chain (Doc 03 §4.2): override → project default → global, with provenance. */
class ScopeInheritanceTest {

    private val selected = Scope.SelectedProjects(setOf("p1", "p2"))

    private val defaults = ScopeDefaults(
        global = Scope.ThisProject,
        perProject = mapOf(
            "projA" to Scope.AllProjects,
            "projB" to selected,
        ),
        perConversationOverride = mapOf(
            "convOver" to Scope.AllProjects,
        ),
    )

    // ---- override wins -------------------------------------------------------------------

    @Test
    fun override_wins_overProjectAndGlobal() {
        val r = resolveActiveScope("convOver", conversationProjectId = "projA", defaults = defaults)
        assertEquals(Scope.AllProjects, r.scope)
        assertEquals(ScopeSource.CONVERSATION_OVERRIDE, r.source)
    }

    @Test
    fun override_wins_evenWithNoProject() {
        val r = resolveActiveScope("convOver", conversationProjectId = null, defaults = defaults)
        assertEquals(Scope.AllProjects, r.scope)
        assertEquals(ScopeSource.CONVERSATION_OVERRIDE, r.source)
    }

    // ---- project default applies ---------------------------------------------------------

    @Test
    fun projectDefault_appliesForProjectChat_noOverride() {
        val r = resolveActiveScope("convX", conversationProjectId = "projA", defaults = defaults)
        assertEquals(Scope.AllProjects, r.scope)
        assertEquals(ScopeSource.PROJECT_DEFAULT, r.source)
    }

    @Test
    fun projectDefault_canBeSelectedProjects() {
        val r = resolveActiveScope("convY", conversationProjectId = "projB", defaults = defaults)
        assertEquals(selected, r.scope)
        assertEquals(ScopeSource.PROJECT_DEFAULT, r.source)
    }

    // ---- global fallback -----------------------------------------------------------------

    @Test
    fun global_fallback_whenNoProject() {
        val r = resolveActiveScope("convZ", conversationProjectId = null, defaults = defaults)
        assertEquals(Scope.ThisProject, r.scope)
        assertEquals(ScopeSource.GLOBAL_DEFAULT, r.source)
    }

    @Test
    fun global_fallback_whenProjectHasNoDefault() {
        val r = resolveActiveScope("convZ", conversationProjectId = "projUnknown", defaults = defaults)
        assertEquals(Scope.ThisProject, r.scope)
        assertEquals(ScopeSource.GLOBAL_DEFAULT, r.source)
    }

    @Test
    fun global_fallback_whenDefaultsAreBareGlobalOnly() {
        val bare = ScopeDefaults(global = Scope.AllProjects)
        val r = resolveActiveScope("c", conversationProjectId = "anyProject", defaults = bare)
        assertEquals(Scope.AllProjects, r.scope)
        assertEquals(ScopeSource.GLOBAL_DEFAULT, r.source)
    }

    // ---- source correctness for each layer ----------------------------------------------

    @Test
    fun source_isCorrect_acrossAllThreeLayers() {
        assertEquals(
            ScopeSource.CONVERSATION_OVERRIDE,
            resolveActiveScope("convOver", "projA", defaults).source,
        )
        assertEquals(
            ScopeSource.PROJECT_DEFAULT,
            resolveActiveScope("freshConv", "projA", defaults).source,
        )
        assertEquals(
            ScopeSource.GLOBAL_DEFAULT,
            resolveActiveScope("freshConv", null, defaults).source,
        )
    }

    @Test
    fun resolution_isDeterministic() {
        val a = resolveActiveScope("convOver", "projA", defaults)
        val b = resolveActiveScope("convOver", "projA", defaults)
        assertEquals(a, b)
    }

    // ---- withOverride / clearOverride immutability ---------------------------------------

    @Test
    fun withOverride_returnsNewInstance_leavesOriginalUnchanged() {
        val before = defaults
        val after = withOverride(before, "newConv", Scope.AllProjects)
        assertNotSame(before, after)
        // original has no override for newConv
        assertEquals(
            ScopeSource.GLOBAL_DEFAULT,
            resolveActiveScope("newConv", null, before).source,
        )
        // original map is untouched
        assertTrue(!before.perConversationOverride.containsKey("newConv"))
    }

    @Test
    fun withOverride_makesResolutionPickIt() {
        val after = withOverride(defaults, "newConv", selected)
        val r = resolveActiveScope("newConv", conversationProjectId = "projA", defaults = after)
        assertEquals(selected, r.scope)
        assertEquals(ScopeSource.CONVERSATION_OVERRIDE, r.source)
    }

    @Test
    fun withOverride_replacesExistingOverride() {
        val after = withOverride(defaults, "convOver", Scope.ThisProject)
        val r = resolveActiveScope("convOver", "projA", after)
        assertEquals(Scope.ThisProject, r.scope)
        assertEquals(ScopeSource.CONVERSATION_OVERRIDE, r.source)
    }

    @Test
    fun clearOverride_fallsBackToProjectDefault() {
        val after = clearOverride(defaults, "convOver")
        val r = resolveActiveScope("convOver", conversationProjectId = "projA", defaults = after)
        assertEquals(Scope.AllProjects, r.scope) // projA's project default
        assertEquals(ScopeSource.PROJECT_DEFAULT, r.source)
    }

    @Test
    fun clearOverride_fallsBackToGlobalWhenNoProjectDefault() {
        val after = clearOverride(defaults, "convOver")
        val r = resolveActiveScope("convOver", conversationProjectId = null, defaults = after)
        assertEquals(Scope.ThisProject, r.scope)
        assertEquals(ScopeSource.GLOBAL_DEFAULT, r.source)
    }

    @Test
    fun clearOverride_leavesOriginalUnchanged() {
        val before = defaults
        val after = clearOverride(before, "convOver")
        assertNotSame(before, after)
        // original still resolves the override
        assertEquals(
            ScopeSource.CONVERSATION_OVERRIDE,
            resolveActiveScope("convOver", "projA", before).source,
        )
    }

    @Test
    fun clearOverride_isIdempotentNoOp_whenAbsent() {
        val after = clearOverride(defaults, "noSuchConv")
        assertEquals(defaults, after)
    }

    @Test
    fun withThenClear_roundTripsToEquivalent() {
        val withIt = withOverride(defaults, "tmpConv", Scope.AllProjects)
        val cleared = clearOverride(withIt, "tmpConv")
        assertEquals(defaults, cleared)
    }
}
