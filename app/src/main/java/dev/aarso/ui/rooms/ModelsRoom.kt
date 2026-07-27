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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aarso.data.DownloadCenter
import dev.aarso.domain.device.FitVerdict
import dev.aarso.ui.ImagesViewModel
import dev.aarso.ui.ModelsViewModel
import dev.aarso.hyle.cells.HyleButton
import androidx.compose.ui.platform.LocalContext
import dev.aarso.hyle.cells.HyleCard
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.cells.HyleTitle
import dev.aarso.hyle.theme.LocalHyleColors
import kotlin.math.absoluteValue

/**
 * The on-device Chat shelf (§5/§10): Settings → Models → Text → On-device opens straight into
 * this — no intermediate Chat/Image/Bring-your-own tabs and no On-device/Cloud toggle, both of
 * which used to duplicate the choice the owner already made one level up (owner-flagged, the
 * old [ModelsRoom]'s "Manage on-device models" button opened the WHOLE tabbed room instead of
 * just this shelf). Each card is large and visually rich (gradient header + big monogram, no
 * logo), with the full download lifecycle inline, then bring-your-own-GGUF, then what's already
 * on this device.
 *
 * Rendered inside [SettingsRoom]'s hoisted `overlay` slot (root-level, outside the scrolling
 * content) rather than inline — a [Coverflow]'s `HorizontalPager` measured inside a
 * `verticalScroll` parent gets an infinite height constraint and crashes (the PR #39 fix this
 * file's sibling already paid for; see [SettingsRoom]'s KDoc). Full-screen here buys the room a
 * pager needs without reopening that crash class.
 */
@Composable
fun ChatOnDeviceShelf(
    downloads: DownloadCenter,
    onCustomUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    modelsViewModel: ModelsViewModel = viewModel(factory = ModelsViewModel.Factory),
) {
    if (onClose != null) BackHandler(onBack = onClose)
    val downloaded by modelsViewModel.downloaded.collectAsState()
    val active by downloads.active.collectAsState()
    var customUrl by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (onClose != null) {
            TextButton(onClick = onClose, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
                Text("‹ Settings")
            }
        }
        HyleTitle("On-device chat models")
        DeviceFitLine()
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Coverflow(modelsViewModel.catalog.size) { page ->
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
            Spacer(Modifier.height(16.dp))
            BringYourOwn(
                hint = "Point at any GGUF and it downloads to this device. Bigger files need " +
                    "more RAM to run; the fit check applies once it lands.",
                label = "GGUF URL",
                placeholder = "https://huggingface.co/…/file.gguf",
                url = customUrl,
                onUrlChange = { customUrl = it },
                enabled = customUrl.endsWith(".gguf"),
                onDownload = { onCustomUrl(customUrl); customUrl = "" },
            )
            if (downloaded.isNotEmpty()) {
                OnThisDeviceHeading()
                downloaded.forEach { local ->
                    LocalRow(
                        local.name,
                        local.sizeBytes,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    ) { modelsViewModel.delete(local) }
                }
            }
        }
    }
}

/** The on-device Image shelf — [ChatOnDeviceShelf]'s Stable Diffusion counterpart, same shape. */
@Composable
fun ImageOnDeviceShelf(
    downloads: DownloadCenter,
    onCustomUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    imagesViewModel: ImagesViewModel = viewModel(factory = ImagesViewModel.Factory),
) {
    if (onClose != null) BackHandler(onBack = onClose)
    val sdDownloaded by imagesViewModel.sdModels.collectAsState()
    val active by downloads.active.collectAsState()
    var customUrl by remember { mutableStateOf("") }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (onClose != null) {
            TextButton(onClick = onClose, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
                Text("‹ Settings")
            }
        }
        HyleTitle("On-device image models")
        DeviceFitLine()
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Coverflow(imagesViewModel.sdCatalog.size) { page ->
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
            Spacer(Modifier.height(16.dp))
            BringYourOwn(
                hint = "Point at a Stable Diffusion checkpoint (GGUF) and it downloads to this " +
                    "device. Bigger files need more RAM to run; the fit check applies once it lands.",
                label = "Checkpoint URL",
                placeholder = "https://huggingface.co/…/file.gguf",
                url = customUrl,
                onUrlChange = { customUrl = it },
                enabled = customUrl.endsWith(".gguf"),
                onDownload = { onCustomUrl(customUrl); customUrl = "" },
            )
            if (sdDownloaded.isNotEmpty()) {
                OnThisDeviceHeading()
                sdDownloaded.forEach { local ->
                    LocalRow(
                        local.name,
                        local.sizeBytes,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    ) { imagesViewModel.deleteSdModel(local) }
                }
            }
        }
    }
}

/** "This device: N GB RAM · abi · fit disclaimer" — identical wording in both shelves. Reads
 *  the device spec directly rather than through a viewmodel — [ImagesViewModel]'s own copy is
 *  private, and it's the same physical device either way. */
@Composable
private fun DeviceFitLine() {
    val device = dev.aarso.data.DeviceInfo.read(LocalContext.current)
    val ramGb = "%.1f".format(device.totalRamBytes / 1_000_000_000.0)
    Text(
        "This device: $ramGb GB RAM · " +
            (if (device.arm64) "arm64-v8a" else device.abis.joinToString()) +
            "  ·  fit is a RAM safety check, not a speed promise.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

/** Bring-your-own — nested under On-device (owner ask), not a sibling tab of it. */
@Composable
private fun BringYourOwn(
    hint: String,
    label: String,
    placeholder: String,
    url: String,
    onUrlChange: (String) -> Unit,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Bring your own", style = MaterialTheme.typography.titleSmall)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HyleField(
            value = url,
            onValueChange = onUrlChange,
            label = label,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        HyleButton("Download from URL", onClick = onDownload, enabled = enabled, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun OnThisDeviceHeading() {
    Text(
        "On this device",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
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
private fun LocalRow(name: String, size: Long, modifier: Modifier = Modifier, onDelete: () -> Unit) {
    HyleCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
