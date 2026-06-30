package dev.aarso.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aarso.AarsoApp
import dev.aarso.domain.remote.ExecChunk
import dev.aarso.domain.remote.ExecRequest
import dev.aarso.domain.remote.Identity
import dev.aarso.domain.remote.RemoteHost
import dev.aarso.domain.remote.RemoteSessionDriver
import dev.aarso.domain.remote.SessionState
import dev.aarso.domain.remote.Trust
import dev.aarso.ui.wire.WireBox
import dev.aarso.ui.wire.WireButton
import dev.aarso.ui.wire.WireField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Wireframe **Remote** screen — the device-testable face of the remote-exec spine (Sprint 1).
 * Add an SSH host, connect (the **trust** decision is yours, shown with the real fingerprint),
 * run a command, and read the remote's raw output verbatim — it is a watched object, never our
 * paraphrase (THE LAW). Boxy/wireframe; the design system reskins later. Runtime is
 * owner-verified (no SSH server in CI).
 */
@Composable
fun RemoteScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val container = (LocalContext.current.applicationContext as AarsoApp).container
    val store = container.remoteHostStore
    val hosts by store.hosts.collectAsState()
    val scope = rememberCoroutineScope()

    // One live session driver, rebuilt per connection over a fresh sshj transport.
    var driver by remember { mutableStateOf<RemoteSessionDriver?>(null) }
    var phase by remember { mutableStateOf<SessionState>(SessionState.Disconnected) }
    var output by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("uname -a") }
    var error by remember { mutableStateOf<String?>(null) }

    // Trust prompt: connect suspends here until the user accepts/rejects a non-vetted key.
    var pendingTrust by remember { mutableStateOf<Trust?>(null) }
    var trustGate by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    // Interactive shell (Sprint 2 terminal made real): output flows reader-thread → channel →
    // main, where a VtParser folds it into a ScreenBuffer the UI renders.
    val pty = remember { dev.aarso.domain.remote.term.PtyChannel(rows = 24, cols = 80) }
    val shellOut = remember { kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED) }
    var shell by remember { mutableStateOf<dev.aarso.domain.remote.ShellSession?>(null) }
    var screenVersion by remember { mutableStateOf(0) }
    var shellInput by remember { mutableStateOf("") }

    LaunchedEffect(pty) {
        for (bytes in shellOut) { pty.onOutput(String(bytes)); screenVersion++ }
    }

    fun connect(host: RemoteHost, identity: Identity) {
        error = null; output = ""
        val d = RemoteSessionDriver(container.newSshTransport(), store.knownHosts.value)
        driver = d
        scope.launch {
            runCatching {
                d.open(host, identity) { verdict ->
                    val gate = CompletableDeferred<Boolean>()
                    pendingTrust = verdict; trustGate = gate
                    val ok = gate.await()
                    pendingTrust = null; trustGate = null
                    if (ok && verdict !is Trust.Vetted) {
                        // Persist the pin the driver just accepted so it's vetted next time.
                        val key = (verdict as? Trust.Unknown)?.presented
                            ?: (verdict as Trust.Changed).presented
                        store.pin(host.endpoint, key)
                    }
                    ok
                }
            }.onFailure { error = it.message }
            phase = d.state
        }
    }

    fun run() {
        val d = driver ?: return
        scope.launch {
            runCatching {
                d.exec(ExecRequest(command.trim())) { chunk ->
                    output += String(chunk.bytes)
                }
            }.onFailure { error = it.message }
            phase = d.state
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WireButton("‹ Close", onClick = onClose)
            Spacer(Modifier.width(12.dp))
            Text("Remote", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(4.dp))
        // State as material-ish line (wireframe placeholder; restyle to material later).
        Text("session: ${phaseLabel(phase)}", color = MaterialTheme.colorScheme.outline)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))

        AddHostForm(onAdd = { host, secretPlain, isKey ->
            store.upsert(host)
            if (secretPlain.isNotBlank()) store.setHostSecret(host.alias, secretPlain, isKey)
        })
        Spacer(Modifier.height(12.dp))

        Text("Saved hosts", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (hosts.isEmpty()) {
            Text("No remotes yet. Add one above.", color = MaterialTheme.colorScheme.outline)
        }
        for (host in hosts) {
            WireBox {
                Text("${host.alias}  —  ${host.username}@${host.endpoint}")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WireButton("Connect", onClick = {
                        val ref = store.hostSecret(host.alias)
                        val identity = when {
                            ref == null -> Identity.Agent
                            ref.isKey -> Identity.PublicKey(ref.id)
                            else -> Identity.Password(ref.id)
                        }
                        connect(host, identity)
                    })
                    WireButton("Forget", onClick = { store.remove(host.alias) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (phase is SessionState.Ready || phase is SessionState.Running) {
            Spacer(Modifier.height(12.dp))
            Text("Run a command", style = MaterialTheme.typography.titleMedium)
            WireField("command", command, { command = it })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WireButton("Run", onClick = ::run, enabled = phase is SessionState.Ready)
                WireButton("Disconnect", onClick = { scope.launch { driver?.close(); phase = driver?.state ?: SessionState.Disconnected } })
            }
        }

        if (output.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            // The remote's raw voice — verbatim, monospace, a watched object.
            WireBox(Modifier.heightIn(min = 80.dp)) {
                Text(output, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Interactive shell — a real terminal (PTY → VtParser → ScreenBuffer).
        if (phase is SessionState.Ready || phase is SessionState.Running) {
            Spacer(Modifier.height(12.dp))
            Text("Interactive shell", style = MaterialTheme.typography.titleMedium)
            if (shell == null) {
                WireButton("Open shell", onClick = {
                    scope.launch {
                        runCatching {
                            driver?.shell { chunk -> shellOut.trySend(chunk.bytes) }
                        }.onSuccess { shell = it }.onFailure { error = it.message }
                    }
                })
            } else {
                // The rendered screen — keyed on screenVersion so each VT update recomposes.
                key(screenVersion) {
                    WireBox(Modifier.heightIn(min = 120.dp)) {
                        val text = (0 until pty.screen.rows).joinToString("\n") { pty.screen.lineText(it) }.trimEnd('\n')
                        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                WireField("input (sent with Enter)", shellInput, { shellInput = it })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WireButton("Send", onClick = {
                        val line = shellInput; shellInput = ""
                        scope.launch { runCatching { shell?.send(line + "\n") }.onFailure { error = it.message } }
                    })
                    WireButton("Ctrl-C", onClick = { scope.launch { shell?.send(3.toChar().toString()) } })
                    WireButton("Close shell", onClick = {
                        scope.launch { runCatching { shell?.close() }; shell = null }
                    })
                }
            }
        }
    }

    // Trust dialog — the user's call, with the real fingerprint shown (never auto-accepted).
    pendingTrust?.let { verdict ->
        TrustDialog(
            verdict = verdict,
            onAccept = { trustGate?.complete(true) },
            onReject = { trustGate?.complete(false) },
        )
    }
}

@Composable
private fun AddHostForm(onAdd: (RemoteHost, String, Boolean) -> Unit) {
    var alias by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var isKey by remember { mutableStateOf(true) }

    WireBox {
        Text("Add host", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        WireField("alias", alias, { alias = it })
        WireField("hostname / ip", hostname, { hostname = it })
        WireField("port", port, { port = it.filter { c -> c.isDigit() } }, number = true)
        WireField("username", username, { username = it })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WireButton("Private key", onClick = { isKey = true }, selected = isKey)
            WireButton("Password", onClick = { isKey = false }, selected = !isKey)
        }
        WireField(if (isKey) "private key (PEM)" else "password", secret, { secret = it }, secret = true)
        Spacer(Modifier.height(8.dp))
        WireButton(
            "Save host",
            enabled = alias.isNotBlank() && hostname.isNotBlank() && username.isNotBlank(),
            onClick = {
                onAdd(
                    RemoteHost(alias.trim(), hostname.trim(), port.toIntOrNull() ?: 22, username.trim()),
                    secret,
                    isKey,
                )
                alias = ""; hostname = ""; port = "22"; username = ""; secret = ""
            },
        )
    }
}

@Composable
private fun TrustDialog(verdict: Trust, onAccept: () -> Unit, onReject: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onReject,
        title = { Text(if (verdict is Trust.Changed) "Host key CHANGED" else "Unknown host") },
        text = {
            Column {
                when (verdict) {
                    is Trust.Unknown -> {
                        Text("First time connecting. Verify this fingerprint matches the server:")
                        Spacer(Modifier.height(8.dp))
                        Text(verdict.presented.fingerprint, fontFamily = FontFamily.Monospace)
                    }
                    is Trust.Changed -> {
                        Text("The host key DIFFERS from the one you pinned. This could be a reinstall — or an interception. Do not accept unless you know why it changed.")
                        Spacer(Modifier.height(8.dp))
                        Text("pinned:    ${verdict.pinned.fingerprint}", fontFamily = FontFamily.Monospace)
                        Text("presented: ${verdict.presented.fingerprint}", fontFamily = FontFamily.Monospace)
                    }
                    Trust.Vetted -> Text("Vetted.")
                }
            }
        },
        confirmButton = { WireButton(if (verdict is Trust.Changed) "Accept anyway" else "Accept", onClick = onAccept) },
        dismissButton = { WireButton("Reject", onClick = onReject) },
    )
}

private fun phaseLabel(s: SessionState): String = when (s) {
    SessionState.Disconnected -> "disconnected"
    SessionState.Connecting -> "connecting"
    is SessionState.TrustCheck -> "checking trust"
    SessionState.Authenticating -> "authenticating"
    SessionState.Ready -> "ready"
    SessionState.Running -> "running"
    SessionState.Closed -> "closed"
    is SessionState.Failed -> "failed: ${s.reason}"
}
