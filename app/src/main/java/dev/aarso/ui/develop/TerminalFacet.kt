package dev.aarso.ui.develop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.launch

/**
 * The Develop room's **Terminal** tab — a plain shell prompt that runs one command at a time on
 * the user's homelab runner / machine over the SSH spine, mirroring [DevicesFacet]'s Raspberry-Pi
 * shell mode. The host's raw stdout/stderr is shown verbatim in a monospace pane (a **watched
 * object** — never paraphrased). Trust stays the user's: [dev.aarso.data.DeviceRepo.exec] only
 * proceeds on an already-pinned host (connect once in Settings). Owner-verified — there is no SSH
 * host in CI.
 */
@Composable
fun TerminalFacet() {
    val container = (LocalContext.current.applicationContext as AarsoApp).container
    val repo = container.deviceRepo
    val store = container.remoteHostStore
    val hosts by store.hosts.collectAsState()
    val scope = rememberCoroutineScope()

    if (hosts.isEmpty()) {
        Hint(
            "No machine connected. Add one in Settings → Global → your machines and connect once " +
                "to trust it — then run commands on it here.",
        )
        return
    }

    var selected by remember { mutableStateOf(hosts.first().alias) }
    var cmd by remember { mutableStateOf("uname -a") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    // Same identity resolution as DevicesFacet: a pinned key/password secret if present, else the
    // ssh-agent — the host's trust decision belongs to the Remote screen, not this action.
    fun identityFor(host: dev.aarso.domain.remote.RemoteHost): dev.aarso.domain.remote.Identity {
        val ref = store.hostSecret(host.alias)
        return when {
            ref == null -> dev.aarso.domain.remote.Identity.Agent
            ref.isKey -> dev.aarso.domain.remote.Identity.PublicKey(ref.id)
            else -> dev.aarso.domain.remote.Identity.Password(ref.id)
        }
    }

    Text("Terminal", style = MaterialTheme.typography.titleSmall)
    Hint("Run a shell command on your machine over SSH. Output is shown verbatim.")
    Spacer(Modifier.height(8.dp))

    Text("Machine", style = MaterialTheme.typography.labelMedium)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        hosts.forEach { h ->
            WireButton(h.alias, selected = h.alias == selected, onClick = { selected = h.alias })
        }
    }
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        cmd,
        { cmd = it },
        label = { Text("Shell command") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    WireButton(if (running) "Running…" else "Run", enabled = !running && cmd.isNotBlank(), onClick = {
        val host = hosts.firstOrNull { it.alias == selected } ?: return@WireButton
        val command = cmd.trim()
        running = true
        scope.launch {
            output += (if (output.isEmpty()) "" else "\n") + "$ $command\n"
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
            running = false
        }
    })
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
    Hint("☁ watched — runs on your machine over SSH; credentials stay in the Keystore.")
}
