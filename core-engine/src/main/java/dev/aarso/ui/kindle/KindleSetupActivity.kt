package dev.aarso.ui.kindle

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.aarso.AarsoApp
import dev.aarso.data.kindle.ApprovedPackageDownloader
import dev.aarso.data.kindle.FylzOperationClient
import dev.aarso.data.kindle.KindleDeviceRepository
import dev.aarso.data.kindle.KindleProvisioningCoordinator
import dev.aarso.data.kindle.KindleProvisioningStore
import dev.aarso.data.kindle.KindleSshOrchestrator
import dev.aarso.data.remote.SshjTransport
import dev.aarso.data.workdeck.WorkdeckPairingStore
import dev.aarso.domain.kindle.KindleDeviceProfile
import dev.aarso.domain.kindle.KindleDeviceState
import dev.aarso.domain.kindle.KindleFeatureState
import dev.aarso.domain.kindle.KindleFirmwareVersion
import dev.aarso.domain.kindle.KindleNetworkState
import dev.aarso.domain.kindle.KindleProfileMatch
import dev.aarso.domain.kindle.KindleProvisioningManifestCodec
import dev.aarso.domain.kindle.KindleProvisioningPolicy
import dev.aarso.domain.kindle.KindleProvisioningRecipe
import dev.aarso.domain.kindle.KindleProvisioningRun
import dev.aarso.domain.kindle.KindleUsbState
import dev.aarso.domain.kindle.ProvisioningSelection
import dev.aarso.domain.kindle.ProvisioningStage
import dev.aarso.domain.kindle.WorkdeckClientManifest
import dev.aarso.domain.kindle.WorkdeckClientManifestCodec
import dev.aarso.domain.remote.HostKey
import dev.aarso.domain.remote.Identity
import dev.aarso.domain.remote.RemoteHost
import dev.aarso.service.WorkdeckService
import dev.aarso.ui.theme.AarsoTheme
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class KindleSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setContent { AarsoTheme { KindleSetupSurface(onClose = ::finish) } }
    }
}

