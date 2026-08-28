@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.fonebrew.ui.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.fonebrew.domain.git.GitHost
import dev.fonebrew.domain.git.GitHostKind
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aarso.hyle.cells.HyleSwitch
import dev.aarso.hyle.component.HyleContextMenu
import dev.aarso.hyle.component.HyleTree
import dev.fonebrew.data.GitBrowse
import dev.fonebrew.data.GitTransport
import dev.fonebrew.domain.builds.Build
import dev.fonebrew.domain.builds.CiTrigger
import dev.fonebrew.domain.builds.Workflow
import dev.fonebrew.domain.builds.WorkflowRun
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import dev.fonebrew.domain.cloud.CloudProvider
import dev.fonebrew.domain.codelens.CodeLens
import dev.fonebrew.domain.council.Generator
import dev.fonebrew.ui.codelens.CodeLensScreen
import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.inference.EngineGenerator
import dev.fonebrew.domain.cloud.ProviderKind
import dev.fonebrew.domain.image.ImageProvider
import dev.fonebrew.domain.image.ImageProviderKind
import dev.fonebrew.data.Object3dProviderConfig
import dev.fonebrew.domain.object3d.Object3dCloudProvider
import dev.fonebrew.flavor.InvocationFeatures
import dev.fonebrew.ui.SettingsViewModel
import dev.aarso.hyle.component.HyleField as DesktopHyleField
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleCard
import dev.aarso.hyle.cells.HyleChip
import dev.aarso.hyle.cells.HyleDropdownField
import dev.aarso.hyle.cells.HyleTitle
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.ui.theme.ThemePicker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import dev.fonebrew.domain.git.GitLookupApi
import dev.fonebrew.ui.loops.LoopRoom

// The 4 Settings tabs (IA §C), each an icon: General (config), Models (Text/Image/Video/3D ×
// on-device/cloud, nested inside), Dev (Git & coding / Builds / Remote / Instruments), About.
private enum class SettingsTab(val label: String) {
    GENERAL("General"), MODELS("Models"), DEV("Dev"), ABOUT("About")
}

// Providers split by where they run: on-device (the default, rule 2) vs watched cloud.
private enum class ProviderScope { LOCAL, CLOUD }

// The four provider modalities nested inside the Models tab (owner ask): Text / Image / Video
// / 3D, each with its own on-device ⇄ watched-cloud split (ProviderScope, above).
private enum class ModelModality(val label: String) {
    TEXT("Text"), IMAGE("Image"), VIDEO("Video"), OBJECT3D("3D")
}

/**
 * The room parked off the RIGHT edge (IA §C): **configuration only, never a launcher.** Four
 * icon tabs — General (config), Models (Text/Image/Video/3D, each on-device ⇄ watched-cloud,
 * nested inside), Dev (Git & coding / Builds / Remote / Instruments), and About. Every cloud
 * provider is a **watched object**: opt-in, isolated, never a hidden default; keys stay in the
 * Android Keystore.
 *
 * Full-screen sub-surfaces (Me·Myself·I, Free tiers, Remote, Git…) render in a hoisted
 * [overlay] slot at the room root, OUTSIDE the scrolling content — a scrollable child measured
 * inside a verticalScroll parent gets an infinite height constraint and crashes (the PR #39
 * fix). On-device model management ([LocalModels], Models tab) used to need this same overlay
 * slot for that reason — its [Coverflow] pager now gives itself an explicit bounded height
 * instead (see that file's KDoc), so it renders directly inline in this room's own scrolling
 * content and no longer uses [overlay] at all.
 *
 * [extraGlobalRows] mirrors [dev.fonebrew.ui.rooms.ProductRoomFree]'s `extraTabs` seam: it lets an
 * above-core layer append rows to the bottom of the General tab (e.g. an entitlement/unlock
 * status row) without this file referencing that code.
 */
@Composable
fun SettingsRoom(
    onShowSpatialMap: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
    extraGlobalRows: List<@Composable () -> Unit> = emptyList(),
) {
    var tab by remember { mutableStateOf(SettingsTab.GENERAL) }
    var overlay by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    BackHandler(enabled = overlay != null) { overlay = null }
    val session = (LocalContext.current.applicationContext as dev.fonebrew.FonebrewApp).container.sessionStore
    val universalPosition by session.tabBarPosition.collectAsState()
    val roomOverrides by session.roomTabBarPosition.collectAsState()
    val position = roomOverrides["settings"] ?: universalPosition

    val tabBar = @Composable { SettingsTabBar(tab, position = position) { tab = it } }
    val content: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                SettingsTab.GENERAL -> GeneralSettings(
                    onShowSpatialMap = onShowSpatialMap,
                    openOverlay = { overlay = it },
                    closeOverlay = { overlay = null },
                    extraGlobalRows = extraGlobalRows,
                )
                SettingsTab.MODELS -> ModelsSettings(viewModel = viewModel)
                SettingsTab.DEV -> DevSettings(
                    openOverlay = { overlay = it },
                    closeOverlay = { overlay = null },
                )
                SettingsTab.ABOUT -> AboutSettings()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            HyleTitle("Settings")
            if (position == "BOTTOM") {
                content()
                tabBar()
            } else {
                tabBar()
                content()
            }
        }
        overlay?.invoke()
    }
}

// One HyleTabSpec per SettingsTab, glyphs unchanged from the original hand-rolled TabGlyph —
// this IS the bar HyleTabBar (Aeon.kt) was extracted from; now it consumes the shared component
// instead of keeping its own parallel copy (2026-07-19 tab-bar consolidation).
private val SettingsTabSpecs: List<dev.aarso.hyle.cells.HyleTabSpec> = SettingsTab.entries.map { t ->
    dev.aarso.hyle.cells.HyleTabSpec(t.label) { tint ->
        val w = size.width; val h = size.height
        val sw = w * 0.09f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(tint, androidx.compose.ui.geometry.Offset(x0, y0), androidx.compose.ui.geometry.Offset(x1, y1), strokeWidth = sw)
        when (t) {
            SettingsTab.GENERAL -> {
                drawCircle(tint, radius = w * 0.42f, style = stroke)
                drawOval(
                    tint, topLeft = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.08f),
                    size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.84f), style = stroke,
                )
                line(w * 0.10f, h * 0.5f, w * 0.90f, h * 0.5f)
            }
            SettingsTab.MODELS -> {
                // A shelf: a rounded-rect case with three shelves — the Text/Image/Video/3D
                // modalities nested inside this tab.
                drawRoundRect(
                    tint, topLeft = androidx.compose.ui.geometry.Offset(w * 0.10f, h * 0.14f),
                    size = androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.72f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f), style = stroke,
                )
                line(w * 0.22f, h * 0.36f, w * 0.78f, h * 0.36f)
                line(w * 0.22f, h * 0.50f, w * 0.78f, h * 0.50f)
                line(w * 0.22f, h * 0.64f, w * 0.78f, h * 0.64f)
            }
            SettingsTab.DEV -> {
                // A "<>" chevron pair.
                val left = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.42f, h * 0.24f); lineTo(w * 0.16f, h * 0.5f); lineTo(w * 0.42f, h * 0.76f)
                }
                drawPath(left, tint, style = stroke)
                val right = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.58f, h * 0.24f); lineTo(w * 0.84f, h * 0.5f); lineTo(w * 0.58f, h * 0.76f)
                }
                drawPath(right, tint, style = stroke)
            }
            SettingsTab.ABOUT -> {
                // A circle with an "i" — dot above, stem below.
                drawCircle(tint, radius = w * 0.42f, style = stroke)
                drawCircle(tint, radius = w * 0.045f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.32f))
                line(w * 0.5f, h * 0.46f, w * 0.5f, h * 0.70f)
            }
        }
    }
}

