package dev.fonebrew.domain.benchmark

import org.json.JSONArray
import java.util.Locale

/**
 * The "Copy results as text" payload and the on-screen table's wording: a human-readable block per
 * run, then one compact JSON block, so the owner can paste numbers into a session. Unknown prints
 * as "unknown", never 0. Nothing here is a verdict.
 */
object BenchmarkTextExport {

    fun render(results: List<BenchmarkResult>): String {
        if (results.isEmpty()) return "No benchmark results yet."
        val sb = StringBuilder()
        sb.append("Fonebrew on-device benchmark results\n")
        sb.append(BenchmarkResult.SCOPE_STATEMENT).append("\n")
        sb.append("Prefill tok/s uses the engine's own prompt token count, which excludes chat-template overhead (slight under-read). TTFT includes template render + prefill + first sample.\n\n")
        results.forEach { sb.append(renderOne(it)).append("\n") }
        sb.append("JSON:\n")
        sb.append(JSONArray().also { arr -> results.forEach { arr.put(BenchmarkJson.encode(it)) } }.toString())
        sb.append("\n")
        return sb.toString()
    }

    fun renderOne(r: BenchmarkResult): String {
        val sb = StringBuilder()
        sb.append("${r.outcome.glyph} ${r.outcome.label} - ${r.modelFileName}")
        sb.append(" (quant ${r.quantization ?: "unknown"}, ctx ${r.contextSize}, tokenizer ${r.tokenizerId ?: "unknown"})\n")
        r.outcomeDetail?.let { sb.append("  note: $it\n") }
        sb.append("  device: ${r.device.deviceModel ?: "unknown"}, Android ${r.device.androidRelease ?: "unknown"}")
        sb.append(" (API ${r.device.sdkInt ?: "unknown"}), ABI ${r.device.abi ?: "unknown"}\n")
        sb.append("  cpu: ${r.device.cpuFeatures ?: "unknown"}\n")
        sb.append("  time: ${r.startedAtMillis} -> ${r.endedAtMillis} (epoch ms)\n")
        sb.append("  thermal: ${level(r.thermalBefore)} -> ${level(r.thermalAfter)}; battery: ${pct(r.batteryPercentBefore)} -> ${pct(r.batteryPercentAfter)}\n")
        sb.append("  memory PSS MB: before load ${mb(r.pssKbBeforeLoad)}, after load ${mb(r.pssKbAfterLoad)}, after generation ${mb(r.pssKbAfterGeneration)}\n")
        sb.append("  load: ${ms(r.loadMillis)}\n")
        r.summaries().forEach { s ->
            sb.append("  [${s.promptId}] prompt tokens ${s.promptTokens ?: "unknown"}\n")
            sb.append("    ttft ms      ${stat(s.ttftMillis)}\n")
            sb.append("    prefill t/s  ${stat(s.prefillTokensPerSec)}\n")
            sb.append("    decode t/s   ${stat(s.decodeTokensPerSec)}\n")
        }
        r.notes.forEach { sb.append("  unknown/note: $it\n") }
        return sb.toString()
    }

    /** "median 12.3 (p10 11.0, p90 13.1, min 10.0, max 14.0, n=3, unknown 0, drift -4.2%)". */
    fun stat(s: StatSummary): String {
        if (s.known == 0) return "unknown (n=0, unknown ${s.unknown})"
        return "median ${num(s.median)} (p10 ${num(s.p10)}, p90 ${num(s.p90)}, min ${num(s.min)}, max ${num(s.max)}, " +
            "n=${s.known}, unknown ${s.unknown}, drift ${s.driftPercent?.let { num(it) + "%" } ?: "unknown"})"
    }

    fun num(v: Double?): String = v?.let { String.format(Locale.US, "%.2f", it) } ?: "unknown"
    fun ms(v: Double?): String = v?.let { String.format(Locale.US, "%.0f ms", it) } ?: "unknown"
    fun level(l: ThermalLevel?): String = l?.name ?: "unknown"
    fun pct(p: Int?): String = p?.let { "$it%" } ?: "unknown"
    fun mb(kb: Long?): String = kb?.let { String.format(Locale.US, "%.0f", it / 1024.0) } ?: "unknown"
}
