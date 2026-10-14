package dev.fonebrew.spike.fb4

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A SIMPLIFIED, STATIC copy of the shape of core's "Chats" room
 * (core-engine/.../ui/rooms/ChatsRoom.kt): title + close, the All/Text/Image/Starred/Projects tab
 * strip, and a list of conversation cards. No view-model, no gestures, no Hyle atoms -- plain
 * foundation + material3 Text, so the only thing under test is "does this room-shaped Compose code
 * draw on Compose Desktop". Look and feel is NOT claimed to match the Android app.
 */
data class ConversationSummary(
    val title: String,
    val snippet: String,
    val modelLabel: String,
    val branches: Int,
    val starred: Boolean,
    val cloud: Boolean,
)

val SampleConversations = listOf(
    ConversationSummary("Port plan: KMP seam", "Room-KMP survives at the current pins; FTS5 shares the engine.", "local-gguf", 3, true, false),
    ConversationSummary("Council: release notes", "Three participants disagreed on the order of the changelog.", "remote-model (provider-generic)", 1, false, true),
    ConversationSummary("Loop editor ideas", "Drag-a-wire connect; per-node model; BPMN round trip.", "local-gguf", 0, false, false),
)

val RoomTabs = listOf("All", "Text", "Image", "Starred", "Projects")

@Composable
fun ConversationsRoom(
    conversations: List<ConversationSummary> = SampleConversations,
    selectedTab: Int = 0,
) {
    Column(
        Modifier.fillMaxSize().background(Palette.Ink).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", color = Palette.TextHigh, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("‹ Close", color = Palette.TextMid, fontSize = 14.sp)
        }
        TabStrip(selectedTab)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(conversations) { ConversationCard(it) }
        }
    }
}

/** Selection is shown by a bar under the tab AND by weight AND by a leading marker -- never by colour alone. */
@Composable
private fun TabStrip(selected: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        RoomTabs.forEachIndexed { i, label ->
            val on = i == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    (if (on) "▸ " else "") + label,
                    color = if (on) Palette.TextHigh else Palette.TextMid,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(if (on) 36.dp else 0.dp).height(3.dp).background(Palette.Violet))
            }
        }
    }
}

@Composable
private fun ConversationCard(c: ConversationSummary) {
    Row(
        Modifier.fillMaxWidth()
            .background(Palette.Raised, RoundedCornerShape(10.dp))
            .border(1.dp, Palette.Outline, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TreeMarker(c.branches)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, color = Palette.TextHigh, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // shape (filled vs outline star) + a text state, not just a hue
                Text(if (c.starred) "★ starred" else "☆", color = if (c.starred) Palette.TextHigh else Palette.TextMid, fontSize = 13.sp)
            }
            Text(c.snippet, color = Palette.TextMid, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(c.modelLabel, glyph = null)
                // rule 2: a cloud provider is a visibly marked "watched object"; the on-device default is marked too.
                if (c.cloud) Chip("CLOUD · watched", glyph = Glyph.Eye) else Chip("ON-DEVICE", glyph = Glyph.Square)
                if (c.branches > 0) Chip("${c.branches} branch" + if (c.branches == 1) "" else "es", glyph = null)
            }
        }
    }
}

private enum class Glyph { Square, Eye }

@Composable
private fun Chip(label: String, glyph: Glyph?) {
    Row(
        Modifier.background(Palette.Inset, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            val tint = if (glyph == Glyph.Eye) Palette.Warning else Palette.Cyan
            Canvas(Modifier.size(10.dp)) {
                when (glyph) {
                    Glyph.Square -> drawRect(tint, size = Size(size.width, size.height), style = Stroke(width = 2f))
                    Glyph.Eye -> {
                        drawCircle(tint, radius = size.minDimension / 2f, style = Stroke(width = 2f))
                        drawCircle(tint, radius = size.minDimension / 5f)
                    }
                }
            }
        }
        Text(label, color = Palette.TextHigh, fontSize = 11.sp)
    }
}

/** Tree-topology glyph: a trunk, with side stubs when the conversation has branches. Drawn, not font-dependent. */
@Composable
private fun TreeMarker(branches: Int) {
    Canvas(Modifier.size(width = 18.dp, height = 44.dp)) {
        val x = size.width / 2f
        drawLine(Palette.Violet, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        drawCircle(Palette.Violet, radius = 5f, center = Offset(x, size.height * 0.2f))
        repeat(minOf(branches, 3)) { i ->
            val y = size.height * (0.45f + 0.2f * i)
            drawLine(Palette.Violet, Offset(x, y), Offset(size.width, y - 6f), strokeWidth = 3f)
        }
    }
}
