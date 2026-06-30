package dev.aarso.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.domain.format.LocaleFormat
import dev.aarso.domain.ledger.Provenance
import dev.aarso.domain.library.ConvKind
import dev.aarso.domain.library.ConversationSummary
import dev.aarso.domain.library.Flair
import dev.aarso.domain.library.FlairSet
import java.time.ZoneId
import java.util.Locale

/**
 * **One dense, scannable Conversations row** (Doc 02 §2) — the open-core list item the
 * Conversations Room renders one of per chat. On-thesis legibility: the row carries enough fact
 * to make the list self-explaining *without opening the chat* — its title, how alive it is (a
 * relative timestamp + a branch pip), *what kind* of work it holds, and crucially **which models
 * touched it and where the work ran** (the model flairs, with on-device⇄cloud provenance).
 *
 * **Pure presentational.** Every input is a domain type, a callback, the caller's [locale], or
 * the caller's [nowMillis] — there is no ViewModel, no clock read, no I/O, no app singleton here.
 * The caller supplies the already-derived [flairs] (see [dev.aarso.domain.library.ModelFlairs])
 * and the current time, so this component stays trivially previewable and testable. Styling is
 * Material 3 ([MaterialTheme] typography + colorScheme), matching the codebase's
 * `dev.aarso.ui.components` register — no Hyle dependency.
 *
 * **Colour-independence (a11y) is the contract, not a nicety** (binding rule 2 + Doc 02). Each
 * model flair leads with a non-colour glyph — ⌂ on-device / ☁ cloud (a watched object) / ◐ mixed —
 * so the on-device⇄cloud distinction survives greyscale, colour-blindness, and a screen reader.
 * A single [Modifier.semantics] `contentDescription` folds the whole row (title, time, models,
 * star state) into one spoken phrase for TalkBack.
 *
 * @param summary the denormalised chat summary to render (title / activity / kinds / star / etc.).
 * @param flairs the bounded, pre-derived model-flair strip for this chat (visible flairs + overflow).
 * @param nowMillis the caller's "now" in epoch millis — used only to phrase the relative timestamp.
 * @param locale the locale every number/date in the row is formatted against (never a global default).
 * @param onClick invoked when the row body is tapped (open the chat — restore/branch happen on the tree).
 * @param onToggleStar invoked when the ★/☆ affordance is tapped (pin / unpin this chat).
 * @param modifier outer modifier; the row's clickable + semantics + content are layered on top.
 */
@Composable
fun ConversationRow(
    summary: ConversationSummary,
    flairs: FlairSet,
    nowMillis: Long,
    locale: Locale,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeText = LocaleFormat.relativeOrAbsolute(
        epochMillis = summary.lastActivityMillis,
        nowMillis = nowMillis,
        zone = ZoneId.systemDefault(),
        locale = locale,
    )
    val typeText = typeLabel(summary.kinds)

    // One spoken phrase for TalkBack: title, time, the models that touched it, star state.
    val modelsSpoken = flairs.flairs.joinToString(", ") { "${provenanceWord(it.provenance)} ${it.model}" }
        .ifEmpty { "no models recorded" } +
        if (flairs.moreCount > 0) ", and ${flairs.moreCount} more" else ""
    val starWord = if (summary.starred) "starred" else "not starred"
    val rowDescription = "Conversation, ${summary.title}, $timeText, models $modelsSpoken, $starWord"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = rowDescription }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Title — single line, ellipsized so a long title never reflows the dense row.
            Text(
                summary.title.ifBlank { "(untitled)" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))

            // One-line meta: relative time · branch pip (if any) · type indicator.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary.branchCount > 0) {
                    MetaDot()
                    // Branch pip: ⑂ + a locale-grouped count (the chat's aliveness, at a glance).
                    Text(
                        "⑂ ${LocaleFormat.count(summary.branchCount, locale)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (typeText != null) {
                    MetaDot()
                    Text(
                        typeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // The model-flair strip — ⌂Model / ☁Model chips, glyph-led (never colour-alone),
            // capped with a "+k more" overflow when the derived FlairSet was bounded.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                flairs.flairs.forEach { flair -> FlairChip(flair) }
                if (flairs.moreCount > 0) {
                    Text(
                        "+${LocaleFormat.count(flairs.moreCount, locale)} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        // Star toggle — ★ pinned / ☆ unpinned. Its own clickable so it doesn't open the chat.
        Text(
            if (summary.starred) "★" else "☆",
            style = MaterialTheme.typography.titleMedium,
            color = if (summary.starred) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .clickable(onClick = onToggleStar)
                .semantics { contentDescription = if (summary.starred) "Unstar" else "Star" }
                .padding(4.dp),
        )
    }
}

/**
 * A single **model flair** chip — glyph + model name, **never colour-alone**.
 *
 * The leading glyph is the non-colour carrier of provenance: [Provenance.LOCAL] → "⌂" (on-device),
 * [Provenance.CLOUD] → "☁" (a watched cloud object), [Provenance.MIXED] → "◐" (both). (A per-turn
 * derived flair is only ever LOCAL or CLOUD; ◐ is handled defensively for an aggregate value.)
 * The glyph and the model name together carry the meaning, so the chip stays legible in greyscale
 * and to colour-blind readers; colour may only ever add on top.
 */
@Composable
private fun FlairChip(flair: Flair) {
    val glyph = when (flair.provenance) {
        Provenance.LOCAL -> "⌂"
        Provenance.CLOUD -> "☁"
        Provenance.MIXED -> "◐"
    }
    Text(
        "$glyph${flair.model}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A thin middle-dot separator in the meta line — text, so it survives greyscale. */
@Composable
private fun MetaDot() {
    Text(
        "·",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The compact type indicator from a chat's [ConvKind] set. A chat may hold both kinds at once
 * (Doc 02 — [kinds][ConversationSummary.kinds] is a set), so a mixed chat reads "Text + Image".
 * Returns `null` when there is nothing to say (empty set), so the caller omits the field entirely.
 */
private fun typeLabel(kinds: Set<ConvKind>): String? {
    val text = ConvKind.TEXT in kinds
    val image = ConvKind.IMAGE in kinds
    return when {
        text && image -> "Text + Image"
        text -> "Text"
        image -> "Image"
        else -> null
    }
}

/** The spoken provenance word for the row's TalkBack description (parallels [FlairChip]'s glyph). */
private fun provenanceWord(provenance: Provenance): String = when (provenance) {
    Provenance.LOCAL -> "on-device"
    Provenance.CLOUD -> "cloud"
    Provenance.MIXED -> "mixed"
}
