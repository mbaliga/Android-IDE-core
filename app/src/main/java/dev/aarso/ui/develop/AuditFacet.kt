package dev.aarso.ui.develop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aarso.domain.audit.AuditCheck
import dev.aarso.domain.audit.AuditChecklist
import dev.aarso.domain.audit.AuditStatus

/**
 * Audit (free/core): a **to-do list of checks, not a scanner**. Aarso deliberately does not
 * embed heavy static analysers — each row is honest advice, and its **Run** button fires a
 * chat prompt ([AuditChecklist.promptFor]) that runs the *real* tool where the model can act
 * on the repo. The owner records the outcome by hand (Pass/Fail/Skip), or an external QA app
 * surfaces it back (a stub, below). Nothing here claims a check passed on its own.
 *
 * Wireframe fidelity like the rest of the Develop room ([WireBox]/[WireButton]/[Hint]).
 * [onRunPrompt] hands the prompt text to the host (which pastes it into Chat).
 */
@Composable
fun AuditFacet(onRunPrompt: (String) -> Unit) {
    var checks by remember { mutableStateOf(AuditChecklist.default()) }

    Text("Audit", style = MaterialTheme.typography.titleSmall)
    Hint(
        "A to-do list of checks — not a scanner. Tapping Run fires a prompt in Chat that runs " +
            "the check for real; aarso doesn't build in heavy scanners. Record the outcome yourself.",
    )
    Spacer(Modifier.height(8.dp))

    val counts = AuditChecklist.summary(checks)
    Text(
        "${counts.total} checks · ✓${counts.passed} ✗${counts.failed} " +
            "· ${counts.running} running · ${counts.pending} pending · ${counts.skipped} skipped",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    for (check in checks) {
        WireBox {
            Text(check.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${check.category.name.lowercase()} · ${check.status.name.lowercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                check.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WireButton("Run") {
                    onRunPrompt(AuditChecklist.promptFor(check))
                    checks = AuditChecklist.withStatus(checks, check.id, AuditStatus.RUNNING)
                }
                WireButton("Pass", selected = check.status == AuditStatus.PASSED) {
                    checks = AuditChecklist.withStatus(checks, check.id, AuditStatus.PASSED)
                }
                WireButton("Fail", selected = check.status == AuditStatus.FAILED) {
                    checks = AuditChecklist.withStatus(checks, check.id, AuditStatus.FAILED)
                }
                WireButton("Skip", selected = check.status == AuditStatus.SKIPPED) {
                    checks = AuditChecklist.withStatus(checks, check.id, AuditStatus.SKIPPED)
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("External QA app", style = MaterialTheme.typography.titleSmall)
    Hint(
        "Optionally connect the owner's separate QA/testing app — it runs these checks externally " +
            "and surfaces results + fix automation back here. It's a watched object. Not connected.",
    )
    Spacer(Modifier.height(6.dp))
    var qaNote by remember { mutableStateOf<String?>(null) }
    WireButton("Connect QA app") {
        qaNote = "Not connected — the external QA app integration isn't wired up yet (stub)."
    }
    qaNote?.let {
        Spacer(Modifier.height(6.dp))
        Hint(it)
    }
}
