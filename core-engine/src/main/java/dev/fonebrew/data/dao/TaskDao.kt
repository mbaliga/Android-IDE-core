package dev.fonebrew.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.fonebrew.data.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: TaskEntity)

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    /** Manual order first (the free List/To-do order), tie-broken by creation so a
     *  concurrent-insert dead heat is still stable. */
    @Query("SELECT * FROM tasks ORDER BY orderKey ASC, createdAt ASC")
    fun observeAll(): Flow<List<TaskEntity>>

    /** A one-shot, unordered snapshot of every row — [TaskStore.setDependsOn] needs the whole
     *  graph in hand (existence + cycle checks) before it writes, not a live [Flow]. */
    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT MAX(orderKey) FROM tasks")
    suspend fun maxOrderKey(): Double?
}
