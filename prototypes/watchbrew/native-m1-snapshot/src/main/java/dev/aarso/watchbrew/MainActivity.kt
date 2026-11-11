// SPDX-License-Identifier: Apache-2.0
package dev.aarso.watchbrew

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WatchApp(viewModel()) } }
    }
}

@Composable
private fun WatchApp(model: WatchViewModel) {
    val ui by model.ui.collectAsState()
    val s = ui.session
    var screen by rememberSaveable { mutableStateOf("home") }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val editor = rememberCapture(s.draft, screen, ui.loaded, model::dispatch)
    LaunchedEffect(screen) { listState.scrollToItem(0); focus.requestFocus() }
    BackHandler(screen != "home") { screen = "home" }
    Scaffold(positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
            modifier = Modifier.fillMaxSize().onRotaryScrollEvent {
                scope.launch { listState.scrollBy(it.verticalScrollPixels) }; true
            }.focusRequester(focus).focusable(),
        ) {
            item { Text("WatchBrew · DEMO", textAlign = TextAlign.Center, fontSize = 12.sp) }
            if (!ui.loaded) {
                item { Text("Loading saved session…") }
            } else {
                ui.storageError?.let { error -> item { Text(error, color = Color(0xFFFFD89C)) } }
                if (screen != "home") item { ActionChip("Back to home") { screen = "home" } }
                item { Text(s.notice, fontSize = 12.sp, textAlign = TextAlign.Center) }
                if (!ui.readOnly) when (screen) {
                    "home" -> {
                        item { Text("Your work, within reach", style = MaterialTheme.typography.title2, textAlign = TextAlign.Center) }
                        item { Text("${s.host.label}\n${s.mode.label}\n${if (s.connected) "Demo link available" else "Demo link offline"}", textAlign = TextAlign.Center) }
                        item { ActionChip("Capture request") { screen = "capture" } }
                        item { ActionChip("Task: ${s.task.label}") { screen = "task" } }
                        item { ActionChip("Sample message") { screen = "message" } }
                        item { ActionChip("Host & options") { screen = "settings" } }
                        item { ActionChip("Simulation controls") { screen = "simulation" } }
                        item { Text("${s.outbox.size} queued · branch ${s.branch}", fontSize = 12.sp) }
                    }
                    "capture" -> {
                        item { Text("Review your request", style = MaterialTheme.typography.title2) }
                        if (s.replyTo != null) item { Text("Replying to sample result", fontSize = 12.sp) }
                        item {
                            BasicTextField(value = editor.text, onValueChange = editor.update,
                                textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).background(Color(0xFFE9EFE4)).padding(8.dp)
                                    .semantics { contentDescription = "Request text. Review before sending." })
                        }
                        item { ActionChip("Dictate with system speech", onClick = editor.dictate) }
                        item { Text("System speech may use its provider's network. Offline preference is not guaranteed.", fontSize = 12.sp) }
                        if (editor.notice.isNotEmpty()) item { Text(editor.notice, fontSize = 12.sp) }
                        item { ActionChip(if (s.connected && s.outbox.isEmpty()) "Send to demo host" else "Queue on watch", !s.busy && !ui.readOnly) {
                            model.dispatch(Action.Submit); screen = "task"
                        } }
                        item { Text("Sending uses a simulated host. Draft changes are saved locally.", fontSize = 12.sp) }
                    }
                    "task" -> {
                        item { Text(s.task.label, style = MaterialTheme.typography.title2, textAlign = TextAlign.Center) }
                        item { Text(s.active?.text ?: "No active request", textAlign = TextAlign.Center) }
                        s.active?.let { active -> item { Text("${active.host.label}\n${active.mode.label}", fontSize = 12.sp, textAlign = TextAlign.Center) } }
                        item { Text("${s.outbox.size} queued", fontSize = 12.sp) }
                        item { ActionChip("Sync next queued", s.connected && !s.busy && s.outbox.isNotEmpty()) { model.dispatch(Action.SyncOne) } }
                        item { ActionChip("Request stop", s.connected && s.task == TaskStatus.RUNNING) { model.dispatch(Action.Stop) } }
                        if (s.task == TaskStatus.STOP_REQUESTED) item { Text("The host has not confirmed cancellation.", fontSize = 12.sp) }
                        item { ActionChip("Read sample message") { screen = "message" } }
                        item { ActionChip("Simulation controls") { screen = "simulation" } }
                    }
                    "message" -> {
                        item { Text("Sample result", style = MaterialTheme.typography.title2) }
                        item { Text("Check the build target configuration before starting again. This is illustrative content; no build or model was run.", textAlign = TextAlign.Center) }
                        item { Text("Message value: ${s.rating}") }
                        item { ActionChip("Rate lower", s.rating > -2) { model.dispatch(Action.Rate(s.rating - 1)) } }
                        item { ActionChip("Rate higher", s.rating < 2) { model.dispatch(Action.Rate(s.rating + 1)) } }
                        item { ActionChip("Reply / quote") { model.dispatch(Action.Reply); screen = "capture" } }
                        item { ActionChip("Regenerate from here") { screen = "regenerate" } }
                        item { ActionChip("Mode for next request") { screen = "mode" } }
                        item { Text(if (s.displayEnabled) "E-ink enabled as a preference. Display connection is not implemented." else "Optional e-ink is off. This message remains readable on the watch.", fontSize = 12.sp) }
                    }
                    "regenerate" -> {
                        item { Text("Create a branch?", style = MaterialTheme.typography.title2) }
                        item { Text("Regenerate the sample result on a new branch. Earlier demo events are retained.\n${s.host.label}\n${s.mode.label}", textAlign = TextAlign.Center) }
                        item { ActionChip("Create branch & run", s.connected && !s.busy && s.outbox.isEmpty()) { model.dispatch(Action.Regenerate); screen = "task" } }
                        item { ActionChip("Keep current branch") { screen = "message" } }
                    }
                    "mode" -> {
                        item { Text("Next request", style = MaterialTheme.typography.title2) }
                        ConversationMode.entries.forEach { mode -> item {
                            ActionChip("${if (mode == s.mode) "Selected: " else ""}${mode.label}") { model.dispatch(Action.Mode(mode)) }
                        } }
                        item { Text("Active and queued requests keep their captured mode. Core conversation integration comes later.", fontSize = 12.sp) }
                    }
                    "settings" -> {
                        item { Text("Simulated host", style = MaterialTheme.typography.title2) }
                        Host.entries.forEach { host -> item {
                            ActionChip("${if (host == s.host) "Selected: " else ""}${host.label}", !s.busy && s.outbox.isEmpty()) { model.dispatch(Action.SelectHost(host)) }
                        } }
                        item { ActionChip(if (s.displayEnabled) "Disable optional e-ink" else "Enable optional e-ink") { model.dispatch(Action.Display(!s.displayEnabled)) } }
                        item { Text("E-ink is optional. No Kindle is paired in this milestone. No cloud or remote connection is made.", fontSize = 12.sp) }
                    }
                    "simulation" -> {
                        item { Text("Demo controls", style = MaterialTheme.typography.title2) }
                        item { ActionChip(if (s.connected) "Simulate disconnect" else "Simulate reconnect") { model.dispatch(Action.Connection(!s.connected)) } }
                        item { ActionChip("Deliver sample result", s.connected && s.busy) { model.dispatch(Action.Complete) } }
                        item { ActionChip("Confirm requested stop", s.connected && s.task == TaskStatus.STOP_REQUESTED) { model.dispatch(Action.ConfirmStop) } }
                        item { ActionChip("Session events") { screen = "events" } }
                    }
                    "events" -> {
                        item { Text("Recent demo events", style = MaterialTheme.typography.title2) }
                        if (s.history.isEmpty()) item { Text("No events yet.") }
                        s.history.asReversed().forEach { event -> item { Text(event, fontSize = 12.sp) } }
                        item { Text("Local demo log: last 100 events. Full portable history is a later integration.", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Chip(onClick = onClick, enabled = enabled, label = { Text(label) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
}

private data class CaptureEditor(val text: String, val update: (String) -> Unit, val dictate: () -> Unit, val notice: String)

@Composable
private fun rememberCapture(initial: String, screen: String, loaded: Boolean, dispatch: (Action) -> Unit): CaptureEditor {
    var draft by rememberSaveable(screen, loaded) { mutableStateOf(initial) }
    var speechNotice by rememberSaveable { mutableStateOf("") }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) { draft = text.take(1200); dispatch(Action.Draft(draft)); speechNotice = "Review the transcription before sending." }
            else speechNotice = "No transcription returned. Type or try again."
        } else speechNotice = "Dictation canceled. Your draft is unchanged."
    }
    return CaptureEditor(draft, { draft = it.take(1200); dispatch(Action.Draft(draft)) }, {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Request for WatchBrew")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { speech.launch(intent) }
        catch (_: ActivityNotFoundException) { speechNotice = "System dictation is unavailable. Use the keyboard." }
        catch (_: SecurityException) { speechNotice = "System dictation is not permitted. Use the keyboard." }
    }, speechNotice)
}
