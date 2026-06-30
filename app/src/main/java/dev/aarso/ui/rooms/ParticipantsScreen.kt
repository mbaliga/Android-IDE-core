package dev.aarso.ui.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aarso.AarsoApp
import dev.aarso.data.Participant
import dev.aarso.ui.hyle.HyleButton
import dev.aarso.ui.hyle.HyleDropdownField
import dev.aarso.ui.hyle.HyleTitle
import dev.aarso.ui.wire.WireBox
import java.util.UUID

/**
 * Manage the council's participants like a group chat (IA §B4): add / remove members, and define
 * each one individually — name, instructions, **its own model** (on-device or watched cloud),
 * and **long-term memory**. Saved to [dev.aarso.data.CouncilStore]; the personas council fans
 * out over this roster. Per-member **files** are an honest "soon" (rule 6 — not wired yet).
 */
@Composable
fun ParticipantsScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val container = (LocalContext.current.applicationContext as AarsoApp).container
    val store = container.councilStore
    val saved by store.participants.collectAsState()

    // Runnable models = downloaded on-device + configured cloud providers (same source the model
    // picker uses). Listed so any member can run a different one.
    val specs = remember { container.modelRegistry.allSpecs().filter { container.engineProvider.isRunnable(it) } }
    val modelOptions = listOf("Active model (default)") + specs.map { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName }
    fun labelFor(modelId: String?): String =
        modelId?.let { id -> specs.firstOrNull { it.id == id }?.let { (if (it.isOnDevice) "⌂ " else "☁ ") + it.displayName } }
            ?: "Active model (default)"

    val rows = remember { mutableStateListOf<Participant>().apply { addAll(saved) } }

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

            rows.forEachIndexed { i, p ->
                WireBox {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Member ${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        HyleButton("Remove", onClick = { rows.removeAt(i) })
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = p.name, onValueChange = { rows[i] = p.copy(name = it) },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = p.instructions, onValueChange = { rows[i] = p.copy(instructions = it) },
                        label = { Text("Instructions") }, modifier = Modifier.fillMaxWidth(),
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
                    OutlinedTextField(
                        value = p.memory, onValueChange = { rows[i] = p.copy(memory = it) },
                        label = { Text("Long-term memory") }, modifier = Modifier.fillMaxWidth(),
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
                HyleButton("Save", onClick = { store.setAll(rows.toList()); onClose() })
            }
        }
    }
}
