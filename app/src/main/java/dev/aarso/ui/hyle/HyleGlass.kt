package dev.aarso.ui.hyle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.tokens.HyleTokens

/** The real Hyle glass-pane token — a translucent near-black, meant to sit over content. */
val HyleGlassPane: Color = Color(HyleTokens.Color.colorPaletteGlassPane)

/**
 * A translucent glass pane over the whole surface: a scrim (Hyle's [HyleGlassPane] token) that
 * **swallows all touches** underneath it — the "front surface is untouchable" treatment for an
 * obstructive operation (a destructive git write, a model load that can't be interrupted
 * mid-flight). [label] is spoken by a screen reader and shown under the spinner; keep it short
 * and concrete ("Loading model…"), never a bare "Working". Place as the last child of the
 * enclosing `Box` so it draws (and hit-tests) on top of everything else.
 */
@Composable
fun HyleBlockingOverlay(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(HyleGlassPane)
            // An empty pointerInput still claims the gesture arena, so nothing beneath ever sees a touch.
            .pointerInput(Unit) {}
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
