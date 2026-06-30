package dev.aarso.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aarso.flavor.InvocationFeatures

/**
 * In-app flagging of a generated output (Play's GenAI policy asks for a way to
 * report offensive AI content without leaving the app). Honouring binding rule 1
 * — no telemetry, no phoning home — the app transmits nothing itself: the dialog
 * prepares a report the *user* sends, via email when a reporting address is
 * configured, else the share sheet.
 */
@Composable
fun FlagOutputDialog(
    content: String,
    modelId: String?,
    onDismiss: () -> Unit,
) {
    val categories = listOf(
        "Harmful or dangerous",
        "Sexual content",
        "Hate or harassment",
        "Deceptive or misleading",
        "Other",
    )
    var selected by remember { mutableStateOf(categories.first()) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flag this output") },
        text = {
            Column {
                Text(
                    "Prepare a report you send yourself — Aarso transmits nothing on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (c in categories) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        RadioButton(selected = selected == c, onClick = { selected = c })
                        Text(c, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val body = buildString {
                        appendLine("Aarso output report")
                        appendLine("Category: $selected")
                        modelId?.let { appendLine("Model: $it") }
                        appendLine()
                        appendLine(content)
                    }
                    val email = InvocationFeatures.FLAG_REPORT_EMAIL
                    val intent = if (email.isNotBlank()) {
                        Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:$email")
                            putExtra(Intent.EXTRA_SUBJECT, "Aarso output report: $selected")
                            putExtra(Intent.EXTRA_TEXT, body)
                        }
                    } else {
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Aarso output report: $selected")
                            putExtra(Intent.EXTRA_TEXT, body)
                        }
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, "Send report"))
                    }
                    onDismiss()
                },
            ) {
                Text("Prepare report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
