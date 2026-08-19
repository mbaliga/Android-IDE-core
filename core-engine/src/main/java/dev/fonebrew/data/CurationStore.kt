package dev.fonebrew.data

import dev.fonebrew.data.dao.CompactionDirectiveDao
import dev.fonebrew.data.dao.FormStateDao
import dev.fonebrew.data.dao.GhostBranchDao
import dev.fonebrew.data.dao.MessageBookmarkDao
import dev.fonebrew.data.dao.VerdictDao
import dev.fonebrew.data.dao.VersionDao
import dev.fonebrew.data.entity.CompactionDirectiveEntity
import dev.fonebrew.data.entity.FormStateEntity
import dev.fonebrew.data.entity.GhostBranchEntity
import dev.fonebrew.data.entity.MessageBookmarkEntity
import dev.fonebrew.data.entity.VerdictEntity
import dev.fonebrew.data.entity.VersionEntity
import dev.fonebrew.domain.curation.BookmarkKind
import dev.fonebrew.domain.curation.CompactionDirective
import dev.fonebrew.domain.curation.Fidelity
import dev.fonebrew.domain.curation.FormState
import dev.fonebrew.domain.curation.GhostBranch
import dev.fonebrew.domain.curation.GhostReason
import dev.fonebrew.domain.curation.MessageBookmark
import dev.fonebrew.domain.curation.MessageRef
import dev.fonebrew.domain.curation.Verdict
import dev.fonebrew.domain.curation.Version
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The Conversation Instrument's data gateway (STUDIO_UX_SPEC.md §5.1): verdicts, message-level
 * bookmarks, versions, compaction directives, ghost branches, and form state. Fronts six
 * [dev.fonebrew.data.dao] interfaces with one store, since these six annotation types are read and
 * combined together constantly (compaction resolution alone needs verdicts + bookmarks +
 * directives + version spines at once) — same "one store per closely-related concern" shape as
 * [TaskStore]/[WatchStore], just over six tables instead of one.
 */
