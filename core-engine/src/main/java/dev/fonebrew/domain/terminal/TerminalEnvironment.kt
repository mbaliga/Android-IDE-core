package dev.fonebrew.domain.terminal

/** App-private locations a session's shell needs. Paths only; creating them is the data layer's job. */
data class SessionPaths(
    val home: String,
    val tmp: String,
    /** App-private bin directory (empty today; a later package puts command bridges here). */
    val bin: String,
    /** The generated rc file named by `ENV`. */
    val rcFile: String,
)

/**
 * The environment a terminal's shell runs in, built from an **allow-list** (never "inherit
 * everything, then remove"). What the child gets:
 *
 *  - set by us: `HOME`, `PATH` (`/system/bin:/system/xbin:<app bin>`), `TMPDIR`, `LANG=C.UTF-8`,
 *    `TERM=xterm-256color`, `COLORTERM=truecolor`, `ENV` (the generated rc), `SHELL`;
 *  - copied from the app process **only if present** and only from [PASS_THROUGH]: the
 *    `ANDROID_*` root/data/storage variables the platform's own tools need, `EXTERNAL_STORAGE`
 *    and `BOOTCLASSPATH`.
 *
 * Everything else the app process has (loader variables, proxies, anything a library or a
 * future feature exported) stays out. Secrets are never placed here: API keys live in the
 * Keystore and reach only their provider. Whether the pass-through set is enough for every
 * Android tool is owner-verified.
 */
object TerminalEnvironment {
    const val SHELL = "/system/bin/sh"
    const val TERM = "xterm-256color"

    val PASS_THROUGH: List<String> = listOf(
        "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_STORAGE", "ANDROID_RUNTIME_ROOT",
        "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "ANDROID_ASSETS",
        "EXTERNAL_STORAGE", "BOOTCLASSPATH",
    )

    /** The child's environment as an ordered map. [inherited] is the app process environment. */
    fun build(paths: SessionPaths, inherited: Map<String, String>): LinkedHashMap<String, String> {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = paths.home
        env["PATH"] = "/system/bin:/system/xbin:${paths.bin}"
        env["TMPDIR"] = paths.tmp
        env["LANG"] = "C.UTF-8"
        env["TERM"] = TERM
        env["COLORTERM"] = "truecolor"
        env["ENV"] = paths.rcFile
        env["SHELL"] = SHELL
        for (k in PASS_THROUGH) inherited[k]?.takeIf { it.isNotEmpty() }?.let { env[k] = it }
        return env
    }

    /** `KEY=VALUE` strings, the shape `execve` takes. */
    fun toEnvp(env: Map<String, String>): Array<String> = env.map { (k, v) -> "$k=$v" }.toTypedArray()

    const val EXTENSION_BEGIN = "# --- FONEBREW EXTENSION POINT (begin) ---"
    const val EXTENSION_END = "# --- FONEBREW EXTENSION POINT (end) ---"

    /**
     * The generated rc file for the Android shell (mksh): a prompt that shows the working
     * directory, a few safe aliases, and a clearly marked extension point. Nothing here
     * downloads or executes anything; the hook only *sources* shell fragments already present
     * under `$HOME/.fonebrew/rc.d` (app-private), which a later package will populate.
     */
    fun rcFileContent(): String = """
        |# Fonebrew terminal rc - GENERATED at session start. Edits to this file are overwritten;
        |# the extension point below is the supported place for additions.
        |#
        |# Prompt: working directory, then ${'$'}. (Kept to plain parameter expansion so it works in mksh.)
        |PS1='${'$'}PWD ${'$'} '
        |HISTFILE="${'$'}HOME/.sh_history"
        |HISTSIZE=1000
        |
        |# Safe aliases: read-only conveniences only (nothing here deletes or overwrites).
        |alias ll='ls -l'
        |alias la='ls -la'
        |alias ..='cd ..'
        |
        |$EXTENSION_BEGIN
        |# A later package adds command bridges here. Loader hook: every readable
        |# ${'$'}HOME/.fonebrew/rc.d/*.sh is sourced (sourcing reads shell text with the system
        |# shell; it never executes a downloaded binary - see docs/ratified/EXECUTION_CONTRACT.md 3.1).
        |if [ -d "${'$'}HOME/.fonebrew/rc.d" ]; then
        |    for f in "${'$'}HOME"/.fonebrew/rc.d/*.sh; do
        |        [ -r "${'$'}f" ] && . "${'$'}f"
        |    done
        |fi
        |$EXTENSION_END
        |""".trimMargin()
}
