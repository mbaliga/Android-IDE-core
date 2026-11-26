package dev.fonebrew.domain.agent

/**
 * The plain-text tool protocol. A model asks for a tool with one block per call:
 *
 * ```
 * <<<TOOL name>>>
 * key: value
 * ---            (only for a tool that takes a body)
 * raw body
 * <<<END>>>
 * ```
 *
 * [ToolCallParser] only splits blocks into [RawCall]s; [ToolRegistry] validates them against the
 * registered [ToolSpec]s. Neither ever throws on model output. Pure.
 */
data class RawCall(val index: Int, val name: String, val args: Map<String, String>, val body: String?)

/** Why a block could not become a call. [message] is short and goes back to the model verbatim. */
data class ToolError(val index: Int, val tool: String?, val message: String)

data class ParsedTurn(val calls: List<RawCall>, val errors: List<ToolError>) {
    val isEmpty: Boolean get() = calls.isEmpty() && errors.isEmpty()
}

object ToolCallParser {
    const val OPEN = "<<<TOOL"
    const val END = "<<<END>>>"
    private val HEADER = Regex("^<<<TOOL\\s+([A-Za-z0-9_]{1,40})\\s*>>>\\s*$")
    private val KEY = Regex("^([A-Za-z_][A-Za-z0-9_]{0,30})\\s*:\\s?(.*)$")

    fun parse(modelText: String): ParsedTurn {
        val lines = modelText.replace("\r\n", "\n").split('\n')
        val calls = mutableListOf<RawCall>()
        val errors = mutableListOf<ToolError>()
        var i = 0
        var block = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (!line.startsWith(OPEN)) { i++; continue }
            val idx = block++
            val m = HEADER.matchEntire(line)
            // Find the terminator even for a bad header so one bad block cannot swallow the next.
            var j = i + 1
            while (j < lines.size && lines[j].trim() != END) {
                if (lines[j].trim().startsWith(OPEN)) break
                j++
            }
            val closed = j < lines.size && lines[j].trim() == END
            if (m == null) {
                errors += ToolError(idx, null, "malformed block header; write <<<TOOL name>>>")
                i = if (closed) j + 1 else j
                continue
            }
            val name = m.groupValues[1]
            if (!closed) {
                errors += ToolError(idx, name, "block for $name was never closed with $END (reply truncated?); nothing was run")
                i = j
                continue
            }
            val inner = lines.subList(i + 1, j)
            val sep = inner.indexOfFirst { it.trim() == "---" }
            val head = if (sep >= 0) inner.subList(0, sep) else inner
            val body = if (sep >= 0) inner.subList(sep + 1, inner.size).joinToString("\n") else null
            val args = LinkedHashMap<String, String>()
            var bad: String? = null
            for (h in head) {
                if (h.isBlank()) continue
                val km = KEY.matchEntire(h)
                if (km == null) { bad = "argument line is not 'key: value': ${h.take(60)}"; break }
                val key = km.groupValues[1]
                if (key in args) { bad = "argument '$key' given twice"; break }
                args[key] = km.groupValues[2].trim()
            }
            if (bad != null) errors += ToolError(idx, name, bad) else calls += RawCall(idx, name, args, body)
            i = j + 1
        }
        return ParsedTurn(calls, errors)
    }
}

/** What a tool may do. Declared per tool; the consent policy is per class. */
enum class SideEffect { READ_LOCAL, WRITE_LOCAL, NETWORK, DESTRUCTIVE }

enum class ParamType { TEXT, INT }

data class ParamSpec(val name: String, val type: ParamType = ParamType.TEXT, val required: Boolean = true, val doc: String = "")

/** Validated arguments handed to a tool. */
data class ToolArgs(val text: Map<String, String>, val ints: Map<String, Int>, val body: String?) {
    fun text(name: String): String = text[name].orEmpty()
    fun int(name: String): Int? = ints[name]
}