@Composable
private fun SettingsTabBar(selected: SettingsTab, position: String = "TOP", onSelect: (SettingsTab) -> Unit) {
    dev.aarso.hyle.cells.HyleTabBar(
        tabs = SettingsTabSpecs,
        selected = SettingsTab.entries.indexOf(selected),
        onSelect = { onSelect(SettingsTab.entries[it]) },
        position = position,
    )
}

// One HyleTabSpec per ModelModality — the exact glyphs the top-level Image/Text/Video/3D tabs
// used before the Global/Image/Text/Video/3D → General/Models/Dev/About regroup, now nested
// one level down inside the Models tab.
private val ModelModalitySpecs: List<dev.aarso.hyle.cells.HyleTabSpec> = ModelModality.entries.map { t ->
    dev.aarso.hyle.cells.HyleTabSpec(t.label) { tint ->
        val w = size.width; val h = size.height
        val sw = w * 0.09f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(tint, androidx.compose.ui.geometry.Offset(x0, y0), androidx.compose.ui.geometry.Offset(x1, y1), strokeWidth = sw)
        when (t) {
            ModelModality.IMAGE -> {
                drawRoundRect(
                    tint, topLeft = androidx.compose.ui.geometry.Offset(w * 0.10f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.64f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f), style = stroke,
                )
                drawCircle(tint, radius = w * 0.07f, center = androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.38f))
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.16f, h * 0.74f); lineTo(w * 0.42f, h * 0.50f)
                    lineTo(w * 0.60f, h * 0.66f); lineTo(w * 0.72f, h * 0.56f); lineTo(w * 0.84f, h * 0.74f)
                }
                drawPath(p, tint, style = stroke)
            }
            ModelModality.TEXT -> {
                line(w * 0.16f, h * 0.30f, w * 0.84f, h * 0.30f)
                line(w * 0.16f, h * 0.50f, w * 0.72f, h * 0.50f)
                line(w * 0.16f, h * 0.70f, w * 0.80f, h * 0.70f)
            }
            ModelModality.VIDEO -> {
                drawRoundRect(
                    tint, topLeft = androidx.compose.ui.geometry.Offset(w * 0.10f, h * 0.24f),
                    size = androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.52f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f), style = stroke,
                )
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.42f, h * 0.37f); lineTo(w * 0.42f, h * 0.63f); lineTo(w * 0.62f, h * 0.50f); close()
                }
                drawPath(p, tint)
            }
            ModelModality.OBJECT3D -> {
                drawCircle(tint, radius = w * 0.42f, style = stroke)
                drawOval(
                    tint, topLeft = androidx.compose.ui.geometry.Offset(w * 0.06f, h * 0.34f),
                    size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.32f), style = stroke,
                )
            }
        }
    }
}

@Composable
private fun ModelModalityTabBar(selected: ModelModality, onSelect: (ModelModality) -> Unit) {
    dev.aarso.hyle.cells.HyleTabBar(
        tabs = ModelModalitySpecs,
        selected = ModelModality.entries.indexOf(selected),
        onSelect = { onSelect(ModelModality.entries[it]) },
    )
}

/**
 * The Models tab (owner ask): Text / Image / Video / 3D as a nested tab row, each with its own
 * on-device ⇄ watched-cloud toggle (IA §C) beneath it. On-device is the default (rule 2).
 * Video has no engine wired yet — shown honestly as planned, never faked (rule 6); 3D has both
 * an on-device path (the active chat model, [Object3dOnDeviceSettings]) and watched cloud
 * providers ([Object3dCloudSettings]) — docs/design/objects-3d.md §1.
 */
@Composable
private fun ColumnScope.ModelsSettings(viewModel: SettingsViewModel) {
    var modality by remember { mutableStateOf(ModelModality.TEXT) }
    ModelModalityTabBar(modality) { modality = it }
    var scope by remember(modality) { mutableStateOf(ProviderScope.LOCAL) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyleChip(scope == ProviderScope.LOCAL, { scope = ProviderScope.LOCAL }, "On-device")
        HyleChip(scope == ProviderScope.CLOUD, { scope = ProviderScope.CLOUD }, "Cloud · watched")
    }
    when (modality) {
        ModelModality.TEXT ->
            if (scope == ProviderScope.CLOUD) TextSettings(viewModel)
            else LocalModels("chat")
        ModelModality.IMAGE ->
            if (scope == ProviderScope.CLOUD) ImageSettings(viewModel)
            else LocalModels("image")
        ModelModality.VIDEO -> PlannedProvider("Video", scope)
        ModelModality.OBJECT3D ->
            if (scope == ProviderScope.CLOUD) Object3dCloudSettings(viewModel) else Object3dOnDeviceSettings()
    }
}

/**
 * On-device models for a modality — the cards render directly here now (owner ask: "the model
 * cards are still hidden behind a button"), no extra tap into a separate overlay screen.
 * [ChatOnDeviceShelf]/[ImageOnDeviceShelf]'s [Coverflow] pager used to need [SettingsRoom]'s
 * hoisted `overlay` slot because a `HorizontalPager` measured inside a `verticalScroll` parent
 * (this room's own scrolling content) gets an infinite height constraint and crashes (PR #39);
 * [Coverflow] now gives its own pager an explicit bounded height instead (see its KDoc in
 * ModelsRoom.kt), so it no longer needs a dedicated full-screen host and renders straight into
 * this already-scrolling column — no Chat/Image/Bring-your-own tabs and no On-device/Cloud
 * toggle to re-pick (owner-flagged as duplicative: both choices were already made one level up,
 * in this very screen).
 */
@Composable
private fun LocalModels(kind: String) {
    val container = (LocalContext.current.applicationContext as dev.fonebrew.FonebrewApp).container
    Text(
        "On-device $kind models run locally — the default. Download, switch, and remove them below.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    when (kind) {
        "image" -> {
            val imagesVm: dev.fonebrew.ui.ImagesViewModel =
                viewModel(factory = dev.fonebrew.ui.ImagesViewModel.Factory)
            ImageOnDeviceShelf(
                downloads = container.downloadCenter,
                onCustomUrl = { imagesVm.downloadSdModel(it) },
                imagesViewModel = imagesVm,
            )
        }
        else -> {
            val modelsVm: dev.fonebrew.ui.ModelsViewModel =
                viewModel(factory = dev.fonebrew.ui.ModelsViewModel.Factory)
            ChatOnDeviceShelf(
                downloads = container.downloadCenter,
                onCustomUrl = { modelsVm.downloadCustom(it) },
                modelsViewModel = modelsVm,
            )
        }
    }
}

