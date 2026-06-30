package dev.aarso.ui.guide

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.aarso.domain.guide.Guide

/**
 * A small "?" help affordance that opens a step-by-step walk-through for a setup. Drop one
 * next to any setup heading: `HelpIcon(Guides.CONNECT_GIT)`. The guided steps are the owner's
 * ask — "see here, click here" — made reusable.
 */
@Composable
fun HelpIcon(guide: Guide, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(22.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Text("?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
    if (open) GuideSheet(guide, onDismiss = { open = false })
}

@Composable
fun GuideSheet(guide: Guide, onDismiss: () -> Unit) {
    var i by remember { mutableStateOf(0) }
    val step = guide.steps[i.coerceIn(0, guide.steps.lastIndex)]
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(20.dp).fillMaxWidth()) {
                Text(guide.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("Step ${i + 1} of ${guide.steps.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Text(step.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(step.body, style = MaterialTheme.typography.bodyMedium)
                step.hint?.let {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(8.dp),
                    ) { Text("→ $it", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (i > 0) i-- }, enabled = i > 0) { Text("Back") }
                    if (i < guide.steps.lastIndex) {
                        TextButton(onClick = { i++ }) { Text("Next") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Done") }
                    }
                }
            }
        }
    }
}
