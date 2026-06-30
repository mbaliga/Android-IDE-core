package dev.aarso.ui.rooms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.AarsoApp
import dev.aarso.domain.MessageNode
import dev.aarso.domain.tree.Bookmarks
import dev.aarso.domain.tree.Conversations
import dev.aarso.ui.ChatViewModel
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.hyle.HyleChip
import dev.aarso.ui.hyle.HyleTitle
import dev.aarso.ui.hyle.FileImage
import dev.aarso.ui.theme.LocalHyleColors

private enum class ChatsTab(val label: String) {
    ALL("All"), TEXT("Text"), IMAGE("Image"), STARRED("Starred"), PROJECTS("Projects")
}

/**
 * The room parked off the LEFT edge (IA §A): every conversation, newest first.
 * Tabs filter it — All / Text / Image / Starred / Projects — and a round + button starts a new
 * chat. Image turns are nodes on the same tree (§6), not a separate place; a star on each card
 * stars its conversation, and a tag assigns it to a project (both persisted in SessionStore).
 */
@Composable
fun ChatsRoom(
    viewModel: ChatViewModel,
    onClose: () -> Unit,
) {
    val c = LocalHyleColors.current
    val context = LocalContext.current
    val session = (context.applicationContext as AarsoApp).container.sessionStore

    val conversations by viewModel.conversations.collectAsState()
    val imageNodes by viewModel.imageNodes.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val bookmarked by session.bookmarkedRoots.collectAsState()
    val projects by session.conversationProjects.collectAsState()
    var tab by remember { mutableStateOf(ChatsTab.ALL) }
    var projectDialogFor by remember { mutableStateOf<Conversations.Summary?>(null) }

    Box(Modifier.fillMaxSize().background(c.ink)) {
        Column(Modifier.fillMaxSize()) {
            HyleTitle("Chats")
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatsTab.entries.forEach { t -> HyleChip(tab == t, { tab = t }, t.label) }
            }

            val activeIds = state.steps.map { it.node.id }.toSet()
            val firstNodeId = state.steps.firstOrNull()?.node?.id
            fun listProps(list: List<Conversations.Summary>, empty: String) = ConversationListProps(
                conversations = list, emptyMessage = empty, activeIds = activeIds, firstNodeId = firstNodeId,
                bookmarked = bookmarked, projects = projects, enabled = !state.isGenerating,
                onOpen = { viewModel.openConversation(it.rootId); onClose() },
                onToggleBookmark = { session.toggleBookmark(it.rootId) },
                onSetProject = { projectDialogFor = it },
            )
            when (tab) {
                ChatsTab.IMAGE -> ImageList(imageNodes, !state.isGenerating) {
                    viewModel.branchFrom(it.id); onClose()
                }
                ChatsTab.PROJECTS -> ProjectGroupedList(listProps(conversations, ""), projects)
                else -> {
                    val list = when (tab) {
                        ChatsTab.STARRED -> Bookmarks.filter(conversations, bookmarked)
                        ChatsTab.TEXT -> conversations.filter { !it.hasImage }
                        else -> conversations
                    }
                    val empty = when (tab) {
                        ChatsTab.STARRED -> "No starred chats yet. Tap the star on a conversation to keep it here."
                        ChatsTab.TEXT -> "No text-only conversations yet."
                        else -> "No conversations yet. Start one — every turn becomes a node on the tree, " +
                            "and every fork stays visible."
                    }
                    ConversationList(listProps(list, empty))
                }
            }
        }
        projectDialogFor?.let { conv ->
            ProjectDialog(
                current = projects[conv.rootId].orEmpty(),
                existing = projects.values.distinct().sorted(),
                onDismiss = { projectDialogFor = null },
                onSet = { session.setConversationProject(conv.rootId, it); projectDialogFor = null },
            )
        }

        // Round + : start a new chat. 56dp target, bottom-end.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(c.violet, CircleShape)
                .clickable(enabled = !state.isGenerating) { viewModel.newChat(); onClose() }
                .semantics { contentDescription = "New chat" },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.headlineMedium, color = c.onViolet)
        }
    }
}