/** Honest placeholder for a modality with no engine wired yet (rule 6: never claim it works). */
@Composable
private fun PlannedProvider(label: String, scope: ProviderScope) {
    Text("$label providers", style = MaterialTheme.typography.titleSmall)
    Text(
        if (scope == ProviderScope.LOCAL)
            "No on-device $label engine is wired yet. When one lands, you'll download and manage " +
                "local $label models here — on-device by default, like text and image."
        else
            "No $label cloud provider is wired yet. When added, each will be a watched object: " +
                "opt-in, key encrypted on-device, used only for what you invoke.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Planned — Information Architecture §C. Tracked, not faked.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GeneralSettings(
    onShowSpatialMap: () -> Unit,
    openOverlay: (@Composable () -> Unit) -> Unit,
    closeOverlay: () -> Unit,
    extraGlobalRows: List<@Composable () -> Unit> = emptyList(),
) {
    val container = (LocalContext.current.applicationContext as dev.fonebrew.FonebrewApp).container
    val session = container.sessionStore

    // "Me · Myself · I" — the user meta (drift inert; linked accounts; usage). Provisional home
    // is here in General until the owner picks its spatial place (IA open question).
    Text("You", style = MaterialTheme.typography.titleMedium)
    Text(
        "Your linked accounts, usage overview, and the (inert) self-reflection mirror.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HyleButton("Me · Myself · I", onClick = {
        openOverlay { dev.fonebrew.ui.rooms.MeScreen(onClose = closeOverlay) }
    })
    HorizontalDivider()

    // Export everything (IA cross-cutting): the whole local profile as open JSON. Keys excluded.
    Text("Your data", style = MaterialTheme.typography.titleMedium)
    Text(
        "Export your whole profile as open JSON — appearance, defaults, bookmarks, projects, " +
            "notes, incidents, participants, loops, and your message tree. API keys stay in the " +
            "Keystore and are never included.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    run {
        val exportScope = rememberCoroutineScope()
        val ctx = LocalContext.current
        HyleButton("Export everything", onClick = {
            exportScope.launch {
                val json = dev.fonebrew.data.DataExport.toJson(container)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Fonebrew export")
                    putExtra(android.content.Intent.EXTRA_TEXT, json)
                }
                ctx.startActivity(
                    android.content.Intent.createChooser(send, "Export your data")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        })
    }
    HorizontalDivider()

    // Models are managed in the Models tab (Text/Image/Video/3D) now; Settings is not a launcher.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Cloud free tiers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        dev.fonebrew.ui.guide.HelpIcon(dev.fonebrew.domain.guide.Guides.ADD_CLOUD)
    }
    Text(
        "What each cloud provider gives free, and how much you've availed — a watched, " +
            "consent-gated online refresh.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HyleButton("Open free tiers", onClick = {
        openOverlay { dev.fonebrew.ui.rooms.FreeTiersScreen(onClose = closeOverlay) }
    })
    HorizontalDivider()

    Text("Appearance", style = MaterialTheme.typography.titleMedium)
    Text(
        "Make it yours — light or dark, your own accent, your own grain.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ThemePicker()
    HorizontalDivider()

    Text("Header status", style = MaterialTheme.typography.titleMedium)
    Text(
        "One quiet fact in the Chat header, about the conversation you're in — or nothing at all.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    run {
        val headerIndicator by session.headerIndicator.collectAsState()
        val c = LocalHyleColors.current
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "NONE" to "None",
                "SOVEREIGNTY" to "Sovereignty",
                "QUOTA" to "Quota",
                "TIME" to "Time",
            ).forEach { (value, label) ->
                HyleChip(headerIndicator == value, { session.setHeaderIndicator(value) }, label)
            }
        }
        Text(
            when (headerIndicator) {
                "SOVEREIGNTY" -> "⌂ the % of this conversation's tokens that stayed on-device."
                "QUOTA" -> "how many watched-cloud requests you've made today, across providers."
                "TIME" -> "how long ago this conversation started."
                else -> "nothing shown next to Settings in Chat."
            },
            style = MaterialTheme.typography.labelSmall,
            color = c.textMid,
        )
    }
    HorizontalDivider()

    Text("Tab bar position", style = MaterialTheme.typography.titleMedium)
    Text(
        "Top or bottom, thumb-reach — applies everywhere unless a room says otherwise.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    run {
        val tabBarPosition by session.tabBarPosition.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("TOP" to "Top", "BOTTOM" to "Bottom").forEach { (value, label) ->
                HyleChip(tabBarPosition == value, { session.setTabBarPosition(value) }, label)
            }
        }
    }

    // Per-room override, one row per room that reads roomTabBarPosition (Chat/ChatsRoom/
    // TreeRoom/DevelopRoom/SettingsRoom/ProductRoomFree) -- "Default" clears the override
    // (setRoomTabBarPosition(id, null)) and falls back to the universal choice above.
    run {
        val roomOverrides by session.roomTabBarPosition.collectAsState()
        val rooms = listOf(
            "chat" to "Chat",
            "chats" to "Chats",
            "tree" to "Tree",
            "develop" to "Develop",
            "settings" to "Settings",
            "project" to "Project",
        )
        rooms.forEach { (roomId, label) ->
            val current = roomOverrides[roomId]
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp),
                )
                listOf(null to "Default", "TOP" to "Top", "BOTTOM" to "Bottom").forEach { (value, chipLabel) ->
                    HyleChip(current == value, { session.setRoomTabBarPosition(roomId, value) }, chipLabel)
                }
            }
        }
    }
    HorizontalDivider()

    Text("Terminal Ctrl-C button", style = MaterialTheme.typography.titleMedium)
    Text(
        "On by default. Turn it off if your keyboard already has a control key (e.g. Clackpad) — " +
            "typing /ctrlc always works either way.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    run {
        val showCtrlC by session.terminalCtrlCButton.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Show Ctrl-C button in Terminal", style = MaterialTheme.typography.bodyMedium)
            HyleSwitch(checked = showCtrlC, onCheckedChange = session::setTerminalCtrlCButton)
        }
    }
    HorizontalDivider()

    Text("Summon from anywhere", style = MaterialTheme.typography.titleMedium)
    Text(
        buildString {
            append("• Select text in any app → tap \"Fonebrew\" in the selection menu.\n")
            append("• Share anything (text or image) → choose Fonebrew.\n")
            append(
                "• Assist gesture: set Fonebrew as your Digital assistant in the system " +
                    "Settings → Apps → Default apps → Digital assistant app (this replaces Gemini). " +
                    "It then captures the on-screen text and brings Fonebrew forward.",
            )
            if (InvocationFeatures.BUBBLE_AVAILABLE) {
                append("\n• Floating bubble (below): tap to open Fonebrew; long-press to OCR the screen behind it.")
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val context = LocalContext.current
    if (InvocationFeatures.BUBBLE_AVAILABLE) {
        var bubbleOn by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Floating bubble (always-on summon)", style = MaterialTheme.typography.bodyMedium)
            HyleSwitch(
                checked = bubbleOn,
                onCheckedChange = { on ->
                    if (on) {
                        if (!android.provider.Settings.canDrawOverlays(context)) {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } else {
                            InvocationFeatures.startBubble(context)
                            bubbleOn = true
                        }
                    } else {
                        InvocationFeatures.stopBubble(context)
                        bubbleOn = false
                    }
                },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("How to move around", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onShowSpatialMap) { Text("Show the map") }
    }
    HorizontalDivider()

    Text("Council", style = MaterialTheme.typography.titleMedium)
    Text(
        "How new conversations start — you can still switch per-conversation from the composer.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val councilDefault by session.councilDefault.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyleChip(councilDefault == "SINGLE", { session.setCouncilDefault("SINGLE") }, "Single")
        HyleChip(councilDefault == "PERSONAS", { session.setCouncilDefault("PERSONAS") }, "Personas")
        HyleChip(councilDefault == "MODELS", { session.setCouncilDefault("MODELS") }, "Models")
    }
    HorizontalDivider()

    // THREAD_TOPOLOGY_PLAN.md WP4 — one switch per gesture channel the message-bubble drag
    // detector arbitrates (binding constraint 4: every gesture needs a disable toggle here).
    // Turning a switch off never removes the underlying action — it's always still reachable via
    // the chevron row / TurnActionsSheet / TalkBack custom actions; this just stops the drag from
    // triggering it, for anyone who finds the hold-and-pull motion fights their own touch habits.
    // These three + the Observer switch below render via [HyleSwitch] (cells package), same as
    // the rest of this General tab — the owner picked the square up-down switch as the standard
    // settings toggle (2026-08-21: "the square up down toggle is fine"), retiring the merge-era
    // mix with the desktop-class kit's HyleToggle.
    Text("Gestures", style = MaterialTheme.typography.titleMedium)
    Text(
        "Message-bubble drags — hold briefly, then pull. Every one has a tap equivalent " +
            "(chevrons, the long-press sheet) whether or not it's on here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val gestureVerdictDrag by session.gestureVerdictDragEnabled.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Verdict drag", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Pull a message up/down to rate it (§4.2) instead of just the chevron buttons.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HyleSwitch(checked = gestureVerdictDrag, onCheckedChange = { session.setGestureVerdictDragEnabled(it) })
    }
    val gestureQuoteReply by session.gestureQuoteReplyEnabled.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Reply / quote drag", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Pull a message left to reply, right to quote it into the composer.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HyleSwitch(checked = gestureQuoteReply, onCheckedChange = { session.setGestureQuoteReplyEnabled(it) })
    }
    val gestureRadialFan by session.gestureRadialFanEnabled.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Branch / Fork / Spawn fan", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Pull a message right and hold to open the radial menu instead of quoting.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HyleSwitch(checked = gestureRadialFan, onCheckedChange = { session.setGestureRadialFanEnabled(it) })
    }
    HorizontalDivider()

    // THREAD_TOPOLOGY_PLAN.md WP9 — the graph observer, off by default (owner decision 5). No
    // graph room reads this yet (WP10); the switch exists so the substrate's inert-by-default
    // contract is real and testable ahead of that surface, not a dead control.
    Text("Observer (inert)", style = MaterialTheme.typography.titleMedium)
    Text(
        "Lets a local pass describe your conversation topology structurally — message/fork/" +
            "marker/delegation counts, nothing interpreted. Off by default; no graph surface " +
            "reads this yet.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val observerEnabled by session.observerEnabled.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Enable graph observer", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Reads only what's already on-device (the tree, markers, delegations) — never " +
                    "the capture log.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HyleSwitch(checked = observerEnabled, onCheckedChange = { session.setObserverEnabled(it) })
    }

    // Loops (pinch-in) and Develop (bottom edge) are spatial rooms, not Settings entries —
    // reach them from the map (see "How to move around" above). Settings is config only.
    // Git & coding / Builds / Remote / Instruments live under the Dev tab.

    if (extraGlobalRows.isNotEmpty()) {
        HorizontalDivider()
        for (row in extraGlobalRows) row()
    }
}

/** Build/dev surfaces moved out of General (owner ask): connect a Git host + see your builds,
 *  connect to your own machines over SSH, and the entropy-colouring instrument. */
@Composable
private fun DevSettings(
    openOverlay: (@Composable () -> Unit) -> Unit,
    closeOverlay: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as dev.fonebrew.FonebrewApp).container
    val session = container.sessionStore

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Remote", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        dev.fonebrew.ui.guide.HelpIcon(dev.fonebrew.domain.guide.Guides.CONNECT_SSH)
    }
    Text(
        "Connect to your own machines over SSH — a Pi, a Dell, any server. You decide " +
            "trust (the real fingerprint is shown); the remote's output is a watched object.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HyleButton("Open Remote", onClick = {
        openOverlay { dev.fonebrew.ui.remote.RemoteScreen(onClose = closeOverlay) }
    })
    HorizontalDivider()

    Text("Instruments", style = MaterialTheme.typography.titleMedium)
    val entropy by session.entropyColoring.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Entropy colouring", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Tint streaming tokens by the model's per-token uncertainty (§5a).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HyleSwitch(checked = entropy, onCheckedChange = { session.setEntropyColoring(it) })
    }
    HorizontalDivider()

    Text("Git & coding", style = MaterialTheme.typography.titleMedium)
    GitConnect()
    HorizontalDivider()

    Text("Builds", style = MaterialTheme.typography.titleMedium)
    Text(
        "APK builds from your connected Git host. Tap Install to sideload without leaving the app " +
            "(full build only — Play forbids in-app installs).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BuildsSection()
}

