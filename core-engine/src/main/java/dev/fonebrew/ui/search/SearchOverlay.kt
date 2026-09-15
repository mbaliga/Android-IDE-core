package dev.fonebrew.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fonebrew.domain.scope.ContextAssembly.AssemblyMode
import dev.fonebrew.domain.search.ExplainField
import dev.fonebrew.domain.search.GraphAdjacentRecall
import dev.fonebrew.domain.search.MatchExplanation
import dev.fonebrew.domain.search.SearchKind
import dev.fonebrew.domain.search.query.Diagnostic
import dev.fonebrew.domain.search.query.unbackedReason
import dev.fonebrew.domain.thread.ThreadGraph
import dev.fonebrew.ui.components.GraphRecallCitationRow
import dev.fonebrew.ui.components.SlashCommand
import dev.fonebrew.ui.components.SlashCommandPopup
import dev.fonebrew.ui.components.reason
import dev.fonebrew.ui.components.uiLabel
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleChip
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.cells.HyleTitle
import dev.aarso.hyle.component.HyleField as DesktopHyleField
import dev.aarso.hyle.theme.LocalHyleColors
import kotlinx.coroutines.launch

/**
 * S1's dormant entry pill: sits above [dev.fonebrew.ui.rooms.ChatsRoom]'s tab row, opens
 * [SearchOverlay] on tap. Deliberately not itself editable — typing only ever happens inside
 * the overlay (S1's "dormant" framing).
 */
@Composable
fun SearchEntryPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHyleColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(c.inset, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", style = MaterialTheme.typography.titleMedium, color = c.textMid)
        Spacer(Modifier.width(10.dp))
        Text("Search conversations", style = MaterialTheme.typography.bodyMedium, color = c.textMid)
    }
}

/**
 * The full-screen search overlay (M3 shippable UI: S2 zero state, S3 as-you-type results, S4
 * facet chips, S5 why-matched, S10 operator hints, S14 index-building/no-results states).
 * Follows the existing Participants/Me full-screen-dialog precedent
 * ([androidx.compose.ui.window.Dialog] with `usePlatformDefaultWidth = false` + a fill-size
 * [Surface]) rather than inventing a new overlay pattern.
 *
 * ### The syntax help is concealed, not removed (owner ask)
 * You always arrive here with an empty box, and the empty box used to answer with a wall of
 * help: an "N conversations indexed" banner, then a hard-coded six-row operator cheat-sheet,
 * with a full example query (`gradle is:starred "exact phrase" /regex/`) baked into the
 * placeholder for good measure. The grammar is strong and worth teaching — so it moved behind
 * the quiet `?` at the field's trailing edge ([OperatorLegend], collapsed by default) and into
 * the autocomplete rows, which teach the same vocabulary at the moment it's useful. What's left
 * standing in the empty state is what's actually personal: recent and saved searches.
 */
