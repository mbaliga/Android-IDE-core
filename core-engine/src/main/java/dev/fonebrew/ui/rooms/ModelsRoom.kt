package dev.fonebrew.ui.rooms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.aarso.hyle.cells.HyleButton
import androidx.compose.ui.platform.LocalContext
import dev.aarso.hyle.cells.HyleCard
import dev.aarso.hyle.cells.HyleField
import dev.aarso.hyle.theme.LocalHyleColors
import dev.fonebrew.data.DownloadCenter
import dev.fonebrew.domain.device.FitVerdict
import dev.fonebrew.ui.ImagesViewModel
import dev.fonebrew.ui.ModelsViewModel
import kotlin.math.absoluteValue

// MERGE-NOTE (reunification, cluster E): the dev line's shared-catalog "Update" flow —
// AppContainer.modelCatalogUpdater / SessionStore.modelCatalogSourceUrl, a confirm-before-
// network dialog, and a status line ("contacting the source online…" / "updated (list dated
// …)") — had its only UI trigger in the old single-screen ModelsRoom this file used to define
// (tab row + on-device/cloud toggle + "Update" button). The launch line's redesign below
// deliberately removed that room (owner ask: model cards render inline in SettingsRoom now, no
// separate screen, no on-device/cloud re-pick since that choice is already made one level up —
// see [ChatOnDeviceShelf]'s KDoc) and has no replacement affordance for refreshing the catalog
// from its remote source. ModelCatalogUpdater/ModelCatalogStore/catalogLastUpdated are all still
// intact and unused. Needs an owner decision on whether/where a "check for an updated model
// list" control resurfaces (e.g. in SettingsRoom's Models tab header) — not reintroduced here
// unilaterally, since it would mean redesigning chrome the launch line intentionally simplified.

/**
 * The on-device Chat shelf (§5/§10): Settings → Models → Text → On-device shows this directly —
 * no intermediate Chat/Image/Bring-your-own tabs and no On-device/Cloud toggle, both of which
 * would duplicate the choice the owner already made one level up. Each card is large and
 * visually rich (gradient header + big monogram, no logo), with the full download lifecycle
 * inline, then bring-your-own-GGUF, then what's already on this device.
 *
 * Renders directly inline in [LocalModels] now (owner ask: "the model cards are still hidden
 * behind a button" — no extra tap). It used to need [SettingsRoom]'s hoisted `overlay` slot
 * instead, because a [Coverflow]'s `HorizontalPager` measured inside a `verticalScroll` parent
 * gets an infinite height constraint and crashes (PR #39). [Coverflow] now gives its own pager
 * an explicit bounded height (see its KDoc), so that crash class no longer applies and this can
 * render straight into [SettingsRoom]'s already-scrolling content — no dedicated full-screen
 * host required. Plain content composable now: no `onClose`/back-button chrome, no own
 * `fillMaxSize`/scroll — the single `verticalScroll` already belongs to [SettingsRoom]'s content
 * column, and nesting another one (or a `weight(1f)` that needs a bounded parent) underneath it
 * would be broken/redundant.
 */
