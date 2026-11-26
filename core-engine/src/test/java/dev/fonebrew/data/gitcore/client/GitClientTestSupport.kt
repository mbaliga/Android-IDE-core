package dev.fonebrew.domain.gitcore.client

import dev.fonebrew.data.gitcore.GitTransports
import dev.fonebrew.domain.gitcore.CommitOptions
import dev.fonebrew.domain.gitcore.GitTestSupport
import dev.fonebrew.domain.gitcore.JvmFileSystem
import dev.fonebrew.domain.gitcore.Repository
import dev.fonebrew.domain.gitcore.add
import dev.fonebrew.domain.gitcore.commit
import dev.fonebrew.domain.gitcore.transport.Credential
import dev.fonebrew.domain.gitcore.transport.CredentialProvider
import dev.fonebrew.domain.gitcore.transport.GitHttpFixture
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File

/** Shared helpers for tests that talk to a real `git http-backend` through the real OkHttp transport. */
object ClientEnv {
    val available: Boolean by lazy { GitHttpFixture.gitHttpBackendAvailable() }

    fun client(creds: CredentialProvider = CredentialProvider.NONE) = GitClient(JvmFileSystem(), GitTransports(creds))

    fun creds(user: String, pass: String, host: String? = null) = CredentialProvider { req ->
        if (host == null || host == req.host) Credential(user, pass) else null
    }

    fun clone(c: GitClient, url: String, dir: File, opts: CloneOptions = CloneOptions()): Pair<CloneResult, List<TransferEvent>> = runBlocking {
        val evs = c.clone(url, dir.absolutePath, opts).toList()
        evs.filterIsInstance<CloneEvent.Done>().single().result to evs.filterIsInstance<CloneEvent.Progress>().map { it.event }
    }

    fun failure(body: () -> Unit): GitFailure {
        try { body() } catch (e: GitClientException) { return e.failure }
        fail("expected a GitClientException"); throw IllegalStateException()
    }

    /** A work repository seeded through the git CLI and pushed into the bare server repo. */
    fun seed(fx: GitHttpFixture, name: String, commits: Int = 3, tags: Boolean = true): File {
        val bare = fx.createBare(name)
        val work = GitTestSupport.tempDir("seed")
        GitTestSupport.git(work, "init", "-q", "-b", "main")
        for (i in 1..commits) {
            GitTestSupport.write(File(work, "f$i.txt"), "content $i\n".repeat(20))
            GitTestSupport.write(File(work, "dir/sub/n$i.txt"), "nested $i")
            GitTestSupport.git(work, "add", "-A"); GitTestSupport.git(work, "commit", "-q", "-m", "commit $i")
        }
        GitTestSupport.git(work, "branch", "feature")
        if (tags) {
            GitTestSupport.git(work, "tag", "light")
            GitTestSupport.git(work, "tag", "-a", "annotated", "-m", "release")
        }
        GitTestSupport.git(work, "push", "-q", bare.absolutePath, "--all")
        if (tags) GitTestSupport.git(work, "push", "-q", bare.absolutePath, "--tags")
        return work
    }

    /** Adds a commit to the server repo through a throwaway clone (what "someone else pushed" looks like). */
    fun serverCommit(fx: GitHttpFixture, name: String, file: String, text: String, branch: String = "main"): String {
        val w = GitTestSupport.tempDir("other")
        GitTestSupport.git(w, "clone", "-q", fx.bare(name).absolutePath, ".")
        GitTestSupport.git(w, "checkout", "-q", branch)
        GitTestSupport.write(File(w, file), text)
        GitTestSupport.git(w, "add", "-A"); GitTestSupport.git(w, "commit", "-q", "-m", "server: $file")
        GitTestSupport.git(w, "push", "-q", "origin", branch)
        return GitTestSupport.git(w, "rev-parse", "HEAD").trim()
    }

    fun rev(dir: File, ref: String) = GitTestSupport.git(dir, "rev-parse", ref).trim()
    fun revList(dir: File) = GitTestSupport.git(dir, "rev-list", "--all").lines().filter { it.isNotBlank() }.sorted()

    /** git fsck --full and 'every commit on the server is in the clone'. */
    fun assertHealthyClone(clone: File, serverBare: File, bare: Boolean = false) {
        val cd = if (bare) clone else clone
        GitTestSupport.git(cd, "fsck", "--full", "--strict")
        val server = revList(serverBare)
        val local = revList(cd)
        assertEquals("rev-list --all differs between server and client", server, local)
    }

    fun openLocal(path: File): Repository {
        val r = Repository.open(JvmFileSystem(), path.absolutePath)
        r.config.set("user", null, "name", "Eng"); r.config.set("user", null, "email", "eng@test"); r.saveConfig()
        return r
    }

    fun localCommit(path: File, file: String, text: String, msg: String = "local $file") = openLocal(path).use { r ->
        File(path, file).apply { parentFile.mkdirs() }.writeText(text)
        r.add(listOf(file))
        r.commit(CommitOptions(msg))
    }

    fun leftovers(parent: File): List<String> = parent.list().orEmpty().toList()
    fun assertNoRepoAt(parent: File, target: File) {
        assertTrue("target must not exist", !target.exists())
        assertEquals("no staging debris", emptyList<String>(), leftovers(parent))
    }
}
