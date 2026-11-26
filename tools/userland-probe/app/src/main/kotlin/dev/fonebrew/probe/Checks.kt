package dev.fonebrew.probe

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.StatFs
import java.io.File

/** Android-side check runners. Each returns a [CheckResult]; none throws, none touches the network. */
class Checks(private val ctx: Context) {
    private val nativeDir: String get() = ctx.applicationInfo.nativeLibraryDir

    fun header(): List<Pair<String, String>> = listOf(
        "Report time (device clock)" to java.util.Date().toString(),
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
        "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}",
        "Build fingerprint" to Build.FINGERPRINT,
        "Kernel" to read("/proc/version").trim().ifEmpty { "UNKNOWN (unreadable)" },
        "SELinux context" to (read("/proc/self/attr/current").let { SelinuxContext.clean(it) }.ifEmpty { "UNKNOWN (unreadable)" }),
        "targetSdk of this probe" to "${ctx.applicationInfo.targetSdkVersion}",
        "ABIs" to Build.SUPPORTED_ABIS.joinToString(),
        "CPU features" to (CpuInfo.features(read("/proc/cpuinfo")) ?: "UNKNOWN (no Features line)"),
        "uid" to "${android.os.Process.myUid()}",
        "nativeLibraryDir" to nativeDir,
        "filesDir" to ctx.filesDir.absolutePath,
    )

    fun all(): List<CheckResult> {
        val loadErr = Native.ensureLoaded()
        return listOf(
            execFromNativeDir(), execFromFilesDir(), mmapAndDlopen(loadErr), ptrace(loadErr),
            forkLimits(), virtualization(loadErr), storage(), bridge(),
        )
    }

    fun execFromNativeDir(): CheckResult {
        val f = File(nativeDir, "libprobe_exec.so")
        if (!f.exists()) return CheckResult("ii", "exec from nativeLibraryDir", Status.UNKNOWN,
            "${f.path} does not exist (packaging did not extract it; nothing was tried)")
        val (s, d) = ExecClassifier.classify(Exec.run(listOf(f.path), 5000))
        return CheckResult("ii", "exec from nativeLibraryDir (ProcessBuilder)", s, d)
    }

    private fun copyToFiles(name: String, from: File): File {
        val dest = File(ctx.filesDir, name)
        dest.delete()
        from.copyTo(dest, overwrite = true)
        return dest
    }

    fun execFromFilesDir(): CheckResult {
        val src = File(nativeDir, "libprobe_exec.so")
        if (!src.exists()) return CheckResult("iii", "exec from filesDir", Status.UNKNOWN, "source executable missing")
        val dest = try { copyToFiles("probe_exec_copy", src).also { it.setExecutable(true, true) } }
        catch (t: Throwable) { return CheckResult("iii", "exec from filesDir", Status.UNKNOWN, "copy failed: $t") }
        val (s, d) = ExecClassifier.classify(Exec.run(listOf(dest.path), 5000))
        return CheckResult("iii", "exec from filesDir after chmod +x (the targetSdk>=29 W^X question)", s, d)
    }

    fun mmapAndDlopen(loadErr: String?): CheckResult {
        val parts = mutableListOf<String>()
        var worst = Status.PASS
        fun note(s: Status, line: String) { parts += "[${s.glyph} ${s.name}] $line"; if (s == Status.FAIL) worst = Status.FAIL else if (s == Status.UNKNOWN && worst == Status.PASS) worst = Status.UNKNOWN }
        val dl = File(nativeDir, "libprobe_dl.so")
        if (!dl.exists()) return CheckResult("iv", "mmap-exec and dlopen from filesDir", Status.UNKNOWN, "libprobe_dl.so missing from nativeLibraryDir")
        val copy = try { copyToFiles("libprobe_dl_copy.so", dl) } catch (t: Throwable) { return CheckResult("iv", "mmap-exec and dlopen from filesDir", Status.UNKNOWN, "copy failed: $t") }
        if (loadErr != null) note(Status.UNKNOWN, "native helper not loadable ($loadErr), mmap check skipped")
        else { val (s, d) = NativeVerdict.parse(Native.mmapExec(copy.path)); note(s, "mmap PROT_EXEC of the filesDir copy: $d") }
        try {
            System.load(copy.path)
            val n = DlProbe.answer()
            note(if (n == 42) Status.PASS else Status.FAIL, "System.load of the filesDir copy, then call: returned $n")
        } catch (t: Throwable) { note(Status.FAIL, "System.load of the filesDir copy: $t") }
        return CheckResult("iv", "mmap-exec and dlopen of a shared library in filesDir", worst, parts.joinToString("\n"))
    }