@Composable
fun ChatOnDeviceShelf(
    downloads: DownloadCenter,
    onCustomUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    modelsViewModel: ModelsViewModel = viewModel(factory = ModelsViewModel.Factory),
) {
    val downloaded by modelsViewModel.downloaded.collectAsState()
    val active by downloads.active.collectAsState()
    var customUrl by remember { mutableStateOf("") }

    Column(modifier) {
        DeviceFitLine()
        Spacer(Modifier.height(12.dp))
        Coverflow(modelsViewModel.catalog.size) { page ->
            val m = modelsViewModel.catalog[page]
            val fit = modelsViewModel.fit(m.sizeBytes)
            CoverCard(
                name = m.name,
                spec = "${m.params} · ${m.quant} · %.1f GB".format(m.sizeBytes / 1_000_000_000.0),
                fitVerdict = fit.verdict,
                fitReason = fit.reason,
                state = active[m.id],
                downloaded = modelsViewModel.isDownloaded(m.fileName),
                available = m.downloadUrl != null,
                onDownload = { modelsViewModel.downloadCatalog(m) },
                onPause = { downloads.pause(m.id) },
                onResume = { downloads.retry(m.id) },
                onCancel = { downloads.cancel(m.id) },
            )
        }
        Spacer(Modifier.height(16.dp))
        BringYourOwnCard(
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

/** The on-device Image shelf — [ChatOnDeviceShelf]'s Stable Diffusion counterpart, same shape,
 *  same "renders directly inline into [LocalModels] now" story (see that KDoc). */
@Composable
fun ImageOnDeviceShelf(
    downloads: DownloadCenter,
    onCustomUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    imagesViewModel: ImagesViewModel = viewModel(factory = ImagesViewModel.Factory),
) {
    val sdDownloaded by imagesViewModel.sdModels.collectAsState()
    val active by downloads.active.collectAsState()
    var customUrl by remember { mutableStateOf("") }

    Column(modifier) {
        DeviceFitLine()
        Spacer(Modifier.height(12.dp))
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
                available = m.downloadUrl != null,
                // Never coerce a null downloadUrl into a download call — SdCatalogModel's own
                // honesty contract (see its KDoc): callers must gate on it being non-null.
                onDownload = { m.downloadUrl?.let { url -> imagesViewModel.downloadSdModel(url) } },
                onPause = { downloads.pause(id) },
                onResume = { downloads.retry(id) },
                onCancel = { downloads.cancel(id) },
            )
        }
        Spacer(Modifier.height(16.dp))
        BringYourOwnCard(
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

/** "This device: N GB RAM · abi · fit disclaimer" — identical wording in both shelves. Reads
 *  the device spec directly rather than through a viewmodel — [ImagesViewModel]'s own copy is
 *  private, and it's the same physical device either way. */
@Composable
private fun DeviceFitLine() {
    val device = dev.fonebrew.data.DeviceInfo.read(LocalContext.current)
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

/**
 * Bring-your-own — nested under On-device (owner ask), not a sibling tab of it. Restyled as a
 * sibling card in [CoverCard]'s own visual family (owner ask #3: "has to be in the format of a
 * card too, for the sake of consistency") — same `Card`/border/shape/elevation and the same
 * gradient-header-plus-monogram structure, not a parallel hand-rolled look. There's no specific
 * model to monogram, so the header glyph is a generic "+" — the same affordance the chat
 * composer's own Gemini-style `+` already carries elsewhere in this app (CLAUDE.md: "bring
 * something outside the preset list").
 *
 * A sibling card directly below [Coverflow], not one more page inside its pager: swiping the
 * pager means "show me another candidate in the same choice," and bring-your-own isn't a
 * candidate to compare — it's a different kind of action. Folding it into the pager would also
 * force [PagerPositionIndicator]'s "N of M" counter into an awkward choice (count it as a
 * "model" and lie, or special-case the last page). A plain sibling avoids both.
 */
@Composable
private fun BringYourOwnCard(
    hint: String,
    label: String,
    placeholder: String,
    url: String,
    onUrlChange: (String) -> Unit,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    val c = LocalHyleColors.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = c.inset),
        border = BorderStroke(1.dp, c.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
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
                    Text("+", style = MaterialTheme.typography.headlineMedium, color = c.onViolet)
                }
            }
            Column(Modifier.padding(16.dp)) {
                Text("Bring your own", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                HyleField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = label,
                    placeholder = placeholder,
                    modifier = Modifier.fillMaxWidth(),
                )
                HyleButton(
                    "Download from URL",
                    onClick = onDownload,
                    enabled = enabled,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
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

/** [Coverflow]'s pager height: bounded (not `fillMaxHeight`/`wrapContentHeight`, which would
 *  just forward whatever the parent proposes), sized to comfortably clear [CoverCard]'s own
 *  `heightIn(min = 380.dp)` with margin for a two-line name or a running/paused download row. */
private val CoverflowHeight = 430.dp

/** How much of a neighbour card peeks past the current page's edge (owner ask #2: the old
 *  36.dp peek "reads as a single static card with no visible hint of more content"). */
private val CoverflowPeek = 52.dp

/**
 * Horizontal coverflow: the focused card is full size; neighbours shrink and fade, and a
 * dots-plus-count readout underneath makes the "there's more here, swipe" affordance explicit
 * (owner ask #2) rather than relying solely on the peek to register.
 *
 * The `HorizontalPager` gets an explicit, fixed [CoverflowHeight] rather than
 * `fillMaxHeight()`/`wrapContentHeight()` — both of those just relay whatever height the
 * *parent* proposes, and here the parent is [SettingsRoom]'s own `verticalScroll` content
 * column, which (being scrollable) proposes an *infinite* max height so it can measure its full
 * intrinsic size. A lazy layout like `HorizontalPager` needs a bounded cross-axis constraint to
 * lay out its pages and crashes on `Constraints.Infinity` (the PR #39 crash class this file used
 * to dodge by routing to a full-screen overlay instead). `Modifier.height(fixedDp)` pins an
 * exact, finite value regardless of what the parent offers, so the pager always measures with a
 * real number — that's what makes it safe to render straight inside [LocalModels]' existing
 * `verticalScroll` now, no separate overlay host required.
 */
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
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = state,
            contentPadding = PaddingValues(horizontal = CoverflowPeek),
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxWidth().height(CoverflowHeight),
        ) { page ->
            val offset = ((state.currentPage - page) + state.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
            Box(
                Modifier.graphicsLayer {
                    // A much shallower fade floor than before (0.45f -> 0.68f) plus a
                    // stronger, opaque border/shadow on the card itself (see CoverCard) — the
                    // old combination of a near-background container colour and a deep alpha
                    // fade was why neighbours were reported as invisible.
                    val s = lerp(0.87f, 1f, 1f - offset)
                    scaleX = s
                    scaleY = s
                    alpha = lerp(0.68f, 1f, 1f - offset)
                },
            ) {
                card(page)
            }
        }
        if (count > 1) {
            Spacer(Modifier.height(10.dp))
            PagerPositionIndicator(state, count, Modifier.padding(horizontal = 20.dp))
        }
    }
}

/** Explicit page-position readout below the pager (owner ask #2): dots for an at-a-glance
 *  shape, plus a "N of M" count that stays legible and unambiguous no matter how large the
 *  catalog grows (dots alone stop scaling past a handful of items, so past a dozen this drops
 *  them and keeps just the text). */
@Composable
private fun PagerPositionIndicator(state: PagerState, count: Int, modifier: Modifier = Modifier) {
    val c = LocalHyleColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count <= 12) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(count) { i ->
                    val active = i == state.currentPage
                    Box(
                        Modifier
                            .size(if (active) 8.dp else 6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (active) c.violet else c.outline),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(
            "${state.currentPage + 1} of $count",
            style = MaterialTheme.typography.labelSmall,
            color = c.textMid,
        )
    }
}

/**
 * A large, visually rich model card: gradient header + big monogram, then details with real
 * typographic hierarchy (name → spec → a coloured fit-status badge, not cramped default `Text`
 * stacking) and the inline download lifecycle.
 *
 * Container colour is [dev.aarso.hyle.theme.HyleColors.inset] rather than the M3
 * `colorScheme.surfaceVariant` this used to read (owner ask #2's root-cause diagnosis:
 * [dev.fonebrew.ui.theme.FonebrewTheme] maps `surfaceVariant` to the SAME `c.raised` token the
 * surrounding page background derives from — `ink`/`raised` sit one hair-step apart, e.g.
 * `0xFFF9F9F9`/`0xFFFFFFFF` in light mode — so a faded neighbour card's fill nearly vanished
 * into the page behind it). `c.inset` ("inputs, scroll track") is a genuinely further step,
 * `0xFFEEF0F4` in light mode, and is already used elsewhere for recessed surfaces, so this is
 * an existing Hyle token, not an invented colour. The border moves from the low-alpha
 * `c.hairline` (a ~12-14%-opacity "crisp OLED edge" tuned for full-opacity content) to the
 * opaque `c.outline`, and the card now carries real elevation — both stay legible even at the
 * pager's faded neighbour alpha, instead of fading toward invisible alongside the fill.
 */
@Composable
private fun CoverCard(
    name: String,
    spec: String,
    fitVerdict: FitVerdict,
    fitReason: String,
    state: DownloadCenter.State?,
    downloaded: Boolean,
    // Never coerce a null downloadUrl into "available" — the catalog's own honesty rule
    // (CatalogModel/SdCatalogModel's KDoc): a null mirror shows "not available", never a
    // Download button that silently no-ops.
    available: Boolean,
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 380.dp),
        colors = CardDefaults.cardColors(containerColor = c.inset),
        border = BorderStroke(1.dp, c.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
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
                Spacer(Modifier.height(2.dp))
                Text(spec, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                FitBadge(fitReason, fitColor)
                Spacer(Modifier.height(16.dp))
                DownloadAction(state, downloaded, available, fitVerdict, onDownload, onPause, onResume, onCancel)
            }
        }
    }
}

/** The fit-verdict line as a coloured status pill rather than plain coloured text — a small
 *  touch that reads as a deliberate status indicator instead of an afterthought caption. Uses
 *  the same semantic colour [CoverCard] already computes; no new colours invented. */
@Composable
private fun FitBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** The shared download lifecycle: on-device / not available / paused / failed / running / download. */
@Composable
private fun DownloadAction(
    state: DownloadCenter.State?,
    downloaded: Boolean,
    available: Boolean,
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
        // Never coerce a null downloadUrl into "available" — the catalog's own honesty rule.
        !available -> Text(
            "not available in this build — no verified mirror yet",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state?.paused == true -> Column {
            LinearProgressIndicator(progress = { progress?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("paused · ${((progress?.fraction ?: 0f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onResume) { Text("Resume") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        state != null && state.failed -> Column {
            // Audit finding: the failed-download error is an arbitrary exception/HTTP message
            // (unbounded length) rendered inside CoverCard's now-fixed-height pager page (see
            // Coverflow's CoverflowHeight) — capped here so a long/wrapping message can't push
            // the Retry/Dismiss row past the card's visible bounds.
            Text(
                "failed: ${progress?.error}",
                style = MaterialTheme.typography.labelSmall,
                color = c.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
