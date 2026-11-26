package dev.fonebrew.ui.develop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fonebrew.domain.agent.AgentRunLog
import dev.fonebrew.domain.agent.AgentStep
import dev.fonebrew.domain.agent.EngineBadge
import dev.fonebrew.domain.agent.RunTotals
import dev.fonebrew.domain.agent.StepLogPresenter

/**
 * The step log as plain, glyph-and-label text (never colour alone). Pure rendering lives in
 * [StepLogPresenter]; this only lays the lines out. Used live during a run and again, from the
 * kept [AgentRunLog], on the review screen.
 */
@Composable
fun AgentRunLogView(
    engine: EngineBadge,
    seedNotes: List<String>,
    steps: List<AgentStep>,
    totals: RunTotals,
    stopLine: String?,
    format: (Long) -> String,
) {
    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
        Text(StepLogPresenter.header(engine), style = MaterialTheme.typography.labelMedium)
        seedNotes.forEach { Hint("Context: $it") }
        steps.forEach { s -> StepLogPresenter.step(s).forEach { Hint(it) } }
        stopLine?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        Hint(StepLogPresenter.tokens(totals) + " | " + StepLogPresenter.cost(totals, engine.cloud, format))
    }
}

@Composable
fun AgentRunLogView(log: AgentRunLog, format: (Long) -> String) =
    AgentRunLogView(log.engine, log.seedNotes, log.outcome.steps, log.outcome.totals, StepLogPresenter.stop(log.outcome), format)
