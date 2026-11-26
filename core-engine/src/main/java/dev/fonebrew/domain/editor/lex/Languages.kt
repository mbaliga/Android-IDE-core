package dev.fonebrew.domain.editor.lex

import dev.fonebrew.domain.editor.edit.CommentSyntax

/** A language the editor knows: how to lex it and how it comments. */
class Language(
    val id: String,
    val displayName: String,
    val lexer: LineLexer,
    val comments: CommentSyntax,
    val extensions: Set<String> = emptySet(),
    val fileNames: Set<String> = emptySet(),
)

/** Lexer for unknown file types: every line is plain text, no state. */
object PlainLexer : LineLexer {
    override val id = "plain"
    override fun lexLine(line: String, start: LexState, sink: TokenSink): LexState = LexState.INITIAL
}

object Languages {
    val PLAIN = Language("plain", "Plain text", PlainLexer, CommentSyntax.NONE)

    val ALL: List<Language> = listOf(
        Language("kotlin", "Kotlin", CLikeLexer(CLikeLanguages.KOTLIN), CommentSyntax.SLASH, setOf("kt", "kts")),
        Language("java", "Java", CLikeLexer(CLikeLanguages.JAVA), CommentSyntax.SLASH, setOf("java")),
        Language("javascript", "JavaScript / TypeScript", CLikeLexer(CLikeLanguages.JAVASCRIPT), CommentSyntax.SLASH,
            setOf("js", "mjs", "cjs", "jsx", "ts", "tsx", "mts", "cts")),
        Language("python", "Python", CLikeLexer(CLikeLanguages.PYTHON), CommentSyntax.HASH, setOf("py", "pyi", "pyw")),
        Language("json", "JSON", JsonLexer(), CommentSyntax.NONE, setOf("json", "jsonc", "geojson", "webmanifest")),
        Language("yaml", "YAML", YamlLexer(), CommentSyntax.HASH, setOf("yml", "yaml")),
        Language("toml", "TOML", TomlLexer(), CommentSyntax.HASH, setOf("toml"), setOf("Cargo.lock")),
        Language("markdown", "Markdown", MarkdownLexer(), CommentSyntax.BLOCK_XML, setOf("md", "markdown")),
        Language("shell", "Shell", CLikeLexer(CLikeLanguages.SHELL), CommentSyntax.HASH,
            setOf("sh", "bash", "zsh", "ksh"), setOf(".bashrc", ".zshrc", ".profile", ".bash_profile")),
        Language("c", "C", CLikeLexer(CLikeLanguages.C), CommentSyntax.SLASH, setOf("c", "h")),
        Language("cpp", "C++", CLikeLexer(CLikeLanguages.CPP), CommentSyntax.SLASH, setOf("cpp", "cc", "cxx", "hpp", "hh", "hxx")),
        Language("rust", "Rust", CLikeLexer(CLikeLanguages.RUST), CommentSyntax.SLASH, setOf("rs")),
        Language("go", "Go", CLikeLexer(CLikeLanguages.GO), CommentSyntax.SLASH, setOf("go")),
        Language("markup", "HTML / XML", MarkupLexer(), CommentSyntax.BLOCK_XML,
            setOf("html", "htm", "xhtml", "xml", "svg", "xsd", "xsl", "plist")),
        Language("css", "CSS", CssLexer(), CommentSyntax.BLOCK_CSS, setOf("css")),
        Language("gradle", "Gradle", CLikeLexer(CLikeLanguages.GRADLE), CommentSyntax.SLASH, setOf("gradle")),
    )

    private val byId = ALL.associateBy { it.id }
    fun byId(id: String): Language = byId[id] ?: PLAIN

    /** Picks by file name (`.gradle.kts` before `.kts`); anything unknown is [PLAIN]. */
    fun forFileName(name: String): Language {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        if (base.endsWith(".gradle.kts") || base.endsWith(".gradle")) return byId("gradle")
        ALL.firstOrNull { base in it.fileNames }?.let { return it }
        val ext = base.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || base.startsWith(".") && base.count { it == '.' } == 1) return PLAIN
        return ALL.firstOrNull { ext in it.extensions } ?: PLAIN
    }
}
