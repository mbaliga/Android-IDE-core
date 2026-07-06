package dev.aarso.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A deliberately minimal recovery surface shown when the previous launch left a captured crash
 * (see [dev.aarso.CrashLog]). It uses ONLY stock Material3 + the platform font — no AarsoTheme,
 * no bundled font, no grain shader — so it cannot re-trigger a theming/first-frame crash. It
 * shows the trace (so the cause is visible and shareable) and offers Continue / Share / Reset.
 */
@Composable
fun RecoveryScreen(
    trace: String,
    onContinue: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101216))
            .padding(20.dp),
    ) {
        Text("Aarso hit a snag", color = Color(0xFFE6E6E6), fontSize = 22.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "The last launch crashed. This is the recovery screen, not a crash — you're not stuck. " +
                "Share the report so it can be fixed, or reset the app's local data to continue.",
            color = Color(0xFF9AA0A6),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onShare) { Text("Share report") }
            OutlinedButton(onClick = onContinue) { Text("Continue") }
            OutlinedButton(onClick = onReset) { Text("Reset data") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "“Reset data” wipes local chats/settings (Git-backed loops & tree survive) and restarts.",
            color = Color(0xFF6B7177),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text("Crash report", color = Color(0xFFE6E6E6), fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF16181D))
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                trace,
                color = Color(0xFFD0786B),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}