    fun ptrace(loadErr: String?): CheckResult {
        if (loadErr != null) return CheckResult("v", "ptrace feasibility", Status.UNKNOWN, "native helper not loadable: $loadErr")
        val (s, d) = NativeVerdict.parse(Native.ptraceProbe())
        return CheckResult("v", "ptrace: TRACEME + SYSCALL stepping + process_vm_readv in a forked child", s, d + "\n" + Native.pvrErrno())
    }

    fun forkLimits(requested: Int = 40, dwellSeconds: Int = 10): CheckResult {
        ProbeService.resetLatch()
        try { ctx.startForegroundService(Intent(ctx, ProbeService::class.java)) } catch (t: Throwable) {
            return CheckResult("vi", "fork limits under a foreground service", Status.UNKNOWN, "could not start the foreground service: $t")
        }
        val fg = ProbeService.awaitStart(5000)
        val svcNote = ProbeService.startError
        val procs = mutableListOf<java.lang.Process>()
        var failure: String? = null
        try {
            for (i in 0 until requested) {
                try { procs += ProcessBuilder("/system/bin/sleep", "${dwellSeconds + 5}").start() }
                catch (t: Throwable) { failure = "child ${i + 1}: $t"; break }
            }
            Thread.sleep(dwellSeconds * 1000L)
            val importance = importanceName()
            val dead = procs.filter { !it.isAlive }.map { it.exitValue() }
            val s = ForkSurvey(requested, procs.size, failure, procs.size - dead.size, dead, dwellSeconds, fg, importance)
            val (st, d) = s.verdict()
            return CheckResult("vi", "fork limits: $requested children, survival after ${dwellSeconds}s under a foreground service", st,
                d + (svcNote?.let { "\nforeground service start error: $it" } ?: "") +
                    "\nTip for a background test: press Home right after tapping Run; appImportanceAtEnd then reflects it.")
        } finally {
            procs.forEach { it.destroyForcibly() }
            ctx.startService(Intent(ctx, ProbeService::class.java).setAction(ProbeService.ACTION_STOP))
        }
    }

    private fun importanceName(): String {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return when (info.importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
            else -> "OTHER(${info.importance})"
        }
    }

    fun virtualization(loadErr: String?): CheckResult {
        val lines = mutableListOf<String>()
        val kvm = File("/dev/kvm")
        lines += "/dev/kvm: exists=${kvm.exists()} readable=${kvm.canRead()} writable=${kvm.canWrite()}"
        val feature = ctx.packageManager.hasSystemFeature("android.software.virtualization_framework")
        lines += "system feature android.software.virtualization_framework: $feature"
        val terminal = terminalPackages()
        lines += "Linux Terminal app installed: ${if (terminal.isEmpty()) "none of the candidate packages found" else terminal.joinToString()}"
        lines += "user namespace files: /proc/self/ns/user exists=${File("/proc/self/ns/user").exists()}, " +
            "max_user_namespaces=${read("/proc/sys/user/max_user_namespaces").trim().ifEmpty { "unreadable" }}"
        lines += if (loadErr != null) "unshare(CLONE_NEWUSER): UNKNOWN (native helper not loadable: $loadErr)"
        else "unshare(CLONE_NEWUSER): ${Native.unshareUserNs()}"
        // PASS only if there is something a VM route could use; the individual facts are the real output.
        val s = if (feature && kvm.exists()) Status.PASS else if (feature || kvm.exists() || terminal.isNotEmpty()) Status.UNKNOWN else Status.FAIL
        return CheckResult("vii", "user namespaces, /dev/kvm, AVF feature flag, Linux Terminal app", s,
            lines.joinToString("\n") + "\nThe feature flag and the app being present do not prove a VM can be started; that needs the owner to open the Terminal app.")
    }

