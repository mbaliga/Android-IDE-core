package dev.aarso.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * A flat registry of concurrent work happening elsewhere in the app — a Loop run, the coding
 * Agent proposing a change — so Chat's background-tasks strip can show what's actually running,
 * the way this app's own build tooling shows parallel background agents. [DownloadCenter] already
 * has its own richer per-item percentage and isn't routed through here; this is for coarser
 * start/finish jobs that don't have a progress fraction.
 */
class BackgroundJobs {

    data class Job(
        val id: String,
        val label: String,
        val kind: String,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val failed: Boolean = false,
    )

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    /** Registers a running job; call [finish] with the returned id once it settles. */
    fun start(label: String, kind: String): String {
        val id = UUID.randomUUID().toString()
        _jobs.update { it + Job(id, label, kind, startedAt = System.currentTimeMillis()) }
        return id
    }

    fun finish(id: String, failed: Boolean = false) {
        _jobs.update { list ->
            list.map { if (it.id == id) it.copy(finishedAt = System.currentTimeMillis(), failed = failed) else it }
        }
    }

    /** Drops finished jobs older than [maxAgeMillis] — called opportunistically on read, not on a timer. */
    fun prune(maxAgeMillis: Long = 5 * 60_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        _jobs.update { list -> list.filter { it.finishedAt == null || it.finishedAt > cutoff } }
    }
}
