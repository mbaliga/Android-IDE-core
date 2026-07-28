package dev.aarso.ui.rooms

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aarso.AarsoApp
import dev.aarso.domain.cost.FreeTierProvider
import dev.aarso.ui.wire.WireBox
import dev.aarso.ui.wire.WireButton

/**
 * The free-tier guide (owner ask): which cloud sources are free and how much. Grouped into
 * ongoing-free vs trial-credit. Every figure links to its source and the whole list shows when
 * it was last refreshed — these drift, so we show provenance, not assertion (binding rule 8).
 */
@Composable
fun FreeTiersScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val container = (context.applicationContext as AarsoApp).container
    val session = container.sessionStore
    val scope = rememberCoroutineScope()

    var catalog by remember { mutableStateOf(container.freeTierStore.catalog()) }
    val providers by container.providerStore.providers.collectAsState()
    val usage by container.freeTierUsageStore.usage.collectAsState()
    val autoUpdate by session.freeTierAutoUpdate.collectAsState()
    val sourceUrl by session.freeTierSourceUrl.collectAsState()

    var updating by remember { mutableStateOf(false) }
    var updateNote by remember { mutableStateOf<String?>(null) }
    var pendingConsent by remember { mutableStateOf<FtConsent?>(null) }

    fun runUpdate() {
        scope.launch {
            updating = true
            updateNote = "contacting the source online…" // the reminder that a fetch is happening
            container.freeTierUpdater.update(sourceUrl).fold(
                { lu -> catalog = container.freeTierStore.catalog(); updateNote = "updated (list dated $lu)" },
                { e -> updateNote = "update failed: ${e.message}" },
            )
            updating = false
        }
    }

    // Auto-update only runs because the user opted in (with consent); the note makes it visible.
    LaunchedEffect(Unit) { if (autoUpdate) runUpdate() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WireButton("‹ Back", onClick = onClose)
            Spacer(Modifier.width(12.dp))
            Text("Free tiers", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Cloud sources with a free tier. These numbers drift — each links to its source; " +
                "always confirm before relying on it. Last refreshed: ${catalog.lastUpdated.ifBlank { "—" }}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // Updating the list — an explicit, consented online fetch (on-device stays the default).
        WireBox {
            Text("Updating the list", style = MaterialTheme.typography.titleSmall)
            Text(
                "Updating fetches the latest list ONLINE from the source below — the only time " +
                    "Aarso reaches out for this, and never without your say-so.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text("source: $sourceUrl", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            WireButton(if (updating) "Updating…" else "Update now", enabled = !updating, onClick = { pendingConsent = FtConsent.MANUAL })
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-update on open", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = autoUpdate,
                    onCheckedChange = { want ->
                        if (want) pendingConsent = FtConsent.ENABLE_AUTO else session.setFreeTierAutoUpdate(false)
                    },
                )
            }
            if (autoUpdate) Text(
                "On — Aarso will fetch this list online each time you open this screen, until you turn it off.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            updateNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(16.dp))

        // Your usage — what you've spent against each configured provider's free tier today.
        if (providers.isNotEmpty()) {
            Text("Your usage", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            for (p in providers) {
                val u = usage[p.id] ?: dev.aarso.data.ProviderUsage()
                val tier = dev.aarso.domain.cost.FreeTierMatch.match(p, catalog)
                WireBox {
                    Text(p.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "today: ${u.requestsToday} req · ${u.tokensToday / 1000}K tok   ·   month: ${u.tokensThisMonth / 1000}K tok",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    tier?.let { t ->
                        val limitText = when {
                            t.requestsPerDay != null -> "of ~${t.requestsPerDay} req/day (${t.name})"
                            t.tokensPerDay != null -> "of ~${t.tokensPerDay / 1000}K tok/day (${t.name})"
                            t.tokensPerMonth != null -> "of ~${t.tokensPerMonth / 1_000_000}M tok/month (${t.name})"
                            else -> "free tier: ${t.name}"
                        }
                        Text(limitText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        if (catalog.ongoing.isNotEmpty()) {
            Text("Ongoing free", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            for (p in catalog.ongoing) { ProviderCard(p) { open(context, p.sourceUrl) }; Spacer(Modifier.height(8.dp)) }
            Spacer(Modifier.height(8.dp))
        }
        if (catalog.trial.isNotEmpty()) {
            Text("Trial credit", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            for (p in catalog.trial) { ProviderCard(p) { open(context, p.sourceUrl) }; Spacer(Modifier.height(8.dp)) }
        }
    }

    // Explicit consent before any network reach — and a reminder of what auto-update will do.
    pendingConsent?.let { consent ->
        AlertDialog(
            onDismissRequest = { pendingConsent = null },
            title = { Text("Go online to update?") },
            text = {
                Text(
                    "This connects to the internet and fetches the latest free-tier list from:\n\n" +
                        "$sourceUrl\n\n" +
                        "Aarso is on-device by default; this is the only time it reaches out for this list." +
                        if (consent == FtConsent.ENABLE_AUTO)
                            " Auto-update will then do this each time you open this screen, until you turn it off."
                        else "",
                )
            },
            confirmButton = {
                WireButton("Allow", onClick = {
                    pendingConsent = null
                    if (consent == FtConsent.ENABLE_AUTO) session.setFreeTierAutoUpdate(true)
                    runUpdate()
                })
            },
            dismissButton = { WireButton("Cancel", onClick = { pendingConsent = null }) },
        )
    }
}

/** What a pending consent is for: a one-off manual update, or enabling auto-update. */
private enum class FtConsent { MANUAL, ENABLE_AUTO }

@Composable
private fun ProviderCard(p: FreeTierProvider, onSource: () -> Unit) {
    WireBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (!p.requiresCard) Text("no card", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(4.dp))
        Text(p.summary, style = MaterialTheme.typography.bodySmall)
        val caps = buildList {
            p.requestsPerDay?.let { add("$it req/day") }
            p.tokensPerDay?.let { add("${it / 1000}K tok/day") }
            p.tokensPerMonth?.let { add("${it / 1_000_000}M tok/mo") }
            p.trialCredit?.let { add(it) }
        }
        if (caps.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(caps.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        WireButton("View source", onClick = onSource)
    }
}

private fun open(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