    private fun terminalPackages(): List<String> {
        // Candidate package names are from secondary memory, not verified: the check lists whichever exist.
        val candidates = listOf("com.android.virtualization.terminal", "com.google.android.virtualization.terminal")
        return candidates.filter { pkg ->
            try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Throwable) { false }
        }
    }

    fun storage(): CheckResult = try {
        val (s, d) = StorageVerdict.verdict(StatFs(ctx.filesDir.absolutePath).availableBytes)
        CheckResult("viii", "free storage for a ~1 GB rootfs", s, d)
    } catch (t: Throwable) { CheckResult("viii", "free storage for a ~1 GB rootfs", Status.UNKNOWN, "$t") }

    /** Check (ix): shell started by the app calls back into the app over a filesystem unix socket. */
    fun bridge(): CheckResult {
        val sock = File(ctx.filesDir, "probe-bridge.sock")
        sock.delete()
        val token = "t" + System.nanoTime().toString(36)
        val bound = LocalSocket()
        val server = try {
            bound.bind(LocalSocketAddress(sock.path, LocalSocketAddress.Namespace.FILESYSTEM))
            LocalServerSocket(bound.fileDescriptor)
        } catch (t: Throwable) { runCatching { bound.close() }; return CheckResult("ix", "local-socket command bridge", Status.FAIL, "could not bind ${sock.path}: $t") }
        val answered = StringBuilder()
        val acceptor = Thread {
            try {
                val c = server.accept()
                val line = c.inputStream.bufferedReader().readLine().orEmpty()
                answered.append(line)
                c.outputStream.write((BridgeProtocol.reply(line) + "\n").toByteArray()); c.outputStream.flush(); c.close()
            } catch (_: Throwable) {}
        }.also { it.isDaemon = true; it.start() }
        val notes = mutableListOf<String>()
        val nc = Exec.run(listOf("/system/bin/sh", "-c", "command -v nc"), 3000)
        notes += "nc in PATH: ${nc.output.trim().ifEmpty { "not found" }}"
        // Shell FUNCTION calling back (the "no userland" bridge idea): f() wraps nc -U.
        val script = "f() { printf 'PING %s\\n' \"\$1\" | nc -U '${sock.path}'; }; f $token"
        val r = Exec.run(listOf("/system/bin/sh", "-c", script), 5000)
        acceptor.join(1000)
        runCatching { server.close(); bound.close() }
        sock.delete()
        val ok = r.output.lines().any { it.trim() == "PONG $token" }
        notes += "nc -U variant: exit=${r.exit} timedOut=${r.timedOut} thrown=${r.thrown} output=${r.output.trim()} (app received: '${answered}')"
        val tcp = Exec.run(listOf("/system/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/1 2>&1; echo rc=\$?"), 3000)
        notes += "/dev/tcp in the system shell (informational; no TCP server is opened because the probe declares no INTERNET permission): ${tcp.output.trim().take(200)}"
        return CheckResult("ix", "local-socket command bridge (shell function -> nc -U -> app)", if (ok) Status.PASS else Status.FAIL, notes.joinToString("\n"))
    }

    private fun read(path: String): String = try { File(path).readText() } catch (t: Throwable) { "" }
}
