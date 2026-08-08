package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.aarso.data.entity.GhostBranchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GhostBranchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ghost: GhostBranchEntity)

    @Query("SELECT * FROM ghost_branches WHERE branchTipMsgId = :msgId")
    suspend fun getByTip(msgId: String): GhostBranchEntity?

    @Query("SELECT * FROM ghost_branches")
    fun observeAll(): Flow<List<GhostBranchEntity>>
}
