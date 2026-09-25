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
import dev.aarso.domain.runtime.RuntimePackageVersion
import dev.aarso.domain.runtime.RuntimeProbeReceipt
import dev.aarso.contracts.execution.ExecutionTargetType
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

data class TermuxCommandResult(
    val requestId: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val internalErrorCode: Int,
    val internalErrorMessage: String,
    val stdoutOriginalLength: Long = stdout.length.toLong(),
    val stderrOriginalLength: Long = stderr.length.toLong(),
    val capturedViaFileChannel: Boolean = false,
) {
    val outputTruncated: Boolean
        get() = stdoutOriginalLength > stdout.length || stderrOriginalLength > stderr.length
}

class TermuxRunCommandBridge(private val context: Context) {

    private val resultStore = TermuxResultStore(context.applicationContext)
    private val healthProbe = AndroidRuntimeHealthProbe(context.applicationContext)

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
    }.isSuccess

    fun hasRunCommandPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun installationState(): RuntimeAvailability {
        if (!isInstalled()) return RuntimeAvailability.NEEDS_SETUP
        return if (hasRunCommandPermission()) RuntimeAvailability.READY
        else RuntimeAvailability.NEEDS_SETUP
    }

    fun setupHint(): String = when {
        !isInstalled() -> "Install Termux first."
        !hasRunCommandPermission() ->
            "Grant Fonebrew the Android 'Run commands in Termux environment' permission."
        else ->
            "In Termux set allow-external-apps=true in ~/.termux/termux.properties, then probe again."
    }

    suspend fun run(
        executable: String,
        args: List<String> = emptyList(),
        workDir: String = "~/",
        stdin: String? = null,
        timeoutMs: Long = 120_000,
        requestId: String = UUID.randomUUID().toString(),
    ): TermuxCommandResult {
        check(installationState() == RuntimeAvailability.READY) {
            "Termux is not ready. Install Termux, enable allow-external-apps=true, and grant Fonebrew Run commands in Termux."
        }

        require(requestId.isNotBlank() && requestId.length <= 256) { "Invalid Termux request id." }
        val deferred = CompletableDeferred<TermuxCommandResult>()
        TermuxResultService.register(requestId, deferred)

        val resultIntent = Intent(context, TermuxResultService::class.java).apply {
            putExtra(TermuxResultService.EXTRA_REQUEST_ID, requestId)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getService(context, requestId.hashCode(), resultIntent, flags)

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
            return withTimeout(timeoutMs) { deferred.await() }.also(resultStore::put)
        } finally {
            TermuxResultService.unregister(requestId)
        }
    }

    /**
     * Runs a command with stdout/stderr redirected inside Termux, then transfers bounded chunks
     * over the small PendingIntent channel. Command output itself never has to fit in an Intent.
     */
    suspend fun runFileBacked(
        executable: String,
        args: List<String> = emptyList(),
        workDir: String = "~/",
        stdin: String? = null,
        timeoutMs: Long = 120_000,
        maxCapturedBytesPerStream: Long = 8L * 1024L * 1024L,
        requestId: String = UUID.randomUUID().toString(),
    ): TermuxCommandResult {
        require(maxCapturedBytesPerStream in 1..64L * 1024L * 1024L)
        val channelId = requestId.sha256().take(40)
        val directory = "$RESULT_ROOT/$channelId"
        val stdoutPath = "$directory/stdout"
        val stderrPath = "$directory/stderr"
        val setup = run(
            executable = "\$PREFIX/bin/mkdir",
            args = listOf("-p", directory),
            timeoutMs = 30_000,
        )
        check(setup.exitCode == 0 && setup.internalErrorCode == Activity.RESULT_OK) {
            "Unable to create Termux output channel."
        }
        var finalized = false
        return try {
            val script = """
out="\$1"; err="\$2"; shift 2
"\$@" >"\$out" 2>"\$err"
status=\$?
printf 'exit_code=%s\nstdout_bytes=%s\nstderr_bytes=%s\n' "\$status" "\$(wc -c <"\$out")" "\$(wc -c <"\$err")"
exit 0
""".trimIndent()
            val wrapper = run(
                executable = "\$PREFIX/bin/sh",
                args = listOf("-c", script, "_", stdoutPath, stderrPath, executable) + args,
                workDir = workDir,
                stdin = stdin,
                timeoutMs = timeoutMs,
                requestId = requestId,
            )
            check(wrapper.internalErrorCode == Activity.RESULT_OK && wrapper.exitCode == 0) {
                "Termux output wrapper failed: " + wrapper.internalErrorMessage.ifBlank { wrapper.stderr }
            }
            val result = finalizeFileBacked(wrapper, stdoutPath, stderrPath, maxCapturedBytesPerStream)
            resultStore.put(result)
            finalized = true
            result
        } finally {
            // On timeout/process recreation the command may still complete, so retain its
            // deterministic channel for recoverFileBacked instead of racing it with deletion.
            if (finalized) {
                // Generated beneath a fixed private root; never accepts a caller-supplied deletion target.
                runCatching {
                    run("\$PREFIX/bin/rm", listOf("-rf", "--", directory), timeoutMs = 30_000)
                }
            }
        }
    }

    /** Last retained result lets UI/provider recovery report a finished command after process loss. */
    fun recoverResult(requestId: String): TermuxCommandResult? = resultStore.get(requestId)

    /**
     * Completes a file-backed result whose PendingIntent arrived while Android was recreating the
     * caller. Output paths are deterministic from requestId and remain until this recovery or the
     * stale-channel janitor removes them.
     */
    suspend fun recoverFileBacked(
        requestId: String,
        maxCapturedBytesPerStream: Long = 8L * 1024L * 1024L,
    ): TermuxCommandResult? {
        require(maxCapturedBytesPerStream in 1..64L * 1024L * 1024L)
        val retained = resultStore.get(requestId) ?: return null
        if (retained.capturedViaFileChannel) return retained
        val directory = "$RESULT_ROOT/${requestId.sha256().take(40)}"
        val recovered = runCatching {
            finalizeFileBacked(retained, "$directory/stdout", "$directory/stderr", maxCapturedBytesPerStream)
        }.getOrNull() ?: return null
        resultStore.put(recovered)
        runCatching { run("\$PREFIX/bin/rm", listOf("-rf", "--", directory), timeoutMs = 30_000) }
        return recovered
    }

    fun forgetResult(requestId: String) = resultStore.remove(requestId)

    suspend fun cleanupStaleOutputChannels(maxAgeHours: Int = 24): TermuxCommandResult {
        require(maxAgeHours in 1..(24 * 30))
        return run(
            executable = "\$PREFIX/bin/sh",
            args = listOf(
                "-c",
                "mkdir -p \"\$1\" && find \"\$1\" -mindepth 1 -maxdepth 1 -type d -mmin +\"\$2\" -exec rm -rf -- {} +",
                "_", RESULT_ROOT, (maxAgeHours * 60).toString(),
            ),
            timeoutMs = 60_000,
        )
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
        val chromium = run(
            executable = "\$PREFIX/bin/pkg",
            args = listOf("install", "-y", "python", "chromium"),
            timeoutMs = 20 * 60 * 1000L,
        )
        if (chromium.exitCode != 0 || chromium.internalErrorCode != Activity.RESULT_OK) return chromium
        return run(
            executable = "\$PREFIX/bin/python",
            args = listOf("-m", "pip", "install", "--upgrade", "selenium"),
            timeoutMs = 10 * 60 * 1000L,
        )
    }

    suspend fun probeRuntime(): Pair<RuntimeProviderProfile, RuntimeProbeReceipt?> {
        if (installationState() != RuntimeAvailability.READY) {
            return setupProfile() to null
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
                        "python -c 'import selenium' >/dev/null 2>&1 && printf 'selenium_client=1\\n'",
                    ).joinToString("; "),
                ),
                timeoutMs = 30_000,
            )
        }.getOrElse {
            return setupProfile("Termux is installed but Fonebrew could not execute a probe. Check allow-external-apps=true and Android permission.") to null
        }

        if (probe.internalErrorCode != Activity.RESULT_OK || probe.exitCode != 0) {
            return setupProfile("Termux probe failed: " + probe.internalErrorMessage.ifBlank { probe.stderr }) to null
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
        if ("browser" in flags && "webdriver" in flags && "selenium_client" in flags) {
            capabilities += RuntimeCapability.SELENIUM
        }

        val kinds = linkedSetOf(RuntimeKind.LINUX_USERSPACE)
        if (RuntimeCapability.JVM in capabilities) kinds += RuntimeKind.JVM
        if (RuntimeCapability.BROWSER_HEADLESS in capabilities) kinds += RuntimeKind.BROWSER

        val profile = RuntimeProviderProfile(
            providerId = PROVIDER_ID,
            executionTargetType = ExecutionTargetType.LOCAL_ANDROID,
            kinds = kinds,
            capabilities = capabilities,
            locality = RuntimeLocality.ON_DEVICE,
            availability = RuntimeAvailability.READY,
            architectures = setOf("aarch64", "arm64-v8a"),
        )
        val evidence = runCatching { probeEvidence() }.getOrNull()
        return profile to evidence
    }

    suspend fun probeProfile(): RuntimeProviderProfile = probeRuntime().first

    private suspend fun probeEvidence(): RuntimeProbeReceipt {
        val output = runFileBacked(
            executable = "\$PREFIX/bin/sh",
            args = listOf(
                "-lc",
                """
printf 'runtime_version='; uname -sr
printf 'architecture='; uname -m
printf 'free_bytes='; df -Pk "\$HOME" | awk 'NR==2 { print \$4 * 1024 }'
for tool in git java gradle python node clang cmake ninja chromium chromedriver; do
  if command -v "\$tool" >/dev/null 2>&1; then
    version="\$("\$tool" --version 2>&1 | head -n 1 | tr '\n\r=' '   ')"
    printf 'package.%s=%s\n' "\$tool" "\$version"
  fi
done
""".trimIndent(),
            ),
            timeoutMs = 60_000,
            maxCapturedBytesPerStream = 512 * 1024,
        )
        check(output.exitCode == 0 && output.internalErrorCode == Activity.RESULT_OK)
        val values = output.stdout.lineSequence().mapNotNull { line ->
            line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() }
        }.toMap()
        val health = healthProbe.inspect()
        return RuntimeProbeReceipt(
            providerId = PROVIDER_ID,
            observedAtUtc = Instant.now(),
            runtimeVersion = values.getValue("runtime_version"),
            architecture = values.getValue("architecture"),
            packageVersions = values.entries.filter { it.key.startsWith("package.") }
                .sortedBy { it.key }
                .map { RuntimePackageVersion(it.key.removePrefix("package."), it.value) },
            freeSpaceBytes = values.getValue("free_bytes").toLong(),
            batteryPercent = health.batteryPercent,
            charging = health.charging,
            thermalState = health.thermalState,
            fileOutputChannelVerified = output.capturedViaFileChannel,
            // Presence of sdkmanager/adb alone is not proof of a working ARM build toolchain.
            androidSdkVerified = false,
        )
    }

    private suspend fun readFileChannel(path: String, length: Long): String {
        if (length == 0L) return ""
        val bytes = java.io.ByteArrayOutputStream(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var offset = 0L
        val chunkBytes = 48 * 1024
        while (offset < length) {
            val count = minOf(chunkBytes.toLong(), length - offset).toInt()
            val chunk = run(
                executable = "\$PREFIX/bin/sh",
                args = listOf(
                    "-c", "dd if=\"\$1\" bs=1 skip=\"\$2\" count=\"\$3\" 2>/dev/null | base64 -w0",
                    "_", path, offset.toString(), count.toString(),
                ),
                timeoutMs = 60_000,
            )
            check(chunk.exitCode == 0 && chunk.internalErrorCode == Activity.RESULT_OK)
            bytes.write(Base64.getDecoder().decode(chunk.stdout.trim()))
            offset += count
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private suspend fun finalizeFileBacked(
        wrapper: TermuxCommandResult,
        stdoutPath: String,
        stderrPath: String,
        maxCapturedBytesPerStream: Long,
    ): TermuxCommandResult {
        check(wrapper.internalErrorCode == Activity.RESULT_OK && wrapper.exitCode == 0) {
            "Termux output wrapper failed: " + wrapper.internalErrorMessage.ifBlank { wrapper.stderr }
        }
        val metadata = wrapper.stdout.lineSequence().mapNotNull { line ->
            line.trim().split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val stdoutBytes = metadata.getValue("stdout_bytes").toLong()
        val stderrBytes = metadata.getValue("stderr_bytes").toLong()
        require(stdoutBytes >= 0 && stderrBytes >= 0) { "Invalid Termux output lengths." }
        return TermuxCommandResult(
            requestId = wrapper.requestId,
            exitCode = metadata.getValue("exit_code").toInt(),
            stdout = readFileChannel(stdoutPath, minOf(stdoutBytes, maxCapturedBytesPerStream)),
            stderr = readFileChannel(stderrPath, minOf(stderrBytes, maxCapturedBytesPerStream)),
            internalErrorCode = wrapper.internalErrorCode,
            internalErrorMessage = wrapper.internalErrorMessage,
            stdoutOriginalLength = stdoutBytes,
            stderrOriginalLength = stderrBytes,
            capturedViaFileChannel = true,
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
        setupHint = hint ?: setupHint(),
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
        private const val RESULT_ROOT = "/data/data/com.termux/files/home/.fonebrew/results"
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        .joinToString("") { "%02x".format(it) }
}
