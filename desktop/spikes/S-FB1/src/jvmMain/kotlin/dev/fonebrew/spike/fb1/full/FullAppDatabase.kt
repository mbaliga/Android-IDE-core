package dev.fonebrew.spike.fb1.full

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.fonebrew.data.Converters as CoreConverters
import dev.fonebrew.data.dao.*
import dev.fonebrew.data.entity.*
import kotlinx.coroutines.Dispatchers

/**
 * Part B (own package so the mapped core classes are not shadowed by Part A's same-named copies):
 * the real schema. Entity list, version and DAO accessors are copied from core's
 * `AppDatabase` (version 10 at the time of the spike; the porting plan's text says v8 -- the tree
 * has moved on). The entities, DAOs and Converters are NOT copied: they are mapped unchanged from
 * core-engine by the `mapCore*Sources` tasks in build.gradle.kts.
 *
 * Why this class is in jvmMain with a `factory`, not `@ConstructedBy` in commonMain: core's
 * Converters.kt needs org.json (JVM/Android only), so the @TypeConverters reference can only
 * resolve in a JVM source set. In the real LX-4 module that file would be rewritten for common.
 */
@Database(
    entities = [
        MessageNodeEntity::class, TokenCountEntity::class, MessageEmbeddingEntity::class,
        LedgerEntryEntity::class, TaskEntity::class, ReceiptEntity::class,
        BufferJournalEntryEntity::class, RecoverySnapshotEntity::class, BufferRegistryEntity::class,
        VerdictEntity::class, MessageBookmarkEntity::class, VersionEntity::class,
        CompactionDirectiveEntity::class, GhostBranchEntity::class, FormStateEntity::class,
        ThreadMarkerEntity::class, DelegationEventEntity::class,
    ],
    version = 10,
    exportSchema = false, // as core's AppDatabase
)
@TypeConverters(CoreConverters::class)
abstract class FullAppDatabase : RoomDatabase() {
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
        /** Context-free builder over a file path -- the desktop spelling of AppContainer's builder. */
        fun open(path: String): FullAppDatabase =
            Room.databaseBuilder<FullAppDatabase>(name = path, factory = { FullAppDatabase_Impl() })
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
