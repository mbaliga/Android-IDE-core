package dev.aarso.domain.curation

import dev.aarso.domain.MessageNode

/**
 * One message's compaction outcome — the Compaction Preview's per-row badge
 * (STUDIO_UX_SPEC.md §5.2) and the Receipt's per-message entry.
 *
 * @property text the message content *after* compaction: unchanged for [MessageFate.KEPT_VERBATIM],
 *   agent-produced for [MessageFate.FAITHFUL]/[MessageFate.GIST]/[MessageFate.TOMBSTONE], null
 *   for [MessageFate.DROPPED].
 */
data class CompactedMessage(
    val msgId: String,
    val fate: MessageFate,
    val resolution: ResolvedFidelity,
    val text: String?,
)

/**
 * The compaction agent's job: turn a verbatim message into a lower-fidelity version. Deliberately
 * an interface, not an implementation — same split as
 * [dev.aarso.domain.bridge.SummaryBridge]'s own KDoc: *"the summary prose is a model call...
 * that is emphatically NOT this file."* [CompactionEngine] owns the deterministic contract
 * (which messages get which fidelity, and the mechanical proof that F3 was honored);
 * *generating* gist/faithful text is a model call through the existing
 * [dev.aarso.inference.InferenceEngine] machinery, wired by whatever calls [CompactionEngine.run]
 * — never faked here.
 */
fun interface CompactionAgent {
    /**
     * Produce the compacted text for one message. Never called for
     * [MessageFate.KEPT_VERBATIM] (the engine keeps the original verbatim itself, so the agent
     * can't accidentally alter an F3 span) or [MessageFate.DROPPED] (nothing to produce).
     */
    suspend fun compact(original: MessageNode, fate: MessageFate, resolution: ResolvedFidelity): String
}

/** A run's outcome: either every F3-resolved span survived byte-for-byte, or it didn't — and if it didn't, the run fails loudly rather than silently shipping a violated contract. */
sealed interface CompactionRunResult {
    data class Success(val receipt: Receipt) : CompactionRunResult
    data class Failure(val violations: List<F3Violation>) : CompactionRunResult
}

/** One message whose [MessageFate.KEPT_VERBATIM] promise was broken. */
data class F3Violation(val msgId: String, val original: String, val produced: String)

/** A permanent, F3-itself, expandable/diffable record of what a compaction run did (STUDIO_UX_SPEC.md §5.2's "Receipt"). */
data class Receipt(
    val runAt: Long,
    val entries: List<CompactedMessage>,
    val directivesHonored: Int,
    val directivesTotal: Int,
)

/**
 * Runs the Compaction Contract over a set of messages: resolves each message's fidelity
 * ([CompactionContract]), asks the [CompactionAgent] to produce non-verbatim text, and
 * mechanically verifies every F3/must-verbatim span survived untouched
 * ([CompactionVerifier]) before returning success.
 */
object CompactionEngine {

    /**
     * @param messages the messages to consider, in any order (verdicts/bookmarks/versionSpine
     *   are looked up by id, not by position).
     * @param directives explicit per-message user directives, if any.
     * @param verdicts current verdict per message, if any.
     * @param bookmarkedIds every message id with at least one [MessageBookmark] (whole-message
     *   or block-level) pointing at it.
     * @param versionSpineIds every message id lying on the ancestor path of some [Version]'s
     *   branch tip.
     */
    suspend fun run(
        messages: List<MessageNode>,
        directives: Map<String, CompactionDirective>,
        verdicts: Map<String, Verdict>,
        bookmarkedIds: Set<String>,
        versionSpineIds: Set<String>,
        agent: CompactionAgent,
        now: Long,
    ): CompactionRunResult {
        val entries = messages.map { message ->
            val resolution = CompactionContract.resolve(
                msgId = message.id,
                directive = directives[message.id],
                verdict = verdicts[message.id],
                isBookmarked = message.id in bookmarkedIds,
                isOnVersionSpine = message.id in versionSpineIds,
            )
            val fate = Fates.forResolution(resolution)
            val text = when (fate) {
                MessageFate.KEPT_VERBATIM -> message.content
                MessageFate.DROPPED -> null
                MessageFate.FAITHFUL, MessageFate.GIST, MessageFate.TOMBSTONE ->
                    agent.compact(message, fate, resolution)
            }
            CompactedMessage(msgId = message.id, fate = fate, resolution = resolution, text = text)
        }

        val violations = CompactionVerifier.verify(messages, entries)
        if (violations.isNotEmpty()) return CompactionRunResult.Failure(violations)

        // Fixed per adversarial review: scope the "directives honored" count to directives whose
        // message is actually IN this run's batch. The caller's `directives` map may (and, once a
        // Compaction Preview only submits a windowed subset of the conversation, routinely will)
        // carry directives for messages outside this specific run — those can never appear in
        // `entries`, so counting them toward `directivesTotal` without a matching honored count
        // made the receipt's "n/n honored" trust figure under-report even when nothing in this
        // run actually violated anything.
        val messageIds = messages.mapTo(mutableSetOf()) { it.id }
        val directivesInBatch = directives.filterKeys { it in messageIds }
        val directivesTotal = directivesInBatch.size
        val directivesHonored = directivesInBatch.count { (msgId, directive) ->
            val entry = entries.firstOrNull { it.msgId == msgId } ?: return@count false
            entry.resolution.fidelity == directive.fidelity
        }
        return CompactionRunResult.Success(
            Receipt(
                runAt = now,
                entries = entries,
                directivesHonored = directivesHonored,
                directivesTotal = directivesTotal,
            ),
        )
    }
}

/**
 * The mechanical, non-negotiable half of the contract (STUDIO_UX_SPEC.md §5.2: *"F3 spans are
 * byte-compared post-run; a violation fails the run loudly — never silently."*). Pure and
 * agent-independent on purpose: this check must catch a broken/malicious/buggy
 * [CompactionAgent] just as readily as a correct one, so it never trusts the agent's own report
 * of what it did — it re-derives verbatim-ness from the original messages every time.
 */
object CompactionVerifier {

    fun verify(originals: List<MessageNode>, produced: List<CompactedMessage>): List<F3Violation> {
        val originalById = originals.associateBy { it.id }
        val violations = mutableListOf<F3Violation>()
        for (entry in produced) {
            if (entry.fate != MessageFate.KEPT_VERBATIM) continue
            val original = originalById[entry.msgId] ?: continue
            if (entry.text != original.content) {
                violations += F3Violation(
                    msgId = entry.msgId,
                    original = original.content,
                    produced = entry.text.orEmpty(),
                )
            }
        }
        return violations
    }
}
