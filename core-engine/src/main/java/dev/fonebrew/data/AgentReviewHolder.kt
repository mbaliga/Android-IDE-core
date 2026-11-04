package dev.fonebrew.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A read-only mirror of the Develop room's Agent tab (`ui/develop/DevelopRoom.kt`, `FilesFacet`) at
 * process scope, so the home-screen "Agent review" widget (`dev.fonebrew.widget`) can say honestly
 * whether a run is working or changes are waiting, without reaching into a composable.
 *
 * The tab still owns its proposal (in `remember`, as before): it is reviewed and committed there, to
 * the host it was read from, and it is gone when the tab leaves composition. This holder only follows
 * it: the tab [started] a run, [offer]s what it proposed, marks a run [finished] when it fails or is
 * cancelled, and [clear]s everything when the proposal is committed or the tab goes away. Nothing here
 * commits, and nothing here keeps the typed objective.
 */
class AgentReviewHolder(private val clock: () -> Long = System::currentTimeMillis) {

    data class Pending(
        val proposal: AgentRepoRunner.Proposal,
        /** Whether the proposing model ran on this device; false means a cloud model (a watched object). */
        val onDevice: Boolean,
        val proposedAt: Long,
    ) {
        /** Files with a real change, the same count the Develop room's "Review N change(s)" shows. */
        val changeCount: Int get() = proposal.changeSet.effective.size

        /** Lines added/removed across the proposal: a line diff per file, so computed once, off the UI thread. */
        val lineStat: dev.fonebrew.domain.diff.LineDiff.Stat by lazy { proposal.changeSet.stat }
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _runningOnDevice = MutableStateFlow<Boolean?>(null)

    /** Where the run in progress executes (false: a cloud model); null when no run is in progress. */
    val runningOnDevice: StateFlow<Boolean?> = _runningOnDevice.asStateFlow()

    /** A run started on a model that runs on this device ([onDevice]) or in the cloud; the old proposal is gone. */
    fun started(onDevice: Boolean) {
        _pending.value = null
        _runningOnDevice.value = onDevice
    }

    fun offer(proposal: AgentRepoRunner.Proposal, onDevice: Boolean) {
        _runningOnDevice.value = null
        _pending.value = Pending(proposal, onDevice, clock())
    }

    /** The run ended without a proposal (it failed, or leaving the tab cancelled it). */
    fun finished() {
        _runningOnDevice.value = null
    }

    fun clear() {
        _runningOnDevice.value = null
        _pending.value = null
    }
}
