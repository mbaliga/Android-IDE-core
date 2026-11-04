package dev.fonebrew.ui.develop

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.fonebrew.FonebrewApp
import dev.fonebrew.contracts.execution.ExecutionEvent
import dev.fonebrew.domain.execution.RunAuthorityUiState
import dev.fonebrew.domain.execution.RunCommandPresets
import dev.fonebrew.domain.execution.RunOutcome
import dev.fonebrew.domain.execution.RunOutcomes
import dev.fonebrew.domain.execution.RunTarget
import dev.fonebrew.domain.execution.RunTargetCatalog
import dev.fonebrew.ui.components.ProvenanceBadge
import kotlinx.coroutines.launch

/**
 * Develop -> Audit -> **Run**: run your own product's build/test command for real, on a
 * configured target, through the Execution Contract + Authority engine (WP-4/WP-5's first real
 * consumer — see [dev.fonebrew.data.execution.RunSessionDriver]). Lives inside the Audit tab
 * rather than a fifth top-level tab: the free-core Develop surface is deliberately exactly
 * Hardware/Files/Terminal/Audit (see [DevelopRoom]'s own doc comment), and Audit is already the
 * "run a check for real" surface — its existing per-item **Run** buttons fire a chat prompt
 * ([AuditChecklist.promptFor]); this panel is the one place in Audit where "Run" instead means
 * *actually execute a command on a real target and record what happened*, which is why it keeps
 * its own distinct label ("Run command") rather than colliding with those buttons' wording.
 *
 * Only the providers that exist are offered: [RunTargetCatalog] lists this phone plus whatever
 * SSH hosts / Git hosts are actually connected — never a target with nothing behind it.
 */