@Composable
fun SearchOverlay(
    viewModel: SearchViewModel,
    /** Called with the conversation to open and the plain text to hand to that chat's find bar
     *  (S9 continuity) — facet syntax stripped, so opening a result for `gradle is:starred`
     *  looks for `gradle` inside the conversation, not the facet. Only ever called for a
     *  [SearchKind.TEXT]/[SearchKind.IMAGE]/[SearchKind.MIXED] hit — see [onOpenLoop]/[onOpenTask]
     *  for the other two result kinds. */
    onOpenConversation: (convId: String, findText: String) -> Unit,
    /** Called with a loop hit's id — the caller's job is opening LoopRoom onto that loop
     *  (`dev.fonebrew.ui.loops.LoopRoom`'s `initialLoopId`). */
    onOpenLoop: (loopId: String) -> Unit = {},
    /** Called with a task hit's id — the caller's job is opening the Project room's To-do floor
     *  scrolled to and highlighting that task (`dev.fonebrew.ui.rooms.ProductRoomFree`'s
     *  `highlightTaskId`). */
    onOpenTask: (taskId: String) -> Unit = {},
    /**
     * Lane A1: obtains a fresh [ThreadGraph] snapshot for the "Related context" action —
     * [dev.fonebrew.ui.ChatViewModel.loadThreadGraph], the exact projector path
     * `ui/graph/GraphRoom.kt` already uses, wired in by [dev.fonebrew.ui.spatial.SpatialRoot]
     * (which already holds the `ChatViewModel` this Dialog itself has no reason to depend on).
     * Never called eagerly — only when a row's "Related context" affordance is actually used, so
     * opening the search overlay itself never pays a graph-projection cost.
     */
    onLoadThreadGraph: suspend () -> ThreadGraph,
    onDismiss: () -> Unit,
) {
    val c = LocalHyleColors.current
    val state by viewModel.uiState.collectAsState()
    var saveDialogOpen by remember { mutableStateOf(false) }
    // Lane A1's "Related context" sheet: which row it's for (null = closed) plus its own small
    // load/error/result state. Local Compose state, same idiom `GraphRoom`'s own Observations
    // panel uses for `observerRemarks` — a transient, on-demand UI concern, not something
    // SearchViewModel needs to own (it never touches the repository/database SearchViewModel is
    // scoped to; the graph snapshot comes from ChatViewModel via [onLoadThreadGraph] instead).
    var relatedContextRow by remember { mutableStateOf<SearchResultsPresenter.ResultRow?>(null) }
    var relatedContextState by remember { mutableStateOf(RelatedContextUiState()) }
    val relatedContextScope = rememberCoroutineScope()
    val openRelatedContext: (SearchResultsPresenter.ResultRow) -> Unit = { row ->
        relatedContextRow = row
        relatedContextState = RelatedContextUiState(loading = true)
        relatedContextScope.launch {
            val outcome = runCatching {
                val graph = onLoadThreadGraph()
                val seeds = GraphAdjacentRecallPresenter.seedsFor(row)
                val recall = GraphAdjacentRecall.expand(seeds, graph, cap = GraphAdjacentRecallPresenter.DEFAULT_CAP)
                GraphAdjacentRecallPresenter.present(recall, graph)
            }
            relatedContextState = outcome.fold(
                onSuccess = { sheet -> RelatedContextUiState(sheet = sheet) },
                onFailure = { e -> RelatedContextUiState(error = e.message ?: "Couldn't load related context") },
            )
        }
    }
    // Both exits are wrapped so the ViewModel sees the two moments a search is actually
    // committed — a result opened, or the overlay closed on a query that found something. That
    // is what fills `search_history` (and therefore the Recent list) without logging keystrokes.
    val dismiss: () -> Unit = { viewModel.onOverlayClosed(); onDismiss() }
    // One dispatch point for "a result row was tapped (or Ctrl+Enter'd)" — the ViewModel is told
    // regardless of kind (search_history's "what did this search lead to" doesn't care which
    // corpus the opened id belongs to), then the matching typed callback fires.
    val openResult: (SearchResultsPresenter.ResultRow) -> Unit = { row ->
        viewModel.onResultOpened(row.convId)
        when (row.kind) {
            SearchKind.LOOP -> onOpenLoop(row.convId)
            SearchKind.TASK -> onOpenTask(row.convId)
            SearchKind.TEXT, SearchKind.IMAGE, SearchKind.MIXED -> onOpenConversation(row.convId, state.findText)
        }
    }
    // A recalled graph node is always a conversation-tree node (MESSAGE/FORK_ROOT/SPAWN_ROOT/
    // RUN_ROOT/DECISION/…, never LOOP/TASK — those aren't part of ThreadGraph at all), so jumping
    // to one only ever needs the conversation half of [openResult]'s own dispatch — reusing
    // exactly that path (same `onOpenConversation` call, same current find text) rather than a
    // second, parallel "how do I open a thing" implementation.
    val jumpToRecalledNode: (rootId: String) -> Unit = { rootId ->
        viewModel.onResultOpened(rootId)
        onOpenConversation(rootId, state.findText)
        relatedContextRow = null
    }
    // Scoped hardware-keyboard shortcuts (§11.1's reachable-pre-M5 subset, WP13): Esc closes,
    // Ctrl+S saves the current query, Ctrl+Enter opens the top result. Kept local to this
    // Dialog's own composition (a separate Android window from SpatialRoot's), and — unlike
    // that root's Ctrl+K — deliberately not consuming Enter/Tab bare, so typing into the query
    // field is never intercepted. Owner-verified only; no hardware keyboard in this container.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusTarget()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Escape -> { dismiss(); true }
                        event.isCtrlPressed && event.key == Key.S && !state.isZeroState -> {
                            saveDialogOpen = true; true
                        }
                        event.isCtrlPressed && event.key == Key.Enter -> {
                            state.rows.firstOrNull()?.let(openResult)
                            true
                        }
                        else -> false
                    }
                },
            color = c.ink,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HyleTitle("Search", modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .padding(end = 20.dp)
                            .size(32.dp)
                            .clickable(onClick = dismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", style = MaterialTheme.typography.titleMedium, color = c.textMid)
                    }
                }

                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DesktopHyleField(
                            value = state.queryText,
                            onValueChange = viewModel::onQueryChange,
                            // Plain, not a syntax demo. The operators are one tap away at the
                            // trailing edge, and autocomplete offers them while you type.
                            placeholder = "Search conversations",
                            modifier = Modifier.weight(1f),
                        )
                        OperatorsToggle(
                            expanded = state.operatorsExpanded,
                            onToggle = viewModel::toggleOperators,
                        )
                    }
                    if (state.operatorsExpanded) OperatorLegend()
                    if (state.suggestions.isNotEmpty()) {
                        // Reuses the composer's completion popup rather than inventing a second
                        // one: SlashCommand is (name, description, action), which is exactly a
                        // suggestion row — insertion happens in the ViewModel, not in the list.
                        SlashCommandPopup(
                            commands = state.suggestions.map { suggestion ->
                                SlashCommand(suggestion.label, suggestion.detail) {
                                    viewModel.applySuggestion(suggestion)
                                }
                            },
                            onPick = { cmd -> cmd.run() },
                        )
                    }
                    if (state.chips.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Tapping a chip removes that node from the query. These looked like
                            // removable filter chips from day one but shipped with `onClick = {}`.
                            // The ✕ is display-only — chip.text stays the canonical, reparseable
                            // query fragment.
                            state.chips.forEachIndexed { index, chip ->
                                HyleChip(true, { viewModel.removeChip(index) }, chip.text + "  ✕")
                            }
                        }
                    }
                    if (state.hasLossyDisjunction) {
                        Text(
                            "A filter OR'd with text is applied narrowly here — results may be " +
                                "missing. Splitting it into two searches finds everything.",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMid,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    state.diagnostics.forEach { d ->
                        Text(
                            diagnosticMessage(d),
                            style = MaterialTheme.typography.labelSmall,
                            color = c.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // Not errors — these filters work, they just measure something coarser than
                    // their name suggests, so they're said quietly rather than in the error colour.
                    state.facetCaveats.forEach { caveat ->
                        Text(
                            caveat,
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMid,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                when {
                    // Indexing is tested FIRST. It used to sit after isZeroState, and since the
                    // overlay always opens blank, the very first thing a new user saw was the
                    // empty state announcing "0 conversations indexed" — the spinner below was
                    // unreachable until they typed something.
                    state.indexing -> CenteredMessage(indexingMessage(state.indexedCount)) {
                        CircularProgressIndicator(color = c.violet, modifier = Modifier.size(28.dp))
                    }
                    state.isZeroState -> ZeroState(
                        state = state,
                        onApplyQuery = viewModel::applySavedSearch,
                        onDeleteSaved = viewModel::deleteSavedSearch,
                        onClearRecent = viewModel::clearRecentSearches,
                    )
                    state.isNoResults -> NoResults(state = state, onDropFacets = viewModel::dropFacets)
                    else -> ResultsList(state, viewModel::toggleExplain, openResult, openRelatedContext)
                }

                if (!state.isZeroState) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        HyleButton("Save search", onClick = { saveDialogOpen = true }, secondary = true)
                    }
                }
            }
        }
    }

    if (saveDialogOpen) {
        SaveSearchDialog(
            onDismiss = { saveDialogOpen = false },
            onSave = { name -> viewModel.saveCurrentQuery(name); saveDialogOpen = false },
        )
    }

    relatedContextRow?.let { row ->
        RelatedContextSheet(
            forRow = row,
            state = relatedContextState,
            onJump = jumpToRecalledNode,
            onDismiss = { relatedContextRow = null },
        )
    }
}

