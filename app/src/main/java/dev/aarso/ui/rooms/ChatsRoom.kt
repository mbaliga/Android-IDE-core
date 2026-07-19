@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.aarso.ui.rooms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import dev.aarso.AarsoApp
import dev.aarso.domain.MessageNode
import dev.aarso.domain.library.ConvSort
import dev.aarso.domain.library.ConversationProjection
import dev.aarso.domain.tree.Bookmarks
import dev.aarso.domain.tree.Conversations
import dev.aarso.ui.ChatViewModel
import dev.aarso.domain.library.Conversations as LibConversations
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.hyle.HyleCard
import dev.aarso.ui.hyle.HyleChip
import dev.aarso.ui.hyle.HyleTitle
import dev.aarso.ui.hyle.FileImage
import dev.aarso.ui.theme.LocalHyleColors

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
) {
    val c = LocalHyleColors.current
    val context = LocalContext.current
    val session = (context.applicationContext as AarsoApp).container.sessionStore

    val conversations by viewModel.conversations.collectAsState()
    val imageNodes by viewModel.imageNodes.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val bookmarked by session.bookmarkedRoots.collectAsState()
    val projects by session.conversationProjects.collectAsState()
    val opens by session.conversationOpens.collectAsState()
    var tab by remember { mutableStateOf(ChatsTab.ALL) }
    var sort by remember { mutableStateOf(ConvSort.RECENT) }
    // Filters (the Sort row) default to collapsed — a funnel icon on the tab bar reveals them,
    // decoupling the always-visible category tabs from tucked-away sort options.
    var sortExpanded by remember { mutableStateOf(false) }
    var projectDialogFor by remember { mutableStateOf<Conversations.Summary?>(null) }
    // Image nodes aren't conversations — they key their own star/project state off the node's
    // own id (session.bookmarkedRoots/conversationProjects are plain String-keyed maps, not
    // strictly root-only), so this dialog is separate from projectDialogFor above.
    var imageProjectDialogFor by remember { mutableStateOf<MessageNode?>(null) }

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
            val sortEligible = tab != ChatsTab.IMAGE && tab != ChatsTab.PROJECTS
            ChatsTabBar(
                selected = tab,
                onSelect = { tab = it },
                sortExpanded = sortExpanded,
                showFilterToggle = sortEligible,
                onToggleSort = { sortExpanded = !sortExpanded },
            )

            // Sort control (Doc 02): backed by the tested LibConversations.sort. Hidden on the
            // Image tab (image turns are browsed newest-first) and Projects (grouped by its own
            // most-recent order), where a conversation sort wouldn't apply — and, per the owner's
            // "filters behind an icon" request, collapsed by default behind the tab bar's funnel.
            if (sortEligible && sortExpanded) {
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
                onOpen = { viewModel.openConversation(it.rootId); onClose() },
                onToggleBookmark = { session.toggleBookmark(it.rootId) },
                onSetProject = { projectDialogFor = it },
            )
            when (tab) {
                ChatsTab.IMAGE -> ImageList(
                    imageNodes = imageNodes,
                    bookmarked = bookmarked,
                    projects = projects,
                    enabled = !state.isGenerating,
                    onOpen = { viewModel.branchFrom(it.id); onClose() },
                    onToggleBookmark = { session.toggleBookmark(it.id) },
                    onSetProject = { imageProjectDialogFor = it },
                )
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
        imageProjectDialogFor?.let { node ->
            ProjectDialog(
                current = projects[node.id].orEmpty(),
                existing = projects.values.distinct().sorted(),
                onDismiss = { imageProjectDialogFor = null },
                onSet = { session.setConversationProject(node.id, it); imageProjectDialogFor = null },
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

/**
 * The Chats-room tab bar, styled to match [SettingsRoom]'s [SettingsTabBar]: five fixed-width
 * tabs (a hand-drawn glyph + label + underline indicator), not a scrolling chip row, plus a
 * trailing funnel icon that reveals the Sort row (the owner's "filters behind an icon" request).
 */
@Composable
private fun ChatsTabBar(
    selected: ChatsTab,
    onSelect: (ChatsTab) -> Unit,
    sortExpanded: Boolean,
    showFilterToggle: Boolean,
    onToggleSort: () -> Unit,
) {
    val c = LocalHyleColors.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatsTab.entries.forEach { t ->
                val on = t == selected
                val tint = if (on) c.violet else c.textMid
                Column(
                    modifier = Modifier.weight(1f).clickable { onSelect(t) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ChatsTabGlyph(t, tint)
                    Spacer(Modifier.height(5.dp))
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.height(2.dp).width(22.dp).background(if (on) c.violet else Color.Transparent))
                }
            }
            // Always reserve this 36dp slot — even when the funnel isn't shown — so the five
            // weighted tabs ahead of it never resize between tabs. A conditionally-present
            // sibling in this Row was the "tab bar fluctuates" bug: Compose redistributes the
            // weighted tabs' width whenever this Box enters/leaves the tree, which reads as the
            // whole bar jittering every time the Image/Projects tab (no sort) is selected.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .alpha(if (showFilterToggle) 1f else 0f)
                    .clip(CircleShape)
                    .clickable(enabled = showFilterToggle, onClick = onToggleSort)
                    .semantics {
                        contentDescription = if (sortExpanded) "Hide sort options" else "Show sort options"
                    },
                contentAlignment = Alignment.Center,
            ) {
                FilterGlyph(if (sortExpanded) c.violet else c.textMid)
            }
        }
        HorizontalDivider()
    }
}

/** Small hand-drawn line glyphs for the Chats tabs — same technique as Settings' TabGlyph. */
@Composable
private fun ChatsTabGlyph(tab: ChatsTab, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height
        val sw = w * 0.09f
        val stroke = Stroke(width = sw)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
            drawLine(tint, Offset(x0, y0), Offset(x1, y1), strokeWidth = sw)
        when (tab) {
            ChatsTab.ALL -> {
                // A stack of 3 lines of differing width — "everything", not one fixed shape.
                line(w * 0.16f, h * 0.28f, w * 0.68f, h * 0.28f)
                line(w * 0.16f, h * 0.50f, w * 0.84f, h * 0.50f)
                line(w * 0.16f, h * 0.72f, w * 0.56f, h * 0.72f)
            }
            ChatsTab.TEXT -> {
                line(w * 0.16f, h * 0.30f, w * 0.84f, h * 0.30f)
                line(w * 0.16f, h * 0.50f, w * 0.72f, h * 0.50f)
                line(w * 0.16f, h * 0.70f, w * 0.80f, h * 0.70f)
            }
            ChatsTab.IMAGE -> {
                drawRoundRect(
                    tint, topLeft = Offset(w * 0.10f, h * 0.18f),
                    size = Size(w * 0.80f, h * 0.64f),
                    cornerRadius = CornerRadius(w * 0.10f), style = stroke,
                )
                drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.34f, h * 0.38f))
                val p = Path().apply {
                    moveTo(w * 0.16f, h * 0.74f); lineTo(w * 0.42f, h * 0.50f)
                    lineTo(w * 0.60f, h * 0.66f); lineTo(w * 0.72f, h * 0.56f); lineTo(w * 0.84f, h * 0.74f)
                }
                drawPath(p, tint, style = stroke)
            }
            ChatsTab.STARRED -> {
                val cx = w * 0.5f; val cy = h * 0.54f
                val outerR = w * 0.42f; val innerR = outerR * 0.42f
                val star = Path().apply {
                    for (i in 0 until 10) {
                        val r = if (i % 2 == 0) outerR else innerR
                        val a = -Math.PI / 2.0 + i * Math.PI / 5.0
                        val x = cx + (r * cos(a)).toFloat()
                        val y = cy + (r * sin(a)).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(star, tint, style = stroke)
            }
            ChatsTab.PROJECTS -> {
                // A folder-tab outline: a body rect with a small raised tab at top-left.
                val p = Path().apply {
                    moveTo(w * 0.12f, h * 0.30f)
                    lineTo(w * 0.40f, h * 0.30f)
                    lineTo(w * 0.48f, h * 0.20f)
                    lineTo(w * 0.88f, h * 0.20f)
                    lineTo(w * 0.88f, h * 0.78f)
                    lineTo(w * 0.12f, h * 0.78f)
                    close()
                }
                drawPath(p, tint, style = stroke)
            }
        }
    }
}

