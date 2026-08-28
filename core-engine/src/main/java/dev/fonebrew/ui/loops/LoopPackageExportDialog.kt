package dev.fonebrew.ui.loops

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleChip
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.cells.HyleFocusLens
import dev.aarso.hyle.cells.HyleLensActions
import dev.aarso.hyle.cells.HyleLensHeading
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.domain.bpmn.BpmnGraph
import dev.fonebrew.domain.loop.LoopPackageCodec

/** A short common SPDX list plus a free field — export UI never invents a license, it only offers one. */
private val COMMON_SPDX_LICENSES = listOf(
    "MIT", "Apache-2.0", "BSD-3-Clause", "GPL-3.0-only", "LGPL-3.0-only",
    "MPL-2.0", "CC0-1.0", "CC-BY-4.0", "Proprietary", "Other…",
)

private val SEMVER = Regex("^\\d+\\.\\d+\\.\\d+$")

/**
 * "Export package…" (LoopRoom): a small metadata editor over [LoopPackageCodec], writing the
 * result via SAF `CREATE_DOCUMENT` (owner-verified — no device in this build environment).
 * Loop ID/version are prefilled; license is an SPDX pick-list plus a free field; provenance
 * (producer/version/timestamp) is filled from what the app actually knows, never invented —
 * the user only supplies license and author.
 */
@Composable
fun ExportLoopPackageDialog(
    graph: BpmnGraph,
    objective: String,
    suggestedLoopId: String,
    onDismiss: () -> Unit,
    onExported: (fileName: String) -> Unit,
) {
    val context = LocalContext.current
    // core-engine carries no versionName of its own (SettingsRoom.kt's About screen has the same
    // constraint) — read the shipping app's real version live via PackageManager.
    val appVersionName = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var loopId by remember { mutableStateOf(suggestedLoopId) }
    var version by remember { mutableStateOf("1.0.0") }
    var license by remember { mutableStateOf(COMMON_SPDX_LICENSES.first()) }
    var customLicense by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingFileName by remember { mutableStateOf("") }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = pendingBytes
        if (uri != null && bytes != null) {
            val wrote = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            if (wrote) onExported(pendingFileName)
        }
        onDismiss()
    }

    val versionValid = version.matches(SEMVER)
    val idValid = loopId.isNotBlank()

    HyleFocusLens(visible = true, onDismiss = onDismiss) {
        HyleLensHeading(
            "Export package…",
            "Writes a ${LoopPackageCodec.PACKAGE_FILE_EXTENSION} file the recipient can inspect and " +
                "import elsewhere — graph, license, and provenance travel with it. Unsigned: this build " +
                "has no publisher signing key.",
        )
        Column(
            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HyleField(loopId, { loopId = it }, label = "Loop ID", mandatory = true, modifier = Modifier.fillMaxWidth())
            HyleField(version, { version = it }, label = "Version (SemVer, e.g. 1.0.0)", mandatory = true, modifier = Modifier.fillMaxWidth())
            if (!versionValid) {
                Text("Version must look like 1.0.0.", style = MaterialTheme.typography.labelSmall, color = LocalHyleColors.current.violet)
            }
            Text("License", style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // HyleChip has no wrap layout of its own — a short fixed list fits three per row on a phone.
                for (rowStart in COMMON_SPDX_LICENSES.indices step 3) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in rowStart until minOf(rowStart + 3, COMMON_SPDX_LICENSES.size)) {
                            val l = COMMON_SPDX_LICENSES[i]
                            HyleChip(license == l, { license = l }, l)
                        }
                    }
                }
            }
            if (license == "Other…") {
                HyleField(customLicense, { customLicense = it }, label = "License (SPDX id or free text)", modifier = Modifier.fillMaxWidth())
            }
            HyleField(author, { author = it }, label = "Author (optional)", modifier = Modifier.fillMaxWidth())
            HyleField(note, { note = it }, label = "Notes for the recipient (optional)", singleLine = false, modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            Text(
                "Provenance filled in automatically: producer \"Fonebrew\" $appVersionName, exported just now. " +
                    "No capabilities are requested — loop nodes in this app carry no device/network/repo access today.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HyleLensActions {
            HyleButton("Cancel", secondary = true, onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            HyleButton(
                "Export…",
                enabled = idValid && versionValid,
                onClick = {
                    val effectiveLicense = (if (license == "Other…") customLicense else license).ifBlank { null }
                    val fileName = "$loopId-$version${LoopPackageCodec.PACKAGE_FILE_EXTENSION}"
                    val bytes = LoopPackageCodec.encode(
                        LoopPackageCodec.ExportRequest(
                            graph = graph, objective = objective, loopId = loopId, semanticVersion = version,
                            licenseId = effectiveLicense, authorName = author.ifBlank { null },
                            appVersion = appVersionName,
                            readme = note.ifBlank { null },
                        ),
                    )
                    pendingBytes = bytes; pendingFileName = fileName
                    saveLauncher.launch(fileName)
                },
            )
        }
    }
}
