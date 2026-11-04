package dev.fonebrew.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.fonebrew.data.entity.ThreadMarkerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadMarkerDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(marker: ThreadMarkerEntity)

    @Update
    suspend fun update(marker: ThreadMarkerEntity)

    @Delete
    suspend fun delete(marker: ThreadMarkerEntity)

    @Query("SELECT * FROM thread_markers WHERE id = :id")
    suspend fun getById(id: String): ThreadMarkerEntity?

    @Query("SELECT * FROM thread_markers WHERE rootId = :rootId ORDER BY at ASC")
    suspend fun forRoot(rootId: String): List<ThreadMarkerEntity>

    @Query("SELECT * FROM thread_markers WHERE anchorMsgId = :msgId ORDER BY at DESC")
    suspend fun forAnchor(msgId: String): List<ThreadMarkerEntity>

    @Query("SELECT * FROM thread_markers")
    fun observeAll(): Flow<List<ThreadMarkerEntity>>
}
