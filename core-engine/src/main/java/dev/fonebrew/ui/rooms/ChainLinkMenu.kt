package dev.fonebrew.ui.rooms

import dev.aarso.hyle.component.HyleContextMenuItem

/**
 * Desktop-class kit §3 ("TreeRoom chain rows" → long-press `HyleContextMenu`) — the long-press
 * menu for one `ChainLinkRow`. A chain link row has exactly one existing operation today (its
 * own tap, [dev.fonebrew.domain.thread.ThreadChains.ChainLink] carries no star/delete/rename
 * flow of its own — those live on the conversation card in [ChatsRoom], not here), so the menu
 * is Open alone. Kept as its own tiny pure function (rather than inlined at the call site) so a
 * future row action — if TreeRoom ever grows one — has one place to add it, tested the same way
 * as [conversationCardMenuItems] and [gitBrowseMenuItems].
 */
internal fun chainLinkMenuItems(): List<HyleContextMenuItem> =
    listOf(HyleContextMenuItem(id = "open", label = "Open"))