/** Lane A1's "Related context" sheet state: which of the three mutually-exclusive readings is
 *  live right now — never a [sheet] alongside a stale [error], or vice versa. */
private data class RelatedContextUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val sheet: GraphAdjacentRecallPresenter.SheetState? = null,
)

/**
 * Lane A1 — the graph-adjacent recall's real UI call site: a "Related context" tap on a search
 * result opens this, showing every node [GraphAdjacentRecall.expand] found one hop away, each
 * with every citable reason it was reached ([GraphRecallCitationRow] — edge `because` + matched
 * query term, verbatim, never synthesized), the cap surfaced honestly when it actually cut
 * something, and a tap to jump straight to it via [onJump] (the same conversation-jump path a
 * plain search-result tap already uses — S9 continuity, same find-bar text).
 *
 * The header reuses [AssemblyMode.GraphAdjacent]'s own [uiLabel]/[reason] text — the exact words
 * [dev.fonebrew.ui.components.ScopeInspector] would show for this mode — so this sheet and that
 * inspector never describe graph-adjacent recall two different ways.
 */
@Composable
private fun RelatedContextSheet(
    forRow: SearchResultsPresenter.ResultRow,
    state: RelatedContextUiState,
    onJump: (rootId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalHyleColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(
                Modifier
                    .padding(16.dp)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            AssemblyMode.GraphAdjacent.uiLabel().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "for “${forRow.title}”",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier.size(32.dp).clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", style = MaterialTheme.typography.titleMedium, color = c.textMid)
                    }
                }
                Text(
                    AssemblyMode.GraphAdjacent.reason(),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMid,
                    modifier = Modifier.padding(top = 4.dp),
                )
                HorizontalDivider(color = c.hairline, modifier = Modifier.padding(vertical = 8.dp))

                when {
                    // Not CenteredMessage: that composable fills the whole dialog window by
                    // design (it's used for the top-level overlay's own zero/indexing states),
                    // which would fight this sheet's bounded, scrollable Column. A plain Row is
                    // enough for a sheet this size.
                    state.loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                        CircularProgressIndicator(color = c.violet, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Expanding the graph…", style = MaterialTheme.typography.bodyMedium, color = c.textMid)
                    }
                    state.error != null -> Text(
                        state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.error,
                    )
                    state.sheet == null || state.sheet.rows.isEmpty() -> Text(
                        "Nothing recorded one hop away from this result.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMid,
                    )
                    else -> {
                        val sheet = state.sheet
                        // The cap, surfaced when it actually hit — never a silent truncation
                        // (mirrors GraphAdjacentRecall.Result's own contract).
                        Text(
                            "${sheet.rows.size} of ${sheet.consideredCount} shown (cap ${sheet.cap})" +
                                (if (sheet.truncated) " · ${sheet.cutCount} left out by the cap" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMid,
                        )
                        Spacer(Modifier.height(8.dp))
                        sheet.rows.forEach { row -> RelatedContextRow(row, onJump = onJump) }
                    }
                }
            }
        }
    }
}

