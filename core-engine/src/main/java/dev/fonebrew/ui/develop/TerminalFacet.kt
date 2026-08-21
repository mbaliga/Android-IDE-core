package dev.fonebrew.ui.develop

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.cells.rememberHyleHaptics
import dev.aarso.hyle.component.HyleTerminalField
import dev.aarso.hyle.theme.DefaultAccent
import dev.aarso.hyle.theme.HyleColors
import dev.aarso.hyle.theme.darkHyleColors
import dev.aarso.hyle.theme.parseHexColor
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.remote.TerminalSessionHolder
import dev.fonebrew.ui.components.SlashCommand
import dev.fonebrew.ui.components.SlashCommandPopup
import dev.fonebrew.ui.components.matchSlashCommands
import dev.fonebrew.ui.remote.TerminalBg
import dev.fonebrew.ui.remote.TerminalFg
import dev.fonebrew.ui.remote.TrustDialog

private const val PHONE = TerminalSessionHolder.PHONE
private const val REMOTE = TerminalSessionHolder.REMOTE

/**
 * The terminal VIEW — mounted from two doors (Develop's Terminal tab and Chat's Terminal tab),
 * both opening onto the ONE live session owned by
 * [dev.fonebrew.data.remote.TerminalSessionHolder] on the app container. This composable holds
 * no session state at all: shell, screen, machine choice, connect status, even the input draft
 * all live on the holder, so switching tabs (or entering from the other door) detaches the
 * view and nothing else — the transcript keeps scrolling, an in-flight connect keeps
 * connecting. (Previously all of that was per-mount `remember {}` with an onDispose close —
 * two doors meant two shells and every tab switch killed the session; see the holder's doc.)
 *
 * One tap gets you a live shell — this phone by default, or a saved remote host — never a
 * picker screen you have to get through first (owner ask, 2026-07-27: "the CLI should be
 * visible up-front"). Local and remote are the same
 * [dev.fonebrew.domain.remote.ShellSession] interface underneath, so one
 * [dev.fonebrew.ui.remote.TerminalView] + input + command palette covers both. Switching
 * **which** machine, once you're already in the CLI, is a slash command (`/phone`,
 * `/connect <alias>`) — not a second screen.
 *
 * **Closing never leaves a void (2026-07-29 fix):** Close shows an honest "Session closed."
 * hint plus an explicit **Reconnect** button rather than silently respawning a shell out from
 * under a tap the user just made on purpose. A *failed* connect (initial or Reconnect) gets a
 * **Retry** the same way — no state renders buttonless. Switching target via the toggle or a
 * slash command still auto-opens immediately; that path is already reconnecting on purpose.
 *
 * **Terminal-dark chrome (2026-07-29):** this tab commits to a permanently dark presentation —
 * full-bleed [TerminalBg] panel, monospace everywhere, terminal-local button styling — however
 * the app itself is themed, the way IDEs keep their terminal panel dark inside a light editor
 * theme. Button/accent colours come from [darkHyleColors] seeded with the user's own accent
 * pick, so a custom accent still shows up here — just always via the dark ramp. The input row
 * is Hyle's [HyleTerminalField] (owner ask, 2026-08-21: the terminal's field lives in the
 * design system, not app-local). The grid caps at roughly half the screen because this facet
 * renders inside DevelopRoom's outer `verticalScroll` Column — an unbounded scrollable child
 * there would crash on measure.
 */
