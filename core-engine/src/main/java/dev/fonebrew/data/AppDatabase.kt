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

@Database(
    entities = [
        MessageNodeEntity::class,
        TokenCountEntity::class,
        MessageEmbeddingEntity::class,
        LedgerEntryEntity::class,
        TaskEntity::class,
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
    version = 11,
    // Version history. Schema export is ON (room.schemaLocation in core-engine/build.gradle.kts)
    // and every exported version is committed under core-engine/schemas/. Only v10 and v11 have
    // exports (v10's was re-derived from the pre-bump source tree, see below); v1-v9 predate
    // export and have NO migration path — see the note after the v10->v11 entry. v4->v5
    // (loop-surface ledger columns, CORE_PHASES.md P3) rode fallbackToDestructiveMigration(),
    // as did every bump up to v10; that fallback is gone as of v11.
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
    // v9->v10 (launch-line reunification): DROPS the Watchlist table — the free-tier Watch tab
    // was pulled on the launch line (owner call, moving to Studio); the dev line had carried the
    // base-era table forward untouched. Same destructive-migration ride as every prior bump.
    //
    // v10->v11 (data-loss fix — the #1 blocking bug across two audits): no entity/column/table
    // changed. This bump exists solely to retire fallbackToDestructiveMigration() (see the
    // Room.databaseBuilder call site in AppContainer.kt for the policy this establishes going
    // forward) by proving the real migration path end-to-end. core-engine/schemas/ holds BOTH
    // 10.json and 11.json; they differ only in the "version" number (identityHash, entities and
    // setupQueries are identical — AppDatabaseMigrationTest pins that), which is what makes
    // MIGRATION_10_11 (data/Migrations.kt) a correct no-op. 10.json was produced by running the
    // Room schema export on the source tree as it stood before the v10->v11 commit, not copied
    // from 11.json. The migration test builds its database from 10.json and seeds every table.
    //
    // Installs on schema 1-9 have no Migration and, with the destructive fallback gone, fail to
    // open with Room's "A migration from N to 11 was required but not found". That is
    // deliberate (never silently wipe user data); it is an owner decision how to treat them.
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageNodeDao(): MessageNodeDao
    abstract fun tokenCountDao(): TokenCountDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun taskDao(): TaskDao
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
