@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.fonebrew.ui.rooms

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.distinctUntilChanged
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
import dev.aarso.hyle.component.HyleContextMenu
import dev.aarso.hyle.component.HyleField
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleCard
import dev.aarso.hyle.cells.HyleChip
import dev.aarso.hyle.cells.HyleTitle
import dev.aarso.hyle.cells.FileImage
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.ui.search.SearchEntryPill

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

    val universalTabBarPosition by session.tabBarPosition.collectAsState()
    val roomTabBarOverrides by session.roomTabBarPosition.collectAsState()
    val tabBarPosition = roomTabBarOverrides["chats"] ?: universalTabBarPosition
    val sortEligible = tab != ChatsTab.IMAGE && tab != ChatsTab.PROJECTS

    // The tab bar and its Sort row travel together, top or bottom, per the owner's layout
    // preference (Settings → Global → Tab bar position, or a per-room override).
    val tabBarBlock: @Composable () -> Unit = {
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
    }

    val contentBlock: @Composable () -> Unit = {
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

    Box(Modifier.fillMaxSize().background(c.ink)) {
        Column(Modifier.fillMaxSize()) {
            HyleTitle("Chats")
            SearchEntryPill(onClick = onOpenSearch)
            if (tabBarPosition == "BOTTOM") {
                Box(Modifier.weight(1f)) { contentBlock() }
                tabBarBlock()
            } else {
                tabBarBlock()
                Box(Modifier.weight(1f)) { contentBlock() }
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

// One HyleTabSpec per ChatsTab, glyphs unchanged from the original hand-rolled ChatsTabGlyph —
// this bar now consumes the shared HyleTabBar (Aeon.kt) instead of keeping its own parallel copy
// (2026-07-19 tab-bar consolidation).
private val ChatsTabSpecs: List<dev.aarso.hyle.cells.HyleTabSpec> = ChatsTab.entries.map { tab ->
    dev.aarso.hyle.cells.HyleTabSpec(tab.label) { tint ->
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

/**
 * The Chats-room tab bar: the shared [dev.aarso.hyle.cells.HyleTabBar], plus a trailing funnel icon
 * (via [dev.aarso.hyle.cells.HyleTabBar]'s own `trailing` slot) that reveals the Sort row (the
 * owner's "filters behind an icon" request). The 36dp slot stays reserved even when the funnel
 * isn't interactive — a conditionally-present trailing composable was the original "tab bar
 * fluctuates" bug (Compose redistributes the weighted tabs' width whenever a sibling enters/
 * leaves the tree); `alpha`+`enabled = false` keeps the space without the jitter.
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
    dev.aarso.hyle.cells.HyleTabBar(
        tabs = ChatsTabSpecs,
        selected = ChatsTab.entries.indexOf(selected),
        onSelect = { onSelect(ChatsTab.entries[it]) },
        trailing = {
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
        },
    )
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

/** Shared "is this the open conversation" predicate — the same test [FolderTabRow] inlines,
 *  hoisted here so [ConversationCard] and its threading connector can share one source of truth. */
private fun ConversationListProps.isActive(conv: Conversations.Summary): Boolean =
    conv.latestLeafId in activeIds || firstNodeId == conv.rootId

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
    val listState = rememberLazyListState()
    val haptics = dev.aarso.hyle.cells.rememberHyleHaptics()
    // The design kit's "haptic effect on scroll": one light tick per row scrolled past, driven
    // off the first-visible-item index (not raw scroll delta) so it fires once per threshold
    // crossing rather than continuously while dragging.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { haptics.tap() }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp),
    ) {
        itemsIndexed(p.conversations, key = { _, conv -> conv.rootId }) { idx, conv ->
            ConversationCard(p, conv, index = idx + 1)
            if (idx != p.conversations.lastIndex) {
                ConversationRowConnector()
            }
        }
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
 * tab" and turns it back into "a card"). Long-press opens the same desktop-class-kit
 * [HyleContextMenu] every row in this room shares ([conversationCardMenuItems]) — star,
 * assign-to-project, and (MERGE-NOTE: reconciling the dev line's per-card context menu with the
 * launch line's "actions on long-press" ask — a single mechanism instead of two competing
 * long-press affordances) "open source conversation" when this row carries WP6 lineage, even
 * though the lineage chip itself stays off this deliberately lean row. Alternates a slight
 * left/right inset per row (the reference's loose brick pattern). The open conversation is a
 * solid violet fill (flag included); everything else is outlined/ghost (raised fill + hairline
 * border, outline flag), matching the rest of this room's active-state language.
 */
@Composable
private fun FolderTabRow(p: ConversationListProps, conv: Conversations.Summary, index: Int) {
    val c = LocalHyleColors.current
    val active = conv.latestLeafId in p.activeIds || p.firstNodeId == conv.rootId
    val lineage = p.lineageByRoot[conv.rootId]
    val shape = RoundedCornerShape(10.dp)
    val leaning = index % 2 == 0
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = dev.aarso.hyle.cells.rememberHyleHaptics()
    Box(Modifier.fillMaxWidth()) {
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
                        onLongClick = { haptics.tap(); menuOpen = true },
                    )
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Open conversation actions") { menuOpen = true; true },
                        )
                    }
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
        if (menuOpen) {
            HyleContextMenu(
                expanded = true,
                onDismissRequest = { menuOpen = false },
                items = conversationCardMenuItems(bookmarked = conv.rootId in p.bookmarked, hasLineageSource = lineage != null),
                onItemClick = { id ->
                    when (id) {
                        "open" -> p.onOpen(conv)
                        "toggle_star" -> p.onToggleBookmark(conv)
                        "assign_project" -> p.onSetProject(conv)
                        "open_source" -> lineage?.let { p.onOpenSource(it.srcRootId) }
                    }
                },
            )
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

/**
 * The plain divider threading between two consecutive flat-list rows — "simple dividers instead
 * of boxes" (owner ask). [ConversationCard] itself now carries the tab identity (clipped to
 * [dev.aarso.hyle.cells.HyleFieldShape], the same "/" file-tab silhouette as a nav chip), so this
 * connector no longer needs its own accent bar — the row's slant already reads as a tab without
 * a redundant flag beside it.
 */
@Composable
private fun ConversationRowConnector() {
    val c = LocalHyleColors.current
    HorizontalDivider(color = c.hairline, modifier = Modifier.padding(vertical = 5.dp))
}

/**
 * The flat "All conversations" row — a genuine file-tab silhouette
 * ([dev.aarso.hyle.cells.HyleFieldShape], the exact "/" shape a nav chip clips to), not a boxed
 * [Card] and not [FolderTabRow]'s separate flag-bar-beside-a-rounded-rect (that read as "a card
 * with a colored stripe," not a tab — owner-flagged twice). The slant IS the tab; active state is
 * a solid violet fill of that same shape, exactly like an active nav chip. A leading
 * [ConversationMarker] carries the tree-topology glyph (branch/linear/empty) plus Hyle's
 * watched-cloud-generation breathe (THREAD_TOPOLOGY_PLAN.md / binding rule 2), ahead of the row's
 * own numbered index. Long-press opens the desktop-class-kit [HyleContextMenu]
 * ([conversationCardMenuItems]) — Open / Star / Assign to project, plus "Open source
 * conversation" when [ConversationListProps.lineageByRoot] carries this row's WP6 lineage; the
 * "Spawned/Forked from: …" text below the title is its own tap target straight to
 * [ConversationListProps.onOpenSource].
 */
@Composable
private fun ConversationCard(p: ConversationListProps, conv: Conversations.Summary, index: Int) {
    val c = LocalHyleColors.current
    val active = p.isActive(conv)
    val shape = dev.aarso.hyle.cells.HyleFieldShape
    val lineage = p.lineageByRoot[conv.rootId]
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = dev.aarso.hyle.cells.rememberHyleHaptics()
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
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
                    onLongClick = { haptics.tap(); menuOpen = true },
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Open conversation actions") { menuOpen = true; true },
                    )
                }
                .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                "%02d".format(index),
                style = MaterialTheme.typography.labelSmall,
                color = if (active) c.onViolet.copy(alpha = 0.7f) else c.textMid,
                modifier = Modifier.padding(end = 10.dp),
            )
            Box(Modifier.weight(1f)) {
                ConversationCardContent(
                    p, conv,
                    titleColor = if (active) c.onViolet else c.textHigh,
                    mutedColor = if (active) c.onViolet.copy(alpha = 0.75f) else c.textMid,
                    accentColor = if (active) c.onViolet else c.violet,
                )
            }
        }
        if (menuOpen) {
            HyleContextMenu(
                expanded = true,
                onDismissRequest = { menuOpen = false },
                items = conversationCardMenuItems(bookmarked = conv.rootId in p.bookmarked, hasLineageSource = lineage != null),
                onItemClick = { id ->
                    when (id) {
                        "open" -> p.onOpen(conv)
                        "toggle_star" -> p.onToggleBookmark(conv)
                        "assign_project" -> p.onSetProject(conv)
                        "open_source" -> lineage?.let { p.onOpenSource(it.srcRootId) }
                    }
                },
            )
        }
    }
}

/**
 * [ConversationCard]'s row content — title, project tag, the WP6 "spawned/forked from …" lineage
 * line (its own tap target to [ConversationListProps.onOpenSource]), relative time, turn count,
 * and a passive star indicator (star/project-assign live in the long-press [HyleContextMenu],
 * matching [FolderTabRow]'s pattern). [FolderTabRow] builds its own row content rather than
 * calling this one — a tab row and a flat card read differently enough that sharing this function
 * would fight both layouts — but they share the same long-press menu. [titleColor]/[mutedColor]/
 * [accentColor] default to the plain-surface palette; [ConversationCard] overrides them to the
 * violet-fill/on-violet pair on its active row, same as [FolderTabRow]'s own active-state colours.
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
    val lineage = p.lineageByRoot[conv.rootId]
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
            // THREAD_TOPOLOGY_PLAN.md WP6: "spawned from …" — this card's own lineage, not
            // the whole chain (that's TreeRoom's "Chain" chip); a source that's still around
            // is named, a deleted one still says how this conversation began rather than
            // pretending it has no history. Its own clickable (nested inside the outer row's)
            // so a tap here jumps to the named source instead of just reopening this
            // conversation — an inner clickable consumes the tap before the outer one sees it.
            if (lineage != null) {
                val verb = if (lineage.lineageKind == TreeFork.LineageKind.SPAWN) "Spawned" else "Forked"
                val srcTitle = p.titleByRoot[lineage.srcRootId]
                Text(
                    if (srcTitle != null) "$verb from: $srcTitle" else "$verb from a prior conversation",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
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

/** Assign or clear a conversation's project; tap an existing label or type a new one. Uses the
 *  desktop-class kit's [HyleField] (not a bare `OutlinedTextField`) — desktop-class-kit.md §2. */
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

/** Long-press opens the shared [HyleContextMenu] ([conversationCardMenuItems]) — star + assign
 *  project — keyed off this image turn's own node id: image nodes aren't grouped into a
 *  conversation summary here, so they can't key off a rootId the way [ConversationCard]/
 *  [FolderTabRow] do, and they never carry WP6 lineage. */
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
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = dev.aarso.hyle.cells.rememberHyleHaptics()
    Box(Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                enabled = enabled,
                onClick = onOpen,
                onLongClick = { haptics.tap(); menuOpen = true },
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
        if (menuOpen) {
            HyleContextMenu(
                expanded = true,
                onDismissRequest = { menuOpen = false },
                items = conversationCardMenuItems(bookmarked = bookmarked, hasLineageSource = false),
                onItemClick = { id ->
                    when (id) {
                        "open" -> onOpen()
                        "toggle_star" -> onToggleBookmark()
                        "assign_project" -> onSetProject()
                    }
                },
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
