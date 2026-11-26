package dev.fonebrew.ui.loops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleChip
import dev.aarso.hyle.cells.HyleDropdownField
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.domain.loop.ConditionSyntax
import dev.fonebrew.domain.loop.EgressNotice
import dev.fonebrew.domain.loop.HttpRequestTool
import dev.fonebrew.domain.loop.LoopParams
import dev.fonebrew.domain.loop.LoopTools
import dev.fonebrew.domain.loop.RunConfirmationSheet
import dev.fonebrew.domain.loop.SearchWorkspaceTool
import dev.fonebrew.domain.loop.ToolNodes
import dev.fonebrew.domain.loop.ToolSideEffect
import dev.fonebrew.domain.loop.authoring.ConditionDraft
import dev.fonebrew.domain.loop.authoring.ConditionKind

/*
 * Wave 2 / lane A4 UI (docs/design/loop-tools.md). Everything here is thin Compose over pure,
 * JVM-tested presenters in domain/loop; the editor feel on a phone is OWNER-VERIFIED ONLY. State is
 * carried by glyph + label first (⇄ network, ▤ local, ✓/⚠/⛔) — never by red/green hue alone.
 */

/** The edit surface for a tool node: pick the tool, fill typed arguments, see its side-effect class. */
@Composable
internal fun ToolNodeDialog(
    name: String,
    ext: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (name: String, toolExt: Map<String, String>) -> Unit,
    onConnect: () -> Unit,
    onDelete: () -> Unit,
) {
    val tools = remember { LoopTools.describeOnly.all }
    val initialId = ext[ToolNodes.EXT_TOOL] ?: SearchWorkspaceTool.ID
    var toolId by remember { mutableStateOf(initialId) }
    var n by remember { mutableStateOf(name) }
    val raw = remember { ToolNodes.rawArgs(dev.fonebrew.domain.bpmn.BpmnNode("x", dev.fonebrew.domain.bpmn.BpmnNodeKind.SERVICE_TASK, ext = ext)) }
    var args by remember { mutableStateOf(raw) }
    val tool = tools.firstOrNull { it.id == toolId }
    val unknownTool = tool == null
    // `${param}` values are only knowable at run start, so validate them there; everything else live.
    val hasParams = args.values.any { LoopParams.placeholdersIn(it).isNotEmpty() }
    val problem = if (tool == null) "Unsupported in this version: tool \"$toolId\"." else if (hasParams) null else tool.validate(args)

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
            Column(
                Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Tool step", style = MaterialTheme.typography.titleMedium)
                HyleField(n, { n = it }, label = "Name", modifier = Modifier.fillMaxWidth())
                HyleDropdownField(
                    value = tool?.let { "${it.sideEffect.glyph} ${it.label}" } ?: "⛔ $toolId (unsupported)",
                    options = tools.map { "${it.sideEffect.glyph} ${it.label}" },
                    onSelect = { idx -> toolId = tools[idx].id; args = emptyMap() },
                    label = "Tool",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tool != null) {
                    val effect = tool.sideEffect
                    Text(
                        "${effect.glyph} ${effect.label.replaceFirstChar { it.uppercase() }}" + when (effect) {
                            ToolSideEffect.NETWORK -> " — contacts the host in the URL. https only, GET/POST, no credentials, 15 s, 256 KB. The response is untrusted data."
                            ToolSideEffect.READ_LOCAL -> " — reads your on-device index only. Nothing leaves the device from this step."
                        },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (a in tool.args) {
                        if (tool.id == HttpRequestTool.ID && a.name == "method") {
                            val methods = listOf("GET", "POST")
                            HyleDropdownField(
                                value = args["method"]?.uppercase() ?: "GET", options = methods,
                                onSelect = { args = args + ("method" to methods[it]) }, label = "Method", modifier = Modifier.fillMaxWidth(),
                            )
                        } else if (tool.id == HttpRequestTool.ID && a.name == "contentType") {
                            val types = listOf("text/plain", "application/json")
                            HyleDropdownField(
                                value = args["contentType"] ?: "text/plain", options = types,
                                onSelect = { args = args + ("contentType" to types[it]) }, label = "Content type (POST only)", modifier = Modifier.fillMaxWidth(),
                            )
                        } else if (tool.id == HttpRequestTool.ID && a.name == "body" && (args["method"]?.uppercase() ?: "GET") != "POST") {
                            // A body is only legal for POST; hide the field rather than offer an invalid state.
                        } else {
                            HyleField(
                                args[a.name].orEmpty(), { args = args + (a.name to it) },
                                label = a.name + if (a.required) " *" else "", singleLine = a.name != "body", mandatory = a.required,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(a.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                problem?.let { Text("⚠ $it", style = MaterialTheme.typography.labelSmall, color = LocalHyleColors.current.violet) }
                if (hasParams) Text("◇ \${…} placeholders are checked when you press Run.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row {
                        TextButton(onClick = onConnect) { Text("Connect") }
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                    Row {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        HyleButton("Save", enabled = !unknownTool && problem == null, onClick = { onSave(n, ToolNodes.toExt(toolId, args)) })
                    }
                }
            }
        }
    }
}

/**
 * Structured condition chips + fields, replacing the free-text field. [onChange] reports the saved
 * condition text, `""` for "none", or `null` while the draft is incomplete (the caller must not
 * commit then). No free-form entry and no pattern syntax exist here by construction.
 */
@Composable
internal fun ConditionBuilder(initial: String, onChange: (String?) -> Unit) {
    var draft by remember { mutableStateOf(ConditionDraft.from(initial) ?: ConditionDraft()) }
    fun update(d: ConditionDraft) { draft = d; onChange(if (d.kind == null) "" else d.toText()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Condition (optional) — tested on the last step's output", style = MaterialTheme.typography.labelMedium)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HyleChip(draft.kind == null, { update(ConditionDraft()) }, "○ none (label only)")
            for (k in ConditionKind.entries) {
                HyleChip(draft.kind == k, { update(draft.copy(kind = k)) }, "${k.glyph} ${k.label}")
            }
        }
        val k = draft.kind
        if (k != null && (k.takesText || k.takesNumber)) {
            HyleField(
                draft.text, { update(draft.copy(text = if (k.takesNumber) it.filter(Char::isDigit).take(6) else it.take(ConditionSyntax.MAX_TEXT))) },
                label = if (k.takesNumber) "Characters" else "Text (literal, not a pattern)", modifier = Modifier.fillMaxWidth(),
            )
        }
        if (k != null && k.hasCase) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HyleChip(!draft.ignoreCase, { update(draft.copy(ignoreCase = false)) }, "Aa case-sensitive")
                HyleChip(draft.ignoreCase, { update(draft.copy(ignoreCase = true)) }, "aa ignore case")
            }
        }
        draft.problem()?.let { Text("⚠ $it", style = MaterialTheme.typography.labelSmall, color = LocalHyleColors.current.violet) }
        draft.build()?.let { Text("◇ Takes this edge when ${ConditionSyntax.describe(it)}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            "No branch matches → the default (unconditioned) edge, and the run log says so.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The run-start confirmation block shown inside the run sheet. Returns via [onConfirmedChange]. */
@Composable
internal fun RunConfirmationBlock(
    sheet: RunConfirmationSheet,
    confirmed: Boolean,
    onConfirmedChange: (Boolean) -> Unit,
) {
    if (!sheet.needed) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Confirm before running", style = MaterialTheme.typography.titleSmall)
        Text(sheet.headline, style = MaterialTheme.typography.bodySmall)
        sheet.toolLines.forEach { Text(it, style = MaterialTheme.typography.labelMedium) }
        sheet.hostLines.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        sheet.warnings.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = LocalHyleColors.current.violet) }
        if (!sheet.blocked) {
            HyleChip(confirmed, { onConfirmedChange(!confirmed) }, if (confirmed) "✓ I confirm these tools and hosts" else "☐ Tap to confirm these tools and hosts")
        }
    }
}

/** The import review's egress notice, prominent and glyph-led. */
@Composable
internal fun EgressNoticeBlock(notice: EgressNotice) {
    if (!notice.present) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        notice.lines().forEachIndexed { i, line ->
            Text(
                if (i == 0 && notice.hosts.isNotEmpty()) "⚠ $line" else line,
                style = if (i == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                color = LocalHyleColors.current.violet,
            )
        }
    }
}
