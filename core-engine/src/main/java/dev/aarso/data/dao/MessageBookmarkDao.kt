package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.aarso.data.entity.MessageBookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageBookmarkDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(bookmark: MessageBookmarkEntity)

    @Delete
    suspend fun delete(bookmark: MessageBookmarkEntity)

    @Query("SELECT * FROM message_bookmarks WHERE msgId = :msgId ORDER BY at DESC")
    suspend fun forMessage(msgId: String): List<MessageBookmarkEntity>

    @Query("SELECT * FROM message_bookmarks")
    fun observeAll(): Flow<List<MessageBookmarkEntity>>
}
