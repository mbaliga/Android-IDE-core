package dev.aarso.domain.device

import dev.aarso.domain.remote.RemoteHost

/**
 * Hardware targets on the remote spine (docs/build-plan.md, Sprint 3): the phone is a real
 * computer that pushes code to *other* machines and to microcontrollers. Everything here is a
 * pure model that produces remote-exec recipes ([dev.aarso.domain.device.recipe]) — the actual
 * compiling/flashing happens on a machine the phone reaches over SSH (Sprint 1). The compiler/
 * flasher's own output is shown verbatim and parsed legibly; we never synthesise a "success"
 * word (THE LAW). On-device USB-OTG flashing (v2) is device-gated and deliberately out of scope.
 *
 * Pure Kotlin; JVM-tested.
 */

/**
 * A board's fully-qualified name, e.g. `arduino:avr:uno` (packager:arch:board). Validated so a
 * malformed FQBN is caught before a recipe is built, not at the far end of an SSH round-trip.
 */
@JvmInline
value class Fqbn(val value: String) {
    init {
        val parts = value.split(":")
        require(parts.size >= 3 && parts.all { it.isNotBlank() }) {
            "FQBN must be packager:arch:board, was '$value'"
        }
    }
}

/** A serial device path on the *remote* host, e.g. `/dev/ttyACM0` or `/dev/ttyUSB0`. */
@JvmInline
value class SerialPort(val path: String) {
    init { require(path.isNotBlank()) { "empty serial port" } }
}

/**
 * Where a deploy lands. A [Remote] is any SSH-reachable box (a Raspberry Pi, a Dell). An
 * [Arduino] is a microcontroller reached **through** a remote host that runs `arduino-cli`
 * (the v1 "delegate-to-Pi" path): the phone never touches the board directly.
 */
sealed interface DeployTarget {
    val name: String

    /** A Raspberry Pi / Linux box reached directly over SSH. */
    data class Remote(val host: RemoteHost) : DeployTarget {
        override val name: String get() = host.alias
    }

    /** A board flashed by `arduino-cli` running on [via]; [port] is on that host. */
    data class Arduino(
        val via: RemoteHost,
        val fqbn: Fqbn,
        val port: SerialPort,
        val label: String = "arduino",
    ) : DeployTarget {
        override val name: String get() = label
    }
}
