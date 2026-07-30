package dev.aarso.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aarso.ui.hyle.HyleButton
import kotlinx.coroutines.launch

/**
 * First-run intro: two screens stating the stance, once. Not a tour — the app's
 * argument in its own words (on-device by default, no telemetry; cloud is opt-in
 * and watched; the tree keeps every fork visible). Ends in Chat, where the setup
 * card takes over with the actual model download.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> OnboardingPage(
                        title = "Aarso",
                        subtitle = "“mirror” — Konkani",
                        body = "AI models run on this phone. Your words stay on it.\n\n" +
                            "No accounts. No analytics. No telemetry — ever.",
                    )
                    1 -> OnboardingPage(
                        title = "Nothing hidden",
                        subtitle = "including from yourself",
                        body = "Cloud models are opt-in, per use, and always marked as watched.\n\n" +
                            "Every conversation is a tree: every fork, retry, and model switch " +
                            "stays visible and reversible.",
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(2) { i ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (pager.currentPage == i) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (pager.currentPage == 0) {
                    TextButton(onClick = onDone) { Text("Skip") }
                    HyleButton("Continue", onClick = { scope.launch { pager.animateScrollToPage(1) } })
                } else {
                    HyleButton("Begin", onClick = onDone)
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(title: String, subtitle: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
