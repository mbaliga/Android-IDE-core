package dev.fonebrew.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.fonebrew.data.entity.DelegationEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DelegationEventDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: DelegationEventEntity)

    @Update
    suspend fun update(event: DelegationEventEntity)

    @Query("SELECT * FROM delegation_events WHERE id = :id")
    suspend fun getById(id: String): DelegationEventEntity?

    @Query("SELECT * FROM delegation_events WHERE rootId = :rootId ORDER BY at ASC")
    suspend fun forRoot(rootId: String): List<DelegationEventEntity>

    @Query("SELECT * FROM delegation_events")
    fun observeAll(): Flow<List<DelegationEventEntity>>
}
