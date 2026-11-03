package dev.fonebrew.spike.fb2

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import dev.fonebrew.data.dao.MessageNodeDao
import dev.fonebrew.data.entity.MessageNodeEntity

/** The Room half of the coexistence test: core's real message-tree entity + DAO, nothing else. */
@Database(entities = [MessageNodeEntity::class], version = 1, exportSchema = false)
@ConstructedBy(CoexistDbConstructor::class)
abstract class CoexistDb : RoomDatabase() {
    abstract fun messageNodeDao(): MessageNodeDao
}

@Suppress("KotlinNoActualForExpect")
expect object CoexistDbConstructor : RoomDatabaseConstructor<CoexistDb> {
    override fun initialize(): CoexistDb
}
