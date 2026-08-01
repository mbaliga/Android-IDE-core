package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.aarso.data.entity.VersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VersionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: VersionEntity)

    @Update
    suspend fun update(version: VersionEntity)

    @Delete
    suspend fun delete(version: VersionEntity)

    @Query("SELECT * FROM versions WHERE branchTipMsgId = :msgId")
    suspend fun atTip(msgId: String): VersionEntity?

    @Query("SELECT * FROM versions")
    fun observeAll(): Flow<List<VersionEntity>>
}
