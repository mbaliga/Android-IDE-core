package dev.fonebrew.ui.regular

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aarso.hyle.cells.HyleBottomTabBar
import dev.aarso.hyle.cells.HyleTabSpec
import dev.fonebrew.FonebrewApp
import dev.fonebrew.ui.ChatScreen
import dev.fonebrew.ui.ChatViewModel
import dev.fonebrew.ui.develop.DevelopRoom
import dev.fonebrew.ui.loops.LoopRoom
import dev.fonebrew.ui.rooms.ChatsRoom
import dev.fonebrew.ui.rooms.ProductRoomFree
import dev.fonebrew.ui.rooms.SettingsRoom
import dev.fonebrew.ui.rooms.TreeRoom
import dev.fonebrew.ui.spatial.ProjectRoomSlot

/**
 * The Regular shell (bifurcation wave 1, lane R; owner ruling 2026-09-15): a conventional
 * [androidx.compose.material3.Scaffold]-shaped stance over the SAME room composables
 * [dev.fonebrew.ui.spatial.SpatialRoot] mounts spatially — a bottom tab bar (the existing
 * [HyleBottomTabBar] idiom, same component every room's own internal tab row already uses) with
 * five destinations: Chat (home), Chats, Projects, Develop, Settings. Rooms keep their own
 * internal tab bars (e.g. Chats' All/Text/Image/Starred/Projects, Develop's Hardware/Agent/
 * Terminal/Audit) — this shell only ever switches between the five top-level destinations.
 *
 * Tree and Loops are NOT tab destinations: Tree opens from Chat's header affordance (the same
 * one lane Q added, reused verbatim — [dev.fonebrew.ui.ChatScreen]'s `onOpenTree`), Loops from
 * Develop's entry (`onOpenLoops`), each as a full-screen overlay above the tab content — matching
 * how neither is a peer room in [dev.fonebrew.ui.spatial.SpatialRoot]'s own semantic-zoom framing
 * either. `GraphRoom` stays reachable via TreeRoom's own "Graph" tab, unchanged.
 *
 * Deliberately **no** edge-drag/pinch handlers here — Regular's whole premise is that everything
 * is reachable by tap; message-bubble gestures still work when explicitly turned on in Settings
 * (see [dev.fonebrew.domain.mode.GestureModeDefaults] for why they default OFF here specifically).
 *
 * System Back: pops to the Chat tab first, then falls through to the system default (exits) —
 * the conventional bottom-nav contract; see [RegularShellPresenter.backTargetsChat]. Composable
 * rendering/feel (tab-bar geometry, the overlay transitions) is owner-verified only — no device
 * or emulator in this build environment; [RegularShellPresenter] carries the decision logic this
 * file follows, and that part IS JVM-tested.
 */
@Composable
fun RegularShell() {
    val context = LocalContext.current
    val container = (context.applicationContext as FonebrewApp).container
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory)

    var destination by remember { mutableStateOf(RegularDestination.CHAT) }
    // Tree/Loops: full-screen overlays above the tab content, not destinations of their own —
    // see this file's own KDoc above for why.
    var showTree by remember { mutableStateOf(false) }
    var showLoops by remember { mutableStateOf(false) }

    // Closing an overlay takes priority over the tab-reset handler below; the two conditions are
    // mutually exclusive by construction, so registration order never matters here.
    BackHandler(enabled = showTree || showLoops) {
        showTree = false
        showLoops = false
    }
    BackHandler(enabled = !showTree && !showLoops && RegularShellPresenter.backTargetsChat(destination)) {
        destination = RegularDestination.CHAT
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (destination) {
                    RegularDestination.CHAT -> ChatScreen(
                        viewModel = chatViewModel,
                        onOpenModels = { destination = RegularDestination.SETTINGS },
                        onOpenChats = { destination = RegularDestination.CHATS },
                        onOpenSettings = { destination = RegularDestination.SETTINGS },
                        onOpenTree = { showTree = true },
                        atHome = true,
                    )
                    RegularDestination.CHATS -> ChatsRoom(
                        viewModel = chatViewModel,
                        onClose = { destination = RegularDestination.CHAT },
                    )
                    // Same Studio seam SpatialRoot uses (docs/design/agentic-ide.md): the paid
                    // layer installs the real Project room; core falls back to the free To-do
                    // floor. Neither shell references Studio's type directly.
                    RegularDestination.PROJECTS -> (
                        ProjectRoomSlot.content ?: { onClose -> ProductRoomFree(onClose) }
                    )(
                        { destination = RegularDestination.CHAT },
                    )
                    RegularDestination.DEVELOP -> DevelopRoom(
                        onClose = { destination = RegularDestination.CHAT },
                        onOpenLoops = { showLoops = true },
                    )
                    RegularDestination.SETTINGS -> SettingsRoom(
                        onShowSpatialMap = { container.sessionStore.setSpatialMapSeen(false) },
                    )
                }
            }
            HyleBottomTabBar(
                tabs = RegularTabSpecs,
                selected = RegularShellPresenter.indexForDestination(destination),
                onSelect = { index ->
                    RegularShellPresenter.destinationForIndex(index)?.let { destination = it }
                },
            )
        }

        if (showTree) {
            Box(Modifier.fillMaxSize()) {
                TreeRoom(viewModel = chatViewModel, onNodeChosen = { showTree = false })
            }
        }
        if (showLoops) {
            Box(Modifier.fillMaxSize()) {
                LoopRoom(onClose = { showLoops = false })
            }
        }
    }
}

