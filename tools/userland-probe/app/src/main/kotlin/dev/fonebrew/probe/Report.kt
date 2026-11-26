package dev.fonebrew.probe

/**
 * Pure report model: no Android types, JVM-tested. PASS means "the capability being asked about is
 * available", FAIL means "measured, and it is not (raw errno/exception attached)", UNKNOWN means
 * "could not be measured here" - never a guess. State is carried by a glyph AND a word, never by
 * colour (the owner is red-green colourblind).
 */
enum class Status(val glyph: String) { PASS("+"), FAIL("x"), UNKNOWN("?") }

data class CheckResult(val id: String, val title: String, val status: Status, val detail: String)

object ReportFormatter {
    const val FORMAT = "userland-probe report v1"

    fun format(header: List<Pair<String, String>>, results: List<CheckResult>): String = buildString {
        appendLine(FORMAT)
        appendLine("Local only: nothing in this report was uploaded; the probe has no network permission.")
        appendLine("Legend: [+ PASS] capability available, [x FAIL] measured not available, [? UNKNOWN] not measured.")
        appendLine("Every result below is a measurement of THIS device at THIS moment; none is verified by the author.")
        appendLine()
        for ((k, v) in header) appendLine("$k: $v")
        appendLine()
        for (r in results) {
            appendLine("[${r.status.glyph} ${r.status.name}] ${r.id} ${r.title}")
            for (line in r.detail.lines()) if (line.isNotBlank()) appendLine("    $line")
        }
        appendLine()
        appendLine(summary(results))
    }

    fun summary(results: List<CheckResult>): String {
        val c = Status.values().associateWith { s -> results.count { it.status == s } }
        return "Summary: ${c[Status.PASS]} PASS, ${c[Status.FAIL]} FAIL, ${c[Status.UNKNOWN]} UNKNOWN of ${results.size} checks"
    }
}

/** Native helpers return "PASS ...", "FAIL ..." or "UNKNOWN ..."; anything else is UNKNOWN, kept verbatim. */
object NativeVerdict {
    fun parse(raw: String?): Pair<Status, String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return Status.UNKNOWN to "native helper returned nothing"
        val head = s.substringBefore(' ')
        val status = Status.values().firstOrNull { it.name == head } ?: return Status.UNKNOWN to s
        return status to s.removePrefix(head).trim()
    }
}

/** Result of running a child process with a deadline; [thrown] carries the raw exception text. */
data class ExecResult(val exit: Int?, val output: String, val thrown: String?, val timedOut: Boolean)

object ExecClassifier {
    /** Check (ii)/(iii): did the probe's own test executable run and print its marker? */
    fun classify(r: ExecResult, marker: String = "probe_exec ok"): Pair<Status, String> = when {
        r.thrown != null -> Status.FAIL to "exception: ${r.thrown}"
        r.timedOut -> Status.UNKNOWN to "timed out; partial output: ${r.output.trim()}"
        r.exit == 0 && r.output.contains(marker) -> Status.PASS to r.output.trim()
        else -> Status.FAIL to "exit=${r.exit} output: ${r.output.trim()}"
    }
}

object CpuInfo {
    /** First "Features" line of /proc/cpuinfo (arm64), or null. */
    fun features(cpuinfo: String): String? = cpuinfo.lineSequence()
        .firstOrNull { it.startsWith("Features") }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
}

object SelinuxContext {
    /** /proc/self/attr/current is NUL-terminated; strip the NUL and whitespace. */
    fun clean(raw: String): String = raw.trimEnd('\u0000', '\n', ' ')
}

/** Check (vi): outcome of spawning children and sampling who is alive after the dwell time. */
data class ForkSurvey(
    val requested: Int,
    val spawned: Int,
    val spawnFailure: String?,
    val aliveAtEnd: Int,
    val deadExitCodes: List<Int>,
    val dwellSeconds: Int,
    val foregroundServiceRunning: Boolean,
    val appImportanceAtEnd: String,
) {
    /** 137 = 128 + SIGKILL, the signature of a kill (phantom-process or low-memory). */
    val killed: Int get() = deadExitCodes.count { it == 137 }

    fun verdict(): Pair<Status, String> {
        val head = "requested=$requested spawned=$spawned aliveAfter${dwellSeconds}s=$aliveAtEnd " +
            "dead=${deadExitCodes.size} (SIGKILL-style exit 137: $killed) fgService=$foregroundServiceRunning " +
            "appImportanceAtEnd=$appImportanceAtEnd"
        val note = spawnFailure?.let { "\nfirst spawn failure: $it" }.orEmpty()
        val status = when {
            !foregroundServiceRunning -> Status.UNKNOWN
            spawned == 0 -> Status.FAIL
            spawned == requested && aliveAtEnd == spawned -> Status.PASS
            else -> Status.FAIL
        }
        val why = if (!foregroundServiceRunning) "\nforeground service did not start, so the survival question is unanswered" else ""
        return status to head + note + why
    }
}

object StorageVerdict {
    /** Heuristic threshold of THIS probe (archive + extracted tree + headroom), not a measured need. */
    const val THRESHOLD_BYTES: Long = 2L * 1024 * 1024 * 1024

    fun verdict(freeBytes: Long): Pair<Status, String> {
        val gib = freeBytes / (1024.0 * 1024 * 1024)
        val text = "usable bytes on the app data volume: $freeBytes (%.2f GiB); probe threshold %d GiB is a heuristic, not a measured requirement"
            .format(gib, THRESHOLD_BYTES / (1024 * 1024 * 1024))
        return (if (freeBytes >= THRESHOLD_BYTES) Status.PASS else Status.FAIL) to text
    }
}

/** Check (ix): the line protocol of the local-socket command bridge used by the probe. */
object BridgeProtocol {
    private val token = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** "PING <token>" -> "PONG <token>"; anything else -> "ERR". Never echoes unvalidated input. */
    fun reply(line: String): String {
        val parts = line.trim().split(' ')
        return if (parts.size == 2 && parts[0] == "PING" && token.matches(parts[1])) "PONG ${parts[1]}" else "ERR"
    }
}
