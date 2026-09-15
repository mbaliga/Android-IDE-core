@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.fonebrew.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.fonebrew.domain.markdown.StreamingMarkdown
import dev.fonebrew.core_engine.R
import androidx.activity.compose.BackHandler
import dev.fonebrew.data.DownloadCenter
import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.bridge.BridgeCodec
import dev.fonebrew.domain.bridge.SummaryBridge
import dev.fonebrew.domain.cloud.Source
import dev.fonebrew.domain.gesture.ComposerQuote
import dev.fonebrew.domain.instrument.Confidence
import dev.fonebrew.domain.prompt.LintSeverity
import dev.fonebrew.domain.prompt.PromptLinter
import dev.fonebrew.domain.tree.Attachments
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.domain.tree.PathView
import dev.fonebrew.domain.tree.Sources
import dev.fonebrew.flavor.InvocationFeatures
import dev.aarso.hyle.cells.HeaderGlyph
import dev.aarso.hyle.cells.HyleBottomTabBar
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleChip
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.cells.HyleHeaderButton
import dev.aarso.hyle.cells.HyleTabSpec
import dev.aarso.hyle.cells.FileImage
import dev.aarso.hyle.cells.HyleFocusLens
import dev.aarso.hyle.cells.HyleLensActions
import dev.aarso.hyle.cells.HyleLensHeading
import dev.aarso.hyle.cells.HyleRadialMenu
import dev.aarso.hyle.cells.HyleRadialMenuItem
import dev.aarso.hyle.cells.HyleSealedLens
import dev.aarso.hyle.cells.HyleSwitch
import dev.aarso.hyle.cells.hylePulse
import dev.aarso.hyle.cells.rememberHyleHaptics
import dev.fonebrew.ui.components.MentionPopup
import dev.fonebrew.ui.components.MentionTarget
import dev.fonebrew.ui.components.SlashCommand
import dev.fonebrew.ui.components.SlashCommandPopup
import dev.fonebrew.ui.components.SummaryNodeCard
import dev.fonebrew.ui.components.applyMention
import dev.fonebrew.ui.components.isShellEscape
import dev.fonebrew.ui.components.matchMentions
import dev.fonebrew.ui.components.matchSlashCommands
import dev.fonebrew.ui.curation.RoundtableRequest
import dev.fonebrew.ui.curation.RoundtableSlot
import dev.fonebrew.ui.curation.VersionSuggestSlot
import dev.fonebrew.ui.develop.TerminalFacet
import dev.fonebrew.ui.search.InChatFindBar
import dev.fonebrew.ui.search.InChatFindPresenter
import dev.fonebrew.ui.state.CostLinePresenter
import dev.fonebrew.ui.state.TurnCostMetadata
import dev.fonebrew.ui.state.TurnProvenance
import dev.aarso.hyle.theme.LocalHyleColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The home room of the spatial shell: the active thread. No app chrome beyond a
 * model badge top-right — navigation is spatial (edge drags, bottom overscroll,
 * pinch — wired by SpatialRoot through [threadModifier]). The thesis axes stay
 * progressively disclosed: instruments in a collapsible strip, per-turn actions
 * behind long-press, the council and image generation as composer modes.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    threadModifier: Modifier = Modifier,
    onOpenModels: () -> Unit = {},
    onOpenChats: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** asoc-reachability audit (2026-09-15) item 1b: the Tree (pinch-in only, no tappable
     *  entry) — a header affordance beside the existing Chats/Settings corner buttons. Defaults
     *  to a no-op so ChatScreen stays usable stand-alone (previews, tests) without a controller,
     *  same as [onOpenChats]/[onOpenSettings] above. */
    onOpenTree: () -> Unit = {},
    /** S9 continuity: text carried in from an app-wide search result. Opens the find bar
     *  pre-filled and scrolls to the first hit. Consumed once, via [onFindRequestConsumed], so
     *  reopening the bar later doesn't resurrect a stale query. */
    findRequest: String? = null,
    onFindRequestConsumed: () -> Unit = {},
    /** Reverse continuity: promote what's in the find bar to the app-wide search overlay. */
    onSearchAllChats: (String) -> Unit = {},
    /** The spatial shell's `controller.atHome` fact. Gates two things: the ThreadRail hides
     *  whenever a room is open (THREAD_TOPOLOGY_PLAN.md WP5 — avoids colliding with an open
     *  room's edge peek / parked card), and the tab-reset BackHandler below disarms so Back
     *  closes the open room first instead of silently switching this screen's lens. Defaults
     *  true so ChatScreen stays usable stand-alone (previews, tests) without a controller. */
    atHome: Boolean = true,
) {
    val state by viewModel.uiState.collectAsState()
    val instrumentsExpanded by viewModel.instrumentsExpanded.collectAsState()
    val entropyColoring by viewModel.entropyColoring.collectAsState()
    // The Conversation Instrument (STUDIO_UX_SPEC.md §4-5): verdicts/bookmarks/versions/
    // compaction directives keyed by message id, for the per-bubble controls + curation sheet.
    val verdicts by viewModel.verdicts.collectAsState()
    val messageBookmarks by viewModel.messageBookmarks.collectAsState()
    val versionsByTip by viewModel.versionsByTip.collectAsState()
    val compactionDirectives by viewModel.compactionDirectives.collectAsState()
    // W1 (vision input): photos picked/captured but not yet sent. State lives on ChatViewModel
    // (not a bare remember{} here — same class of bug as the composer-draft-text bug this
    // codebase already fixed once).
    val pendingAttachments by viewModel.pendingAttachments.collectAsState()
    var input by remember { mutableStateOf("") }
    var showModelSheet by remember { mutableStateOf(false) }
    var showPlus by remember { mutableStateOf(false) }
    // W1: gallery pick needs no runtime permission (Android Photo Picker); camera capture writes
    // full-res into AttachmentStore's dir via the FileProvider agent wired in the manifest, so it
    // also needs no storage permission.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { viewModel.addPendingAttachmentFromUri(it) }
    }
    // The contract only returns success/failure, not the Uri — ChatViewModel remembers which
    // path it handed out (survives rotation; the ViewModel outlives this composition).
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        viewModel.onCameraCaptureResult(success)
    }
    // docs/design/objects-3d.md §1: the "Generate 3D…" mini-chooser (On-device / Cloud·watched).
    var showObject3dChooser by remember { mutableStateOf(false) }
    // The object3d node currently opened in the viewer — (relativePath, format) from the tapped
    // node's metadata; the loaded content/error is resolved async below (file I/O off the tap).
    var object3dViewerTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var object3dViewerContent by remember { mutableStateOf<dev.fonebrew.ui.object3d.ObjectViewerContent?>(null) }
    var object3dViewerError by remember { mutableStateOf<String?>(null) }
    // WhatsApp-style: the header title opens participants/group info (the interaction-mode
    // chooser now lives there — see ParticipantsScreen's currentMode/onModeChange), superseding
    // the old composer-row chips + separate "Participants" TextButton.
    var showParticipants by remember { mutableStateOf(false) }
    var actionStep by remember { mutableStateOf<PathView.Step?>(null) }
    var flagStep by remember { mutableStateOf<PathView.Step?>(null) }
    // Curation sheet's "Mark branch as Version…" (STUDIO_UX_SPEC.md §4.4): a lightweight
    // name-entry dialog, same shape as flagStep's own follow-on dialog above.
    var versionNameStep by remember { mutableStateOf<PathView.Step?>(null) }
    var versionNameInput by remember { mutableStateOf("") }
    // THREAD_TOPOLOGY_PLAN.md WP1's TurnActionsSheet rows ("Mark chapter here…"/"Start fresh
    // session here"): the chapter name prompt reuses versionNameStep's own dialog pattern above;
    // session-start needs no name prompt, it fires straight from the sheet.
    var chapterNameStep by remember { mutableStateOf<PathView.Step?>(null) }
    var chapterNameInput by remember { mutableStateOf("") }
    // S-new seam (STUDIO_UX_SPEC.md §5.3/§13 S14): the race TurnActionsSheet's "Re-run with…"
    // row hands off to, when a paid layer has installed RoundtableSlot — see its KDoc. Null in
    // the bare open core, where that row is never even shown (see TurnActionsSheet below).
    var roundtableRequest by remember { mutableStateOf<RoundtableRequest?>(null) }
    // D1: dismissible "Connect your repos" home card (session-scoped dismissal).
    var connectDismissed by remember { mutableStateOf(false) }
    // THREAD_TOPOLOGY_PLAN.md WP7: the Instruments panel — entry from the expanded
    // InstrumentsStrip below.
    var showInstruments by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // S9: find-in-chat (WP14) — row-level, not sub-string highlighting; see InChatFind.kt's
    // KDoc for why (the message body renders through the mikepenz Markdown composable, which
    // owns its own text layout).
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findOptions by remember { mutableStateOf(InChatFindPresenter.FindOptions()) }
    var findIndex by remember { mutableStateOf(-1) }
    val findResult = remember(state.steps, findQuery, findOptions) {
        InChatFindPresenter.find(state.steps.map { it.node.id to it.node.content }, findQuery, findOptions)
    }
    LaunchedEffect(findQuery, findOptions) { findIndex = if (findResult.hits.isEmpty()) -1 else 0 }
    // S9 continuity: a search result was opened — carry its text into this chat's find bar.
    LaunchedEffect(findRequest) {
        val incoming = findRequest?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        findQuery = incoming
        findOptions = InChatFindPresenter.FindOptions()
        findOpen = true
        onFindRequestConsumed()
    }
    val currentFindHit = findResult.hits.getOrNull(findIndex)
    // The thread list's leading "connect repos" card (below) shifts every step's LazyColumn
    // item index by one when shown — read the same signal here so find-scroll lands on the
    // right row instead of one off.
    val ctx0 = LocalContext.current
    val container0 = (ctx0.applicationContext as dev.fonebrew.FonebrewApp).container
    val gitHosts by container0.gitHostStore.hosts.collectAsState()
    val findScrollPrefix = if (!connectDismissed && gitHosts.isEmpty()) 1 else 0

    // docs/design/objects-3d.md §1's "3D file…" SAF import — the picker itself lives here
    // (ChatScreen owns activity-result launchers); the read/store/mint work is ChatViewModel's.
    val object3dPickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importObject3d(it, queryDisplayName(ctx0, it)) } }

    // §3: loads the tapped object3d node's bytes off the main thread and decodes them into
    // whatever ObjectViewerRoom needs — a real model file, or (for the on-device DSL path) the
    // scene JSON text via ProceduralSceneCodec. Re-runs whenever a different node is tapped.
    LaunchedEffect(object3dViewerTarget) {
        val target = object3dViewerTarget
        object3dViewerContent = null
        object3dViewerError = null
        if (target == null) return@LaunchedEffect
        val (relativePath, format) = target
        runCatching {
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container0.object3dStore.readBytes(relativePath)
            }
            if (format == dev.fonebrew.data.Object3dNodeMeta.DSL_JSON_FORMAT) {
                val scene = dev.fonebrew.domain.object3d.ProceduralSceneCodec.fromJsonString(String(bytes, Charsets.UTF_8))
                dev.fonebrew.ui.object3d.ObjectViewerContent.Procedural(scene)
            } else {
                dev.fonebrew.ui.object3d.ObjectViewerContent.Model(bytes, dev.fonebrew.domain.object3d.Object3dFormat.valueOf(format))
            }
        }.fold(
            onSuccess = { object3dViewerContent = it },
            onFailure = { object3dViewerError = it.message ?: "couldn't open this 3D object" },
        )
    }
    // THREAD_TOPOLOGY_PLAN.md WP4: Settings → Gestures — every switch OFF collapses
    // Modifier.messageGestures back to nothing but the always-available long-press (parity rule,
    // binding constraint 4: gestures are additive sugar, never the only way in).
    val gestureVerdictDrag by container0.sessionStore.gestureVerdictDragEnabled.collectAsState()
    val gestureQuoteReply by container0.sessionStore.gestureQuoteReplyEnabled.collectAsState()
    val gestureRadialFan by container0.sessionStore.gestureRadialFanEnabled.collectAsState()
    val gestureToggles = remember(gestureVerdictDrag, gestureQuoteReply, gestureRadialFan) {
        MessageGestureToggles(gestureVerdictDrag, gestureQuoteReply, gestureRadialFan)
    }

    // Lane G / owner ruling 2026-09-06 (open-ux-decisions.md item G, G1-MODIFIED): the per-turn
    // inline cost line only renders when this Settings toggle is on (default off — see
    // SessionStore.perTurnCostInChat's own KDoc). currencyCode is the same display-currency
    // preference the Provider-pricing form and every ledger view already use — CostLinePresenter
    // never invents its own number or its own currency.
    val perTurnCostVisible by container0.sessionStore.perTurnCostInChat.collectAsState()
    val costCurrencyCode by container0.pricingStore.currencyCode.collectAsState()
    val costLocale = java.util.Locale.getDefault()

    // Chat / Terminal / Tasks — three lenses on the centre's one activity: ask in Chat and it
    // runs, drive the shell directly in Terminal (§ owner spec, 2026-07-27), or watch what's
    // running underneath in Tasks. Tasks joined as a peer tab in the 2026-08-21 consolidation:
    // it was the bottom CenterViewTabBar's "Background" lens, and when that bar died (it showed
    // chat's tabs in every room) the lens moved here rather than vanishing. The collapsed
    // [BackgroundTasksStrip] inside Chat stays — it's the one-line summary; this is the full
    // window. Back returns to Chat first (BackHandler below), same contract the bottom bar had.
    var chatTab by remember { mutableStateOf(ChatTab.CHAT) }
    // Gated on atHome so the spatial shell's own room-closing BackHandler (registered earlier,
    // so lower priority when both are enabled) still wins whenever a room is open.
    BackHandler(enabled = atHome && chatTab != ChatTab.CHAT) { chatTab = ChatTab.CHAT }
    val universalTabBarPosition by container0.sessionStore.tabBarPosition.collectAsState()
    val roomTabBarOverrides by container0.sessionStore.roomTabBarPosition.collectAsState()
    val tabBarPosition = roomTabBarOverrides["chat"] ?: universalTabBarPosition
    val activeDownloads by container0.downloadCenter.active.collectAsState()
    val backgroundJobs by container0.backgroundJobs.jobs.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            container0.backgroundJobs.prune()
            kotlinx.coroutines.delay(60_000)
        }
    }

    // Slash commands: a keyboard-driven shortcut to the same actions the header chips, "+" sheet,
    // and composer-mode row already expose — nothing here reaches for a navigation hook the
    // screen doesn't already have.
    val slashCommands = remember(onOpenChats, onOpenSettings) {
        listOf(
            SlashCommand("/chat", "Switch to Chat") { chatTab = ChatTab.CHAT },
            SlashCommand("/terminal", "Switch to Terminal") { chatTab = ChatTab.TERMINAL },
            SlashCommand("/tasks", "Switch to Background Tasks") { chatTab = ChatTab.TASKS },
            SlashCommand("/participants", "Manage council participants") { showParticipants = true },
            SlashCommand("/models", "Switch model") { showModelSheet = true },
            SlashCommand("/image", "Generate an image") { viewModel.setComposerMode(ComposerMode.IMAGE) },
            SlashCommand("/chats", "Open Chats") { onOpenChats() },
            SlashCommand("/settings", "Open Settings") { onOpenSettings() },
        )
    }
    val slashMatches = matchSlashCommands(input, slashCommands)

    // @ mentions: direct a message at one council participant by name — the same "@" convention
    // as every mention-capable chat tool, not a Fonebrew invention. Picking one inserts "@Name "
    // into the composer; ChatViewModel.sendCouncil resolves a *leading* "@Name" (via
    // domain.council.CouncilRouting) and narrows the fan-out to that one voice for the turn —
    // this popup only handles the composer-insertion half.
    val participants by container0.councilStore.participants.collectAsState()
    val mentionTargets = remember(participants) {
        participants.map { p -> MentionTarget(p.name, "council participant", "@${p.name} ") }
    }
    val mentionMatches = matchMentions(input, mentionTargets)

    // THREAD_TOPOLOGY_PLAN.md WP5: ThreadRail — the dash minimap over the active path.
    val threadMarkers by viewModel.threadMarkers.collectAsState()
    val railView = remember(state.steps, verdicts, messageBookmarks, versionsByTip, compactionDirectives, threadMarkers, state.genPhase, state.activeModelId, state.models) {
        // Provenance per turn, resolved the same way every other provenance-tagged surface in
        // this app does (ModelOption.watched — binding rule 2's own "watched object" flag);
        // a user/system turn ran no model at all, so it's trivially LOCAL (nothing left the
        // device), same reasoning ChatViewModel's own private `provenanceFor` uses.
        val modelsById = state.models.associateBy { it.id }
        val provenanceOf: (dev.fonebrew.domain.MessageNode) -> dev.fonebrew.domain.provenance.ProvenanceState = { node ->
            val modelId = node.modelId
            when {
                modelId == null -> dev.fonebrew.domain.provenance.ProvenanceState.LOCAL
                modelsById[modelId]?.watched == true -> dev.fonebrew.domain.provenance.ProvenanceState.CLOUD
                modelsById[modelId] != null -> dev.fonebrew.domain.provenance.ProvenanceState.LOCAL
                else -> dev.fonebrew.domain.provenance.ProvenanceState.UNKNOWN // a model no longer in the catalog — honest, not guessed
            }
        }
        val live = if (state.genPhase != GenPhase.IDLE) {
            dev.fonebrew.ui.state.ThreadRailPresenter.LiveGeneration(watched = modelsById[state.activeModelId]?.watched == true)
        } else null
        dev.fonebrew.ui.state.ThreadRailPresenter.present(
            steps = state.steps,
            verdicts = verdicts,
            bookmarks = messageBookmarks,
            versionsByTip = versionsByTip,
            directives = compactionDirectives,
            markersByAnchor = threadMarkers,
            provenanceOf = provenanceOf,
            live = live,
        )
    }
    val onRailJump: (String) -> Unit = { nodeId ->
        val stepIndex = state.steps.indexOfFirst { it.node.id == nodeId }
        if (stepIndex >= 0) scope.launch { listState.animateScrollToItem(findScrollPrefix + stepIndex) }
    }

    // Fixed per audit: VerdictDragRibbon's live capture-importance preview used to hardcode
    // isOnVersionSpine = false everywhere ("not plumbed to the bubble in this WP"). ThreadRail
    // already computes exactly this fact per-node via a backward pass over versionsByTip
    // (ThreadRailPresenter.present's own "onOrAfterTip") — reused here, on the active path, so
    // the drag ribbon's "would keep: …" preview agrees with what ThreadRail itself shows.
    val onOrAfterVersionTip = remember(state.steps, versionsByTip) {
        val ids = HashSet<String>()
        var seenTip = false
        for (i in state.steps.indices.reversed()) {
            if (versionsByTip.containsKey(state.steps[i].node.id)) seenTip = true
            if (seenTip) ids += state.steps[i].node.id
        }
        ids
    }

    LaunchedEffect(currentFindHit) {
        val hit = currentFindHit ?: return@LaunchedEffect
        val stepIndex = state.steps.indexOfFirst { it.node.id == hit.nodeId }
        if (stepIndex >= 0) listState.animateScrollToItem(findScrollPrefix + stepIndex)
    }

    // §7: text shared in / selected elsewhere arrives here — prefill the input, and land on
    // the lens that has a composer (the share could arrive while Terminal/Tasks is up).
    val intake by viewModel.intake.collectAsState()
    LaunchedEffect(intake) {
        intake?.text?.let {
            input = it
            chatTab = ChatTab.CHAT
            viewModel.consumeIntake()
        }
    }

    LaunchedEffect(state.steps.size, state.streamingTokens.size, state.genPhase) {
        val count = state.steps.size + if (state.genPhase != GenPhase.IDLE) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    // Turn failures surface as a snackbar; when the path ends on an unanswered
    // user turn, Retry regenerates in place.
    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = message,
            actionLabel = if (state.canRegenerate) "Retry" else null,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.regenerate()
        viewModel.clearError()
    }

    Box(Modifier.fillMaxSize()) {
        // SpatialRoot's outer Box already reserves the nav-bar with systemBarsPadding(); a plain
        // imePadding() here would stack on top of that and leave a nav-bar-sized gap between the
        // composer and the keyboard, so exclude what's already reserved.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
        ) {
            // TOP file tabs sit outside the room chrome and merge into its upper edge. The
            // header follows inside the room, matching the traditional and immersive references.
            if (tabBarPosition != "BOTTOM") {
                ChatTabBar(
                    tab = chatTab,
                    position = tabBarPosition,
                    onSelect = { chatTab = it },
                )
            }
            HomeHeader(
                state = state,
                onBadgeTap = { if (!state.isGenerating) showModelSheet = true },
                onTitleClick = { showParticipants = true },
                onOpenChats = onOpenChats,
                onOpenSettings = onOpenSettings,
                onOpenTree = onOpenTree,
            )
            when (chatTab) {
                ChatTab.TERMINAL -> Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    TerminalFacet()
                }
                ChatTab.TASKS -> TasksLens(
                    center = container0.downloadCenter,
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                ChatTab.CHAT -> Column(Modifier.weight(1f)) {
            BackgroundTasksStrip(activeDownloads, backgroundJobs)
            InstrumentsStrip(
                state = state,
                input = input,
                expanded = instrumentsExpanded,
                onToggle = { viewModel.setInstrumentsExpanded(!instrumentsExpanded) },
                entropyColoring = entropyColoring,
                onEntropyColoring = viewModel::setEntropyColoring,
                onOpenCompaction = { viewModel.openCompactionPreview() },
                onOpenInstruments = { showInstruments = true },
            )
            if (findOpen) {
                InChatFindBar(
                    query = findQuery,
                    onQueryChange = { findQuery = it },
                    options = findOptions,
                    onOptionsChange = { findOptions = it },
                    statusText = when {
                        findResult.error != null -> findResult.error
                        findQuery.isBlank() -> ""
                        findResult.hits.isEmpty() -> "No matches"
                        else -> "${findIndex + 1} of ${findResult.hits.size}"
                    },
                    hasHits = findResult.hits.isNotEmpty(),
                    onPrevious = { findIndex = InChatFindPresenter.step(findResult.hits.size, findIndex, forward = false) },
                    onNext = { findIndex = InChatFindPresenter.step(findResult.hits.size, findIndex, forward = true) },
                    onClose = { findOpen = false; findQuery = "" },
                    onSearchAllChats = { onSearchAllChats(findQuery) },
                )
            }
            Box(modifier = Modifier.weight(1f).then(threadModifier)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // D1: "Connect your repos" — only when no Git host is wired and
                    // not dismissed. Opens Settings, where the token-first wizard lives.
                    if (!connectDismissed) {
                        item("connect-repos") {
                            val ctx = LocalContext.current
                            val hosts by (ctx.applicationContext as dev.fonebrew.FonebrewApp)
                                .container.gitHostStore.hosts.collectAsState()
                            if (hosts.isEmpty()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    border = BorderStroke(1.dp, LocalHyleColors.current.hairline),
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text("Connect your repos", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Develop on your own Git host — browse code, run loops, see builds, install APKs, manage a board, all in-app.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.size(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            HyleButton("Connect", onClick = onOpenSettings)
                                            HyleButton("Not now", onClick = { connectDismissed = true }, secondary = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.steps.isEmpty() && state.streamingText == null) {
                        item {
                            if (state.noModelActive) {
                                SetupCard(viewModel, onOpenModels)
                            } else {
                                EmptyHint()
                            }
                        }
                    }
                    items(state.steps, key = { it.node.id }) { step ->
                        // THREAD_TOPOLOGY_PLAN.md WP2: a bridge node (interaction-model switch or
                        // Spawn) carries a decodable structural payload — render SummaryNodeCard
                        // instead of the plain bubble when it does.
                        val bridge = step.node.metadata[BridgeCodec.BRIDGE_PAYLOAD_KEY]?.let(BridgeCodec::decode)
                        val srcRoot = step.node.metadata["lineage.srcRoot"]
                        // Fixed per audit (WP2 known defect): confirmInteractionChange()'s bridge
                        // stamps only lineage.srcNode (same-root branch, not a cross-root lineage
                        // pointer) — try the cross-root path first, same as before, and fall back
                        // to an in-place scroll to the pre-switch turn for a same-root bridge.
                        val srcNode = step.node.metadata["lineage.srcNode"]
                        MessageTurn(
                            step = step,
                            enabled = !state.isGenerating,
                            showPerTurnCost = perTurnCostVisible,
                            costCurrencyCode = costCurrencyCode,
                            costLocale = costLocale,
                            onSwitch = { dir -> viewModel.switchAlternative(step.node.id, dir) },
                            onLongPress = { actionStep = step },
                            onCompare = { viewModel.openCompare(step.node.id) },
                            onChooseForMe = { viewModel.chooseForMe(step.node.id) },
                            chooseForMeEnabled = state.genPhase == GenPhase.IDLE,
                            onOpenObject3d = { path, format -> object3dViewerTarget = path to format },
                            bridge = bridge,
                            onViewFullPrior = {
                                if (srcRoot != null) {
                                    viewModel.openConversation(srcRoot)
                                } else if (srcNode != null) {
                                    val idx = state.steps.indexOfFirst { it.node.id == srcNode }
                                    if (idx >= 0) scope.launch { listState.animateScrollToItem(findScrollPrefix + idx) }
                                }
                            },
                            highlighted = findOpen && currentFindHit?.nodeId == step.node.id,
                            verdict = verdicts[step.node.id],
                            directive = compactionDirectives[step.node.id],
                            isOnVersionSpine = onOrAfterVersionTip.contains(step.node.id),
                            bookmarked = messageBookmarks[step.node.id]?.any { it.ref.blockIndex == null } == true,
                            // Chevron tap = one detent step per tap, cycling to a clear on the
                            // third: null -> +1 -> +2 -> null (and the mirror for down). Tapping
                            // the opposite chevron while the other polarity is set jumps to that
                            // polarity's first detent, matching "the current judgment always
                            // reflects the last chevron pressed."
                            onVerdictUp = {
                                val next = when (verdicts[step.node.id]?.grade) {
                                    1 -> 2
                                    2 -> null
                                    else -> 1
                                }
                                if (next == null) viewModel.clearVerdict(step.node.id) else viewModel.setVerdict(step.node.id, next)
                            },
                            onVerdictDown = {
                                val next = when (verdicts[step.node.id]?.grade) {
                                    -1 -> -2
                                    -2 -> null
                                    else -> -1
                                }
                                if (next == null) viewModel.clearVerdict(step.node.id) else viewModel.setVerdict(step.node.id, next)
                            },
                            onToggleBookmark = { viewModel.toggleMessageBookmark(step.node.id) },
                            // THREAD_TOPOLOGY_PLAN.md WP4: message drag gestures — the pure
                            // MessageDragLogic classifier decides WHAT happened; everything below
                            // is just "what to do about it," identical to what the equivalent
                            // tappable control already does (TurnActionsSheet / VerdictBookmarkRow
                            // chevrons), so gesture and tap stay two doors to the same action.
                            gestureToggles = gestureToggles,
                            onVerdictDragCommit = { grade ->
                                val previous = verdicts[step.node.id]
                                viewModel.setVerdict(step.node.id, grade)
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        message = "Marked ${verdictLabel(grade)}",
                                        actionLabel = "Undo",
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        if (previous != null) {
                                            viewModel.setVerdict(step.node.id, previous.grade)
                                        } else {
                                            viewModel.clearVerdict(step.node.id)
                                        }
                                    }
                                }
                            },
                            onReplyDrag = { input = ComposerQuote.reply(input, step.node.content) },
                            onQuoteDrag = { input = ComposerQuote.quote(input, step.node.content) },
                            onBranchDrag = { viewModel.branchFrom(step.node.id) },
                            onForkDrag = { viewModel.forkFrom(step.node.id) },
                            onSpawnDrag = { viewModel.spawnFrom(step.node.id) },
                        )
                    }
                    // Single streaming bubble — only when not in a council fan-out.
                    if (state.genPhase != GenPhase.IDLE && state.councilCards.isEmpty()) {
                        item("streaming") {
                            StreamingBubble(
                                phase = state.genPhase,
                                tokens = state.streamingTokens,
                                entropyColoring = entropyColoring,
                                imageMode = state.imageMode,
                                object3dMode = state.object3dMode,
                                watched = state.models.find { it.id == state.activeModelId }?.watched ?: false,
                            )
                        }
                    }

                    // Council panel: the lateral set of voices held simultaneously (§4b).
                    if (state.councilCards.isNotEmpty()) {
                        items(state.councilCards, key = { "council-${it.agent}" }) { card ->
                            CouncilCardView(
                                card = card,
                                enabled = state.genPhase == GenPhase.IDLE,
                                onContinue = { card.nodeId?.let { viewModel.branchFrom(it) } },
                            )
                        }
                        item("council-actions") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.genPhase != GenPhase.IDLE) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("working…", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    HyleButton("Auto-merge (model)", onClick = { viewModel.autoMergeCouncil() })
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "or tap a voice to continue",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // Down-arrow FAB (§2): appears whenever the view left the newest turn.
                if (listState.canScrollForward) {
                    Surface(
                        onClick = {
                            scope.launch {
                                val count = listState.layoutInfo.totalItemsCount
                                if (count > 0) listState.animateScrollToItem(count - 1)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, LocalHyleColors.current.hairline),
                        tonalElevation = 4.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_down),
                                contentDescription = "Jump to latest",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // S9 entry point: find-in-chat, only worth offering once there's a thread to
                // search. Toggling closed clears the query (a fresh find each time it opens).
                if (state.steps.isNotEmpty() && !findOpen) {
                    Surface(
                        onClick = { findOpen = true },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = BorderStroke(1.dp, LocalHyleColors.current.hairline),
                        tonalElevation = 4.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(40.dp)
                            .semantics { contentDescription = "Find in this chat" },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("⌕", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // THREAD_TOPOLOGY_PLAN.md WP5: the dash minimap — hidden whenever the spatial
                // shell isn't at home (avoids EdgePeek/parked-card collision, per the ThreadRail
                // section's own gating rule) or there's nothing yet to map.
                if (atHome && !railView.isEmpty) {
                    dev.fonebrew.ui.components.ThreadRail(
                        dashes = railView.dashes,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onJump = onRailJump,
                    )
                }
            }

            // On-demand prompt rewrite suggestion (§6b).
            state.promptSuggestion?.let { suggestion ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Suggested rewrite", style = MaterialTheme.typography.labelMedium)
                        Text(suggestion, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { input = suggestion; viewModel.clearSuggestion() }) { Text("Use") }
                            TextButton(onClick = { viewModel.clearSuggestion() }) { Text("Dismiss") }
                        }
                    }
                }
            }

            if (slashMatches.isNotEmpty()) {
                SlashCommandPopup(slashMatches) { cmd -> cmd.run(); input = "" }
            } else if (mentionMatches.isNotEmpty()) {
                MentionPopup(mentionMatches) { target -> input = applyMention(input, target) }
            }

            // W2 (web search): a globe chip near the "+", opt-in per turn, default off (cloud
            // extras are opt-in — CLAUDE.md rule 2). State lives on ChatViewModel (not a bare
            // remember{} here — same reasoning as the composer-draft-text bug this codebase
            // already fixed once). Stays visible-but-disabled with a short reason when the
            // active model can't search — the PlusSheet Photo/Camera row's convention (W1),
            // never hidden (legibility thesis). Only the single-model turn wires search
            // end-to-end this pass (council/image/3D are out of scope, same exclusion as W1's
            // vision work), so the chip only shows in that mode.
            if (!state.noModelActive && !state.imageMode && !state.object3dMode && !state.councilEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HyleChip(
                        selected = state.webSearchOn,
                        onClick = { viewModel.toggleWebSearch() },
                        label = "⌕ Search",
                        enabled = state.genPhase == GenPhase.IDLE && state.activeSupportsSearch,
                        modifier = Modifier.semantics {
                            contentDescription = if (state.activeSupportsSearch) {
                                "Web search toggle"
                            } else {
                                "Web search unavailable — this model can't search the web"
                            }
                        },
                    )
                    if (!state.activeSupportsSearch) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "this model can't search the web",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Image mode is entered from the "+" sheet (no pill); a banner shows + exits it.
            if (state.imageMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "🖼 Generating an image",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.setComposerMode(ComposerMode.SINGLE) }) { Text("Exit") }
                }
            }

            // 3D-object mode — same "entered from + sheet, banner shows + exits it" shape as
            // image mode above (docs/design/objects-3d.md §1). The banner names the scope the
            // mini-chooser picked, so the opt-in-per-use choice (rule 2) stays visible while typing.
            if (state.object3dMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "🧊 Generating a 3D object · " +
                            if (state.object3dScope == Object3dGenScope.CLOUD) "Cloud · watched" else "On-device",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.setComposerMode(ComposerMode.SINGLE) }) { Text("Exit") }
                }
            }

            // "!cmd" runs locally instead of going to a model (Jupyter/IPython convention) —
            // flag it before Send so it's never a silent surprise which path a message takes.
            if (isShellEscape(input)) {
                Text(
                    "⌘ Runs as a shell command on this phone",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            // W1 (vision input): photos picked/captured but not yet sent. State lives on
            // ChatViewModel, not a bare remember{} here (see the composer-draft-text bug this
            // deliberately doesn't repeat) — cleared on successful send.
            if (pendingAttachments.isNotEmpty()) {
                PendingAttachmentStrip(
                    attachments = pendingAttachments,
                    onRemove = viewModel::removePendingAttachment,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val imageMode = state.imageMode
                val object3dMode = state.object3dMode
                val generationMode = imageMode || object3dMode
                // The "+" (Gemini-style): attach + generation tools live here, not as pills.
                TextButton(
                    onClick = { showPlus = true },
                    enabled = state.genPhase == GenPhase.IDLE,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text("＋", style = MaterialTheme.typography.titleLarge)
                }
                HyleField(
                    value = input,
                    onValueChange = { input = it },
                    label = "",
                    singleLine = false,
                    modifier = Modifier.weight(1f),
                    placeholder = when {
                        imageMode -> "Describe an image"
                        object3dMode -> "Describe a 3D object"
                        state.noModelActive -> "Download a model to begin"
                        state.engineAvailable -> "Message"
                        else -> "Model not runnable yet"
                    },
                    enabled = state.genPhase == GenPhase.IDLE && (state.engineAvailable || generationMode),
                )
                // Always in the tree (never conditionally included) so the field/Send button
                // beside it never shifts position entering/exiting image/3D mode — reserve the
                // space and fade + disable instead (same jitter class already fixed elsewhere).
                TextButton(
                    onClick = { viewModel.refinePrompt(input) },
                    enabled = !generationMode && input.isNotBlank() && !state.rewriting &&
                        state.genPhase == GenPhase.IDLE && state.engineAvailable,
                    modifier = Modifier.alpha(if (!generationMode) 1f else 0f),
                ) {
                    Text(if (state.rewriting) "…" else "Refine")
                }
                if (state.genPhase != GenPhase.IDLE) {
                    // An in-flight image/3D render has no cancel point (§6/§4-5) — the
                    // button stays as state, disabled, rather than lying.
                    HyleButton(
                        "Stop",
                        onClick = { viewModel.stopGeneration() },
                        enabled = !generationMode,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    HyleButton(
                        if (generationMode) "Generate" else "Send",
                        onClick = {
                            viewModel.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank() && (state.engineAvailable || generationMode),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
                } // end ChatTab.CHAT column
            } // end when (chatTab)
            if (tabBarPosition == "BOTTOM") {
                ChatTabBar(
                    tab = chatTab,
                    position = tabBarPosition,
                    onSelect = { chatTab = it },
                )
            }
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))

        // Loading a GGUF into memory is genuinely obstructive — typing or switching rooms
        // mid-load races the engine's init, so the surface goes untouchable until it settles.
        if (state.genPhase == GenPhase.LOADING) {
            // Shown, not said: the ground goes out of reach and keeps moving, because
            // the load genuinely is running. The words survive only for a screen reader.
            HyleSealedLens("Loading model…")
        }
    }

    if (showModelSheet) {
        ModelPickerSheet(
            state = state,
            onSelect = { viewModel.switchModel(it); showModelSheet = false },
            onManage = { showModelSheet = false; onOpenModels() },
            onDismiss = { showModelSheet = false },
        )
    }

    if (showPlus) {
        PlusSheet(
            onGenerateImage = { viewModel.setComposerMode(ComposerMode.IMAGE); showPlus = false },
            onGenerateObject3d = { showPlus = false; showObject3dChooser = true },
            onImportObject3dFile = { showPlus = false; object3dPickerLauncher.launch(arrayOf("*/*")) },
            supportsVision = state.activeSupportsVision,
            onPickPhoto = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                showPlus = false
            },
            onTakePhoto = {
                cameraLauncher.launch(viewModel.newCameraCaptureUri())
                showPlus = false
            },
            onDismiss = { showPlus = false },
        )
    }

    if (showObject3dChooser) {
        Object3dScopeChooserDialog(
            onPick = { scope -> viewModel.enterObject3dMode(scope); showObject3dChooser = false },
            onDismiss = { showObject3dChooser = false },
        )
    }

    // docs/design/objects-3d.md §3: tapping an object3d node opens the offline viewer once its
    // bytes/scene are loaded (see the LaunchedEffect above); a load error surfaces honestly
    // instead of a blank/garbled viewer.
    if (object3dViewerTarget != null) {
        val content = object3dViewerContent
        val loadError = object3dViewerError
        when {
            content != null -> dev.fonebrew.ui.object3d.ObjectViewerRoom(
                content = content,
                onClose = { object3dViewerTarget = null },
            )
            loadError != null -> AlertDialog(
                onDismissRequest = { object3dViewerTarget = null },
                confirmButton = { TextButton(onClick = { object3dViewerTarget = null }) { Text("Close") } },
                title = { Text("Couldn't open this 3D object") },
                text = { Text(loadError) },
            )
            else -> Dialog(onDismissRequest = { object3dViewerTarget = null }) {
                Surface(shape = MaterialTheme.shapes.large) {
                    Box(Modifier.padding(32.dp)) { CircularProgressIndicator() }
                }
            }
        }
    }

    if (showParticipants) {
        Dialog(
            onDismissRequest = { showParticipants = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                dev.fonebrew.ui.rooms.ParticipantsScreen(
                    onClose = { showParticipants = false },
                    conversationId = state.steps.firstOrNull()?.node?.id,
                    currentMode = state.composerMode,
                    onModeChange = viewModel::requestComposerMode,
                )
            }
        }
    }

    // Interaction model is locked once a chat starts (IA §B4): changing it branches with a summary.
    // Grown as a HyleFocusLens (the app's own material — see docs/LENS.md) rather than a generic
    // platform AlertDialog, matching every other confirmation in this app; this one had been
    // missed in the earlier lens migration (owner-flagged: "the modal did not make it as I wished
    // for it to").
    val pendingMode by viewModel.pendingInteractionChange.collectAsState()
    HyleFocusLens(
        visible = pendingMode != null,
        onDismiss = { viewModel.cancelInteractionChange() },
    ) {
        val mode = pendingMode ?: return@HyleFocusLens
        val label = when (mode) {
            ComposerMode.MODELS -> "Council · models"
            ComposerMode.PERSONAS -> "Council · personas"
            else -> "Single"
        }
        HyleLensHeading(
            title = "Switch to $label?",
            body = "The interaction model is locked once a conversation starts. Switching " +
                "starts a new branch and summarizes everything so far into it — your " +
                "current thread stays intact on the tree.",
        )
        HyleLensActions {
            TextButton(onClick = { viewModel.cancelInteractionChange() }) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            HyleButton("Branch & switch", onClick = { viewModel.confirmInteractionChange() })
        }
    }

    actionStep?.let { step ->
        TurnActionsSheet(
            step = step,
            onBranch = { viewModel.branchFrom(step.node.id); actionStep = null },
            onFlag = { flagStep = step; actionStep = null },
            onDismiss = { actionStep = null },
            bookmarked = messageBookmarks[step.node.id]?.any { it.ref.blockIndex == null } == true,
            onToggleBookmark = { viewModel.toggleMessageBookmark(step.node.id) },
            onMarkVersion = { versionNameInput = ""; versionNameStep = step; actionStep = null },
            directive = compactionDirectives[step.node.id],
            onSetFidelity = { fidelity ->
                val current = compactionDirectives[step.node.id]
                viewModel.setCompactionDirective(step.node.id, current?.mustInclude ?: false, fidelity)
            },
            // Fixed per adversarial review: this used to hardcode Fidelity.F1 as the fallback
            // when no directive existed yet, silently downgrading a message that auto-resolves
            // higher (e.g. a +2-verdict message auto-floors to F3) the instant Must-include was
            // touched. ChatViewModel.toggleMustInclude resolves the *actual* current fidelity
            // via the same contract CompactionEngine uses, and preserves it.
            onToggleMustInclude = { viewModel.toggleMustInclude(step.node.id) },
            onRewind = { viewModel.rewindFrom(step.node.id); actionStep = null },
            // S-new seam: only ever reachable when RoundtableSlot.isInstalled (the row itself is
            // absent otherwise — see TurnActionsSheet), so this is safe to wire unconditionally.
            // candidateModelIds left empty — RoundtableRequest's own KDoc: an empty list means
            // "let the installed layer pick its own default set," which core has no basis to
            // choose (it has no concept of what Roundtable considers a good pairing).
            onReRunWithModels = { roundtableRequest = RoundtableRequest(originMsgId = step.node.id); actionStep = null },
            onMarkChapter = { chapterNameInput = ""; chapterNameStep = step; actionStep = null },
            onStartSessionHere = { viewModel.markSessionStart(step.node.id); actionStep = null },
            onFork = { viewModel.forkFrom(step.node.id); actionStep = null },
            onSpawn = { viewModel.spawnFrom(step.node.id); actionStep = null },
            onCompactFromHere = { viewModel.openCompactionPreview(step.node.id); actionStep = null },
            // THREAD_TOPOLOGY_PLAN.md WP4: the tappable parity for pull-right/pull-left —
            // previously flagged "not built this pass" in this sheet's own KDoc; same
            // ComposerQuote transform the drag callbacks use.
            onQuote = { input = ComposerQuote.quote(input, step.node.content); actionStep = null },
            onReply = { input = ComposerQuote.reply(input, step.node.content); actionStep = null },
        )
    }

    // S-new seam (STUDIO_UX_SPEC.md §5.3/§13 S14): full-screen, same Dialog(usePlatformDefaultWidth
    // = false) + fillMaxSize Surface wrapping as showParticipants above — RoundtableSlot's
    // installed content (like ParticipantsScreen) is plain content, not a self-wrapping screen, so
    // core owns the chrome. `content` is re-read (not captured once) so a mid-session entitlement
    // unlock while this exact dialog is already open would still resolve — belt-and-braces, since
    // roundtableRequest is only ever set from a row that itself checked isInstalled first.
    roundtableRequest?.let { request ->
        RoundtableSlot.content?.let { installed ->
            Dialog(
                onDismissRequest = { roundtableRequest = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    installed(request) { roundtableRequest = null }
                }
            }
        }
    }

    // THREAD_TOPOLOGY_PLAN.md WP2: "Compare alternatives" — stacked cards for every sibling at a
    // branch point, reached from the pager row's new TextButton.
    val compareSheet by viewModel.compareSheet.collectAsState()
    compareSheet?.let { compare ->
        CompareSheet(
            compare = compare,
            onContinue = { leafId -> viewModel.branchFrom(leafId); viewModel.closeCompare() },
            onDismiss = { viewModel.closeCompare() },
        )
    }

    // THREAD_TOPOLOGY_PLAN.md WP3: the Compaction preview sheet + its loud F3-violation dialog.
    val compactionPreview by viewModel.compactionPreview.collectAsState()
    val compactionRunning by viewModel.compactionRunning.collectAsState()
    compactionPreview?.let { rows ->
        CompactionSheet(
            rows = rows,
            running = compactionRunning,
            onRun = { viewModel.runCompaction() },
            onDismiss = { viewModel.closeCompactionPreview() },
        )
    }
    val compactionFailure by viewModel.compactionFailure.collectAsState()
    compactionFailure?.let { violations ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissCompactionFailure() },
            title = { Text("Compaction failed") },
            text = {
                Column {
                    Text(
                        "${violations.size} message(s) marked \"reproduce exactly\" came back " +
                            "altered. Nothing was saved — the conversation is unchanged.",
                    )
                    Spacer(Modifier.height(8.dp))
                    for (v in violations.take(5)) {
                        Text("• ${v.msgId}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCompactionFailure() }) { Text("OK") }
            },
        )
    }

    // THREAD_TOPOLOGY_PLAN.md WP7: the Instruments panel, entered from the expanded
    // InstrumentsStrip above. Mounts ContextAssembly's ledger (via the ViewModel's
    // instrumentsAssembly), the last turn's token-confidence heatmap (via lastTurnTokens), and
    // this conversation's on-device usage totals (folded from the ledger the "Myself" screen
    // already reads, scoped to this chat's root id — same on-device-only source, no telemetry).
    if (showInstruments) {
        val ledgerEntries by container0.ledgerStore.entries().collectAsState(initial = emptyList())
        val chatId = state.steps.firstOrNull()?.node?.id
        val ledgerTotals = remember(ledgerEntries, chatId) {
            dev.fonebrew.domain.instrument.InstrumentsAssembly.totalsForChat(ledgerEntries, chatId ?: "")
        }
        val (tokenScores, tokenAvailability) = remember(state.lastTurnTokens) {
            dev.fonebrew.domain.instrument.InstrumentsAssembly.tokenScores(state.lastTurnTokens)
        }
        val currencyCode by container0.pricingStore.currencyCode.collectAsState()
        InstrumentsPanel(
            assembled = state.instrumentsAssembly,
            tokenScores = tokenScores,
            tokenAvailability = tokenAvailability,
            ledgerTotals = ledgerTotals,
            delegationCounts = state.delegationCounts,
            locale = java.util.Locale.getDefault(),
            currencyCode = currencyCode,
            onDismiss = { showInstruments = false },
        )
    }

    flagStep?.let { step ->
        FlagOutputDialog(
            content = step.node.content,
            modelId = step.node.modelId,
            onDismiss = { flagStep = null },
        )
    }

    versionNameStep?.let { step ->
        AlertDialog(
            onDismissRequest = { versionNameStep = null },
            title = { Text("Mark branch as Version") },
            text = {
                HyleField(
                    value = versionNameInput,
                    onValueChange = { versionNameInput = it },
                    label = "Name",
                    singleLine = true,
                    placeholder = "e.g. auth-flow v2",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = versionNameInput.isNotBlank(),
                    onClick = {
                        viewModel.markVersion(step.node.id, versionNameInput.trim())
                        versionNameStep = null
                    },
                ) { Text("Mark") }
            },
            dismissButton = { TextButton(onClick = { versionNameStep = null }) { Text("Cancel") } },
        )
    }

    // THREAD_TOPOLOGY_PLAN.md WP1: TurnActionsSheet's "Mark chapter here…" naming prompt — same
    // shape as versionNameStep's own dialog above.
    chapterNameStep?.let { step ->
        AlertDialog(
            onDismissRequest = { chapterNameStep = null },
            title = { Text("Mark chapter here") },
            text = {
                HyleField(
                    value = chapterNameInput,
                    onValueChange = { chapterNameInput = it },
                    label = "Chapter name",
                    singleLine = true,
                    placeholder = "e.g. Auth flow rewrite",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = chapterNameInput.isNotBlank(),
                    onClick = {
                        viewModel.markChapter(step.node.id, chapterNameInput.trim())
                        chapterNameStep = null
                    },
                ) { Text("Mark") }
            },
            dismissButton = { TextButton(onClick = { chapterNameStep = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The home room's persistent chrome: leading "back to Chats" / trailing Settings corner buttons
 * (per the owner's reference mockup, 2026-08-27) flanking the conversation title (quiet, centred,
 * WhatsApp-style tappable to open [dev.fonebrew.ui.rooms.ParticipantsScreen] — group info + the
 * interaction-mode chooser live behind the header now, not a separate composer-row of chips) and
 * the status indicator. "‹ Chats"/"⚙" used to be a permanent part of [ChatTabBar], travelling
 * with it to wherever [tabBarPosition] docked the Chat/Terminal/Background-Tasks switcher — the
 * owner's mockup keeps them pinned to the header at all times instead, so they moved here
 * (2026-08-27) and [ChatTabBar] is tabs-only now.
 */
@Composable
private fun HomeHeader(
    state: ChatUiState,
    onBadgeTap: () -> Unit,
    onTitleClick: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTree: () -> Unit,
) {
    val c = LocalHyleColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HyleHeaderButton(
            glyph = HeaderGlyph.ROOM_LEFT,
            onClick = onOpenChats,
            contentDescription = "Open chats",
            slantLeft = false,
        )
        // Current conversation title (truncated) — tap opens participants/group info, same as
        // tapping a WhatsApp chat's header.
        Text(
            state.steps.firstOrNull { it.node.role == Role.USER }
                ?.node?.content?.lineSequence()?.firstOrNull()?.take(36) ?: "New chat",
            style = MaterialTheme.typography.titleMedium,
            color = c.textHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onTitleClick),
        )
        // The user-selectable status chip (Settings -> Global -> Header status). Replaces the
        // fixed Me·Myself·I avatar shortcut — that screen stays reachable from Settings, this
        // slot now shows whatever single fact the user opted into seeing at a glance, or nothing.
        HeaderIndicator(state)
        // asoc-reachability audit (2026-09-15) item 1b: the Tree was pinch-in ONLY — no tappable
        // entry anywhere in the app. [HyleHeaderButton]'s glyph enum lives in the Hyle submodule
        // (dev.aarso:hyle) and adding a case there is out of this lane's scope, so this reuses
        // [dev.aarso.hyle.cells.HyleNavChip] instead — the same header-chip idiom (slant-edged,
        // accent-adjacent, already carries its own contentDescription param), already exported
        // by Hyle and otherwise unused in this app.
        dev.aarso.hyle.cells.HyleNavChip(
            label = "Tree",
            onClick = onOpenTree,
            contentDescription = "Conversation tree",
            slantLeft = true,
        )
        HyleHeaderButton(
            glyph = HeaderGlyph.SETTINGS,
            onClick = onOpenSettings,
            contentDescription = "Open settings",
            slantLeft = true,
        )
    }
}

/**
 * The Chat/Terminal/Background-Tasks switcher — three windows onto the same underlying
 * capability, not a Chat-vs-something-else split (§ owner spec). [HyleBottomTabBar] is the Hyle
 * file-tab control: the selected tab is cut from the room's own material and merges into its edge;
 * inactive tabs stay on the black app ground behind slash seams. TOP mirrors the order to place
 * Chat at the upper-right; BOTTOM places it at the lower-left. Its responsive label ladder retains
 * the selected label before falling back to icons under IME/narrow constraints. The "‹ Chats"/"⚙"
 * shortcuts remain in [HomeHeader], inside the traditional room chrome.
 */
@Composable
private fun ChatTabBar(
    tab: ChatTab,
    position: String,
    onSelect: (ChatTab) -> Unit,
) {
    HyleBottomTabBar(
        tabs = listOf(
            HyleTabSpec("Chat") { tint ->
                val w = size.width; val h = size.height
                val sw = w * 0.09f
                drawLine(tint, Offset(w * 0.16f, h * 0.34f), Offset(w * 0.84f, h * 0.34f), strokeWidth = sw)
                drawLine(tint, Offset(w * 0.16f, h * 0.52f), Offset(w * 0.68f, h * 0.52f), strokeWidth = sw)
                drawLine(tint, Offset(w * 0.16f, h * 0.70f), Offset(w * 0.50f, h * 0.70f), strokeWidth = sw)
            },
            HyleTabSpec("Terminal") { tint ->
                val w = size.width; val h = size.height
                val sw = w * 0.10f
                drawLine(tint, Offset(w * 0.18f, h * 0.32f), Offset(w * 0.42f, h * 0.5f), strokeWidth = sw)
                drawLine(tint, Offset(w * 0.18f, h * 0.68f), Offset(w * 0.42f, h * 0.5f), strokeWidth = sw)
                drawLine(tint, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.82f, h * 0.70f), strokeWidth = sw)
            },
            // The job table: dotted rows, each dot a task, each bar its lane.
            HyleTabSpec("Background Tasks") { tint ->
                val w = size.width; val h = size.height
                val sw = w * 0.09f
                drawCircle(tint, radius = sw * 0.55f, center = Offset(w * 0.20f, h * 0.36f))
                drawLine(tint, Offset(w * 0.34f, h * 0.36f), Offset(w * 0.82f, h * 0.36f), strokeWidth = sw)
                drawCircle(tint, radius = sw * 0.55f, center = Offset(w * 0.20f, h * 0.66f))
                drawLine(tint, Offset(w * 0.34f, h * 0.66f), Offset(w * 0.64f, h * 0.66f), strokeWidth = sw)
            },
        ),
        selected = tab.ordinal,
        onSelect = { onSelect(ChatTab.entries[it]) },
        position = position,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Background tasks — a layer BENEATH Chat/Terminal, not a third peer tab (§ owner spec): a
 * collapsed one-line entry inside Chat, expandable into Running/Finished sections the same shape
 * as this app's OWN build tooling shows its parallel background agents. Two real sources, merged
 * for display only: [DownloadCenter.active] (its own percentage) and [dev.fonebrew.data.BackgroundJobs.jobs]
 * (a Loop run, the coding Agent proposing a change — start/finish only, no fraction). Nothing here
 * is invented state (rule 6): a source only appears once something actually registers it.
 */
@Composable
private fun BackgroundTasksStrip(
    active: Map<String, DownloadCenter.State>,
    jobs: List<dev.fonebrew.data.BackgroundJobs.Job>,
) {
    val runningJobs = jobs.filter { it.finishedAt == null }
    val finishedJobs = jobs.filter { it.finishedAt != null }
    val runningCount = active.size + runningJobs.size
    if (runningCount == 0 && finishedJobs.isEmpty()) return

    val c = LocalHyleColors.current
    var expanded by remember { mutableStateOf(false) }

    // A live clock for "Ns" elapsed labels on running jobs — ticks only while there's something
    // to time AND the strip is open, so a collapsed or idle strip never recomposes on its own.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expanded, runningCount) {
        if (expanded && runningCount > 0) {
            while (true) {
                now = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = c.textMid,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                if (runningCount > 0) {
                    "$runningCount background ${if (runningCount == 1) "task" else "tasks"} running"
                } else {
                    "${finishedJobs.size} background ${if (finishedJobs.size == 1) "task" else "tasks"} finished"
                },
                style = MaterialTheme.typography.labelSmall,
                color = c.textMid,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            if (runningCount > 0) {
                BgSectionLabel("Running")
                active.values.forEach { s -> BgDownloadRow(s) }
                runningJobs.forEach { j -> BgJobRow(j, now, finished = false) }
            }
            if (finishedJobs.isNotEmpty()) {
                BgSectionLabel("Finished")
                finishedJobs.sortedByDescending { it.finishedAt }.forEach { j -> BgJobRow(j, now, finished = true) }
            }
        }
    }
}

@Composable
private fun BgSectionLabel(text: String) {
    val c = LocalHyleColors.current
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = c.textMid,
        modifier = Modifier.padding(start = 22.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** A small kind glyph — download / loop / agent / other — ahead of every row's label. */
@Composable
private fun BgIcon(kind: String) {
    val c = LocalHyleColors.current
    Text(
        when (kind) {
            "download" -> "⇩"
            "loop" -> "◆"
            "agent" -> "⌁"
            "distill" -> "✎"
            else -> "•"
        },
        style = MaterialTheme.typography.labelSmall,
        color = c.textMid,
        modifier = Modifier.padding(end = 6.dp),
    )
}

@Composable
private fun BgDownloadRow(s: DownloadCenter.State) {
    val c = LocalHyleColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BgIcon("download")
        Text(
            s.request.fileName,
            style = MaterialTheme.typography.labelSmall,
            color = c.textHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            when {
                s.failed -> "failed"
                s.paused -> "paused · ${(s.progress.fraction * 100).toInt()}%"
                else -> "${(s.progress.fraction * 100).toInt()}%"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (s.failed) c.error else c.textMid,
        )
    }
}

@Composable
private fun BgJobRow(job: dev.fonebrew.data.BackgroundJobs.Job, now: Long, finished: Boolean) {
    val c = LocalHyleColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BgIcon(job.kind)
        Text(
            job.label,
            style = MaterialTheme.typography.labelSmall,
            color = c.textHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (finished) {
                if (job.failed) "failed" else "completed"
            } else {
                "${((now - job.startedAt) / 1000).coerceAtLeast(0)}s"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (job.failed) c.error else c.textMid,
        )
    }
}

/** Two windows onto the same underlying capability (§ owner spec) — never a Chat-vs-Terminal
 *  content fork, just where you're looking from. Background tasks is a layer beneath both. */
private enum class ChatTab { CHAT, TERMINAL, TASKS }

/**
 * The user-selectable status chip that replaced the fixed Me·Myself·I avatar: a single fact,
 * chosen in Settings -> Global -> "Header status" ([dev.fonebrew.data.SessionStore.headerIndicator]),
 * about the CURRENT conversation only — never a claim about the whole account/device, matching
 * this header's existing "quiet, per-conversation" scope (the title text beside it works the
 * same way). Renders nothing for "NONE" (the default) or before there's anything to say yet.
 */
@Composable
private fun HeaderIndicator(state: ChatUiState) {
    val context = LocalContext.current
    val container = (context.applicationContext as dev.fonebrew.FonebrewApp).container
    val mode by container.sessionStore.headerIndicator.collectAsState()
    if (mode == "NONE") return
    val c = LocalHyleColors.current
    val rootId = state.steps.firstOrNull()?.node?.id ?: return

    val label = when (mode) {
        "SOVEREIGNTY" -> {
            val entries by container.ledgerStore.entries().collectAsState(initial = emptyList())
            val split = remember(entries, rootId) {
                dev.fonebrew.domain.ledger.LedgerAggregations.provenanceSplit(
                    entries.filter { it.chatId == rootId },
                )
            }
            if (split.onDeviceTokens + split.cloudTokens <= 0L) return
            "⌂ ${(split.sovereigntyRatio * 100).roundToInt()}%"
        }
        "QUOTA" -> {
            val usage by container.freeTierUsageStore.usage.collectAsState()
            val requestsToday = usage.values.sumOf { it.requestsToday }
            if (requestsToday <= 0) return
            "$requestsToday today"
        }
        "TIME" -> {
            val startedAt = state.steps.firstOrNull()?.node?.createdAt ?: return
            dev.fonebrew.ui.rooms.relativeTime(startedAt)
        }
        else -> return
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.inset, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMid, maxLines = 1)
    }
}

/**
 * The instruments, progressively disclosed (§5a): one collapsed summary line —
 * tap to open the context meter, token I/O, prefill notice, lint findings, and
 * the entropy-colouring toggle. The disclosure state persists.
 */
@Composable
private fun InstrumentsStrip(
    state: ChatUiState,
    input: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    entropyColoring: Boolean,
    onEntropyColoring: (Boolean) -> Unit,
    /** THREAD_TOPOLOGY_PLAN.md WP3: "entry from InstrumentsStrip" — opens the compaction preview
     *  for the active leaf. Null hides the row (nothing to compact with no leaf yet). */
    onOpenCompaction: (() -> Unit)? = null,
    /** THREAD_TOPOLOGY_PLAN.md WP7: "entry from expanded InstrumentsStrip" — opens the
     *  Instruments panel (ContextAssembly ledger, token I/O, per-token confidence heatmap).
     *  Null hides the row. */
    onOpenInstruments: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // A model that can't run is a gating state, not an instrument — always visible.
        state.unavailableReason?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val ctx = state.context
        val stats = state.tokenStats
        if (ctx == null && stats == null && !state.prefillPending) return

        val summary = buildString {
            if (ctx != null) {
                append("ctx ${ctx.usedTokens}/${ctx.window}")
                if (!ctx.fits) append(" · over by ${ctx.overflowBy}")
            }
            if (stats != null) {
                if (isNotEmpty()) append(" · ")
                append("in ${stats.inputTokens} / out ${stats.outputTokens}")
            }
            if (state.prefillPending) {
                if (isNotEmpty()) append(" · ")
                append("prefill pending")
            }
        }
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$summary  ${if (expanded) "▴" else "▾"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (ctx?.fits == false) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!expanded) return

        ctx?.let {
            LinearProgressIndicator(
                progress = { it.fraction },
                modifier = Modifier.fillMaxWidth(),
                color = if (!it.fits) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        stats?.outputPerInput?.let {
            Text(
                "%.1f× output per input token".format(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.prefillPending) {
            Text(
                "model switched — the next turn reprocesses the context (KV cache can't cross models)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "colour tokens by confidence (entropy)",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            HyleSwitch(checked = entropyColoring, onCheckedChange = onEntropyColoring)
        }
        // THREAD_TOPOLOGY_PLAN.md WP3: "entry from InstrumentsStrip" — opens the Compaction
        // preview for the active leaf; nothing is generated until the sheet's own confirm.
        if (onOpenCompaction != null && state.steps.isNotEmpty()) {
            TextButton(onClick = onOpenCompaction, modifier = Modifier.fillMaxWidth()) {
                Text("Compact conversation…", style = MaterialTheme.typography.labelSmall)
            }
        }
        // THREAD_TOPOLOGY_PLAN.md WP7: "entry from expanded InstrumentsStrip" — opens the
        // Instruments panel (context-assembly ledger, token I/O, per-token confidence).
        if (onOpenInstruments != null && state.steps.isNotEmpty()) {
            TextButton(onClick = onOpenInstruments, modifier = Modifier.fillMaxWidth()) {
                Text("Instruments…", style = MaterialTheme.typography.labelSmall)
            }
        }
        // Instant, model-free prompt lint (§6a), recomputed as you type.
        val lint = remember(input) { PromptLinter.lint(input) }
        for (f in lint.findings) {
            Text(
                text = (if (f.severity == LintSeverity.SUGGESTION) "• " else "· ") + f.message,
                style = MaterialTheme.typography.labelSmall,
                color = if (f.severity == LintSeverity.SUGGESTION) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** The send mode, explicit and legible: one voice, a council, an image, or a 3D object (§4b/§6,
 *  docs/design/objects-3d.md §1). */
/**
 * The composer "+" sheet (Gemini-style, IA §B5): attach + generation tools, instead of pills.
 * Image generation, 3D generation, and photo attach (W1) are wired; video / file-attach are
 * honest "soon" rows (rule 6 — never claim a capability that isn't there). They map onto the
 * provider types in Settings. Photo/Camera stay visible even when the active model can't see
 * images — a disabled row with the reason as its subtitle, never a hidden one (legibility thesis).
 */
@Composable
private fun PlusSheet(
    onGenerateImage: () -> Unit,
    onGenerateObject3d: () -> Unit,
    onImportObject3dFile: () -> Unit,
    supportsVision: Boolean,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val visionReason = if (supportsVision) {
        "Attach a photo — the active model can see images"
    } else {
        "This model can't see images — switch model or continue in text"
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Create", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlusRow("🖼", "Image", "Generate & edit — on-device or watched cloud", enabled = true, onClick = onGenerateImage)
                PlusRow("🎬", "Video", "Soon — no engine wired yet", enabled = false) {}
                PlusRow("🧊", "Generate 3D…", "On-device or watched cloud — you pick", enabled = true, onClick = onGenerateObject3d)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Attach", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlusRow("🖼", "Photo", visionReason, enabled = supportsVision, onClick = onPickPhoto)
                PlusRow("📷", "Camera", visionReason, enabled = supportsVision, onClick = onTakePhoto)
                PlusRow("📎", "File", "Soon — multimodal input not wired yet", enabled = false) {}
                PlusRow("🧊", "3D file…", "Import any supported model file to preview", enabled = true, onClick = onImportObject3dFile)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/**
 * docs/design/objects-3d.md §1's mini-chooser: On-device / Cloud · watched, cloud opt-in per use
 * (binding rule 2). [dev.fonebrew.ui.components.ProvenanceBadge] gives each option the same
 * glyph+label dual-channel identity every other provenance surface in this app uses — colour is
 * never the sole carrier of "this would leave your device."
 */
@Composable
private fun Object3dScopeChooserDialog(onPick: (Object3dGenScope) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Generate a 3D object", style = MaterialTheme.typography.titleSmall)
                Object3dScopeRow(
                    title = "On-device",
                    subtitle = "Prompts your active chat model for a scene. Nothing leaves this device.",
                    provenance = dev.fonebrew.domain.provenance.ProvenanceState.LOCAL,
                    onClick = { onPick(Object3dGenScope.ON_DEVICE) },
                )
                Object3dScopeRow(
                    title = "Cloud · watched",
                    subtitle = "Meshy or Tripo, per your Settings → 3D provider. Opt-in for this generation only.",
                    provenance = dev.fonebrew.domain.provenance.ProvenanceState.CLOUD,
                    onClick = { onPick(Object3dGenScope.CLOUD) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun Object3dScopeRow(
    title: String,
    subtitle: String,
    provenance: dev.fonebrew.domain.provenance.ProvenanceState,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        dev.fonebrew.ui.components.ProvenanceBadge(provenance, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Best-effort SAF display-name lookup for the picked object3d file, used both as the user-turn
 *  label and as [dev.fonebrew.domain.object3d.Object3dFormatSniffer]'s extension fallback. Never
 *  throws — an unresolved name just falls back to the format's own label upstream. */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

@Composable
private fun PlusRow(icon: String, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, modifier = Modifier.width(36.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * W1 (vision input): photos picked/captured but not yet sent, above the composer field. Each
 * thumbnail reuses [FileImage] (same decoder as the persisted-turn render branch) with a small
 * ✕ to drop it before send — matching this codebase's text-glyph convention rather than a
 * Material icon (no icon library is imported here today).
 */
@Composable
private fun PendingAttachmentStrip(attachments: List<PendingAttachment>, onRemove: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Box(modifier = Modifier.size(64.dp)) {
                FileImage(
                    path = attachment.path,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                )
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                        .clickable { onRemove(attachment.id) },
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("✕", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * The model picker as a sheet: on-device first (with sizes), cloud below it —
 * each cloud entry explicitly a watched object (binding rule 2) — and the way
 * into the Models room for downloads.
 */
@Composable
private fun ModelPickerSheet(
    state: ChatUiState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val onDevice = state.models.filter { !it.watched }
    val cloud = state.models.filter { it.watched }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("On-device", style = MaterialTheme.typography.titleSmall)
            if (onDevice.isEmpty()) {
                Text(
                    "Nothing downloaded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            for (m in onDevice) {
                ModelRow(m, active = m.id == state.activeModelId, onSelect = onSelect)
            }
            if (cloud.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Cloud — watched",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                for (m in cloud) {
                    ModelRow(m, active = m.id == state.activeModelId, onSelect = onSelect)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                Text("Manage models →")
            }
        }
    }
}

@Composable
private fun ModelRow(m: ModelOption, active: Boolean, onSelect: (String) -> Unit) {
    val detail = buildString {
        m.sizeBytes?.let { append("%.1f GB · ".format(it / 1_000_000_000.0)) }
        append("${m.contextWindow / 1024}k ctx")
        if (!m.runnable) append(" · unavailable")
    }
    TextButton(
        onClick = { onSelect(m.id) },
        enabled = m.runnable,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                (if (active) "● " else "") + m.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Long-press actions for one turn (STUDIO_UX_SPEC.md §4.6's curation sheet, thumb-ordered):
 * bookmark, mark-as-version, compaction directive (must-include + fidelity dial), rewind, the
 * pre-existing branch/copy/flag actions, and — since THREAD_TOPOLOGY_PLAN.md WP4 — the tappable
 * parity for the pull-left/pull-right drags ([onReply]/[onQuote]). "Raw view" (below "Copy
 * text") added 2026-09-15 (asoc-reachability audit item 6) — a plain dialog of the turn's exact
 * stored content, no markdown rendering, no L4 view needed for that.
 *
 * "Re-run with…" (asoc-reachability audit item, 2026-09-15) is now built: right below "Rewind
 * from here" — see the `if (RoundtableSlot.isInstalled)` guard around its row below — it hands
 * off to whatever a paid Studio layer installed into [dev.fonebrew.ui.curation.RoundtableSlot]
 * (the race/blind-mode/consensus UI itself is entirely Studio-side; core only owns the row, the
 * [dev.fonebrew.ui.curation.RoundtableRequest] it builds from this turn, and the full-screen
 * Dialog chrome it hands the installed content — see the `roundtableRequest` handling in
 * [ChatScreen]). Free core / not-entitled: `RoundtableSlot.content` is null, so the row is
 * **absent**, not a disabled placeholder — same rule [dev.fonebrew.ui.rooms.SettingsEntitlementSlot]
 * already follows, so there is no dead tappable to repeat the pitch-tab defect. No radial/drag
 * twin was added alongside it: unlike Branch/Fork/Spawn (which the gesture layer,
 * [dev.fonebrew.domain.gesture.MessageDragLogic], already models as drag intents this sheet's
 * rows mirror), there is no re-run drag gesture defined anywhere in this app, so there is no
 * existing tappable-parity contract to extend — same shape as "Rewind"/"Compact from here",
 * sheet-only rows with no gesture twin either.
 *
 * Still not built this pass (flagged, not silently skipped, since the plumbing genuinely doesn't
 * exist yet): "Convert → Task/Incident" (the spec marks this item "(Studio)"), "Read aloud."
 */
@Composable
private fun TurnActionsSheet(
    step: PathView.Step,
    onBranch: () -> Unit,
    onFlag: () -> Unit,
    onDismiss: () -> Unit,
    bookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
    onMarkVersion: () -> Unit = {},
    directive: dev.fonebrew.domain.curation.CompactionDirective? = null,
    onSetFidelity: (dev.fonebrew.domain.curation.Fidelity) -> Unit = {},
    onToggleMustInclude: () -> Unit = {},
    onRewind: () -> Unit = {},
    /** S-new seam: only invoked from a row that is present at all when [RoundtableSlot.isInstalled]
     *  — see this function's own KDoc above. */
    onReRunWithModels: () -> Unit = {},
    onMarkChapter: () -> Unit = {},
    onStartSessionHere: () -> Unit = {},
    /** THREAD_TOPOLOGY_PLAN.md WP2: "Fork from here" — full-fidelity copy into a new,
     *  independent conversation ([dev.fonebrew.ui.ChatViewModel.forkFrom]). */
    onFork: () -> Unit = {},
    /** WP2: "Spawn from here" — condensed bridge-summary new conversation
     *  ([dev.fonebrew.ui.ChatViewModel.spawnFrom]). */
    onSpawn: () -> Unit = {},
    /** THREAD_TOPOLOGY_PLAN.md WP3: "entry from... TurnActionsSheet" — opens the compaction
     *  preview anchored at this turn ([dev.fonebrew.ui.ChatViewModel.openCompactionPreview]). */
    onCompactFromHere: () -> Unit = {},
    /** WP4: tappable parity for the pull-right/release drag ([dev.fonebrew.domain.gesture.MessageDragLogic.Intent.Quote]). */
    onQuote: () -> Unit = {},
    /** WP4: tappable parity for the pull-left/release drag ([dev.fonebrew.domain.gesture.MessageDragLogic.Intent.Reply]). */
    onReply: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    var showRawView by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                step.node.content.take(120),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onToggleBookmark, modifier = Modifier.fillMaxWidth()) {
                Text(if (bookmarked) "Remove bookmark" else "Bookmark")
            }
            TextButton(onClick = onMarkVersion, modifier = Modifier.fillMaxWidth()) {
                Text("Mark branch as Version…")
            }
            // S-new seam (STUDIO_UX_SPEC.md §4.4/§13 S13): mounted directly beneath the manual
            // action it complements, rather than the Studio content's own "above the composer"
            // framing (see VersionSuggestChip's file KDoc, Studio repo, read-only) — this sheet
            // already opens per-turn with the exact branchTipMsgId (this step's node id) the
            // manual action above operates on, so "adjacent to Mark branch as Version…" reads
            // literally here, and it keeps this seam's only core-side touch inside the one file/
            // function this lane already owns rather than the always-visible composer row (a
            // wider, riskier surface a different lane may also be touching). Renders nothing
            // when unentitled ([VersionSuggestSlot.content] null) or when the installed engine
            // itself has no trigger for this tip — see that composable's own early return.
            VersionSuggestSlot.content?.let { installed -> installed(step.node.id) }
            TextButton(onClick = onMarkChapter, modifier = Modifier.fillMaxWidth()) {
                Text("Mark chapter here…")
            }
            TextButton(onClick = onStartSessionHere, modifier = Modifier.fillMaxWidth()) {
                Text("Start fresh session here")
            }
            TextButton(onClick = onCompactFromHere, modifier = Modifier.fillMaxWidth()) {
                Text("Compact conversation from here…")
            }
            Text(
                "Compaction",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (fidelity in dev.fonebrew.domain.curation.Fidelity.entries) {
                    val selected = directive?.fidelity == fidelity
                    TextButton(
                        onClick = { onSetFidelity(fidelity) },
                        modifier = Modifier.semantics { contentDescription = "Fidelity ${fidelity.name}${if (selected) ", selected" else ""}" },
                    ) {
                        Text(
                            fidelity.name,
                            style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                            color = if (selected) LocalHyleColors.current.violet else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Must-include (never dropped)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = directive?.mustInclude == true, onCheckedChange = { onToggleMustInclude() })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onRewind, modifier = Modifier.fillMaxWidth()) {
                Text("Rewind from here")
            }
            // S-new seam (STUDIO_UX_SPEC.md §5.3/§13 S14): present only when a paid layer has
            // installed RoundtableSlot — absent, not a disabled placeholder, in the bare open
            // core (see this function's own KDoc above for the full rationale).
            if (RoundtableSlot.isInstalled) {
                TextButton(onClick = onReRunWithModels, modifier = Modifier.fillMaxWidth()) {
                    Text("Re-run with…")
                }
            }
            TextButton(onClick = onBranch, modifier = Modifier.fillMaxWidth()) {
                Text("Branch from here — try a different route")
            }
            TextButton(onClick = onFork, modifier = Modifier.fillMaxWidth()) {
                Text("Fork from here — new conversation, full history")
            }
            TextButton(onClick = onSpawn, modifier = Modifier.fillMaxWidth()) {
                Text("Spawn from here — new conversation, condensed")
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = onQuote, modifier = Modifier.fillMaxWidth()) {
                Text("Quote in composer")
            }
            TextButton(onClick = onReply, modifier = Modifier.fillMaxWidth()) {
                Text("Reply")
            }
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(step.node.content))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy text")
            }
            // asoc-reachability audit (2026-09-15) item 6: the turn's exact stored content,
            // verbatim, no markdown rendering. Convert→Task-Incident/Read aloud stay named in the
            // KDoc above rather than faked here; they need plumbing (task/incident write paths,
            // TTS) this lane doesn't own. ("Re-run with…" above this row was the third item in
            // that same not-built list — it's wired now, see the KDoc above TurnActionsSheet.)
            TextButton(onClick = { showRawView = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Raw view")
            }
            if (InvocationFeatures.FLAG_OUTPUT_ENABLED && step.node.role == Role.ASSISTANT) {
                TextButton(onClick = onFlag, modifier = Modifier.fillMaxWidth()) {
                    Text("Flag this output…")
                }
            }
        }
    }
    if (showRawView) {
        AlertDialog(
            onDismissRequest = { showRawView = false },
            title = { Text("Raw view") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        step.node.content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showRawView = false }) { Text("Close") } },
        )
    }
}

/**
 * THREAD_TOPOLOGY_PLAN.md WP3's Compaction preview sheet (STUDIO_UX_SPEC.md §5.2): every row's
 * fate is resolved by [dev.fonebrew.domain.curation.CompactionPreviewPresenter] with NO model call —
 * nothing is generated or sent anywhere until [onRun] is tapped, which is
 * [dev.fonebrew.ui.ChatViewModel.runCompaction] actually calling the agent and mechanically
 * verifying F3 before anything is persisted (a violation surfaces as the sheet's sibling loud
 * dialog in [ChatScreen], never silently here).
 */
@Composable
private fun CompactionSheet(
    rows: List<dev.fonebrew.domain.curation.CompactionPreviewPresenter.Row>,
    running: Boolean,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = { if (!running) onDismiss() }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Compaction preview", style = MaterialTheme.typography.titleMedium)
            Text(
                "${rows.size} message(s) — nothing is sent to a model until you run this.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(rows, key = { it.msgId }) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.excerpt,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            row.fate.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (row.resolution.isFailureTombstone) {
                                MaterialTheme.colorScheme.error
                            } else {
                                LocalHyleColors.current.violet
                            },
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !running) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    HyleButton("Run compaction", onClick = onRun)
                }
            }
        }
    }
}

/**
 * THREAD_TOPOLOGY_PLAN.md WP7 — the Instruments panel: the full "what does the model see /
 * how sure was it / what did it cost" view behind the InstrumentsStrip's collapsed summary
 * line. Purely compositional — every card here already exists and was previously unmounted
 * (see the plan's "what already exists" inventory); this dialog is the first caller:
 *
 *  - `BudgetMeter` (`dev.fonebrew.ui.components.StateComponents`) over `assembled.meter` — the
 *    plan's named standalone budget-bar composable, mounted here for the first time anywhere
 *    in the app (repo-wide, it had zero callers before this WP). Shown as its own headline
 *    reading, ahead of [ScopeInspector]'s fuller included/cut ledger (whose own inline bar is
 *    a separate, pre-existing rendering of the same meter and is left as is — out of this
 *    WP's scope to touch that already-shipped component).
 *  - [ScopeInspector] over [assembled] — Doc 03's context-assembly floor ledger
 *    ([dev.fonebrew.domain.scope.ContextAssembly], wired to a real conversation for the first
 *    time by [dev.fonebrew.domain.instrument.InstrumentsAssembly] — see that object's KDoc for
 *    the honest caveat on what "included/cut" means for chat today).
 *  - [InputOutputCard] over [ledgerTotals] — the on-device usage ledger (Doc 07 "Myself"),
 *    folded down to this one conversation instead of the whole-account view.
 *  - [TokenHeatmap] over the last completed turn's per-token entropy, honest about
 *    [tokenAvailability] when the active engine reports no logprobs (every cloud turn today).
 *
 * "Change scope" has nowhere to go yet: chat has no project-scope *picker* UI at all — this
 * WP wires the ledger reader, not a new scope-selection surface — so it is a documented
 * no-op rather than a pre-build of a feature this WP doesn't own.
 */
@Composable
private fun InstrumentsPanel(
    assembled: dev.fonebrew.domain.scope.ContextAssembly.Assembled?,
    tokenScores: List<dev.fonebrew.domain.inspect.TokenScore>,
    tokenAvailability: dev.fonebrew.domain.inspect.Availability,
    ledgerTotals: dev.fonebrew.domain.ledger.LedgerAggregations.Totals,
    /** THREAD_TOPOLOGY_PLAN.md WP8's descriptive "delegated N · kept N · reverted N" card. */
    delegationCounts: dev.fonebrew.domain.thread.DelegationCounts.Counts,
    locale: java.util.Locale,
    /** The user's own display-currency preference (Cost epic, last mile) — see
     *  [dev.fonebrew.domain.cost.CurrencyPref]; never a fetched exchange rate. */
    currencyCode: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(
                Modifier
                    .padding(16.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Instruments",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                if (assembled != null) {
                    dev.fonebrew.ui.components.BudgetMeter(meter = assembled.meter)
                    Spacer(Modifier.height(12.dp))
                    dev.fonebrew.ui.components.ScopeInspector(
                        assembled = assembled,
                        onChangeScope = {}, // see class KDoc: no scope picker exists yet.
                    )
                } else {
                    Text(
                        "No active conversation yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                dev.fonebrew.ui.components.InputOutputCard(ledgerTotals, locale, currencyCode, showCost = true)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text("Per-token confidence — last turn", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                dev.fonebrew.ui.components.TokenHeatmap(
                    cells = dev.fonebrew.domain.inspect.TokenInspector.heatmap(tokenScores),
                    availability = tokenAvailability,
                    summary = dev.fonebrew.domain.inspect.TokenInspector.summary(tokenScores, tokenAvailability),
                )

                // THREAD_TOPOLOGY_PLAN.md WP8: "delegated N · kept N · reverted N" — purely
                // descriptive, no interpretation (binding constraint 3 / CLAUDE.md rule 4).
                // Hidden with nothing delegated yet rather than showing an all-zero row.
                if (delegationCounts.total > 0) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    dev.fonebrew.ui.components.DelegationCountsRow(delegationCounts)
                }
            }
        }
    }
}

/**
 * The streaming assistant turn, coloured per token by confidence (§5a) when the
 * instrument is on. Tokens carrying entropy (local engines) tint from warm
 * (uncertain) to cool (on rails); tokens without it render neutral — the colour
 * never implies confidence the model didn't report.
 */
@Composable
private fun StreamingBubble(
    phase: GenPhase,
    tokens: List<GeneratedToken>,
    entropyColoring: Boolean,
    imageMode: Boolean,
    object3dMode: Boolean = false,
    watched: Boolean = false,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    // Colorblind-safe: a single luminance/opacity ramp toward the existing violet, never a
    // red-to-violet hue lerp (Hyle's hard rule — state is never encoded in hue alone).
    val low = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val high = MaterialTheme.colorScheme.primary
    val showSpinner = phase == GenPhase.LOADING || tokens.isEmpty()

    Row(modifier = Modifier.fillMaxWidth()) {
        Card(
            // Hyle's material language (dev.aarso.hyle.Finish): a watched, from-elsewhere
            // generation is Radiant — it emits its own light, breathing on the "heartbeat, not
            // weather" cycle (dev.aarso.hyle.Pulse.WATCHED); local work is Reflective and stays
            // still. Motion is never the only provenance signal — the "☁"/"⌂" glyph carries it too.
            modifier = if (watched) Modifier.hylePulse() else Modifier,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSpinner) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                when {
                    imageMode ->
                        Text("rendering image… (on-device SD takes a minute)")
                    object3dMode ->
                        Text("generating a 3D object…")
                    phase == GenPhase.LOADING ->
                        Text((if (watched) "☁ " else "⌂ ") + "loading model… (first load can take a while)")
                    tokens.isEmpty() ->
                        Text((if (watched) "☁ " else "⌂ ") + "generating…")
                    else -> {
                        val annotated: AnnotatedString = buildAnnotatedString {
                            for (t in tokens) {
                                val confidence = if (entropyColoring) Confidence.fromEntropy(t.entropy) else null
                                val color = if (confidence == null) neutral else lerp(low, high, confidence)
                                withStyle(SpanStyle(color = color)) { append(t.text) }
                            }
                        }
                        Text(annotated)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Text(
        text = "Start a conversation. Every turn is a node on the tree.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
    )
}

/**
 * The first-run state: no model yet. One recommended download, sized to this
 * device, progress inline; the model auto-activates when it lands and the
 * input enables. Useful in the first minute, no room-hunting.
 */
@Composable
private fun SetupCard(viewModel: ChatViewModel, onOpenModels: () -> Unit) {
    val progress by viewModel.starterDownload.collectAsState()
    val starter = viewModel.starter
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Run a model on this phone", style = MaterialTheme.typography.titleMedium)
            Text(
                "Fonebrew is on-device first: pick a model once and chat privately, offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            val ramGb = "%.0f".format(viewModel.device.totalRamBytes / 1_000_000_000.0)
            if (starter == null) {
                Text(
                    "This device ($ramGb GB RAM) has no comfortable fit in the catalog — " +
                        "try a small custom GGUF from the Models shelf.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                TextButton(onClick = onOpenModels) { Text("Open Models") }
                return@Card
            }
            Text(
                "Recommended for this device ($ramGb GB RAM):",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(starter.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${starter.params} · ${starter.quant} · %.1f GB · ${viewModel.starterFitReason}".format(
                    starter.sizeBytes / 1_000_000_000.0,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val p = progress
            when {
                p == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    HyleButton(
                        "Download (%.1f GB)".format(starter.sizeBytes / 1_000_000_000.0),
                        onClick = { viewModel.downloadStarter() },
                    )
                    TextButton(onClick = onOpenModels, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Choose another")
                    }
                }
                p.error != null -> Column(Modifier.padding(top = 10.dp)) {
                    Text(
                        "download failed: ${p.error}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row {
                        TextButton(onClick = { viewModel.retryStarter() }) { Text("Retry") }
                        TextButton(onClick = onOpenModels) { Text("Choose another") }
                    }
                }
                else -> Column(Modifier.padding(top = 10.dp)) {
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "downloading… ${(p.fraction * 100).toInt()}% — the chat unlocks when it lands",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * One turn: the bubble plus its branch controls. The alternative pager (‹ 1/3 ›)
 * stays inline — it is navigation state at a real fork, not an action — while
 * actions live behind a long-press.
 */
@Composable
private fun MessageTurn(
    step: PathView.Step,
    enabled: Boolean,
    onSwitch: (Int) -> Unit,
    onLongPress: () -> Unit,
    /** Lane G / owner ruling 2026-09-06 (open-ux-decisions.md item G, G1-MODIFIED) — the three
     *  inputs [CostLinePresenter.resolve] needs to decide this turn's inline cost line. Defaults
     *  match the toggle's own default-off shape, so this composable stays backward compatible. */
    showPerTurnCost: Boolean = false,
    costCurrencyCode: String = dev.fonebrew.domain.cost.CurrencyPref.DEFAULT,
    costLocale: java.util.Locale = java.util.Locale.getDefault(),
    highlighted: Boolean = false,
    verdict: dev.fonebrew.domain.curation.Verdict? = null,
    bookmarked: Boolean = false,
    onVerdictUp: () -> Unit = {},
    onVerdictDown: () -> Unit = {},
    onToggleBookmark: () -> Unit = {},
    /** STUDIO_UX_SPEC.md §4.6: this turn's explicit compaction directive (must-include / fidelity
     *  dial), if the curation sheet ever set one — null means "use the default fidelity," same
     *  contract [dev.fonebrew.domain.curation.CompactionDirective] documents. Renders as a small
     *  persistent glyph on the bubble so the state stays visible without reopening the sheet. */
    directive: dev.fonebrew.domain.curation.CompactionDirective? = null,
    /** THREAD_TOPOLOGY_PLAN.md WP5/WP4: true when this step sits on or before a named [Version]'s
     *  tip on the active path — the same fact ThreadRail derives via its own backward pass over
     *  `versionsByTip`, reused here so the verdict-drag ribbon's capture-importance preview agrees
     *  with what ThreadRail shows for this node. */
    isOnVersionSpine: Boolean = false,
    /** THREAD_TOPOLOGY_PLAN.md WP2: non-null for a bridge node (interaction-model switch or
     *  Spawn) — [SummaryNodeCard] replaces the plain [MessageBubble] when set. */
    bridge: SummaryBridge? = null,
    onViewFullPrior: () -> Unit = {},
    /** "Compare alternatives" on the pager row — only ever shown when [PathView.Step.isBranchPoint]. */
    onCompare: () -> Unit = {},
    /** THREAD_TOPOLOGY_PLAN.md WP8: "Choose for me" beside the pager — [dev.fonebrew.ui.ChatViewModel.chooseForMe].
     *  Separate from [enabled] because a choose-for-me call runs a real model turn (`genPhase`),
     *  which [enabled] (driven by the streaming flag) doesn't reflect on its own. */
    onChooseForMe: () -> Unit = {},
    chooseForMeEnabled: Boolean = enabled,
    /** THREAD_TOPOLOGY_PLAN.md WP4: message drag gestures — see [MessageBubble]'s own KDoc for
     *  what each one does; all default to the toggles-off/no-op shape so every other MessageTurn
     *  caller (there are none today, but the parity with MessageBubble's own defaults matters)
     *  keeps compiling unchanged. */
    gestureToggles: MessageGestureToggles = MessageGestureToggles(),
    onVerdictDragCommit: (Int) -> Unit = {},
    onReplyDrag: () -> Unit = {},
    onQuoteDrag: () -> Unit = {},
    onBranchDrag: () -> Unit = {},
    onForkDrag: () -> Unit = {},
    onSpawnDrag: () -> Unit = {},
    /** docs/design/objects-3d.md §1: tapping an object3d node opens ObjectViewerRoom — the
     *  callback carries the node's (relativePath, format) metadata straight through. */
    onOpenObject3d: (relativePath: String, format: String) -> Unit = { _, _ -> },
) {
    val fromUser = step.node.role == Role.USER
    // costMinor is only ever recorded for a watched-cloud turn that reported usage — the single
    // provenance signal, reused below for both the header glyph and CostLinePresenter's decision
    // rather than adding a second source of truth for the same fact.
    val watchedTurn = step.node.metadata["costMinor"] != null
    Column(modifier = Modifier.fillMaxWidth()) {
        if (bridge != null) {
            SummaryNodeCard(bridge = bridge, onViewFullPrior = onViewFullPrior)
        } else {
            MessageBubble(
                role = step.node.role,
                content = step.node.content,
                imagePath = step.node.metadata[Conversations.IMAGE_KEY],
                // W1: user-node photo attachments — a different metadata key and visual slot from
                // the assistant-generated-image branch above (they're never both present on one node).
                attachments = Attachments.decode(step.node.metadata[Conversations.ATTACHMENTS_KEY]),
                object3dPath = step.node.metadata[dev.fonebrew.data.Object3dNodeMeta.KEY_FILE],
                object3dFormat = step.node.metadata[dev.fonebrew.data.Object3dNodeMeta.KEY_FORMAT],
                onOpenObject3d = {
                    val path = step.node.metadata[dev.fonebrew.data.Object3dNodeMeta.KEY_FILE]
                    val format = step.node.metadata[dev.fonebrew.data.Object3dNodeMeta.KEY_FORMAT]
                    if (path != null && format != null) onOpenObject3d(path, format)
                },
                stopped = step.node.metadata["stopped"] == "true",
                // costMinor is only ever recorded for a watched-cloud turn that reported usage
                // (LedgerComponents.kt) — reuse it as the provenance signal rather than adding a
                // second source of truth for the same fact.
                watched = watchedTurn,
                // Lane G / owner ruling 2026-09-06 (G1-MODIFIED): CostLinePresenter is the one
                // place the toggle x provenance x metadata-presence decision gets made — this
                // composable just renders whatever comes back, never re-derives it.
                costLineText = CostLinePresenter.resolve(
                    showPerTurnCost = showPerTurnCost,
                    provenance = if (watchedTurn) TurnProvenance.CLOUD else TurnProvenance.ON_DEVICE,
                    metadata = TurnCostMetadata.from(
                        step.node.metadata["costMinor"],
                        step.node.metadata["tokensIn"],
                        step.node.metadata["tokensOut"],
                    ),
                    currencyCode = costCurrencyCode,
                    locale = costLocale,
                ),
                // W2: web-search provenance — "webSearch" records the model was allowed to search
                // this turn (the watched-object fact) independent of whether any source came back;
                // "sources" is only ever non-empty when the provider's tool actually returned one.
                webSearch = step.node.metadata[Conversations.WEB_SEARCH_KEY] == "true",
                searchPaused = step.node.metadata[Conversations.SEARCH_PAUSED_KEY] == "true",
                sources = Sources.decode(step.node.metadata[Conversations.SOURCES_KEY]),
                onLongPress = onLongPress,
                highlighted = highlighted,
                verdict = verdict,
                bookmarked = bookmarked,
                directive = directive,
                isOnVersionSpine = isOnVersionSpine,
                onVerdictUp = onVerdictUp,
                onVerdictDown = onVerdictDown,
                onToggleBookmark = onToggleBookmark,
                gestureToggles = gestureToggles,
                onVerdictDragCommit = onVerdictDragCommit,
                onReplyDrag = onReplyDrag,
                onQuoteDrag = onQuoteDrag,
                onBranchDrag = onBranchDrag,
                onForkDrag = onForkDrag,
                onSpawnDrag = onSpawnDrag,
            )
        }
        if (step.isBranchPoint) {
            // Fixed per audit: five controls (‹, n/m, ›, "Compare alternatives", "Choose for me")
            // in one fixed-width Row clip/overflow on a phone-width screen — the same
            // horizontalScroll fix applied to every other packed control row in this file.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onSwitch(-1) }, enabled = enabled) { Text("‹") }
                Text(
                    "${step.activeAlternative}/${step.alternativeCount}",
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = { onSwitch(+1) }, enabled = enabled) { Text("›") }
                TextButton(onClick = onCompare, enabled = enabled) {
                    Text("Compare alternatives", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onChooseForMe, enabled = chooseForMeEnabled) {
                    Text("Choose for me", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** The four verdict detents' human labels (STUDIO_UX_SPEC.md §4.2) — shared by the chevron row's
 *  inline label and, since THREAD_TOPOLOGY_PLAN.md WP4, the verdict-drag commit's snackbar. */
private fun verdictLabel(grade: Int): String = when (grade) {
    2 -> "reference-grade"
    1 -> "useful"
    -1 -> "off"
    -2 -> "wrong"
    else -> "cleared"
}

/** One council voice (§4b): agent label, its answer, and a "continue with this". */
@Composable
private fun CouncilCardView(card: CouncilCard, enabled: Boolean, onContinue: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "☷ ${card.agent}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                card.text.ifBlank { if (card.done) "(empty)" else "…" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (card.done && card.nodeId != null) {
                TextButton(onClick = onContinue, enabled = enabled) { Text("Continue with this ▸") }
            }
        }
    }
}

/** docs/design/objects-3d.md §1/§3: the in-bubble affordance for a 3D-object turn — no inline
 *  WebGL render inside the message list (that's what the locked-down ObjectViewerRoom is for),
 *  just an honest label naming the format and a button to open it. */
@Composable
private fun Object3dTurnCard(format: String?, onOpen: () -> Unit) {
    Column {
        Text(
            "🧊 3D object" + (format?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onOpen, modifier = Modifier.padding(top = 4.dp)) {
            Text("Open viewer ▸")
        }
    }
}

@Composable
private fun MessageBubble(
    role: Role,
    content: String,
    imagePath: String?,
    /** W1: user-node photo attachments — a different metadata key and slot from the assistant-
     *  generated-image branch ([imagePath]); the two are never both present on one node. */
    attachments: List<Attachments.Attachment> = emptyList(),
    stopped: Boolean,
    /** costMinor is only ever recorded for a watched-cloud turn that reported usage — reused as
     *  the provenance signal for the "☁"/"⌂" header glyph and Hyle's Radiant/Reflective pulse. */
    watched: Boolean = false,
    /** Lane G / owner ruling 2026-09-06 (open-ux-decisions.md item G, G1-MODIFIED): the exact
     *  line to render, already resolved by [CostLinePresenter.resolve] (toggle state x
     *  provenance x metadata presence) — `null` renders nothing. This composable never sees the
     *  raw costMinor/tokensIn/tokensOut strings or the Settings toggle itself; it only renders
     *  what the presenter decided, which is what keeps it thin. */
    costLineText: String? = null,
    // W2: web search provenance. webSearch = the model was allowed to search this turn (the
    // watched-object fact, recorded regardless of whether it actually searched); sources = the
    // results the provider's tool actually surfaced, if any; searchPaused = the server-side
    // search loop hit its round cap mid-turn.
    webSearch: Boolean = false,
    searchPaused: Boolean = false,
    sources: List<Source> = emptyList(),
    onLongPress: () -> Unit,
    highlighted: Boolean = false,
    /** docs/design/objects-3d.md §1/§8 — non-null exactly on an object3d turn ([object3dFormat]
     *  is the sibling `object3d.format` value: an [dev.fonebrew.domain.object3d.Object3dFormat]
     *  name, or [dev.fonebrew.data.Object3dNodeMeta.DSL_JSON_FORMAT] for an on-device scene).
     *  [onOpenObject3d] opens the viewer; a no-op default keeps every other call site compiling. */
    object3dPath: String? = null,
    object3dFormat: String? = null,
    onOpenObject3d: () -> Unit = {},
    /** Curation Instrument additions (STUDIO_UX_SPEC.md §4.2/§4.3) — all optional/no-op by
     *  default so every other [MessageBubble] call site (StreamingBubble etc. don't call this
     *  composable, but any future one) keeps compiling unchanged. */
    verdict: dev.fonebrew.domain.curation.Verdict? = null,
    bookmarked: Boolean = false,
    onVerdictUp: () -> Unit = {},
    onVerdictDown: () -> Unit = {},
    onToggleBookmark: () -> Unit = {},
    /** STUDIO_UX_SPEC.md §4.6 — see [MessageTurn]'s own KDoc for this param; null (the default)
     *  renders no glyph, same as every turn before this WP had. */
    directive: dev.fonebrew.domain.curation.CompactionDirective? = null,
    /** See [MessageTurn]'s own KDoc — feeds [VerdictDragRibbon]'s capture-importance preview. */
    isOnVersionSpine: Boolean = false,
    /**
     * THREAD_TOPOLOGY_PLAN.md WP4 — the drag gestures this bubble now arbitrates via
     * `Modifier.messageGestures` (`MessageGestures.kt`), on top of the plain tap controls above:
     * hold 150ms then pull up/down = the verdict ribbon (annotation only — patent design-around,
     * see [dev.fonebrew.domain.gesture.MessageDragLogic]'s KDoc); pull left = reply-quote into the
     * composer; pull right + release = quote into the composer; pull right + hold ≥400ms = the
     * Branch/Fork/Spawn radial fan (Owner decision 1); 500ms stationary = [onLongPress] (same
     * TurnActionsSheet the plain long-press already opens); double-tap = [onToggleBookmark] (same
     * as before — now routed through the same classifier instead of `combinedClickable`). All
     * default to no-ops so this composable's shape is backward compatible.
     */
    gestureToggles: MessageGestureToggles = MessageGestureToggles(),
    onVerdictDragCommit: (Int) -> Unit = {},
    onReplyDrag: () -> Unit = {},
    onQuoteDrag: () -> Unit = {},
    onBranchDrag: () -> Unit = {},
    onForkDrag: () -> Unit = {},
    onSpawnDrag: () -> Unit = {},
) {
    val fromUser = role == Role.USER
    val haptics = rememberHyleHaptics()
    var dragPreviewGrade by remember { mutableStateOf<Int?>(null) }
    var draggingVertical by remember { mutableStateOf(false) }
    var horizontalHint by remember { mutableStateOf<HorizontalDragDirection?>(null) }
    var radialAnchor by remember { mutableStateOf<Offset?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
            // Wraps just the Card (not the whole turn) so the radial fan's anchor Offset — which
            // messageGestures reports relative to THIS Box's own pointerInput node — lines up with
            // where HyleRadialMenu draws it, with no cross-composable coordinate conversion.
            Box {
                Card(
                    modifier = Modifier
                        .messageGestures(
                            enabled = true,
                            toggles = gestureToggles,
                            haptics = haptics,
                            callbacks = MessageGestureCallbacks(
                                // Fixed per audit: [enabled] gates the whole gesture channel (it
                                // has to, to keep reply/quote/radial/long-press role-agnostic per
                                // this file's own KDoc above), so the verdict-drag callbacks need
                                // their own per-role no-op here — a user's own message has no
                                // tappable verdict equivalent (VerdictBookmarkRow's showVerdict
                                // below is already assistant-only), so a vertical drag on one must
                                // not be able to commit a Verdict either.
                                onVerdictPreview = { grade ->
                                    if (role == Role.ASSISTANT) {
                                        draggingVertical = true
                                        dragPreviewGrade = grade
                                    }
                                },
                                onCommitVerdict = { grade ->
                                    if (role == Role.ASSISTANT) {
                                        draggingVertical = false
                                        dragPreviewGrade = null
                                        onVerdictDragCommit(grade)
                                    }
                                },
                                onReply = {
                                    horizontalHint = null
                                    onReplyDrag()
                                },
                                onQuote = {
                                    horizontalHint = null
                                    onQuoteDrag()
                                },
                                onHorizontalPreview = { direction -> horizontalHint = direction },
                                onOpenRadial = { anchor -> radialAnchor = anchor },
                                onLongPress = onLongPress,
                                onDoubleTap = onToggleBookmark,
                                onGestureEnded = {
                                    draggingVertical = false
                                    dragPreviewGrade = null
                                    horizontalHint = null
                                },
                            ),
                        )
                        .semantics {
                            // Binding constraint 4 / STUDIO_UX_SPEC.md §4: every gesture ships a
                            // TalkBack custom action with the same name as its tappable control.
                            customActions = buildList {
                                if (role == Role.ASSISTANT) {
                                    add(CustomAccessibilityAction("Rate up") { onVerdictUp(); true })
                                    add(CustomAccessibilityAction("Rate down") { onVerdictDown(); true })
                                }
                                add(CustomAccessibilityAction(if (bookmarked) "Remove bookmark" else "Bookmark message") { onToggleBookmark(); true })
                                // STUDIO_UX_SPEC.md §4.6: "TalkBack exposes each [directive glyph]
                                // as a named action" — both open the same TurnActionsSheet the
                                // corner glyph itself has no tap target of its own, mirroring how
                                // "Open message actions" below already does the same for long-press.
                                if (directive?.mustInclude == true) {
                                    add(CustomAccessibilityAction("Must-include: open to change") { onLongPress(); true })
                                }
                                if (directive != null) {
                                    add(CustomAccessibilityAction("Fidelity ${directive.fidelity.name}: open to change") { onLongPress(); true })
                                }
                                add(CustomAccessibilityAction("Reply") { onReplyDrag(); true })
                                add(CustomAccessibilityAction("Quote in composer") { onQuoteDrag(); true })
                                add(CustomAccessibilityAction("Branch from here") { onBranchDrag(); true })
                                add(CustomAccessibilityAction("Fork from here") { onForkDrag(); true })
                                add(CustomAccessibilityAction("Spawn from here") { onSpawnDrag(); true })
                                add(CustomAccessibilityAction("Open message actions") { onLongPress(); true })
                            }
                        }
                        .let {
                            // S9: the current find-in-chat hit's row (row-level marker — see InChatFind.kt's
                            // KDoc for why not a sub-string highlight).
                            if (highlighted) {
                                it.border(2.dp, LocalHyleColors.current.cyan, MaterialTheme.shapes.medium)
                            } else {
                                it.border(1.dp, LocalHyleColors.current.hairline, MaterialTheme.shapes.medium)
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (fromUser) {
                            LocalHyleColors.current.violetDim
                        } else {
                            LocalHyleColors.current.raised
                        },
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        // A turn reads as a **file**: a small header line (who wrote it, and —
                        // for a watched-cloud turn — the "☁" glyph, plus "⌕" when web search was
                        // allowed that turn (W2), never colour alone, per Hyle's provenance rule),
                        // the body, then a metadata footer below a hairline divider.
                        val c = LocalHyleColors.current
                        val headerLabel = when {
                            fromUser -> "You"
                            role == Role.SYSTEM -> "System"
                            watched -> "☁ Assistant · watched" + if (webSearch) " ⌕" else ""
                            else -> "⌂ Assistant" + if (webSearch) " ⌕" else ""
                        }
                        Text(headerLabel, style = MaterialTheme.typography.labelSmall, color = c.textMid)
                        Spacer(Modifier.height(4.dp))
                        // W1: user-node photo attachments render as a thumbnail row above the
                        // text — a different metadata key and slot from the assistant-generated-
                        // image branch below.
                        if (attachments.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(attachments) { a ->
                                    FileImage(
                                        path = a.path,
                                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)),
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        when {
                            // An image turn: the node's payload is the generated file (§6).
                            imagePath != null -> FileImage(
                                path = imagePath,
                                modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 320.dp),
                            )
                            // A 3D-object turn (docs/design/objects-3d.md §1/§8): no inline
                            // render (WebGL needs the locked-down ObjectViewerRoom, §3) — a
                            // tappable card opens it.
                            object3dPath != null -> Object3dTurnCard(format = object3dFormat, onOpen = onOpenObject3d)
                            fromUser || role == Role.SYSTEM -> Text(content)
                            // Persisted model turns render as markdown (legibility); the
                            // live stream keeps per-token entropy colouring instead. We run
                            // the text through the JVM-tested StreamingMarkdown.reconcile so a
                            // turn that was stopped mid-fence (a dangling ``` that would swallow
                            // the rest of the bubble) renders cleanly; it's idempotent on
                            // well-formed markdown, so a complete turn passes through unchanged.
                            else -> Markdown(content = StreamingMarkdown.reconcile(content).text)
                        }
                        if (stopped || costLineText != null || sources.isNotEmpty() || searchPaused) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = c.hairline)
                            // Cost (G1-MODIFIED, Lane G): a small, subdued per-turn line — only
                            // ever present when CostLinePresenter.resolve found the Settings
                            // toggle on, a CLOUD turn, and recorded metadata all at once. Quiet
                            // by design (label-typography only, no hue-only semantics — the owner
                            // is red-green colorblind): this reads as a footnote, not a badge.
                            if (costLineText != null) {
                                Text(
                                    costLineText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.textMid,
                                )
                            }
                            if (stopped) {
                                Text("· stopped here", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            // W2: sources footer — each row opens the link via ACTION_VIEW;
                            // runCatching covers the no-app-can-handle-this case (e.g. a device
                            // with no browser), surfacing a toast on that failure so the tap
                            // doesn't silently do nothing.
                            if (sources.isNotEmpty()) {
                                val context = LocalContext.current
                                for (source in sources) {
                                    Text(
                                        "⌕ ${source.title}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                                                }.onFailure {
                                                    Toast.makeText(context, "No app to open this link", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            .padding(vertical = 2.dp),
                                    )
                                }
                            }
                            // v1, no auto-resume: just the visible note (plan §Capturing sources).
                            if (searchPaused) {
                                Text(
                                    "search paused — the server hit its round limit; type \"continue\" to resume",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
                // Fixed per audit: STUDIO_UX_SPEC.md §4.6's compaction-directive glyph — a pin for
                // Must-include plus 0-3 fidelity dots — used to exist only inside TurnActionsSheet;
                // this is its persistent, always-visible trace on the bubble itself.
                if (directive != null) {
                    CompactionDirectiveGlyph(
                        directive = directive,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    )
                }
                // Owner decision 1 / plan's "Gesture arbitration": pull right + hold ≥400ms fans
                // Branch/Fork/Spawn at the drag's anchor point. Glyphs are plain DrawScope
                // primitives (no icon asset needed for three items); HyleHaptics.tap() on each
                // pick is HyleRadialMenu's own built-in feedback, not duplicated here.
                radialAnchor?.let { anchor ->
                    HyleRadialMenu(
                        visible = true,
                        anchor = anchor,
                        items = listOf(
                            HyleRadialMenuItem(
                                label = "Branch",
                                glyph = { tint -> drawLine(tint, center.copy(y = 0f), center, strokeWidth = 3f) },
                                onClick = onBranchDrag,
                            ),
                            HyleRadialMenuItem(
                                label = "Fork",
                                glyph = { tint -> drawCircle(tint, radius = size.minDimension / 3f) },
                                onClick = onForkDrag,
                            ),
                            HyleRadialMenuItem(
                                label = "Spawn",
                                glyph = { tint -> drawRect(tint, topLeft = center * 0.4f, size = size * 0.5f) },
                                onClick = onSpawnDrag,
                            ),
                        ),
                        onDismiss = { radialAnchor = null },
                    )
                }
            }
            // The judgment ribbon while a vertical drag is live (§4.2), and a lighter hint for the
            // horizontal reply/quote channel — both purely additive to the tappable row below,
            // never a replacement for it.
            if (draggingVertical) {
                VerdictDragRibbon(
                    visible = true,
                    grade = dragPreviewGrade,
                    bookmarked = bookmarked,
                    // Fixed per audit: was hardcoded false ("not plumbed to the bubble in this
                    // WP") — now the caller-computed fact (see [MessageTurn]'s KDoc for how it's
                    // derived), so the "would keep: …" preview agrees with ThreadRail.
                    isOnVersionSpine = isOnVersionSpine,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            HorizontalDragHint(direction = horizontalHint, modifier = Modifier.padding(top = 4.dp))
            // Verdict + bookmark row (§4.2/§4.3 Regular-mode controls — the parity-gate
            // requirement: every gesture ships with a tappable equivalent in the same PR).
            // Fixed per adversarial review: the double-tap-to-bookmark gesture above is wired
            // unconditionally on every role's Card, but this row used to render only for
            // Role.ASSISTANT — a user/system message could be bookmarked with no visible pin and
            // no way to un-bookmark it short of the same blind double-tap again. Verdict
            // (useful/wrong) stays assistant-only (rating a user's own message is meaningless),
            // but bookmarking is role-agnostic, so the row always shows, with verdict controls
            // gated inside it instead of gating the whole row.
            VerdictBookmarkRow(
                verdict = verdict,
                bookmarked = bookmarked,
                showVerdict = role == Role.ASSISTANT,
                onVerdictUp = onVerdictUp,
                onVerdictDown = onVerdictDown,
                onToggleBookmark = onToggleBookmark,
            )
        }
    }
}

/**
 * STUDIO_UX_SPEC.md §4.6: "Compaction directives show as tiny edge glyphs on the bubble (pin
 * glyph for must-include; 0–3 fidelity dots)." A pin when [CompactionDirective.mustInclude], plus
 * a 3-dot ladder mirroring TurnActionsSheet's own F0..F3 fidelity dial — so an explicit directive
 * stays visible on the thread itself, not just inside the sheet that set it.
 */
@Composable
private fun CompactionDirectiveGlyph(
    directive: dev.fonebrew.domain.curation.CompactionDirective,
    modifier: Modifier = Modifier,
) {
    val c = LocalHyleColors.current
    val filledDots = when (directive.fidelity) {
        dev.fonebrew.domain.curation.Fidelity.F0 -> 0
        dev.fonebrew.domain.curation.Fidelity.F1 -> 1
        dev.fonebrew.domain.curation.Fidelity.F2 -> 2
        dev.fonebrew.domain.curation.Fidelity.F3 -> 3
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.raised.copy(alpha = 0.92f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (directive.mustInclude) {
            Text(
                "📌",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 3.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (i < filledDots) c.violet else c.hairline),
                )
            }
        }
    }
}

/**
 * Regular-mode verdict + bookmark controls (STUDIO_UX_SPEC.md §4.2: "chevron buttons in the
 * message overflow row"; §4.3 double-tap's tap equivalent). Colourblind-safe by construction
 * (I3): up/down state is shape (filled vs outlined chevron) + a text label on selection, never
 * hue alone — [MaterialTheme.colorScheme.primary]/[LocalHyleColors.current.violet] fill the
 * *selected* glyph, but the outline-vs-filled distinction carries the meaning even without colour.
 */
@Composable
private fun VerdictBookmarkRow(
    verdict: dev.fonebrew.domain.curation.Verdict?,
    bookmarked: Boolean,
    onVerdictUp: () -> Unit,
    onVerdictDown: () -> Unit,
    onToggleBookmark: () -> Unit,
    showVerdict: Boolean = true,
) {
    val grade = verdict?.grade
    Row(
        modifier = Modifier.padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showVerdict) {
            TextButton(
                onClick = onVerdictDown,
                modifier = Modifier.semantics {
                    contentDescription = if (grade != null && grade < 0) "Rated down. Tap to clear." else "Rate down"
                },
            ) {
                Text(
                    if (grade != null && grade < 0) "▼" else "▽",
                    color = if (grade != null && grade < 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                )
            }
            TextButton(
                onClick = onVerdictUp,
                modifier = Modifier.semantics {
                    contentDescription = if (grade != null && grade > 0) "Rated up. Tap to clear." else "Rate up"
                },
            ) {
                Text(
                    if (grade != null && grade > 0) "▲" else "△",
                    color = if (grade != null && grade > 0) LocalHyleColors.current.violet else MaterialTheme.colorScheme.outline,
                )
            }
            if (grade != null) {
                Text(
                    when (grade) {
                        2 -> "reference-grade"
                        1 -> "useful"
                        -1 -> "off"
                        -2 -> "wrong"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        TextButton(
            onClick = onToggleBookmark,
            modifier = Modifier.semantics {
                contentDescription = if (bookmarked) "Bookmarked. Tap to remove." else "Bookmark message"
            },
        ) {
            Text(if (bookmarked) "📌" else "📍", color = if (bookmarked) LocalHyleColors.current.violet else MaterialTheme.colorScheme.outline)
        }
    }
}
