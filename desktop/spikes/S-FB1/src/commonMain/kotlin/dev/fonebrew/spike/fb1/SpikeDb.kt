package dev.fonebrew.spike.fb1

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters

/**
 * Mirrors the shape of core's `AppDatabase` (`exportSchema = false`, `@TypeConverters`, DAO accessors),
 * trimmed to the append-only message tree plus a converter probe. `@ConstructedBy` is the Room-KMP
 * replacement for the reflective `*_Impl` lookup that non-Android targets cannot do.
 */
@Database(
    entities = [MessageNodeEntity::class, ConverterProbeEntity::class],
    version = 1,
    exportSchema = false, // exactly what core's AppDatabase declares today
)
@TypeConverters(Converters::class)
@ConstructedBy(SpikeDbConstructor::class)
abstract class SpikeDb : RoomDatabase() {
    abstract fun messageNodeDao(): MessageNodeDao
    abstract fun converterProbeDao(): ConverterProbeDao
}

// The compiler generates the `actual` per target (here: jvm).
@Suppress("KotlinNoActualForExpect")
expect object SpikeDbConstructor : RoomDatabaseConstructor<SpikeDb> {
    override fun initialize(): SpikeDb
}

/**
 * Same entities, but `exportSchema = true`: needed to learn whether schema export (which core
 * switched off in Phase 0 and the plan lists under S-FB1) works under KMP + the Room Gradle plugin.
 */
@Database(
    entities = [MessageNodeEntity::class, ConverterProbeEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@ConstructedBy(SpikeDbExportedConstructor::class)
abstract class SpikeDbExported : RoomDatabase() {
    abstract fun messageNodeDao(): MessageNodeDao
    abstract fun converterProbeDao(): ConverterProbeDao
}

@Suppress("KotlinNoActualForExpect")
expect object SpikeDbExportedConstructor : RoomDatabaseConstructor<SpikeDbExported> {
    override fun initialize(): SpikeDbExported
}
