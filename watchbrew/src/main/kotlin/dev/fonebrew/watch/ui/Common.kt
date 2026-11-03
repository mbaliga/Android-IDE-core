package dev.fonebrew.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import dev.fonebrew.watch.core.ConnectionView
import dev.fonebrew.watch.core.Provenance
import kotlinx.coroutines.delay

/** Every screen: time at the top, scroll position, edge vignette. */
@Composable
internal fun ScreenFrame(listState: ScalingLazyListState, content: @Composable () -> Unit) {
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) { content() }
}

/** The wall clock, re-read on an interval, for ages ("updated 3 min ago") and "waiting 12 s" counters. */
@Composable
internal fun rememberNowMs(intervalMs: Long = 1_000L): State<Long> =
    produceState(initialValue = System.currentTimeMillis(), intervalMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(intervalMs)
        }
    }

/** Who answered: glyph + words + engine id. The watched-object rule on the wrist. */
@Composable
internal fun ProvenanceRow(provenance: Provenance) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GlyphIcon(provenance.glyph, size = 16.dp)
        Column {
            Text(provenance.label, style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onBackground)
            Text(provenance.engine, style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The phone link as a **first-class state**: a large shape, a headline, one sentence of detail and a
 * way to act — never a spinner that implies progress. [onRetry] re-checks the link and pulls fresh data.
 */
@Composable
internal fun LinkStatePanel(view: ConnectionView, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GlyphIcon(view.glyph, size = 32.dp)
        Text(view.headline, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        Text(view.detail, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)
        CompactChip(onClick = onRetry, label = { Text("Check again") })
    }
}
