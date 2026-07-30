package dev.aarso.data

import dev.aarso.data.dao.WatchDao
import dev.aarso.data.entity.WatchedItemEntity
import dev.aarso.domain.watch.WatchKind
import dev.aarso.domain.watch.WatchSeeds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [WatchDao] — same rationale as [TaskStoreTest]'s FakeTaskDao: Room needs a real
 *  SQLite binding this JVM gate doesn't have, so [WatchStore]'s logic is exercised against the
 *  DAO interface instead. */
private class FakeWatchDao : WatchDao {
    private val rows = MutableStateFlow<List<WatchedItemEntity>>(emptyList())

    override suspend fun insert(item: WatchedItemEntity) {
        check(rows.value.none { it.id == item.id }) { "duplicate id ${item.id}" }
        rows.value = rows.value + item
    }

    override suspend fun update(item: WatchedItemEntity) {
        rows.value = rows.value.map { if (it.id == item.id) item else it }
    }

    override suspend fun delete(item: WatchedItemEntity) {
        rows.value = rows.value.filterNot { it.id == item.id }
    }

    override fun observeAll(): Flow<List<WatchedItemEntity>> =
        rows.map { list -> list.sortedWith(compareBy({ it.dueAt == null }, { it.dueAt })) }
}

class WatchStoreTest {

    private fun store() = WatchStore(FakeWatchDao())

    @Test
    fun `create round-trips through items`() = runTest {
        val store = store()
        val item = store.create("upload key", WatchKind.EXPIRY, dueAt = 100L, now = 1L)
        assertEquals(listOf(item), store.items.first())
        assertEquals(WatchKind.EXPIRY, item.kind)
        assertEquals(1L, item.createdAt)
    }

    @Test
    fun `items sort by dueAt ascending with nulls last`() = runTest {
        val store = store()
        val noDue = store.create("no due date", WatchKind.STATUS, dueAt = null, now = 1L)
        val later = store.create("later", WatchKind.RENEWAL, dueAt = 200L, now = 2L)
        val sooner = store.create("sooner", WatchKind.EXPIRY, dueAt = 100L, now = 3L)
        assertEquals(listOf(sooner, later, noDue), store.items.first())
    }

    @Test
    fun `seeds never insert automatically — only createFromSeed inserts, and only on call`() = runTest {
        val store = store()
        assertTrue("a fresh store must start empty, never pre-seeded", store.items.first().isEmpty())

        val seed = WatchSeeds.TEMPLATES.first()
        val inserted = store.createFromSeed(seed, now = 5L)

        assertEquals(1, store.items.first().size)
        assertEquals(seed.label, inserted.label)
        assertEquals(seed.kind, inserted.kind)
        assertEquals(seed.amountHint, inserted.amountText)
        assertEquals(seed.note, inserted.note)
    }

    @Test
    fun `every seed template inserts independently on tap`() = runTest {
        val store = store()
        WatchSeeds.TEMPLATES.forEach { store.createFromSeed(it, now = 1L) }
        assertEquals(WatchSeeds.TEMPLATES.size, store.items.first().size)
    }

    @Test
    fun `edit updates label and amountText, stamping updatedAt`() = runTest {
        val store = store()
        val item = store.create("Garmin merchant", WatchKind.RENEWAL, now = 1L)
        store.edit(item, label = "Garmin Connect IQ merchant", amountText = "check current figure", now = 9L)
        val edited = store.items.first().single()
        assertEquals("Garmin Connect IQ merchant", edited.label)
        assertEquals("check current figure", edited.amountText)
        assertEquals(9L, edited.updatedAt)
    }

    @Test
    fun `snooze sets snoozedUntil`() = runTest {
        val store = store()
        val item = store.create("cert expiry", WatchKind.EXPIRY, now = 1L)
        store.snooze(item, until = 500L, now = 2L)
        assertEquals(500L, store.items.first().single().snoozedUntil)
    }

    @Test
    fun `delete removes the item`() = runTest {
        val store = store()
        val item = store.create("temp", WatchKind.STATUS, now = 1L)
        store.delete(item)
        assertNull(store.items.first().firstOrNull())
    }
}
