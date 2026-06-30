package dev.aarso.ui.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.aarso.domain.git.GitHost
import dev.aarso.domain.git.GitHostKind
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aarso.data.GitBrowse
import dev.aarso.data.GitTransport
import dev.aarso.domain.builds.Build
import dev.aarso.domain.builds.CiTrigger
import dev.aarso.domain.builds.Workflow
import dev.aarso.domain.builds.WorkflowRun
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import dev.aarso.domain.cloud.CloudProvider
import dev.aarso.domain.codelens.CodeLens
import dev.aarso.domain.council.Generator
import dev.aarso.ui.codelens.CodeLensScreen
import dev.aarso.domain.model.ModelSpec
import dev.aarso.inference.EngineGenerator
import dev.aarso.domain.cloud.ProviderKind
import dev.aarso.domain.image.ImageProvider
import dev.aarso.domain.image.ImageProviderKind
import dev.aarso.flavor.InvocationFeatures
import dev.aarso.ui.SettingsViewModel
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.hyle.HyleChip
import dev.aarso.ui.hyle.HyleDropdownField
import dev.aarso.ui.hyle.HyleField
import dev.aarso.ui.hyle.HyleTitle
import dev.aarso.ui.theme.LocalHyleColors
import dev.aarso.ui.theme.ThemePicker
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import dev.aarso.domain.git.GitLookupApi
import dev.aarso.ui.loops.LoopRoom

// The 5 Settings tabs (IA §C), each an icon. Global = config; the other four are provider
// surfaces (Image / Text / Video / 3D) that toggle on-device ⇄ watched-cloud.
private enum class SettingsTab(val label: String) {
    GLOBAL("Global"), IMAGE("Image"), TEXT("Text"), VIDEO("Video"), OBJECT3D("3D")
}

// Providers split by where they run: on-device (the default, rule 2) vs watched cloud.
private enum class ProviderScope { LOCAL, CLOUD }

/**
 * The room parked off the RIGHT edge (IA §C): **configuration only, never a launcher.** Five
 * icon tabs — Global + the four provider surfaces. Every cloud provider is a **watched object**:
 * opt-in, isolated, never a hidden default; keys stay in the Android Keystore.
 *
 * Full-screen sub-surfaces (Models, Free tiers, Remote, Git…) render in a hoisted [overlay] slot
 * at the room root, OUTSIDE the scrolling content — a scrollable child measured inside a
 * verticalScroll parent gets an infinite height constraint and crashes (the PR #39 fix).
 */
@Composable
fun SettingsRoom(
    onShowSpatialMap: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    var tab by remember { mutableStateOf(SettingsTab.GLOBAL) }
    var overlay by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    BackHandler(enabled = overlay != null) { overlay = null }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            HyleTitle("Settings")
            SettingsTabBar(tab) { tab = it }
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    SettingsTab.GLOBAL -> GlobalSettings(
                        onShowSpatialMap = onShowSpatialMap,
                        openOverlay = { overlay = it },
                        closeOverlay = { overlay = null },
                    )
                    else -> ProviderTab(
                        tab = tab,
                        viewModel = viewModel,
                        openOverlay = { overlay = it },
                        closeOverlay = { overlay = null },
                    )
                }
            }
        }
        overlay?.invoke()
    }
}