/** Bundles the shared list inputs so the All/Text/Starred/Projects views stay in sync. */
private class ConversationListProps(
    val conversations: List<Conversations.Summary>,
    val emptyMessage: String,
    val activeIds: Set<String>,
    val firstNodeId: String?,
    val bookmarked: Set<String>,
    val projects: Map<String, String>,
    val enabled: Boolean,
    val onOpen: (Conversations.Summary) -> Unit,
    val onToggleBookmark: (Conversations.Summary) -> Unit,
    val onSetProject: (Conversations.Summary) -> Unit,
)

@Composable
private fun ConversationList(p: ConversationListProps) {
    if (p.conversations.isEmpty()) {
        Text(
            p.emptyMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp),
    ) {
        items(p.conversations, key = { it.rootId }) { conv -> ConversationCard(p, conv) }
    }
}

/** Projects view: conversations grouped by their assigned project, Unassigned last. */
@Composable
private fun ProjectGroupedList(p: ConversationListProps, projects: Map<String, String>) {
    if (p.conversations.isEmpty()) {
        Text(
            "No conversations yet. Assign a chat to a project with the tag button to group it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    val groups = p.conversations.groupBy { projects[it.rootId] }
    val ordered = groups.keys.filterNotNull().sorted() + if (groups.containsKey(null)) listOf<String?>(null) else emptyList()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp),
    ) {
        ordered.forEach { label ->
            item(key = "hdr-${label ?: "_unassigned"}") {
                Text(
                    label ?: "Unassigned",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(groups[label].orEmpty(), key = { it.rootId }) { conv -> ConversationCard(p, conv) }
        }
    }
}

@Composable
private fun ImageList(imageNodes: List<MessageNode>, enabled: Boolean, onOpen: (MessageNode) -> Unit) {
    if (imageNodes.isEmpty()) {
        Text(
            "No image turns yet. Tap + in the composer → Image, then describe one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp),
    ) {
        items(imageNodes, key = { it.id }) { node ->
            ImageNodeCard(node = node, enabled = enabled, onOpen = { onOpen(node) })
        }
    }
}

@Composable
private fun ConversationCard(p: ConversationListProps, conv: Conversations.Summary) {
    val c = LocalHyleColors.current
    val active = conv.latestLeafId in p.activeIds || p.firstNodeId == conv.rootId
    val bookmarked = conv.rootId in p.bookmarked
    val project = p.projects[conv.rootId]
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = p.enabled, onClick = { p.onOpen(conv) }),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = BorderStroke(1.dp, c.hairline),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    conv.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (project != null) {
                    Text(
                        "▸ $project",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.violet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        relativeTime(conv.lastUpdatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${conv.nodeCount} turns",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (conv.modelIds.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            conv.modelIds.joinToString(" · ") { it.substringAfter(':') },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { p.onSetProject(conv) })
                    .semantics { contentDescription = "Assign to a project" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "⊞",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (project != null) c.violet else c.textMid,
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { p.onToggleBookmark(conv) })
                    .semantics { contentDescription = if (bookmarked) "Remove star" else "Star" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (bookmarked) "★" else "☆",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (bookmarked) c.violet else c.textMid,
                )
            }
        }
    }
}

/** Assign or clear a conversation's project; tap an existing label or type a new one. */
@Composable
private fun ProjectDialog(
    current: String,
    existing: List<String>,
    onDismiss: () -> Unit,
    onSet: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Project name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (existing.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        existing.forEach { HyleChip(text == it, { text = it }, it) }
                    }
                }
            }
        },
        confirmButton = { HyleButton("Save", onClick = { onSet(text.ifBlank { null }) }) },
        dismissButton = { HyleButton("Clear", onClick = { onSet(null) }) },
    )
}

@Composable
private fun ImageNodeCard(node: MessageNode, enabled: Boolean, onOpen: () -> Unit) {
    val c = LocalHyleColors.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, c.hairline),
    ) {
        Column(Modifier.padding(10.dp)) {
            node.metadata[Conversations.IMAGE_KEY]?.let {
                FileImage(it, Modifier.fillMaxWidth().heightIn(max = 220.dp))
            }
            Text(
                relativeTime(node.createdAt) + (node.modelId?.let { " · ${it.substringAfter(':')}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

internal fun relativeTime(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}
