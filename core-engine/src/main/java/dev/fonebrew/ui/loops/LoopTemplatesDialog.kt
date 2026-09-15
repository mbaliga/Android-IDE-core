package dev.fonebrew.ui.loops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aarso.hyle.cells.HyleButton
import dev.aarso.hyle.cells.HyleCard
import dev.aarso.hyle.cells.HyleFocusLens
import dev.aarso.hyle.cells.HyleLensActions
import dev.aarso.hyle.cells.HyleLensHeading
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.domain.loop.LoopTemplateCatalog
import dev.fonebrew.domain.loop.LoopTemplateInfo

/**
 * "Templates" (LoopRoom toolbar): browses [LoopTemplateCatalog]'s bundled loop **packages**
 * (asoc-reachability audit item 5, 2026-09-15) — [dev.fonebrew.data.LoopTemplateAssets] had a
 * loader with no caller anywhere in the app; this is that caller. Picking one does NOT install
 * it directly: [onPick] hands the choice back to [LoopRoom], which reads the asset bytes and
 * opens them through the SAME `ImportLoopPackageDialog` (decode+scan, then the full
 * authority-review screen) a user-picked `.floop.json` file gets — a bundled template is not a
 * trust shortcut around that review.
 */
@Composable
fun LoopTemplatesDialog(
    templates: List<LoopTemplateInfo>,
    onDismiss: () -> Unit,
    onPick: (LoopTemplateInfo) -> Unit,
) {
    val c = LocalHyleColors.current
    HyleFocusLens(visible = true, onDismiss = onDismiss) {
        HyleLensHeading(
            "Templates",
            "Bundled starting points, reviewed the same way as any imported package before anything runs.",
        )
        if (templates.isEmpty()) {
            Text(
                "No bundled templates in this build.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMid,
            )
        } else {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (t in templates) {
                    HyleCard(modifier = Modifier.fillMaxWidth()) {
                        Text(t.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(t.description, style = MaterialTheme.typography.bodySmall, color = c.textMid)
                        HyleButton(
                            "Use this template",
                            onClick = { onPick(t) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        HyleLensActions { HyleButton("Cancel", secondary = true, onClick = onDismiss) }
    }
}
