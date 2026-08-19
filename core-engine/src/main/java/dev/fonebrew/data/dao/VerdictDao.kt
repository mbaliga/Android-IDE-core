package dev.fonebrew.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.fonebrew.data.entity.VerdictEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VerdictDao {

    /** Upsert: one live verdict per message, so a re-rating replaces the prior row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(verdict: VerdictEntity)

    @Delete
    suspend fun delete(verdict: VerdictEntity)

    @Query("SELECT * FROM verdicts WHERE msgId = :msgId")
    suspend fun getByMessage(msgId: String): VerdictEntity?

    @Query("SELECT * FROM verdicts")
    fun observeAll(): Flow<List<VerdictEntity>>
}
