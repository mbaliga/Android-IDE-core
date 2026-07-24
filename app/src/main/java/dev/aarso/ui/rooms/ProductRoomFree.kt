package dev.aarso.ui.rooms

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.aarso.FonebrewApp
import dev.aarso.data.entity.TaskEntity
import dev.aarso.domain.tasks.TaskDue
import dev.aarso.domain.tasks.TaskState
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.hyle.HyleField
import dev.aarso.ui.hyle.HyleTitle
import dev.aarso.ui.theme.LocalHyleColors
import kotlinx.coroutines.launch

/**
 * The free floor's Product room (CORE_PHASES.md P1 / brief §4.1): a flat To-do list — no
 * projects, tags, or filters, flatness is the feature. (The Watchlist/Watch tab that used to
 * live here, CORE_PHASES.md P2, was pulled — owner call: it belongs in the paid Studio layer,
 * not the free floor.) Replaces [dev.aarso.ui.spatial.ProjectRoomSlot]'s prior
 * [dev.aarso.ui.spatial.ProjectRoomLocked] fallback. [extraTabs] lets an above-core layer append
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
    var tab by remember { mutableStateOf(0) }

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
        when {
            tab == 0 -> TodoTab()
            else -> extraTabs[tab - 1].second()
        }
    }
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

