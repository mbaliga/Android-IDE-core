package dev.fonebrew.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.component.HyleContextMenu
import dev.fonebrew.domain.Role
import dev.fonebrew.domain.thread.ThreadChains
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.domain.tree.TreeOutline
import dev.fonebrew.ui.ChatViewModel
import dev.fonebrew.ui.hyle.HyleButton
import dev.fonebrew.ui.hyle.HyleChip
import dev.fonebrew.ui.hyle.HyleTitle
import kotlinx.coroutines.launch
import dev.fonebrew.ui.theme.LocalHyleColors

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
    val chains by viewModel.threadChains.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val container = (context.applicationContext as dev.fonebrew.FonebrewApp).container
    val hosts by container.gitHostStore.hosts.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var handoff by remember { mutableStateOf<String?>(null) }
    // THREAD_TOPOLOGY_PLAN.md WP10: whether the native GraphRoom overlay is open — function-scope
    // (not inside the Column below) so the `if (showGraphRoom)` launch at the bottom of this
    // composable, alongside `handoff?.let { ... }`, can see it.
    var showGraphRoom by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HyleTitle("Tree")
        Text(
            "Every turn is a node; every fork stays visible. Tap a node to continue " +
                "from it — pinch out to return.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        // Brief §6.1: the Tree is tabbed over one git-like tree — conversation branches,
        // commits branch, builds branch. Builds moved here from Develop. "Chain" (WP6) is the
        // cross-conversation view: every root grouped into its Fork/Spawn mega-thread. "Graph"
        // (WP10) opens the native living-graph room on top, rather than swapping tab content —
        // there's nothing to show inline, so selecting it launches `GraphRoom` as its own
        // full-screen overlay and returns here (tab 0) when it closes.
        var treeTab by remember { mutableStateOf(0) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            listOf("Conversation", "Commits", "Builds", "Chain", "Graph").forEachIndexed { i, label ->
                HyleChip(
                    treeTab == i,
                    {
                        if (i == 4) showGraphRoom = true else treeTab = i
                    },
                    label,
                )
            }
        }

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
                    if (host != null) {
                        HyleButton("Back up", onClick = {
                            scope.launch {
                                val token = container.gitHostStore.token(host.id)
                                note = if (token == null) "No token — reconnect in Settings."
                                else container.gitBackup.backUp(host, token).fold({ "Backed up to ${host.repo}." }, { "Backup failed: ${it.message}" })
                            }
                        })
                    }
                    HyleButton("Export", onClick = {
                        scope.launch {
                            val files = dev.fonebrew.domain.sync.TreeArchive.write(container.repository.tree().allNodes())
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
            1 -> Text(
                "Commits — soon. Commits from accepted changes (Develop → Files review) and manual " +
                    "commits will branch here, each linked to its diff, the conversation that produced " +
                    "it, and the builds it triggers. Nothing is fabricated until that plumbing lands.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            2 -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            ) {
                // Builds live in the Tree now (§6.1): the build branch, tied to its commit.
                dev.fonebrew.ui.develop.BuildsFacet()
            }
            else -> ChainListView(
                chains = chains,
                onOpen = { rootId -> viewModel.openConversation(rootId); onNodeChosen() },
            )
        }
    }

    handoff?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { handoff = null },
            title = { Text("Handoff summary") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { HyleButton("Share", onClick = { shareText(context, "Fonebrew handoff", text); handoff = null }) },
            dismissButton = { HyleButton("Close", onClick = { handoff = null }) },
        )
    }

    if (showGraphRoom) {
        dev.fonebrew.ui.graph.GraphRoom(
            viewModel = viewModel,
            onOpenNode = { nodeId -> viewModel.branchFrom(nodeId); onNodeChosen() },
            onClose = { showGraphRoom = false },
        )
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

/**
 * THREAD_TOPOLOGY_PLAN.md WP6's "Chain" chip: every conversation grouped into its Fork/Spawn
 * mega-thread ([dev.fonebrew.domain.thread.ThreadChains]), cross-conversation (unlike the
 * "Conversation" tab above, which is scoped to the active root). A chain with no fork/spawn
 * history renders as one compact row; a multi-root chain expands into its family, oldest first,
 * each link tagged with how it joined ([TreeFork.LineageKind]) and how many chapter/compaction
 * markers it carries.
 */
@Composable
private fun ChainListView(chains: List<ThreadChains.Chain>, onOpen: (String) -> Unit) {
    if (chains.isEmpty()) {
        Text(
            "No conversations yet. Fork or Spawn from a turn (radial menu → Branch/Fork/Spawn) " +
                "to grow a chain here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        items(chains, key = { it.originRootId }) { chain -> ChainCard(chain, onOpen) }
    }
}

@Composable
private fun ChainCard(chain: ThreadChains.Chain, onOpen: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 6.dp),
    ) {
        if (chain.isMultiRoot) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    "⑂ ${chain.rootCount} conversations",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (chain.totalChapters > 0 || chain.totalCompactions > 0) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        buildString {
                            if (chain.totalChapters > 0) append("${chain.totalChapters} chapters")
                            if (chain.totalChapters > 0 && chain.totalCompactions > 0) append(" · ")
                            if (chain.totalCompactions > 0) append("${chain.totalCompactions} compactions")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            chain.links.forEach { link -> ChainLinkRow(link, indent = true, onOpen) }
        } else {
            ChainLinkRow(chain.links.single(), indent = false, onOpen)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChainLinkRow(link: ThreadChains.ChainLink, indent: Boolean, onOpen: (String) -> Unit) {
    // Desktop-class kit §3: long-press (+ a TalkBack custom action) opens a HyleContextMenu —
    // see [chainLinkMenuItems]. The row's own tap-to-open stays exactly as it was.
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onOpen(link.rootId) },
                    onLongClick = { menuOpen = true },
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Open chain link actions") { menuOpen = true; true },
                    )
                }
                .padding(start = if (indent) 24.dp else 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    link.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val lineage = link.lineage
                if (lineage != null) {
                    Text(
                        // TreeFork.LineageKind.name is lowercased for display; an unrecognised/absent
                        // kind (a future WP's lineage source, or a malformed payload) still shows the
                        // link exists — "linked from …" — rather than hiding it.
                        "${lineage.lineageKind?.name?.lowercase() ?: "linked"} from a prior turn",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (link.chapterCount > 0) {
                Text(
                    "${link.chapterCount} ch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (menuOpen) {
            HyleContextMenu(
                expanded = true,
                onDismissRequest = { menuOpen = false },
                items = chainLinkMenuItems(),
                onItemClick = { id -> if (id == "open") onOpen(link.rootId) },
            )
        }
    }
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
