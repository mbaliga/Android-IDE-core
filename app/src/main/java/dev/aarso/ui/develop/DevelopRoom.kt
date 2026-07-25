package dev.aarso.ui.develop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aarso.FonebrewApp
import kotlinx.coroutines.launch

/**
 * The "develop on your repo" room — reskinned to Hyle. The header/tabs call
 * [dev.aarso.hyle.cells.HyleButton]/[dev.aarso.hyle.cells.HyleChip] directly; every facet below still
 * calls the same-named [WireBox]/[WireButton]/[Hint] atoms it always has, but those now delegate
 * to [dev.aarso.hyle.cells.HyleCard]/[dev.aarso.hyle.cells.HyleChip] so the whole room picks up the
 * design system without a per-call-site rewrite. Per brief §7 the free-core facets are exactly:
 *
 *  - **Hardware** → supported boards + detect/troubleshoot + the four control paths
 *                   (Pi/Arduino/ESP/on-phone USB flash)
 *  - **Files**    → review changes to your repo per-hunk and commit (the agentic-coding
 *                   review path; there is no separate "Agent" mode)
 *  - **Terminal** → run a shell command over SSH on your homelab runner
 *  - **Audit**    → a to-do list of checks; "Run" fires a prompt that runs the test
 *
 * Builds moved to the Tree (§6.1) and Cost to Loops (§6.2), so neither is a tab here.
 *
 * The paid Studio layer contributes **Launch** (store-publish) through the [DevelopTabs]
 * seam (S2; see StudioDevelopFacets), so core never references that code directly.
 * Presented full-screen from Settings (like the Loop room). [onClose] returns.
 */
@Composable
fun DevelopRoom(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    // Brief §7: Develop's tabs are exactly Hardware / Files / Terminal / Audit.
    //  - Cost moved to Loops (a per-loop budget boundary, §6.2) — no Cost tab here.
    //  - Builds moved to the Tree (§6.1) — the Tree's Builds tab owns build history.
    //  - "Agent" is gone: agentic changes are reviewed in the Files explorer (§7.2).
    var tab by remember { mutableStateOf(0) }
    // The paid Studio tab (Launch / store-publish) is contributed via DevelopTabs (S2 seam),
    // so core never references it directly.
    val studioTabs = remember { DevelopTabs.provider() }
    // Core four tabs get purpose-drawn glyphs (chip/board, folder-tab, prompt mark, check-in-circle);
    // any Studio-contributed tab past those four gets a neutral diamond so this room never needs to
    // know Studio's icon set.
    val tabSpecs = listOf(
        dev.aarso.hyle.cells.HyleTabSpec("Hardware") { tint -> hardwareTabGlyph(tint) },
        dev.aarso.hyle.cells.HyleTabSpec("Files") { tint -> filesTabGlyph(tint) },
        dev.aarso.hyle.cells.HyleTabSpec("Terminal") { tint -> terminalTabGlyph(tint) },
        dev.aarso.hyle.cells.HyleTabSpec("Audit") { tint -> auditTabGlyph(tint) },
    ) + studioTabs.map { st -> dev.aarso.hyle.cells.HyleTabSpec(st.label) { tint -> genericTabGlyph(tint) } }

    val universalTabBarPosition by container.sessionStore.tabBarPosition.collectAsState()
    val roomTabBarOverrides by container.sessionStore.roomTabBarPosition.collectAsState()
    val tabBarPosition = roomTabBarOverrides["develop"] ?: universalTabBarPosition
    val tabBarBlock: @Composable () -> Unit = {
        dev.aarso.hyle.cells.HyleTabBar(tabs = tabSpecs, selected = tab, onSelect = { tab = it }, position = tabBarPosition)
    }
    val contentBlock: @Composable () -> Unit = {
        when {
            tab == 0 -> HardwareFacet(onOpenTerminal = { tab = 2 })
            tab == 1 -> FilesFacet()
            tab == 2 -> TerminalFacet()
            tab == 3 -> AuditFacet(onRunPrompt = { prompt ->
                // Audit is a to-do list: "Run" fires the check as a prompt in Chat (§7.4).
                container.sharedIntake.offer(dev.aarso.data.Intake(text = prompt, source = "audit"))
                onClose()
            })
            else -> studioTabs[tab - 4].content()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            dev.aarso.hyle.cells.HyleButton("‹ Close", onClick = onClose, secondary = true)
            Spacer(Modifier.width(12.dp))
            Text("Develop", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(12.dp))

        if (tabBarPosition == "BOTTOM") {
            contentBlock()
            Spacer(Modifier.height(16.dp))
            tabBarBlock()
        } else {
            tabBarBlock()
            Spacer(Modifier.height(16.dp))
            contentBlock()
        }
    }
}

/* ------------------------------------------------------------- Tab glyphs */

/** Hardware: a chip/board outline with pins — simple line-drawn shapes, [SettingsRoom]'s TabGlyph style. */
private fun DrawScope.hardwareTabGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    drawRoundRect(
        tint,
        topLeft = Offset(w * 0.24f, h * 0.24f),
        size = Size(w * 0.52f, h * 0.52f),
        cornerRadius = CornerRadius(w * 0.06f),
        style = Stroke(width = sw),
    )
    listOf(h * 0.38f, h * 0.62f).forEach { y ->
        drawLine(tint, Offset(w * 0.06f, y), Offset(w * 0.24f, y), strokeWidth = sw)
        drawLine(tint, Offset(w * 0.76f, y), Offset(w * 0.94f, y), strokeWidth = sw)
    }
    listOf(w * 0.38f, w * 0.62f).forEach { x ->
        drawLine(tint, Offset(x, h * 0.06f), Offset(x, h * 0.24f), strokeWidth = sw)
        drawLine(tint, Offset(x, h * 0.76f), Offset(x, h * 0.94f), strokeWidth = sw)
    }
}

