package dev.aarso.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.cells.HyleLensHeading
import dev.aarso.hyle.cells.HyleLensActions
import dev.aarso.hyle.cells.HyleFocusLens
import dev.aarso.domain.Role
import dev.aarso.domain.tree.Conversations
import dev.aarso.domain.tree.TreeOutline
import dev.aarso.ui.ChatViewModel
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleTabBar
import dev.aarso.hyle.cells.HyleTabSpec
import dev.aarso.hyle.cells.HyleTitle
import kotlinx.coroutines.launch
import dev.aarso.hyle.theme.LocalHyleColors

/**
 * The z-axis view (§5): the current conversation abstracted into its branching
 * structure — same data, different zoom level. Reached by pinching out on the
 * thread; pinch in (or tap a node) to descend back into the flow at that point.
 */
@Composable
fun TreeRoom(
    viewModel: ChatViewModel,
    onNodeChosen: () -> Unit,
) {
    val rows by viewModel.treeOutline.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val container = (context.applicationContext as dev.aarso.FonebrewApp).container
    val hosts by container.gitHostStore.hosts.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var handoff by remember { mutableStateOf<String?>(null) }

    // Brief §6.1: the Tree is tabbed over one git-like tree — conversation branches,
    // commits branch, builds branch. Builds moved here from Develop.
    var treeTab by remember { mutableStateOf(0) }
    val universalTabBarPosition by container.sessionStore.tabBarPosition.collectAsState()
    val roomTabBarOverrides by container.sessionStore.roomTabBarPosition.collectAsState()
    val tabBarPosition = roomTabBarOverrides["tree"] ?: universalTabBarPosition

    val tabBarBlock: @Composable () -> Unit = {
        HyleTabBar(
            tabs = listOf(
                HyleTabSpec("Conversation") { tint ->
                    // Branch glyph: a trunk line with a fork.
                    val w = size.width; val h = size.height
                    val sw = w * 0.09f
                    drawLine(tint, Offset(w * 0.30f, h * 0.15f), Offset(w * 0.30f, h * 0.85f), strokeWidth = sw)
                    drawLine(tint, Offset(w * 0.30f, h * 0.55f), Offset(w * 0.72f, h * 0.30f), strokeWidth = sw)
                    drawCircle(tint, radius = w * 0.09f, center = Offset(w * 0.30f, h * 0.20f))
                    drawCircle(tint, radius = w * 0.09f, center = Offset(w * 0.30f, h * 0.80f))
                    drawCircle(tint, radius = w * 0.09f, center = Offset(w * 0.76f, h * 0.24f))
                },
                HyleTabSpec("Commits") { tint ->
                    // Commit glyph: a node dot on a vertical line.
                    val w = size.width; val h = size.height
                    val sw = w * 0.09f
                    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw)
                    drawLine(tint, Offset(w * 0.5f, h * 0.10f), Offset(w * 0.5f, h * 0.35f), strokeWidth = sw)
                    drawLine(tint, Offset(w * 0.5f, h * 0.65f), Offset(w * 0.5f, h * 0.90f), strokeWidth = sw)
                    drawCircle(tint, radius = w * 0.20f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                },
                HyleTabSpec("Builds") { tint ->
                    // Build glyph: a simple stacked bars/hammer-ish rect.
                    val w = size.width; val h = size.height
                    val sw = w * 0.09f
                    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw)
                    drawRoundRect(
                        tint, topLeft = Offset(w * 0.14f, h * 0.20f),
                        size = Size(w * 0.72f, h * 0.60f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f), style = stroke,
                    )
                    drawLine(tint, Offset(w * 0.14f, h * 0.50f), Offset(w * 0.86f, h * 0.50f), strokeWidth = sw)
                },
            ),
            selected = treeTab,
            onSelect = { treeTab = it },
            modifier = Modifier.fillMaxWidth(),
            position = tabBarPosition,
        )
    }

    val contentBlock: @Composable () -> Unit = {
        when (treeTab) {
            0 -> {
                // Git-sync indicator + manual export + handoff summary (IA §F).
                val host = hosts.firstOrNull()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (host == null) "⊘ not synced" else "⟳ ${host.owner}/${host.repo}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (host == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    // Always in the tree (avoids tab-bar jitter shoving Export/Handoff sideways
                    // when a host connects/disconnects) — just invisible + non-clickable when
                    // there's no host to back up to.
                    HyleButton(
                        "Back up",
                        onClick = {
                            val h = host ?: return@HyleButton
                            scope.launch {
                                val token = container.gitHostStore.token(h.id)
                                note = if (token == null) "No token — reconnect in Settings."
                                else container.gitBackup.backUp(h, token).fold({ "Backed up to ${h.repo}." }, { "Backup failed: ${it.message}" })
                            }
                        },
                        modifier = if (host == null) Modifier.alpha(0f) else Modifier,
                        enabled = host != null,
                    )
                    HyleButton("Export", onClick = {
                        scope.launch {
                            val files = dev.aarso.domain.sync.TreeArchive.write(container.repository.tree().allNodes())
                            val blob = files.entries.joinToString("\n\n") { "// ${it.key}\n${it.value}" }
                            shareText(context, "Fonebrew tree export", blob)
                        }
                    })
                    HyleButton("Handoff", onClick = { handoff = buildHandoff(rows) })
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp))
                }
                if (rows.isEmpty()) {
                    Text(
                        "Nothing here yet — this conversation has no turns.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        items(rows, key = { it.node.id }) { row ->
                            TreeNodeRow(
                                row = row,
                                enabled = !state.isGenerating,
                                onTap = {
                                    viewModel.branchFrom(row.node.id)
                                    onNodeChosen()
                                },
                            )
                        }
                    }
                }
            }
            1 -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            ) {
                Text(
                    "Commits — soon. Commits from accepted changes (Develop → Files review) and manual " +
                        "commits will branch here, each linked to its diff, the conversation that produced " +
                        "it, and the builds it triggers. Nothing is fabricated until that plumbing lands.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            ) {
                // Builds live in the Tree now (§6.1): the build branch, tied to its commit.
                dev.aarso.ui.develop.BuildsFacet()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HyleTitle("Tree")
        Text(
            "Every turn is a node; every fork stays visible. Tap a node to continue " +
                "from it — pinch out to return.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        if (tabBarPosition == "BOTTOM") {
            Box(Modifier.weight(1f)) { contentBlock() }
            tabBarBlock()
        } else {
            tabBarBlock()
            Box(Modifier.weight(1f)) { contentBlock() }
        }
    }

    handoff?.let { text ->
        HyleFocusLens(visible = true, onDismiss = { handoff = null }) {
            HyleLensHeading("Handoff summary")
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            HyleLensActions {
                HyleButton("Close", secondary = true, onClick = { handoff = null })
                Spacer(Modifier.width(8.dp))
                HyleButton("Share", onClick = { shareText(context, "Fonebrew handoff", text); handoff = null })
            }
        }
    }
}

