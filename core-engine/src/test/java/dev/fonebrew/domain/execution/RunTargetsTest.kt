package dev.fonebrew.domain.execution

import dev.fonebrew.contracts.execution.ExecutionTargetType
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import dev.fonebrew.domain.provenance.ProvenanceState
import dev.fonebrew.domain.remote.RemoteHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunTargetsTest {

    private fun sshHost(alias: String = "homelab") = RemoteHost(alias = alias, hostname = "10.0.0.5", username = "pi")

    private fun gitHost(kind: GitHostKind = GitHostKind.GITHUB, id: String = "host-1") = GitHost(
        id = id, displayName = "repo", kind = kind, baseUrl = "", owner = "me", repo = "product",
        branch = "main", authorName = "me", authorEmail = "me@example.com",
    )

    @Test
    fun `catalog -- local only when no hosts are connected`() {
        val targets = RunTargetCatalog.list(localAvailable = true, sshHosts = emptyList(), ciHosts = emptyList())
        assertEquals(listOf(RunTarget.Local), targets)
    }

    @Test
    fun `catalog -- local can be omitted, never a target with nothing behind it`() {
        val targets = RunTargetCatalog.list(localAvailable = false, sshHosts = emptyList(), ciHosts = emptyList())
        assertTrue(targets.isEmpty())
    }

    @Test
    fun `catalog -- local, then every saved SSH host, then every connected Git host`() {
        val ssh = sshHost()
        val git = gitHost()
        val targets = RunTargetCatalog.list(sshHosts = listOf(ssh), ciHosts = listOf(git))
        assertEquals(listOf(RunTarget.Local, RunTarget.Ssh(ssh), RunTarget.Ci(git)), targets)
    }

    @Test
    fun `Local -- unwatched, LOCAL_ANDROID, fb-exec-local_process`() {
        assertEquals(ProvenanceState.LOCAL, RunTarget.Local.provenance)
        assertEquals(ExecutionTargetType.LOCAL_ANDROID, RunTarget.Local.executionTargetType)
        assertEquals("fb.exec.local_process", RunTarget.Local.capabilityId)
    }

    @Test
    fun `Ssh -- watched, SSH_HOST, fb-exec-ssh_remote, targetId keyed off the alias`() {
        val t = RunTarget.Ssh(sshHost(alias = "dell-box"))
        assertEquals(ProvenanceState.CLOUD, t.provenance)
        assertEquals(ExecutionTargetType.SSH_HOST, t.executionTargetType)
        assertEquals("fb.exec.ssh_remote", t.capabilityId)
        assertEquals("ssh:dell-box", t.targetId)
    }

    @Test
    fun `Ci -- watched, target type follows the host kind, fb-ci-dispatch`() {
        val github = RunTarget.Ci(gitHost(kind = GitHostKind.GITHUB))
        val gitea = RunTarget.Ci(gitHost(kind = GitHostKind.GITEA))
        assertEquals(ProvenanceState.CLOUD, github.provenance)
        assertEquals(ExecutionTargetType.GITHUB_ACTIONS, github.executionTargetType)
        assertEquals(ExecutionTargetType.GITEA_ACTIONS, gitea.executionTargetType)
        assertEquals("fb.ci.dispatch", github.capabilityId)
    }

    @Test
    fun `two targets for the same host resolve to the same targetId -- stable authority scoping`() {
        val host = sshHost()
        assertEquals(RunTarget.Ssh(host).targetId, RunTarget.Ssh(host.copy()).targetId)
    }
}
