package dev.aarso.ui.develop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aarso.FonebrewApp
import dev.aarso.data.remote.PtyShellSession
import dev.aarso.domain.remote.Identity
import dev.aarso.domain.remote.RemoteHost
import dev.aarso.domain.remote.RemoteSessionDriver
import dev.aarso.domain.remote.ShellSession
import dev.aarso.domain.remote.Trust
import dev.aarso.domain.remote.term.PtyChannel
import dev.aarso.hyle.cells.rememberHyleHaptics
import dev.aarso.hyle.theme.DefaultAccent
import dev.aarso.hyle.theme.HyleColors
import dev.aarso.hyle.theme.darkHyleColors
import dev.aarso.hyle.theme.parseHexColor
import dev.aarso.ui.components.SlashCommand
import dev.aarso.ui.components.SlashCommandPopup
import dev.aarso.ui.components.matchSlashCommands
import dev.aarso.ui.remote.TerminalBg
import dev.aarso.ui.remote.TerminalFg
import dev.aarso.ui.remote.TrustDialog
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
 *
 * **Closing never leaves a void (2026-07-29 fix):** tapping "Close" used to null out [shell] with
 * nothing gating it back on — the whole terminal block (grid/input/buttons) lives behind
 * `if (shell != null)`, and the auto-connect [LaunchedEffect] only re-fires when `mode`/
 * `remoteAlias` *change*, which a plain Close doesn't touch. Net effect: a permanently blank pane
 * below the mode toggle. Fix chosen: Close now shows an honest "Session closed." [TerminalHint]
 * plus an explicit **Reconnect** button, rather than silently respawning a shell out from under a
 * tap the user just made on purpose — auto-reopening would make Close a no-op, which is worse than
 * confusing, it's dishonest about what the button does. Reconnect reuses whatever `mode`/
 * `remoteAlias` were already active (Close doesn't clear either), so a Remote session reconnects
 * to the *same* host with one tap, never dropping back to an ambiguous "pick a machine" state.
 * Switching target via the mode toggle or a `/connect`/`/phone` slash command still auto-opens
 * immediately, unchanged — that path clears the "closed" flag itself since it's already
 * reconnecting on purpose.
 *
 * **A failed Reconnect (or initial connect) also needs a way back in (audit follow-up):** the
 * first pass only handled the *deliberate Close* dead end above — but a failed [reconnect] call
 * lands in the exact same "no live shell" state minus `closedByUser` (it clears that flag before
 * the async open resolves), and a never-yet-succeeded initial connect never sets it either. Both
 * used to render only the `connectError` [TerminalHint] with no button and no input box, i.e. the
 * same void, reachable a different way. A third branch below now offers **Retry** whenever
 * `shell == null && !closedByUser && !connecting && connectError != null`, calling the same
 * [reconnect] (phone, or the same remote alias) rather than inventing a second code path.
 *

 * **Terminal-dark chrome (2026-07-29):** this tab now commits to a permanently dark, terminal-
 * appropriate presentation — a full-bleed [TerminalBg] panel behind the whole tab (mode toggle,
 * hints, grid, input, buttons), monospace everywhere, terminal-local button/field styling —
 * regardless of the app's own light/dark theme setting, the same way most IDEs keep their
 * terminal panel dark even in a light editor theme. [TerminalBg]/[TerminalFg] are the exact
 * constants [TerminalView] paints its grid with (bumped from `private` to `internal` there so the
 * two can't drift out of sync), and button/accent colours come from [darkHyleColors] — the dark
 * half of the *same* Hyle token system everything else in the app uses, seeded with the user's
 * own accent pick ([dev.aarso.data.SessionStore.accentColor]) rather than a hard-coded hex, so a
 * custom accent still shows up here — just always via the dark ramp, since the canvas behind it
 * never changes with the ambient theme. This intentionally does NOT touch the shared
 * [dev.aarso.ui.wire.WireButton]/[dev.aarso.ui.wire.WireField]/[Hint] atoms (still used by
 * Hardware/Files/Audit and by Remote's own screen) — Terminal gets its own small local button/
 * field/hint composables below instead, so nothing else in the app changes look.
 * [TerminalView]'s own grid-rendering logic is untouched; only its height cap grows (from a flat
 * 320dp max to roughly half the screen) so it actually fills the tab instead of sitting in a
 * small letterboxed strip. It stays capped rather than truly unbounded because this facet's
 * content renders inside [DevelopRoom]'s single outer `verticalScroll` Column, which measures
 * children with unbounded height — an uncapped scrollable grid in there would crash on measure.
 */
@Composable
fun TerminalFacet() {
    val context = LocalContext.current
    val container = (context.applicationContext as FonebrewApp).container
    val store = container.remoteHostStore
    val hosts by store.hosts.collectAsState()
    val showCtrlC by container.sessionStore.terminalCtrlCButton.collectAsState()
    val accentHex by container.sessionStore.accentColor.collectAsState()
    val scope = rememberCoroutineScope()

    // Always the dark half of the Hyle ramp for this tab's chrome, seeded with the user's own
    // accent pick (not a raw hex) — see the class doc for why: the canvas here is pinned dark
    // regardless of the app's own theme setting, so the light-mode ramp would be the wrong pick.
    val termColors = remember(accentHex) { darkHyleColors(parseHexColor(accentHex) ?: DefaultAccent) }

    var mode by remember { mutableStateOf(PHONE) }
    var remoteAlias by remember { mutableStateOf<String?>(null) }

    val pty = remember { PtyChannel(rows = 24, cols = 80) }
    var shell by remember { mutableStateOf<ShellSession?>(null) }
    var screenVersion by remember { mutableStateOf(0) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    // Set only by the explicit Close button (not by switchTo's internal close-before-reopen) so a
    // deliberate Close reads as "Session closed." + Reconnect, while switching target still just
    // reconnects immediately with no interstitial. See the class doc's "Closing never leaves a void".
    var closedByUser by remember { mutableStateOf(false) }
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

    /** The Close button's action: close, and say so — see the class doc. */
    fun userCloseShell() {
        closeShell()
        closedByUser = true
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

    /** Reconnect to whatever's currently active (phone, or the same remote alias) — the Reconnect
     *  affordance after a Close, and also usable by anything else that wants "get me back in". */
    fun reconnect() {
        closedByUser = false
        when {
            mode == PHONE -> openPhone()
            mode == REMOTE && remoteAlias != null -> openRemote(remoteAlias!!)
        }
    }

    fun switchTo(newMode: String, alias: String? = null) {
        closeShell()
        pty.reset()
        mode = newMode
        remoteAlias = alias
        connectError = null
        // Switching target reconnects immediately (below) — never show the "closed" interstitial
        // for that path, only for an explicit Close on the machine you were already on.
        closedByUser = false
    }

    // Auto-connect on entering Terminal, and again whenever the target changes — no "Open shell"
    // gate button. Phone connects immediately; remote waits for a specific alias (auto-picked
    // below if there's exactly one saved host). Deliberately does NOT re-fire on a plain Close
    // (shell -> null with mode/remoteAlias unchanged) — that's the Reconnect button's job now,
    // so a Close reads as a real close rather than an instant, silent respawn.
    LaunchedEffect(mode, remoteAlias) {
        if (shell != null || closedByUser) return@LaunchedEffect
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

    // Roughly half the screen, floored at the old 320dp max — see the class doc for why this
    // stays a bounded heightIn rather than a true fillMaxSize/weight: the parent Column
    // (DevelopRoom) is itself vertically scrollable, so an unbounded-height scrollable child here
    // would crash on measure. This is still a big jump from the old flat 160-320dp cap.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val gridMaxHeight = remember(screenHeightDp) { (screenHeightDp * 0.55f).dp.coerceAtLeast(320.dp) }

    // Full-bleed dark terminal panel behind the ENTIRE tab — mode toggle, hints, grid, input,
    // buttons — not just the grid itself, so Terminal reads as one cohesive dark surface instead
    // of a small dark strip embedded in the room's normal light/Settings-style background.
    // Branches as a single if/else-if chain (rather than the original's early `return`s) so every
    // state — including "no hosts yet" / "pick a machine" — renders inside this one Column and
    // picks up the dark background; nothing here needs to rely on non-local return out of an
    // inline layout composable.
    Column(
        Modifier
            .fillMaxWidth()
            .background(TerminalBg)
            .padding(12.dp),
    ) {
        Text(
            "Terminal",
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
            color = TerminalFg,
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TerminalActionButton(
                "This phone",
                selected = mode == PHONE,
                colors = termColors,
                modifier = Modifier.weight(1f),
                onClick = { if (mode != PHONE) switchTo(PHONE) },
            )
            TerminalActionButton(
                "Remote" + (remoteAlias?.let { " ($it)" } ?: ""),
                selected = mode == REMOTE,
                enabled = hosts.isNotEmpty(),
                colors = termColors,
                modifier = Modifier.weight(1f),
                // Only auto-pick when there's exactly one saved host (the size==1 LaunchedEffect
                // below does the same) — with several, land on the in-CLI "/connect <alias>" hint
                // instead of silently always connecting to whichever host happens to be first.
                onClick = { if (mode != REMOTE) switchTo(REMOTE, hosts.singleOrNull()?.alias) },
            )
        }
        Spacer(Modifier.height(8.dp))

        when {
            mode == REMOTE && hosts.isEmpty() -> {
                TerminalHint("No saved machines yet. Add one in Settings → Global → your machines — then it shows up here.")
            }
            mode == REMOTE && remoteAlias == null -> {
                TerminalHint("Pick a machine: type " + hosts.joinToString(" or ") { "/connect ${it.alias}" })
            }
            else -> {
                when {
                    connecting -> TerminalHint(if (mode == PHONE) "Opening…" else "Connecting to $remoteAlias…")
                    connectError != null -> TerminalHint("Couldn't connect: $connectError")
                }
                Spacer(Modifier.height(8.dp))

                if (shell != null) {
                    key(screenVersion) {
                        dev.aarso.ui.remote.TerminalView(
                            screen = pty.screen,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = gridMaxHeight)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TerminalInputField("input — Enter to send, / for commands", input, { input = it }, colors = termColors)
                    if (slashMatches.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        SlashCommandPopup(slashMatches) { cmd -> cmd.run(); input = "" }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TerminalActionButton(
                            "Send",
                            enabled = input.isNotEmpty() && !input.startsWith("/"),
                            selected = true,
                            colors = termColors,
                            onClick = {
                                val line = input; input = ""
                                scope.launch { runCatching { shell?.send(line + "\n") } }
                            },
                        )
                        if (showCtrlC) {
                            TerminalActionButton("Ctrl-C", colors = termColors, onClick = { scope.launch { runCatching { shell?.send(3.toChar().toString()) } } })
                        }
                        TerminalActionButton("Close", colors = termColors, onClick = { userCloseShell() })
                    }
                } else if (closedByUser) {
                    // The fix for "tapping Close completely disappears the terminal" — an honest
                    // state instead of a void, with a one-tap way back in. See the class doc for
                    // why this is a Reconnect button rather than an automatic instant respawn.
                    TerminalHint("Session closed.")
                    Spacer(Modifier.height(8.dp))
                    TerminalActionButton("Reconnect", selected = true, colors = termColors, onClick = { reconnect() })
                } else if (!connecting && connectError != null) {
                    // Audit finding: without this, a *failed* Reconnect (or a failed initial
                    // connect) left shell == null, closedByUser == false, connectError != null —
                    // neither branch above renders, so there was no button and no input box to
                    // get back in; only the error Hint above. A Retry here re-runs the same
                    // reconnect() (phone, or the same remote alias) so both the new Reconnect
                    // path and the pre-existing initial-connect-failure gap get a way back in.
                    Spacer(Modifier.height(8.dp))
                    TerminalActionButton("Retry", selected = true, colors = termColors, onClick = { reconnect() })
                }
            }
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

/** A small dim, monospace status line for the Terminal tab specifically — legible against the
 *  fixed-dark [TerminalBg] regardless of the app's own light/dark theme, unlike the shared
 *  [Hint] (which tints off the theme-following [dev.aarso.hyle.theme.LocalHyleColors] and would
 *  go low-contrast on a permanently-dark panel under a light app theme). */
@Composable
private fun TerminalHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = TerminalFg.copy(alpha = 0.65f),
    )
}

/**
 * A terminal-chrome button — filled violet when [selected] (the mode toggle's active side, or a
 * primary action like Send/Reconnect), otherwise a transparent fill with a hairline edge, light
 * monospace label throughout. A local reskin scoped to this one tab only: it does NOT touch
 * [dev.aarso.ui.wire.WireButton]/`HyleChip`, which stay exactly as they are for every other
 * Develop facet (Hardware/Files/Audit) and for Remote's own screen. [colors] is always the dark
 * half of the Hyle ramp (see [TerminalFacet]'s class doc) so this reads correctly against
 * [TerminalBg] no matter the app's own theme setting.
 */
@Composable
private fun TerminalActionButton(
    label: String,
    onClick: () -> Unit,
    colors: HyleColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val haptics = rememberHyleHaptics()
    val shape = RoundedCornerShape(6.dp)
    val fill = if (enabled && selected) colors.violet else Color.Transparent
    val textColor = when {
        !enabled -> colors.textDisabled
        selected -> colors.onViolet
        else -> TerminalFg
    }
    val borderColor = if (enabled) colors.hairline else colors.textDisabled.copy(alpha = 0.4f)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled, onClick = { haptics.tap(); onClick() })
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = textColor,
            maxLines = 1,
        )
    }
}

/** A dark input row styled to match the terminal grid it sits under — monospace text, a slightly
 *  raised-from-[TerminalBg] fill so the field itself still reads as a distinct control, hairline
 *  border in the dark Hyle ramp's tone. Local to this tab, same rationale as
 *  [TerminalActionButton]: does not touch the shared [dev.aarso.ui.wire.WireField]/`HyleField`. */
@Composable
private fun TerminalInputField(
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
    colors: HyleColors,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TerminalInputBg, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TerminalFg, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(colors.violet),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = TerminalFg.copy(alpha = 0.4f),
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** Slightly lighter than [TerminalBg] so the input row reads as its own control against the
 *  panel behind it, rather than disappearing flush into it. */
private val TerminalInputBg = Color(0xFF141414)
