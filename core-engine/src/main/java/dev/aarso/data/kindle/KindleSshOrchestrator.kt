package dev.aarso.data.kindle

import android.content.ContentResolver
import android.net.Uri
import dev.aarso.domain.kindle.KindleDeviceState
import dev.aarso.domain.kindle.KindleFeatureState
import dev.aarso.domain.kindle.KindleFirmwareVersion
import dev.aarso.domain.kindle.KindleNetworkState
import dev.aarso.domain.kindle.KindleUsbState
import dev.aarso.domain.remote.ExecRequest
import dev.aarso.domain.remote.HostKey
import dev.aarso.domain.remote.Identity
import dev.aarso.domain.remote.KnownHosts
import dev.aarso.domain.remote.RemoteHost
import dev.aarso.domain.remote.RemoteSessionDriver
import dev.aarso.domain.remote.RemoteTransport
import dev.aarso.domain.remote.SftpOp
import dev.aarso.domain.remote.Trust
import java.security.MessageDigest

data class KindleSshProbeReceipt(
    val deviceState: KindleDeviceState,
    val rawEvidence: Map<String, String>,
    val sha256: String,
)

class KindleSshOrchestrator(private val newTransport: () -> RemoteTransport) {
    /** Connects only far enough to show the host key. No credentials are sent. */
    suspend fun presentedHostKey(host: RemoteHost): HostKey {
        val transport = newTransport()
        return try { transport.connect(host) } finally { runCatching { transport.close() } }
    }

