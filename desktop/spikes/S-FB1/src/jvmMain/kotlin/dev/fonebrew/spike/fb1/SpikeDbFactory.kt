package dev.fonebrew.spike.fb1

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * The "context-free builder": no `android.content.Context`, just a file path. This is the
 * seam `AppContainer` (which today passes a Context) would call on desktop.
 * `fallbackToDestructiveMigration(dropAllTables = true)` is the KMP spelling of what core calls
 * as `fallbackToDestructiveMigration()` (the no-arg form is Android-only / deprecated in KMP).
 */
object SpikeDbFactory {
    fun openFile(path: String): SpikeDb =
        Room.databaseBuilder<SpikeDb>(name = path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    fun openExportedFile(path: String): SpikeDbExported =
        Room.databaseBuilder<SpikeDbExported>(name = path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
}
