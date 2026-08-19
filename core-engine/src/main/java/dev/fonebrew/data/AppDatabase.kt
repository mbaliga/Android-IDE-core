package dev.fonebrew.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.fonebrew.data.dao.BufferJournalDao
import dev.fonebrew.data.dao.BufferRegistryDao
import dev.fonebrew.data.dao.CompactionDirectiveDao
import dev.fonebrew.data.dao.DelegationEventDao
import dev.fonebrew.data.dao.EmbeddingDao
import dev.fonebrew.data.dao.FormStateDao
import dev.fonebrew.data.dao.GhostBranchDao
import dev.fonebrew.data.dao.LedgerDao
import dev.fonebrew.data.dao.MessageBookmarkDao
import dev.fonebrew.data.dao.MessageNodeDao
import dev.fonebrew.data.dao.ReceiptDao
import dev.fonebrew.data.dao.RecoverySnapshotDao
import dev.fonebrew.data.dao.TaskDao
import dev.fonebrew.data.dao.ThreadMarkerDao
import dev.fonebrew.data.dao.TokenCountDao
import dev.fonebrew.data.dao.VerdictDao
import dev.fonebrew.data.dao.VersionDao
import dev.fonebrew.data.dao.WatchDao
import dev.fonebrew.data.entity.BufferJournalEntryEntity
import dev.fonebrew.data.entity.BufferRegistryEntity
import dev.fonebrew.data.entity.CompactionDirectiveEntity
import dev.fonebrew.data.entity.DelegationEventEntity
import dev.fonebrew.data.entity.FormStateEntity
import dev.fonebrew.data.entity.GhostBranchEntity
import dev.fonebrew.data.entity.LedgerEntryEntity
import dev.fonebrew.data.entity.MessageBookmarkEntity
import dev.fonebrew.data.entity.MessageEmbeddingEntity
import dev.fonebrew.data.entity.MessageNodeEntity
import dev.fonebrew.data.entity.ReceiptEntity
import dev.fonebrew.data.entity.RecoverySnapshotEntity
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.data.entity.ThreadMarkerEntity
import dev.fonebrew.data.entity.TokenCountEntity
import dev.fonebrew.data.entity.VerdictEntity
import dev.fonebrew.data.entity.VersionEntity
import dev.fonebrew.data.entity.WatchedItemEntity

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
        ThreadMarkerEntity::class,
        DelegationEventEntity::class,
    ],
    version = 9,
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
    // v8->v9 (THREAD_TOPOLOGY_PLAN.md WP1): two new tables, thread_markers/delegation_events —
    // dev.fonebrew.domain.thread.ThreadMarker/DelegationEvent, fronted by ThreadMarkerStore/
    // DelegationStore (CurationStore's own shape). Same destructive-migration pattern as every
    // prior bump; both tables are brand new (nothing to migrate data out of).
    // dev.fonebrew.domain.contracts.MigrationRunner is real, tested scaffolding for whichever future
    // bump needs an actual Migration — this bump still doesn't, same as v1->v8.
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
    abstract fun threadMarkerDao(): ThreadMarkerDao
    abstract fun delegationEventDao(): DelegationEventDao

    companion object {
        const val NAME = "aarso.db"
    }
}
