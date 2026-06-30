package dev.aarso.ui.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aarso.data.DownloadCenter
import dev.aarso.domain.device.FitVerdict
import dev.aarso.ui.ImagesViewModel
import dev.aarso.ui.ModelsViewModel
import dev.aarso.ui.hyle.HyleButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.HorizontalDivider
import dev.aarso.AarsoApp
import dev.aarso.ui.hyle.HyleChip
import dev.aarso.ui.hyle.HyleField
import dev.aarso.ui.hyle.HyleTitle
import dev.aarso.ui.theme.LocalHyleColors
import kotlin.math.absoluteValue

private enum class ModelsTab { CHAT, IMAGE, BYO }
private enum class ModelSource { ON_DEVICE, CLOUD }

/**
 * The shelf beneath the thread (§5/§10): models as a tabbed coverflow — Chat,
 * Image, Bring-your-own. Each card is large and visually rich (gradient header +
 * big monogram, no logo), with the full download lifecycle inline.
 */
@Composable
fun ModelsRoom(
    downloads: DownloadCenter,
    onCustomUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    modelsViewModel: ModelsViewModel = viewModel(factory = ModelsViewModel.Factory),
    imagesViewModel: ImagesViewModel = viewModel(factory = ImagesViewModel.Factory),
) {
    if (onClose != null) BackHandler(onBack = onClose)
    val downloaded by modelsViewModel.downloaded.collectAsState()
    val sdDownloaded by imagesViewModel.sdModels.collectAsState()
    val active by downloads.active.collectAsState()
    var customUrl by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(ModelsTab.CHAT) }
    var source by remember { mutableStateOf(ModelSource.ON_DEVICE) }
    val cloudProviders by (LocalContext.current.applicationContext as AarsoApp).container.providerStore.providers.collectAsState()

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (onClose != null) {
            TextButton(onClick = onClose, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
                Text("‹ Settings")
            }
        }
        HyleTitle("Models")
        val ramGb = "%.1f".format(modelsViewModel.device.totalRamBytes / 1_000_000_000.0)
        Text(
            "This device: $ramGb GB RAM · " +
                (if (modelsViewModel.device.arm64) "arm64-v8a" else modelsViewModel.device.abis.joinToString()) +
                "  ·  fit is a RAM safety check, not a speed promise.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HyleChip(tab == ModelsTab.CHAT, { tab = ModelsTab.CHAT }, "Chat")
            HyleChip(tab == ModelsTab.IMAGE, { tab = ModelsTab.IMAGE }, "Image")
            HyleChip(tab == ModelsTab.BYO, { tab = ModelsTab.BYO }, "Bring your own")
        }
        // Source filter (Chat tab): on-device vs watched-cloud (owner ask).
        if (tab == ModelsTab.CHAT) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HyleChip(source == ModelSource.ON_DEVICE, { source = ModelSource.ON_DEVICE }, "On-device")
                HyleChip(source == ModelSource.CLOUD, { source = ModelSource.CLOUD }, "Cloud · watched")
            }
        }
        Spacer(Modifier.height(16.dp))

        when (tab) {
            ModelsTab.CHAT -> if (source == ModelSource.CLOUD) {
                CloudProvidersList(cloudProviders)
            } else Coverflow(modelsViewModel.catalog.size) { page ->
                val m = modelsViewModel.catalog[page]
                val fit = modelsViewModel.fit(m.sizeBytes)
                CoverCard(
                    name = m.name,
                    spec = "${m.params} · ${m.quant} · %.1f GB".format(m.sizeBytes / 1_000_000_000.0),
                    fitVerdict = fit.verdict,
                    fitReason = fit.reason,
                    state = active[m.id],
                    downloaded = modelsViewModel.isDownloaded(m.hfFile),
                    onDownload = { modelsViewModel.downloadCatalog(m) },
                    onPause = { downloads.pause(m.id) },
                    onResume = { downloads.retry(m.id) },
                    onCancel = { downloads.cancel(m.id) },
                )
            }
            ModelsTab.IMAGE -> Coverflow(imagesViewModel.sdCatalog.size) { page ->
                val m = imagesViewModel.sdCatalog[page]
                val id = "sd:${m.fileName}"
                val fit = imagesViewModel.fit(m.sizeBytes)
                CoverCard(
                    name = m.name,
                    spec = "${m.family} · %.1f GB · ${m.note}".format(m.sizeBytes / 1_000_000_000.0),
                    fitVerdict = fit.verdict,
                    fitReason = fit.reason,
                    state = active[id],
                    downloaded = sdDownloaded.any { it.name == m.fileName },
                    onDownload = { imagesViewModel.downloadSdModel(m.url) },
                    onPause = { downloads.pause(id) },
                    onResume = { downloads.retry(id) },
                    onCancel = { downloads.cancel(id) },
                )
            }
            ModelsTab.BYO -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "Point at any GGUF and it downloads to this device. Bigger files " +
                            "need more RAM to run; the fit check applies once it lands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HyleField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = "GGUF URL",
                        placeholder = "https://huggingface.co/…/file.gguf",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    HyleButton(
                        "Download from URL",
                        onClick = { onCustomUrl(customUrl); customUrl = "" },
                        enabled = customUrl.endsWith(".gguf"),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (downloaded.isNotEmpty() || sdDownloaded.isNotEmpty()) {
                    item {
                        Text(
                            "On this device",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(downloaded.size) { i ->
                        val local = downloaded[i]
                        LocalRow(local.name, local.sizeBytes) { modelsViewModel.delete(local) }
                    }
                    items(sdDownloaded.size) { i ->
                        val local = sdDownloaded[i]
                        LocalRow("${local.name} (image)", local.sizeBytes) { imagesViewModel.deleteSdModel(local) }
                    }
                }
            }
        }
    }
}

