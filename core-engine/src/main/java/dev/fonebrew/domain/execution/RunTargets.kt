package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.execution.ExecutionTargetType
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import dev.fonebrew.domain.provenance.ProvenanceState
import dev.fonebrew.domain.remote.RemoteHost

/**
 * Develop -> Run: the selectable places a command can actually execute, built ONLY from
 * providers that exist (LOCAL_ANDROID, SSH_HOST, GITHUB_ACTIONS/GITEA_ACTIONS -- WP-5's
 * conformance-tested trio; RASPBERRY_PI is the same SSH provider by construction, see
 * [SshExecutionProvider]'s own doc comment, so it is not a separate [RunTarget] here). Pure
 * Kotlin, no Android, no I/O -- [RunTargetCatalog.list] is a straight fold over already-loaded
 * host lists, JVM-tested without a device or a real Git/SSH connection.
 *
 * `targetId` is this run target's [dev.fonebrew.contracts.execution.ExecutionRequest.targetId]
 * / [dev.fonebrew.contracts.authority.ResourceScope.locator] -- stable and reversible (an SSH
 * host's alias, a Git host's own id) so the same physical target always resolves to the same
 * authority grant, never a fresh one per run.
 */
sealed interface RunTarget {
    /** Stable id -- the [dev.fonebrew.contracts.execution.ExecutionRequest.targetId] this target resolves to. */
    val targetId: String
    val label: String
    val executionTargetType: ExecutionTargetType

    /** The `fb.*` capability this target's execution goes through (`CapabilityRegistry`, WP-4). */
    val capabilityId: String

    /**
     * Where this run's output is truly happening -- reused, per the house rule, from the exact
     * grammar [dev.fonebrew.ui.components.ProvenanceBadge] already renders for cloud model
     * routing (binding rule 2: cloud/remote reach is always visibly a "watched object", never a
     * hidden fallback). [LOCAL_ANDROID] never leaves the device; SSH and CI both do.
     */
    val provenance: ProvenanceState

    /** This phone. Constrained per [LocalProcessExecutionProvider]'s own doc comment -- W^X means
     *  a real on-device run can only invoke an already-installed, package-signed executable, not
     *  an arbitrary shell command; the Run panel says this plainly rather than promising more. */
    data object Local : RunTarget {
        override val targetId: String = "local-android"
        override val label: String = "This phone"
        override val executionTargetType: ExecutionTargetType = ExecutionTargetType.LOCAL_ANDROID
        override val capabilityId: String = "fb.exec.local_process"
        override val provenance: ProvenanceState = ProvenanceState.LOCAL
    }

    /** A saved, SSH-reachable machine (homelab runner, Pi, ...) -- [RemoteHostStore]'s own list. */
    data class Ssh(val host: RemoteHost) : RunTarget {
        override val targetId: String = "ssh:${host.alias}"
        override val label: String = host.alias
        override val executionTargetType: ExecutionTargetType = ExecutionTargetType.SSH_HOST
        override val capabilityId: String = "fb.exec.ssh_remote"
        override val provenance: ProvenanceState = ProvenanceState.CLOUD
    }

    /** A connected Git host's Actions -- dispatches the repo's own CI workflow (CLAUDE.md's
     *  "the owner's own workflows are the oracle, not local tool execution"). */
    data class Ci(val host: GitHost) : RunTarget {
        override val targetId: String = "ci:${host.id}"
        override val label: String = "${host.owner}/${host.repo} CI"
        override val executionTargetType: ExecutionTargetType =
            if (host.kind == GitHostKind.GITHUB) ExecutionTargetType.GITHUB_ACTIONS else ExecutionTargetType.GITEA_ACTIONS
        override val capabilityId: String = "fb.ci.dispatch"
        override val provenance: ProvenanceState = ProvenanceState.CLOUD
    }
}

object RunTargetCatalog {
    /** This phone first (always present -- [localAvailable] exists only so a test can omit it),
     *  then saved SSH hosts, then connected Git hosts, each in the order the caller's own store
     *  already sorts them. No target is ever synthesized past what the caller actually has. */
    fun list(
        localAvailable: Boolean = true,
        sshHosts: List<RemoteHost> = emptyList(),
        ciHosts: List<GitHost> = emptyList(),
    ): List<RunTarget> = buildList {
        if (localAvailable) add(RunTarget.Local)
        sshHosts.forEach { add(RunTarget.Ssh(it)) }
        ciHosts.forEach { add(RunTarget.Ci(it)) }
    }
}
