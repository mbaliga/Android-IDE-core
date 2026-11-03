package dev.fonebrew.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.fonebrew.data.entity.VersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VersionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(version: VersionEntity)

    @Update
    suspend fun update(version: VersionEntity)

    @Delete
    suspend fun delete(version: VersionEntity)

    // Fixed per adversarial review: newest-wins tie-break, matching the domain-layer
    // Versions.atTip()'s semantics — without ORDER BY, SQLite returns an arbitrary row when a
    // branch tip has been marked as a version more than once.
    @Query("SELECT * FROM versions WHERE branchTipMsgId = :msgId ORDER BY at DESC LIMIT 1")
    suspend fun atTip(msgId: String): VersionEntity?

    @Query("SELECT * FROM versions")
    fun observeAll(): Flow<List<VersionEntity>>
}
