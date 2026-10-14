package dev.fonebrew.watch.ui

import android.app.RemoteInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import dev.fonebrew.watch.core.AskFailurePresenter
import dev.fonebrew.watch.core.AskState
import dev.fonebrew.watch.core.ConnectionPresenter
import dev.fonebrew.watch.core.Glyph
import dev.fonebrew.watch.core.ProvenancePresenter
import dev.fonebrew.watch.core.RelativeTime
import dev.fonebrew.watch.core.ReplyPaging
import dev.fonebrew.watch.core.ReplyRecord
import dev.fonebrew.watch.core.UsageLabel
import dev.fonebrew.watch.core.WatchState
import dev.fonebrew.watch.runtime.AskSubmission
import dev.fonebrew.watch.runtime.WatchController

private const val INPUT_KEY = "fonebrew_prompt"

/**
 * Ask: speak or type a prompt, watch the answer stream in from the phone, and always see **who
 * answered** (glyph + words; the watched-object marker for cloud). Every ending that is not a finished
 * reply is its own worded state (see core/Ask.kt) — there is no bare spinner anywhere on this screen.
 * A relayed turn lands in the phone's message tree like any other turn; the watch mints nothing.
 *
 * Compose rendering, RemoteInput availability and voice quality are device-verified only.
 */
@Composable
internal fun AskScreen(state: WatchState, controller: WatchController) {
    val listState = rememberScalingLazyListState()
    var notice by remember { mutableStateOf<String?>(null) }
    // Tick every second only while the "Waiting N s." counter is on screen; otherwise ages are minute-coarse.
    val now by rememberNowMs(if (state.ask is AskState.Sent) 1_000L else 30_000L)
    val linkView = ConnectionPresenter.present(state.connection)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(INPUT_KEY)
        if (text != null) {
            notice = (controller.ask(text) as? AskSubmission.Rejected)?.reason
        }
    }
    val startInput = {
        notice = null
        try {
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(INPUT_KEY).setLabel("Ask Fonebrew").build()))
            launcher.launch(intent)
        } catch (_: Exception) {
            notice = "No voice or keyboard input is available on this watch."
        }
    }

    ScreenFrame(listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ListHeader { Text("Ask") } }

            val ask = state.ask
            // The link panel replaces the input only when there is nothing of the user's own to show.
            if (ask is AskState.Idle && linkView.blocking) {
                item { LinkStatePanel(linkView, onRetry = controller::refresh) }
                return@ScalingLazyColumn
            }
            if (linkView.blocking) {
                item { StatusChip(linkView.glyph, linkView.headline, linkView.detail, controller::refresh) }
            }

            when (ask) {
                AskState.Idle -> {
                    item { Chip(onClick = startInput, label = { Text("Ask Fonebrew") }, secondaryLabel = { Text("Voice or keyboard") }) }
                    notice?.let { item { Body(it) } }
                    state.lastReply?.let { reply -> replyItems(reply, header = "Last reply", now = now) }
                }

                is AskState.Sent -> {
                    item { Body("“${ask.prompt}”") }
                    item { Text("Asking your phone. Waiting ${RelativeTime.elapsedSeconds(ask.sentAtMs, now)} s.", style = MaterialTheme.typography.body2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                    item { Faint("Nothing has come back yet.") }
                    item { CompactChip(onClick = controller::dismissAsk, label = { Text("Stop waiting") }) }
                }

                is AskState.Streaming -> {
                    item { ProvenanceRow(ProvenancePresenter.of(ask.engineId, ask.watchedObject)) }
                    pagedText(ask.text)
                    item { Faint("Receiving… not finished yet") }
                    item { CompactChip(onClick = controller::dismissAsk, label = { Text("Stop watching") }) }
                }

                is AskState.Done -> {
                    replyItems(ask.reply, header = null, now = now)
                    item { Chip(onClick = { controller.dismissAsk(); startInput() }, colors = ChipDefaults.secondaryChipColors(), label = { Text("Ask again") }) }
                }

                is AskState.Failed -> {
                    val view = AskFailurePresenter.present(ask.failure)
                    item { StatusBlock(view.glyph, view.headline, view.detail) }
                    ask.partial?.let { partial ->
                        item { ProvenanceRow(ProvenancePresenter.of(partial.engineId, partial.watchedObject)) }
                        item { Faint("Partial — not a finished answer:") }
                        pagedText(partial.text)
                    }
                    item { Chip(onClick = { controller.dismissAsk(); startInput() }, colors = ChipDefaults.secondaryChipColors(), label = { Text("Ask again") }) }
                    item { CompactChip(onClick = { controller.dismissAsk(); controller.refresh() }, label = { Text("Check link") }) }
                }
            }
        }
    }
}

private fun ScalingLazyListScope.replyItems(reply: ReplyRecord, header: String?, now: Long) {
    header?.let { item { Faint(it) } }
    item { ProvenanceRow(ProvenancePresenter.of(reply.engineId, reply.watchedObject)) }
    pagedText(reply.text)
    item { Faint(UsageLabel.line(reply.usage)) }
    item { Faint(RelativeTime.ago(reply.completedAtMs, now)) }
}

/** Long text as several items: one item taller than the screen cannot be scrolled within. */
private fun ScalingLazyListScope.pagedText(text: String) {
    val pages = ReplyPaging.pages(text)
    if (pages.isEmpty()) {
        item { Faint("(empty reply)") }
    } else {
        pages.forEach { page -> item { Body(page) } }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.body2, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

@Composable
private fun Faint(text: String) {
    Text(text, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

/** A tappable status line (the link banner): glyph, headline, detail; tapping re-checks the link. */
@Composable
internal fun StatusChip(glyph: Glyph, headline: String, detail: String, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        icon = { GlyphIcon(glyph) },
        label = { Text(headline, maxLines = 2) },
        secondaryLabel = { Text(detail, maxLines = 6) },
    )
}

/** A non-interactive status: shape beside a headline and its sentence (not a disabled-looking chip). */
@Composable
internal fun StatusBlock(glyph: Glyph, headline: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlyphIcon(glyph, size = 22.dp)
        Column {
            Text(headline, style = MaterialTheme.typography.title3)
            Text(detail, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurfaceVariant)
        }
    }
}
