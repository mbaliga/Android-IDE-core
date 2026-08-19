package dev.fonebrew.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fonebrew.ui.hyle.HyleChip
import dev.fonebrew.ui.hyle.HyleField
import dev.fonebrew.ui.theme.LocalHyleColors

/**
 * S9's in-chat find bar: sits above the thread (continuity with the app-wide overlay — see
 * [dev.fonebrew.ui.ChatScreen]'s wiring). Row-level highlighting only, not sub-string spans inside
 * the rendered turn — the message body renders through the mikepenz markdown composable
 * ([dev.fonebrew.ui.ChatScreen]'s `Markdown(...)` call, per this repo's Compose↔markdown pin), which
 * owns its own text layout; splicing [InChatFindPresenter.Hit] ranges into markdown-rendered
 * output isn't something that composable exposes a seam for. The current-hit *row* still gets a
 * clear visual marker (the caller passes `highlighted` into the turn it renders) and the ribbon
 * still reports precise "n of m" counts — the presenter computes exact character ranges even
 * though this pass only surfaces them at row granularity.
 */
@Composable
fun InChatFindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    options: InChatFindPresenter.FindOptions,
    onOptionsChange: (InChatFindPresenter.FindOptions) -> Unit,
    statusText: String,
    hasHits: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    /** Reverse continuity (§9): promote what's typed here to the app-wide search overlay. */
    onSearchAllChats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalHyleColors.current
    Column(modifier.fillMaxWidth().background(c.raised)) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HyleField(
            value = query,
            onValueChange = onQueryChange,
            label = "",
            placeholder = "Find in this chat",
            modifier = Modifier.weight(1f),
        )
        Text(
            statusText,
            style = MaterialTheme.typography.labelSmall,
            color = c.textMid,
        )
        HyleChip(options.caseSensitive, { onOptionsChange(options.copy(caseSensitive = !options.caseSensitive)) }, "Aa")
        HyleChip(options.wholeWord, { onOptionsChange(options.copy(wholeWord = !options.wholeWord)) }, "word")
        HyleChip(options.useRegex, { onOptionsChange(options.copy(useRegex = !options.useRegex)) }, ".*")
        FindIconButton("‹", enabled = hasHits, onClick = onPrevious)
        FindIconButton("›", enabled = hasHits, onClick = onNext)
        FindIconButton("✕", enabled = true, onClick = onClose)
    }
    if (query.isNotBlank()) {
        Text(
            "Search all chats for “$query”",
            style = MaterialTheme.typography.labelSmall,
            color = c.violet,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSearchAllChats)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
    }
}

@Composable
private fun FindIconButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalHyleColors.current
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = if (enabled) c.textHigh else c.textDisabled)
    }
}