/** A plain-text digest of the active path — for handing the thread to another agent/AI (§F4). */
private fun buildHandoff(rows: List<TreeOutline.Row>): String = buildString {
    appendLine("# Conversation handoff")
    val active = rows.filter { it.onActivePath }
    appendLine("Turns on the active path: ${active.size} (of ${rows.size} total nodes).")
    val models = active.mapNotNull { it.node.modelId }.distinct()
    if (models.isNotEmpty()) appendLine("Models: ${models.joinToString(", ") { it.substringAfter(':') }}")
    appendLine()
    active.forEach { r ->
        val who = when (r.node.role) {
            Role.USER -> "User"
            Role.ASSISTANT -> "Assistant"
            Role.SYSTEM -> "System"
        }
        val body = r.node.content.ifBlank {
            if (r.node.metadata.containsKey(Conversations.IMAGE_KEY)) "(image)" else "…"
        }
        appendLine("## $who")
        appendLine(body.take(1500))
        appendLine()
    }
}

private fun shareText(context: android.content.Context, title: String, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        android.content.Intent.createChooser(send, title).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun TreeNodeRow(row: TreeOutline.Row, enabled: Boolean, onTap: () -> Unit) {
    // IntrinsicSize so the depth rails stretch to the node card's height.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 2.dp)) {
        // Depth guides: one quiet rail per ancestor level.
        repeat(row.depth) {
            Box(
                Modifier
                    .padding(start = 7.dp, end = 8.dp)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outline),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (row.onActivePath) LocalHyleColors.current.violetDim else MaterialTheme.colorScheme.surfaceVariant,
                )
                .clickable(enabled = enabled, onClick = onTap)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val role = when (row.node.role) {
                Role.USER -> "you"
                Role.ASSISTANT -> "model"
                Role.SYSTEM -> "system"
            }
            Row {
                Text(
                    buildString {
                        append(role)
                        if (row.node.metadata.containsKey(Conversations.IMAGE_KEY)) append(" · image")
                        if (row.isBranchPoint) append("  ⑂ ${row.childCount}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.onActivePath) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (row.onActivePath) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                row.node.content.ifBlank {
                    if (row.node.metadata.containsKey(Conversations.IMAGE_KEY)) "(image)" else "…"
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
