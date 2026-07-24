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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aarso.FonebrewApp
import dev.aarso.data.remote.PtyShellSession
import dev.aarso.domain.remote.term.PtyChannel
import dev.aarso.ui.wire.WireField
import kotlinx.coroutines.launch

private const val THIS_PHONE = "This phone"

/**
 * The Develop room's **Terminal** tab. **This phone** is a real, always-available interactive
 * shell with a genuine controlling terminal — `/system/bin/sh` forked onto a real pty inside
 * this app's own sandbox (no root, no pairing, like Termux's shell but without its bundled
 * userland; see [PtyShellSession]) — alongside the existing
 * one-command-at-a-time SSH prompt for a paired homelab machine (mirrors [DevicesFacet]'s
 * Raspberry-Pi shell mode). Either way, the machine's raw stdout/stderr is shown verbatim (a
 * **watched object** for a remote host — never paraphrased). Owner-verified — there is no
 * device, and no SSH host, in CI.
 */
@Composable
fun TerminalFacet() {
    val context = LocalContext.current
    val container = (context.applicationContext as FonebrewApp).container
    val repo = container.deviceRepo
    val store = container.remoteHostStore
    val hosts by store.hosts.collectAsState()
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf(THIS_PHONE) }

    Text("Terminal", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))

    Text("Machine", style = MaterialTheme.typography.labelMedium)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WireButton(THIS_PHONE, selected = selected == THIS_PHONE, onClick = { selected = THIS_PHONE })
        hosts.forEach { h ->
            WireButton(h.alias, selected = h.alias == selected, onClick = { selected = h.alias })
        }
    }
    Spacer(Modifier.height(8.dp))

    if (selected == THIS_PHONE) {
        LocalTerminal(scope, context.filesDir)
        return
    }

    val host = hosts.firstOrNull { it.alias == selected }
    if (host == null) {
        Hint(
            "No machine connected. Add one in Settings → Global → your machines and connect once " +
                "to trust it — then run commands on it here.",
        )
        return
    }

    var cmd by remember { mutableStateOf("uname -a") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    // Same identity resolution as DevicesFacet: a pinned key/password secret if present, else the
    // ssh-agent — the host's trust decision belongs to the Remote screen, not this action.
    fun identityFor(h: dev.aarso.domain.remote.RemoteHost): dev.aarso.domain.remote.Identity {
        val ref = store.hostSecret(h.alias)
        return when {
            ref == null -> dev.aarso.domain.remote.Identity.Agent
            ref.isKey -> dev.aarso.domain.remote.Identity.PublicKey(ref.id)
            else -> dev.aarso.domain.remote.Identity.Password(ref.id)
        }
    }

    Hint("Run a shell command on your machine over SSH. Output is shown verbatim.")
    Spacer(Modifier.height(8.dp))

    dev.aarso.ui.hyle.HyleField(
        cmd,
        { cmd = it },
        label = "Shell command",
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    WireButton(if (running) "Running…" else "Run", enabled = !running && cmd.isNotBlank(), onClick = {
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

/**
 * A real interactive shell on this phone — same PTY→VtParser→ScreenBuffer core the SSH
 * interactive shell in `RemoteScreen.kt` uses (see [PtyChannel]), fed by a genuine pty via
 * [PtyShellSession] instead of an SSH channel. Because it's a real pty (not a bare pipe), the
 * kernel's own line discipline handles LF->CRLF translation, echo, and job-control signals —
 * no manual byte-munging needed here, unlike the ProcessBuilder-based first cut. Opens lazily
 * on first use and stays open across tab switches within this composition (closed when the
 * composable leaves it).
 */
@Composable
private fun LocalTerminal(scope: kotlinx.coroutines.CoroutineScope, filesDir: java.io.File) {
    val pty = remember { PtyChannel(rows = 24, cols = 80) }
    var shell by remember { mutableStateOf<PtyShellSession?>(null) }
    var screenVersion by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { scope.launch { runCatching { shell?.close() } } }
    }

    Hint("⌂ on-device — a real shell with a real terminal, in this app's own sandbox. No root, no pairing, no network.")
    Spacer(Modifier.height(8.dp))

    if (shell == null) {
        WireButton(if (opening) "Opening…" else "Open shell", enabled = !opening, onClick = {
            opening = true
            openError = null
            scope.launch {
                runCatching {
                    PtyShellSession.open(filesDir, rows = pty.screen.rows, cols = pty.screen.cols) { chunk ->
                        pty.onOutput(String(chunk.bytes, Charsets.UTF_8))
                        screenVersion++
                    }
                }.onSuccess { shell = it }.onFailure { openError = it.message ?: "couldn't open a shell" }
                opening = false
            }
        })
        openError?.let {
            Spacer(Modifier.height(6.dp))
            Hint("Couldn't open a shell: $it")
        }
        return
    }

    key(screenVersion) {
        dev.aarso.ui.remote.TerminalView(
            screen = pty.screen,
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
    Spacer(Modifier.height(8.dp))
    WireField("input (sent with Enter)", input, { input = it })
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WireButton("Send", enabled = input.isNotEmpty(), onClick = {
            val line = input; input = ""
            scope.launch { runCatching { shell?.send(line + "\n") } }
        })
        WireButton("Ctrl-C", onClick = { scope.launch { runCatching { shell?.send(3.toChar().toString()) } } })
        WireButton("Close shell", onClick = { scope.launch { runCatching { shell?.close() }; shell = null } })
    }
}
