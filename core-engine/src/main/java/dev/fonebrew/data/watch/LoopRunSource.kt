package dev.fonebrew.data.watch

import dev.fonebrew.data.BackgroundJobs
import dev.fonebrew.data.MessageTreeRepository
import dev.fonebrew.ui.state.LedgerSource
import dev.fonebrew.watchbridge.LoopRunSnapshot
import kotlinx.coroutines.flow.first

/** Where the relay gets its read-only loop-run glances. A seam so the presenter is tested without a database. */
fun interface LoopRunSource {
    suspend fun recentRuns(): List<LoopRunSnapshot>
}

/**
 * Reads the phone's own records of loop runs, never anything of its own: the run log in the message
 * tree ([MessageTreeRepository], where `GraphRunLog` filed each run), the usage ledger
 * ([LedgerSource], where `GraphRunLedger` wrote each step's real or estimated cost), and the live
 * [BackgroundJobs] registry for runs still going. All of the assembly rules are in [WristSnapshots.loopRuns].
 *
 * Read-only by construction: no method here writes to the tree, the ledger or the job registry.
 *
 * Cost note: the tree read is the whole-tree snapshot `MessageTreeRepository.tree()` that the phone's
 * own navigation already uses. It runs only when the watch asks for a sync, never on a timer.
 */
class LedgerLoopRunSource(
    private val repository: MessageTreeRepository,
    private val ledger: LedgerSource,
    private val jobs: () -> List<BackgroundJobs.Job>,
    private val loopName: (loopId: String?) -> String?,
    private val currencyCode: () -> String,
) : LoopRunSource {

    override suspend fun recentRuns(): List<LoopRunSnapshot> =
        WristSnapshots.loopRuns(
            tree = repository.tree(),
            ledger = ledger.entries().first(),
            jobs = jobs(),
            loopName = loopName,
            currencyCode = currencyCode(),
        )
}
