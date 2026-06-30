package dev.aarso.hyleprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class HyleProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val tabs = listOf("Radiant glow", "Glass + sand", "Ferrofluid bead", "Lens", "Atoms")
            val pagerState = rememberPagerState { tabs.size }
            val scope = rememberCoroutineScope()

            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF08090C)),
            ) {
                // Content fills above the tab bar; statusBarsPadding keeps each probe's
                // title clear of the status bar / notch (targetSdk 36 draws edge-to-edge).
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .statusBarsPadding(),
                ) { page ->
                    when (page) {
                        0 -> RadiantGlowProbe()
                        1 -> GlassSandProbe()
                        2 -> FerrofluidProbe()
                        3 -> LensProbe()
                        4 -> HyleAtomsProbe()
                    }
                }

                // Tabs at the bottom (owner's call), clear of the gesture nav bar.
                Box(Modifier.background(Color(0xFF0E0F12))) {
                    TabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = Color(0xFF0E0F12),
                        contentColor = Color(0xFFEDEFF3),
                        modifier = Modifier.navigationBarsPadding(),
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                text = {
                                    Text(
                                        title,
                                        fontSize = 12.sp,
                                        color = if (pagerState.currentPage == index)
                                            Color(0xFFEDEFF3) else Color(0xFF5B626E),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
