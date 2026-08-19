package dev.fonebrew.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fonebrew.domain.search.ExplainField
import dev.fonebrew.domain.search.MatchExplanation
import dev.fonebrew.domain.search.query.Diagnostic
import dev.fonebrew.ui.hyle.HyleButton
import dev.fonebrew.ui.hyle.HyleChip
import dev.fonebrew.ui.hyle.HyleField
import dev.fonebrew.ui.hyle.HyleTitle
import dev.fonebrew.ui.theme.LocalHyleColors

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
 */
@Composable
fun SearchOverlay(
    viewModel: SearchViewModel,
    /** Called with the conversation to open and the plain text to hand to that chat's find bar
     *  (S9 continuity) — facet syntax stripped, so opening a result for `gradle is:starred`
     *  looks for `gradle` inside the conversation, not the facet. */
    onOpenConversation: (convId: String, findText: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalHyleColors.current
    val state by viewModel.uiState.collectAsState()
    var saveDialogOpen by remember { mutableStateOf(false) }
    // Scoped hardware-keyboard shortcuts (§11.1's reachable-pre-M5 subset, WP13): Esc closes,
    // Ctrl+S saves the current query, Ctrl+Enter opens the top result. Kept local to this
    // Dialog's own composition (a separate Android window from SpatialRoot's), and — unlike
    // that root's Ctrl+K — deliberately not consuming Enter/Tab bare, so typing into the query
    // field is never intercepted. Owner-verified only; no hardware keyboard in this container.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusTarget()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        event.key == Key.Escape -> { onDismiss(); true }
                        event.isCtrlPressed && event.key == Key.S && !state.isZeroState -> {
                            saveDialogOpen = true; true
                        }
                        event.isCtrlPressed && event.key == Key.Enter -> {
                            state.rows.firstOrNull()?.let { onOpenConversation(it.convId, state.findText) }
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
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", style = MaterialTheme.typography.titleMedium, color = c.textMid)
                    }
                }

                Column(Modifier.padding(horizontal = 20.dp)) {
                    HyleField(
                        value = state.queryText,
                        onValueChange = viewModel::onQueryChange,
                        label = "",
                        placeholder = "gradle is:starred \"exact phrase\" /regex/",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.chips.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.chips.forEach { chip -> HyleChip(true, {}, chip.text) }
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
                }

                when {
                    state.isZeroState -> ZeroState(
                        state = state,
                        onApplySaved = viewModel::applySavedSearch,
                        onDeleteSaved = viewModel::deleteSavedSearch,
                    )
                    state.indexing -> CenteredMessage("Indexing your conversations…") {
                        CircularProgressIndicator(color = c.violet, modifier = Modifier.size(28.dp))
                    }
                    state.isNoResults -> CenteredMessage(
                        "No results for “${state.queryText}”. Try a shorter query or removing a facet.",
                    )
                    else -> ResultsList(state, viewModel::toggleExplain, onOpenConversation)
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
}

@Composable
private fun ZeroState(
    state: SearchPresenter.UiState,
    onApplySaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
) {
    val c = LocalHyleColors.current
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            "${state.indexedCount} conversation" + (if (state.indexedCount == 1L) "" else "s") + " indexed",
            style = MaterialTheme.typography.labelMedium,
            color = c.textMid,
        )
        Spacer(Modifier.height(16.dp))
        if (state.savedSearches.isNotEmpty()) {
            Text("Saved searches", style = MaterialTheme.typography.titleSmall, color = c.textHigh)
            Spacer(Modifier.height(8.dp))
            state.savedSearches.forEach { saved ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onApplySaved(saved.query) })
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
            Spacer(Modifier.height(16.dp))
        }
        Text("Operators", style = MaterialTheme.typography.titleSmall, color = c.textHigh)
        Spacer(Modifier.height(8.dp))
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
    onOpenConversation: (convId: String, findText: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
    ) {
        items(state.rows, key = { it.convId }) { row ->
            ResultRowView(
                row = row,
                expanded = state.expandedResultId == row.convId,
                onOpen = { onOpenConversation(row.convId, state.findText) },
                onToggleExplain = { onToggleExplain(row.convId) },
            )
            HorizontalDivider(color = LocalHyleColors.current.hairline)
        }
    }
}

@Composable
private fun ResultRowView(
    row: SearchResultsPresenter.ResultRow,
    expanded: Boolean,
    onOpen: () -> Unit,
    onToggleExplain: () -> Unit,
) {
    val c = LocalHyleColors.current
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
    is Diagnostic.UnindexedFacet -> "${d.field.name.lowercase()}: isn't indexed yet"
    is Diagnostic.UnterminatedQuote -> "Unterminated quote — treated as closed at the end"
    is Diagnostic.UnterminatedRegex -> "Unterminated /regex/ — treated as a plain term"
    is Diagnostic.InvalidDate -> "\"${d.raw}\" isn't a date this app understands"
    is Diagnostic.SemanticUnavailable -> "Semantic search (?${d.text}) needs the AI layer — not available"
}
