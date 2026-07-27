package dev.aarso.ui.develop

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aarso.FonebrewApp
import dev.aarso.data.remote.PtyShellSession
import dev.aarso.domain.remote.Identity
import dev.aarso.domain.remote.RemoteHost
import dev.aarso.domain.remote.RemoteSessionDriver
import dev.aarso.domain.remote.ShellSession
import dev.aarso.domain.remote.Trust
import dev.aarso.domain.remote.term.PtyChannel
import dev.aarso.ui.components.SlashCommand
import dev.aarso.ui.components.SlashCommandPopup
import dev.aarso.ui.components.matchSlashCommands
import dev.aarso.ui.remote.TrustDialog
import dev.aarso.ui.wire.WireField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

private const val PHONE = "phone"
private const val REMOTE = "remote"

/**
 * The Develop room's **Terminal** tab. One tap gets you a live shell — this phone by default,
 * or a saved remote host — never a picker screen you have to get through first (owner ask,
 * 2026-07-27: "the CLI should be visible up-front"). Local and remote are the same
 * [ShellSession] interface underneath ([PtyShellSession] for this phone,
 * [RemoteSessionDriver.shell] over SSH for a remote host — see [RemoteScreen.kt]'s original
 * interactive shell, which this now shares logic with rather than duplicates), so one
 * [TerminalView] + input + command palette covers both.
 *
 * Switching **which** machine, once you're already in the CLI, is a slash command
 * (`/phone`, `/connect <alias>`) — not a second screen. The two-way phone/remote choice up top
 * is the only UI outside the terminal itself; picking a *specific* saved host when there's more
 * than one lives inside the CLI, per the same ask.
 */
