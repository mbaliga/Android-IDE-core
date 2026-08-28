package dev.fonebrew.ui.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.cells.HyleTitle
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.entity.TaskEntity
import dev.fonebrew.domain.tasks.TaskDue
import dev.fonebrew.domain.tasks.TaskState
import kotlinx.coroutines.launch

/**
 * The free floor's Product room (CORE_PHASES.md P1 / brief §4.1): a flat To-do list — no
 * projects, tags, or filters, flatness is the feature. (The Watchlist/Watch tab that used to
 * live here, CORE_PHASES.md P2, was pulled — owner call: it belongs in the paid Studio layer,
 * not the free floor.) Replaces [dev.fonebrew.ui.spatial.ProjectRoomSlot]'s prior
 * [dev.fonebrew.ui.spatial.ProjectRoomLocked] fallback. [extraTabs] lets an above-core layer append
 * tabs (e.g. Studio's pitch tab, brief §8.3) without core referencing that code — the S6 seam
 * installs a *variant* of this composable rather than replacing it outright while unentitled
 * (brief §7.2).
 */
@Composable
fun ProductRoomFree(
    onClose: () -> Unit,
    extraTabs: List<Pair<String, @Composable () -> Unit>> = emptyList(),
) {
    BackHandler(onBack = onClose)
    val c = LocalHyleColors.current
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    var tab by remember { mutableStateOf(0) }

    // Same per-room override as Chat/Chats/Tree/Develop/Settings (Settings → Global → Tab bar
    // position): a room-specific choice wins over the universal default when set.
    val universalTabBarPosition by container.sessionStore.tabBarPosition.collectAsState()
    val roomTabBarOverrides by container.sessionStore.roomTabBarPosition.collectAsState()
    val tabBarPosition = roomTabBarOverrides["project"] ?: universalTabBarPosition

    // "To-do" plus whatever the paid Studio layer contributes; any Studio tab gets a neutral
    // glyph so this room never needs to know Studio's icon set (same shape as DevelopTabs).
    val tabSpecs = listOf(
        dev.aarso.hyle.cells.HyleTabSpec("To-do") { tint -> todoTabGlyph(tint) },
    ) + extraTabs.map { (label, _) -> dev.aarso.hyle.cells.HyleTabSpec(label) { tint -> genericTabGlyph(tint) } }

    val tabBarBlock: @Composable () -> Unit = {
        dev.aarso.hyle.cells.HyleTabBar(tabs = tabSpecs, selected = tab, onSelect = { tab = it }, position = tabBarPosition)
    }
    val contentBlock: @Composable () -> Unit = {
        when {
            tab == 0 -> TodoTab()
            else -> extraTabs[tab - 1].second()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.ink)
            // SpatialRoot's outer Box already reserves the nav-bar with systemBarsPadding();
            // a plain imePadding() would stack on top of that (same bug fixed in ChatScreen.kt)
            // and, worse, this room had NO ime handling at all — an editing field's keyboard
            // simply drew over the bottom of the screen with nothing to push content out of
            // the way, hiding the rest of a list behind it.
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
            HyleButton("‹ Back", onClick = onClose)
        }
        HyleTitle("Product")
        if (tabBarPosition == "BOTTOM") {
            Box(Modifier.weight(1f)) { contentBlock() }
            tabBarBlock()
        } else {
            tabBarBlock()
            Box(Modifier.weight(1f)) { contentBlock() }
        }
    }
}

/** To-do: a checklist line with one checked box — [SettingsRoom]'s TabGlyph line-drawn style. */
private fun DrawScope.todoTabGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    listOf(h * 0.30f, h * 0.50f, h * 0.70f).forEach { y ->
        drawLine(tint, Offset(w * 0.40f, y), Offset(w * 0.90f, y), strokeWidth = sw)
    }
    val check = Path().apply {
        moveTo(w * 0.10f, h * 0.30f)
        lineTo(w * 0.20f, h * 0.40f)
        lineTo(w * 0.34f, h * 0.20f)
    }
    drawPath(check, tint, style = Stroke(width = sw))
}

/** Neutral placeholder glyph for any Studio-contributed tab (this room doesn't know its icon). */
private fun DrawScope.genericTabGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    val diamond = Path().apply {
        moveTo(w * 0.5f, h * 0.10f)
        lineTo(w * 0.90f, h * 0.5f)
        lineTo(w * 0.5f, h * 0.90f)
        lineTo(w * 0.10f, h * 0.5f)
        close()
    }
    drawPath(diamond, tint, style = Stroke(width = sw))
}

