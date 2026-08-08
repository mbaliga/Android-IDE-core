package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.aarso.data.entity.FormStateEntity

@Dao
interface FormStateDao {

    /** Upsert: resubmitting a rewound form overwrites its prior answers. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: FormStateEntity)

    @Query("SELECT * FROM form_states WHERE msgId = :msgId")
    suspend fun getByMessage(msgId: String): FormStateEntity?
}
