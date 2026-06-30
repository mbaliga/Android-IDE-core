package dev.aarso.domain.device.recipe

import dev.aarso.domain.device.DeployTarget
import dev.aarso.domain.device.Fqbn
import dev.aarso.domain.device.SerialPort
import dev.aarso.domain.remote.ExecRequest

/**
 * Recipes turn a deploy intent into concrete remote-exec commands ([ExecRequest]) the SSH spine
 * runs on the far machine (docs/build-plan.md, Sprint 3). They are pure command builders — no
 * I/O — so the exact command is testable and *visible* to the user before it runs (legibility:
 * you can read what will execute on your Pi). Shell-quoting is conservative (single quotes) so
 * paths with spaces are safe.
 */
object DeviceRecipes {

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // ---- Raspberry Pi / generic remote ----

    /** Run a script/interpreter on a [DeployTarget.Remote], e.g. `python3 app.py`. */
    fun run(target: DeployTarget.Remote, interpreter: String, scriptPath: String, args: List<String> = emptyList()): ExecRequest {
        val tail = if (args.isEmpty()) "" else " " + args.joinToString(" ") { q(it) }
        return ExecRequest("$interpreter ${q(scriptPath)}$tail")
    }

    /** A bare shell command on a remote (kept explicit so callers don't hand-roll [ExecRequest]). */
    fun shell(target: DeployTarget.Remote, command: String): ExecRequest = ExecRequest(command)

    // ---- Arduino via arduino-cli on a remote host (v1: delegate-to-Pi) ----

    /** Compile a sketch directory for the target board. */
    fun compile(target: DeployTarget.Arduino, sketchPath: String): ExecRequest =
        ExecRequest("arduino-cli compile --fqbn ${q(target.fqbn.value)} ${q(sketchPath)}")

    /** Upload a compiled sketch to the board on the remote's serial port. */
    fun upload(target: DeployTarget.Arduino, sketchPath: String): ExecRequest =
        ExecRequest(
            "arduino-cli upload -p ${q(target.port.path)} --fqbn ${q(target.fqbn.value)} ${q(sketchPath)}",
        )

    /** Compile then upload in one shell invocation (`&&` so upload only runs on a clean build). */
    fun compileAndUpload(target: DeployTarget.Arduino, sketchPath: String): ExecRequest =
        ExecRequest(compile(target, sketchPath).command + " && " + upload(target, sketchPath).command)

    /** List boards arduino-cli can see on the remote (for port/FQBN discovery). */
    fun listBoards(): ExecRequest = ExecRequest("arduino-cli board list")

    // ---- ESP over-the-air (espota) ----

    /**
     * Push a firmware binary to an ESP device over the network from the remote host using
     * espota.py. [deviceIp] is the ESP's address; [binPath] is the firmware on the remote.
     */
    fun espOta(deviceIp: String, binPath: String, espotaPath: String = "espota.py", port: Int = 3232): ExecRequest {
        require(deviceIp.isNotBlank()) { "empty device ip" }
        return ExecRequest("python3 ${q(espotaPath)} -i ${q(deviceIp)} -p $port -f ${q(binPath)}")
    }
}
