package dev.fonebrew.domain.git

/**
 * Which Git host the Develop -> Agent flow will read from and commit to. Pure selection logic so
 * the "never silently the first host" rule is JVM-tested: with exactly one host connected it is
 * unambiguous; with several, the user must have picked one, and an unknown/stale pick is not
 * quietly replaced by another host.
 */
object AgentTarget {
    sealed interface Resolution {
        /** No host connected. */
        data object NoHost : Resolution
        /** Several hosts and no valid pick yet: the user must choose before Run/Commit. */
        data class NeedsChoice(val hosts: List<GitHost>) : Resolution
        data class Resolved(val host: GitHost) : Resolution
    }

    fun resolve(hosts: List<GitHost>, chosenId: String?): Resolution = when {
        hosts.isEmpty() -> Resolution.NoHost
        hosts.size == 1 -> Resolution.Resolved(hosts.single())
        else -> hosts.firstOrNull { it.id == chosenId }?.let { Resolution.Resolved(it) }
            ?: Resolution.NeedsChoice(hosts)
    }

    /** What the user sees before Run and before Commit: host name, repo and branch. */
    fun label(host: GitHost): String = "${host.displayName} · ${host.owner}/${host.repo} @ ${host.branch}"
}
