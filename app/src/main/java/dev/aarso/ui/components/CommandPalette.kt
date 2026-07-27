package dev.aarso.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.cells.HyleCard
import dev.aarso.hyle.theme.LocalHyleColors

/**
 * One `/`-invoked action: a canned command with a name, a one-line description, and what it
 * does. Shared between Chat's composer and Terminal's input — a single library rather than a
 * copy per surface, so a command written once shows up wherever `/` is typed that registers it.
 */
data class SlashCommand(val name: String, val description: String, val run: () -> Unit)

/** One `@`-invoked mention target: a council participant, a model, or anything else a message
 *  can be directed at. [insertion] is what actually lands in the composer text on pick (usually
 *  `"@$label "` — the trailing space so typing continues past it without a manual space). */
data class MentionTarget(val label: String, val description: String, val insertion: String)

/** Slash commands whose [SlashCommand.name] starts with what's typed so far — empty unless the
 *  whole input is currently a `/`-prefixed token (a command is a whole-input shortcut, not
 *  something you type mid-sentence). */
fun matchSlashCommands(input: String, commands: List<SlashCommand>): List<SlashCommand> =
    if (input.startsWith("/")) commands.filter { it.name.startsWith(input.trim(), ignoreCase = true) } else emptyList()

/** The `@`-token currently being typed, if the cursor is sitting right after one with no
 *  intervening space — e.g. typing `hey @jud` mid-sentence matches `jud`, but `@judge, hey`
 *  (space already after the mention) does not. Unlike a slash command, a mention can start
 *  anywhere in the message, not just at the front. */
private fun activeMentionQuery(input: String): String? {
    val at = input.lastIndexOf('@')
    if (at < 0) return null
    val tail = input.substring(at + 1)
    if (tail.contains(' ') || tail.contains('\n')) return null
    return tail
}

/** Mention targets matching the `@`-token currently being typed, if any. */
fun matchMentions(input: String, targets: List<MentionTarget>): List<MentionTarget> {
    val query = activeMentionQuery(input) ?: return emptyList()
    return targets.filter { it.label.startsWith(query, ignoreCase = true) }
}

/** Replaces the in-progress `@token` at the end of [input] with [target]'s full insertion text. */
fun applyMention(input: String, target: MentionTarget): String {
    val at = input.lastIndexOf('@')
    if (at < 0) return input
    return input.substring(0, at) + target.insertion
}

/** The popup shown above a composer/input field while [commands] match what's typed so far. */
@Composable
fun SlashCommandPopup(commands: List<SlashCommand>, onPick: (SlashCommand) -> Unit) {
    val c = LocalHyleColors.current
    HyleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        commands.forEachIndexed { i, cmd ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onPick(cmd) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(cmd.name, style = MaterialTheme.typography.labelMedium, color = c.violet, modifier = Modifier.padding(end = 10.dp))
                Text(cmd.description, style = MaterialTheme.typography.labelSmall, color = c.textMid)
            }
            if (i != commands.lastIndex) HorizontalDivider(color = c.hairline)
        }
    }
}

/** The popup shown above a composer/input field while [targets] match the `@`-token being typed. */
@Composable
fun MentionPopup(targets: List<MentionTarget>, onPick: (MentionTarget) -> Unit) {
    val c = LocalHyleColors.current
    HyleCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        targets.forEachIndexed { i, t ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("@${t.label}", style = MaterialTheme.typography.labelMedium, color = c.violet, modifier = Modifier.padding(end = 10.dp))
                Text(t.description, style = MaterialTheme.typography.labelSmall, color = c.textMid)
            }
            if (i != targets.lastIndex) HorizontalDivider(color = c.hairline)
        }
    }
}
