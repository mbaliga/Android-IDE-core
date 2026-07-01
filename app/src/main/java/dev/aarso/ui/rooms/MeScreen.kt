package dev.aarso.ui.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aarso.AarsoApp
import dev.aarso.domain.provenance.ProvenanceState
import dev.aarso.ui.components.ByProviderList
import dev.aarso.ui.components.BudgetRingView
import dev.aarso.ui.components.InputOutputCard
import dev.aarso.ui.components.ProvenanceBadge
import dev.aarso.ui.components.SovereigntyCard
import dev.aarso.ui.components.StatePane
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.hyle.HyleTitle
import dev.aarso.ui.state.MyselfPresenter
import dev.aarso.ui.wire.WireBox

/**
 * "Me · Myself · I" (IA meta). Three parts:
 *  - Drift / self-observation (the Aarso mirror) — ships **inert** by design. The §5b/§5c
 *    engine is owner-blocked on GitHub Issue #2 (binding rule 4); we never invent the baseline.
 *  - Linked accounts — the cloud providers and Git hosts you've connected (all watched).
 *  - Usage overview — per-provider request/token usage, a monthly total, and an honest note
 *    on the savings/behaviour metrics that need more data (or the blocked engine) to compute.
 *
 * Everything here is on-device; nothing is sent anywhere (rules 1 & 2).
 */
@Composable
fun MeScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val container = (LocalContext.current.applicationContext as AarsoApp).container
    val providers by container.providerStore.providers.collectAsState()
    val imageProviders by container.imageProviderStore.providers.collectAsState()
    val hosts by container.gitHostStore.hosts.collectAsState()
    val usage by container.freeTierUsageStore.usage.collectAsState()
    // The on-device usage ledger (Doc 07 "Myself"); empty until the per-turn capture writer
    // lands — the StatePane renders the honest empty state meanwhile.
    val ledgerEntries by container.ledgerStore.entries().collectAsState(initial = emptyList())

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
            HyleButton("‹ Back", onClick = onClose)
        }
        HyleTitle("Me · Myself · I")

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ── Drift / self-observation (inert) ──────────────────────────────
            Text("Self-observation & drift", style = MaterialTheme.typography.titleMedium)
            WireBox {
                Text("Paused by design.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "The Aarso mirror reflects your idiolect back to you and tracks drift over time. " +
                        "It stays inert until you set the baseline yourself (GitHub Issue #2) — Aarso " +
                        "won't infer who you are. On-device only; no profiling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            // ── Linked accounts ───────────────────────────────────────────────
            Text("Linked accounts", style = MaterialTheme.typography.titleMedium)
            if (providers.isEmpty() && imageProviders.isEmpty() && hosts.isEmpty()) {
                Text(
                    "Nothing linked yet. Add cloud providers in Settings → Text/Image, or a Git host " +
                        "in Settings → Global → Git & coding. Each is a watched object.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                providers.forEach { AccountRow(it.displayName, "${it.kind.label} · text · watched") }
                imageProviders.forEach { AccountRow(it.displayName, "image · watched") }
                hosts.forEach { AccountRow("${it.owner}/${it.repo}", "Git host · ${it.kind.name.lowercase()}") }
            }
            HorizontalDivider()

            // ── Usage overview ────────────────────────────────────────────────
            Text("Usage overview", style = MaterialTheme.typography.titleMedium)
            val monthTotal = usage.values.sumOf { it.tokensThisMonth }
            WireBox {
                Text("This month: ${fmtTokens(monthTotal)} tokens across cloud providers", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "On-device generation is free and unmetered — it doesn't count here.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (usage.isEmpty()) {
                Text("No cloud usage recorded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                usage.forEach { (id, u) ->
                    val name = providers.firstOrNull { it.id == id }?.displayName ?: id
                    WireBox {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "today ${u.requestsToday} req · ${fmtTokens(u.tokensToday)} tok   ·   month ${fmtTokens(u.tokensThisMonth)} tok",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Cost per model, savings (on-device & free-tier vs Claude/GPT), and which model you " +
                    "use for which task are computed as more usage accrues and the drift baseline lands. " +
                    "Tracked, not faked.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // ── Usage — detailed (the Myself ledger, Doc 07) ──────────────────
            // Driven by the on-device ledger through the JVM-tested MyselfPresenter; renders
            // the input/output, sovereignty (on-device vs cloud), by-provider, and budget-ring
            // cards. Empty until the per-turn capture writer lands — shown honestly.
            Text("Usage — detailed", style = MaterialTheme.typography.titleMedium)
            val locale = java.util.Locale.getDefault()
            StatePane(MyselfPresenter.present(ledgerEntries)) { view ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InputOutputCard(view.totals, locale, "USD")
                    SovereigntyCard(view.provenanceSplit, locale)
                    ByProviderList(view.byProvider, locale, "USD")
                    view.budgetRings.forEach { ring -> BudgetRingView(ring, "Budget", locale) }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(title: String, subtitle: String) {
    WireBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Every linked account is off-device — a watched object (binding rule 2). The badge
            // is the four-state model's glyph+label (never colour-alone, TalkBack-labelled).
            ProvenanceBadge(ProvenanceState.CLOUD)
        }
    }
    Spacer(Modifier.height(6.dp))
}

private fun fmtTokens(t: Long): String = when {
    t >= 1_000_000 -> "%.1fM".format(t / 1_000_000.0)
    t >= 1_000 -> "${t / 1_000}K"
    else -> t.toString()
}
