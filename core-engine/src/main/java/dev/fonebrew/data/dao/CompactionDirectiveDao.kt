package dev.fonebrew.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.fonebrew.data.entity.CompactionDirectiveEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CompactionDirectiveDao {

    /** Upsert: at most one directive per message. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(directive: CompactionDirectiveEntity)

    @Delete
    suspend fun delete(directive: CompactionDirectiveEntity)

    @Query("SELECT * FROM compaction_directives WHERE msgId = :msgId")
    suspend fun getByMessage(msgId: String): CompactionDirectiveEntity?

    @Query("SELECT * FROM compaction_directives")
    fun observeAll(): Flow<List<CompactionDirectiveEntity>>
}
