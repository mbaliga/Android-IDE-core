package dev.fonebrew.ui.loops

import dev.aarso.hyle.component.HyleContextMenuItem

/**
 * Desktop-class kit §3 ("Loop editor node long-press" → keep the existing gesture menu primary,
 * add a `HyleContextMenu` parity path off the node's tap-selected state) — the items for that
 * parity menu, surfaced from inside `NodeConfigDialog` (the dialog a task/gateway node's tap
 * already opens — its "tap-selected state"). Mirrors the two items the long-press
 * `NodeMenuDialog` offers besides Edit: Connect (`connectingFrom = id`) and Delete
 * (`deleteNode(id)`) — Edit is left off this parity menu on purpose, since seeing it here would
 * mean "start editing" while the surface showing it already IS the edit surface. Delete is
 * `destructive = true`; [LoopRoom]'s call site confirms before it actually calls `deleteNode`.
 */
internal fun loopNodeMenuItems(): List<HyleContextMenuItem> = listOf(
    HyleContextMenuItem(id = "connect", label = "Connect from here"),
    HyleContextMenuItem(id = "delete", label = "Delete", destructive = true),
)
