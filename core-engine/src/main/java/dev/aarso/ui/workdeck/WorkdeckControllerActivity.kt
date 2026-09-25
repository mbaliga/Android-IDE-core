package dev.aarso.ui.workdeck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.aarso.data.workdeck.WorkdeckInboundEvent
import dev.aarso.data.workdeck.WorkdeckPairingStore
import dev.aarso.data.workdeck.WorkdeckSessionHub
import dev.aarso.domain.workdeck.WorkdeckNativeDocument
import dev.aarso.domain.workdeck.WorkdeckNativeSection
import dev.aarso.domain.workdeck.WorkdeckSectionKind
import dev.aarso.service.WorkdeckAccessibilityService
import dev.aarso.service.WorkdeckService
import dev.aarso.ui.ChatViewModel
import dev.aarso.ui.theme.AarsoTheme
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/** Landscape-first phone control deck; the lower half is always the user's normal Android IME. */
class WorkdeckControllerActivity : ComponentActivity() {
    private val chat: ChatViewModel by viewModels { ChatViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContent { AarsoTheme { WorkdeckControllerSurface(chat = chat, onClose = ::finish) } }
    }
}

@Composable
private fun WorkdeckControllerSurface(chat: ChatViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val pairing = remember { WorkdeckPairingStore(context) }
    var editor by remember { mutableStateOf<EditText?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf("Active model") }
    var status by remember { mutableStateOf<String?>(null) }
    val ui by chat.uiState.collectAsState()
    val server by WorkdeckSessionHub.state.collectAsState()
    val revision = remember { AtomicLong(1) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            WorkdeckSessionHub.receive(WorkdeckInboundEvent.Control("attach:${it}"))
            status = "Attached opaque Fylz/SAF handle"
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let {
            WorkdeckSessionHub.receive(WorkdeckInboundEvent.Control("attach-photo:${it}"))
            status = "Attached Foto Xplorr/photo handle"
        }
    }

    fun control(value: String) { WorkdeckSessionHub.receive(WorkdeckInboundEvent.Control(value)) }
    fun type(value: String) { WorkdeckSessionHub.receive(WorkdeckInboundEvent.Keyboard(value)) }
    fun send() {
        val text = editor?.text?.toString().orEmpty()
        if (text.isNotBlank()) {
            chat.send(text)
            control("send")
            editor?.text?.clear()
        }
    }

    // Workdeck-native mode is semantic and text-first. Debouncing token updates prevents a
    // conventional video cadence while still keeping a streaming answer responsive on e-ink.
    LaunchedEffect(ui.steps, ui.streamingText, ui.councilCards, ui.error) {
        delay(350)
        val sections = buildList {
            ui.steps.takeLast(10).forEach { step ->
                add(WorkdeckNativeSection(
                    WorkdeckSectionKind.PROMPT_RESPONSE,
                    step.node.role.name.lowercase().replaceFirstChar(Char::uppercase),
                    step.node.content.take(48 * 1024),
                ))
            }
            ui.streamingText?.takeIf(String::isNotBlank)?.let {
                add(WorkdeckNativeSection(WorkdeckSectionKind.PROMPT_RESPONSE, "Assistant · streaming", it.take(48 * 1024)))
            }
            ui.councilCards.take(4).forEach {
                add(WorkdeckNativeSection(WorkdeckSectionKind.AGENT_STATUS, it.agent, it.text.take(48 * 1024)))
            }
            ui.error?.let { add(WorkdeckNativeSection(WorkdeckSectionKind.AGENT_STATUS, "Error", it)) }
            if (isEmpty()) add(WorkdeckNativeSection(WorkdeckSectionKind.PROMPT_RESPONSE, "Ready", "Type on the phone to begin."))
        }
        WorkdeckSessionHub.showNativeDocument(
            WorkdeckNativeDocument("Fonebrew · ${ui.activeModelLabel}", revision.getAndIncrement(), sections),
        )
    }

    LaunchedEffect(Unit) {
        WorkdeckSessionHub.inbound.collect { event ->
            when (event) {
                is WorkdeckInboundEvent.Keyboard -> editor?.text?.insert(editor?.selectionStart ?: 0, event.text)
                is WorkdeckInboundEvent.Clipboard -> editor?.text?.insert(editor?.selectionStart ?: 0, event.text)
                is WorkdeckInboundEvent.Control -> when (event.action) {
                    "send" -> send()
                    "branch" -> ui.steps.lastOrNull()?.node?.id?.let(chat::branchFrom)
                    else -> Unit
                }
                else -> Unit
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose) { Text("Close") }
            Button(onClick = {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WorkdeckService::class.java).setAction(WorkdeckService.ACTION_START),
                )
                status = "Workdeck listening · pairing ${pairing.pairingCode()}"
            }) { Text("Start") }
            OutlinedButton(onClick = {
                val projection = Intent().setClassName(context.packageName, "dev.aarso.ui.workdeck.WorkdeckProjectionActivity")
                if (projection.resolveActivity(context.packageManager) != null) context.startActivity(projection)
                else status = "Projection is available in the sideload/full build."
            }) { Text("Project app") }
            OutlinedButton(onClick = { modelMenu = true }) { Text(selectedModel) }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                ui.models.forEach { model ->
                    DropdownMenuItem(text = { Text(model.displayName) }, enabled = model.runnable, onClick = {
                        selectedModel = model.displayName
                        chat.switchModel(model.id)
                        control("model:${model.id}")
                        modelMenu = false
                    })
                }
            }
            Text("Pair ${pairing.pairingCode()}", style = MaterialTheme.typography.labelLarge)
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = ::send) { Text("Send") }
            DeckKey("Esc") { control("escape") }
            DeckKey("Enter") { type("\n") }
            DeckKey("←") { control("left") }
            DeckKey("↑") { control("up") }
            DeckKey("↓") { control("down") }
            DeckKey("→") { control("right") }
            DeckKey("Select") { editor?.selectAll() }
            DeckKey("Copy") {
                val start = minOf(editor?.selectionStart ?: 0, editor?.selectionEnd ?: 0)
                val end = maxOf(editor?.selectionStart ?: 0, editor?.selectionEnd ?: 0)
                val value = editor?.text?.subSequence(start, end).orEmpty()
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Workdeck", value))
            }
            DeckKey("Paste") {
                val value = context.getSystemService(ClipboardManager::class.java).primaryClip
                    ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                editor?.text?.insert(editor?.selectionStart ?: 0, value)
            }
            DeckKey("Undo") { editor?.onTextContextMenuItem(android.R.id.undo) }
            DeckKey("Redo") { editor?.onTextContextMenuItem(android.R.id.redo) }
            DeckKey("Fylz") { documentPicker.launch(arrayOf("*/*")) }
            DeckKey("Foto Xplorr") { photoPicker.launch(ActivityResultContracts.PickVisualMedia.ImageOnly) }
            DeckKey("Branch") {
                ui.steps.lastOrNull()?.node?.id?.let(chat::branchFrom)
                control("branch")
            }
            DeckKey("Quote/reply") {
                ui.steps.lastOrNull()?.node?.content?.take(1_000)?.lineSequence()?.joinToString("\n") { "> $it" }?.let {
                    editor?.text?.insert(editor?.selectionStart ?: 0, "$it\n\n")
                }
                control("quote_reply")
            }
            DeckKey("Palette") { control("command_palette") }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val prefs = context.getSharedPreferences("workdeck_macros", 0)
            listOf("Ctrl-C" to "control:interrupt", "Tab" to "\t", "Refresh" to "control:refresh").forEachIndexed { index, fallback ->
                val value = prefs.getString("macro_$index", fallback.second) ?: fallback.second
                DeckKey(fallback.first) { if (value.startsWith("control:")) control(value.removePrefix("control:")) else type(value) }
            }
            DeckKey("Save macro") {
                val value = editor?.text?.toString().orEmpty()
                if (value.isNotEmpty()) prefs.edit().putString("macro_0", value).apply()
            }
            DeckKey("Generic input") {
                context.getSharedPreferences(WorkdeckAccessibilityService.PREFERENCES, 0).edit()
                    .putBoolean(WorkdeckAccessibilityService.KEY_ENABLED, true).apply()
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                EditText(ctx).apply {
                    hint = "Type with Gboard, Clackpad, or any installed IME"
                    minLines = 2
                    maxLines = 6
                    imeOptions = EditorInfo.IME_ACTION_SEND
                    setSingleLine(false)
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
                    }
                    editor = this
                    requestFocus()
                    post { ctx.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
                }
            },
        )
        Text(
            server.connectedDevice?.let { "Connected · ${it.deviceId} · ${it.displayWidth}×${it.displayHeight}" }
                ?: server.listeningAddress?.let { "Listening · $it" }
                ?: "Workdeck stopped",
            style = MaterialTheme.typography.labelSmall,
        )
        (status ?: server.lastError)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun DeckKey(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label) }
}