/** A tool's result. [ok] false = a tool error fed back to the model. [data] is the untrusted payload. */
data class ToolResult(val ok: Boolean, val summary: String, val data: String = "") {
    companion object {
        fun ok(summary: String, data: String = "") = ToolResult(true, summary, data)
        fun error(message: String) = ToolResult(false, message, message)
    }
}

class ToolSpec(
    val name: String,
    val effect: SideEffect,
    val doc: String,
    val params: List<ParamSpec> = emptyList(),
    val takesBody: Boolean = false,
    /** Ends the run after this call (finish). */
    val terminal: Boolean = false,
    val execute: suspend (ToolArgs) -> ToolResult,
)

/** Per-class consent: what the loop does before running a tool of that class. */
enum class Consent { AUTO, PER_CALL, DENY }

data class ConsentPolicy(
    val readLocal: Consent = Consent.AUTO,
    val writeLocal: Consent = Consent.PER_CALL,
    val network: Consent = Consent.PER_CALL,
    val destructive: Consent = Consent.DENY,
) {
    fun forEffect(e: SideEffect): Consent = when (e) {
        SideEffect.READ_LOCAL -> readLocal
        SideEffect.WRITE_LOCAL -> writeLocal
        SideEffect.NETWORK -> network
        SideEffect.DESTRUCTIVE -> destructive
    }
}

data class ConsentRequest(val tool: String, val effect: SideEffect, val argsSummary: String)

/** Asked once per call for a [Consent.PER_CALL] tool. The default refuses everything. */
fun interface ConsentGate {
    suspend fun approve(request: ConsentRequest): Boolean

    companion object { val DENY_ALL = ConsentGate { false } }
}

class ToolRegistry {
    private val specs = LinkedHashMap<String, ToolSpec>()

    fun register(spec: ToolSpec): ToolRegistry {
        require(Regex("[A-Za-z0-9_]{1,40}").matches(spec.name)) { "bad tool name" }
        require(spec.name !in specs) { "tool already registered: ${spec.name}" }
        specs[spec.name] = spec
        return this
    }

    fun get(name: String): ToolSpec? = specs[name]
    val all: List<ToolSpec> get() = specs.values.toList()

    sealed interface Validated {
        data class Ok(val spec: ToolSpec, val args: ToolArgs) : Validated
        data class Bad(val message: String) : Validated
    }

    /** Never throws: unknown tools, missing/unknown/ill-typed arguments and body mismatches are [Validated.Bad]. */
    fun validate(call: RawCall): Validated {
        val spec = specs[call.name]
            ?: return Validated.Bad("unknown tool '${call.name}'; available: ${specs.keys.joinToString(", ")}")
        val known = spec.params.map { it.name }.toSet()
        call.args.keys.firstOrNull { it !in known }?.let {
            return Validated.Bad("${spec.name}: unknown argument '$it'")
        }
        val text = LinkedHashMap<String, String>()
        val ints = LinkedHashMap<String, Int>()
        for (p in spec.params) {
            val v = call.args[p.name]
            if (v == null || v.isEmpty()) {
                if (p.required) return Validated.Bad("${spec.name}: missing argument '${p.name}'")
                continue
            }
            when (p.type) {
                ParamType.TEXT -> text[p.name] = v
                ParamType.INT -> ints[p.name] = v.toIntOrNull()?.takeIf { it in 0..10_000_000 }
                    ?: return Validated.Bad("${spec.name}: '${p.name}' must be a whole number")
            }
        }
        if (spec.takesBody && call.body == null) return Validated.Bad("${spec.name}: missing '---' line and body")
        if (!spec.takesBody && call.body != null) return Validated.Bad("${spec.name}: takes no body")
        return Validated.Ok(spec, ToolArgs(text, ints, call.body))
    }

    /** The tool list for the system prompt. */
    fun describe(): String = all.joinToString("\n") { s ->
        val ps = s.params.joinToString(", ") { it.name + if (it.required) "" else "?" } + if (s.takesBody) ", body" else ""
        "- ${s.name}($ps): ${s.doc}"
    }
}