/** One [GraphAdjacentRecallPresenter.RecalledRow]: the node's own graph label when the projector
 *  minted one (falling back to its bare id — never a fabricated title), tappable to jump straight
 *  to it via [onJump] when [GraphAdjacentRecallPresenter.RecalledRow.jumpRootId] is non-null, and
 *  every citation underneath via the shared [GraphRecallCitationRow]. */
@Composable
private fun RelatedContextRow(row: GraphAdjacentRecallPresenter.RecalledRow, onJump: (String) -> Unit) {
    val c = LocalHyleColors.current
    val jumpTarget = row.jumpRootId
    val title = row.label?.takeIf { it.isNotBlank() } ?: row.nodeId
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .let { if (jumpTarget != null) it.clickable { onJump(jumpTarget) } else it }
            .semantics {
                contentDescription = title + (if (jumpTarget != null) ", tap to open" else "")
            },
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (jumpTarget != null) c.violet else c.textHigh,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        row.citations.forEach { citation ->
            GraphRecallCitationRow(citation, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }
    }
    HorizontalDivider(color = c.hairline)
}

/** The whole of the operator help's standing presence: one glyph at the field's trailing edge,
 *  violet while open. Everything the old always-on cheat-sheet said now lives behind this. */
@Composable
private fun OperatorsToggle(expanded: Boolean, onToggle: () -> Unit) {
    val c = LocalHyleColors.current
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(32.dp)
            .clickable(onClick = onToggle)
            .semantics { contentDescription = if (expanded) "Hide search operators" else "Show search operators" },
        contentAlignment = Alignment.Center,
    ) {
        Text("?", style = MaterialTheme.typography.titleMedium, color = if (expanded) c.violet else c.textMid)
    }
}

