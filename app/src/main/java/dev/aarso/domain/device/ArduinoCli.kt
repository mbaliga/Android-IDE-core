package dev.aarso.domain.device

/**
 * Parses `arduino-cli` output into legible state (docs/build-plan.md, Sprint 3). The compiler's
 * and flasher's *real* words are what the user sees; this turns them into structured outcomes
 * (errors with file:line:col, flash/RAM usage, upload success) so the UI can render the truth
 * materially — it never fabricates a "success" the tool didn't report. Pure Kotlin; JVM-tested.
 */
object ArduinoCli {

    /** One compiler diagnostic at a source location. */
    data class CompileError(val file: String, val line: Int, val col: Int, val message: String)

    /** Memory usage reported on a successful build. */
    data class SketchSize(
        val programBytes: Int? = null,
        val programPct: Int? = null,
        val dataBytes: Int? = null,
        val dataPct: Int? = null,
    )

    /** The outcome of a compile. [ok] only when the tool reported no errors. */
    data class CompileResult(
        val ok: Boolean,
        val errors: List<CompileError>,
        val size: SketchSize?,
        val raw: String,
    )

    /** The outcome of an upload. [ok] only when the flasher reported completion. */
    data class UploadResult(val ok: Boolean, val raw: String)

    // file:line:col: error|fatal error: message
    private val errorRegex =
        Regex("""^(.*?):(\d+):(\d+):\s*(?:fatal\s+)?error:\s*(.*)$""", RegexOption.MULTILINE)
    private val programRegex =
        Regex("""Sketch uses (\d+) bytes(?:\s*\((\d+)%\))?""")
    private val dataRegex =
        Regex("""Global variables use (\d+) bytes(?:\s*\((\d+)%\))?""")

    fun parseCompile(output: String): CompileResult {
        val errors = errorRegex.findAll(output).map { m ->
            CompileError(
                file = m.groupValues[1].trim(),
                line = m.groupValues[2].toInt(),
                col = m.groupValues[3].toInt(),
                message = m.groupValues[4].trim(),
            )
        }.toList()

        val size = parseSize(output)
        // The tool's own failure markers; presence of any error line also fails the build.
        val failed = errors.isNotEmpty() ||
            output.contains("Error during build", ignoreCase = true) ||
            output.contains("Compilation error", ignoreCase = true)
        return CompileResult(ok = !failed, errors = errors, size = size, raw = output)
    }

    private fun parseSize(output: String): SketchSize? {
        val p = programRegex.find(output)
        val d = dataRegex.find(output)
        if (p == null && d == null) return null
        return SketchSize(
            programBytes = p?.groupValues?.get(1)?.toIntOrNull(),
            programPct = p?.groupValues?.get(2)?.toIntOrNull(),
            dataBytes = d?.groupValues?.get(1)?.toIntOrNull(),
            dataPct = d?.groupValues?.get(2)?.toIntOrNull(),
        )
    }

    fun parseUpload(output: String): UploadResult {
        // ok iff a completion marker is present and no hard failure word — we report only the
        // truth the flasher stated, never an optimistic default.
        val completed = output.contains("avrdude done", ignoreCase = true) ||
            output.contains("Verify successful", ignoreCase = true) ||
            output.contains("Hash of data verified", ignoreCase = true)
        val failed = output.contains("can't open device", ignoreCase = true) ||
            output.contains("programmer is not responding", ignoreCase = true) ||
            output.contains("Error:", ignoreCase = true) ||
            output.contains("A fatal error occurred", ignoreCase = true)
        return UploadResult(ok = completed && !failed, raw = output)
    }
}