class CurationStore(
    private val verdictDao: VerdictDao,
    private val bookmarkDao: MessageBookmarkDao,
    private val versionDao: VersionDao,
    private val directiveDao: CompactionDirectiveDao,
    private val ghostDao: GhostBranchDao,
    private val formStateDao: FormStateDao,
) {

    // ---- Verdicts --------------------------------------------------------------------------

    val verdicts: Flow<List<Verdict>> = verdictDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun verdictFor(msgId: String): Verdict? = verdictDao.getByMessage(msgId)?.toDomain()

    /** Sets (or replaces) the verdict on a message — "one live per message." */
    suspend fun setVerdict(msgId: String, grade: Int, now: Long = System.currentTimeMillis()) {
        verdictDao.upsert(VerdictEntity(msgId = msgId, grade = grade, at = now))
    }

    suspend fun clearVerdict(msgId: String) {
        verdictDao.getByMessage(msgId)?.let { verdictDao.delete(it) }
    }

    // ---- Bookmarks ---------------------------------------------------------------------------

    val bookmarks: Flow<List<MessageBookmark>> = bookmarkDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun bookmarksFor(msgId: String): List<MessageBookmark> = bookmarkDao.forMessage(msgId).map { it.toDomain() }

    /**
     * Guards [toggleMessageBookmark]/[toggleBlockBookmark]'s check-then-act (SELECT existing,
     * then INSERT or DELETE — two separate suspend DAO round-trips, no Room `@Transaction`).
     * Fixed per adversarial review: without this, two fast taps on the same bookmark control
     * (an ordinary single-tap button, not just the double-tap gesture) could both observe "no
     * existing bookmark" before either write lands, and both insert — leaving two live
     * whole-message bookmark rows that then required an extra, unexplained tap to fully clear.
     * A single process-wide mutex is enough here (this is a local, single-user, single-process
     * app — there's no cross-device contention to model), and simpler than adding a Room
     * `@Transaction` DAO method for a check-then-act that spans two different queries.
     */
    private val bookmarkMutex = Mutex()

    /**
     * Double-tap semantics (STUDIO_UX_SPEC.md §4.3): double-tap pins the whole message; double-
     * tap again removes it. Returns the new bookmark if one was created, or null if the existing
     * one was removed.
     */
    suspend fun toggleMessageBookmark(
        msgId: String,
        kind: BookmarkKind = BookmarkKind.REFERENCE,
        now: Long = System.currentTimeMillis(),
    ): MessageBookmark? = bookmarkMutex.withLock {
        val existing = bookmarkDao.forMessage(msgId).firstOrNull { it.blockIndex == null }
        if (existing != null) {
            bookmarkDao.delete(existing)
            return@withLock null
        }
        val bookmark = MessageBookmark(
            id = UUID.randomUUID().toString(),
            ref = MessageRef(msgId, blockIndex = null),
            kind = kind,
            at = now,
        )
        bookmarkDao.insert(bookmark.toEntity())
        bookmark
    }

    /** Same double-tap toggle, scoped to one code block within a message (STUDIO_UX_SPEC.md §4.3: "Double-tap on a code block bookmarks the block specifically"). */
    suspend fun toggleBlockBookmark(
        msgId: String,
        blockIndex: Int,
        kind: BookmarkKind = BookmarkKind.SNIPPET,
        now: Long = System.currentTimeMillis(),
    ): MessageBookmark? = bookmarkMutex.withLock {
        val existing = bookmarkDao.forMessage(msgId).firstOrNull { it.blockIndex == blockIndex }
        if (existing != null) {
            bookmarkDao.delete(existing)
            return@withLock null
        }
        val bookmark = MessageBookmark(
            id = UUID.randomUUID().toString(),
            ref = MessageRef(msgId, blockIndex = blockIndex),
            kind = kind,
            at = now,
        )
        bookmarkDao.insert(bookmark.toEntity())
        bookmark
    }

    suspend fun updateBookmark(bookmark: MessageBookmark) {
        bookmarkDao.delete(bookmark.toEntity())
        bookmarkDao.insert(bookmark.toEntity())
    }

    suspend fun removeBookmark(bookmark: MessageBookmark) = bookmarkDao.delete(bookmark.toEntity())

    // ---- Versions ------------------------------------------------------------------------------

    val versions: Flow<List<Version>> = versionDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun versionAtTip(msgId: String): Version? = versionDao.atTip(msgId)?.toDomain()

    suspend fun markVersion(
        branchTipMsgId: String,
        name: String,
        note: String? = null,
        proofRef: String? = null,
        suggestedBy: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Version {
        val version = Version(
            id = UUID.randomUUID().toString(),
            branchTipMsgId = branchTipMsgId,
            name = name,
            note = note,
            proofRef = proofRef,
            at = now,
            suggestedBy = suggestedBy,
        )
        versionDao.insert(version.toEntity())
        return version
    }

    suspend fun renameVersion(version: Version, name: String, note: String? = version.note) {
        versionDao.update(version.copy(name = name, note = note).toEntity())
    }

    // ---- Compaction directives -------------------------------------------------------------

    val directives: Flow<List<CompactionDirective>> = directiveDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun directiveFor(msgId: String): CompactionDirective? = directiveDao.getByMessage(msgId)?.toDomain()

    suspend fun setDirective(msgId: String, mustInclude: Boolean, fidelity: Fidelity) {
        directiveDao.upsert(CompactionDirectiveEntity(msgId = msgId, mustInclude = mustInclude, fidelity = fidelity))
    }

    suspend fun clearDirective(msgId: String) {
        directiveDao.getByMessage(msgId)?.let { directiveDao.delete(it) }
    }

    // ---- Ghost branches ----------------------------------------------------------------------

    val ghostBranches: Flow<List<GhostBranch>> = ghostDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun ghostFor(branchTipMsgId: String): GhostBranch? = ghostDao.getByTip(branchTipMsgId)?.toDomain()

    suspend fun recordGhost(ghost: GhostBranch) = ghostDao.upsert(ghost.toEntity())

    // ---- Form state ----------------------------------------------------------------------------

    suspend fun formStateFor(msgId: String): FormState? = formStateDao.getByMessage(msgId)?.toDomain()

    suspend fun saveFormState(msgId: String, schemaJson: String, answersJson: String) {
        formStateDao.upsert(FormStateEntity(msgId = msgId, schemaJson = schemaJson, answersJson = answersJson))
    }
}

private fun VerdictEntity.toDomain() = Verdict(msgId = msgId, grade = grade, at = at)
private fun Verdict.toEntity() = VerdictEntity(msgId = msgId, grade = grade, at = at)

private fun MessageBookmarkEntity.toDomain() = MessageBookmark(
    id = id,
    ref = MessageRef(msgId, blockIndex),
    kind = kind,
    note = note,
    label = label,
    at = at,
)

private fun MessageBookmark.toEntity() = MessageBookmarkEntity(
    id = id,
    msgId = ref.msgId,
    blockIndex = ref.blockIndex,
    kind = kind,
    note = note,
    label = label,
    at = at,
)

private fun VersionEntity.toDomain() = Version(
    id = id, branchTipMsgId = branchTipMsgId, name = name, note = note,
    proofRef = proofRef, at = at, suggestedBy = suggestedBy,
)

private fun Version.toEntity() = VersionEntity(
    id = id, branchTipMsgId = branchTipMsgId, name = name, note = note,
    proofRef = proofRef, at = at, suggestedBy = suggestedBy,
)

private fun CompactionDirectiveEntity.toDomain() = CompactionDirective(msgId = msgId, mustInclude = mustInclude, fidelity = fidelity)

private fun GhostBranchEntity.toDomain() = GhostBranch(branchTipMsgId = branchTipMsgId, rewoundAt = rewoundAt, reason = reason)
private fun GhostBranch.toEntity() = GhostBranchEntity(branchTipMsgId = branchTipMsgId, rewoundAt = rewoundAt, reason = reason)

private fun FormStateEntity.toDomain() = FormState(msgId = msgId, schemaJson = schemaJson, answersJson = answersJson)
