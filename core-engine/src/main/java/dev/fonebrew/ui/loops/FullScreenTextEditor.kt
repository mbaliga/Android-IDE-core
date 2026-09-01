package dev.fonebrew.ui.loops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleField

/**
 * `LOOP_PHONE_AUTHORING_SPEC.md` §3.4 — "Any Node Sheet text field whose content commonly exceeds
 * roughly 200 characters ... MUST offer a full-screen, distraction-free editing mode with
 * per-field autosave." This is that mode: a plain full-screen field with nothing else competing
 * for attention. "Per-field autosave" here piggybacks on the same draft-level autosave
 * [LoopRoom] already runs on every keystroke via [onValueChange] — there is no second,
 * field-scoped persistence path to duplicate.
 *
 * Dictation and import-from-file/share-sheet (also required by §3.4) are not wired here — this
 * container has no device to verify a mic/share-sheet flow against (`CLAUDE.md` "Environment
 * honesty"); named follow-up, not a silent gap.
 */
@Composable
fun FullScreenTextEditDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    HyleButton("Done", onClick = onDone)
                }
                Text(
                    "${value.length} characters",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                HyleField(
                    value, onValueChange, label = title, singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
