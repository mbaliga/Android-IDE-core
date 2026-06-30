package dev.aarso.domain.scope

/**
 * Knowledge scoping (Doc 03, "Projects-as-Repos & Knowledge Scoping"): *which* projects
 * a turn is allowed to draw context from. Scope is the **first, legible knob** in the
 * hybrid context-assembly model — the user always knows, and can always narrow, the
 * blast radius of what the model is allowed to see.
 *
 * Three scopes, deliberately small:
 * - [ThisProject]      — only the current project's corpus (the safe default).
 * - [SelectedProjects] — an explicit, user-chosen set of project ids.
 * - [AllProjects]       — everything the user has (the widest, opt-in lens).
 *
 * Scope is a pure value: it names projects, it does not fetch anything. [resolveProjects]
 * turns a scope plus the ambient "current project" / "all projects" facts into the
 * concrete set of project ids that downstream assembly filters the corpus to. This file
 * is pure domain (no Android, no IO), JVM-tested.
 */
sealed interface Scope {

    /** A short, human-facing name for this scope — the "watched object" label in the UI. */
    val label: String

    /** Only the project the user is currently in. The conservative default. */
    data object ThisProject : Scope {
        override val label: String get() = "This project"
    }

    /**
     * An explicit, user-chosen set of project ids. An empty set is *honest emptiness* —
     * it resolves to no projects (an empty corpus), it is not silently widened.
     */
    data class SelectedProjects(val ids: Set<String>) : Scope {
        override val label: String
            get() = when (ids.size) {
                0 -> "No projects selected"
                1 -> "1 selected project"
                else -> "${ids.size} selected projects"
            }
    }

    /** Every project the user has. The widest lens — opt-in, never a hidden fallback. */
    data object AllProjects : Scope {
        override val label: String get() = "All projects"
    }
}

/** Pure scope operations. */
object Scopes {

    /**
     * Resolve a [scope] to the concrete set of project ids it covers:
     * - [Scope.ThisProject]      → `{currentProjectId}`.
     * - [Scope.SelectedProjects] → exactly the chosen ids (no widening, no implicit current).
     * - [Scope.AllProjects]      → all of [allProjectIds].
     *
     * The result is order-independent (a [Set]); determinism here means *which* ids, not
     * their iteration order. Selecting a project id that is not in [allProjectIds] is
     * preserved as given — resolution does not silently drop unknown ids, it trusts the
     * caller's explicit selection; the corpus filter downstream is what makes a
     * non-existent project contribute nothing.
     */
    fun resolveProjects(
        scope: Scope,
        currentProjectId: String,
        allProjectIds: Set<String>,
    ): Set<String> = when (scope) {
        is Scope.ThisProject -> setOf(currentProjectId)
        is Scope.SelectedProjects -> scope.ids
        is Scope.AllProjects -> allProjectIds
    }
}
