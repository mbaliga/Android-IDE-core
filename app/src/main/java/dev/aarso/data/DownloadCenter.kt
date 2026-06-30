package dev.aarso.data

import android.content.Context
import dev.aarso.service.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-wide home for model downloads, so they outlive whichever screen
 * started them (the old per-ViewModel coroutines died on backgrounding) and so
 * one glanceable surface (AppRoot strip + the foreground service notification)
 * can show everything in flight.
 *
 * Work runs in this singleton's scope; [DownloadService] only holds foreground
 * priority while anything is active — the same division of labour as
 * [dev.aarso.service.GenerationService].
 */
class DownloadCenter(private val context: Context) {

    data class Request(
        val id: String,
        val url: String,
        val fileName: String,
        val downloader: ModelDownloader,
    )

    data class State(
        val request: Request,
        val progress: ModelDownloader.Progress,
        /** User-paused: the transfer is stopped but the .part is kept for resume. */
        val paused: Boolean = false,
    ) {
        val failed: Boolean get() = progress.error != null && !paused
        val running: Boolean get() = !progress.done && progress.error == null && !paused
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val _active = MutableStateFlow<Map<String, State>>(emptyMap())
    val active: StateFlow<Map<String, State>> = _active.asStateFlow()

    /** Start (or resume) a download. A no-op while the same id is already running. */
    @Synchronized
    fun enqueue(id: String, url: String, fileName: String, downloader: ModelDownloader) {
        if (jobs[id]?.isActive == true) return
        val request = Request(id, url, fileName, downloader)
        val resumeFrom = downloader.partFile(fileName).length()
        _active.update { it + (id to State(request, ModelDownloader.Progress(resumeFrom, 0))) }
        DownloadService.start(context)
        jobs[id] = scope.launch {
            request.downloader.download(url, fileName).collect { p ->
                _active.update { it + (id to State(request, p)) }
            }
            if (_active.value[id]?.progress?.done == true) {
                _active.update { it - id }
            }
            onJobFinished(id)
        }
    }

    /** Retry a failed or paused download — resumes from the kept .part. */
    fun retry(id: String) {
        val request = _active.value[id]?.takeIf { it.failed || it.paused }?.request ?: return
        enqueue(request.id, request.url, request.fileName, request.downloader)
    }

    /** User pause: stop the transfer but keep the .part; [retry] resumes it. */
    @Synchronized
    fun pause(id: String) {
        val state = _active.value[id]?.takeIf { it.running } ?: return
        jobs.remove(id)?.cancel()
        _active.update { it + (id to state.copy(paused = true)) }
        stopServiceIfIdle()
    }

    /** User cancel: stop the transfer and discard the partial file. */
    @Synchronized
    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        _active.value[id]?.request?.let { it.downloader.discardPart(it.fileName) }
        _active.update { it - id }
        stopServiceIfIdle()
    }

    @Synchronized
    private fun onJobFinished(id: String) {
        jobs.remove(id)
        stopServiceIfIdle()
    }

    private fun stopServiceIfIdle() {
        if (jobs.values.none { it.isActive }) DownloadService.stop(context)
    }
}
