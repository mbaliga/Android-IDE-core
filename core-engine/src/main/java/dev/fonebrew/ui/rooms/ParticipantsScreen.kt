package dev.fonebrew.ui.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.cells.FileImage
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleDropdownField
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.cells.HyleSegmentedToggle
import dev.aarso.hyle.cells.HyleTitle
import dev.aarso.hyle.component.HyleField as DesktopHyleField
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.FonebrewApp
import dev.fonebrew.data.Participant
import dev.fonebrew.domain.MessageNode
import dev.fonebrew.domain.tree.Conversations
import dev.fonebrew.domain.tree.MessageTree
import dev.fonebrew.ui.ComposerMode
import dev.fonebrew.ui.wire.WireBox
import java.util.UUID

/**
 * Manage the council's participants like a group chat (IA §B4): add / remove members, and define
 * each one individually — name, instructions, **its own model** (on-device or watched cloud),
 * and **long-term memory**. Saved to [dev.fonebrew.data.CouncilStore]; the personas council fans
 * out over this roster. Per-member **files** are an honest "soon" (rule 6 — not wired yet).
 *
 * Behaves like a WhatsApp group-info screen (owner spec, 2026-07-27): search-and-add a
 * model/persona instead of a plain "new member" only; "Empty group" instead of "Exit group" (you
 * can't leave a council you own, but you can empty it out); and an Artifacts section — Media /
 * Links / Docs — scoped to [conversationId], plus an AI-relevant "Manage context" row standing in
 * for WhatsApp's "Manage storage."
 *
 * **This is now the one place the interaction model changes** (owner ask, 2026-07-27 follow-up):
 * there is no more separate Single/Council·personas/Council·models row sitting outside the chat —
 * the same way WhatsApp has no separate "is this a group?" selector, the mode falls out of who's
 * in the roster. 0–1 participants reads as Single; 2+ reads as a council, flavoured Personas vs
 * Models by [diversityMode] below. [onModeChange] carries that back to
 * [dev.fonebrew.ui.ChatViewModel.requestComposerMode] on Save, which still enforces the real
 * architecture rule this screen doesn't own: **immutable once a chat starts — a genuine change
 * branches with a summary** (unchanged; only the UI entry point moved).
 *
 * Per-member Name/Instructions/Long-term memory fields use the desktop-class kit's
 * [DesktopHyleField] (dev-line kit adoption, kept per reunification policy) rather than the
 * plain [HyleField] cell; the search-and-add field above the roster is new with this screen's
 * redesign and stays on the plain cell.
 */
