package dev.fonebrew.domain.execution

/**
 * Sensible starting points for the Run panel's command field -- never a guess that runs on its
 * own, always an editable suggestion. Pure Kotlin: [detect] takes a plain file-name list (the
 * caller already fetched it, e.g. via `GitBrowse.list`) and returns a best-effort command, or
 * null when nothing recognizable is present so the field starts blank rather than wrong.
 */
object RunCommandPresets {

    /** Root manifest file -> the test command its ecosystem conventionally uses. Checked in
     *  this order; a repo matching several just takes the first. Deliberately small and boring
     *  -- this is a *starting point* the user edits, not a build-system detector. */
    private val MANIFEST_COMMANDS: List<Pair<String, String>> = listOf(
        "gradlew" to "./gradlew test",
        "package.json" to "npm test",
        "pyproject.toml" to "pytest",
        "Cargo.toml" to "cargo test",
        "go.mod" to "go test ./...",
        "Makefile" to "make test",
    )

    /** Best-effort guess at a repo's test command from its root file listing. */
    fun detect(rootFileNames: Collection<String>): String? =
        MANIFEST_COMMANDS.firstOrNull { (manifest, _) -> manifest in rootFileNames }?.second

    /** Shell-command targets ([RunTarget.Local]/[RunTarget.Ssh]) start here when nothing was detected. */
    const val DEFAULT_SHELL_COMMAND: String = "./gradlew test"

    /**
     * A CI target's "command" field is not a shell command at all --
     * [CiActionsExecutionProvider]'s own doc comment: `operation.command` there IS the workflow
     * file name. GitHub's own scaffolding convention names the primary workflow this way; still
     * fully editable, never assumed correct for a given repo.
     */
    const val DEFAULT_CI_WORKFLOW: String = "ci.yml"
}