/** Funnel glyph for the "filters hidden behind an icon" affordance. */
@Composable
private fun FilterGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w * 0.10f, h * 0.16f)
            lineTo(w * 0.90f, h * 0.16f)
            lineTo(w * 0.58f, h * 0.54f)
            lineTo(w * 0.58f, h * 0.86f)
            lineTo(w * 0.42f, h * 0.74f)
            lineTo(w * 0.42f, h * 0.54f)
            close()
        }
        drawPath(p, tint, style = Stroke(width = w * 0.12f))
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

/**
 * Projects view: conversations grouped by their assigned project, Unassigned last — styled as
 * tactile "hanging folder" tabs (per the owner's Hyle demo reference): each group renders as one
 * physical [FolderBody] — a single bordered container (the folder header, label + chat count)
 * enclosing its numbered, slightly staggered tab rows. The open conversation renders as a solid
 * violet-filled tab; the rest are outlined/ghost, same as every other list's active state.
 */
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp),
    ) {
        ordered.forEach { label ->
            val members = groups[label].orEmpty()
            item(key = "folder-${label ?: "_unassigned"}") {
                FolderBody(label = label, members = members, p = p)
            }
        }
    }
}

/**
 * A single project's "hanging folder" body: a bordered, rounded container — the folder itself —
 * enclosing its header (label + chat count) and numbered [FolderTabRow]s, so a group reads as one
 * physical folder rather than a bare header-plus-list (the fidelity gap the plain header/rows
 * layout left open against the owner's Hyle demo reference).
 */
