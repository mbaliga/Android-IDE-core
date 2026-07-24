package dev.aarso.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aarso.FonebrewApp
import dev.aarso.data.DeviceInfo
import dev.aarso.domain.catalog.ModelCatalog
import dev.aarso.domain.device.FitVerdict
import dev.aarso.domain.device.ModelFit
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.hyle.HyleCard
import dev.aarso.ui.hyle.HyleTitle
import dev.aarso.ui.onboarding.AiCoreAvailability
import dev.aarso.ui.theme.LocalHyleColors
import kotlinx.coroutines.launch

/**
 * First-run wizard: two stance screens, then model setup. Ends with either on-device Gemini
 * Nano confirmed available, or a real GGUF download already started in the background (the
 * same [dev.aarso.data.DownloadCenter] job [ChatScreen]'s setup card reads) — never a bare
 * "figure it out later" that would leave a fresh install with nothing to chat against.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f), userScrollEnabled = false) { page ->
                when (page) {
                    0 -> OnboardingPage(
                        title = "Fonebrew",
                        subtitle = "“mirror” — Konkani",
                        body = "AI models run on this phone. Your words stay on it.\n\n" +
                            "No accounts. No analytics. No telemetry — ever.",
                    )
                    1 -> OnboardingPage(
                        title = "Nothing hidden",
                        subtitle = "including from yourself",
                        body = "Cloud models are opt-in, per use, and always marked as watched.\n\n" +
                            "Every conversation is a tree: every fork, retry, and model switch " +
                            "stays visible and reversible.",
                    )
                    else -> ModelSetupPage(onReady = onDone)
                }
            }

            val hyleColors = LocalHyleColors.current
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { i ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (pager.currentPage == i) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        hyleColors.hairline
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // Reserve this row's space on every page (rather than including/excluding the
                // buttons outright) so the dot row never shifts vertically as the user swipes —
                // on page 2 (model setup) it goes invisible/disabled because that page draws its
                // own Begin/Skip further down inside ModelSetupPage.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.alpha(if (pager.currentPage < 2) 1f else 0f),
                ) {
                    HyleButton(
                        "Skip",
                        secondary = true,
                        enabled = pager.currentPage < 2,
                        // Skip on page 1 or 2 must still land on model setup (page 3) — a fresh
                        // install must never end onboarding with no model confirmed or
                        // downloading. Only that final page's own Skip actually exits.
                        onClick = { scope.launch { pager.animateScrollToPage(2) } },
                    )
                    HyleButton(
                        "Continue",
                        enabled = pager.currentPage < 2,
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(title: String, subtitle: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The model-setup step: checks for on-device Gemini Nano ([AiCoreAvailability]), and always
 * offers a short list of GGUF models this phone's RAM can actually run ([ModelFit]). Either path
 * — confirming Nano or starting a download — flips [ready] true; [onReady] (== the wizard's
 * [onDone]) only ever fires from here once one of those has happened, or the user explicitly
 * skips (their call, not a trap).
 */
@Composable
private fun ModelSetupPage(onReady: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as FonebrewApp).container
    val session = container.sessionStore
    val scope = rememberCoroutineScope()
    val c = LocalHyleColors.current

    var checking by remember { mutableStateOf(true) }
    var aiCoreAvailable by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        aiCoreAvailable = AiCoreAvailability.probe(context.applicationContext)
        checking = false
    }

    val device = remember { DeviceInfo.read(context.applicationContext) }
    val fitting = remember(device) {
        ModelCatalog.models
            .filter { ModelFit.check(it.sizeBytes, device).verdict == FitVerdict.FITS }
            .sortedByDescending { it.sizeBytes }
            .take(3)
    }
    val active by container.downloadCenter.active.collectAsState()

    Column(Modifier.fillMaxSize()) {
        HyleTitle("Pick a model")
        Text(
            "So you start chatting with a real model, not a placeholder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        when {
            checking -> HyleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking this phone for on-device Gemini…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            aiCoreAvailable -> HyleCard {
                Text("⌂ Gemini Nano is already on this phone", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "No download — runs entirely on-device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                HyleButton("Use Gemini Nano", onClick = {
                    session.setAiCoreEnabled(true)
                    ready = true
                })
            }
            else -> Text(
                "Gemini Nano isn't available on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("Or download a model", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        if (fitting.isEmpty()) {
            Text(
                "No catalog model comfortably fits this phone's RAM — open Models later for the full list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        fitting.forEach { m ->
            val progress = active[m.id]?.progress
            HyleCard(Modifier.padding(top = 8.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${m.params} · ${m.quant} · ${"%.1f".format(m.sizeBytes / 1_000_000_000.0)} GB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    progress == null -> HyleButton("Download", onClick = {
                        container.downloadCenter.enqueue(m.id, m.downloadUrl, m.hfFile, container.modelDownloader)
                        ready = true
                    })
                    progress.done -> Text(
                        "Downloaded",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    progress.error != null -> Column {
                        Text(
                            "failed: ${progress.error}",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        HyleButton("Retry", onClick = {
                            container.downloadCenter.retry(m.id)
                            ready = true
                        })
                    }
                    else -> Column {
                        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "downloading… ${(progress.fraction * 100).toInt()}% — continues in the background",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            HyleButton("Skip for now", secondary = true, onClick = onReady)
            Spacer(Modifier.width(8.dp))
            HyleButton("Begin", enabled = ready, onClick = onReady)
        }
    }
}
