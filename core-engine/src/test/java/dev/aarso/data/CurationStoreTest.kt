package dev.aarso.data

import dev.aarso.data.dao.CompactionDirectiveDao
import dev.aarso.data.dao.FormStateDao
import dev.aarso.data.dao.GhostBranchDao
import dev.aarso.data.dao.MessageBookmarkDao
import dev.aarso.data.dao.VerdictDao
import dev.aarso.data.dao.VersionDao
import dev.aarso.data.entity.CompactionDirectiveEntity
import dev.aarso.data.entity.FormStateEntity
import dev.aarso.data.entity.GhostBranchEntity
import dev.aarso.data.entity.MessageBookmarkEntity
import dev.aarso.data.entity.VerdictEntity
import dev.aarso.data.entity.VersionEntity
import dev.aarso.domain.curation.BookmarkKind
import dev.aarso.domain.curation.Fidelity
import dev.aarso.domain.curation.GhostReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory fakes — same rationale as [WatchStoreTest]'s FakeWatchDao: Room needs a real SQLite binding this JVM gate doesn't have, so [CurationStore]'s logic is exercised against the DAO interfaces instead. */
private class FakeVerdictDao : VerdictDao {
    private val rows = MutableStateFlow<Map<String, VerdictEntity>>(emptyMap())
    override suspend fun upsert(verdict: VerdictEntity) { rows.value = rows.value + (verdict.msgId to verdict) }
    override suspend fun delete(verdict: VerdictEntity) { rows.value = rows.value - verdict.msgId }
    override suspend fun getByMessage(msgId: String): VerdictEntity? = rows.value[msgId]
    override fun observeAll(): Flow<List<VerdictEntity>> = rows.map { it.values.toList() }
}

private class FakeMessageBookmarkDao : MessageBookmarkDao {
    private val rows = MutableStateFlow<List<MessageBookmarkEntity>>(emptyList())
    override suspend fun insert(bookmark: MessageBookmarkEntity) {
        check(rows.value.none { it.id == bookmark.id }) { "duplicate id ${bookmark.id}" }
        rows.value = rows.value + bookmark
    }
    override suspend fun delete(bookmark: MessageBookmarkEntity) { rows.value = rows.value.filterNot { it.id == bookmark.id } }
    override suspend fun forMessage(msgId: String): List<MessageBookmarkEntity> =
        rows.value.filter { it.msgId == msgId }.sortedByDescending { it.at }
    override fun observeAll(): Flow<List<MessageBookmarkEntity>> = rows
}

private class FakeVersionDao : VersionDao {
    private val rows = MutableStateFlow<List<VersionEntity>>(emptyList())
    override suspend fun insert(version: VersionEntity) {
        check(rows.value.none { it.id == version.id }) { "duplicate id ${version.id}" }
        rows.value = rows.value + version
    }
    override suspend fun update(version: VersionEntity) { rows.value = rows.value.map { if (it.id == version.id) version else it } }
    override suspend fun delete(version: VersionEntity) { rows.value = rows.value.filterNot { it.id == version.id } }
    override suspend fun atTip(msgId: String): VersionEntity? = rows.value.lastOrNull { it.branchTipMsgId == msgId }
    override fun observeAll(): Flow<List<VersionEntity>> = rows
}

private class FakeCompactionDirectiveDao : CompactionDirectiveDao {
    private val rows = MutableStateFlow<Map<String, CompactionDirectiveEntity>>(emptyMap())
    override suspend fun upsert(directive: CompactionDirectiveEntity) { rows.value = rows.value + (directive.msgId to directive) }
    override suspend fun delete(directive: CompactionDirectiveEntity) { rows.value = rows.value - directive.msgId }
    override suspend fun getByMessage(msgId: String): CompactionDirectiveEntity? = rows.value[msgId]
    override fun observeAll(): Flow<List<CompactionDirectiveEntity>> = rows.map { it.values.toList() }
}

private class FakeGhostBranchDao : GhostBranchDao {
    private val rows = MutableStateFlow<Map<String, GhostBranchEntity>>(emptyMap())
    override suspend fun upsert(ghost: GhostBranchEntity) { rows.value = rows.value + (ghost.branchTipMsgId to ghost) }
    override suspend fun getByTip(msgId: String): GhostBranchEntity? = rows.value[msgId]
    override fun observeAll(): Flow<List<GhostBranchEntity>> = rows.map { it.values.toList() }
}

private class FakeFormStateDao : FormStateDao {
    private val rows = MutableStateFlow<Map<String, FormStateEntity>>(emptyMap())
    override suspend fun upsert(state: FormStateEntity) { rows.value = rows.value + (state.msgId to state) }
    override suspend fun getByMessage(msgId: String): FormStateEntity? = rows.value[msgId]
}

class CurationStoreTest {

    private fun store() = CurationStore(
        FakeVerdictDao(), FakeMessageBookmarkDao(), FakeVersionDao(),
        FakeCompactionDirectiveDao(), FakeGhostBranchDao(), FakeFormStateDao(),
    )

