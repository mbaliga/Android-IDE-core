package dev.fonebrew.spike.fb2

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

object CoexistFactory {
    fun openRoom(path: String): CoexistDb =
        Room.databaseBuilder<CoexistDb>(name = path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
}