@Composable
fun RunPanel() {
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    val driver = container.runSessionDriver
    val scope = rememberCoroutineScope()

    val sshHosts by container.remoteHostStore.hosts.collectAsState()
    val gitHosts by container.gitHostStore.hosts.collectAsState()
    val targets = remember(sshHosts, gitHosts) {
        RunTargetCatalog.list(localAvailable = true, sshHosts = sshHosts, ciHosts = gitHosts)
    }
    var selectedTargetId by remember { mutableStateOf(targets.firstOrNull()?.targetId) }
    LaunchedEffect(targets) {
        if (targets.none { it.targetId == selectedTargetId }) selectedTargetId = targets.firstOrNull()?.targetId
    }
    val target = targets.firstOrNull { it.targetId == selectedTargetId }

    var command by remember(target?.targetId) {
        mutableStateOf(
            when (target) {
                is RunTarget.Ci -> RunCommandPresets.DEFAULT_CI_WORKFLOW
                null -> ""
                else -> RunCommandPresets.DEFAULT_SHELL_COMMAND
            },
        )
    }
    // Best-effort preset detection reads the connected repo's own root listing — only for a
    // shell-command target; a CI target's "command" is a workflow file name (see
    // RunCommandPresets.DEFAULT_CI_WORKFLOW's own doc comment), which a root file listing can't
    // usefully suggest. Detection only ever pre-fills the editable field — it never runs.
    LaunchedEffect(target?.targetId) {
        if (target == null || target is RunTarget.Ci) return@LaunchedEffect
        val host = container.gitHostStore.hosts.value.firstOrNull() ?: return@LaunchedEffect
        val token = container.gitHostStore.token(host.id) ?: return@LaunchedEffect
        container.gitBrowse.list(host, token, "").onSuccess { entries ->
            RunCommandPresets.detect(entries.map { it.name })?.let { command = it }
        }
    }

    var gate by remember { mutableStateOf<RunAuthorityUiState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<RunOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun startRun(t: RunTarget, grantId: String) {
        busy = true; output = ""; outcome = null; error = null; gate = null
        scope.launch {
            val events = driver.execute(t, command, grantId)
            if (events == null) {
                error = "No provider configured for ${t.label}" +
                    (if (t is RunTarget.Ci) " — add a token for this host in Settings → Global → Git & coding." else ".")
                busy = false
                return@launch
            }
            try {
                events.collect { event ->
                    when (event) {
                        is ExecutionEvent.OutputChunk -> output += event.text
                        is ExecutionEvent.StateChanged -> event.reason?.let { output += "\n[$it]\n" }
                        is ExecutionEvent.ReceiptReady -> {
                            driver.persistReceipt(event.receipt)
                            outcome = RunOutcomes.from(event.receipt, t, command)
                        }
                        is ExecutionEvent.HeartbeatReceived -> {}
                    }
                }
            } catch (e: Exception) {
                error = "run failed: ${e.message}"
            }
            busy = false
        }
    }

    fun beginRun() {
        val t = target ?: return
        if (command.isBlank()) return
        busy = true; error = null; outcome = null
        scope.launch {
            when (val g = driver.evaluate(t)) {
                is RunAuthorityUiState.Allowed -> startRun(t, g.grantId)
                is RunAuthorityUiState.NeedsConfirmation, is RunAuthorityUiState.NeedsGrant -> { gate = g; busy = false }
            }
        }
    }

    Text("Run command", style = MaterialTheme.typography.titleSmall)
    Hint(
        "Run your product's own build/test command for real — not a chat prompt. Every run goes " +
            "through this app's authority check first: a remote host or CI target always needs a " +
            "fresh confirm; this phone runs straight through once you've allowed it once.",
    )
    Spacer(Modifier.height(8.dp))

    if (targets.size <= 1) {
        Hint(
            "Only this phone is available. Add an SSH host (Settings → Global → your machines) or " +
                "connect a Git host (Settings → Global → Git & coding) to run against a homelab " +
                "runner or your repo's own CI.",
        )
        Spacer(Modifier.height(6.dp))
    }
    Text("Target", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        targets.forEach { t -> WireButton(t.label, selected = t.targetId == selectedTargetId, onClick = { selectedTargetId = t.targetId }) }
    }
    target?.let {
        Spacer(Modifier.height(4.dp))
        ProvenanceBadge(it.provenance)
    }
    Spacer(Modifier.height(8.dp))

    dev.aarso.hyle.cells.HyleField(
        command, { command = it },
        label = if (target is RunTarget.Ci) "Workflow file (e.g. ci.yml)" else "Command",
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    WireButton(if (busy) "Running…" else "Run", enabled = !busy && target != null && command.isNotBlank(), onClick = { beginRun() })

    when (val g = gate) {
        null, is RunAuthorityUiState.Allowed -> {} // Allowed never lands here -- beginRun() executes straight through it.
        is RunAuthorityUiState.NeedsGrant -> {
            Spacer(Modifier.height(8.dp))
            WireBox {
                Text("Allow ${target?.label} to run commands?", style = MaterialTheme.typography.bodyMedium)
                Hint(
                    "No authority reaches this target yet (${g.reasonCode.lowercase().replace('_', ' ')}). " +
                        "Allowing scopes only this one target and this one capability — never a blanket grant.",
                )
                Spacer(Modifier.height(6.dp))
                WireButton("Allow", onClick = {
                    val t = target ?: return@WireButton
                    busy = true
                    scope.launch {
                        when (val g2 = driver.grantAndReevaluate(t)) {
                            is RunAuthorityUiState.Allowed -> startRun(t, g2.grantId)
                            is RunAuthorityUiState.NeedsConfirmation -> { gate = g2; busy = false }
                            is RunAuthorityUiState.NeedsGrant -> { error = "still denied: ${g2.reasonCode}"; gate = null; busy = false }
                        }
                    }
                })
            }
        }
        is RunAuthorityUiState.NeedsConfirmation -> {
            Spacer(Modifier.height(8.dp))
            WireBox {
                Text("Confirm: run on ${target?.label}?", style = MaterialTheme.typography.bodyMedium)
                Hint("This target is watched (${g.promptRef}) — confirm each run, on purpose, every time.")
                Spacer(Modifier.height(6.dp))
                WireButton("Confirm & run", onClick = { target?.let { startRun(it, g.grantId) } })
            }
        }
    }

    error?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

    outcome?.let { o ->
        Spacer(Modifier.height(8.dp))
        WireBox {
            Text(
                (if (o.succeeded) "✓ " else "✗ ") + o.exitState.name.lowercase().replace('_', ' '),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                o.targetLabel + (o.durationMs?.let { " · ${it / 1000.0}s" } ?: "") + " · receipt ${o.receiptId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (output.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        WireBox {
            Text(output, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
}