/**
 * §8.1: composer pinned top; circle checkbox (outline → filled violet + strike, ~300ms);
 * long-press drag reorder (fractional [TaskOrdering]); swipe → done with undo; done items
 * sink to a collapsed "Done" section. Due renders relative, overdue = high-luminance
 * violet + glyph + label — never red (§1.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoTab() {
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    val store = container.taskStore
    val tasks by store.tasks.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var doneExpanded by remember { mutableStateOf(false) }
    var composerText by remember { mutableStateOf("") }

    val active = tasks.filter { it.state != TaskState.DONE }.sortedBy { it.orderKey }
    val done = tasks.filter { it.state == TaskState.DONE }.sortedByDescending { it.doneAt }

    // Optimistic order for the active list while a drag is in flight — the store's own
    // Flow is the source of truth once the drag commits.
    var dragOrder by remember { mutableStateOf<List<TaskEntity>?>(null) }
    LaunchedEffect(active.map { it.id }) { dragOrder = null }
    val displayActive = dragOrder ?: active

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) { Snackbar(it) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HyleField(
                    value = composerText,
                    onValueChange = { composerText = it },
                    label = "",
                    placeholder = "Capture anything…",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                HyleButton(
                    text = "Add",
                    enabled = composerText.isNotBlank(),
                    onClick = {
                        val title = composerText.trim()
                        if (title.isNotEmpty()) {
                            scope.launch { store.create(title) }
                            composerText = ""
                        }
                    },
                )
            }

            if (tasks.isEmpty()) {
                Text(
                    "Capture anything. Studio turns these into a plan later — they carry over.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalHyleColors.current.textMid,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(displayActive, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        onToggle = {
                            scope.launch {
                                store.toggleDone(task)
                                val result = snackbarHost.showSnackbar("Marked done", actionLabel = "Undo")
                                if (result == SnackbarResult.ActionPerformed) store.toggleDone(task)
                            }
                        },
                        onDragStart = { dragOrder = active },
                        onDragMove = { deltaIndex ->
                            val list = (dragOrder ?: active).toMutableList()
                            val from = list.indexOfFirst { it.id == task.id }
                            val to = (from + deltaIndex).coerceIn(0, list.lastIndex)
                            if (from != -1 && from != to) {
                                val item = list.removeAt(from)
                                list.add(to, item)
                                dragOrder = list
                            }
                        },
                        onDragEnd = {
                            val list = dragOrder ?: active
                            val idx = list.indexOfFirst { it.id == task.id }
                            if (idx != -1) {
                                val before = list.getOrNull(idx - 1)?.orderKey
                                val after = list.getOrNull(idx + 1)?.orderKey
                                scope.launch { store.reorder(task, before, after) }
                            }
                        },
                    )
                    HorizontalDivider(color = LocalHyleColors.current.hairline)
                }

                if (done.isNotEmpty()) {
                    item(key = "done-header") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { doneExpanded = !doneExpanded }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (doneExpanded) "▾" else "▸",
                                color = LocalHyleColors.current.textMid,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                "Done (${done.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = LocalHyleColors.current.textMid,
                            )
                        }
                    }
                    if (doneExpanded) {
                        items(done, key = { it.id }) { task ->
                            TaskRow(task = task, onToggle = { scope.launch { store.toggleDone(task) } })
                            HorizontalDivider(color = LocalHyleColors.current.hairline)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskRow(
    task: TaskEntity,
    onToggle: () -> Unit,
    onDragStart: () -> Unit = {},
    onDragMove: (Int) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onToggle()
            false // the row's own state (not swipe offset) reflects done-ness; snap back
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(LocalHyleColors.current.violetDim).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text("Done", color = LocalHyleColors.current.violet) }
        },
    ) {
        var rowOffsetIndex by remember { mutableStateOf(0) }
        Row(
            Modifier
                .fillMaxWidth()
                .background(LocalHyleColors.current.ink)
                .zIndex(if (rowOffsetIndex != 0) 1f else 0f)
                .pointerInput(task.id) {
                    var accumulated = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDragEnd = { accumulated = 0f; rowOffsetIndex = 0; onDragEnd() },
                        onDragCancel = { accumulated = 0f; rowOffsetIndex = 0 },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumulated += dragAmount.y
                            val rowHeightPx = 64.dp.toPx()
                            val steps = (accumulated / rowHeightPx).toInt()
                            if (steps != 0) {
                                onDragMove(steps)
                                accumulated -= steps * rowHeightPx
                                rowOffsetIndex += steps
                            }
                        },
                    )
                }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskCheckbox(checked = task.state == TaskState.DONE, onClick = onToggle)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalHyleColors.current.textHigh,
                    textDecoration = if (task.state == TaskState.DONE) TextDecoration.LineThrough else null,
                )
                task.dueAt?.let { due ->
                    val label = TaskDue.label(due, System.currentTimeMillis())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (label.overdue) {
                            Text(
                                "▲ ",
                                color = LocalHyleColors.current.violet,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            label.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (label.overdue) {
                                LocalHyleColors.current.violet
                            } else {
                                LocalHyleColors.current.textMid
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Outline circle → filled violet + strike, ~300ms (§8.1). Hand-drawn (no icon-font
 *  dependency) to match this codebase's zero-extra-asset stance (see `MonogramTile`). */
@Composable
private fun TaskCheckbox(checked: Boolean, onClick: () -> Unit) {
    val c = LocalHyleColors.current
    val fill by animateColorAsState(if (checked) c.violet else Color.Transparent, label = "checkbox-fill")
    val stroke by animateColorAsState(if (checked) c.violet else c.hairline, label = "checkbox-stroke")
    Canvas(
        Modifier
            .size(22.dp)
            .clickable(onClick = onClick),
    ) {
        val r = size.minDimension / 2f
        drawCircle(color = fill, radius = r * 0.82f, center = Offset(size.width / 2f, size.height / 2f))
        drawCircle(
            color = stroke,
            radius = r * 0.82f,
            center = Offset(size.width / 2f, size.height / 2f),
            style = Stroke(width = 1.6.dp.toPx()),
        )
    }
}
