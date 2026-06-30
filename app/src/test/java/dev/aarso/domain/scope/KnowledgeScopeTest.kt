package dev.aarso.domain.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scope resolution + labels — the first legible knob in context assembly. */
class KnowledgeScopeTest {

    private val all = setOf("p1", "p2", "p3")

    @Test
    fun thisProject_resolvesToCurrentOnly() {
        val r = Scopes.resolveProjects(Scope.ThisProject, currentProjectId = "p2", allProjectIds = all)
        assertEquals(setOf("p2"), r)
    }

    @Test
    fun selectedProjects_resolvesToTheChosenSet() {
        val scope = Scope.SelectedProjects(setOf("p1", "p3"))
        val r = Scopes.resolveProjects(scope, currentProjectId = "p2", allProjectIds = all)
        assertEquals(setOf("p1", "p3"), r)
    }

    @Test
    fun selectedProjects_doesNotImplicitlyAddCurrent() {
        val scope = Scope.SelectedProjects(setOf("p1"))
        val r = Scopes.resolveProjects(scope, currentProjectId = "p2", allProjectIds = all)
        assertTrue("current must not be silently widened in", "p2" !in r)
        assertEquals(setOf("p1"), r)
    }

    @Test
    fun selectedProjects_emptyIsHonestEmptiness() {
        val r = Scopes.resolveProjects(Scope.SelectedProjects(emptySet()), "p2", all)
        assertTrue(r.isEmpty())
    }

    @Test
    fun selectedProjects_preservesUnknownIds() {
        // Resolution trusts the explicit selection; the corpus filter is what makes
        // an unknown project contribute nothing.
        val r = Scopes.resolveProjects(Scope.SelectedProjects(setOf("ghost")), "p2", all)
        assertEquals(setOf("ghost"), r)
    }

    @Test
    fun allProjects_resolvesToEverything() {
        val r = Scopes.resolveProjects(Scope.AllProjects, currentProjectId = "p2", allProjectIds = all)
        assertEquals(all, r)
    }

    @Test
    fun allProjects_emptyUniverseResolvesEmpty() {
        val r = Scopes.resolveProjects(Scope.AllProjects, "p2", emptySet())
        assertTrue(r.isEmpty())
    }

    @Test
    fun labels_areLegible() {
        assertEquals("This project", Scope.ThisProject.label)
        assertEquals("All projects", Scope.AllProjects.label)
        assertEquals("No projects selected", Scope.SelectedProjects(emptySet()).label)
        assertEquals("1 selected project", Scope.SelectedProjects(setOf("a")).label)
        assertEquals("2 selected projects", Scope.SelectedProjects(setOf("a", "b")).label)
    }
}
