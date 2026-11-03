package dev.fonebrew.data

import dev.fonebrew.data.dao.EmbeddingDao
import dev.fonebrew.data.dao.MessageNodeDao
import dev.fonebrew.data.dao.TokenCountDao
import dev.fonebrew.data.entity.MessageEmbeddingEntity
import dev.fonebrew.data.entity.TokenCountEntity
import dev.fonebrew.domain.diff.ChangeSet
import dev.fonebrew.domain.diff.FileChange
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import dev.fonebrew.domain.git.GitRequest
import dev.fonebrew.domain.ide.CommitAnchor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Graph-wave lane D.** [AgentRepoRunner.commit]'s own KDoc explains why this file tests
 * [AgentRepoRunner.squashedCommit]/[AgentRepoRunner.perFileCommit]/[AgentRepoRunner.recordCommitAnchors]
 * directly (companion-object, `internal`, taking every dependency explicitly) rather than through
 * a constructed [AgentRepoRunner] instance: the public constructor also needs a real
 * [GitHostStore] (`android.content.Context`-backed), [dev.fonebrew.inference.ModelRegistry], and
 * [dev.fonebrew.inference.EngineProvider] — none fakeable on the JVM without Robolectric/Mockito,
 * neither of which this module depends on (see CLAUDE.md's build-dependency list) — which is
 * exactly why [AgentRepoParseTest] only ever exercised the static [AgentRepoRunner.parseFileBlocks]
 * before this lane. `commit()`'s own body (resolve host/token, delegate to these companion
 * functions) is now a two-line, effectively-untestable-but-trivial wrapper; the logic worth
 * testing is fully covered here.
 */
class AgentRepoRunnerCommitTest {

    /** Records the requests and replays canned responses, so no network is touched — same idiom
     *  [GitEditTest]'s own `FakeTransport` uses. */
    private class FakeTransport(private vararg val replies: GitTransport.Resp) : GitTransport() {
        val seen = mutableListOf<GitRequest>()
        private var i = 0
        override suspend fun execute(req: GitRequest): GitTransport.Resp {
            seen += req
            return replies[i++]
        }
    }

    private val host = GitHost(
        id = "h1", displayName = "mine", kind = GitHostKind.GITHUB, baseUrl = "",
        owner = "me", repo = "proj", branch = "work", authorName = "Me", authorEmail = "me@example.com",
    )

    // ---- squashedCommit / perFileCommit still work after moving to the companion object -------

    @Test fun `squashedCommit walks get-ref, get-commit, create-tree, create-commit, update-ref and returns the new sha`() = runTest {
        val transport = FakeTransport(
            GitTransport.Resp(200, JSONObject().put("object", JSONObject().put("sha", "head1")).toString()),
            GitTransport.Resp(200, JSONObject().put("tree", JSONObject().put("sha", "tree1")).toString()),
            GitTransport.Resp(200, JSONObject().put("sha", "tree2").toString()),
            GitTransport.Resp(200, JSONObject().put("sha", "commit1").toString()),
            GitTransport.Resp(200, "{}"),
        )
        val changeSet = ChangeSet(listOf(FileChange(path = "a.txt", oldText = "old", newText = "new")))
        val ids = AgentRepoRunner.squashedCommit(transport, host, "tok", changeSet, "msg").getOrThrow()
        assertEquals(listOf("commit1"), ids)
        assertEquals(5, transport.seen.size)
        assertEquals("PATCH", transport.seen.last().method)
    }

    @Test fun `perFileCommit issues one Contents-API commit per changed file`() = runTest {
        val transport = FakeTransport(
            GitTransport.Resp(200, JSONObject().put("commit", JSONObject().put("sha", "c1")).toString()),
        )
        val changeSet = ChangeSet(listOf(FileChange(path = "new.txt", oldText = "", newText = "hello")))
        val ids = AgentRepoRunner.perFileCommit(transport, host, "tok", changeSet, "msg").getOrThrow()
        assertEquals(listOf("c1"), ids)
        assertEquals("PUT", transport.seen.single().method)
    }

