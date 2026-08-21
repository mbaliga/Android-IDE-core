@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.fonebrew.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import dev.fonebrew.domain.GeneratedToken
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.bridge.BridgeCodec
import dev.fonebrew.domain.bridge.SummaryBridge
import dev.fonebrew.domain.gesture.ComposerQuote
import dev.fonebrew.domain.instrument.Confidence
import dev.fonebrew.domain.prompt.LintSeverity
import dev.fonebrew.domain.prompt.PromptLinter
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.domain.tree.PathView
import dev.fonebrew.flavor.InvocationFeatures
import dev.aarso.hyle.cells.HyleRadialMenu
import dev.aarso.hyle.cells.HyleRadialMenuItem
import dev.aarso.hyle.cells.rememberHyleHaptics
import dev.fonebrew.ui.components.SummaryNodeCard
import dev.fonebrew.ui.hyle.HyleButton
import dev.fonebrew.ui.hyle.HyleChip
import dev.fonebrew.ui.hyle.HyleField
import dev.fonebrew.ui.hyle.HeaderGlyph
import dev.fonebrew.ui.hyle.HyleHeaderButton
import dev.fonebrew.ui.hyle.FileImage
import dev.fonebrew.ui.search.InChatFindBar
import dev.fonebrew.ui.search.InChatFindPresenter
import dev.fonebrew.ui.theme.LocalHyleColors
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
    /** S9 continuity: text carried in from an app-wide search result. Opens the find bar
     *  pre-filled and scrolls to the first hit. Consumed once, via [onFindRequestConsumed], so
     *  reopening the bar later doesn't resurrect a stale query. */
    findRequest: String? = null,
    onFindRequestConsumed: () -> Unit = {},
    /** Reverse continuity: promote what's in the find bar to the app-wide search overlay. */
    onSearchAllChats: (String) -> Unit = {},
    /** THREAD_TOPOLOGY_PLAN.md WP5: ThreadRail is hidden whenever the spatial shell isn't at home
     *  (`controller.atHome` — avoids colliding with an open room's own edge peek / parked card).
     *  Defaults true so ChatScreen stays usable stand-alone (previews, tests) without a controller. */
    showThreadRail: Boolean = true,
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
    var input by remember { mutableStateOf("") }
    var showModelSheet by remember { mutableStateOf(false) }
    var showPlus by remember { mutableStateOf(false) }
    // docs/design/objects-3d.md §1: the "Generate 3D…" mini-chooser (On-device / Cloud·watched).
    var showObject3dChooser by remember { mutableStateOf(false) }
    // The object3d node currently opened in the viewer — (relativePath, format) from the tapped
    // node's metadata; the loaded content/error is resolved async below (file I/O off the tap).
    var object3dViewerTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var object3dViewerContent by remember { mutableStateOf<dev.fonebrew.ui.object3d.ObjectViewerContent?>(null) }
    var object3dViewerError by remember { mutableStateOf<String?>(null) }
    var showParticipants by remember { mutableStateOf(false) }
    var showMe by remember { mutableStateOf(false) }
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

    // §7: text shared in / selected elsewhere arrives here — prefill the input.
    val intake by viewModel.intake.collectAsState()
    LaunchedEffect(intake) {
        intake?.text?.let { input = it; viewModel.consumeIntake() }
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
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            HomeHeader(
                state = state,
                onBadgeTap = { if (!state.isGenerating) showModelSheet = true },
                onOpenChats = onOpenChats,
                onOpenSettings = onOpenSettings,
                onOpenMe = { showMe = true },
            )
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
                                            TextButton(onClick = { connectDismissed = true }) { Text("Not now") }
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
                if (showThreadRail && !railView.isEmpty) {
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

            ComposerModeRow(
                mode = state.composerMode,
                enabled = state.genPhase == GenPhase.IDLE,
                onMode = viewModel::requestComposerMode,
            )
            // Personas council = a group of experts you manage like a group chat (IA §B4).
            if (state.composerMode == ComposerMode.PERSONAS) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    TextButton(onClick = { showParticipants = true }) { Text("Participants") }
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
                if (!generationMode) {
                    TextButton(
                        onClick = { viewModel.refinePrompt(input) },
                        enabled = input.isNotBlank() && !state.rewriting &&
                            state.genPhase == GenPhase.IDLE && state.engineAvailable,
                    ) {
                        Text(if (state.rewriting) "…" else "Refine")
                    }
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
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
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
                dev.fonebrew.ui.rooms.ParticipantsScreen(onClose = { showParticipants = false })
            }
        }
    }

    if (showMe) {
        Dialog(
            onDismissRequest = { showMe = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                dev.fonebrew.ui.rooms.MeScreen(onClose = { showMe = false })
            }
        }
    }

    // Interaction model is locked once a chat starts (IA §B4): changing it branches with a summary.
    val pendingMode by viewModel.pendingInteractionChange.collectAsState()
    pendingMode?.let { mode ->
        val label = when (mode) {
            ComposerMode.MODELS -> "Council · models"
            ComposerMode.PERSONAS -> "Council · personas"
            else -> "Single"
        }
        AlertDialog(
            onDismissRequest = { viewModel.cancelInteractionChange() },
            title = { Text("Switch to $label?") },
            text = {
                Text(
                    "The interaction model is locked once a conversation starts. Switching " +
                        "starts a new branch and summarizes everything so far into it — your " +
                        "current thread stays intact on the tree.",
                )
            },
            confirmButton = { HyleButton("Branch & switch", onClick = { viewModel.confirmInteractionChange() }) },
            dismissButton = { TextButton(onClick = { viewModel.cancelInteractionChange() }) { Text("Cancel") } },
        )
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
        InstrumentsPanel(
            assembled = state.instrumentsAssembly,
            tokenScores = tokenScores,
            tokenAvailability = tokenAvailability,
            ledgerTotals = ledgerTotals,
            delegationCounts = state.delegationCounts,
            locale = java.util.Locale.getDefault(),
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
 * The one piece of chrome the home room keeps: the conversation title (quiet,
 * left) and the model badge (right) — a dropdown affordance, cloud explicitly
 * watched (binding rule 2).
 */
@Composable
private fun HomeHeader(
    state: ChatUiState,
    onBadgeTap: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMe: () -> Unit = {},
) {
    val c = LocalHyleColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Left: the room parked off the left edge (Conversations).
        HyleHeaderButton(
            glyph = HeaderGlyph.ROOM_LEFT,
            onClick = onOpenChats,
            contentDescription = "Open conversations",
            slantLeft = false,
        )
        // Centre: the conversation's own title, given the weight the mockups give it —
        // this is the one thing naming where you are, so it reads as a title rather than
        // the muted caption it used to be.
        Text(
            state.steps.firstOrNull { it.node.role == Role.USER }
                ?.node?.content?.lineSequence()?.firstOrNull()?.take(36)
                ?: "New chat",
            style = MaterialTheme.typography.titleMedium,
            color = c.textHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        // Right: Settings. (The mockups carry no header avatar, so the Me · Myself · I
        // entry point is not duplicated here — it stays reachable from Settings, which
        // already opens MeScreen.)
        HyleHeaderButton(
            glyph = HeaderGlyph.SETTINGS,
            onClick = onOpenSettings,
            contentDescription = "Open settings",
            slantLeft = true,
        )
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
            Switch(checked = entropyColoring, onCheckedChange = onEntropyColoring)
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
 * Image and 3D generation are wired; video / photo-attach / file-attach are honest "soon" rows
 * (rule 6 — never claim a capability that isn't there). They map onto the provider types in
 * Settings.
 */
@Composable
private fun PlusSheet(
    onGenerateImage: () -> Unit,
    onGenerateObject3d: () -> Unit,
    onImportObject3dFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Create", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlusRow("🖼", "Image", "Generate & edit — on-device or watched cloud", enabled = true, onClick = onGenerateImage)
                PlusRow("🎬", "Video", "Soon — no engine wired yet", enabled = false) {}
                PlusRow("🧊", "Generate 3D…", "On-device or watched cloud — you pick", enabled = true, onClick = onGenerateObject3d)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Attach", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlusRow("🖼", "Photo", "Soon — multimodal input not wired yet", enabled = false) {}
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

@Composable
private fun ComposerModeRow(mode: ComposerMode, enabled: Boolean, onMode: (ComposerMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The interaction model (IA §B4): one model, a council of personas, or a council of
        // different models. Image/video/3D are NOT modes here — they live behind the composer's
        // "+" (Gemini-style, IA §B5). ("Council", not "MoE/Mixture of Experts" — binding rule 3.)
        HyleChip(mode == ComposerMode.SINGLE, { onMode(ComposerMode.SINGLE) }, "Single", enabled = enabled)
        HyleChip(mode == ComposerMode.PERSONAS, { onMode(ComposerMode.PERSONAS) }, "Council · personas", enabled = enabled)
        HyleChip(mode == ComposerMode.MODELS, { onMode(ComposerMode.MODELS) }, "Council · models", enabled = enabled)
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
 * parity for the pull-left/pull-right drags ([onReply]/[onQuote]). Not built this pass (flagged,
 * not silently skipped): "Re-run with…" (needs Roundtable, PC-B, not built), "Convert →
 * Task/Incident" (the spec marks this item "(Studio)"), "Read aloud," "Raw view" (no L4 view
 * exists yet).
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
            if (InvocationFeatures.FLAG_OUTPUT_ENABLED && step.node.role == Role.ASSISTANT) {
                TextButton(onClick = onFlag, modifier = Modifier.fillMaxWidth()) {
                    Text("Flag this output…")
                }
            }
        }
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

                dev.fonebrew.ui.components.InputOutputCard(ledgerTotals, locale, "USD", showCost = false)
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
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val uncertain = MaterialTheme.colorScheme.error
    val onRails = MaterialTheme.colorScheme.primary
    val showSpinner = phase == GenPhase.LOADING || tokens.isEmpty()

    Row(modifier = Modifier.fillMaxWidth()) {
        Card(
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
                        Text("loading model… (first load can take a while)")
                    tokens.isEmpty() ->
                        Text("generating…")
                    else -> {
                        val annotated: AnnotatedString = buildAnnotatedString {
                            for (t in tokens) {
                                val confidence = if (entropyColoring) Confidence.fromEntropy(t.entropy) else null
                                val color = if (confidence == null) neutral else lerp(uncertain, onRails, confidence)
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
    Column(modifier = Modifier.fillMaxWidth()) {
        if (bridge != null) {
            SummaryNodeCard(bridge = bridge, onViewFullPrior = onViewFullPrior)
        } else {
            MessageBubble(
                role = step.node.role,
                content = step.node.content,
                imagePath = step.node.metadata[Conversations.IMAGE_KEY],
                object3dPath = step.node.metadata[dev.fonebrew.data.Object3dNodeMeta.KEY_FILE],
                object3dFormat = step.node.metadata[dev.fonebrew.data.Object3dNodeMeta.KEY_FORMAT],
                onOpenObject3d = {
                    val path = step.node.metadata[dev.fonebrew.data.Object3dNodeMeta.KEY_FILE]
                    val format = step.node.metadata[dev.fonebrew.data.Object3dNodeMeta.KEY_FORMAT]
                    if (path != null && format != null) onOpenObject3d(path, format)
                },
                stopped = step.node.metadata["stopped"] == "true",
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
        // Cost (G1): a small per-turn line for watched-cloud turns that reported usage.
        step.node.metadata["costMinor"]?.let { minor ->
            val tin = step.node.metadata["tokensIn"] ?: "?"
            val tout = step.node.metadata["tokensOut"] ?: "?"
            Text(
                costLine(minor, tin, tout),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            )
        }
        if (step.isBranchPoint) {
            // Fixed per audit: five controls (‹, n/m, ›, "Compare alternatives", "Choose for me")
            // in one fixed-width Row clip/overflow on a phone-width screen — same horizontalScroll
            // fix this file's own ComposerModeRow already applies to an equivalent packed row.
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

/**
 * The per-turn cost line. [minor] is in the user's own price denomination (we never invent a
 * currency — binding rule 8), so it's shown as a plain value alongside the real token counts.
 */
private fun costLine(minor: String, tokensIn: String, tokensOut: String): String {
    val m = minor.toLongOrNull() ?: 0L
    return "≈ $m  ·  in $tokensIn / out $tokensOut tok"
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
    stopped: Boolean,
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
                            if (highlighted) it.border(2.dp, LocalHyleColors.current.cyan, MaterialTheme.shapes.medium) else it
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (fromUser) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
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
                        if (stopped) {
                            Text(
                                "· stopped here",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
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
