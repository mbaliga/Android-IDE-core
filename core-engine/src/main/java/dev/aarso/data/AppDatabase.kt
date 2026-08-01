package dev.aarso.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.aarso.data.dao.CompactionDirectiveDao
import dev.aarso.data.dao.EmbeddingDao
import dev.aarso.data.dao.FormStateDao
import dev.aarso.data.dao.GhostBranchDao
import dev.aarso.data.dao.LedgerDao
import dev.aarso.data.dao.MessageBookmarkDao
import dev.aarso.data.dao.MessageNodeDao
import dev.aarso.data.dao.TaskDao
import dev.aarso.data.dao.TokenCountDao
import dev.aarso.data.dao.VerdictDao
import dev.aarso.data.dao.VersionDao
import dev.aarso.data.dao.WatchDao
import dev.aarso.data.entity.CompactionDirectiveEntity
import dev.aarso.data.entity.FormStateEntity
import dev.aarso.data.entity.GhostBranchEntity
import dev.aarso.data.entity.LedgerEntryEntity
import dev.aarso.data.entity.MessageBookmarkEntity
import dev.aarso.data.entity.MessageEmbeddingEntity
import dev.aarso.data.entity.MessageNodeEntity
import dev.aarso.data.entity.TaskEntity
import dev.aarso.data.entity.TokenCountEntity
import dev.aarso.data.entity.VerdictEntity
import dev.aarso.data.entity.VersionEntity
import dev.aarso.data.entity.WatchedItemEntity

@Database(
    entities = [
        MessageNodeEntity::class,
        TokenCountEntity::class,
        MessageEmbeddingEntity::class,
        LedgerEntryEntity::class,
        TaskEntity::class,
        WatchedItemEntity::class,
        VerdictEntity::class,
        MessageBookmarkEntity::class,
        VersionEntity::class,
        CompactionDirectiveEntity::class,
        GhostBranchEntity::class,
        FormStateEntity::class,
    ],
    version = 6,
    // Schema export is off in Phase 0 (no migrations yet). Turn on with a
    // room.schemaLocation KSP arg once the schema needs to be versioned. v4->v5
    // (loop-surface ledger columns, CORE_PHASES.md P3) rides the same
    // fallbackToDestructiveMigration() every prior bump has — no real Migration object exists
    // anywhere in this codebase yet (none of the JVM tests can exercise one: Room's migration
    // testing needs Robolectric or an instrumented test, neither of which this gate has).
    // v5->v6 adds the Conversation Instrument tables (verdicts/bookmarks/versions/compaction
    // directives/ghost branches/form states) — same destructive-migration posture, additive
    // new tables only, no existing table touched.
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
    abstract fun verdictDao(): VerdictDao
    abstract fun messageBookmarkDao(): MessageBookmarkDao
    abstract fun versionDao(): VersionDao
    abstract fun compactionDirectiveDao(): CompactionDirectiveDao
    abstract fun ghostBranchDao(): GhostBranchDao
    abstract fun formStateDao(): FormStateDao

    companion object {
        const val NAME = "aarso.db"
    }
}
