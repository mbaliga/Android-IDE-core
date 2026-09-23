package dev.aarso.data.runtime

import dev.aarso.contracts.execution.ExecutionTargetType
import dev.aarso.domain.runtime.RuntimeAvailability
import dev.aarso.domain.runtime.RuntimeCapability
import dev.aarso.domain.runtime.RuntimeKind
import dev.aarso.domain.runtime.RuntimeLocality
import dev.aarso.domain.runtime.RuntimeProviderProfile

data class WindowsCompatProbe(
    val wineAvailable: Boolean,
    val wineVersion: String?,
    val x64Translator: String?,
) {
    val canRunX64: Boolean get() = !x64Translator.isNullOrBlank()
}

/**
 * Optional Windows compatibility backend over the already-authorized Termux runtime.
 *
 * Fonebrew does not install Wine/Box64/FEX itself in this pass. The ecosystem is still moving and
 * Wine packages may come from user-selected repositories. We probe what is genuinely runnable and
 * only publish WINDOWS_COMPAT when Wine responds successfully.
 */
class TermuxWindowsCompatBridge(
    private val termux: TermuxRunCommandBridge,
) {
    suspend fun probe(): Pair<WindowsCompatProbe, RuntimeProviderProfile> {
        if (termux.installationState() != RuntimeAvailability.READY) {
            val probe = WindowsCompatProbe(false, null, null)
            return probe to setupProfile(termux.setupHint())
        }

        val result = runCatching {
            termux.run(
                executable = "\$PREFIX/bin/sh",
                args = listOf(
                    "-lc",
                    """
WINE_BIN="$(command -v wine64 || command -v wine || true)"
if [ -n "$WINE_BIN" ]; then
  printf 'wine=1\n'
  "$WINE_BIN" --version 2>/dev/null | head -n 1 | sed 's/^/wine_version=/'
fi
if command -v box64 >/dev/null 2>&1; then
  printf 'translator=box64\n'
elif command -v FEXInterpreter >/dev/null 2>&1; then
  printf 'translator=fex\n'
fi
""".trimIndent(),
                ),
                timeoutMs = 30_000,
            )
        }.getOrElse {
            val probe = WindowsCompatProbe(false, null, null)
            return probe to setupProfile("Windows compatibility probe failed: " + (it.message ?: "unknown error"))
        }

        val values = result.stdout.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
        val wineAvailable = values["wine"] == "1" && result.exitCode == 0
        val probe = WindowsCompatProbe(
            wineAvailable = wineAvailable,
            wineVersion = values["wine_version"],
            x64Translator = values["translator"],
        )

        return if (wineAvailable) {
            probe to RuntimeProviderProfile(
                providerId = PROVIDER_ID,
                executionTargetType = ExecutionTargetType.LOCAL_ANDROID,
                kinds = setOf(RuntimeKind.WINDOWS_COMPAT),
                capabilities = setOf(
                    RuntimeCapability.WINDOWS_API,
                    RuntimeCapability.FILE_TRANSFER,
                    RuntimeCapability.NETWORK,
                ),
                locality = RuntimeLocality.ON_DEVICE,
                availability = RuntimeAvailability.READY,
                architectures = buildSet {
                    add("windows")
                    if (probe.canRunX64) add("x86_64-windows")
                },
                setupHint = if (probe.canRunX64) null
                    else "Wine is available, but no x64 translation backend was detected.",
            )
        } else {
            probe to setupProfile("No working Wine executable was detected in the local Linux runtime.")
        }
    }

    suspend fun runExecutable(
        executablePath: String,
        args: List<String> = emptyList(),
        workDirectory: String = "~/",
        timeoutMs: Long = 120_000,
    ): TermuxCommandResult {
        require(executablePath.isNotBlank()) { "Windows executable path must be non-blank." }
        return termux.run(
            executable = "\$PREFIX/bin/sh",
            args = listOf(
                "-lc",
                "WINE_BIN=\"$(command -v wine64 || command -v wine)\"; exec \"$WINE_BIN\" \"$@\"" ,
                "_",
                executablePath,
            ) + args,
            workDir = workDirectory,
            timeoutMs = timeoutMs,
        )
    }

    private fun setupProfile(hint: String): RuntimeProviderProfile = RuntimeProviderProfile(
        providerId = PROVIDER_ID,
        executionTargetType = ExecutionTargetType.LOCAL_ANDROID,
        kinds = setOf(RuntimeKind.WINDOWS_COMPAT),
        capabilities = emptySet(),
        locality = RuntimeLocality.ON_DEVICE,
        availability = RuntimeAvailability.NEEDS_SETUP,
        architectures = emptySet(),
        setupHint = hint,
    )

    companion object {
        const val PROVIDER_ID = "termux-windows-compat"
    }
}