/** Version + local-first blurb, plus (debug builds only) a long-press to preview the
 *  crash-recovery screen without a real crash. Fonebrew leads the About text (owner instruction,
 *  reunification-merge session) — the Konkani-etymology sentence for the Aarso mirror lens, when
 *  it's kept at all, only ever follows it, never opens the paragraph.
 *
 *  :core-engine deliberately carries no versionName/DEBUG flag of its own that a future
 *  Studio :app consuming this same module could rely on having the same value for (see
 *  [dev.fonebrew.core_engine.BuildConfig] — an application-module concern), so the shipping
 *  app's own versionName is read live via PackageManager rather than a baked-in BuildConfig
 *  constant from a specific :app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutSettings() {
    val context = LocalContext.current
    Text("About", style = MaterialTheme.typography.titleMedium)
    val appVersionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    Text(
        "Fonebrew $appVersionName — its self-reflection lens is Aarso, Konkani for “mirror”.\n\n" +
            "Local-first by design: conversations, models, and keys live on this " +
            "device. No analytics, no telemetry. Cloud models run only when you " +
            "invoke them, and only against the provider you configured.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // Debug-only: long-press the version to preview the crash-recovery screen without
        // actually crashing (dev.aarso:crash-recovery — see that repo's README). Never
        // reachable from a release build.
        modifier = if (dev.fonebrew.core_engine.BuildConfig.DEBUG) {
            Modifier.combinedClickable(
                onClick = {},
                onLongClick = {
                    context.startActivity(
                        dev.aarso.crashrecovery.CrashRecovery.previewIntent(
                            context,
                            appLabel = "Fonebrew",
                            style = dev.fonebrew.ui.theme.FonebrewCrashRecoveryStyle,
                        ),
                    )
                },
            )
        } else {
            Modifier
        },
    )
}

@Composable
private fun TextSettings(viewModel: SettingsViewModel) {
    val providers by viewModel.providers.collectAsState()
    Text(
        "Cloud is opt-in and watched — the app defaults to on-device models. " +
            "Keys are encrypted on this device and sent only to the provider you invoke.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var editing by remember { mutableStateOf<CloudProvider?>(null) }
    for (p in providers) {
        ProviderRow(
            provider = p,
            hasKey = viewModel.hasKey(p.id),
            onEdit = { editing = p },
            onDelete = { if (editing?.id == p.id) editing = null; viewModel.remove(p.id) },
        )
    }
    Text(
        if (editing == null) "Add a provider" else "Edit “${editing?.displayName}”",
        style = MaterialTheme.typography.titleSmall,
    )
    ProviderForm(
        editing = editing,
        onSave = { id, name, kind, baseUrl, model, ctx, vision, key ->
            viewModel.save(id, name, kind, baseUrl, model, ctx, vision, key)
            editing = null
        },
        onCancelEdit = { editing = null },
    )
}

@Composable
private fun ImageSettings(viewModel: SettingsViewModel) {
    val imageProviders by viewModel.imageProviders.collectAsState()
    Text(
        "On-device generation is the default; cloud image providers are watched and " +
            "opt-in, keys encrypted on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (p in imageProviders) {
        ImageProviderRow(p, viewModel.hasImageKey(p.id)) { viewModel.removeImage(p.id) }
    }
    ImageProviderForm(onSave = viewModel::saveImage)
}

/** docs/design/objects-3d.md §1/§4: "On-device explains the procedural path (uses the active
 *  chat model — nothing extra to download)." Unlike Image/Text, there is no separate on-device
 *  3D model to manage — [dev.fonebrew.inference.object3d.ProceduralObjectEngine] prompts whatever
 *  chat model is already active, so this tab is explanatory only, not another Models shelf. */
