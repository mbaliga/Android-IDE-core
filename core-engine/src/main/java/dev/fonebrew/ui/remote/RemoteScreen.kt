package dev.fonebrew.ui.remote

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.cells.HyleFocusLens
import dev.aarso.hyle.cells.HyleLensActions
import dev.aarso.hyle.cells.HyleLensHeading
import dev.aarso.hyle.component.HyleTerminalField
import dev.aarso.hyle.theme.DefaultAccent
import dev.aarso.hyle.theme.darkHyleColors
import dev.aarso.hyle.theme.parseHexColor
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.remote.TerminalSessionHolder
import dev.fonebrew.domain.remote.ExecRequest
import dev.fonebrew.domain.remote.Identity
import dev.fonebrew.domain.remote.RemoteHost
import dev.fonebrew.domain.remote.RemoteSessionDriver
import dev.fonebrew.domain.remote.SessionState
import dev.fonebrew.domain.remote.Trust
import dev.fonebrew.ui.wire.WireBox
import dev.fonebrew.ui.wire.WireButton
import dev.fonebrew.ui.wire.WireField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Wireframe **Remote** screen — the device-testable face of the remote-exec spine (Sprint 1).
 * Add an SSH host, connect (the **trust** decision is yours, shown with the real fingerprint),
 * run a command, and read the remote's raw output verbatim — it is a watched object, never our
 * paraphrase (THE LAW). Boxy/wireframe; the design system reskins later. Runtime is
 * owner-verified (no SSH server in CI).
 *
 * **This screen no longer builds a terminal of its own.** It used to: its own
 * `PtyChannel(24, 80)`, its own [dev.fonebrew.domain.remote.ShellSession], its own screen
 * version, its own input draft and its own Send/Ctrl-C/Close — every one of them per-mount
 * `remember {}`, disposed on navigation. That is exactly the shape
 * [dev.fonebrew.data.remote.TerminalSessionHolder] was written to end, so the owner's report of
 * "two terminals" was in fact three. The Terminal section below drives THE one session on the
 * app container; opening a host's terminal here is the same live shell Chat and Develop show,
 * with the same scrollback.
 *
 * The one-shot **Run a command** box keeps its own [RemoteSessionDriver]: that is a different
 * thing from a terminal — a single exec against a host you are administering, with its output
 * captured whole — and folding it into the interactive session would conflate them.
 */
