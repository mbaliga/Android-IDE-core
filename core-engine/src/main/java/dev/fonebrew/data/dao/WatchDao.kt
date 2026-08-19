package dev.fonebrew.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.fonebrew.data.entity.WatchedItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: WatchedItemEntity)

    @Update
    suspend fun update(item: WatchedItemEntity)

    @Delete
    suspend fun delete(item: WatchedItemEntity)

    /** Sorted by [dev.fonebrew.data.entity.WatchedItemEntity.dueAt] ascending, nulls last
     *  (CORE_PHASES.md P2 "Watch: Sorted by dueAt, nulls last"). */
    @Query("SELECT * FROM watched_items ORDER BY (dueAt IS NULL) ASC, dueAt ASC")
    fun observeAll(): Flow<List<WatchedItemEntity>>
}
