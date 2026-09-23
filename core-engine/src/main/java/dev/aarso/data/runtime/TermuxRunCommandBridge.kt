package dev.aarso.data.runtime

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.aarso.domain.runtime.RuntimeAvailability
import dev.aarso.domain.runtime.RuntimeCapability
import dev.aarso.domain.runtime.RuntimeKind
import dev.aarso.domain.runtime.RuntimeLocality
import dev.aarso.domain.runtime.RuntimeProviderProfile
import dev.aarso.contracts.execution.ExecutionTargetType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

data class TermuxCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val internalErrorCode: Int,
    val internalErrorMessage: String,
)

class TermuxRunCommandBridge(private val context: Context) {

    fun installationState(): RuntimeAvailability {
        val installed = runCatching {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        }.isSuccess
        if (!installed) return RuntimeAvailability.NEEDS_SETUP

        val permission = ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION)
        return if (permission == PackageManager.PERMISSION_GRANTED) RuntimeAvailability.READY
        else RuntimeAvailability.NEEDS_SETUP
    }

    suspend fun run(
        executable: String,
        args: List<String> = emptyList(),
        workDir: String = "~/",
        stdin: String? = null,
        timeoutMs: Long = 120_000,
    ): TermuxCommandResult {
        check(installationState() == RuntimeAvailability.READY) {
            "Termux is not ready. Install Termux, enable allow-external-apps=true, and grant Fonebrew Run commands in Termux."
        }

        val requestId = TermuxResultService.nextRequestId()
        val deferred = CompletableDeferred<TermuxCommandResult>()
        TermuxResultService.register(requestId, deferred)

        val resultIntent = Intent(context, TermuxResultService::class.java).apply {
            putExtra(TermuxResultService.EXTRA_REQUEST_ID, requestId)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getService(context, requestId, resultIntent, flags)

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            action = TERMUX_RUN_COMMAND_ACTION
            putExtra(EXTRA_COMMAND_PATH, executable)
            putExtra(EXTRA_ARGUMENTS, args.toTypedArray())
            putExtra(EXTRA_WORKDIR, workDir)
            putExtra(EXTRA_BACKGROUND, true)
            stdin?.let { putExtra(EXTRA_STDIN, it) }
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            putExtra(EXTRA_COMMAND_LABEL, "Fonebrew runtime")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Command requested by Fonebrew's execution fabric.")
        }

        try {
            context.startService(intent)
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            TermuxResultService.unregister(requestId)
        }
    }

    suspend fun provisionCoreDevelopmentToolchain(): TermuxCommandResult =
        run(
            executable = "\$PREFIX/bin/pkg",
            args = listOf(
                "install", "-y",
                "git", "openjdk-17", "gradle", "python", "nodejs",
                "clang", "cmake", "ninja",
            ),
            timeoutMs = 20 * 60 * 1000L,
        )

    suspend fun provisionBrowserTestToolchain(): TermuxCommandResult {
        val repo = run(
            executable = "\$PREFIX/bin/pkg",
            args = listOf("install", "-y", "x11-repo"),
            timeoutMs = 5 * 60 * 1000L,
        )
        if (repo.exitCode != 0 || repo.internalErrorCode != Activity.RESULT_OK) return repo
        return run(
            executable = "\$PREFIX/bin/pkg",
            args = listOf("install", "-y", "chromium"),
            timeoutMs = 20 * 60 * 1000L,
        )
    }

    suspend fun probeProfile(): RuntimeProviderProfile {
        if (installationState() != RuntimeAvailability.READY) {
            return setupProfile()
        }

        val probe = runCatching {
            run(
                executable = "\$PREFIX/bin/sh",
                args = listOf(
                    "-lc",
                    listOf(
                        "printf 'shell=1\\n'",
                        "command -v git >/dev/null 2>&1 && printf 'git=1\\n'",
                        "command -v java >/dev/null 2>&1 && printf 'jvm=1\\n'",
                        "command -v gradle >/dev/null 2>&1 && printf 'gradle=1\\n'",
                        "command -v python >/dev/null 2>&1 && printf 'python=1\\n'",
                        "command -v node >/dev/null 2>&1 && printf 'node=1\\n'",
                        "(command -v clang >/dev/null 2>&1 || command -v gcc >/dev/null 2>&1) && printf 'native=1\\n'",
                        "(command -v chromium >/dev/null 2>&1 || command -v chromium-browser >/dev/null 2>&1) && printf 'browser=1\\n'",
                        "(command -v chromedriver >/dev/null 2>&1 || command -v geckodriver >/dev/null 2>&1) && printf 'webdriver=1\\n'",
                    ).joinToString("; "),
                ),
                timeoutMs = 30_000,
            )
        }.getOrElse {
            return setupProfile("Termux is installed but Fonebrew could not execute a probe. Check allow-external-apps=true and Android permission.")
        }

        if (probe.internalErrorCode != Activity.RESULT_OK || probe.exitCode != 0) {
            return setupProfile("Termux probe failed: " + probe.internalErrorMessage.ifBlank { probe.stderr })
        }

        val flags = probe.stdout.lineSequence()
            .mapNotNull { line ->
                val p = line.trim().split("=", limit = 2)
                if (p.size == 2 && p[1] == "1") p[0] else null
            }.toSet()

        val capabilities = linkedSetOf(
            RuntimeCapability.SHELL,
            RuntimeCapability.FILE_TRANSFER,
            RuntimeCapability.NETWORK,
        )
        if ("git" in flags) capabilities += RuntimeCapability.GIT
        if ("jvm" in flags) capabilities += RuntimeCapability.JVM
        if ("gradle" in flags) capabilities += RuntimeCapability.GRADLE
        if ("python" in flags) capabilities += RuntimeCapability.PYTHON
        if ("node" in flags) capabilities += RuntimeCapability.NODE
        if ("native" in flags) capabilities += RuntimeCapability.NATIVE_TOOLCHAIN
        if ("browser" in flags) capabilities += RuntimeCapability.BROWSER_HEADLESS
        if ("browser" in flags && "webdriver" in flags) capabilities += RuntimeCapability.SELENIUM

        val kinds = linkedSetOf(RuntimeKind.LINUX_USERSPACE)
        if (RuntimeCapability.JVM in capabilities) kinds += RuntimeKind.JVM
        if (RuntimeCapability.BROWSER_HEADLESS in capabilities) kinds += RuntimeKind.BROWSER

        return RuntimeProviderProfile(
            providerId = PROVIDER_ID,
            executionTargetType = ExecutionTargetType.LOCAL_ANDROID,
            kinds = kinds,
            capabilities = capabilities,
            locality = RuntimeLocality.ON_DEVICE,
            availability = RuntimeAvailability.READY,
            architectures = setOf("aarch64", "arm64-v8a"),
        )
    }

    fun setupProfile(hint: String? = null): RuntimeProviderProfile = RuntimeProviderProfile(
        providerId = PROVIDER_ID,
        executionTargetType = ExecutionTargetType.LOCAL_ANDROID,
        kinds = setOf(RuntimeKind.LINUX_USERSPACE, RuntimeKind.JVM, RuntimeKind.BROWSER),
        capabilities = emptySet(),
        locality = RuntimeLocality.ON_DEVICE,
        availability = RuntimeAvailability.NEEDS_SETUP,
        architectures = setOf("aarch64", "arm64-v8a"),
        setupHint = hint ?: "Install Termux, set allow-external-apps=true, then grant Fonebrew the Run commands in Termux permission.",
    )

    companion object {
        const val PROVIDER_ID = "termux-linux"
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    }
}
