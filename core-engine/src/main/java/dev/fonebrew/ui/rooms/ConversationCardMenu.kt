package dev.fonebrew.ui.rooms

import dev.aarso.hyle.component.HyleContextMenuItem

/**
 * Desktop-class kit §3 ("Conversations lists (ChatsRoom cards...)" → long-press
 * `HyleContextMenu`) — the long-press menu for one [ConversationCard], built ONLY from
 * operations the card already exposes as buttons/inline actions today: Open (the card's own
 * tap), Star/Unstar (the trailing star button), Assign to project (the trailing tag button),
 * and — only when this conversation actually carries a THREAD_TOPOLOGY_PLAN "spawned/forked
 * from…" lineage chip — Open source conversation (that chip's own tap). There is no
 * delete/rename/export flow on this card, so none is offered (desktop-class-kit.md §3 gates
 * those on the surface already having the flow). No destructive item here, so no confirm step
 * is needed on this menu.
 */
internal fun conversationCardMenuItems(bookmarked: Boolean, hasLineageSource: Boolean): List<HyleContextMenuItem> =
    buildList {
        add(HyleContextMenuItem(id = "open", label = "Open"))
        add(HyleContextMenuItem(id = "toggle_star", label = if (bookmarked) "Remove star" else "Star"))
        add(HyleContextMenuItem(id = "assign_project", label = "Assign to project"))
        if (hasLineageSource) add(HyleContextMenuItem(id = "open_source", label = "Open source conversation"))
    }
