package dev.fonebrew.ui.loops

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.domain.loop.LoopBudget

/**
 * `LOOP_PHONE_AUTHORING_SPEC.md` §3.1 — Intent View: objective, inputs/outputs, side-effect/
 * authority envelope, run budget envelope, and the current validation summary. Every value here
 * is [StagePresenter]'s honest derivation from the actual draft — nothing invented for display.
 *
 * The Distill entry point stays reachable from [LoopRoom]'s toolbar ("Distill…"), unchanged;
 * §3.1/§6 both require Distiller output to never auto-publish, which is already true — a
 * distilled draft only ever replaces the in-memory draft, and only an explicit Save persists it.
 */
@Composable
fun IntentView(
    objective: String,
    onObjectiveChange: (String) -> Unit,
    onExpandObjective: () -> Unit,
    inputParams: List<String>,
    validation: StagePresenter.ValidationSummary,
    envelope: StagePresenter.AuthorityEnvelopeSummary,
    /** The budget most recently used to Run this draft (or mid-run right now) — [LoopBudget] has
     *  no persisted "default" slot on a draft today, so this is an honest read of the real
     *  session state ([LoopRoom]'s own `runBudget`), not a fabricated per-draft setting; the
     *  actual editable budget form is, as today, the Run sheet. */
    lastRunBudget: LoopBudget?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalHyleColors.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Objective", style = MaterialTheme.typography.titleSmall)
        HyleField(objective, onObjectiveChange, label = "What is this loop for?", singleLine = false, modifier = Modifier.fillMaxWidth())
        // §3.4's >200-char full-screen rule applies to any long-text field, objective included.
        if (objective.length > 200) {
            HyleButton("Expand ↗", onClick = onExpandObjective, modifier = Modifier.padding(top = 4.dp))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text("Inputs", style = MaterialTheme.typography.titleSmall)
        if (inputParams.isEmpty()) {
            Text("No \${…} placeholders referenced yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(inputParams.joinToString { "\${$it}" }, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Outputs: the final stage's output — this draft has no independently typed output " +
                "schema yet (named follow-up: LOOP_PHONE_AUTHORING_SPEC.md §3.1's typed output field is not carried by this editor's node model).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text("Authority envelope", style = MaterialTheme.typography.titleSmall)
        if (envelope.watchedCloudModels.isEmpty() && envelope.onDeviceModels.isEmpty()) {
            Text("No model bindings yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (envelope.onDeviceModels.isNotEmpty()) {
                Text("⌂ On-device: ${envelope.onDeviceModels.joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
            if (envelope.watchedCloudModels.isNotEmpty()) {
                // "watched object" — binding rule 2: cloud is opt-in, always visibly marked.
                Text("☁ Watched (cloud): ${envelope.watchedCloudModels.joinToString()}", style = MaterialTheme.typography.bodySmall, color = colors.violet)
            }
        }
        if (envelope.sideEffectCounts.isNotEmpty()) {
            Text(
                "Side effects declared: " + envelope.sideEffectCounts.entries.joinToString { "${it.key} ×${it.value}" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (envelope.undeclaredSideEffectTaskCount > 0) {
            Text(
                "${envelope.undeclaredSideEffectTaskCount} task(s) have no declared side-effect class yet — set it from the Node Sheet.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.violet,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text("Run budget envelope", style = MaterialTheme.typography.titleSmall)
        if (lastRunBudget == null) {
            Text("No budget set yet — configure one from the Run sheet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                listOfNotNull(
                    lastRunBudget.maxSteps?.let { "$it step(s)" },
                    lastRunBudget.maxWallMs?.let { "${it / 1000}s wall time" },
                    lastRunBudget.maxTokensTotal?.let { "$it tokens" },
                ).joinToString(" · ").ifBlank { "No limits set" },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text("Validation summary", style = MaterialTheme.typography.titleSmall)
        if (validation.findings.isEmpty()) {
            Text("✓ No issues found.", style = MaterialTheme.typography.bodySmall, color = colors.success)
        } else {
            for (f in validation.findings) {
                val glyph = when (f.severity) {
                    StagePresenter.ValidationFinding.Severity.BLOCKER -> "⛔"
                    StagePresenter.ValidationFinding.Severity.WARNING -> "⚠"
                    StagePresenter.ValidationFinding.Severity.INFO -> "ℹ"
                }
                val tint = when (f.severity) {
                    StagePresenter.ValidationFinding.Severity.BLOCKER -> colors.violet
                    StagePresenter.ValidationFinding.Severity.WARNING -> colors.violet
                    StagePresenter.ValidationFinding.Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text("$glyph ", color = tint, style = MaterialTheme.typography.bodySmall)
                    Text(f.message, color = tint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
