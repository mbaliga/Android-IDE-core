package dev.fonebrew.domain.workspace.ignore

import dev.fonebrew.domain.workspace.roots.WorkspaceRoot
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Builds the [IgnoreMatcher] a root's settings ask for. */
object RootIgnore {
    /** A `.gitignore` bigger than this is not read (it is a mistake or an attack, not a rule list). */
    const val MAX_IGNORE_BYTES = 1L * 1024 * 1024

    fun matcherFor(root: WorkspaceRoot, realRoot: Path = Path.of(root.path)): IgnoreMatcher = IgnoreMatcher(
        source = IgnoreSource { dir ->
            if (!root.settings.useGitignore) return@IgnoreSource null
            val f = if (dir.isEmpty()) realRoot.resolve(".gitignore") else realRoot.resolve(dir).resolve(".gitignore")
            // a symlinked .gitignore could read a file from outside the root: only plain files count
            if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && Files.size(f) <= MAX_IGNORE_BYTES) {
                String(Files.readAllBytes(f), Charsets.UTF_8)
            } else null
        },
        defaults = if (root.settings.useDefaultIgnores) DefaultIgnores.PATTERNS else emptyList(),
        userExcludes = root.settings.excludes,
        useGitignoreFiles = root.settings.useGitignore,
    )
}