// One HyleTabSpec per RegularDestination, in RegularShellPresenter.destinations order — plain
// abstract glyphs in the same hand-drawn Canvas idiom every other room's own HyleTabSpec list
// already uses (see e.g. ChatsRoom.kt's ChatsTabSpecs, DevelopRoom.kt's per-tab glyphs).
private val RegularTabSpecs: List<HyleTabSpec> = listOf(
    HyleTabSpec("Chat") { tint -> regularChatGlyph(tint) },
    HyleTabSpec("Chats") { tint -> regularChatsGlyph(tint) },
    HyleTabSpec("Projects") { tint -> regularProjectsGlyph(tint) },
    HyleTabSpec("Develop") { tint -> regularDevelopGlyph(tint) },
    HyleTabSpec("Settings") { tint -> regularSettingsGlyph(tint) },
)

private fun DrawScope.regularChatGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val stroke = Stroke(width = w * 0.09f)
    drawRoundRect(
        tint,
        topLeft = Offset(w * 0.12f, h * 0.16f),
        size = Size(w * 0.76f, h * 0.54f),
        cornerRadius = CornerRadius(w * 0.14f),
        style = stroke,
    )
    val tail = Path().apply {
        moveTo(w * 0.30f, h * 0.70f)
        lineTo(w * 0.22f, h * 0.90f)
        lineTo(w * 0.46f, h * 0.70f)
        close()
    }
    drawPath(tail, tint)
}

private fun DrawScope.regularChatsGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    drawLine(tint, Offset(w * 0.16f, h * 0.28f), Offset(w * 0.68f, h * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.16f, h * 0.50f), Offset(w * 0.84f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.16f, h * 0.72f), Offset(w * 0.56f, h * 0.72f), strokeWidth = sw, cap = StrokeCap.Round)
}

private fun DrawScope.regularProjectsGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val stroke = Stroke(width = w * 0.09f)
    drawRoundRect(
        tint,
        topLeft = Offset(w * 0.12f, h * 0.18f),
        size = Size(w * 0.34f, h * 0.64f),
        cornerRadius = CornerRadius(w * 0.06f),
        style = stroke,
    )
    drawRoundRect(
        tint,
        topLeft = Offset(w * 0.54f, h * 0.18f),
        size = Size(w * 0.34f, h * 0.40f),
        cornerRadius = CornerRadius(w * 0.06f),
        style = stroke,
    )
}

private fun DrawScope.regularDevelopGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    drawLine(tint, Offset(w * 0.38f, h * 0.24f), Offset(w * 0.16f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.16f, h * 0.50f), Offset(w * 0.38f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.62f, h * 0.24f), Offset(w * 0.84f, h * 0.50f), strokeWidth = sw, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.84f, h * 0.50f), Offset(w * 0.62f, h * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
}

private fun DrawScope.regularSettingsGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    val stroke = Stroke(width = sw)
    drawCircle(tint, radius = w * 0.30f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
    drawCircle(tint, radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.5f))
}
