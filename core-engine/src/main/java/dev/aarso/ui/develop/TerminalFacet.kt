package dev.aarso.ui.develop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aarso.AarsoApp
import dev.aarso.domain.runtime.RuntimeAvailability
import kotlinx.coroutines.launch

/**
 * Develop → Terminal. Commands can run either in the on-phone Linux userspace (Termux bridge)
 * or on a trusted SSH machine. Output is always shown verbatim as a watched object.
 */
@Composable
fun TerminalFacet() {
    val container = (LocalContext.current.applicationContext as AarsoApp).container
    val repo = container.deviceRepo
    val store = container.remoteHostStore
    val hosts by store.hosts.collectAsState()
    val termuxProfile by container.runtimeProfileRegistry.termuxProfile.collectAsState()
    val scope = rememberCoroutineScope()

    var localSelected by remember { mutableStateOf(true) }
    var selectedRemote by remember { mutableStateOf(hosts.firstOrNull()?.alias) }
    var cmd by remember { mutableStateOf("uname -a") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }

    fun identityFor(host: dev.aarso.domain.remote.RemoteHost): dev.aarso.domain.remote.Identity {
        val ref = store.hostSecret(host.alias)
        return when {
            ref == null -> dev.aarso.domain.remote.Identity.Agent
            ref.isKey -> dev.aarso.domain.remote.Identity.PublicKey(ref.id)
            else -> dev.aarso.domain.remote.Identity.Password(ref.id)
        }
    }

    Text("Terminal", style = MaterialTheme.typography.titleSmall)
    Hint("Run locally on this phone or on one of your trusted machines. Output is shown verbatim.")
    Spacer(Modifier.height(8.dp))

    Text("Runtime", style = MaterialTheme.typography.labelMedium)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WireButton("This phone", selected = localSelected, onClick = { localSelected = true })
        hosts.forEach { host ->
            WireButton(
                host.alias,
                selected = !localSelected && selectedRemote == host.alias,
                onClick = {
                    localSelected = false
                    selectedRemote = host.alias
                },
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    if (localSelected) {
        WireBox {
            Text("Linux userspace", style = MaterialTheme.typography.labelMedium)
            val readiness = when (termuxProfile.availability) {
                RuntimeAvailability.READY -> "ready"
                RuntimeAvailability.NEEDS_SETUP -> "needs setup"
                RuntimeAvailability.UNSUPPORTED -> "unsupported"
            }
            Text(
                "Termux bridge · $readiness",
                style = MaterialTheme.typography.bodySmall,
            )
            if (termuxProfile.capabilities.isNotEmpty()) {
                Text(
                    termuxProfile.capabilities.joinToString(" · ") { it.name.lowercase() },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            termuxProfile.setupHint?.let { Hint(it) }
            Spacer(Modifier.height(6.dp))
            WireButton(
                if (probing) "Probing…" else "Probe toolchain",
                enabled = !probing && !running,
            ) {
                probing = true
                scope.launch {
                    val profile = container.runtimeProfileRegistry.refreshTermux()
                    output += "\n[runtime probe: ${profile.availability.name.lowercase()}]\n"
                    probing = false
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    } else if (hosts.isEmpty()) {
        Hint("No SSH machine connected. Add one in Settings → Global → your machines.")
        Spacer(Modifier.height(8.dp))
    }

    OutlinedTextField(
        cmd,
        { cmd = it },
        label = { Text("Shell command") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    WireButton(
        if (running) "Running…" else "Run",
        enabled = !running && cmd.isNotBlank(),
    ) {
        val command = cmd.trim()
        running = true
        scope.launch {
            output += (if (output.isEmpty()) "" else "\n") + "$ " + command + "\n"
            if (localSelected) {
                runCatching {
                    container.termuxRuntimeBridge.run(
                        executable = "\$PREFIX/bin/sh",
                        args = listOf("-lc", command),
                    )
                }.fold(
                    onSuccess = { result ->
                        if (result.stdout.isNotBlank()) output += result.stdout
                        if (result.stderr.isNotBlank()) output += result.stderr
                        output += "\n[exit ${result.exitCode}]\n"
                    },
                    onFailure = { error ->
                        output += "failed: ${error.message}\n"
                    },
                )
            } else {
                val host = hosts.firstOrNull { it.alias == selectedRemote }
                if (host == null) {
                    output += "failed: no remote machine selected\n"
                } else {
                    runCatching {
                        val recipe = dev.aarso.domain.device.recipe.DeviceRecipes.shell(
                            dev.aarso.domain.device.DeployTarget.Remote(host),
                            command,
                        )
                        repo.exec(host, identityFor(host), store.knownHosts.value, recipe, { output += it }).fold(
                            { code -> output += "\n[exit $code]\n" },
                            { output += "\nfailed: ${it.message} — is the machine trusted? connect once in Settings → Global.\n" },
                        )
                    }.onFailure { output += "\nfailed: ${it.message}\n" }
                }
            }
            running = false
        }
    }
    Spacer(Modifier.height(8.dp))

    WireBox {
        Text("Output", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            if (output.isEmpty()) "(no output yet)" else output,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 320.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
    Spacer(Modifier.height(6.dp))
    Hint(if (localSelected) "⌂ watched — runs in the on-phone Linux userspace." else "☁ watched — runs over SSH.")
}
