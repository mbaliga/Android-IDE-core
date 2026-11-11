package dev.fonebrew.ui.chatharness

import dev.fonebrew.data.dao.CompactionDirectiveDao
import dev.fonebrew.data.dao.DelegationEventDao
import dev.fonebrew.data.dao.EmbeddingDao
import dev.fonebrew.data.dao.FormStateDao
import dev.fonebrew.data.dao.GhostBranchDao
import dev.fonebrew.data.dao.LedgerDao
import dev.fonebrew.data.dao.MessageBookmarkDao
import dev.fonebrew.data.dao.MessageNodeDao
import dev.fonebrew.data.dao.ReceiptDao
import dev.fonebrew.data.dao.TaskDao
import dev.fonebrew.data.dao.ThreadMarkerDao
import dev.fonebrew.data.dao.TokenCountDao
import dev.fonebrew.data.dao.VerdictDao
import dev.fonebrew.data.dao.VersionDao
import dev.fonebrew.data.entity.CompactionDirectiveEntity
import dev.fonebrew.data.entity.DelegationEventEntity
import dev.fonebrew.data.entity.FormStateEntity
import dev.fonebrew.data.entity.GhostBranchEntity
import dev.fonebrew.data.entity.LedgerEntryEntity
import dev.fonebrew.data.entity.MessageBookmarkEntity
import dev.fonebrew.data.entity.MessageEmbeddingEntity
import dev.fonebrew.data.entity.MessageNodeEntity
import dev.fonebrew.data.entity.ReceiptEntity
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.data.entity.ThreadMarkerEntity
import dev.fonebrew.data.entity.TokenCountEntity
import dev.fonebrew.data.entity.VerdictEntity
import dev.fonebrew.data.entity.VersionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory DAOs for the chat harness, in the repo's existing fake-DAO idiom (Room needs a real
 * SQLite binding this JVM gate doesn't have). Only [FakeNodeDao] and [FakeLedgerDao] carry
 * behaviour the chat tests assert on; the rest are the minimum a real [dev.fonebrew.ui.ChatViewModel]
 * needs to construct and to run its send/branch/restore paths.
 */
internal class FakeNodeDao : MessageNodeDao {
    val rows = MutableStateFlow<List<MessageNodeEntity>>(emptyList())

    /** Ids in the order they were inserted — the append-only log the tests compare against. */
    val insertOrder = mutableListOf<String>()

    /** Append-only, like the shipped `OnConflictStrategy.ABORT`: a repeated id is a loud failure. */
    override suspend fun insert(node: MessageNodeEntity) {
        check(rows.value.none { it.id == node.id }) { "append-only: id collision ${node.id}" }
        insertOrder += node.id
        rows.value = rows.value + node
    }

    override suspend fun getById(id: String): MessageNodeEntity? = rows.value.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<MessageNodeEntity>> = rows.map { list -> list.sortedBy { it.createdAt } }
    override fun observeChildren(parentId: String): Flow<List<MessageNodeEntity>> =
        rows.map { list -> list.filter { it.parentId == parentId }.sortedBy { it.createdAt } }

    /** A real ancestor walk standing in for the shipped recursive CTE; leaf-first like the CTE. */
    override suspend fun pathToRoot(leafId: String): List<MessageNodeEntity> {
        val byId = rows.value.associateBy { it.id }
        val out = mutableListOf<MessageNodeEntity>()
        var cur = byId[leafId]
        while (cur != null) {
            out += cur
            cur = cur.parentId?.let(byId::get)
        }
        return out
    }
}

internal class FakeTokenDao : TokenCountDao {
    val counts = mutableListOf<TokenCountEntity>()
    override suspend fun upsert(count: TokenCountEntity) {
        counts.removeAll { it.nodeId == count.nodeId && it.tokenizerId == count.tokenizerId }
        counts += count
    }
    override suspend fun forNode(nodeId: String): List<TokenCountEntity> = counts.filter { it.nodeId == nodeId }
}

internal class FakeEmbedDao : EmbeddingDao {
    val stored = mutableListOf<MessageEmbeddingEntity>()
    override suspend fun upsert(embedding: MessageEmbeddingEntity) { stored += embedding }
    override suspend fun forNode(nodeId: String): MessageEmbeddingEntity? = stored.lastOrNull { it.nodeId == nodeId }
    override suspend fun count(): Int = stored.size
}

internal class FakeLedgerDao : LedgerDao {
    val rows = MutableStateFlow<List<LedgerEntryEntity>>(emptyList())
    override suspend fun insert(e: LedgerEntryEntity) { rows.value = rows.value + e }
    override fun all(): Flow<List<LedgerEntryEntity>> = rows.map { list -> list.sortedBy { it.timestampMillis } }
}

internal class FakeTaskDao : TaskDao {
    private val rows = MutableStateFlow<List<TaskEntity>>(emptyList())
    override suspend fun insert(task: TaskEntity) { rows.value = rows.value + task }
    override suspend fun update(task: TaskEntity) { rows.value = rows.value.map { if (it.id == task.id) task else it } }
    override suspend fun delete(task: TaskEntity) { rows.value = rows.value.filterNot { it.id == task.id } }
    override suspend fun getById(id: String): TaskEntity? = rows.value.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<TaskEntity>> = rows
    override suspend fun getAll(): List<TaskEntity> = rows.value
    override suspend fun maxOrderKey(): Double? = rows.value.maxOfOrNull { it.orderKey }
}