@Composable
private fun KindleSetupSurface(onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AarsoApp
    val deviceRepo = remember { KindleDeviceRepository(context) }
    val provisioningStore = remember { KindleProvisioningStore(context) }
    val coordinator = remember {
        KindleProvisioningCoordinator(
            FylzOperationClient(context), ApprovedPackageDownloader(context), provisioningStore,
        )
    }
    val manifest = remember {
        KindleProvisioningManifestCodec.decode(
            context.assets.open("kindle/provisioning_manifest.v1.json").bufferedReader().use { it.readText() },
        )
    }
    val resumedRun = remember { coordinator.resume() }
    val resumedProfile = remember(resumedRun?.profileId) {
        resumedRun?.let { saved -> deviceRepo.profiles.all().singleOrNull { it.id == saved.profileId } }
    }
    val resumedRecipe = remember(resumedRun?.recipeId, resumedRun?.recipeVersion) {
        resumedRun?.let { saved ->
            manifest.recipes.singleOrNull { it.id == saved.recipeId && it.version == saved.recipeVersion }
        }
    }
    var candidates by remember { mutableStateOf(deviceRepo.discoverUsb()) }
    var serial by remember { mutableStateOf(candidates.firstOrNull()?.serial ?: resumedProfile?.serialPrefixes?.first().orEmpty()) }
    var firmwareText by remember { mutableStateOf(resumedRun?.firmware?.toString() ?: "5.17.1.0.3") }
    var profile by remember { mutableStateOf(resumedProfile) }
    var recipe by remember { mutableStateOf(resumedRecipe) }
    var run by remember { mutableStateOf(resumedRun) }
    val restoredStorage = remember(run?.id) { run?.let { provisioningStore.storageHandles(it.id) } }
    var kindleTree by remember { mutableStateOf(restoredStorage?.first) }
    var backupTree by remember { mutableStateOf(restoredStorage?.second) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deviceState by remember {
        mutableStateOf(
            KindleDeviceState(resumedProfile?.id, resumedProfile?.model, resumedProfile?.serialPrefixes?.firstOrNull(),
                resumedRun?.firmware, KindleUsbState.DISCONNECTED, KindleNetworkState.OFFLINE,
                KindleFeatureState.UNKNOWN, KindleFeatureState.UNKNOWN, KindleFeatureState.UNKNOWN,
                KindleFeatureState.UNKNOWN, KindleFeatureState.UNKNOWN),
        )
    }
    val scope = rememberCoroutineScope()

    fun persistHandle(uri: Uri, write: Boolean) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0),
            )
        }
    }
    val kindleTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { persistHandle(it, write = true); kindleTree = it }
    }
    val backupTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { persistHandle(it, write = true); backupTree = it }
    }

    var sshHost by remember { mutableStateOf("192.168.43.2") }
    var sshUser by remember { mutableStateOf("root") }
    var sshPassword by remember { mutableStateOf("") }
    var presentedKey by remember { mutableStateOf<HostKey?>(null) }
    var homebrewObserved by remember { mutableStateOf(false) }
    var clientManifest by remember { mutableStateOf<WorkdeckClientManifest?>(null) }
    var clientBinary by remember { mutableStateOf<Uri?>(null) }
    var phoneAddress by remember { mutableStateOf("192.168.43.1") }
    val remoteStore = app.container.remoteHostStore
    val ssh = remember { KindleSshOrchestrator { SshjTransport(remoteStore::secret) } }
    val pairing = remember { WorkdeckPairingStore(context) }
    val clientManifestPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                persistHandle(it, write = false)
                WorkdeckClientManifestCodec.decode(context.contentResolver.readBoundedText(it, 64 * 1024))
                    .also { parsed ->
                        val currentProfile = requireNotNull(profile) { "Identify the Kindle first." }
                        val currentFirmware = requireNotNull(run?.firmware) { "Identify firmware first." }
                        parsed.requireCompatible(currentProfile, currentFirmware)
                    }
            }.onSuccess { clientManifest = it; message = "Workdeck client manifest accepted" }
                .onFailure { message = it.message }
        }
    }
    val clientBinaryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistHandle(it, write = false); clientBinary = it; message = "Opaque Workdeck client handle selected" }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose) { Text("Close") }
            Text("Add Kindle", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Phone-only setup · Fylz owns every storage operation · no Amazon credentials are requested")

        DeviceCard(deviceState)

        SetupStep(1, "Connect Kindle by USB") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { candidates = deviceRepo.discoverUsb(); message = "USB rescanned" }) { Text("Rescan") }
                candidates.firstOrNull { !it.permissionGranted }?.let { candidate ->
                    OutlinedButton(onClick = {
                        val manager = context.getSystemService(UsbManager::class.java)
                        val target = deviceRepo.usbDevice(candidate.deviceId)
                        if (target != null) {
                            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                            manager.requestPermission(target, PendingIntent.getBroadcast(
                                context, target.deviceId,
                                Intent("dev.aarso.kindle.USB_PERMISSION").setPackage(context.packageName), flags,
                            ))
                            message = "Android USB permission requested; tap Rescan after responding."
                        }
                    }) { Text("Grant USB access") }
                }
            }
            Text(if (candidates.isEmpty()) "No likely Kindle USB device detected" else "${candidates.size} candidate(s) detected")
        }

        SetupStep(2, "Identify model and firmware") {
            OutlinedTextField(serial, { serial = it }, label = { Text("Serial / prefix") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(firmwareText, { firmwareText = it }, label = { Text("Firmware from Kindle Device Info") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy, onClick = {
                runCatching {
                    val firmware = KindleFirmwareVersion.parse(firmwareText)
                    val match = deviceRepo.profiles.match(dev.aarso.domain.kindle.KindleIdentity(serial = serial, firmware = firmware))
                    val matched = (match as? KindleProfileMatch.Matched)?.profile
                        ?: error("Serial/model did not match one unambiguous device profile.")
                    val selected = KindleProvisioningPolicy.select(manifest, matched, firmware)
                    val approved = (selected as? ProvisioningSelection.Approved)?.recipe
                        ?: error((selected as ProvisioningSelection.Blocked).reason)
                    profile = matched; recipe = approved
                    run = coordinator.start(matched, firmware, approved)
                    deviceState = deviceState.copy(
                        profileId = matched.id, model = matched.model,
                        serialPrefix = matched.serialPrefixes.firstOrNull { serial.replace(" ", "").startsWith(it.replace(" ", ""), true) },
                        firmware = firmware,
                        usb = candidates.firstOrNull()?.usbState ?: KindleUsbState.DETECTED,
                    )
                    message = "Approved recipe ${approved.id} v${approved.version}"
                }.onFailure { message = it.message }
            }) { Text("Identify and guard firmware") }
        }

        SetupStep(3, "Create recursive backup through Fylz") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { kindleTreePicker.launch(null) }) { Text(if (kindleTree == null) "Choose Kindle storage" else "Kindle selected") }
                OutlinedButton(onClick = { backupTreePicker.launch(null) }) { Text(if (backupTree == null) "Choose backup folder" else "Backup selected") }
            }
            Button(enabled = !busy && run?.nextStage == ProvisioningStage.BACKUP && kindleTree != null && backupTree != null, onClick = {
                val current = run ?: return@Button
                provisioningStore.saveStorageHandles(current.id, requireNotNull(kindleTree), requireNotNull(backupTree))
                busy = true
                scope.launch {
                    runCatching { coordinator.backup(current, requireNotNull(kindleTree), requireNotNull(backupTree)) { message = "Backup ${it.completedBytes}/${it.totalBytes ?: -1}" } }
                        .onSuccess { run = it; message = "Backup verified · ${it.checkpoints.last().receiptDigest}" }
                        .onFailure { message = it.message }
                    busy = false
                }
            }) { Text("Back up and verify") }
        }

        SetupStep(4, "Download and verify approved package") {
            Button(enabled = !busy && run?.nextStage == ProvisioningStage.VERIFY_PACKAGE && recipe != null, onClick = {
                val current = run ?: return@Button; val approved = recipe ?: return@Button
                busy = true
                scope.launch {
                    runCatching { coordinator.downloadAndVerify(current, approved) { message = "Hashing ${it.completedBytes}/${it.totalBytes ?: -1}" } }
                        .onSuccess { run = it.first; message = "Manifest SHA-256 verified" }
                        .onFailure { message = it.message }
                    busy = false
                }
            }) { Text("Download + SHA-256") }
        }

        SetupStep(5, "Stage files through Fylz") {
            Button(enabled = !busy && run?.nextStage == ProvisioningStage.STAGE_FILES && recipe != null && kindleTree != null, onClick = {
                val current = run ?: return@Button; val approved = recipe ?: return@Button
                busy = true
                scope.launch {
                    runCatching { coordinator.stage(current, approved, kindleTree = requireNotNull(kindleTree)) { message = "Staging ${it.completedBytes}/${it.totalBytes ?: -1}" } }
                        .onSuccess { run = it; message = "Fylz staged and verified every file" }
                        .onFailure { message = it.message }
                    busy = false
                }
            }) { Text("Extract verified package to Kindle") }
        }

        SetupStep(6, "Follow device-side prompts") {
            recipe?.instructions?.filter { it.kind.name in setOf("USER_ACTION", "RESTART") }?.forEach {
                Text("${it.title}: ${it.detail}")
            }
            Button(enabled = run?.nextStage == ProvisioningStage.USER_CONFIRMATION, onClick = {
                run = coordinator.confirm(requireNotNull(run)); message = "Confirmation checkpoint saved"
            }) { Text("I completed these steps") }
            Button(enabled = run?.nextStage == ProvisioningStage.PERFORM_STEP, onClick = {
                run = coordinator.markPerformed(requireNotNull(run), "Kindle-side WinterBreak step reported complete")
            }) { Text("Kindle UI restarted") }
        }

        SetupStep(7, "Verify homebrew on Kindle") {
            Text("Open the Homebrew/KUAL menu on the Kindle. Stop here if it is absent; do not continue by guessing or installing unrelated packages.")
            Button(onClick = { homebrewObserved = true; message = "Kindle-side homebrew confirmation recorded" }) {
                Text(if (homebrewObserved) "Homebrew menu confirmed" else "I can open the homebrew menu")
            }
        }

        SetupStep(8, "Establish SSH and verify resulting state") {
            OutlinedTextField(sshHost, { sshHost = it }, label = { Text("Kindle hotspot/Wi-Fi address") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sshUser, { sshUser = it }, label = { Text("SSH user") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sshPassword, { sshPassword = it }, label = { Text("SSH password (encrypted on phone)") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && homebrewObserved, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { ssh.presentedHostKey(RemoteHost("kindle-workdeck", sshHost, 22, sshUser)) }
                            .onSuccess { presentedKey = it; message = "Review fingerprint: ${it.fingerprint}" }
                            .onFailure { message = it.message }
                        busy = false
                    }
                }) { Text("Show host key") }
                Button(enabled = presentedKey != null && sshPassword.isNotBlank() && run?.nextStage == ProvisioningStage.VERIFY_STATE, onClick = {
                    val host = RemoteHost("kindle-workdeck", sshHost, 22, sshUser)
                    remoteStore.upsert(host)
                    remoteStore.setHostSecret(host.alias, sshPassword, isKey = false)
                    remoteStore.pin(host.endpoint, requireNotNull(presentedKey))
                    val identity = Identity.Password(requireNotNull(remoteStore.hostSecret(host.alias)).id)
                    busy = true
                    scope.launch {
                        runCatching {
                            val proof = ssh.probe(host, identity, remoteStore.knownHosts.value, profile?.id, profile?.model, serial.take(8))
                                val observed = buildSet {
                                    if (proof.deviceState.jailbreak == KindleFeatureState.PRESENT) add("JAILBROKEN")
                                    if (proof.deviceState.kpmHomebrew == KindleFeatureState.PRESENT) add("KPM_PRESENT")
                                    if (proof.deviceState.otaProtection == KindleFeatureState.PRESENT) add("OTA_PROTECTED")
                                }
                                val required = recipe?.expectedResultingStates.orEmpty()
                                require(observed.containsAll(required)) {
                                    "SSH connected, but required states are missing: ${required - observed}"
                                }
                                val verified = coordinator.verifyState(requireNotNull(run), observed)
                                proof to coordinator.finish(verified)
                            }.onSuccess { (proof, completed) ->
                                deviceState = proof.deviceState
                                run = completed
                                message = "Homebrew/OTA/SSH verified · receipt ${proof.sha256}"
                            }
                            .onFailure { message = it.message }
                        busy = false
                    }
                }) { Text("Trust, connect, verify") }
            }
            Text("The host key is never accepted silently. Passwords/keys remain Android-Keystore encrypted.")
        }

        SetupStep(9, "Install the manifest-verified Workdeck client") {
            Text("Download the CI/release manifest and matching ARM client on the phone. Unknown versions, hashes, profiles, firmware, sizes, or protocols are rejected.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clientManifestPicker.launch(arrayOf("application/json", "text/plain")) }) {
                    Text(if (clientManifest == null) "Choose client manifest" else "Manifest ${clientManifest?.clientVersion}")
                }
                OutlinedButton(onClick = { clientBinaryPicker.launch(arrayOf("application/octet-stream", "*/*")) }) {
                    Text(if (clientBinary == null) "Choose client binary" else "Client selected")
                }
            }
            OutlinedTextField(phoneAddress, { phoneAddress = it }, label = { Text("Phone hotspot address") }, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = !busy && run?.nextStage == null && clientManifest != null && clientBinary != null,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            val currentManifest = requireNotNull(clientManifest)
                            currentManifest.requireCompatible(requireNotNull(profile), requireNotNull(run).firmware)
                            val host = RemoteHost("kindle-workdeck", sshHost, 22, sshUser)
                            val reference = requireNotNull(remoteStore.hostSecret(host.alias)) { "Verify SSH first." }
                            val identity = if (reference.isKey) Identity.PublicKey(reference.id) else Identity.Password(reference.id)
                            ssh.installWorkdeckClient(
                                host = host,
                                identity = identity,
                                knownHosts = remoteStore.knownHosts.value,
                                resolver = context.contentResolver,
                                binaryUri = requireNotNull(clientBinary),
                                expectedSha256 = currentManifest.sha256,
                                expectedSizeBytes = currentManifest.sizeBytes,
                                pairingSecretBase64 = pairing.exportSecret(),
                                phoneAddress = phoneAddress,
                                port = WorkdeckService.PORT,
                            )
                            ssh.probe(host, identity, remoteStore.knownHosts.value, profile?.id, profile?.model, serial.take(8))
                        }.onSuccess { proof ->
                            if (proof.deviceState.workdeckClient != KindleFeatureState.PRESENT) {
                                message = "Client upload returned, but the executable was not verified on Kindle."
                            } else {
                                deviceState = proof.deviceState
                                message = "Workdeck client installed and started"
                            }
                        }.onFailure { message = it.message }
                        busy = false
                    }
                },
            ) { Text("Verify, install, and start") }
        }

        SetupStep(10, "Pair over the phone hotspot") {
            Text("Pairing code ${pairing.pairingCode()} · phone ${phoneAddress}:${WorkdeckService.PORT}. The full secret is transferred only through the pinned SSH session.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_TETHER_SETTINGS)) }) { Text("Phone hotspot") }
                Button(onClick = {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, WorkdeckService::class.java).setAction(WorkdeckService.ACTION_START),
                    )
                    message = "Workdeck is listening on the active private Wi-Fi/hotspot interface"
                }) { Text("Start LAN listener") }
            }
        }

        SetupStep(11, "Start Workdeck") {
            Text("Use native e-ink documents or project one chosen app/display. The phone remains authoritative and provides the normal Android IME.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = run?.nextStage == null, onClick = {
                    context.startActivity(Intent(context, dev.aarso.ui.workdeck.WorkdeckControllerActivity::class.java))
                }) { Text("Open Workdeck controller") }
                OutlinedButton(onClick = {
                    context.startService(Intent(context, WorkdeckService::class.java).setAction(WorkdeckService.ACTION_STOP))
                }) { Text("Stop Workdeck") }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        run?.let { Text("Checkpoint: ${it.nextStage?.name ?: "PROVISIONING COMPLETE"}") }
    }
}

@Composable
private fun SetupStep(number: Int, title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$number · $title", style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DeviceCard(state: KindleDeviceState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(state.model ?: "Kindle not identified", style = MaterialTheme.typography.titleLarge)
            listOf(
                "Serial prefix" to (state.serialPrefix ?: "unknown"),
                "Firmware" to (state.firmware?.toString() ?: "unknown"),
                "USB" to state.usb.name,
                "Network" to state.network.name,
                "Jailbreak" to state.jailbreak.name,
                "KPM / homebrew" to state.kpmHomebrew.name,
                "SSH" to state.ssh.name,
                "OTA protection" to state.otaProtection.name,
                "Workdeck client" to state.workdeckClient.name,
            ).forEach { (label, value) -> Text("$label · $value") }
        }
    }
}

private fun ContentResolver.readBoundedText(uri: Uri, maxBytes: Int): String {
    require(maxBytes > 0)
    val bytes = openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "Selected manifest exceeds $maxBytes bytes." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Unable to read the selected manifest handle.")
    return String(bytes, Charsets.UTF_8)
}
