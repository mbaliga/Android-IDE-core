package dev.aarso.domain.codelens

import dev.aarso.domain.council.Generator

/**
 * Translates a window of code lines into plain English — the domain primitive for
 * the Lens overlay (the "filter screen" the user drags over a file to understand
 * what's beneath it without reading code). Pure: no Android, no network, no model
 * wiring. The caller picks the [Generator] (on-device model or a watched cloud
 * provider surfaced as such).
 *
 * Design intent: the explanation must be readable by a non-technical user in ~2–3
 * seconds. Short. No jargon. No code fragments in the output. If the lines are
 * blank or whitespace only, we skip the round-trip and return null.
 */
object CodeLens {

    /** Maximum lines sent to the model — keeps the prompt tight and fast. */
    const val MAX_LINES = 30

    /**
     * Explain [lines] (the code currently under the lens) in plain English.
     * [fileExt] (e.g. "kt", "py", "ts") gives the model a language hint.
     * Returns null for blank/empty windows (nothing to explain).
     */
    suspend fun explain(
        lines: List<String>,
        fileExt: String,
        generator: Generator,
    ): String? {
        val trimmed = lines.map { it.trimEnd() }.filter { it.isNotBlank() }
        if (trimmed.isEmpty()) return null
        val capped = if (trimmed.size > MAX_LINES) trimmed.take(MAX_LINES) else trimmed
        val snippet = capped.joinToString("\n")
        val language = languageLabel(fileExt)
        return generator.complete(
            system = systemPrompt(language),
            user = snippet,
        ).trim().ifBlank { null }
    }

    internal fun systemPrompt(language: String): String =
        "You explain $language code to someone who is not a programmer. " +
        "When shown a snippet, describe in 2–3 short, plain sentences what it DOES and WHY it matters — " +
        "no jargon, no code, no bullet points. If the snippet is just imports or boilerplate with no " +
        "meaningful behaviour, say so in one sentence. Never reproduce the code."

    internal fun languageLabel(ext: String): String = when (ext.lowercase().trimStart('.')) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "py" -> "Python"
        "ts", "tsx" -> "TypeScript"
        "js", "jsx" -> "JavaScript"
        "swift" -> "Swift"
        "go" -> "Go"
        "rs" -> "Rust"
        "cpp", "cc", "cxx" -> "C++"
        "c" -> "C"
        "cs" -> "C#"
        "rb" -> "Ruby"
        "sh", "bash" -> "shell script"
        "yaml", "yml" -> "YAML configuration"
        "json" -> "JSON data"
        "xml" -> "XML"
        "sql" -> "SQL"
        "md" -> "Markdown"
        "gradle" -> "Gradle build script"
        else -> "code"
    }
}
