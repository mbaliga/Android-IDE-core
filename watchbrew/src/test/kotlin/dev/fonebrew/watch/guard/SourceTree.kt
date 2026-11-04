package dev.fonebrew.watch.guard

import org.junit.Assert.assertNotNull
import java.io.File

/** Locates the module's files regardless of whether Gradle or an IDE started the test from the module or the repo root. */
internal object SourceTree {
    val moduleDir: File = run {
        val dir = listOf(File("."), File("watchbrew")).firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        assertNotNull("cannot locate the watchbrew module directory from ${File(".").absolutePath}", dir)
        dir!!.canonicalFile
    }

    val repoRoot: File = moduleDir.parentFile

    fun file(relativeToModule: String) = File(moduleDir, relativeToModule)

    fun repoFile(relativeToRepo: String) = File(repoRoot, relativeToRepo)

    /** All `.kt` files under `src/main`, optionally restricted to a sub-package directory. */
    fun mainKotlin(subDir: String = ""): List<File> =
        File(moduleDir, "src/main/kotlin/dev/fonebrew/watch/$subDir").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** A file's lines with `//` and block-comment/KDoc text removed, so rules can name forbidden things in prose. */
    fun codeOnly(file: File): String {
        val noBlock = file.readText().replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        return noBlock.lines().joinToString("\n") { it.substringBefore("//") }
    }

    /**
     * The version catalog's `[libraries]` as `libs.` accessor to `group:name` (dashes become dots, as in
     * Gradle's generated accessors), so a test can resolve what `implementation(libs.x.y)` really is.
     */
    fun catalogLibraries(): Map<String, String> {
        val text = repoFile("gradle/libs.versions.toml").readText().substringAfter("[libraries]").substringBefore("[plugins]")
        return Regex("""^([\w-]+)\s*=\s*\{\s*group\s*=\s*"([^"]+)"\s*,\s*name\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .findAll(text)
            .associate { "libs." + it.groupValues[1].replace('-', '.') to "${it.groupValues[2]}:${it.groupValues[3]}" }
    }

    /** Every dependency coordinate the module's build file declares, whether spelled out or via a `libs.` alias. */
    fun declaredCoordinates(buildCode: String): List<String> {
        val catalog = catalogLibraries()
        val fromAliases = Regex("""\blibs\.(?!plugins\b|versions\b)[\w.]+""").findAll(buildCode).map { match ->
            val alias = match.value
            catalog[alias] ?: throw AssertionError("build.gradle.kts references $alias, which is not in the version catalog")
        }.toList()
        val literal = Regex("""["']([\w.-]+:[\w.-]+)(?::[\w.+-]+)?["']""").findAll(buildCode).map { it.groupValues[1] }.toList()
        return fromAliases + literal
    }
}