@Composable
private fun Object3dOnDeviceSettings() {
    Text("On-device 3D generation", style = MaterialTheme.typography.titleSmall)
    Text(
        "Prompts your active chat model for a compact 3D scene (or, if it prefers, raw mesh " +
            "text) — nothing extra to download. The result is checked before it's saved; an " +
            "invalid reply gets one corrected retry, then an honest failure. Reach it from the " +
            "composer's + menu → \"Generate 3D…\" → On-device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** docs/design/objects-3d.md §1/§5: Meshy/Tripo provider configs, each a watched object
 *  (binding rule 2) — same shape as [ImageSettings], key encrypted via [KeystoreSecret] on save. */
@Composable
private fun Object3dCloudSettings(viewModel: SettingsViewModel) {
    val object3dProviders by viewModel.object3dProviders.collectAsState()
    Text(
        "On-device generation is the default; cloud 3D providers are watched and opt-in " +
            "per use, keys encrypted on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (p in object3dProviders) {
        Object3dProviderRow(p, viewModel.hasObject3dKey(p.id)) { viewModel.removeObject3d(p.id) }
    }
    Object3dProviderForm(onSave = viewModel::saveObject3d)
}

@Composable
private fun Object3dProviderRow(provider: Object3dProviderConfig, hasKey: Boolean, onDelete: () -> Unit) {
    HyleCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${provider.displayName} · watched", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${provider.kind.label} · ${provider.baseUrl}" + if (hasKey) " · key set" else " · no key",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasKey) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onDelete) { Text("Remove") }
        }
    }
}