    // ---- recordCommitAnchors (the actual lane D deliverable) -----------------------------------

    private class FakeMessageNodeDao : MessageNodeDao {
        val inserted = mutableListOf<dev.fonebrew.data.entity.MessageNodeEntity>()
        private val rows = MutableStateFlow<List<dev.fonebrew.data.entity.MessageNodeEntity>>(emptyList())
        override suspend fun insert(node: dev.fonebrew.data.entity.MessageNodeEntity) {
            inserted += node
            rows.value = rows.value + node
        }
        override suspend fun getById(id: String): dev.fonebrew.data.entity.MessageNodeEntity? = rows.value.firstOrNull { it.id == id }
        override fun observeAll(): Flow<List<dev.fonebrew.data.entity.MessageNodeEntity>> = rows
        override fun observeChildren(parentId: String): Flow<List<dev.fonebrew.data.entity.MessageNodeEntity>> =
            MutableStateFlow(rows.value.filter { it.parentId == parentId })
        override suspend fun pathToRoot(leafId: String): List<dev.fonebrew.data.entity.MessageNodeEntity> = emptyList()
    }

    private class FakeTokenCountDao : TokenCountDao {
        override suspend fun upsert(count: TokenCountEntity) {}
        override suspend fun forNode(nodeId: String): List<TokenCountEntity> = emptyList()
    }

    private class FakeEmbeddingDao : EmbeddingDao {
        override suspend fun upsert(embedding: MessageEmbeddingEntity) {}
        override suspend fun forNode(nodeId: String): MessageEmbeddingEntity? = null
        override suspend fun count(): Int = 0
    }

    @Test fun `recordCommitAnchors is a no-op when no tree repository is wired — the exact pre-lane-D behavior`() = runTest {
        // Must not throw even though ids/host are real — treeRepository=null is the documented
        // "byte-identical to before this lane" default.
        AgentRepoRunner.recordCommitAnchors(null, host, listOf("sha1", "sha2"), "msg", { 1L }, { "x" })
    }

    @Test fun `recordCommitAnchors mints one CommitAnchor node per returned sha, carrying the derived repoRef`() = runTest {
        val nodeDao = FakeMessageNodeDao()
        val repo = MessageTreeRepository(nodeDao, FakeTokenCountDao(), FakeEmbeddingDao())
        var nextId = 0

        AgentRepoRunner.recordCommitAnchors(
            repo, host, listOf("sha-a", "sha-b"), "Agent: fix crash",
            clock = { 500L }, idGen = { "anchor-${nextId++}" },
        )

        assertEquals(2, nodeDao.inserted.size)
        val first = repo.node("anchor-0")!!
        assertEquals("sha-a", first.metadata[CommitAnchor.SHA_KEY])
        assertEquals("me/proj@work", first.metadata[CommitAnchor.REPO_KEY])
        assertEquals(500L, first.createdAt)
        assertNull(first.parentId) // a fresh root — no existing conversation node to attach to here
        val second = repo.node("anchor-1")!!
        assertEquals("sha-b", second.metadata[CommitAnchor.SHA_KEY])
        // Two distinct shas from one commit() call (the per-file fallback case) each get their
        // own node — never collapsed, never dropped.
        assertTrue(first.id != second.id)
    }

    @Test fun `recordCommitAnchors mints nothing for an empty id list`() = runTest {
        val nodeDao = FakeMessageNodeDao()
        val repo = MessageTreeRepository(nodeDao, FakeTokenCountDao(), FakeEmbeddingDao())
        AgentRepoRunner.recordCommitAnchors(repo, host, emptyList(), "msg", { 1L }, { "x" })
        assertTrue(nodeDao.inserted.isEmpty())
    }
}