@Composable
fun TerminalFacet() {
    val context = LocalContext.current
    val container = (context.applicationContext as FonebrewApp).container
    val store = container.remoteHostStore
    val term = container.terminalSession
    val hosts by store.hosts.collectAsState()
    val showCtrlC by container.sessionStore.terminalCtrlCButton.collectAsState()
    val accentHex by container.sessionStore.accentColor.collectAsState()

    // Always the dark half of the Hyle ramp for this tab's chrome, seeded with the user's own
    // accent pick (not a raw hex) — see the class doc: the canvas here is pinned dark
    // regardless of the app's own theme setting, so the light-mode ramp would be the wrong pick.
    val termColors = remember(accentHex) { darkHyleColors(parseHexColor(accentHex) ?: DefaultAccent) }

    val mode by term.mode.collectAsState()
    val remoteAlias by term.remoteAlias.collectAsState()
    val shell by term.shell.collectAsState()
    val screenVersion by term.screenVersion.collectAsState()
    val connecting by term.connecting.collectAsState()
    val connectError by term.connectError.collectAsState()
    val closedByUser by term.closedByUser.collectAsState()
    val pendingTrust by term.pendingTrust.collectAsState()
    val input by term.input.collectAsState()

    // Auto-connect on entering the terminal, and again whenever the target changes — no "Open
    // shell" gate button. The holder no-ops when a session is already live (the common case now
    // that it survives navigation), mid-connect, or explicitly closed (Reconnect's job).
    LaunchedEffect(mode, remoteAlias) { term.ensureOpen() }
    // Entering Remote mode with exactly one saved host: connect to it directly, no extra pick.
    LaunchedEffect(mode, hosts) { term.autoPickSingleHost() }

    val slashCommands = remember(mode, remoteAlias, hosts) {
        buildList {
            add(SlashCommand("/ctrlc", "Send Ctrl-C") { term.sendRaw(3.toChar().toString()) })
            add(SlashCommand("/ctrld", "Send Ctrl-D (EOF)") { term.sendRaw(4.toChar().toString()) })
            add(SlashCommand("/ctrlz", "Send Ctrl-Z (suspend)") { term.sendRaw(26.toChar().toString()) })
            add(SlashCommand("/clear", "Clear the screen") { term.clearScreen() })
            if (mode != PHONE) add(SlashCommand("/phone", "Switch to this phone") { term.switchTo(PHONE) })
            hosts.forEach { h ->
                if (mode != REMOTE || remoteAlias != h.alias) {
                    add(SlashCommand("/connect ${h.alias}", "Switch to ${h.alias}") { term.switchTo(REMOTE, h.alias) })
                }
            }
        }
    }
    val slashMatches = matchSlashCommands(input, slashCommands)

    // Roughly half the screen, floored at the old 320dp max — see the class doc for why this
    // stays a bounded heightIn rather than a true fillMaxSize/weight.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val gridMaxHeight = remember(screenHeightDp) { (screenHeightDp * 0.55f).dp.coerceAtLeast(320.dp) }

    // Full-bleed dark terminal panel behind the ENTIRE tab — mode toggle, hints, grid, input,
    // buttons — not just the grid itself, so Terminal reads as one cohesive dark surface instead
    // of a small dark strip embedded in the room's normal light/Settings-style background.
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
                onClick = { if (mode != PHONE) term.switchTo(PHONE) },
            )
            TerminalActionButton(
                "Remote" + (remoteAlias?.let { " ($it)" } ?: ""),
                selected = mode == REMOTE,
                enabled = hosts.isNotEmpty(),
                colors = termColors,
                modifier = Modifier.weight(1f),
                // Only auto-pick when there's exactly one saved host (autoPickSingleHost does
                // the same) — with several, land on the in-CLI "/connect <alias>" hint instead
                // of silently always connecting to whichever host happens to be first.
                onClick = { if (mode != REMOTE) term.switchTo(REMOTE, hosts.singleOrNull()?.alias) },
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
                        dev.fonebrew.ui.remote.TerminalView(
                            screen = term.pty.screen,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = gridMaxHeight)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HyleTerminalField(
                        value = input,
                        onValueChange = term::setInput,
                        colors = termColors,
                        placeholder = "input — Enter to send, / for commands",
                        fg = TerminalFg,
                    )
                    if (slashMatches.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        SlashCommandPopup(slashMatches) { cmd -> cmd.run(); term.setInput("") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TerminalActionButton(
                            "Send",
                            enabled = input.isNotEmpty() && !input.startsWith("/"),
                            selected = true,
                            colors = termColors,
                            onClick = {
                                val line = input
                                term.setInput("")
                                term.sendLine(line)
                            },
                        )
                        if (showCtrlC) {
                            TerminalActionButton("Ctrl-C", colors = termColors, onClick = { term.sendRaw(3.toChar().toString()) })
                        }
                        TerminalActionButton("Close", colors = termColors, onClick = { term.userCloseShell() })
                    }
                } else if (closedByUser) {
                    // The fix for "tapping Close completely disappears the terminal" — an honest
                    // state instead of a void, with a one-tap way back in. See the class doc for
                    // why this is a Reconnect button rather than an automatic instant respawn.
                    TerminalHint("Session closed.")
                    Spacer(Modifier.height(8.dp))
                    TerminalActionButton("Reconnect", selected = true, colors = termColors, onClick = { term.reconnect() })
                } else if (!connecting && connectError != null) {
                    // Without this, a failed Reconnect (or failed initial connect) rendered only
                    // the error hint — no button, no input box, no way back in. Retry re-runs the
                    // same reconnect() (phone, or the same remote alias).
                    Spacer(Modifier.height(8.dp))
                    TerminalActionButton("Retry", selected = true, colors = termColors, onClick = { term.reconnect() })
                }
            }
        }
    }

    pendingTrust?.let { verdict ->
        TrustDialog(
            verdict = verdict,
            onAccept = { term.resolveTrust(true) },
            onReject = { term.resolveTrust(false) },
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
 * [dev.fonebrew.ui.wire.WireButton]/`HyleChip`, which stay exactly as they are for every other
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