    suspend fun probe(
        host: RemoteHost,
        identity: Identity,
        knownHosts: KnownHosts,
        profileId: String?,
        model: String?,
        serialPrefix: String?,
    ): KindleSshProbeReceipt {
        val driver = RemoteSessionDriver(newTransport(), knownHosts)
        try {
            driver.open(host, identity) { it is Trust.Vetted }
            val output = StringBuilder()
            val command = """
printf 'firmware='; (grep -Eo '[0-9]+(\.[0-9]+){1,7}' /etc/prettyversion.txt 2>/dev/null | head -n1 || true)
test -x /var/local/kmc/bin/gandalf && echo jailbreak=1 || echo jailbreak=0
test -x /var/local/kmc/sbin/kpm.sh && echo kpm=1 || echo kpm=0
ota_services_running=0
(status ota-update 2>/dev/null; status otaupd 2>/dev/null; status otav3 2>/dev/null) | grep -q 'start/running' && ota_services_running=1
ota_durable_block=0
test -d /mnt/us/update.bin.tmp.partial && ota_durable_block=1
test ! -x /usr/bin/otaupd -a -e /usr/bin/otaupd.bck && ota_durable_block=1
test ! -x /usr/bin/otav3 -a -e /usr/bin/otav3.bck && ota_durable_block=1
test "${'$'}ota_services_running" = 0 -a "${'$'}ota_durable_block" = 1 && echo ota_protected=1 || echo ota_protected=0
test -x /mnt/us/extensions/workdeck/bin/workdeck-client && echo workdeck=1 || echo workdeck=0
""".trimIndent()
            val result = driver.exec(ExecRequest(command)) { output.append(String(it.bytes)) }
            check(result.exitCode == 0) { "Kindle state probe exited ${result.exitCode}." }
            val evidence = output.lineSequence().mapNotNull { line ->
                line.trim().split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            val state = KindleDeviceState(
                profileId = profileId,
                model = model,
                serialPrefix = serialPrefix,
                firmware = evidence["firmware"]?.takeIf(String::isNotBlank)?.let {
                    runCatching { KindleFirmwareVersion.parse(it) }.getOrNull()
                },
                usb = KindleUsbState.DISCONNECTED,
                network = KindleNetworkState.HOTSPOT_PAIRED,
                jailbreak = evidence.state("jailbreak"),
                kpmHomebrew = evidence.state("kpm"),
                ssh = KindleFeatureState.PRESENT,
                otaProtection = evidence.state("ota_protected"),
                workdeckClient = evidence.state("workdeck"),
            )
            val canonical = evidence.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
            return KindleSshProbeReceipt(state, evidence, canonical.sha256())
        } finally {
            runCatching { driver.close() }
        }
    }

    suspend fun installWorkdeckClient(
        host: RemoteHost,
        identity: Identity,
        knownHosts: KnownHosts,
        resolver: ContentResolver,
        binaryUri: Uri,
        expectedSha256: String,
        expectedSizeBytes: Long,
        pairingSecretBase64: String,
        phoneAddress: String,
        port: Int,
    ) {
        require(expectedSha256.matches(Regex("^[a-fA-F0-9]{64}$")))
        require(expectedSizeBytes in 1..MAX_CLIENT_BYTES.toLong())
        require(pairingSecretBase64.isNotBlank() && HOST.matches(phoneAddress) && port in 1..65535)
        val bytes = resolver.openInputStream(binaryUri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count)
                require(total <= MAX_CLIENT_BYTES) { "Workdeck client package is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Unable to read Workdeck client handle.")
        check(bytes.size.toLong() == expectedSizeBytes) { "Workdeck client size does not match its manifest." }
        check(bytes.sha256() == expectedSha256.lowercase()) { "Workdeck client SHA-256 mismatch." }

        val driver = RemoteSessionDriver(newTransport(), knownHosts)
        try {
            driver.open(host, identity) { it is Trust.Vetted }
            // Open a second already-vetted transport for SFTP because RemoteSessionDriver exposes exec only.
            val transfer = newTransport()
            try {
                transfer.connect(host).also { presented ->
                    require(knownHosts.classify(host, presented) is Trust.Vetted) { "Kindle SSH host key changed." }
                }
                transfer.authenticate(identity)
                transfer.sftp(SftpOp.Mkdir("/mnt/us/extensions/workdeck/bin"))
                transfer.sftp(SftpOp.PutData("/mnt/us/extensions/workdeck/bin/workdeck-client", bytes))
                transfer.sftp(SftpOp.PutData("/mnt/us/extensions/workdeck/bin/workdeck.conf", buildString {
                    append("PHONE_HOST=").append(phoneAddress).append('\n')
                    append("PHONE_PORT=").append(port).append('\n')
                    append("PAIRING_SECRET=").append(pairingSecretBase64).append('\n')
                }.toByteArray()))
                transfer.sftp(SftpOp.PutData("/mnt/us/extensions/workdeck/bin/start.sh", START_SCRIPT.toByteArray()))
                transfer.sftp(SftpOp.PutData("/mnt/us/extensions/workdeck/bin/stop.sh", STOP_SCRIPT.toByteArray()))
                transfer.sftp(SftpOp.PutData("/mnt/us/extensions/workdeck/menu.json", MENU_JSON.toByteArray()))
            } finally {
                runCatching { transfer.close() }
            }
            val result = driver.exec(ExecRequest(
                "chmod 700 /mnt/us/extensions/workdeck/bin/workdeck-client /mnt/us/extensions/workdeck/bin/*.sh && " +
                    "/mnt/us/extensions/workdeck/bin/start.sh",
            )) { }
            check(result.exitCode == 0) { "Workdeck client failed to start (${result.exitCode})." }
        } finally {
            runCatching { driver.close() }
        }
    }

    private fun Map<String, String>.state(key: String): KindleFeatureState =
        if (this[key] == "1") KindleFeatureState.PRESENT else KindleFeatureState.ABSENT

    private fun String.sha256(): String = toByteArray().sha256()
    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_CLIENT_BYTES = 16 * 1024 * 1024
        val HOST = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
        val START_SCRIPT = """#!/bin/sh
DIR="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)"
exec "${'$'}DIR/workdeck-client" --daemon --config "${'$'}DIR/workdeck.conf"
"""
        val STOP_SCRIPT = """#!/bin/sh
DIR="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)"
exec "${'$'}DIR/workdeck-client" --stop
"""
        val MENU_JSON = """{"items":[{"name":"Start Workdeck","priority":0,"action":"bin/start.sh"},{"name":"Stop Workdeck","priority":1,"action":"bin/stop.sh"}]}"""
    }
}
