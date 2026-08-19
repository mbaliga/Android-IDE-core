package dev.fonebrew.data

import dev.fonebrew.data.dao.WatchDao
import dev.fonebrew.data.entity.WatchedItemEntity
import dev.fonebrew.domain.watch.WatchKind
import dev.fonebrew.domain.watch.WatchSeed
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The Watchlist substrate (CORE_PHASES.md §"Data models", P2): renewals/expiries/status the
 * user wants legibility on. Never asserts amounts as current fact, never acts on its own —
 * "Legibility, not automation."
 */
class WatchStore(private val dao: WatchDao) {

    val items: Flow<List<WatchedItemEntity>> = dao.observeAll()

    suspend fun create(
        label: String,
        kind: WatchKind,
        dueAt: Long? = null,
        note: String = "",
        amountText: String? = null,
        now: Long = System.currentTimeMillis(),
    ): WatchedItemEntity {
        val item = WatchedItemEntity(
            id = UUID.randomUUID().toString(),
            label = label,
            kind = kind,
            dueAt = dueAt,
            note = note,
            amountText = amountText,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(item)
        return item
    }

    /** Empty-state ghost rows insert on tap only — never inserted automatically. */
    suspend fun createFromSeed(seed: WatchSeed, now: Long = System.currentTimeMillis()): WatchedItemEntity =
        create(label = seed.label, kind = seed.kind, note = seed.note, amountText = seed.amountHint, now = now)

    suspend fun edit(
        item: WatchedItemEntity,
        label: String = item.label,
        dueAt: Long? = item.dueAt,
        note: String = item.note,
        amountText: String? = item.amountText,
        now: Long = System.currentTimeMillis(),
    ) {
        dao.update(
            item.copy(label = label, dueAt = dueAt, note = note, amountText = amountText, updatedAt = now),
        )
    }

    suspend fun snooze(item: WatchedItemEntity, until: Long, now: Long = System.currentTimeMillis()) {
        dao.update(item.copy(snoozedUntil = until, updatedAt = now))
    }

    suspend fun delete(item: WatchedItemEntity) = dao.delete(item)
}
