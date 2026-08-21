package dev.fonebrew.ui.rooms

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
import dev.fonebrew.FonebrewApp
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.library.ConvSort
import dev.fonebrew.domain.library.ConversationProjection
import dev.fonebrew.domain.search.query.ChatsPreset
import dev.fonebrew.domain.search.query.ConversationFacetFilter
import dev.fonebrew.domain.search.query.QueryParser
import dev.fonebrew.domain.thread.ThreadChains
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.domain.tree.TreeFork
import dev.fonebrew.ui.ChatViewModel
import dev.fonebrew.domain.library.Conversations as LibConversations
import dev.aarso.hyle.component.HyleField
import dev.fonebrew.ui.hyle.HyleButton
import dev.fonebrew.ui.hyle.HyleChip
import dev.fonebrew.ui.hyle.HyleTitle
import dev.fonebrew.ui.hyle.FileImage
import dev.fonebrew.ui.search.SearchEntryPill
import dev.fonebrew.ui.theme.LocalHyleColors

private enum class ChatsTab(val label: String) {
    ALL("All"), TEXT("Text"), IMAGE("Image"), STARRED("Starred"), PROJECTS("Projects")
}

/** The user-facing sort options, backed by the JVM-tested [LibConversations.sort]. */
private val SORT_LABELS: List<Pair<ConvSort, String>> = listOf(
    ConvSort.RECENT to "Recent",
    ConvSort.CREATED to "Created",
    ConvSort.TITLE to "A–Z",
    ConvSort.MOST_USED to "Most used",
    ConvSort.MOST_BRANCHED to "Most branched",
)

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
    onOpenSearch: () -> Unit = {},
) {
    val c = LocalHyleColors.current
    val context = LocalContext.current
    val session = (context.applicationContext as FonebrewApp).container.sessionStore

    val conversations by viewModel.conversations.collectAsState()
    val imageNodes by viewModel.imageNodes.collectAsState()
    val threadChains by viewModel.threadChains.collectAsState()
    val state by viewModel.uiState.collectAsState()
    // THREAD_TOPOLOGY_PLAN.md WP6's "spawned from …" chip: a rootId -> lineage-pointer map
    // flattened from every chain's links (no new store — [ChatViewModel.threadChains] already
    // combines the same [Conversations.Summary] list this room renders with the marker data),
    // plus a title lookup so the chip can name the source conversation, not just say "a prior
    // conversation" when the source is still around to be named.
    val lineageByRoot = remember(threadChains) {
        threadChains.flatMap { it.links }.mapNotNull { link -> link.lineage?.let { link.rootId to it } }.toMap()
    }
    val titleByRoot = remember(conversations) { conversations.associate { it.rootId to it.title } }
    val bookmarked by session.bookmarkedRoots.collectAsState()
    val projects by session.conversationProjects.collectAsState()
    val opens by session.conversationOpens.collectAsState()
    var tab by remember { mutableStateOf(ChatsTab.ALL) }
    var sort by remember { mutableStateOf(ConvSort.RECENT) }
    var projectDialogFor by remember { mutableStateOf<Conversations.Summary?>(null) }

    // Reorder a list of tree summaries by the chosen sort, through the JVM-tested library path:
    // project each summary into the library model (carrying the honest open count / branch count),
    // sort, then map the order back onto the tree summaries the cards render. RECENT is the
    // default and matches the list's natural newest-first order, so this is purely additive.
    fun sorted(list: List<Conversations.Summary>): List<Conversations.Summary> {
        if (sort == ConvSort.RECENT) return list
        val projected = list.map { s ->
            ConversationProjection.from(s, s.rootId in bookmarked, projects[s.rootId], opens[s.rootId] ?: 0)
        }
        val order = LibConversations.sort(projected, sort)
        val byId = list.associateBy { it.rootId }
        return order.mapNotNull { byId[it.id] }
    }

    Box(Modifier.fillMaxSize().background(c.ink)) {
        Column(Modifier.fillMaxSize()) {
            HyleTitle("Chats")
            SearchEntryPill(onClick = onOpenSearch)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatsTab.entries.forEach { t -> HyleChip(tab == t, { tab = t }, t.label) }
            }

            // Sort control (Doc 02): backed by the tested LibConversations.sort. Hidden on the
            // Image tab (image turns are browsed newest-first) and Projects (grouped by its own
            // most-recent order), where a conversation sort wouldn't apply.
            if (tab != ChatsTab.IMAGE && tab != ChatsTab.PROJECTS) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sort", style = MaterialTheme.typography.labelMedium, color = c.textMid)
                    SORT_LABELS.forEach { (key, label) -> HyleChip(sort == key, { sort = key }, label) }
                }
            }

            val activeIds = state.steps.map { it.node.id }.toSet()
            val firstNodeId = state.steps.firstOrNull()?.node?.id
            fun listProps(list: List<Conversations.Summary>, empty: String) = ConversationListProps(
                conversations = list, emptyMessage = empty, activeIds = activeIds, firstNodeId = firstNodeId,
                bookmarked = bookmarked, projects = projects, enabled = !state.isGenerating,
                generating = state.isGenerating,
                lineageByRoot = lineageByRoot, titleByRoot = titleByRoot,
                // "Watched" is the model's own flag (binding rule 2), not a guess from its id.
                generatingWatched = state.models.firstOrNull { it.id == state.activeModelId }?.watched == true,
                onOpen = { viewModel.openConversation(it.rootId); onClose() },
                onToggleBookmark = { session.toggleBookmark(it.rootId) },
                onSetProject = { projectDialogFor = it },
                onOpenSource = { srcRootId -> viewModel.openConversation(srcRootId); onClose() },
            )
            when (tab) {
                ChatsTab.IMAGE -> ImageList(imageNodes, !state.isGenerating) {
                    viewModel.branchFrom(it.id); onClose()
                }
                ChatsTab.PROJECTS -> ProjectGroupedList(listProps(conversations, ""), projects)
                else -> {
                    // Doc §6.4: "the existing tabs become presets" — Starred/Text are genuinely
                    // boolean facet filters over data already in scope, so they're evaluated
                    // through the shared query-language pipeline (ConversationFacetFilter)
                    // instead of bespoke per-tab logic. Provably identical output to the old
                    // Bookmarks.filter(...)/!it.hasImage checks — see ConversationFacetFilterTest.
                    val list = when (tab) {
                        ChatsTab.STARRED -> conversations.filterByPreset(ChatsPreset.STARRED, bookmarked, projects)
                        ChatsTab.TEXT -> conversations.filterByPreset(ChatsPreset.TEXT, bookmarked, projects)
                        else -> conversations
                    }
                    val empty = when (tab) {
                        ChatsTab.STARRED -> "No starred chats yet. Tap the star on a conversation to keep it here."
                        ChatsTab.TEXT -> "No text-only conversations yet."
                        else -> "No conversations yet. Start one — every turn becomes a node on the tree, " +
                            "and every fork stays visible."
                    }
                    ConversationList(listProps(sorted(list), empty))
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

/** Evaluates [preset]'s query through [ConversationFacetFilter] — see the call site's comment
 *  in [ChatsRoom] for why only Starred/Text (genuine boolean filters) go through this path. */
private fun List<Conversations.Summary>.filterByPreset(
    preset: ChatsPreset,
    bookmarked: Set<String>,
    projects: Map<String, String>,
): List<Conversations.Summary> {
    val root = QueryParser.parse(preset.query).root
    return filter { ConversationFacetFilter.matches(it, it.rootId in bookmarked, projects[it.rootId], root) }
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
    /** THREAD_TOPOLOGY_PLAN.md WP6: this conversation's Fork/Spawn source, if any (rootId ->
     *  lineage pointer), and a rootId -> title lookup to name that source when it's known. */
    val lineageByRoot: Map<String, ThreadChains.LineagePointer> = emptyMap(),
    val titleByRoot: Map<String, String> = emptyMap(),
    /** True while a turn is generating into the open conversation. */
    val generating: Boolean,
    /** True when the model producing that turn is a **watched** (cloud) one — the only
     *  case Hyle's motion rule lets breathe. */
    val generatingWatched: Boolean,
    val onOpen: (Conversations.Summary) -> Unit,
    val onToggleBookmark: (Conversations.Summary) -> Unit,
    val onSetProject: (Conversations.Summary) -> Unit,
    /** Fixed per audit: the "Spawned/Forked from: …" chip used to name its source with no way to
     *  jump there — tapping it did the same thing as tapping anywhere else on the card (opened
     *  THIS conversation). Navigates straight to [ThreadChains.LineagePointer.srcRootId]. */
    val onOpenSource: (String) -> Unit = {},
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
            // Leading state marker — branch glyph / filled dot / hollow ring, breathing
            // only while a watched cloud model is generating into THIS conversation.
            ConversationMarker(
                mark = when {
                    conv.branchCount > 1 -> ConversationMark.BRANCHED
                    conv.nodeCount <= 1 -> ConversationMark.EMPTY
                    else -> ConversationMark.LINEAR
                },
                pulse = when {
                    !active || !p.generating -> ConversationPulse.NONE
                    p.generatingWatched -> ConversationPulse.WATCHED
                    else -> ConversationPulse.LOCAL
                },
                active = active,
            )
            Spacer(Modifier.width(12.dp))
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
                // THREAD_TOPOLOGY_PLAN.md WP6: "spawned from …" — this card's own lineage, not
                // the whole chain (that's TreeRoom's "Chain" chip); a source that's still around
                // is named, a deleted one still says how this conversation began rather than
                // pretending it has no history.
                p.lineageByRoot[conv.rootId]?.let { lineage ->
                    val verb = if (lineage.lineageKind == TreeFork.LineageKind.SPAWN) "Spawned" else "Forked"
                    val srcTitle = p.titleByRoot[lineage.srcRootId]
                    // Fixed per audit: its own clickable (nested inside the outer Card's) so a tap
                    // here jumps to the named source instead of just reopening this conversation —
                    // an inner clickable consumes the tap before the outer Card's ever sees it.
                    Text(
                        if (srcTitle != null) "$verb from: $srcTitle" else "$verb from a prior conversation",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.violet,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable(enabled = p.enabled) { p.onOpenSource(lineage.srcRootId) }
                            .semantics { contentDescription = "Open source conversation: ${srcTitle ?: "a prior conversation"}" },
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
                HyleField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Project name",
                    singleLine = true,
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

// Relative time through the JVM-tested LocaleFormat: "just now / N minutes ago / …" with
// locale-localized digits, falling back to the locale's absolute date past a week.
internal fun relativeTime(millis: Long): String =
    dev.fonebrew.domain.format.LocaleFormat.relativeOrAbsolute(
        epochMillis = millis,
        nowMillis = System.currentTimeMillis(),
        zone = java.time.ZoneId.systemDefault(),
        locale = java.util.Locale.getDefault(),
    )