internal class FakeVerdictDao : VerdictDao {
    private val rows = MutableStateFlow<Map<String, VerdictEntity>>(emptyMap())
    override suspend fun upsert(verdict: VerdictEntity) { rows.value = rows.value + (verdict.msgId to verdict) }
    override suspend fun delete(verdict: VerdictEntity) { rows.value = rows.value - verdict.msgId }
    override suspend fun getByMessage(msgId: String): VerdictEntity? = rows.value[msgId]
    override fun observeAll(): Flow<List<VerdictEntity>> = rows.map { it.values.toList() }
}

internal class FakeBookmarkDao : MessageBookmarkDao {
    private val rows = MutableStateFlow<List<MessageBookmarkEntity>>(emptyList())
    override suspend fun insert(bookmark: MessageBookmarkEntity) { rows.value = rows.value + bookmark }
    override suspend fun delete(bookmark: MessageBookmarkEntity) { rows.value = rows.value.filterNot { it.id == bookmark.id } }
    override suspend fun forMessage(msgId: String): List<MessageBookmarkEntity> =
        rows.value.filter { it.msgId == msgId }.sortedByDescending { it.at }
    override fun observeAll(): Flow<List<MessageBookmarkEntity>> = rows
}

internal class FakeVersionDao : VersionDao {
    private val rows = MutableStateFlow<List<VersionEntity>>(emptyList())
    override suspend fun insert(version: VersionEntity) { rows.value = rows.value + version }
    override suspend fun update(version: VersionEntity) { rows.value = rows.value.map { if (it.id == version.id) version else it } }
    override suspend fun delete(version: VersionEntity) { rows.value = rows.value.filterNot { it.id == version.id } }
    override suspend fun atTip(msgId: String): VersionEntity? = rows.value.filter { it.branchTipMsgId == msgId }.maxByOrNull { it.at }
    override fun observeAll(): Flow<List<VersionEntity>> = rows
}

internal class FakeDirectiveDao : CompactionDirectiveDao {
    private val rows = MutableStateFlow<Map<String, CompactionDirectiveEntity>>(emptyMap())
    override suspend fun upsert(directive: CompactionDirectiveEntity) { rows.value = rows.value + (directive.msgId to directive) }
    override suspend fun delete(directive: CompactionDirectiveEntity) { rows.value = rows.value - directive.msgId }
    override suspend fun getByMessage(msgId: String): CompactionDirectiveEntity? = rows.value[msgId]
    override fun observeAll(): Flow<List<CompactionDirectiveEntity>> = rows.map { it.values.toList() }
}

/** Records the ghosted branch tips, so a rewind test can assert the abandoned branch was annotated, not deleted. */
internal class FakeGhostDao : GhostBranchDao {
    val rows = MutableStateFlow<Map<String, GhostBranchEntity>>(emptyMap())
    override suspend fun upsert(ghost: GhostBranchEntity) { rows.value = rows.value + (ghost.branchTipMsgId to ghost) }
    override suspend fun getByTip(msgId: String): GhostBranchEntity? = rows.value[msgId]
    override fun observeAll(): Flow<List<GhostBranchEntity>> = rows.map { it.values.toList() }
}

internal class FakeFormStateDao : FormStateDao {
    private val rows = MutableStateFlow<Map<String, FormStateEntity>>(emptyMap())
    override suspend fun upsert(state: FormStateEntity) { rows.value = rows.value + (state.msgId to state) }
    override suspend fun getByMessage(msgId: String): FormStateEntity? = rows.value[msgId]
}

internal class FakeMarkerDao : ThreadMarkerDao {
    private val rows = MutableStateFlow<List<ThreadMarkerEntity>>(emptyList())
    override suspend fun insert(marker: ThreadMarkerEntity) { rows.value = rows.value + marker }
    override suspend fun update(marker: ThreadMarkerEntity) { rows.value = rows.value.map { if (it.id == marker.id) marker else it } }
    override suspend fun delete(marker: ThreadMarkerEntity) { rows.value = rows.value.filterNot { it.id == marker.id } }
    override suspend fun getById(id: String): ThreadMarkerEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun forRoot(rootId: String): List<ThreadMarkerEntity> = rows.value.filter { it.rootId == rootId }.sortedBy { it.at }
    override suspend fun forAnchor(msgId: String): List<ThreadMarkerEntity> = rows.value.filter { it.anchorMsgId == msgId }.sortedByDescending { it.at }
    override fun observeAll(): Flow<List<ThreadMarkerEntity>> = rows
}

internal class FakeDelegationDao : DelegationEventDao {
    private val rows = MutableStateFlow<List<DelegationEventEntity>>(emptyList())
    override suspend fun insert(event: DelegationEventEntity) { rows.value = rows.value + event }
    override suspend fun update(event: DelegationEventEntity) { rows.value = rows.value.map { if (it.id == event.id) event else it } }
    override suspend fun getById(id: String): DelegationEventEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun forRoot(rootId: String): List<DelegationEventEntity> = rows.value.filter { it.rootId == rootId }
    override fun observeAll(): Flow<List<DelegationEventEntity>> = rows
}

internal class FakeReceiptDao : ReceiptDao {
    private val rows = MutableStateFlow<List<ReceiptEntity>>(emptyList())
    override suspend fun insert(e: ReceiptEntity) { rows.value = rows.value + e.copy(id = rows.value.size.toLong() + 1) }
    override fun all(): Flow<List<ReceiptEntity>> = rows.map { it.sortedBy(ReceiptEntity::createdAtUtcMillis) }
    override suspend fun findByIdempotencyKey(idempotencyKey: String): ReceiptEntity? =
        rows.value.filter { it.idempotencyKey == idempotencyKey }.maxByOrNull { it.createdAtUtcMillis }
    override fun forObjectId(objectId: String): Flow<List<ReceiptEntity>> =
        rows.map { list -> list.filter { it.objectId == objectId }.sortedBy(ReceiptEntity::createdAtUtcMillis) }
}