/** Files: a folder-tab shape. */
private fun DrawScope.filesTabGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    val path = Path().apply {
        moveTo(w * 0.10f, h * 0.30f)
        lineTo(w * 0.10f, h * 0.80f)
        lineTo(w * 0.90f, h * 0.80f)
        lineTo(w * 0.90f, h * 0.38f)
        lineTo(w * 0.46f, h * 0.38f)
        lineTo(w * 0.36f, h * 0.22f)
        lineTo(w * 0.10f, h * 0.22f)
        close()
    }
    drawPath(path, tint, style = Stroke(width = sw))
}

/** Terminal: a `>` prompt mark plus a line, like a shell cursor. */
private fun DrawScope.terminalTabGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    val chevron = Path().apply {
        moveTo(w * 0.16f, h * 0.26f)
        lineTo(w * 0.42f, h * 0.50f)
        lineTo(w * 0.16f, h * 0.74f)
    }
    drawPath(chevron, tint, style = Stroke(width = sw))
    drawLine(tint, Offset(w * 0.50f, h * 0.74f), Offset(w * 0.86f, h * 0.74f), strokeWidth = sw)
}

/** Audit: a checkmark inside a circle. */
private fun DrawScope.auditTabGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    drawCircle(tint, radius = w * 0.42f, style = Stroke(width = sw))
    val check = Path().apply {
        moveTo(w * 0.30f, h * 0.52f)
        lineTo(w * 0.44f, h * 0.66f)
        lineTo(w * 0.72f, h * 0.34f)
    }
    drawPath(check, tint, style = Stroke(width = sw))
}

/** Neutral placeholder glyph for any Studio-contributed tab (this room doesn't know its icon). */
private fun DrawScope.genericTabGlyph(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.09f
    val diamond = Path().apply {
        moveTo(w * 0.5f, h * 0.10f)
        lineTo(w * 0.90f, h * 0.5f)
        lineTo(w * 0.5f, h * 0.90f)
        lineTo(w * 0.10f, h * 0.5f)
        close()
    }
    drawPath(diamond, tint, style = Stroke(width = sw))
}

/* ----------------------------------------------------------------- Builds */

/**
 * Builds (free/core): the APK artifacts your CI produced, with a sideload install. Viewing
 * builds + installing is part of the free dev loop — only the store-publish Launch tab is
 * the paid Studio layer. Reads through [dev.aarso.data.BuildsRepo]; install is sideload-only.
 */
