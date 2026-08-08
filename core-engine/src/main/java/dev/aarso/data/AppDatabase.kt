package dev.aarso.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.aarso.data.dao.BufferJournalDao
import dev.aarso.data.dao.BufferRegistryDao
import dev.aarso.data.dao.CompactionDirectiveDao
import dev.aarso.data.dao.EmbeddingDao
import dev.aarso.data.dao.FormStateDao
import dev.aarso.data.dao.GhostBranchDao
import dev.aarso.data.dao.LedgerDao
import dev.aarso.data.dao.MessageBookmarkDao
import dev.aarso.data.dao.MessageNodeDao
import dev.aarso.data.dao.ReceiptDao
import dev.aarso.data.dao.RecoverySnapshotDao
import dev.aarso.data.dao.TaskDao
import dev.aarso.data.dao.TokenCountDao
import dev.aarso.data.dao.VerdictDao
import dev.aarso.data.dao.VersionDao
import dev.aarso.data.dao.WatchDao
import dev.aarso.data.entity.BufferJournalEntryEntity
import dev.aarso.data.entity.BufferRegistryEntity
import dev.aarso.data.entity.CompactionDirectiveEntity
import dev.aarso.data.entity.FormStateEntity
import dev.aarso.data.entity.GhostBranchEntity
import dev.aarso.data.entity.LedgerEntryEntity
import dev.aarso.data.entity.MessageBookmarkEntity
import dev.aarso.data.entity.MessageEmbeddingEntity
import dev.aarso.data.entity.MessageNodeEntity
import dev.aarso.data.entity.ReceiptEntity
import dev.aarso.data.entity.RecoverySnapshotEntity
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
        ReceiptEntity::class,
        BufferJournalEntryEntity::class,
        RecoverySnapshotEntity::class,
        BufferRegistryEntity::class,
        VerdictEntity::class,
        MessageBookmarkEntity::class,
        VersionEntity::class,
        CompactionDirectiveEntity::class,
        GhostBranchEntity::class,
        FormStateEntity::class,
    ],
    version = 8,
    // Schema export is off in Phase 0 (no migrations yet). Turn on with a
    // room.schemaLocation KSP arg once the schema needs to be versioned. v4->v5
    // (loop-surface ledger columns, CORE_PHASES.md P3) rides the same
    // fallbackToDestructiveMigration() every prior bump has — no real Migration object exists
    // anywhere in this codebase yet (none of the JVM tests can exercise one: Room's migration
    // testing needs Robolectric or an instrumented test, neither of which this gate has).
    // v8 is a merge of two independent v5-based lines that both bumped in parallel: v5->v6->v7
    // (the fonebrew handoff-pack branch: v6 added WP-2's ReceiptStore table, v7 added WP-3's
    // Workspace Kernel journal/snapshot/buffer-registry tables, docs/ratified/
    // WORKSPACE_KERNEL_SPEC.md FB-RAT-WS-003/005) and v5->v6 (main: Session 0 + PC-A's
    // Conversation Instrument tables — verdicts/bookmarks/versions/compaction directives/ghost
    // branches/form states). Neither line touches a table the other added, so the merge is purely
    // additive; v8 is one past the higher of the two (v7), not a renumbering of either line.
    // dev.aarso.domain.contracts.MigrationRunner is real, tested scaffolding for whichever future
    // bump needs an actual Migration — this bump still doesn't, same as v1->v7.
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
    abstract fun bufferJournalDao(): BufferJournalDao
    abstract fun recoverySnapshotDao(): RecoverySnapshotDao
    abstract fun bufferRegistryDao(): BufferRegistryDao
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
