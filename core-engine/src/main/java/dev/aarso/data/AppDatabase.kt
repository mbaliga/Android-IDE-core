package dev.aarso.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.aarso.data.dao.EmbeddingDao
import dev.aarso.data.dao.LedgerDao
import dev.aarso.data.dao.MessageNodeDao
import dev.aarso.data.dao.ReceiptDao
import dev.aarso.data.dao.TaskDao
import dev.aarso.data.dao.TokenCountDao
import dev.aarso.data.dao.WatchDao
import dev.aarso.data.entity.LedgerEntryEntity
import dev.aarso.data.entity.MessageEmbeddingEntity
import dev.aarso.data.entity.MessageNodeEntity
import dev.aarso.data.entity.ReceiptEntity
import dev.aarso.data.entity.TaskEntity
import dev.aarso.data.entity.TokenCountEntity
import dev.aarso.data.entity.WatchedItemEntity

@Database(
    entities = [
        MessageNodeEntity::class,
        TokenCountEntity::class,
        MessageEmbeddingEntity::class,
        LedgerEntryEntity::class,
        TaskEntity::class,
        WatchedItemEntity::class,
        ReceiptEntity::class,
    ],
    version = 6,
    // Schema export is off in Phase 0 (no migrations yet). Turn on with a
    // room.schemaLocation KSP arg once the schema needs to be versioned. v5->v6 (the WP-2
    // append-only receipt store, docs/ratified/COMMON_CONVENTIONS.md FB-RAT-COM-006) rides the
    // same fallbackToDestructiveMigration() every prior bump has — no real Migration object
    // exists anywhere in this codebase yet (none of the JVM tests can exercise one: Room's
    // migration testing needs Robolectric or an instrumented test, neither of which this gate
    // has). dev.aarso.domain.contracts.MigrationRunner is real, tested scaffolding for whichever
    // future bump needs an actual Migration — this bump still doesn't, same as v1->v5.
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageNodeDao(): MessageNodeDao
    abstract fun tokenCountDao(): TokenCountDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun taskDao(): TaskDao
    abstract fun watchDao(): WatchDao
    abstract fun receiptDao(): ReceiptDao

    companion object {
        const val NAME = "aarso.db"
    }
}