@Composable
fun TerminalFacet() {
    val context = LocalContext.current
    val container = (context.applicationContext as FonebrewApp).container
    val store = container.remoteHostStore
    val hosts by store.hosts.collectAsState()
    val showCtrlC by container.sessionStore.terminalCtrlCButton.collectAsState()
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(PHONE) }
    var remoteAlias by remember { mutableStateOf<String?>(null) }

    val pty = remember { PtyChannel(rows = 24, cols = 80) }
    var shell by remember { mutableStateOf<ShellSession?>(null) }
    var screenVersion by remember { mutableStateOf(0) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var pendingTrust by remember { mutableStateOf<Trust?>(null) }
    var trustGate by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var input by remember { mutableStateOf("") }

    fun onOutput(bytes: ByteArray) {
        pty.onOutput(String(bytes, Charsets.UTF_8))
        screenVersion++
    }

    fun closeShell() {
        val s = shell
        shell = null
        scope.launch { runCatching { s?.close() } }
    }

    fun identityFor(h: RemoteHost): Identity {
        val ref = store.hostSecret(h.alias)
        return when {
            ref == null -> Identity.Agent
            ref.isKey -> Identity.PublicKey(ref.id)
            else -> Identity.Password(ref.id)
        }
    }

    fun openPhone() {
        connecting = true; connectError = null
        scope.launch {
            runCatching {
                PtyShellSession.open(context.filesDir, rows = pty.screen.rows, cols = pty.screen.cols) { chunk -> onOutput(chunk.bytes) }
            }.onSuccess { shell = it }.onFailure { connectError = it.message ?: "couldn't open a shell" }
            connecting = false
        }
    }

    fun openRemote(alias: String) {
        val host = hosts.firstOrNull { it.alias == alias }
        if (host == null) {
            connectError = "no saved host named \"$alias\""
            return
        }
        connecting = true; connectError = null
        scope.launch {
            val driver = RemoteSessionDriver(container.newSshTransport(), store.knownHosts.value)
            runCatching {
                driver.open(host, identityFor(host)) { verdict ->
                    val gate = CompletableDeferred<Boolean>()
                    pendingTrust = verdict; trustGate = gate
                    val ok = gate.await()
                    pendingTrust = null; trustGate = null
                    if (ok && verdict !is Trust.Vetted) {
                        val key = (verdict as? Trust.Unknown)?.presented ?: (verdict as Trust.Changed).presented
                        store.pin(host.endpoint, key)
                    }
                    ok
                }
                driver.shell { chunk -> onOutput(chunk.bytes) }
            }.onSuccess { shell = it }.onFailure { connectError = it.message ?: "couldn't connect — is it trusted yet in Settings → Global?" }
            connecting = false
        }
    }

    fun switchTo(newMode: String, alias: String? = null) {
        closeShell()
        pty.reset()
        mode = newMode
        remoteAlias = alias
        connectError = null
    }

    // Auto-connect on entering Terminal, and again whenever the target changes — no "Open shell"
    // gate button. Phone connects immediately; remote waits for a specific alias (auto-picked
    // below if there's exactly one saved host).
    LaunchedEffect(mode, remoteAlias) {
        if (shell != null) return@LaunchedEffect
        when {
            mode == PHONE -> openPhone()
            mode == REMOTE && remoteAlias != null -> openRemote(remoteAlias!!)
        }
    }
    // Entering Remote mode with exactly one saved host: connect to it directly, no extra pick.
    LaunchedEffect(mode, hosts) {
        if (mode == REMOTE && remoteAlias == null && hosts.size == 1) remoteAlias = hosts.first().alias
    }

    DisposableEffect(Unit) { onDispose { scope.launch { runCatching { shell?.close() } } } }

    val slashCommands = remember(mode, hosts, shell) {
        buildList {
            add(SlashCommand("/ctrlc", "Send Ctrl-C") { scope.launch { runCatching { shell?.send(3.toChar().toString()) } } })
            add(SlashCommand("/ctrld", "Send Ctrl-D (EOF)") { scope.launch { runCatching { shell?.send(4.toChar().toString()) } } })
            add(SlashCommand("/ctrlz", "Send Ctrl-Z (suspend)") { scope.launch { runCatching { shell?.send(26.toChar().toString()) } } })
            add(SlashCommand("/clear", "Clear the screen") { pty.screen.clear(); screenVersion++ })
            if (mode != PHONE) add(SlashCommand("/phone", "Switch to this phone") { switchTo(PHONE) })
            hosts.forEach { h ->
                if (mode != REMOTE || remoteAlias != h.alias) {
                    add(SlashCommand("/connect ${h.alias}", "Switch to ${h.alias}") { switchTo(REMOTE, h.alias) })
                }
            }
        }
    }
    val slashMatches = matchSlashCommands(input, slashCommands)

    Text("Terminal", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WireButton("This phone", selected = mode == PHONE, onClick = { if (mode != PHONE) switchTo(PHONE) })
        WireButton(
            "Remote" + (remoteAlias?.let { " ($it)" } ?: ""),
            selected = mode == REMOTE,
            enabled = hosts.isNotEmpty(),
            // Only auto-pick when there's exactly one saved host (the size==1 LaunchedEffect
            // below does the same) — with several, land on the in-CLI "/connect <alias>" hint
            // instead of silently always connecting to whichever host happens to be first.
            onClick = { if (mode != REMOTE) switchTo(REMOTE, hosts.singleOrNull()?.alias) },
        )
    }
    Spacer(Modifier.height(8.dp))

    if (mode == REMOTE && hosts.isEmpty()) {
        Hint("No saved machines yet. Add one in Settings → Global → your machines — then it shows up here.")
        return
    }
    if (mode == REMOTE && remoteAlias == null) {
        Hint("Pick a machine: type " + hosts.joinToString(" or ") { "/connect ${it.alias}" })
        return
    }

    when {
        connecting -> Hint(if (mode == PHONE) "Opening…" else "Connecting to $remoteAlias…")
        connectError != null -> Hint("Couldn't connect: $connectError")
    }
    Spacer(Modifier.height(8.dp))

    if (shell != null) {
        key(screenVersion) {
            dev.aarso.ui.remote.TerminalView(
                screen = pty.screen,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        Spacer(Modifier.height(8.dp))
        WireField("input — Enter to send, / for commands", input, { input = it })
        if (slashMatches.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SlashCommandPopup(slashMatches) { cmd -> cmd.run(); input = "" }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WireButton("Send", enabled = input.isNotEmpty() && !input.startsWith("/"), onClick = {
                val line = input; input = ""
                scope.launch { runCatching { shell?.send(line + "\n") } }
            })
            if (showCtrlC) {
                WireButton("Ctrl-C", onClick = { scope.launch { runCatching { shell?.send(3.toChar().toString()) } } })
            }
            WireButton("Close", onClick = { closeShell() })
        }
    }

    pendingTrust?.let { verdict ->
        TrustDialog(
            verdict = verdict,
            onAccept = { trustGate?.complete(true) },
            onReject = { trustGate?.complete(false) },
        )
    }
}
