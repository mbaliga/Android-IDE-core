@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.aarso.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.semantics.contentDescription
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
import dev.aarso.hyle.cells.HyleSwitch
import dev.aarso.domain.markdown.StreamingMarkdown
import dev.aarso.R
import dev.aarso.domain.GeneratedToken
import dev.aarso.domain.Role
import dev.aarso.domain.instrument.Confidence
import dev.aarso.domain.prompt.LintSeverity
import dev.aarso.domain.prompt.PromptLinter
import dev.aarso.domain.tree.Conversations
import dev.aarso.hyle.cells.hylePulse
import dev.aarso.domain.tree.PathView
import dev.aarso.flavor.InvocationFeatures
import dev.aarso.data.DownloadCenter
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleCard
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.cells.HyleNavChip
import dev.aarso.hyle.cells.HyleSlashTabBar
import dev.aarso.hyle.cells.HyleTabSpec
import dev.aarso.hyle.cells.FileImage
import dev.aarso.hyle.theme.LocalHyleColors
import dev.aarso.ui.components.MentionPopup
import dev.aarso.ui.components.MentionTarget
import dev.aarso.ui.components.SlashCommand
import dev.aarso.ui.components.SlashCommandPopup
import dev.aarso.ui.components.applyMention
import dev.aarso.ui.components.isShellEscape
import dev.aarso.ui.components.matchMentions
import dev.aarso.ui.components.matchSlashCommands
import dev.aarso.ui.develop.TerminalFacet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
) {
    val state by viewModel.uiState.collectAsState()
    val instrumentsExpanded by viewModel.instrumentsExpanded.collectAsState()
    val entropyColoring by viewModel.entropyColoring.collectAsState()
    var input by remember { mutableStateOf("") }
    var showModelSheet by remember { mutableStateOf(false) }
    var showPlus by remember { mutableStateOf(false) }
    var showParticipants by remember { mutableStateOf(false) }
    var actionStep by remember { mutableStateOf<PathView.Step?>(null) }
    var flagStep by remember { mutableStateOf<PathView.Step?>(null) }
    // D1: dismissible "Connect your repos" home card (session-scoped dismissal).
    var connectDismissed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Chat / Terminal — two windows onto the same underlying capability: ask in Chat and it runs,
    // or drive the shell directly in Terminal (§ owner spec, 2026-07-27). Background tasks is a
    // layer BENEATH both, not a third peer tab — see [BackgroundTasksStrip].
    val container = (LocalContext.current.applicationContext as dev.aarso.FonebrewApp).container
    val c = LocalHyleColors.current
    var chatTab by remember { mutableStateOf(ChatTab.CHAT) }
    val universalTabBarPosition by container.sessionStore.tabBarPosition.collectAsState()
    val roomTabBarOverrides by container.sessionStore.roomTabBarPosition.collectAsState()
    val tabBarPosition = roomTabBarOverrides["chat"] ?: universalTabBarPosition
    val activeDownloads by container.downloadCenter.active.collectAsState()
    val backgroundJobs by container.backgroundJobs.jobs.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            container.backgroundJobs.prune()
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
    val participants by container.councilStore.participants.collectAsState()
    val mentionTargets = remember(participants) {
        participants.map { p -> MentionTarget(p.name, "council participant", "@${p.name} ") }
    }
    val mentionMatches = matchMentions(input, mentionTargets)

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
        // SpatialRoot's outer Box already reserves the nav-bar with systemBarsPadding();
        // a plain imePadding() here would stack on top of that and leave a nav-bar-sized
        // gap between the composer and the keyboard, so exclude what's already reserved.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
        ) {
            HomeHeader(
                state = state,
                onBadgeTap = { if (!state.isGenerating) showModelSheet = true },
                onTitleClick = { showParticipants = true },
            )
            if (tabBarPosition != "BOTTOM") {
                ChatTabBar(
                    tab = chatTab,
                    tabBarPosition = tabBarPosition,
                    onSelect = { chatTab = it },
                    onOpenChats = onOpenChats,
                    onOpenSettings = onOpenSettings,
                )
            }
            when (chatTab) {
                ChatTab.TERMINAL -> Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    TerminalFacet()
                }
                ChatTab.CHAT -> Column(Modifier.weight(1f)) {
                    BackgroundTasksStrip(activeDownloads, backgroundJobs)
                    InstrumentsStrip(
                        state = state,
                        input = input,
                        expanded = instrumentsExpanded,
                        onToggle = { viewModel.setInstrumentsExpanded(!instrumentsExpanded) },
                        entropyColoring = entropyColoring,
                        onEntropyColoring = viewModel::setEntropyColoring,
                    )
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
                            val hosts by (ctx.applicationContext as dev.aarso.FonebrewApp)
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
                        MessageTurn(
                            step = step,
                            enabled = !state.isGenerating,
                            onSwitch = { dir -> viewModel.switchAlternative(step.node.id, dir) },
                            onLongPress = { actionStep = step },
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val imageMode = state.imageMode
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
                        state.noModelActive -> "Download a model to begin"
                        state.engineAvailable -> "Message"
                        else -> "Model not runnable yet"
                    },
                    enabled = state.genPhase == GenPhase.IDLE && (state.engineAvailable || imageMode),
                )
                // Always in the tree (never conditionally included) so the field/Send button
                // beside it never shifts position entering/exiting image mode — reserve the
                // space and fade + disable instead (same jitter class already fixed elsewhere).
                TextButton(
                    onClick = { viewModel.refinePrompt(input) },
                    enabled = !imageMode && input.isNotBlank() && !state.rewriting &&
                        state.genPhase == GenPhase.IDLE && state.engineAvailable,
                    modifier = Modifier.alpha(if (!imageMode) 1f else 0f),
                ) {
                    Text(if (state.rewriting) "…" else "Refine")
                }
                if (state.genPhase != GenPhase.IDLE) {
                    // An in-flight image render has no cancel point (§6) — the
                    // button stays as state, disabled, rather than lying.
                    HyleButton(
                        "Stop",
                        onClick = { viewModel.stopGeneration() },
                        enabled = !imageMode,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                } else {
                    HyleButton(
                        if (imageMode) "Generate" else "Send",
                        onClick = {
                            viewModel.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank() && (state.engineAvailable || imageMode),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
                }
            }
            if (tabBarPosition == "BOTTOM") {
                ChatTabBar(
                    tab = chatTab,
                    tabBarPosition = tabBarPosition,
                    onSelect = { chatTab = it },
                    onOpenChats = onOpenChats,
                    onOpenSettings = onOpenSettings,
                )
            }
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))

        // Loading a GGUF into memory is genuinely obstructive — typing or switching rooms
        // mid-load races the engine's init, so the surface goes untouchable until it settles.
        if (state.genPhase == GenPhase.LOADING) {
            // Shown, not said: the ground goes out of reach and keeps moving, because
            // the load genuinely is running. The words survive only for a screen reader.
            dev.aarso.hyle.cells.HyleSealedLens("Loading model…")
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
            onDismiss = { showPlus = false },
        )
    }

    if (showParticipants) {
        Dialog(
            onDismissRequest = { showParticipants = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                dev.aarso.ui.rooms.ParticipantsScreen(
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
    dev.aarso.hyle.cells.HyleFocusLens(
        visible = pendingMode != null,
        onDismiss = { viewModel.cancelInteractionChange() },
    ) {
        val mode = pendingMode ?: return@HyleFocusLens
        val label = when (mode) {
            ComposerMode.MODELS -> "Council · models"
            ComposerMode.PERSONAS -> "Council · personas"
            else -> "Single"
        }
        dev.aarso.hyle.cells.HyleLensHeading(
            title = "Switch to $label?",
            body = "The interaction model is locked once a conversation starts. Switching " +
                "starts a new branch and summarizes everything so far into it — your " +
                "current thread stays intact on the tree.",
        )
        dev.aarso.hyle.cells.HyleLensActions {
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
        )
    }

    flagStep?.let { step ->
        FlagOutputDialog(
            content = step.node.content,
            modelId = step.node.modelId,
            onDismiss = { flagStep = null },
        )
    }
}

/**
 * The one piece of chrome the home room keeps: the conversation title (quiet, left, WhatsApp-
 * style tappable to open [dev.aarso.ui.rooms.ParticipantsScreen] — group info lives behind the
 * header, not a separate mode-selector row) and the status indicator (right). "‹ Chats"/"⚙" no
 * longer live here — they're a permanent part of [ChatTabBar]'s leading/trailing slots now, so
 * this header doesn't double them up regardless of whether that bar docks top or bottom.
 */
@Composable
private fun HomeHeader(
    state: ChatUiState,
    onBadgeTap: () -> Unit,
    onTitleClick: () -> Unit,
) {
    val c = LocalHyleColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Current conversation title (truncated) — tap opens participants/group info, same as
        // tapping a WhatsApp chat's header.
        Text(
            state.steps.firstOrNull { it.node.role == Role.USER }
                ?.node?.content?.lineSequence()?.firstOrNull()?.take(36) ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = c.textMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onTitleClick),
        )
        // The user-selectable status chip (Settings -> Global -> Header status). Replaces the
        // fixed Me·Myself·I avatar shortcut — that screen stays reachable from Settings, this
        // slot now shows whatever single fact the user opted into seeing at a glance, or nothing.
        HeaderIndicator(state)
    }
}

/**
 * The Chat/Terminal switcher — two windows onto the same underlying capability, not a Chat-vs-
 * something-else split (§ owner spec). Styled as [HyleSlashTabBar] (the owner's reference: a
 * leading slot, then tabs threaded by a literal "/", no per-tab fill) rather than [HyleTabBar]'s
 * heavier filled-chip register — this switches VIEWS of one conversation, not top-level rooms.
 * "All chats" sits in the leading slot in place of the reference's generic overflow icon (owner
 * ask); Settings trails. Lives here rather than split into [HomeHeader] so the whole row moves
 * as one unit with [tabBarPosition] (top or bottom of the screen).
 */
@Composable
private fun ChatTabBar(
    tab: ChatTab,
    tabBarPosition: String,
    onSelect: (ChatTab) -> Unit,
    onOpenChats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HyleSlashTabBar(
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
            ),
            selected = tab.ordinal,
            onSelect = { onSelect(ChatTab.entries[it]) },
            leading = {
                HyleNavChip(label = "‹ Chats", onClick = onOpenChats, slantLeft = false, contentDescription = "Open chats")
            },
        )
        HyleNavChip(label = "⚙", onClick = onOpenSettings, slantLeft = true, contentDescription = "Open settings")
    }
}