@Composable
private fun FolderBody(label: String?, members: List<Conversations.Summary>, p: ConversationListProps) {
    val c = LocalHyleColors.current
    HyleCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (label ?: "Unassigned").uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = c.violet,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${members.size} ${if (members.size == 1) "chat" else "chats"}",
                style = MaterialTheme.typography.labelSmall,
                color = c.textMid,
            )
        }
        members.forEachIndexed { idx, conv ->
            FolderTabRow(p, conv, idx + 1)
            if (idx != members.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * A single "hanging folder tab" row: a small left-edge tab-flag flourish, then a numbered index
 * prefix ahead of a lean, single-line title + timestamp (the reference's rows are just an index
 * and a title — deliberately NOT the full metadata block the flat list shows, since cramming
 * two 44dp icon buttons into a small tab is exactly what stops a row from reading as "a filed
 * tab" and turns it back into "a card"). Star/assign-to-project move to long-press, matching the
 * long-press-for-actions language chat turns already use ([dev.aarso.ui.ChatScreen]'s
 * TurnActionsSheet) instead of inventing a second pattern. Alternates a slight left/right inset
 * per row (the reference's loose brick pattern). The open conversation is a solid violet fill
 * (flag included); everything else is outlined/ghost (raised fill + hairline border, outline
 * flag), matching the rest of this room's active-state language.
 */
@Composable
private fun FolderTabRow(p: ConversationListProps, conv: Conversations.Summary, index: Int) {
    val c = LocalHyleColors.current
    val active = conv.latestLeafId in p.activeIds || p.firstNodeId == conv.rootId
    val shape = RoundedCornerShape(10.dp)
    val leaning = index % 2 == 0
    var showActions by remember { mutableStateOf(false) }
    val haptics = dev.aarso.ui.hyle.rememberHyleHaptics()
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(
            start = if (leaning) 18.dp else 0.dp,
            end = if (leaning) 0.dp else 18.dp,
        ),
    ) {
        // A small hanging-folder "tab flag" on the row's leading edge — the reference's tab-flag
        // flourish along the folder body's left edge.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(5.dp)
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(if (active) c.violet else c.outline),
        )
        Spacer(Modifier.width(4.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .then(
                    if (active) {
                        Modifier.background(c.violet, shape)
                    } else {
                        Modifier.background(c.raised, shape).border(1.dp, c.hairline, shape)
                    },
                )
                .combinedClickable(
                    enabled = p.enabled,
                    onClick = { p.onOpen(conv) },
                    onLongClick = { haptics.tap(); showActions = true },
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "%02d".format(index),
                style = MaterialTheme.typography.labelSmall,
                color = if (active) c.onViolet.copy(alpha = 0.7f) else c.textMid,
                modifier = Modifier.padding(end = 10.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    conv.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (active) c.onViolet else c.textHigh,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    relativeTime(conv.lastUpdatedAt) + "  ·  ${conv.nodeCount} turns",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) c.onViolet.copy(alpha = 0.75f) else c.textMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (conv.rootId in p.bookmarked) {
                Text(
                    "★",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (active) c.onViolet else c.violet,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
    if (showActions) {
        ConversationActionsSheet(
            title = conv.title,
            bookmarked = conv.rootId in p.bookmarked,
            onToggleBookmark = { p.onToggleBookmark(conv); showActions = false },
            onSetProject = { p.onSetProject(conv); showActions = false },
            onDismiss = { showActions = false },
        )
    }
}

/**
 * Long-press actions shared by every entry in this room — star and project-assignment, off the
 * row/card itself so the default list stays a lean title+time line. Used by [FolderTabRow],
 * [ConversationCard], and [ImageNodeCard] alike (generalized off a single [title] rather than a
 * [Conversations.Summary] so the Image tab's [dev.aarso.domain.MessageNode] rows can share it).
 */
@Composable
private fun ConversationActionsSheet(
    title: String,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onSetProject: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            androidx.compose.material3.TextButton(onClick = onToggleBookmark, modifier = Modifier.fillMaxWidth()) {
                Text(if (bookmarked) "☆ Remove star" else "★ Star")
            }
            androidx.compose.material3.TextButton(onClick = onSetProject, modifier = Modifier.fillMaxWidth()) {
                Text("⊞ Assign to a project")
            }
        }
    }
}

@Composable
private fun ImageList(
    imageNodes: List<MessageNode>,
    bookmarked: Set<String>,
    projects: Map<String, String>,
    enabled: Boolean,
    onOpen: (MessageNode) -> Unit,
    onToggleBookmark: (MessageNode) -> Unit,
    onSetProject: (MessageNode) -> Unit,
) {
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
            ImageNodeCard(
                node = node,
                enabled = enabled,
                bookmarked = node.id in bookmarked,
                project = projects[node.id],
                onOpen = { onOpen(node) },
                onToggleBookmark = { onToggleBookmark(node) },
                onSetProject = { onSetProject(node) },
            )
        }
    }
}

@Composable
private fun ConversationCard(p: ConversationListProps, conv: Conversations.Summary) {
    val c = LocalHyleColors.current
    val active = conv.latestLeafId in p.activeIds || p.firstNodeId == conv.rootId
    var showActions by remember { mutableStateOf(false) }
    val haptics = dev.aarso.ui.hyle.rememberHyleHaptics()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = p.enabled,
                onClick = { p.onOpen(conv) },
                onLongClick = { haptics.tap(); showActions = true },
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = BorderStroke(1.dp, c.hairline),
    ) {
        Box(Modifier.padding(14.dp)) {
            ConversationCardContent(p, conv)
        }
    }
    if (showActions) {
        ConversationActionsSheet(
            title = conv.title,
            bookmarked = conv.rootId in p.bookmarked,
            onToggleBookmark = { p.onToggleBookmark(conv); showActions = false },
            onSetProject = { p.onSetProject(conv); showActions = false },
            onDismiss = { showActions = false },
        )
    }
}

/**
 * [ConversationCard]'s row content — title, project tag, relative time, turn count, and a
 * passive star indicator (no longer a tap target: star/project-assign now live in the
 * long-press [ConversationActionsSheet], matching [FolderTabRow]'s pattern). [FolderTabRow]
 * builds its own row content rather than calling this one — a tab row and a flat card read
 * differently enough that sharing this function would fight both layouts — but they share the
 * same long-press sheet. [titleColor]/[mutedColor]/[accentColor] remain overridable for a future
 * non-default caller; [ConversationCard] is the only caller today and uses the defaults.
 */
@Composable
private fun ConversationCardContent(
    p: ConversationListProps,
    conv: Conversations.Summary,
    titleColor: Color = Color.Unspecified,
    mutedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accentColor: Color = LocalHyleColors.current.violet,
) {
    val bookmarked = conv.rootId in p.bookmarked
    val project = p.projects[conv.rootId]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                conv.title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (project != null) {
                Text(
                    "▸ $project",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    relativeTime(conv.lastUpdatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColor,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${conv.nodeCount} turns",
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColor,
                )
                if (conv.modelIds.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        conv.modelIds.joinToString(" · ") { it.substringAfter(':') },
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (bookmarked) {
            Text(
                "★",
                style = MaterialTheme.typography.titleSmall,
                color = accentColor,
                modifier = Modifier.padding(start = 6.dp),
            )
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

/** A long-press reveals [ConversationActionsSheet] (star + assign-to-project), keyed off this
 *  image turn's own node id — image nodes aren't grouped into a conversation summary here, so
 *  they can't key off a rootId the way [ConversationCard]/[FolderTabRow] do. */
@Composable
private fun ImageNodeCard(
    node: MessageNode,
    enabled: Boolean,
    bookmarked: Boolean,
    project: String?,
    onOpen: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSetProject: () -> Unit,
) {
    val c = LocalHyleColors.current
    var showActions by remember { mutableStateOf(false) }
    val haptics = dev.aarso.ui.hyle.rememberHyleHaptics()
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            enabled = enabled,
            onClick = onOpen,
            onLongClick = { haptics.tap(); showActions = true },
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, c.hairline),
    ) {
        Column(Modifier.padding(10.dp)) {
            node.metadata[Conversations.IMAGE_KEY]?.let {
                FileImage(it, Modifier.fillMaxWidth().heightIn(max = 220.dp))
            }
            if (project != null) {
                Text(
                    "▸ $project",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.violet,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    relativeTime(node.createdAt) + (node.modelId?.let { " · ${it.substringAfter(':')}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).weight(1f),
                )
                if (bookmarked) {
                    Text("★", style = MaterialTheme.typography.titleSmall, color = c.violet)
                }
            }
        }
    }
    if (showActions) {
        ConversationActionsSheet(
            title = relativeTime(node.createdAt),
            bookmarked = bookmarked,
            onToggleBookmark = { onToggleBookmark(); showActions = false },
            onSetProject = { onSetProject(); showActions = false },
            onDismiss = { showActions = false },
        )
    }
}

// Relative time through the JVM-tested LocaleFormat: "just now / N minutes ago / …" with
// locale-localized digits, falling back to the locale's absolute date past a week.
internal fun relativeTime(millis: Long): String =
    dev.aarso.domain.format.LocaleFormat.relativeOrAbsolute(
        epochMillis = millis,
        nowMillis = System.currentTimeMillis(),
        zone = java.time.ZoneId.systemDefault(),
        locale = java.util.Locale.getDefault(),
    )
