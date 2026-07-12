package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.aarso.data.entity.TaskEntity
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

    @Query("SELECT MAX(orderKey) FROM tasks")
    suspend fun maxOrderKey(): Double?
}