/** The operator reference, shown only when [OperatorsToggle] is on. Same six lines it always
 *  was — the change is that you have to ask for them. */
@Composable
private fun OperatorLegend() {
    val c = LocalHyleColors.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        listOf(
            "\"exact phrase\"" to "match text verbatim",
            "term1 OR term2" to "either term",
            "-term" to "exclude a term",
            "is:starred  has:image  project:name" to "filter by facet",
            "before:2026-01-01  after:-7d" to "filter by date",
            "/pattern/" to "regex over matched conversations",
        ).forEach { (op, desc) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(op, style = MaterialTheme.typography.labelMedium, color = c.violet)
                Spacer(Modifier.width(8.dp))
                Text(desc, style = MaterialTheme.typography.labelMedium, color = c.textMid)
            }
        }
    }
}

/**
 * The empty state. Personal content only: what you searched before, and what you saved. The
 * index-status line appears only on a genuine first run — while indexing is actually running the
 * `when` above shows the spinner instead, so this can never be the permanent banner it was.
 */
@Composable
private fun ZeroState(
    state: SearchPresenter.UiState,
    /** Puts a stored query back in the box — shared by Recent and Saved, which differ only in
     *  where the text came from. */
    onApplyQuery: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onClearRecent: () -> Unit,
) {
    val c = LocalHyleColors.current
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        if (state.showIndexStatus) {
            Text(
                "Nothing indexed yet — conversations become searchable as you have them.",
                style = MaterialTheme.typography.labelMedium,
                color = c.textMid,
            )
            Spacer(Modifier.height(16.dp))
        }
        if (state.recentSearches.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textHigh,
                    modifier = Modifier.weight(1f),
                )
                // Local-first app: search history is sensitive, so clearing it is one tap away
                // and actually deletes the rows.
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.violet,
                    modifier = Modifier.clickable(onClick = onClearRecent),
                )
            }
            Spacer(Modifier.height(8.dp))
            state.recentSearches.forEach { query ->
                Text(
                    query,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onApplyQuery(query) })
                        .padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        if (state.savedSearches.isNotEmpty()) {
            Text("Saved searches", style = MaterialTheme.typography.titleSmall, color = c.textHigh)
            Spacer(Modifier.height(8.dp))
            state.savedSearches.forEach { saved ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onApplyQuery(saved.query) })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(saved.name, style = MaterialTheme.typography.bodyMedium, color = c.textHigh)
                        Text(
                            saved.query,
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "Remove",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.violet,
                        modifier = Modifier.clickable(onClick = { onDeleteSaved(saved.id) }),
                    )
                }
            }
        }
    }
}