@Composable
private fun Object3dProviderForm(
    onSave: (Object3dCloudProvider, String, String, String) -> Unit,
) {
    var kind by remember { mutableStateOf(Object3dCloudProvider.MESHY) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(kind.apiBaseUrl) }
    var apiKey by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HyleDropdownField(
            value = kind.label,
            options = Object3dCloudProvider.entries.map { it.label },
            onSelect = { i ->
                kind = Object3dCloudProvider.entries[i]
                baseUrl = kind.apiBaseUrl
            },
            label = "Type",
            modifier = Modifier.fillMaxWidth(),
        )
        DesktopHyleField(name, { name = it }, label = "Display name", modifier = Modifier.fillMaxWidth())
        DesktopHyleField(
            baseUrl, { baseUrl = it },
            label = "Base URL",
            mandatory = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DesktopHyleField(
            apiKey, { apiKey = it },
            label = "API key (encrypted on-device)",
            mandatory = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        HyleButton(
            "Save 3D provider",
            onClick = { onSave(kind, name, baseUrl, apiKey); name = ""; apiKey = "" },
            enabled = apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Connect a Git host you own (watched): tree backup + the coding assistant. The
 *  token is encrypted on-device; the app talks only to your host. */
@Composable
private fun GitConnect() {
    val container = (LocalContext.current.applicationContext as dev.fonebrew.FonebrewApp).container
    val store = container.gitHostStore
    val transport = container.gitTransport
    val hosts by store.hosts.collectAsState()
    val scope = rememberCoroutineScope()
    val generator: Generator? = remember {
        val spec = container.modelRegistry.allSpecs().firstOrNull { container.engineProvider.isRunnable(it) }
        spec?.let { s -> container.engineProvider.engineFor(s)?.let { engine -> EngineGenerator(engine, s.modelPath) } }
    }

    Text(
        "Connect a Git host you own — GitHub or Gitea/Forgejo — for tree backup and " +
            "the coding assistant. Watched: the app talks only to your host. Your access " +
            "token (a PAT) is encrypted on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (h in hosts) {
        var status by remember(h.id) { mutableStateOf<String?>(null) }
        var browsing by remember(h.id) { mutableStateOf(false) }
        var ciOpen by remember(h.id) { mutableStateOf(false) }
        if (browsing) {
            GitBrowser(h, store.token(h.id).orEmpty(), container.gitBrowse, generator) { browsing = false }
        }
        if (ciOpen) {
            CiPanel(h, store.token(h.id).orEmpty(), transport) { ciOpen = false }
        }
        var showActions by remember(h.id) { mutableStateOf(false) }
        val haptics = dev.aarso.hyle.cells.rememberHyleHaptics()
        HyleCard(
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = { haptics.tap(); showActions = true },
            ),
        ) {
            Text("${h.displayName} · watched", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${h.kind.label} · ${h.owner}/${h.repo}@${h.branch}" +
                    (if (store.hasToken(h.id)) " · token set" else " · no token"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            // Test is the one inline action — a read-only connectivity check, safe to leave a
            // single tap away. Everything else (Back up/Pull/Browse/CI/Remove) is a
            // longer-running or destructive action, so it moves behind the long-press sheet
            // below (mirrors ChatsRoom.kt's ConversationActionsSheet pattern).
            TextButton(onClick = {
                val token = store.token(h.id)
                if (token.isNullOrBlank()) {
                    status = "no token stored"
                } else {
                    status = "testing…"
                    scope.launch {
                        status = transport.testConnection(h, token)
                            .fold({ "✓ connected · $it branch(es)" }, { "✗ ${it.message}" })
                    }
                }
            }) { Text("Test") }
        }
        if (showActions) {
            GitHostActionsSheet(
                title = h.displayName,
                onBackUp = {
                    showActions = false
                    val token = store.token(h.id)
                    if (token.isNullOrBlank()) {
                        status = "no token stored"
                    } else {
                        status = "backing up…"
                        scope.launch {
                            status = container.gitBackup.backUp(h, token).fold(
                                { "✓ backed up · ${it.created} new, ${it.skipped} already there" +
                                    (if (it.failed > 0) ", ${it.failed} failed" else "") },
                                { "✗ ${it.message}" },
                            )
                        }
                    }
                },
                onPull = {
                    showActions = false
                    val token = store.token(h.id)
                    if (token.isNullOrBlank()) {
                        status = "no token stored"
                    } else {
                        status = "pulling…"
                        scope.launch {
                            status = container.gitBackup.pull(h, token).fold(
                                { "✓ pulled · ${it.imported} imported, ${it.alreadyHad} already here" +
                                    (if (it.orphans > 0) ", ${it.orphans} skipped" else "") },
                                { "✗ ${it.message}" },
                            )
                        }
                    }
                },
                onBrowse = {
                    showActions = false
                    if (store.token(h.id).isNullOrBlank()) status = "no token stored" else browsing = true
                },
                onCi = {
                    showActions = false
                    if (store.token(h.id).isNullOrBlank()) status = "no token stored" else ciOpen = true
                },
                onRemove = {
                    showActions = false
                    store.remove(h.id)
                },
                onDismiss = { showActions = false },
            )
        }
    }
    Text(if (hosts.isEmpty()) "Add a host" else "Add another", style = MaterialTheme.typography.titleSmall)
    GitConnectForm(newId = store.newId(), transport = transport) { host, token -> store.upsert(host, token) }
}

/** Long-press actions for a connected Git host row (mirrors ChatsRoom.kt's
 *  ConversationActionsSheet/FolderTabActionsSheet pattern): only "Test" — a safe, read-only
 *  connectivity check — stays inline on the row; Back up/Pull/Browse/CI and the destructive
 *  Remove live here instead, off a long-press rather than six buttons crammed into one row. */
@Composable
private fun GitHostActionsSheet(
    title: String,
    onBackUp: () -> Unit,
    onPull: () -> Unit,
    onBrowse: () -> Unit,
    onCi: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onBackUp, modifier = Modifier.fillMaxWidth()) { Text("Back up") }
            TextButton(onClick = onPull, modifier = Modifier.fillMaxWidth()) { Text("Pull") }
            TextButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) { Text("Browse") }
            TextButton(onClick = onCi, modifier = Modifier.fillMaxWidth()) { Text("CI") }
            TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Remove") }
        }
    }
}

/**
 * Shows APK builds from every connected Git host: name, version, a one-line
 * summary, and an Install button that downloads and sideloads. In the play build
 * the install returns an error (Play forbids REQUEST_INSTALL_PACKAGES) — the
 * error is shown inline so the flow is honest rather than silent.
 *
 * Network and install calls are owner-verified on device.
 */
@Composable
private fun BuildsSection() {
    val container = (LocalContext.current.applicationContext as dev.fonebrew.FonebrewApp).container
    val store = container.gitHostStore
    val hosts by store.hosts.collectAsState()
    val scope = rememberCoroutineScope()

    if (hosts.isEmpty()) {
        Text(
            "No Git host connected — add one in Git & coding above to see your builds here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    for (host in hosts) {
        var builds by remember(host.id) { mutableStateOf<List<Build>?>(null) }
        var loadError by remember(host.id) { mutableStateOf<String?>(null) }
        LaunchedEffect(host.id) {
            val token = store.token(host.id) ?: run { loadError = "no token"; return@LaunchedEffect }
            builds = runCatching { container.buildsRepo.listBuilds(host) }
                .onFailure { loadError = it.message }
                .getOrDefault(emptyList())
        }

        Text(
            "${host.owner}/${host.repo}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )

        val err = loadError
        val list = builds
        when {
            err != null -> Text("✗ $err", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            list == null -> Text("Loading…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.isEmpty() -> Text("No builds found — push a release or set up apk-dist on your repo.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (build in list.take(5)) {
                    var installProgress by remember(build.id) { mutableStateOf<Float?>(null) }
                    var installError by remember(build.id) { mutableStateOf<String?>(null) }
                    HyleCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(build.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${build.version}  ·  ${build.source.name.lowercase().replace('_', ' ')}" +
                                        (if (build.sizeBytes > 0) "  ·  ${build.sizeBytes / (1024 * 1024)} MB" else ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val url = container.buildsRepo.findApkUrl(build)
                            if (url != null) {
                                val prog = installProgress
                                if (prog != null) {
                                    Text(
                                        if (prog < 0f) "✗" else "${(prog * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (prog < 0f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    TextButton(onClick = {
                                        installProgress = 0f
                                        installError = null
                                        scope.launch {
                                            container.apkInstaller.downloadAndInstall(url, build.name) { p ->
                                                installProgress = if (p.error != null) { installError = p.error; -1f } else if (p.done) null else p.fraction
                                            }
                                        }
                                    }) { Text("Install") }
                                }
                            }
                        }
                        val p = installProgress
                        if (p != null && p >= 0f) {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        installError?.let {
                            Text("✗ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen repo browser: the desktop-class kit's `HyleTree` (docs/design/desktop-class-kit.md
 *  §3) — the whole fetched-so-far tree stays visible (not a single-level drill-down); a
 *  directory's chevron fetches + expands it lazily via the same [GitBrowse.list] call the old
 *  drill-down used, a file's row tap opens it via the same [GitBrowse.read] call as before.
 *  Long-press opens a per-row [HyleContextMenu] — see [gitBrowseMenuItems] — reachable from
 *  TalkBack via the standard long-click semantics action Compose's `combinedClickable` registers
 *  on each row (HyleTree owns row layout internally and exposes no per-row slot for this file to
 *  attach a named custom action to; see the inline note at the `HyleTree` call). [generator] is
 *  optional — if null the Lens shows the code without explanations. */
@Composable
private fun GitBrowser(host: GitHost, token: String, browse: GitBrowse, generator: Generator?, onClose: () -> Unit) {
    // path ("" = root) -> that directory's listing, populated lazily as chevrons open it.
    val childrenByPath = remember { mutableStateMapOf<String, List<GitBrowse.Entry>>() }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var file by remember { mutableStateOf<Pair<String, String>?>(null) }
    var status by remember { mutableStateOf("") }
    var menuPath by remember { mutableStateOf<String?>(null) }
    // The header breadcrumb: the last path the user actually interacted with — a directory
    // they toggled (open OR closed; see [toggle]), or the parent directory of a file they
    // opened (see [openFile]). Unlike the old drill-down, "current location" is no longer a
    // single navigation cursor the whole pane is scoped to, so this is best-effort orientation,
    // not a scope — but it must still move live as the user works the tree, not sit fixed at "/".
    var currentPath by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun load(path: String) {
        if (path.isBlank()) status = "loading…"
        scope.launch {
            browse.list(host, token, path).fold(
                { childrenByPath[path] = it; if (path.isBlank()) status = if (it.isEmpty()) "empty" else "" },
                { status = "✗ ${it.message}" },
            )
        }
    }
    LaunchedEffect(host.id, host.repo) { childrenByPath.clear(); expandedIds = emptySet(); currentPath = ""; load("") }

    // Every entry seen so far, by path — lets a row's `id` (all HyleTree/HyleContextMenu
    // callbacks carry is the id) be resolved back to "is this a directory or a file".
    val entryByPath = remember(childrenByPath.toMap()) {
        buildMap { childrenByPath.values.forEach { list -> list.forEach { e -> put(e.path, e) } } }
    }
    val forest = remember(childrenByPath.toMap()) { buildGitBrowseForest(childrenByPath) }

    fun openFile(path: String) {
        currentPath = path.substringBeforeLast('/', "")
        status = "loading ${path.substringAfterLast('/')}…"
        scope.launch {
            browse.read(host, token, path).fold(
                { file = path to it; status = "" },
                { status = "✗ ${it.message}" },
            )
        }
    }
    fun toggle(path: String) {
        currentPath = path
        if (path in expandedIds) {
            expandedIds = expandedIds - path
        } else {
            expandedIds = expandedIds + path
            if (path !in childrenByPath) load(path)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${host.owner}/${host.repo} @ ${host.branch}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        // Live breadcrumb: the last directory toggled or the parent of the last
                        // file opened (currentPath, kept by toggle()/openFile() above) — not a
                        // fixed "/". The old single-pane drill-down had one navigation cursor to
                        // show here; the tree has many open branches at once, so this names
                        // "where you last touched", not "where you are".
                        Text(
                            if (currentPath.isBlank()) "/" else "/$currentPath",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider()
                // Up removed: chevron collapse on each row is the visible replacement affordance
                // — the tree keeps every opened ancestor on screen, so collapsing a row's own
                // chevron does what "Up" used to (steps back out of that directory) without
                // discarding the rest of the tree the old single-pane drill-down had to.
                if (file != null) {
                    TextButton(onClick = { file = null }) { Text("‹ Back") }
                }
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val f = file
                if (f != null) {
                    val editTransport = remember { dev.fonebrew.data.GitEdit(dev.fonebrew.data.GitTransport()) }
                    CodeLensScreen(
                        code = f.second,
                        fileName = f.first.substringAfterLast('/'),
                        filePath = f.first,
                        explain = { lines, ext -> generator?.let { g -> CodeLens.explain(lines, ext, g) } },
                        watched = false,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onCommit = { newText, message ->
                            runCatching {
                                val st = editTransport.open(host, token, f.first).getOrThrow()
                                val id = editTransport.commit(host, token, st, newText, message).getOrThrow()
                                // Reflect the committed text locally so the lens shows the new state.
                                file = f.first to newText
                                id
                            }
                        },
                    )
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        HyleTree(
                            roots = forest,
                            expandedIds = expandedIds,
                            selectedId = null,
                            onToggleExpand = ::toggle,
                            // Row body tap: a directory "opens" by revealing its children (the
                            // tree's equivalent of the old descend-and-replace-the-pane
                            // navigation — the fetch is the same GitBrowse.list call, only the
                            // result now nests in place instead of swapping out the ancestors);
                            // a file opens exactly as it always did, via GitBrowse.read.
                            onSelect = { id ->
                                val e = entryByPath[id]
                                when {
                                    e == null -> {}
                                    e.isDir -> toggle(id)
                                    else -> openFile(id)
                                }
                            },
                            onLongPress = { id -> menuPath = id },
                            // NOTE: no per-row CustomAccessibilityAction here (unlike the
                            // combinedClickable sites this task added below in ChatsRoom/TreeRoom,
                            // where this file owns the row Modifier directly) — HyleTree lays out
                            // and long-press-wires every row internally (see HyleTree.kt's private
                            // HyleTreeRowView) and exposes no per-row modifier/semantics slot to
                            // attach a named action to. TalkBack still reaches the menu through the
                            // long-press Compose's `combinedClickable` already registers as a
                            // standard long-click semantics action on each row — just without a
                            // codebase-style custom label. Giving HyleTree that per-row hook is a
                            // Hyle-side change, out of scope here (the submodule stays untouched).
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val mp = menuPath
                        if (mp != null) {
                            val e = entryByPath[mp]
                            val isDir = e?.isDir == true
                            HyleContextMenu(
                                expanded = true,
                                onDismissRequest = { menuPath = null },
                                items = gitBrowseMenuItems(isDir = isDir, expanded = mp in expandedIds),
                                onItemClick = { id ->
                                    when (id) {
                                        "toggle" -> toggle(mp)
                                        "open" -> openFile(mp)
                                        "copy_path" -> clipboard.setText(AnnotatedString("/$mp"))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen CI panel: lists recent workflow runs and lets the user trigger a run on
 *  the host's branch. All traffic goes only to the user's own host (watched). */
@Composable
private fun CiPanel(host: GitHost, token: String, transport: GitTransport, onClose: () -> Unit) {
    var workflows by remember { mutableStateOf<List<Workflow>>(emptyList()) }
    var runs by remember { mutableStateOf<List<WorkflowRun>>(emptyList()) }
    var selectedWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var status by remember { mutableStateOf("loading…") }
    var triggering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val wResp = transport.execute(CiTrigger.listWorkflows(host, token))
        if (wResp.code in 200..299) {
            workflows = CiTrigger.parseWorkflows(wResp.body)
            selectedWorkflow = workflows.firstOrNull { it.state == "active" } ?: workflows.firstOrNull()
        }
        val rResp = transport.execute(CiTrigger.listRuns(host, token))
        if (rResp.code in 200..299) runs = CiTrigger.parseRuns(rResp.body, host.kind)
        status = if (workflows.isEmpty() && runs.isEmpty()) "no workflows or runs found" else ""
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("CI — ${host.owner}/${host.repo}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("watched · your host only", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider()
                if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (workflows.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HyleDropdownField(
                            value = selectedWorkflow?.name ?: "—",
                            options = workflows.map { it.name },
                            onSelect = { selectedWorkflow = workflows[it] },
                            label = "Workflow",
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        HyleButton(
                            "Trigger",
                            onClick = {
                                val wf = selectedWorkflow ?: return@HyleButton
                                triggering = true
                                status = "triggering…"
                                scope.launch {
                                    val resp = transport.execute(CiTrigger.dispatch(host, wf.id, host.branch, token))
                                    if (resp.code in 200..299) {
                                        status = "✓ triggered on ${host.branch}"
                                        val r2 = transport.execute(CiTrigger.listRuns(host, token))
                                        if (r2.code in 200..299) runs = CiTrigger.parseRuns(r2.body, host.kind)
                                    } else {
                                        status = "✗ ${resp.code} — add `on: workflow_dispatch:` to the workflow"
                                    }
                                    triggering = false
                                }
                            },
                            enabled = selectedWorkflow != null && !triggering,
                        )
                    }
                }
                HorizontalDivider()
                Text(
                    "Recent runs",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    if (runs.isEmpty() && status.isBlank()) {
                        Text(
                            "No runs yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (run in runs.take(20)) {
                        val isSuccess = run.conclusion == "success"
                        val isFailure = run.conclusion != null && !isSuccess
                        val isRunning = run.status == "in_progress"
                        val badge = when {
                            isSuccess -> "✓"
                            isFailure -> "✗"
                            isRunning -> "⟳"
                            else -> "·"
                        }
                        val badgeColor = when {
                            isSuccess -> MaterialTheme.colorScheme.tertiary
                            isFailure -> MaterialTheme.colorScheme.error
                            isRunning -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                badge,
                                color = badgeColor,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(run.workflowName.ifEmpty { run.name }, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${run.headBranch} · ${run.event} · ${run.createdAt.take(10)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                run.conclusion ?: run.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Token-first connect wizard. Step 1: paste PAT (+ instance URL for Gitea) and
 * connect — the app resolves your identity and repo list from the host's API.
 * Step 2: pick the repo from a searchable list. No manual owner/branch/email
 * fields — those are inferred from the API response.
 */
private enum class ConnectStep { TOKEN, REPO_PICK }

@Composable
private fun GitConnectForm(
    newId: String,
    transport: GitTransport,
    onSave: (GitHost, String) -> Unit,
) {
    var step        by remember { mutableStateOf(ConnectStep.TOKEN) }
    var kind        by remember { mutableStateOf(GitHostKind.GITHUB) }
    var baseUrl     by remember { mutableStateOf("") }
    var token       by remember { mutableStateOf("") }
    var resolvedUser by remember { mutableStateOf<GitLookupApi.UserInfo?>(null) }
    var repoList    by remember { mutableStateOf<List<GitLookupApi.RepoInfo>>(emptyList()) }
    var selected    by remember { mutableStateOf<GitLookupApi.RepoInfo?>(null) }
    var authorEmail by remember { mutableStateOf("") }
    var connecting  by remember { mutableStateOf(false) }
    var connectErr  by remember { mutableStateOf<String?>(null) }
    var repoSearch  by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reset() {
        step = ConnectStep.TOKEN; token = ""; baseUrl = ""; repoSearch = ""
        selected = null; resolvedUser = null; repoList = emptyList(); connectErr = null
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (step) {

            ConnectStep.TOKEN -> {
                HyleDropdownField(
                    value    = kind.label,
                    options  = GitHostKind.entries.map { it.label },
                    onSelect = { kind = GitHostKind.entries[it] },
                    label    = "Host",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind.needsBaseUrl) {
                    DesktopHyleField(
                        baseUrl, { baseUrl = it },
                        label = "Instance URL", mandatory = true,
                        placeholder = "https://gitea.example.com",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DesktopHyleField(
                    token, { token = it },
                    label = "Access token (PAT, encrypted on-device)", mandatory = true,
                    placeholder = "paste your personal access token",
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (connecting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                connectErr?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                HyleButton(
                    "Connect",
                    onClick = {
                        connecting = true; connectErr = null
                        val url = baseUrl.trim()
                        scope.launch {
                            val uResp = transport.execute(GitLookupApi.whoAmI(kind, url, token))
                            if (uResp.code !in 200..299) {
                                connectErr = "couldn't reach ${kind.label} (HTTP ${uResp.code})"
                                connecting = false; return@launch
                            }
                            val user = runCatching { GitLookupApi.parseUser(uResp.body, kind) }.getOrElse {
                                connectErr = it.message; connecting = false; return@launch
                            }
                            val rResp = transport.execute(GitLookupApi.listRepos(kind, url, token))
                            val repos = if (rResp.code in 200..299) {
                                runCatching { GitLookupApi.parseRepos(rResp.body, kind) }.getOrDefault(emptyList())
                            } else emptyList()
                            resolvedUser = user; repoList = repos; authorEmail = user.email
                            step = ConnectStep.REPO_PICK; connecting = false
                        }
                    },
                    enabled = !connecting && token.isNotBlank() && (!kind.needsBaseUrl || baseUrl.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ConnectStep.REPO_PICK -> {
                val user = resolvedUser!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { step = ConnectStep.TOKEN; connectErr = null }) { Text("‹ Back") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        user.login + (if (user.name.isNotBlank()) " · ${user.name}" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DesktopHyleField(repoSearch, { repoSearch = it }, label = "Search repos", modifier = Modifier.fillMaxWidth())
                val filtered = repoList.filter {
                    repoSearch.isBlank() || it.fullName.contains(repoSearch, ignoreCase = true)
                }
                Column(
                    Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (filtered.isEmpty()) {
                        Text(
                            if (repoList.isEmpty()) "No repos found on this account."
                            else "No results for \"$repoSearch\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (r in filtered) {
                        val isSelected = selected?.fullName == r.fullName
                        HyleCard(selected = isSelected, onClick = { selected = r }) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(r.fullName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(
                                    r.defaultBranch + if (r.isPrivate) " · private" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (user.email.isBlank()) {
                    DesktopHyleField(
                        authorEmail, { authorEmail = it },
                        label = "Git email", mandatory = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HyleButton(
                    "Save",
                    onClick = {
                        val r = selected ?: return@HyleButton
                        onSave(
                            GitHost(
                                id          = newId,
                                displayName = r.fullName,
                                kind        = kind,
                                baseUrl     = baseUrl.trim(),
                                owner       = r.owner,
                                repo        = r.name,
                                branch      = r.defaultBranch,
                                authorName  = user.name.ifBlank { user.login },
                                authorEmail = authorEmail.ifBlank { user.email },
                            ),
                            token,
                        )
                        reset()
                    },
                    enabled = selected != null && (user.email.isNotBlank() || authorEmail.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ImageProviderRow(provider: ImageProvider, hasKey: Boolean, onDelete: () -> Unit) {
    HyleCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(provider.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${provider.kind.label} · ${provider.model}" + if (hasKey) " · key set" else " · no key",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasKey) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onDelete) { Text("Remove") }
        }
    }
}

@Composable
private fun ImageProviderForm(
    onSave: (ImageProviderKind, String, String, String, String) -> Unit,
) {
    var kind by remember { mutableStateOf(ImageProviderKind.OPENAI_IMAGE) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(kind.defaultBaseUrl) }
    var model by remember { mutableStateOf(kind.defaultModel) }
    var apiKey by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HyleDropdownField(
            value = kind.label,
            options = ImageProviderKind.entries.map { it.label },
            onSelect = { i ->
                kind = ImageProviderKind.entries[i]
                baseUrl = kind.defaultBaseUrl
                model = kind.defaultModel
            },
            label = "Type",
            modifier = Modifier.fillMaxWidth(),
        )
        DesktopHyleField(name, { name = it }, label = "Display name", modifier = Modifier.fillMaxWidth())
        DesktopHyleField(baseUrl, { baseUrl = it }, label = "Base URL", mandatory = true, modifier = Modifier.fillMaxWidth())
        DesktopHyleField(model, { model = it }, label = "Model id", mandatory = true, modifier = Modifier.fillMaxWidth())
        DesktopHyleField(
            apiKey, { apiKey = it },
            label = "API key (encrypted on-device)",
            mandatory = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        HyleButton(
            "Save image provider",
            onClick = { onSave(kind, name, baseUrl, model, apiKey); name = ""; apiKey = "" },
            enabled = apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProviderRow(
    provider: CloudProvider,
    hasKey: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    HyleCard(onClick = onEdit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${provider.displayName} · watched", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${provider.kind.label} · ${provider.model}" +
                        (if (hasKey) " · key set" else " · no key") + " · tap to edit",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasKey) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onDelete) { Text("Remove") }
        }
    }
}

@Composable
private fun ProviderForm(
    editing: CloudProvider?,
    onSave: (String?, String, ProviderKind, String, String, Int, Boolean, String) -> Unit,
    onCancelEdit: () -> Unit,
) {
    // Keyed on the provider being edited so tapping a card reloads the form.
    var kind by remember(editing) { mutableStateOf(editing?.kind ?: ProviderKind.OPENAI_COMPATIBLE) }
    var name by remember(editing) { mutableStateOf(editing?.displayName ?: "") }
    var baseUrl by remember(editing) { mutableStateOf(editing?.baseUrl ?: kind.defaultBaseUrl) }
    var model by remember(editing) { mutableStateOf(editing?.model ?: "") }
    var contextWindow by remember(editing) { mutableStateOf((editing?.contextWindow ?: 8192).toString()) }
    var supportsVision by remember(editing) { mutableStateOf(editing?.supportsVision ?: true) }
    var apiKey by remember(editing) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HyleDropdownField(
            value = kind.label,
            options = ProviderKind.entries.map { it.label },
            onSelect = { i ->
                kind = ProviderKind.entries[i]
                // Reset base URL to the new kind's default.
                baseUrl = kind.defaultBaseUrl
            },
            label = "Type",
            modifier = Modifier.fillMaxWidth(),
        )
        DesktopHyleField(name, { name = it }, label = "Display name", modifier = Modifier.fillMaxWidth())
        DesktopHyleField(baseUrl, { baseUrl = it }, label = "Base URL", mandatory = true, modifier = Modifier.fillMaxWidth())
        DesktopHyleField(
            model, { model = it },
            label = "Model id",
            placeholder = "gpt-4o · claude-opus-4-8 · deepseek-chat",
            mandatory = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DesktopHyleField(
            contextWindow, { contextWindow = it.filter(Char::isDigit) },
            label = "Context window (tokens)",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Model understands images", style = MaterialTheme.typography.bodyMedium)
            HyleSwitch(checked = supportsVision, onCheckedChange = { supportsVision = it })
        }
        DesktopHyleField(
            apiKey, { apiKey = it },
            label = if (editing == null) "API key (encrypted on-device)" else "API key (blank = keep stored)",
            mandatory = editing == null,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        HyleButton(
            if (editing == null) "Save provider" else "Save changes",
            onClick = {
                onSave(
                    editing?.id, name, kind, baseUrl, model,
                    contextWindow.toIntOrNull() ?: 8192, supportsVision, apiKey,
                )
                name = ""; model = ""; apiKey = ""; contextWindow = "8192"; supportsVision = true
            },
            // A new provider needs a key; an edit may keep the stored one.
            enabled = model.isNotBlank() && (apiKey.isNotBlank() || editing != null),
            modifier = Modifier.fillMaxWidth(),
        )
        if (editing != null) {
            TextButton(onClick = onCancelEdit, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel edit")
            }
        }
    }
}
