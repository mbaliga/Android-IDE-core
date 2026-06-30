package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.aarso.data.entity.MessageEmbeddingEntity

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: MessageEmbeddingEntity)

    @Query("SELECT * FROM message_embeddings WHERE nodeId = :nodeId")
    suspend fun forNode(nodeId: String): MessageEmbeddingEntity?

    /** Cold-start sanity: how many messages we've embedded so far. */
    @Query("SELECT COUNT(*) FROM message_embeddings")
    suspend fun count(): Int
}
