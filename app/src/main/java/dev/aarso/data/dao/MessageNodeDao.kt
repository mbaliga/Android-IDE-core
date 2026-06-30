package dev.aarso.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.aarso.data.entity.MessageNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageNodeDao {

    /** Append-only: inserts abort on id collision rather than overwriting. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(node: MessageNodeEntity)

    @Query("SELECT * FROM message_nodes WHERE id = :id")
    suspend fun getById(id: String): MessageNodeEntity?

    /**
     * The whole tree as a reactive stream. Phase 0 builds the in-memory
     * [dev.aarso.domain.tree.MessageTree] from this so that path /
     * branch logic has a single tested implementation. When trees grow large a
     * recursive-CTE ancestor query (see [pathToRoot]) becomes the hot path.
     */
    @Query("SELECT * FROM message_nodes ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<MessageNodeEntity>>

    @Query("SELECT * FROM message_nodes WHERE parentId = :parentId ORDER BY createdAt ASC")
    fun observeChildren(parentId: String): Flow<List<MessageNodeEntity>>

    /**
     * Efficient root→leaf path via a recursive CTE (leaf-first; reverse for
     * root-first). Not used by Phase 0's UI yet but provided so the scaling path
     * is ready and obvious.
     */
    @Query(
        """
        WITH RECURSIVE ancestors(id, parentId, role, content, modelId, createdAt, metadataJson) AS (
            SELECT id, parentId, role, content, modelId, createdAt, metadataJson
            FROM message_nodes WHERE id = :leafId
            UNION ALL
            SELECT n.id, n.parentId, n.role, n.content, n.modelId, n.createdAt, n.metadataJson
            FROM message_nodes n
            JOIN ancestors a ON n.id = a.parentId
        )
        SELECT * FROM ancestors
        """,
    )
    suspend fun pathToRoot(leafId: String): List<MessageNodeEntity>
}
