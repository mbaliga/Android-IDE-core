package dev.fonebrew.data.benchmark

import dev.fonebrew.domain.benchmark.BenchmarkJson
import dev.fonebrew.domain.benchmark.BenchmarkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/**
 * Local benchmark history: one JSON file in app storage, newest last, bounded to the last
 * [MAX_RESULTS]. Append-only in spirit: the only operations are [append] (which drops the oldest
 * past the bound) and read; a stored result is never edited. No network, no export beyond the
 * explicit copy-to-clipboard in the UI.
 *
 * Writes go to a temp file and are renamed over the real one, so a process kill mid-write leaves
 * the previous history intact. A history file that cannot be parsed is renamed `*.corrupt` (kept,
 * not overwritten) and history starts empty; a single record that cannot be decoded is skipped.
 */
class BenchmarkHistoryStore(private val file: File) {

    private val lock = Any()
    private val _results = MutableStateFlow(load())
    val results: StateFlow<List<BenchmarkResult>> = _results.asStateFlow()

    fun append(result: BenchmarkResult) {
        synchronized(lock) {
            val next = (_results.value + result).takeLast(MAX_RESULTS)
            write(next)
            _results.value = next
        }
    }

    private fun write(list: List<BenchmarkResult>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().also { arr -> list.forEach { arr.put(BenchmarkJson.encode(it)) } }.toString())
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse rename-over; fall back to delete + rename.
            file.delete()
            check(tmp.renameTo(file)) { "could not save benchmark history" }
        }
    }

    private fun load(): List<BenchmarkResult> {
        if (!file.exists()) return emptyList()
        val arr = try {
            JSONArray(file.readText())
        } catch (_: Exception) {
            file.renameTo(File(file.parentFile, file.name + ".corrupt"))
            return emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { BenchmarkJson.decode(arr.getJSONObject(i)) }.getOrNull()
        }.takeLast(MAX_RESULTS)
    }

    companion object {
        const val MAX_RESULTS = 50
        const val FILE_NAME = "benchmark-history.json"
    }
}
