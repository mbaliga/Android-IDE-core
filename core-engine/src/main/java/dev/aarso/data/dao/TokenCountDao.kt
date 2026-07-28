package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.aarso.data.entity.TokenCountEntity

@Dao
interface TokenCountDao {

    /** Upsert: re-counting under the same tokenizer replaces the prior count. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(count: TokenCountEntity)

    @Query("SELECT * FROM token_counts WHERE nodeId = :nodeId")
    suspend fun forNode(nodeId: String): List<TokenCountEntity>
}