@Composable
fun ParticipantsScreen(
    onClose: () -> Unit,
    conversationId: String? = null,
    currentMode: ComposerMode = ComposerMode.SINGLE,
    onModeChange: (ComposerMode) -> Unit = {},
) {
    BackHandler(onBack = onClose)
    val c = LocalHyleColors.current
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    val store = container.councilStore
    val saved by store.participants.collectAsState()
    var diversityMode by remember { mutableStateOf(currentMode == ComposerMode.MODELS) }

    // Runnable models = downloaded on-device + configured cloud providers (same source the model
    // picker uses). Listed so any member can run a different one.
    val specs = remember { container.modelRegistry.allSpecs().filter { container.engineProvider.isRunnable(it) } }
    val modelOptions = listOf("Active model (default)") + specs.map { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName }
    fun labelFor(modelId: String?): String =
        modelId?.let { id -> specs.firstOrNull { it.id == id }?.let { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName } }
            ?: "Active model (default)"

    val rows = remember { mutableStateListOf<Participant>().apply { addAll(saved) } }

    // Search-and-add (WhatsApp "add member" is search-driven, not just a blank slot): typing
    // filters the same runnable-model list the per-member dropdown uses; tapping a match appends
    // it as a new member straight away.
    var search by remember { mutableStateOf("") }
    val searchMatches = if (search.isBlank()) {
        emptyList()
    } else {
        modelOptions.withIndex().filter { (_, label) -> label.contains(search, ignoreCase = true) }
    }

    var confirmingEmpty by remember { mutableStateOf(false) }
    var artifactsExpanded by remember { mutableStateOf(false) }

    // Artifacts (Media/Links/Docs) are scoped to this conversation's subtree of the one append-only
    // tree (handoff §2) — the same "a conversation is a subtree under one root" model
    // Conversations.summarize uses, walked locally here since that helper is private to its file.
    val tree: MessageTree by container.repository.observeTree().collectAsState(initial = MessageTree(emptyList()))
    val conversationNodes: List<MessageNode> = if (conversationId == null) {
        emptyList()
    } else {
        val out = mutableListOf<MessageNode>()
        fun walk(node: MessageNode) {
            out += node
            tree.childrenOf(node.id).forEach(::walk)
        }
        tree.node(conversationId)?.let(::walk)
        out
    }
    val mediaNodes = conversationNodes.filter { it.metadata.containsKey(Conversations.IMAGE_KEY) }
    val linkUrls = Regex("https?://\\S+")
        .findAll(conversationNodes.joinToString("\n") { it.content })
        .map { it.value }
        .distinct()
        .toList()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
            HyleButton("‹ Back", onClick = onClose)
        }
        HyleTitle("Participants")
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Your council members — like a group chat. Each has a name, its own instructions, " +
                    "its own model, and long-term memory. The personas council sends every message " +
                    "to all of them in parallel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (specs.isEmpty()) {
                Text(
                    "No runnable models yet — download one (Models) or add a cloud provider (Settings → " +
                        "Text), then members can each pick a different model.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Only matters once there are 2+ members (below that this is just Single) — but kept
            // visible rather than conditionally hidden, so choosing it ahead of adding a second
            // member is possible.
            Text("With 2+ members", style = MaterialTheme.typography.labelSmall, color = c.textMid)
            HyleSegmentedToggle(
                options = listOf("Personas — named experts", "Models — one prompt, diversity"),
                selected = if (diversityMode) 1 else 0,
                onSelect = { diversityMode = it == 1 },
                modifier = Modifier.fillMaxWidth(),
            )

            HyleField(
                value = search, onValueChange = { search = it },
                label = "Search models or personas to add", singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (search.isNotBlank()) {
                if (searchMatches.isEmpty()) {
                    Text(
                        "No matching models. Use \"Add member\" below for a blank/custom persona.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        searchMatches.forEach { (idx, label) ->
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        val modelId = if (idx == 0) null else specs[idx - 1].id
                                        rows.add(
                                            Participant(
                                                UUID.randomUUID().toString(), name = label,
                                                instructions = "", modelId = modelId, memory = "",
                                            ),
                                        )
                                        search = ""
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            rows.forEachIndexed { i, p ->
                WireBox {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Member ${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        HyleButton("Remove", onClick = { rows.removeAt(i) })
                    }
                    Spacer(Modifier.height(6.dp))
                    DesktopHyleField(
                        value = p.name, onValueChange = { rows[i] = p.copy(name = it) },
                        label = "Name", singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    DesktopHyleField(
                        value = p.instructions, onValueChange = { rows[i] = p.copy(instructions = it) },
                        label = "Instructions", singleLine = false, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    HyleDropdownField(
                        value = labelFor(p.modelId),
                        options = modelOptions,
                        onSelect = { idx -> rows[i] = p.copy(modelId = if (idx == 0) null else specs[idx - 1].id) },
                        label = "Model",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    DesktopHyleField(
                        value = p.memory, onValueChange = { rows[i] = p.copy(memory = it) },
                        label = "Long-term memory", singleLine = false, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Files: soon — attaching documents to a member needs file→context plumbing the engines don't have yet.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HyleButton("Add member", onClick = {
                    rows.add(Participant(UUID.randomUUID().toString(), "New member", ""))
                })
                HyleButton(
                    "Save",
                    onClick = {
                        store.setAll(rows.toList())
                        val mode = when {
                            rows.size <= 1 -> ComposerMode.SINGLE
                            diversityMode -> ComposerMode.MODELS
                            else -> ComposerMode.PERSONAS
                        }
                        onModeChange(mode)
                        onClose()
                    },
                )
            }

            // Artifacts — WhatsApp's "Media, links, and docs," scoped to this conversation.
            WireBox {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { artifactsExpanded = !artifactsExpanded },
                ) {
                    Text("Media, links, and docs", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(if (artifactsExpanded) "▲" else "▼", style = MaterialTheme.typography.labelSmall, color = c.textMid)
                }
                if (artifactsExpanded) {
                    Spacer(Modifier.height(8.dp))
                    Text("Media", style = MaterialTheme.typography.labelSmall, color = c.textMid)
                    when {
                        conversationId == null ->
                            Text(
                                "No conversation yet.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        mediaNodes.isEmpty() ->
                            Text(
                                "No image turns in this conversation yet.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        else -> {
                            Text(
                                "${mediaNodes.size} image turn(s)",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                mediaNodes.take(6).forEach { node ->
                                    node.metadata[Conversations.IMAGE_KEY]?.let { path ->
                                        FileImage(path, Modifier.size(48.dp))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Links", style = MaterialTheme.typography.labelSmall, color = c.textMid)
                    when {
                        conversationId == null ->
                            Text(
                                "No conversation yet.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        linkUrls.isEmpty() ->
                            Text(
                                "No links shared in this conversation yet.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        else -> linkUrls.forEach { url ->
                            Text(
                                url, style = MaterialTheme.typography.labelSmall, color = c.violet,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Docs", style = MaterialTheme.typography.labelSmall, color = c.textMid)
                    Text(
                        "Docs: not wired yet — file attachments aren't part of the message context model.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // AI-relevant options — the "Manage storage" analogue is "Manage context": how much is
            // actually in this conversation's context, honestly (no trim/compact action exists to
            // wire up yet, so it's a labelled placeholder rather than a fake button — rule 6).
            WireBox {
                Text("Manage context", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (conversationId == null) {
                        "No active conversation yet — this will show the turn count once you start chatting."
                    } else {
                        "${conversationNodes.size} turn(s) in this conversation's context."
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Trim/compact context: planned — every turn is currently sent in full; there's no " +
                        "trim or summarize action wired up yet.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // Empty group — WhatsApp's "Exit group," except you own this council rather than
            // belonging to someone else's, so the destructive action is emptying it out.
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, c.error, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                if (!confirmingEmpty) {
                    Text(
                        "Empty group",
                        style = MaterialTheme.typography.labelMedium, color = c.error,
                        modifier = Modifier.clickable { confirmingEmpty = true },
                    )
                    Text(
                        "Removes every participant. There's no \"exit\" for a council you own — this is its equivalent.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    Text(
                        "Remove all ${rows.size} participant(s)? This can't be undone.",
                        style = MaterialTheme.typography.labelSmall, color = c.error,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HyleButton("Cancel", onClick = { confirmingEmpty = false }, secondary = true)
                        HyleButton(
                            "Confirm — empty group",
                            onClick = {
                                rows.clear()
                                store.setAll(rows.toList())
                                confirmingEmpty = false
                                onModeChange(ComposerMode.SINGLE)
                            },
                        )
                    }
                }
            }
        }
    }
}