@Composable
fun RemoteScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
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

    // THE terminal session — shared with Chat and Develop, owned by the app container.
    val term = container.terminalSession
    val termMode by term.mode.collectAsState()
    val termAlias by term.remoteAlias.collectAsState()
    val termShell by term.shell.collectAsState()
    val termVersion by term.screenVersion.collectAsState()
    val termConnecting by term.connecting.collectAsState()
    val termError by term.connectError.collectAsState()
    val termClosed by term.closedByUser.collectAsState()
    val termTrust by term.pendingTrust.collectAsState()
    val termInput by term.input.collectAsState()
    val termHistory by term.history.collectAsState()
    val accentHex by container.sessionStore.accentColor.collectAsState()
    // The terminal panel is pinned dark whatever the app's theme is (same rule as the Terminal
    // facet), so its chrome comes off the dark ramp seeded with the user's own accent.
    val termColors = remember(accentHex) { darkHyleColors(parseHexColor(accentHex) ?: DefaultAccent) }
    // Fixed, not wrap-content: TerminalView derives the pty's row count from the box it is
    // handed, so a box that sized itself to its content would shrink the grid on every pass.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val gridHeight = remember(screenHeightDp) { (screenHeightDp * 0.5f).dp.coerceAtLeast(280.dp) }

    fun sendTerminalLine() {
        val line = termInput
        if (line.isEmpty()) return
        term.setInput("")
        term.sendLine(line)
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
                    // Points THE shared session at this host — it does its own connect (and its
                    // own trust gate, answered below), independent of the exec driver above.
                    WireButton("Terminal", onClick = {
                        term.switchTo(TerminalSessionHolder.REMOTE, host.alias)
                        term.ensureOpen()
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

        // The terminal — THE one session, not a second copy of one. Always shown (it isn't gated
        // on the exec driver's phase, because it doesn't use the exec driver), so this screen can
        // say honestly which machine the app's shell is currently sitting on.
        Spacer(Modifier.height(12.dp))
        Text("Terminal", style = MaterialTheme.typography.titleMedium)
        Text(
            "The same terminal as Chat and Develop — one session, one scrollback. " +
                (
                    if (termMode == TerminalSessionHolder.PHONE) {
                        "Currently on this phone."
                    } else {
                        "Currently on ${termAlias ?: "a remote host"}."
                    }
                    ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
        when {
            termShell != null -> {
                // Scrollback, both scroll axes and the real grid size all come from TerminalView
                // itself, so Remote gets them by construction rather than by duplication.
                TerminalView(
                    screen = term.pty.screen,
                    version = termVersion,
                    onGridMeasured = term::resize,
                    modifier = Modifier.fillMaxWidth().height(gridHeight),
                )
                Spacer(Modifier.height(8.dp))
                // Was a WireField labelled "input (sent with Enter)" that had no IME action at
                // all, so Enter did nothing — the same defect as the Terminal tab's field, in a
                // second place. HyleTerminalField's onSubmit is what makes the label true.
                HyleTerminalField(
                    value = termInput,
                    onValueChange = term::setInput,
                    colors = termColors,
                    placeholder = "input — Enter to send",
                    fg = TerminalFg,
                    onSubmit = { sendTerminalLine() },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WireButton("Send", onClick = { sendTerminalLine() }, enabled = termInput.isNotEmpty())
                    WireButton("↑", onClick = { term.historyBack() }, enabled = termHistory.isNotEmpty())
                    WireButton("↓", onClick = { term.historyForward() }, enabled = termHistory.isNotEmpty())
                    WireButton("Ctrl-C", onClick = { term.sendRaw(3.toChar().toString()) })
                    WireButton("Close shell", onClick = { term.userCloseShell() })
                }
            }
            termConnecting -> Text(
                if (termMode == TerminalSessionHolder.PHONE) "opening…" else "connecting to ${termAlias ?: "host"}…",
                color = MaterialTheme.colorScheme.outline,
            )
            termClosed -> {
                Text("Session closed.", color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                WireButton("Reconnect", onClick = { term.reconnect() })
            }
            termError != null -> {
                Text("Couldn't connect: $termError", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                WireButton("Retry", onClick = { term.reconnect() })
            }
            else -> WireButton("Open terminal on this phone", onClick = {
                term.switchTo(TerminalSessionHolder.PHONE)
                term.ensureOpen()
            })
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
    // The shared session runs its own connect, so it has its own trust gate — and it has to be
    // answerable from here, or tapping "Terminal" on a host that isn't vetted yet would suspend
    // forever with the prompt on a screen the user isn't looking at.
    termTrust?.let { verdict ->
        TrustDialog(
            verdict = verdict,
            onAccept = { term.resolveTrust(true) },
            onReject = { term.resolveTrust(false) },
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
internal fun TrustDialog(verdict: Trust, onAccept: () -> Unit, onReject: () -> Unit) {
    // A security decision: dismissal must be an explicit Reject, never a stray tap,
    // so onDismiss stays null and the lens cannot be dismissed by touching the ground.
    HyleFocusLens(visible = true, onDismiss = null) {
        HyleLensHeading(if (verdict is Trust.Changed) "Host key CHANGED" else "Unknown host")
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
        HyleLensActions {
            WireButton("Reject", onClick = onReject)
            Spacer(Modifier.width(8.dp))
            WireButton(if (verdict is Trust.Changed) "Accept anyway" else "Accept", onClick = onAccept)
        }
    }
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