@Composable
private fun SettingsTabBar(selected: SettingsTab, onSelect: (SettingsTab) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            SettingsTab.entries.forEach { t ->
                val on = t == selected
                val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TabGlyph(t, tint)
                    Spacer(Modifier.height(5.dp))
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.height(2.dp).width(22.dp).background(
                            if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

/** Small Aeon-style line glyphs drawn in code (the app ships no icon font). */
@Composable
private fun TabGlyph(tab: SettingsTab, tint: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val sw = w * 0.09f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(tint, androidx.compose.ui.geometry.Offset(x0, y0), androidx.compose.ui.geometry.Offset(x1, y1), strokeWidth = sw)
        when (tab) {
            SettingsTab.GLOBAL -> {
                drawCircle(tint, radius = w * 0.42f, style = stroke)
                drawOval(
                    tint, topLeft = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.08f),
                    size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.84f), style = stroke,
                )
                line(w * 0.10f, h * 0.5f, w * 0.90f, h * 0.5f)
            }
            SettingsTab.IMAGE -> {
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
            SettingsTab.TEXT -> {
                line(w * 0.16f, h * 0.30f, w * 0.84f, h * 0.30f)
                line(w * 0.16f, h * 0.50f, w * 0.72f, h * 0.50f)
                line(w * 0.16f, h * 0.70f, w * 0.80f, h * 0.70f)
            }
            SettingsTab.VIDEO -> {
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
            SettingsTab.OBJECT3D -> {
                drawCircle(tint, radius = w * 0.42f, style = stroke)
                drawOval(
                    tint, topLeft = androidx.compose.ui.geometry.Offset(w * 0.06f, h * 0.34f),
                    size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.32f), style = stroke,
                )
            }
        }
    }
}

/**
 * A provider surface (Image / Text / Video / 3D) with an on-device ⇄ watched-cloud toggle
 * (IA §C). On-device is the default (rule 2). Video/3D have no engine wired yet — shown
 * honestly as planned, never faked (rule 6).
 */
@Composable
private fun ColumnScope.ProviderTab(
    tab: SettingsTab,
    viewModel: SettingsViewModel,
    openOverlay: (@Composable () -> Unit) -> Unit,
    closeOverlay: () -> Unit,
) {
    var scope by remember(tab) { mutableStateOf(ProviderScope.LOCAL) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HyleChip(scope == ProviderScope.LOCAL, { scope = ProviderScope.LOCAL }, "On-device")
        HyleChip(scope == ProviderScope.CLOUD, { scope = ProviderScope.CLOUD }, "Cloud · watched")
    }
    when (tab) {
        SettingsTab.TEXT ->
            if (scope == ProviderScope.CLOUD) TextSettings(viewModel)
            else LocalModels("chat", openOverlay, closeOverlay)
        SettingsTab.IMAGE ->
            if (scope == ProviderScope.CLOUD) ImageSettings(viewModel)
            else LocalModels("image", openOverlay, closeOverlay)
        SettingsTab.VIDEO -> PlannedProvider("Video", scope)
        SettingsTab.OBJECT3D -> PlannedProvider("3D-model", scope)
        SettingsTab.GLOBAL -> Unit // handled by SettingsRoom
    }
}

/** On-device models for a modality — managed in the Models room (opened in the overlay slot). */
@Composable
private fun LocalModels(
    kind: String,
    openOverlay: (@Composable () -> Unit) -> Unit,
    closeOverlay: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as dev.aarso.AarsoApp).container
    Text(
        "On-device $kind models run locally — the default. Download, switch, and remove them " +
            "in the Models shelf.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HyleButton("Manage on-device models", onClick = {
        openOverlay {
            val modelsVm: dev.aarso.ui.ModelsViewModel =
                viewModel(factory = dev.aarso.ui.ModelsViewModel.Factory)
            ModelsRoom(
                downloads = container.downloadCenter,
                onCustomUrl = { modelsVm.downloadCustom(it) },
                onClose = closeOverlay,
            )
        }
    })
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
private fun GlobalSettings(
    onShowSpatialMap: () -> Unit,
    openOverlay: (@Composable () -> Unit) -> Unit,
    closeOverlay: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as dev.aarso.AarsoApp).container
    val session = container.sessionStore

    // "Me · Myself · I" — the user meta (drift inert; linked accounts; usage). Provisional home
    // is here in Global until the owner picks its spatial place (IA open question).
    Text("You", style = MaterialTheme.typography.titleMedium)
    Text(
        "Your linked accounts, usage overview, and the (inert) self-reflection mirror.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HyleButton("Me · Myself · I", onClick = {
        openOverlay { dev.aarso.ui.rooms.MeScreen(onClose = closeOverlay) }
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
                val json = dev.aarso.data.DataExport.toJson(container)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Aarso export")
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

    // Models are managed in the provider tabs (Image/Text/…) now; Settings is not a launcher.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Cloud free tiers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        dev.aarso.ui.guide.HelpIcon(dev.aarso.domain.guide.Guides.ADD_CLOUD)
    }
    Text(
        "What each cloud provider gives free, and how much you've availed — a watched, " +
            "consent-gated online refresh.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HyleButton("Open free tiers", onClick = {
        openOverlay { dev.aarso.ui.rooms.FreeTiersScreen(onClose = closeOverlay) }
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

    Text("Summon from anywhere", style = MaterialTheme.typography.titleMedium)
    Text(
        buildString {
            append("• Select text in any app → tap \"Aarso\" in the selection menu.\n")
            append("• Share anything (text or image) → choose Aarso.\n")
            append(
                "• Assist gesture: set Aarso as your Digital assistant in the system " +
                    "Settings → Apps → Default apps → Digital assistant app (this replaces Gemini). " +
                    "It then captures the on-screen text and brings Aarso forward.",
            )
            if (InvocationFeatures.BUBBLE_AVAILABLE) {
                append("\n• Floating bubble (below): tap to open Aarso; long-press to OCR the screen behind it.")
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
            Switch(
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

    // Loops (pinch-in) and Develop (bottom edge) are spatial rooms, not Settings entries —
    // reach them from the map (see "How to move around" above). Settings is config only.

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Remote", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        dev.aarso.ui.guide.HelpIcon(dev.aarso.domain.guide.Guides.CONNECT_SSH)
    }
    Text(
        "Connect to your own machines over SSH — a Pi, a Dell, any server. You decide " +
            "trust (the real fingerprint is shown); the remote's output is a watched object.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HyleButton("Open Remote", onClick = {
        openOverlay { dev.aarso.ui.remote.RemoteScreen(onClose = closeOverlay) }
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
        Switch(checked = entropy, onCheckedChange = { session.setEntropyColoring(it) })
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
    HorizontalDivider()

    Text("About", style = MaterialTheme.typography.titleMedium)
    Text(
        "Aarso ${dev.aarso.BuildConfig.VERSION_NAME} — Konkani for “mirror”.\n\n" +
            "Local-first by design: conversations, models, and keys live on this " +
            "device. No analytics, no telemetry. Cloud models run only when you " +
            "invoke them, and only against the provider you configured.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        onSave = { id, name, kind, baseUrl, model, ctx, key ->
            viewModel.save(id, name, kind, baseUrl, model, ctx, key)
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

/** Connect a Git host you own (watched): tree backup + the coding assistant. The
 *  token is encrypted on-device; the app talks only to your host. */
@Composable
private fun GitConnect() {
    val container = (LocalContext.current.applicationContext as dev.aarso.AarsoApp).container
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
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, LocalHyleColors.current.hairline),
        ) {
            Column(Modifier.padding(12.dp)) {
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
                Row {
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
                    TextButton(onClick = {
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
                    }) { Text("Back up") }
                    TextButton(onClick = {
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
                    }) { Text("Pull") }
                    TextButton(onClick = {
                        if (store.token(h.id).isNullOrBlank()) status = "no token stored" else browsing = true
                    }) { Text("Browse") }
                    TextButton(onClick = {
                        if (store.token(h.id).isNullOrBlank()) status = "no token stored" else ciOpen = true
                    }) { Text("CI") }
                    TextButton(onClick = { store.remove(h.id) }) { Text("Remove") }
                }
            }
        }
    }
    Text(if (hosts.isEmpty()) "Add a host" else "Add another", style = MaterialTheme.typography.titleSmall)
    GitConnectForm(newId = store.newId(), transport = transport) { host, token -> store.upsert(host, token) }
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
    val container = (LocalContext.current.applicationContext as dev.aarso.AarsoApp).container
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
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = BorderStroke(1.dp, LocalHyleColors.current.hairline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(10.dp)) {
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
}

/** Full-screen repo browser: tap folders to descend, tap a file to view it with the
 *  Lens (CodeLensScreen with plain-English explanation). [generator] is optional — if
 *  null the Lens shows the code without explanations. */
@Composable
private fun GitBrowser(host: GitHost, token: String, browse: GitBrowse, generator: Generator?, onClose: () -> Unit) {
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<GitBrowse.Entry>>(emptyList()) }
    var file by remember { mutableStateOf<Pair<String, String>?>(null) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path) {
        file = null
        status = "loading…"
        browse.list(host, token, path).fold(
            { entries = it; status = if (it.isEmpty()) "empty" else "" },
            { status = "✗ ${it.message}" },
        )
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
                        Text(
                            if (path.isBlank()) "/" else "/$path",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider()
                when {
                    file != null -> TextButton(onClick = { file = null }) { Text("‹ Back") }
                    path.isNotBlank() -> TextButton(onClick = { path = path.substringBeforeLast('/', "") }) { Text("‹ Up") }
                }
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val f = file
                if (f != null) {
                    val editTransport = remember { dev.aarso.data.GitEdit(dev.aarso.data.GitTransport()) }
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
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                        for (e in entries) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (e.isDir) {
                                            path = e.path
                                        } else {
                                            status = "loading ${e.name}…"
                                            scope.launch {
                                                browse.read(host, token, e.path).fold(
                                                    { file = e.path to it; status = "" },
                                                    { status = "✗ ${it.message}" },
                                                )
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    (if (e.isDir) "📁  " else "📄  ") + e.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
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
                    HyleField(
                        baseUrl, { baseUrl = it },
                        label = "Instance URL", mandatory = true,
                        placeholder = "https://gitea.example.com",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HyleField(
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
                HyleField(repoSearch, { repoSearch = it }, label = "Search repos", modifier = Modifier.fillMaxWidth())
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
                        Card(
                            onClick = { selected = r },
                            colors  = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            border  = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                    HyleField(
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
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, LocalHyleColors.current.hairline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
        HyleField(name, { name = it }, label = "Display name", modifier = Modifier.fillMaxWidth())
        HyleField(baseUrl, { baseUrl = it }, label = "Base URL", mandatory = true, modifier = Modifier.fillMaxWidth())
        HyleField(model, { model = it }, label = "Model id", mandatory = true, modifier = Modifier.fillMaxWidth())
        HyleField(
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
    Card(
        modifier = Modifier.clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, LocalHyleColors.current.hairline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
    onSave: (String?, String, ProviderKind, String, String, Int, String) -> Unit,
    onCancelEdit: () -> Unit,
) {
    // Keyed on the provider being edited so tapping a card reloads the form.
    var kind by remember(editing) { mutableStateOf(editing?.kind ?: ProviderKind.OPENAI_COMPATIBLE) }
    var name by remember(editing) { mutableStateOf(editing?.displayName ?: "") }
    var baseUrl by remember(editing) { mutableStateOf(editing?.baseUrl ?: kind.defaultBaseUrl) }
    var model by remember(editing) { mutableStateOf(editing?.model ?: "") }
    var contextWindow by remember(editing) { mutableStateOf((editing?.contextWindow ?: 8192).toString()) }
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
        HyleField(name, { name = it }, label = "Display name", modifier = Modifier.fillMaxWidth())
        HyleField(baseUrl, { baseUrl = it }, label = "Base URL", mandatory = true, modifier = Modifier.fillMaxWidth())
        HyleField(
            model, { model = it },
            label = "Model id",
            placeholder = "gpt-4o · claude-opus-4-8 · deepseek-chat",
            mandatory = true,
            modifier = Modifier.fillMaxWidth(),
        )
        HyleField(
            contextWindow, { contextWindow = it.filter(Char::isDigit) },
            label = "Context window (tokens)",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        HyleField(
            apiKey, { apiKey = it },
            label = if (editing == null) "API key (encrypted on-device)" else "API key (blank = keep stored)",
            mandatory = editing == null,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        HyleButton(
            if (editing == null) "Save provider" else "Save changes",
            onClick = {
                onSave(editing?.id, name, kind, baseUrl, model, contextWindow.toIntOrNull() ?: 8192, apiKey)
                name = ""; model = ""; apiKey = ""; contextWindow = "8192"
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