@Composable
internal fun BuildsFacet() {
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    val hosts by container.gitHostStore.hosts.collectAsState()
    val host = hosts.firstOrNull()
    if (host == null) {
        Hint("No Git host connected. Add one in Settings → Global → Git & coding to see your builds.")
        return
    }
    val scope = rememberCoroutineScope()
    var builds by remember(host.id) { mutableStateOf<List<dev.aarso.domain.builds.Build>?>(null) }
    LaunchedEffect(host.id) { builds = runCatching { container.buildsRepo.listBuilds(host) }.getOrNull() }

    Text("${host.owner}/${host.repo}", style = MaterialTheme.typography.titleSmall)
    Text(
        "APKs your CI produced. Tests show as a badge. Install is sideload-only (Play forbids in-app installs).",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    when (val list = builds) {
        null -> Hint("Loading…")
        else -> if (list.isEmpty()) {
            Hint("No builds found — push a release or set up the apk-dist branch on your repo.")
        } else {
            for (build in list.take(8)) {
                var checks by remember(build.id) { mutableStateOf<dev.aarso.domain.builds.ChecksSummary?>(null) }
                LaunchedEffect(build.id) { checks = runCatching { container.buildsRepo.checks(host, build) }.getOrNull() }
                var prog by remember(build.id) { mutableStateOf<Float?>(null) }
                WireBox {
                    Text(build.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${build.version} · ${build.source.name.lowercase().replace('_', ' ')}" +
                            (if (build.sizeBytes > 0) " · ${build.sizeBytes / (1024 * 1024)} MB" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    checks?.let { c ->
                        Text(
                            "checks: ${c.conclusion.name.lowercase()}  (✓${c.passed} ✗${c.failed})",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    val url = container.buildsRepo.findApkUrl(build)
                    val p = prog
                    when {
                        url == null -> Hint("no APK url")
                        p == null -> WireButton("Install") {
                            prog = 0f
                            scope.launch {
                                container.apkInstaller.downloadAndInstall(url, build.name) { pr ->
                                    prog = if (pr.error != null) -1f else if (pr.done) null else pr.fraction
                                }
                            }
                        }
                        p < 0f -> Text("✗ install failed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        else -> Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** A grouped-content box — delegates to [dev.aarso.hyle.cells.HyleCard]. */
@Composable
internal fun WireBox(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    dev.aarso.hyle.cells.HyleCard(modifier.padding(vertical = 4.dp), content = content)
}

/** A tappable label; [selected] marks tab/segment/toggle state — delegates to [dev.aarso.hyle.cells.HyleChip]. */
@Composable
internal fun WireButton(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    dev.aarso.hyle.cells.HyleChip(selected = selected, onClick = onClick, label = label, enabled = enabled)
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = dev.aarso.hyle.theme.LocalHyleColors.current.textMid)
}

/* ---------------------------------------------------------------- Devices */

/**
 * Devices (agentic-ide #3): run on a Pi/Dell over SSH, or flash an Arduino plugged into that
 * host via `arduino-cli` (the v1 delegate-to-Pi path — the phone never touches the board), or
 * push firmware to an ESP over the network. Recipes run through [dev.aarso.data.DeviceRepo];
 * the host's raw output is shown verbatim (watched object). All owner-verified — no host/board
 * in CI. (Direct phone↔board over USB is the device-gated #4, not here.)
 */
@Composable
private fun HardwareFacet(onOpenTerminal: () -> Unit) {
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    val repo = container.deviceRepo
    val store = container.remoteHostStore
    val hosts by store.hosts.collectAsState()
    val scope = rememberCoroutineScope()

    // Supported boards + quick troubleshooting — the Arduino-IDE-grade framing (§7.1). All local
    // and sovereign; the four control paths below are Pi-over-SSH, Arduino-via-Pi, ESP-OTA, and
    // on-phone USB flash (CDC/Stk500/IntelHex). Device interaction is owner-verified on hardware.
    Text("Hardware", style = MaterialTheme.typography.titleSmall)
    Hint(
        "Supported: Raspberry Pi (SSH) · Arduino AVR (via Pi or on-phone USB) · ESP32/8266 (OTA) · " +
            "USB-flashable MCUs. Troubleshooting: no port → check the cable/OTG + driver (CH340/CP210x " +
            "clones need a vendor driver); permission denied → re-trust the Pi in Settings; flash fails " +
            "mid-way → it's flagged, re-run before power-cycling.",
    )
    Spacer(Modifier.height(8.dp))

    if (hosts.isEmpty()) {
        Hint(
            "No SSH host yet. Add one in Settings → Global → your machines and connect once to " +
                "trust it — then a Pi (or an Arduino plugged into it) shows up here. On-phone USB " +
                "flash works without a host.",
        )
        return
    }
    var selected by remember { mutableStateOf(hosts.first().alias) }
    var mode by remember { mutableStateOf(0) }
    var output by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var fqbn by remember { mutableStateOf("arduino:avr:uno") }
    var port by remember { mutableStateOf("/dev/ttyACM0") }
    var sketch by remember { mutableStateOf("~/sketches/blink") }
    var espIp by remember { mutableStateOf("") }
    var espBin by remember { mutableStateOf("") }

    fun identityFor(host: dev.aarso.domain.remote.RemoteHost): dev.aarso.domain.remote.Identity {
        val ref = store.hostSecret(host.alias)
        return when {
            ref == null -> dev.aarso.domain.remote.Identity.Agent
            ref.isKey -> dev.aarso.domain.remote.Identity.PublicKey(ref.id)
            else -> dev.aarso.domain.remote.Identity.Password(ref.id)
        }
    }
    fun runRecipe(recipe: dev.aarso.domain.remote.ExecRequest, parse: ((String, Int) -> String)?) {
        val host = hosts.firstOrNull { it.alias == selected } ?: return
        running = true; output = ""; summary = null
        scope.launch {
            repo.exec(host, identityFor(host), store.knownHosts.value, recipe, { output += it }).fold(
                { code -> summary = parse?.invoke(output, code) ?: "exit $code" },
                { summary = "failed: ${it.message} — is the host trusted? connect once in Settings → Remote." },
            )
            running = false
        }
    }
    val host = hosts.first { it.alias == selected }
    fun arduinoTarget(): dev.aarso.domain.device.DeployTarget.Arduino? = runCatching {
        dev.aarso.domain.device.DeployTarget.Arduino(
            via = host,
            fqbn = dev.aarso.domain.device.Fqbn(fqbn.trim()),
            port = dev.aarso.domain.device.SerialPort(port.trim()),
        )
    }.getOrElse { summary = "invalid FQBN/port: ${it.message}"; null }

    Text("Devices", style = MaterialTheme.typography.titleSmall)
    Hint("Run on a Pi/Dell over SSH, or flash an Arduino plugged into that host. Output is shown verbatim.")
    Spacer(Modifier.height(8.dp))
    Text("Host", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        hosts.forEach { h -> WireButton(h.alias, selected = h.alias == selected, onClick = { selected = h.alias }) }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WireButton("Raspberry Pi", selected = mode == 0, onClick = { mode = 0 })
        WireButton("Arduino (via Pi)", selected = mode == 1, onClick = { mode = 1 })
        WireButton("ESP-OTA", selected = mode == 2, onClick = { mode = 2 })
        WireButton("This phone (USB)", selected = mode == 3, onClick = { mode = 3 })
    }
    Spacer(Modifier.height(6.dp))
    Hint(
        if (mode == 3) {
            "⌂ on-device — flashes a board plugged into this phone. No network, no pairing."
        } else {
            "☁ watched — controls a paired host over SSH; credentials stay in the Keystore."
        },
    )
    Spacer(Modifier.height(8.dp))
    when (mode) {
        0 -> {
            Hint(
                "Running one-off shell commands on a paired host now lives in the Terminal tab, so " +
                    "every command shares one running transcript instead of clearing per run.",
            )
            Spacer(Modifier.height(6.dp))
            WireButton("Open Terminal ▸", onClick = onOpenTerminal)
        }
        1 -> {
            dev.aarso.hyle.cells.HyleField(fqbn, { fqbn = it }, label = "FQBN (packager:arch:board)", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            dev.aarso.hyle.cells.HyleField(port, { port = it }, label = "Serial port on the host", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            dev.aarso.hyle.cells.HyleField(sketch, { sketch = it }, label = "Sketch path on the host", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WireButton("List boards", enabled = !running, onClick = {
                    runRecipe(dev.aarso.domain.device.recipe.DeviceRecipes.listBoards(), null)
                })
                WireButton("Compile", enabled = !running, onClick = {
                    arduinoTarget()?.let { t ->
                        runRecipe(dev.aarso.domain.device.recipe.DeviceRecipes.compile(t, sketch.trim())) { o, _ -> compileSummary(o) }
                    }
                })
                WireButton("Upload", enabled = !running, onClick = {
                    arduinoTarget()?.let { t ->
                        runRecipe(dev.aarso.domain.device.recipe.DeviceRecipes.upload(t, sketch.trim())) { o, _ -> uploadSummary(o) }
                    }
                })
                WireButton("Compile & upload", enabled = !running, onClick = {
                    arduinoTarget()?.let { t ->
                        runRecipe(dev.aarso.domain.device.recipe.DeviceRecipes.compileAndUpload(t, sketch.trim())) { o, _ -> uploadSummary(o) }
                    }
                })
            }
        }
        2 -> {
            dev.aarso.hyle.cells.HyleField(espIp, { espIp = it }, label = "ESP device IP", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            dev.aarso.hyle.cells.HyleField(espBin, { espBin = it }, label = "Firmware .bin path on the host", modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            WireButton(if (running) "Flashing…" else "Flash over network", enabled = !running && espIp.isNotBlank() && espBin.isNotBlank(), onClick = {
                runRecipe(dev.aarso.domain.device.recipe.DeviceRecipes.espOta(espIp.trim(), espBin.trim()), null)
            })
        }
        else -> UsbFlashPanel()
    }
    summary?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
    if (output.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        WireBox {
            Text(output, style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

/**
 * Direct-USB flashing (agentic-ide #4) — the device-gated panel. Lists attached USB boards and
 * flashes a host-built .hex via the tested STK500 over [dev.aarso.data.device.CdcUsbSerialLink].
 * Experimental + owner-verified: CDC boards only (Uno R3/Leonardo/Micro, ESP-CDC); CH340/CP210x
 * clones need a vendor driver (follow-up). Compilation is never on the phone — get the .hex from
 * the Arduino-via-Pi flow or a CI build.
 */
@Composable
private fun UsbFlashPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val flasher = remember { dev.aarso.data.device.UsbFlasher(ctx) }
    var boards by remember { mutableStateOf(flasher.attached()) }
    var selected by remember { mutableStateOf(boards.firstOrNull { it.cdc }?.device?.deviceName ?: boards.firstOrNull()?.device?.deviceName) }
    var hexPath by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Text(
        "Experimental · device-gated. Flashes a board plugged into the phone over USB-OTG. CDC " +
            "boards only (Uno R3/Leonardo/Micro, ESP-CDC) — clone chips (CH340/CP210x) need a " +
            "vendor driver (follow-up). The .hex comes from a host/CI build, not the phone.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    WireButton("Rescan USB", onClick = { boards = flasher.attached() })
    Spacer(Modifier.height(6.dp))
    if (boards.isEmpty()) {
        Hint("No USB device detected. Connect the board via an OTG cable, then Rescan.")
    } else {
        boards.forEach { b ->
            WireButton(
                (if (b.cdc) "✓ " else "✗ ") + b.label,
                selected = b.device.deviceName == selected,
                onClick = { selected = b.device.deviceName },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    dev.aarso.hyle.cells.HyleField(hexPath, { hexPath = it }, label = ".hex file path on this phone", modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    WireButton(if (busy) "Flashing…" else "Flash over USB", enabled = !busy && selected != null && hexPath.isNotBlank(), onClick = {
        val board = boards.firstOrNull { it.device.deviceName == selected }?.device ?: return@WireButton
        busy = true; progress = "requesting USB permission…"
        scope.launch {
            try {
                if (!flasher.requestPermission(board)) { progress = "USB permission denied"; return@launch }
                val hex = runCatching { java.io.File(hexPath.trim()).readText() }.getOrElse { progress = "can't read .hex: ${it.message}"; return@launch }
                flasher.flash(board, hex) { progress = it }.onFailure { progress = "failed: ${it.message}" }
            } finally {
                busy = false
            }
        }
    })
    progress?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("done")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
}

private fun compileSummary(out: String): String {
    val r = dev.aarso.domain.device.ArduinoCli.parseCompile(out)
    return if (r.ok) {
        "✓ compiled" + (r.size?.programBytes?.let { " · $it bytes" } ?: "")
    } else {
        val first = r.errors.firstOrNull()
        "✗ ${r.errors.size} error(s)" + (first?.let { " — ${it.file}:${it.line} ${it.message}" } ?: "")
    }
}

private fun uploadSummary(out: String): String {
    val r = dev.aarso.domain.device.ArduinoCli.parseUpload(out)
    return if (r.ok) "✓ uploaded" else "✗ upload failed"
}

/* ------------------------------------------------------------------ Agent */

/**
 * The agentic repo loop (agentic-ide #1): give an objective + the files to read as context, the
 * model proposes a ChangeSet, you **review** it (shared ReviewSheet), and the approved subset is
 * committed to your connected repo. Review-first — nothing commits unseen. Network + generation
 * are owner-verified.
 */
@Composable
private fun FilesFacet() {
    val container = (LocalContext.current.applicationContext as FonebrewApp).container
    val runner = container.agentRepoRunner
    val scope = rememberCoroutineScope()
    val runnable = remember { container.modelRegistry.allSpecs().filter { container.engineProvider.isRunnable(it) } }

    var objective by remember { mutableStateOf("") }
    var pathsText by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf(runnable.firstOrNull()?.id) }
    var status by remember { mutableStateOf<String?>(null) }
    var proposal by remember { mutableStateOf<dev.aarso.data.AgentRepoRunner.Proposal?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    if (!runner.hasHost) {
        Hint("Connect a Git host (Settings → Global → Git & coding) — the agent reads and commits to your repo.")
        return
    }
    Text("Files & changes", style = MaterialTheme.typography.titleSmall)
    Hint(
        "Review changes to your repo and commit them. Ask for a change here or in Chat → the model " +
            "proposes edits → you review per-hunk → commit. Nothing commits without review; a " +
            "cloud-proposed change is a watched object. (Accepted changes land as commits in the Tree.)",
    )
    if (runnable.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        Hint("No runnable model. Download one (Models) or add a cloud provider (Settings → Text).")
        return
    }
    Spacer(Modifier.height(8.dp))
    dev.aarso.hyle.cells.HyleField(objective, { objective = it }, label = "Objective", singleLine = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    dev.aarso.hyle.cells.HyleField(
        pathsText, { pathsText = it },
        label = "Context files — one path per line",
        singleLine = false,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Text("Model", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        runnable.forEach { s ->
            WireButton((if (s.isOnDevice) "⌂ " else "☁ ") + s.displayName, selected = s.id == modelId, onClick = { modelId = s.id })
        }
    }
    Spacer(Modifier.height(8.dp))
    WireButton(
        if (busy) "Working…" else "Run",
        enabled = !busy && objective.isNotBlank() && modelId != null,
        onClick = {
            val mid = modelId ?: return@WireButton
            val paths = pathsText.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }
            busy = true; status = "reading ${paths.size} file(s)…"; proposal = null
            scope.launch {
                val ctx = runner.read(paths)
                status = "proposing… (read ${ctx.size}/${paths.size})"
                runner.propose(objective, ctx, mid).fold(
                    { p ->
                        proposal = p
                        status = "proposed ${p.changeSet.effective.size} change(s) over files: ${p.filesRead.joinToString(", ").ifEmpty { "(none)" }}"
                    },
                    { status = "failed: ${it.message}" },
                )
                busy = false
            }
        },
    )
    status?.let { Spacer(Modifier.height(8.dp)); Hint(it) }
    proposal?.let { p ->
        Spacer(Modifier.height(6.dp))
        WireButton("Review ${p.changeSet.effective.size} change(s) ▸", enabled = !p.changeSet.isEmpty, onClick = { reviewing = true })
    }

    if (reviewing) {
        val p = proposal
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { reviewing = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                dev.aarso.ui.ide.ReviewSheet(
                    changeSet = p?.changeSet ?: dev.aarso.domain.diff.ChangeSet(emptyList()),
                    title = "Review — agent changes",
                    commitLabel = "Commit",
                    busy = busy,
                    onCancel = { reviewing = false },
                    onCommit = { approved ->
                        busy = true
                        scope.launch {
                            runner.commit(approved, "Agent: ${objective.take(60)}").fold(
                                { ids -> status = "committed ${ids.size} file(s)"; proposal = null; reviewing = false },
                                { status = "commit failed: ${it.message}" },
                            )
                            busy = false
                        }
                    },
                )
            }
        }
    }
}