/**
 * No results, with a way forward instead of a shrug. This used to be a single line — "try a
 * shorter query or removing a facet" — that named the fix without offering it.
 *
 * TODO(owner decision): the natural next offer here is "search the rest of this device instead".
 *  It is deliberately NOT built. The app has no device-wide search at all today (only
 *  single-file SAF pickers), and adding one means `Intent.ACTION_OPEN_DOCUMENT_TREE`, a
 *  *persisted* tree URI permission, and a walked/indexed copy of whatever that tree contains —
 *  a real change to this app's privacy surface, on a phone, in a local-first app whose first
 *  binding rule is about not reaching where it wasn't invited. That is the owner's call to make,
 *  not this layer's to sneak in behind a helpful-looking button.
 */
@Composable
private fun NoResults(state: SearchPresenter.UiState, onDropFacets: () -> Unit) {
    val c = LocalHyleColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No results for “${state.queryText}”.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMid,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (state.hasFacets) {
            Text(
                "The filters may be narrowing it to nothing.",
                style = MaterialTheme.typography.labelMedium,
                color = c.textMid,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            HyleButton("Search without filters", onClick = onDropFacets, secondary = true)
        } else {
            Text(
                "Search covers the titles and message text of your ${countPhrase(state.indexedCount)} " +
                    "on this device. It doesn't look at files stored elsewhere on your phone.",
                style = MaterialTheme.typography.labelMedium,
                color = c.textMid,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CenteredMessage(text: String, icon: (@Composable () -> Unit)? = null) {
    val c = LocalHyleColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon?.let { it(); Spacer(Modifier.height(16.dp)) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textMid, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ResultsList(
    state: SearchPresenter.UiState,
    onToggleExplain: (String) -> Unit,
    onOpenResult: (SearchResultsPresenter.ResultRow) -> Unit,
    onRelatedContext: (SearchResultsPresenter.ResultRow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        items(state.rows, key = { it.convId }) { row ->
            ResultRowView(
                row = row,
                expanded = state.expandedResultId == row.convId,
                onOpen = { onOpenResult(row) },
                onToggleExplain = { onToggleExplain(row.convId) },
                onRelatedContext = { onRelatedContext(row) },
            )
            HorizontalDivider(color = LocalHyleColors.current.hairline)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRowView(
    row: SearchResultsPresenter.ResultRow,
    expanded: Boolean,
    onOpen: () -> Unit,
    onToggleExplain: () -> Unit,
    /** Lane A1's "Related context" action (graph-adjacent recall's real UI call site) — wired to
     *  both this row's long-press (below) and its own visible affordance (bottom of this
     *  Column), tap-plus-long-press parity so it's reachable without discovering the gesture. */
    onRelatedContext: () -> Unit,
) {
    val c = LocalHyleColors.current
    // Graph-adjacent recall only ever walks ThreadGraph — conversation nodes — so it has nothing
    // honest to say about a LOOP/TASK hit (kindLabel(row.kind) != null for exactly those two;
    // see that function's own KDoc). Rather than open a sheet that can only ever say "nothing
    // recorded" for those kinds, the affordance (both the long-press and its visible twin below)
    // is simply absent for them.
    val graphEligible = kindLabel(row.kind) == null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = if (graphEligible) onRelatedContext else null,
                onLongClickLabel = if (graphEligible) "Related context" else null,
            )
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Legibility over convenience (this app's own design thesis, CLAUDE.md's north
            // star): a mixed result list must say out loud which corpus a hit came from, not
            // leave someone to discover it only once the row opens somewhere unexpected.
            kindLabel(row.kind)?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.violet,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            Text(
                text = highlighted(row.title, row.titleHighlights, c.violet),
                style = MaterialTheme.typography.titleSmall,
                color = c.textHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(row.relativeTime, style = MaterialTheme.typography.labelSmall, color = c.textMid)
        }
        Text(
            text = highlighted(row.snippet, row.snippetHighlights, c.violet),
            style = MaterialTheme.typography.bodySmall,
            color = c.textMid,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (row.explanation != null) {
            Text(
                if (expanded) "Hide why this matched" else "Why did this match?",
                style = MaterialTheme.typography.labelSmall,
                color = c.violet,
                modifier = Modifier.padding(top = 4.dp).clickable(onClick = onToggleExplain),
            )
            if (expanded) ExplanationPanel(row.explanation)
        }
        // Lane A1: the visible row affordance for graph-adjacent recall — the tappable twin of
        // this row's own long-press above (tap-plus-long-press parity; never long-press-only).
        if (graphEligible) {
            Text(
                "Related context",
                style = MaterialTheme.typography.labelSmall,
                color = c.violet,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(onClick = onRelatedContext)
                    .semantics { contentDescription = "Show related context for ${row.title}" },
            )
        }
    }
}

@Composable
private fun ExplanationPanel(explanation: MatchExplanation) {
    val c = LocalHyleColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(c.raised, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        explanation.termContributions.forEach { term ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    "\"${term.term}\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textHigh,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    (if (term.field == ExplainField.TITLE) "title" else "content") +
                        " ×${term.rawCount} → +${"%.2f".format(term.subtotal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMid,
                )
            }
        }
        Row(Modifier.padding(top = 4.dp)) {
            Text("Recency boost", style = MaterialTheme.typography.labelSmall, color = c.textHigh, modifier = Modifier.weight(1f))
            Text("+${"%.2f".format(explanation.recencyFactor)}", style = MaterialTheme.typography.labelSmall, color = c.textMid)
        }
        Row(Modifier.padding(top = 4.dp)) {
            Text("Total score", style = MaterialTheme.typography.labelMedium, color = c.textHigh, modifier = Modifier.weight(1f))
            Text("%.2f".format(explanation.lexicalScore), style = MaterialTheme.typography.labelMedium, color = c.violet)
        }
    }
}

@Composable
private fun SaveSearchDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this search") },
        text = { HyleField(value = name, onValueChange = { name = it }, label = "Name", placeholder = "e.g. Open build issues") },
        confirmButton = { HyleButton("Save", onClick = { onSave(name) }) },
        dismissButton = { HyleButton("Cancel", onClick = onDismiss, secondary = true) },
    )
}

/** `null` for the conversation kinds — a bare title is what every result looked like before
 *  loop:/task: existed, so the label only appears for the two kinds that are new. */
private fun kindLabel(kind: SearchKind): String? = when (kind) {
    SearchKind.LOOP -> "LOOP"
    SearchKind.TASK -> "TASK"
    SearchKind.TEXT, SearchKind.IMAGE, SearchKind.MIXED -> null
}

private fun countPhrase(count: Long): String =
    "$count conversation" + if (count == 1L) "" else "s"

private fun indexingMessage(indexedCount: Long): String =
    if (indexedCount > 0L) "Indexing your conversations… ${countPhrase(indexedCount)} so far."
    else "Indexing your conversations…"

private fun highlighted(text: String, ranges: List<IntRange>, color: androidx.compose.ui.graphics.Color) =
    buildAnnotatedString {
        append(text)
        ranges.forEach { r ->
            val start = r.first.coerceIn(0, text.length)
            val end = (r.last + 1).coerceIn(start, text.length)
            if (start < end) addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), start, end)
        }
    }

private fun diagnosticMessage(d: Diagnostic): String = when (d) {
    is Diagnostic.UnknownField -> "Unknown field \"${d.typed}\"" + (d.suggestion?.let { " — did you mean $it:?" } ?: "")
    // Says which pair, and why. "isn't indexed yet" was the only reason available before, and it
    // is the wrong reason for is:archived — that column exists and is indexed, nothing ever
    // writes it. See dev.fonebrew.domain.search.query.unbackedReason.
    is Diagnostic.UnindexedFacet ->
        "${d.field.key}:${d.value} " + (unbackedReason(d.field, d.value) ?: "isn't indexed yet")
    is Diagnostic.UnterminatedQuote -> "Unterminated quote — treated as closed at the end"
    is Diagnostic.UnterminatedRegex -> "Unterminated /regex/ — treated as a plain term"
    is Diagnostic.InvalidDate -> "\"${d.raw}\" isn't a date this app understands"
    is Diagnostic.SemanticUnavailable -> "Semantic search (?${d.text}) needs the AI layer — not available"
}