/** The watched-cloud models the user has configured. Add/remove lives in Settings → Text. */
@Composable
private fun CloudProvidersList(providers: List<dev.aarso.domain.cloud.CloudProvider>) {
    val c = LocalHyleColors.current
    if (providers.isEmpty()) {
        Text(
            "No cloud providers yet. Add one in Settings → Text — each is a watched object: " +
                "opt-in, isolated, and on-device stays the default.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        items(providers.size) { i ->
            val p = providers[i]
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("watched", style = MaterialTheme.typography.labelSmall, color = c.warning)
                }
                Text(
                    "${p.kind.label} · ${p.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(top = 8.dp), color = c.hairline)
            }
        }
    }
}

/** Horizontal coverflow: the focused card is full size; neighbours shrink and fade. */
@Composable
private fun Coverflow(count: Int, card: @Composable (Int) -> Unit) {
    if (count == 0) {
        Text(
            "Nothing here yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    val state = rememberPagerState(pageCount = { count })
    HorizontalPager(
        state = state,
        contentPadding = PaddingValues(horizontal = 36.dp),
        pageSpacing = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        val offset = ((state.currentPage - page) + state.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
        Box(
            Modifier.graphicsLayer {
                val s = lerp(0.86f, 1f, 1f - offset)
                scaleX = s
                scaleY = s
                alpha = lerp(0.45f, 1f, 1f - offset)
            },
        ) {
            card(page)
        }
    }
}

/** A large, visually rich model card: gradient header + big monogram, then details
 *  and the inline download lifecycle. */
@Composable
private fun CoverCard(
    name: String,
    spec: String,
    fitVerdict: FitVerdict,
    fitReason: String,
    state: DownloadCenter.State?,
    downloaded: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalHyleColors.current
    val fitColor = when (fitVerdict) {
        FitVerdict.FITS -> c.success
        FitVerdict.TIGHT -> c.warning
        FitVerdict.WONT_FIT -> c.error
    }
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, c.hairline),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .background(Brush.verticalGradient(listOf(c.violetDim, c.raised))),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.violet),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.firstOrNull { it.isLetter() }?.uppercase() ?: "?",
                        style = MaterialTheme.typography.headlineMedium,
                        color = c.onViolet,
                    )
                }
            }
            Column(Modifier.padding(16.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Text(spec, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(fitReason, style = MaterialTheme.typography.labelMedium, color = fitColor)
                Spacer(Modifier.height(14.dp))
                DownloadAction(state, downloaded, fitVerdict, onDownload, onPause, onResume, onCancel)
            }
        }
    }
}

/** The shared download lifecycle: on-device / paused / failed / running / download. */
@Composable
private fun DownloadAction(
    state: DownloadCenter.State?,
    downloaded: Boolean,
    fitVerdict: FitVerdict,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalHyleColors.current
    val progress = state?.progress
    when {
        downloaded -> Text("on device", style = MaterialTheme.typography.labelMedium, color = c.success)
        state?.paused == true -> Column {
            LinearProgressIndicator(progress = { progress?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("paused · ${((progress?.fraction ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onResume) { Text("Resume") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        state != null && state.failed -> Column {
            Text("failed: ${progress?.error}", style = MaterialTheme.typography.labelSmall, color = c.error)
            Row {
                TextButton(onClick = onResume) { Text("Retry") }
                TextButton(onClick = onCancel) { Text("Dismiss") }
            }
        }
        state != null && state.running -> Column {
            LinearProgressIndicator(progress = { progress?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        append("${((progress?.fraction ?: 0f) * 100).toInt()}%")
                        if ((progress?.resumedFrom ?: 0) > 0) append(" (resumed)")
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = onPause) { Text("Pause") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        else -> HyleButton(
            if (fitVerdict == FitVerdict.WONT_FIT) "Too large" else "Download",
            onClick = onDownload,
            enabled = fitVerdict != FitVerdict.WONT_FIT,
        )
    }
}

@Composable
private fun LocalRow(name: String, size: Long, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, LocalHyleColors.current.hairline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "%.1f GB".format(size / 1_000_000_000.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}