/**
 * Background tasks — a layer BENEATH Chat/Terminal, not a third peer tab (§ owner spec): a
 * collapsed one-line entry inside Chat, expandable into Running/Finished sections the same shape
 * as this app's OWN build tooling shows its parallel background agents. Two real sources, merged
 * for display only: [DownloadCenter.active] (its own percentage) and [BackgroundJobs.jobs] (a
 * Loop run, the coding Agent proposing a change — start/finish only, no fraction). Nothing here
 * is invented state (rule 6): a source only appears once something actually registers it.
 */
@Composable
private fun BackgroundTasksStrip(
    active: Map<String, DownloadCenter.State>,
    jobs: List<dev.aarso.data.BackgroundJobs.Job>,
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
private fun BgJobRow(job: dev.aarso.data.BackgroundJobs.Job, now: Long, finished: Boolean) {
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
private enum class ChatTab { CHAT, TERMINAL }

/**
 * The user-selectable status chip that replaced the fixed Me·Myself·I avatar: a single fact,
 * chosen in Settings -> Global -> "Header status" ([dev.aarso.data.SessionStore.headerIndicator]),
 * about the CURRENT conversation only — never a claim about the whole account/device, matching
 * this header's existing "quiet, per-conversation" scope (the title text beside it works the
 * same way). Renders nothing for "NONE" (the default) or before there's anything to say yet.
 */
@Composable
private fun HeaderIndicator(state: ChatUiState) {
    val context = LocalContext.current
    val container = (context.applicationContext as dev.aarso.FonebrewApp).container
    val mode by container.sessionStore.headerIndicator.collectAsState()
    if (mode == "NONE") return
    val c = LocalHyleColors.current
    val rootId = state.steps.firstOrNull()?.node?.id ?: return

    val label = when (mode) {
        "SOVEREIGNTY" -> {
            val entries by container.ledgerStore.entries().collectAsState(initial = emptyList())
            val split = remember(entries, rootId) {
                dev.aarso.domain.ledger.LedgerAggregations.provenanceSplit(
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
            dev.aarso.ui.rooms.relativeTime(startedAt)
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

/** The send mode, explicit and legible: one voice, a council, or an image (§4b/§6). */
/**
 * The composer "+" sheet (Gemini-style, IA §B5): attach + generation tools, instead of pills.
 * Image generation is wired today; video / 3D / file-attach are honest "soon" rows (rule 6 —
 * never claim a capability that isn't there). They map onto the provider types in Settings.
 */
@Composable
private fun PlusSheet(onGenerateImage: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Create", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlusRow("🖼", "Image", "Generate & edit — on-device or watched cloud", enabled = true, onClick = onGenerateImage)
                PlusRow("🎬", "Video", "Soon — no engine wired yet", enabled = false) {}
                PlusRow("◯", "3D model", "Soon — no engine wired yet", enabled = false) {}
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("Attach", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PlusRow("🖼", "Photo", "Soon — multimodal input not wired yet", enabled = false) {}
                PlusRow("📎", "File", "Soon — multimodal input not wired yet", enabled = false) {}
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

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

/** Long-press actions for one turn: the branch axis, plain copy, and (play) flagging. */
@Composable
private fun TurnActionsSheet(
    step: PathView.Step,
    onBranch: () -> Unit,
    onFlag: () -> Unit,
    onDismiss: () -> Unit,
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
            TextButton(onClick = onBranch, modifier = Modifier.fillMaxWidth()) {
                Text("Branch from here — try a different route")
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
    watched: Boolean,
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
) {
    val fromUser = step.node.role == Role.USER
    Column(modifier = Modifier.fillMaxWidth()) {
        MessageBubble(
            role = step.node.role,
            content = step.node.content,
            imagePath = step.node.metadata[Conversations.IMAGE_KEY],
            stopped = step.node.metadata["stopped"] == "true",
            // costMinor is only ever recorded for a watched-cloud turn that reported usage
            // (LedgerComponents.kt) — reuse it as the provenance signal rather than adding a
            // second source of truth for the same fact.
            watched = step.node.metadata["costMinor"] != null,
            costMinor = step.node.metadata["costMinor"],
            tokensIn = step.node.metadata["tokensIn"],
            tokensOut = step.node.metadata["tokensOut"],
            onLongPress = onLongPress,
        )
        if (step.isBranchPoint) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onSwitch(-1) }, enabled = enabled) { Text("‹") }
                Text(
                    "${step.activeAlternative}/${step.alternativeCount}",
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = { onSwitch(+1) }, enabled = enabled) { Text("›") }
            }
        }
    }
}

/**
 * The per-turn cost line. [minor] is in the user's own price denomination (we never invent a
 * currency), so it's shown as a plain value alongside the real token counts.
 */
private fun costLine(minor: String, tokensIn: String, tokensOut: String): String {
    val m = minor.toLongOrNull() ?: 0L
    return "≈ $m  ·  in $tokensIn / out $tokensOut tok"
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

/**
 * A turn reads as a **file**: a small header line (who wrote it, and — for a watched-cloud
 * turn — the "☁" glyph, never colour alone, per Hyle's [dev.aarso.hyle.Provenance] rule), the
 * body, and a metadata footer below a hairline divider (cost/tokens, or "stopped here") —
 * the same three-part shape as a file card elsewhere in the app, instead of a bare chat bubble.
 */
@Composable
private fun MessageBubble(
    role: Role,
    content: String,
    imagePath: String?,
    stopped: Boolean,
    watched: Boolean,
    costMinor: String?,
    tokensIn: String?,
    tokensOut: String?,
    onLongPress: () -> Unit,
) {
    val fromUser = role == Role.USER
    val c = dev.aarso.hyle.theme.LocalHyleColors.current
    val haptics = dev.aarso.hyle.cells.rememberHyleHaptics()
    val shape = RoundedCornerShape(10.dp)
    val headerLabel = when {
        fromUser -> "You"
        role == Role.SYSTEM -> "System"
        watched -> "☁ Assistant · watched"
        else -> "⌂ Assistant"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .clip(shape)
                .background(if (fromUser) c.violetDim else c.raised, shape)
                .border(1.dp, c.hairline, shape)
                .combinedClickable(onClick = {}, onLongClick = { haptics.tap(); onLongPress() })
                .padding(12.dp),
        ) {
            Text(headerLabel, style = MaterialTheme.typography.labelSmall, color = c.textMid)
            Spacer(Modifier.height(4.dp))
            when {
                // An image turn: the node's payload is the generated file (§6).
                imagePath != null -> FileImage(
                    path = imagePath,
                    modifier = Modifier.fillMaxWidth(0.8f).heightIn(max = 320.dp),
                )
                fromUser || role == Role.SYSTEM -> Text(content)
                // Persisted model turns render as markdown (legibility); the
                // live stream keeps per-token entropy colouring instead. We run
                // the text through the JVM-tested StreamingMarkdown.reconcile so a
                // turn that was stopped mid-fence (a dangling ``` that would swallow
                // the rest of the bubble) renders cleanly; it's idempotent on
                // well-formed markdown, so a complete turn passes through unchanged.
                else -> Markdown(content = StreamingMarkdown.reconcile(content).text)
            }
            if (stopped || costMinor != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = c.hairline)
                if (costMinor != null) {
                    Text(
                        costLine(costMinor, tokensIn ?: "?", tokensOut ?: "?"),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textMid,
                    )
                }
                if (stopped) {
                    Text("· stopped here", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}