    // ---- Verdicts --------------------------------------------------------------------------

    @Test fun `setVerdict then verdictFor round-trips`() = runTest {
        val store = store()
        store.setVerdict("m1", grade = 2, now = 10L)
        val v = store.verdictFor("m1")
        assertEquals(2, v?.grade)
        assertEquals(10L, v?.at)
    }

    @Test fun `re-rating a message replaces the prior verdict, never accumulates`() = runTest {
        val store = store()
        store.setVerdict("m1", grade = 1, now = 1L)
        store.setVerdict("m1", grade = -2, now = 2L)
        assertEquals(1, store.verdicts.first().size)
        assertEquals(-2, store.verdictFor("m1")?.grade)
    }

    @Test fun `clearVerdict removes the row`() = runTest {
        val store = store()
        store.setVerdict("m1", grade = 1, now = 1L)
        store.clearVerdict("m1")
        assertNull(store.verdictFor("m1"))
    }

    // ---- Bookmarks -----------------------------------------------------------------------------

    @Test fun `toggleMessageBookmark creates a whole-message pin on first call`() = runTest {
        val store = store()
        val bookmark = store.toggleMessageBookmark("m1", BookmarkKind.DECISION, now = 5L)
        assertEquals("m1", bookmark?.ref?.msgId)
        assertNull(bookmark?.ref?.blockIndex)
        assertEquals(BookmarkKind.DECISION, bookmark?.kind)
    }

    @Test fun `toggleMessageBookmark removes the pin on the second call`() = runTest {
        val store = store()
        store.toggleMessageBookmark("m1", now = 1L)
        val second = store.toggleMessageBookmark("m1", now = 2L)
        assertNull(second)
        assertTrue(store.bookmarksFor("m1").isEmpty())
    }

    @Test fun `a block bookmark and a whole-message bookmark on the same message coexist`() = runTest {
        val store = store()
        store.toggleMessageBookmark("m1", now = 1L)
        store.toggleBlockBookmark("m1", blockIndex = 0, now = 2L)
        assertEquals(2, store.bookmarksFor("m1").size)
    }

    @Test fun `toggling the same code block twice removes only that block's bookmark`() = runTest {
        val store = store()
        store.toggleMessageBookmark("m1", now = 1L)
        store.toggleBlockBookmark("m1", blockIndex = 0, now = 2L)
        store.toggleBlockBookmark("m1", blockIndex = 0, now = 3L)
        val remaining = store.bookmarksFor("m1")
        assertEquals(1, remaining.size)
        assertNull(remaining.single().ref.blockIndex)
    }

    // ---- Versions ------------------------------------------------------------------------------

    @Test fun `markVersion then versionAtTip round-trips`() = runTest {
        val store = store()
        store.markVersion("m1", name = "auth-flow v2", now = 7L)
        val v = store.versionAtTip("m1")
        assertEquals("auth-flow v2", v?.name)
    }

    @Test fun `renameVersion updates name and note in place`() = runTest {
        val store = store()
        val v = store.markVersion("m1", name = "draft", now = 1L)
        store.renameVersion(v, name = "final", note = "shipped")
        val updated = store.versionAtTip("m1")
        assertEquals("final", updated?.name)
        assertEquals("shipped", updated?.note)
    }

    // ---- Compaction directives -------------------------------------------------------------

    @Test fun `setDirective then directiveFor round-trips, and a second call replaces the first`() = runTest {
        val store = store()
        store.setDirective("m1", mustInclude = false, fidelity = Fidelity.F1)
        store.setDirective("m1", mustInclude = true, fidelity = Fidelity.F3)
        val d = store.directiveFor("m1")
        assertEquals(Fidelity.F3, d?.fidelity)
        assertTrue(d?.mustInclude == true)
        assertEquals(1, store.directives.first().size)
    }

    @Test fun `clearDirective removes the row so the default resolution applies again`() = runTest {
        val store = store()
        store.setDirective("m1", mustInclude = false, fidelity = Fidelity.F0)
        store.clearDirective("m1")
        assertNull(store.directiveFor("m1"))
    }

    // ---- Ghost branches ----------------------------------------------------------------------

    @Test fun `recordGhost then ghostFor round-trips`() = runTest {
        val store = store()
        store.recordGhost(dev.aarso.domain.curation.GhostBranch("leaf1", rewoundAt = 9L, reason = GhostReason.REWIND))
        val ghost = store.ghostFor("leaf1")
        assertEquals(GhostReason.REWIND, ghost?.reason)
    }

    // ---- Form state ------------------------------------------------------------------------------

    @Test fun `saveFormState then formStateFor round-trips, and resubmitting overwrites`() = runTest {
        val store = store()
        store.saveFormState("m1", schemaJson = """{"q":1}""", answersJson = """{"a":"x"}""")
        store.saveFormState("m1", schemaJson = """{"q":1}""", answersJson = """{"a":"y"}""")
        val state = store.formStateFor("m1")
        assertEquals("""{"a":"y"}""", state?.answersJson)
    }
}
