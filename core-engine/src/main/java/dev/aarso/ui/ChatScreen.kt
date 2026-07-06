@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.aarso.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
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
import dev.aarso.domain.markdown.StreamingMarkdown
import dev.aarso.R
import dev.aarso.domain.GeneratedToken
import dev.aarso.domain.Role
import dev.aarso.domain.instrument.Confidence
import dev.aarso.domain.prompt.LintSeverity
import dev.aarso.domain.prompt.PromptLinter
import dev.aarso.domain.tree.Conversations
import dev.aarso.domain.tree.PathView
import dev.aarso.flavor.InvocationFeatures
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.hyle.HyleChip
import dev.aarso.ui.hyle.HyleField
import dev.aarso.ui.hyle.HyleNavChip
import dev.aarso.ui.hyle.FileImage
import dev.aarso.ui.theme.LocalHyleColors
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
) {
    val state by viewModel.uiState.collectAsState()
    val instrumentsExpanded by viewModel.instrumentsExpanded.collectAsState()
    val entropyColoring by viewModel.entropyColoring.collectAsState()
    var input by remember { mutableStateOf("") }
    var showModelSheet by remember { mutableStateOf(false) }
    var showPlus by remember { mutableStateOf(false) }
    var showParticipants by remember { mutableStateOf(false) }
    var showMe by remember { mutableStateOf(false) }
    var actionStep by remember { mutableStateOf<PathView.Step?>(null) }
    var flagStep by remember { mutableStateOf<PathView.Step?>(null) }
    // D1: dismissible "Connect your repos" home card (session-scoped dismissal).
    var connectDismissed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
                            val hosts by (ctx.applicationContext as dev.aarso.AarsoApp)
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
                if (!imageMode) {
                    TextButton(
                        onClick = { viewModel.refinePrompt(input) },
                        enabled = input.isNotBlank() && !state.rewriting &&
                            state.genPhase == GenPhase.IDLE && state.engineAvailable,
                    ) {
                        Text(if (state.rewriting) "…" else "Refine")
                    }
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
            onDismiss = { showPlus = false },
        )
    }

    if (showParticipants) {
        Dialog(
            onDismissRequest = { showParticipants = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                dev.aarso.ui.rooms.ParticipantsScreen(onClose = { showParticipants = false })
            }
        }
    }

    if (showMe) {
        Dialog(
            onDismissRequest = { showMe = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                dev.aarso.ui.rooms.MeScreen(onClose = { showMe = false })
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Left: Chats/Back — slant on the right, rounds on the left.
        HyleNavChip(label = "‹ Chats", onClick = onOpenChats, slantLeft = false, contentDescription = "Open chats")
        // Centre: current conversation title (truncated).
        Text(
            state.steps.firstOrNull { it.node.role == Role.USER }
                ?.node?.content?.lineSequence()?.firstOrNull()?.take(36) ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = c.textMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Right: Settings, then the profile avatar → Me · Myself · I.
        HyleNavChip(label = "⚙", onClick = onOpenSettings, slantLeft = true, contentDescription = "Open settings")
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(c.violet, CircleShape)
                .clickable(onClick = onOpenMe)
                .semantics { contentDescription = "Open your profile — Me, Myself, I" },
            contentAlignment = Alignment.Center,
        ) {
            Text("☺", style = MaterialTheme.typography.titleSmall, color = c.onViolet)
        }
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
            Switch(checked = entropyColoring, onCheckedChange = onEntropyColoring)
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
                "Aarso is on-device first: pick a model once and chat privately, offline.",
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
            onLongPress = onLongPress,
        )
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
 * currency — binding rule 8), so it's shown as a plain value alongside the real token counts.
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

@Composable
private fun MessageBubble(
    role: Role,
    content: String,
    imagePath: String?,
    stopped: Boolean,
    onLongPress: () -> Unit,
) {
    val fromUser = role == Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
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
    }
}
