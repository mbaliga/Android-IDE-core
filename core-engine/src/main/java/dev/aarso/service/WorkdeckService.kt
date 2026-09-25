package dev.aarso.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.aarso.data.workdeck.WorkdeckInboundEvent
import dev.aarso.data.workdeck.WorkdeckPairingStore
import dev.aarso.data.workdeck.WorkdeckProjectionSession
import dev.aarso.data.workdeck.WorkdeckSessionHub
import dev.aarso.domain.workdeck.NormalizedPointer
import dev.aarso.domain.workdeck.WorkdeckAuthenticator
import dev.aarso.domain.workdeck.WorkdeckMessageType
import dev.aarso.domain.workdeck.WorkdeckNativeDocumentCodec
import dev.aarso.domain.workdeck.WorkdeckPacket
import dev.aarso.domain.workdeck.WorkdeckPacketAuthenticator
import dev.aarso.domain.workdeck.WorkdeckPayloadCodec
import dev.aarso.domain.workdeck.WorkdeckProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-client, LAN-bound, authenticated e-ink display server. Phone state remains authoritative. */
class WorkdeckService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    @Volatile private var serverSocket: ServerSocket? = null
    private lateinit var projection: WorkdeckProjectionSession

    override fun onCreate() {
        super.onCreate()
        projection = WorkdeckProjectionSession(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START_PROJECTION -> {
                startServer()
                val data = androidx.core.content.IntentCompat.getParcelableExtra(
                    requireNotNull(intent), EXTRA_PROJECTION_DATA, Intent::class.java,
                ) ?: return START_NOT_STICKY
                projection.start(intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0), data)
            }
            else -> startServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { serverSocket?.close() }
        projection.stop()
        scope.cancel()
        WorkdeckSessionHub.update { it.copy(listeningAddress = null, connectedDevice = null) }
        super.onDestroy()
    }

    private fun startServer() {
        if (serverJob?.isActive == true) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Fonebrew Workdeck")
            .setContentText("Waiting for the paired Kindle on the local network")
            .setOngoing(true)
            .build())
        serverJob = scope.launch { serverLoop() }
    }

    private suspend fun serverLoop() = withContext(Dispatchers.IO) {
        while (true) {
            try {
                val address = lanAddress() ?: error("No private Wi-Fi/hotspot address is active.")
                ServerSocket().use { server ->
                    serverSocket = server
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(address, PORT), 1)
                    WorkdeckSessionHub.update {
                        it.copy(listeningAddress = "${address.hostAddress}:$PORT", lastError = null)
                    }
                    while (true) {
                        val socket = server.accept()
                        if (!socket.inetAddress.isPrivateLanAddress()) {
                            socket.close()
                            continue
                        }
                        runCatching { serve(socket) }.onFailure { failure ->
                            if (failure !is CancellationException) {
                                WorkdeckSessionHub.update { it.copy(lastError = failure.message, connectedDevice = null) }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                WorkdeckSessionHub.update { it.copy(lastError = failure.message, listeningAddress = null, connectedDevice = null) }
                kotlinx.coroutines.delay(RETRY_MILLIS)
            }
        }
    }

    private suspend fun serve(socket: Socket) = socket.use { client ->
        client.tcpNoDelay = true
        client.keepAlive = true
        client.soTimeout = 45_000
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
        val hello = WorkdeckProtocol.read(input)
        require(hello.type == WorkdeckMessageType.HELLO) { "Client did not begin with HELLO." }
        val capabilities = WorkdeckPayloadCodec.decodeHello(hello.payload)
        val secret = WorkdeckPairingStore(applicationContext).secret()
        val nonce = ByteArray(WorkdeckAuthenticator.NONCE_BYTES).also(SecureRandom()::nextBytes)
        write(output, WorkdeckPacket(type = WorkdeckMessageType.AUTH_CHALLENGE, sequence = 0, payload = nonce))
        val response = WorkdeckProtocol.read(input)
        require(response.type == WorkdeckMessageType.AUTH_RESPONSE) { "Client did not answer pairing challenge." }
        require(WorkdeckAuthenticator.verify(secret, nonce, capabilities.deviceId, response.payload)) {
            "Workdeck pairing authentication failed."
        }
        WorkdeckSessionHub.update {
            it.copy(connectedDevice = capabilities, lastError = null, connectionEpoch = it.connectionEpoch + 1)
        }
        val outboundSequence = AtomicLong(1)
        write(
            output,
            WorkdeckPacketAuthenticator.seal(
                secret,
                WorkdeckPacket(
                    type = WorkdeckMessageType.VIEWPORT_ROTATION,
                    sequence = outboundSequence.getAndIncrement(),
                    payload = WorkdeckPayloadCodec.viewport(capabilities.displayWidth, capabilities.displayHeight, 0),
                ),
            ),
        )
        WorkdeckSessionHub.state.value.nativeDocument?.let { document ->
            write(
                output,
                WorkdeckPacketAuthenticator.seal(
                    secret,
                    WorkdeckPacket(
                        type = WorkdeckMessageType.NATIVE_DOCUMENT,
                        sequence = outboundSequence.getAndIncrement(),
                        payload = WorkdeckNativeDocumentCodec.encode(document),
                    ),
                ),
            )
        }
        coroutineScope {
            val writer = launch {
                WorkdeckSessionHub.outbound.collect { packet ->
                    val sequenced = packet.copy(sequence = outboundSequence.getAndIncrement())
                    write(output, WorkdeckPacketAuthenticator.seal(secret, sequenced))
                }
            }
            try {
                var lastInboundSequence = -1L
                while (true) {
                    val packet = WorkdeckPacketAuthenticator.open(secret, WorkdeckProtocol.read(input))
                    require(packet.sequence > lastInboundSequence) { "Replayed/out-of-order Workdeck packet." }
                    lastInboundSequence = packet.sequence
                    handleInbound(packet, output, secret, outboundSequence)
                }
            } finally {
                writer.cancel()
                WorkdeckSessionHub.update { it.copy(connectedDevice = null) }
            }
        }
    }

    private fun handleInbound(
        packet: WorkdeckPacket,
        output: DataOutputStream,
        secret: ByteArray,
        sequence: AtomicLong,
    ) {
        when (packet.type) {
            WorkdeckMessageType.TOUCH_POINTER -> WorkdeckPayloadCodec.decodeTouch(packet.payload).let {
                WorkdeckSessionHub.receive(WorkdeckInboundEvent.Pointer(NormalizedPointer(it.x, it.y), it.action))
            }
            WorkdeckMessageType.KEYBOARD -> WorkdeckSessionHub.receive(
                WorkdeckInboundEvent.Keyboard(WorkdeckPayloadCodec.decodeText(packet.payload)),
            )
            WorkdeckMessageType.CLIPBOARD_TEXT -> WorkdeckSessionHub.receive(
                WorkdeckInboundEvent.Clipboard(WorkdeckPayloadCodec.decodeText(packet.payload)),
            )
            WorkdeckMessageType.CONTROL_ACTION -> WorkdeckSessionHub.receive(
                WorkdeckInboundEvent.Control(WorkdeckPayloadCodec.decodeText(packet.payload)),
            )
            WorkdeckMessageType.SUSPEND_WAKE -> {
                val value = WorkdeckPayloadCodec.decodeText(packet.payload)
                WorkdeckSessionHub.receive(if (value == "wake") WorkdeckInboundEvent.ClientWoke else WorkdeckInboundEvent.ClientSuspended)
            }
            WorkdeckMessageType.PING_RECONNECT -> write(
                output,
                WorkdeckPacketAuthenticator.seal(
                    secret,
                    WorkdeckPacket(
                        type = WorkdeckMessageType.PING_RECONNECT,
                        sequence = sequence.getAndIncrement(),
                        payload = packet.payload,
                    ),
                ),
            )
            else -> Unit
        }
    }

    private fun write(output: DataOutputStream, packet: WorkdeckPacket) {
        synchronized(output) {
            output.write(WorkdeckProtocol.encode(packet))
            output.flush()
        }
    }

    private fun lanAddress(): Inet4Address? = Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { Collections.list(it.inetAddresses).asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }

    private fun InetAddress.isPrivateLanAddress(): Boolean = isSiteLocalAddress || isLoopbackAddress

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Workdeck", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val PORT = 48731
        const val ACTION_START = "dev.aarso.workdeck.START"
        const val ACTION_STOP = "dev.aarso.workdeck.STOP"
        const val ACTION_START_PROJECTION = "dev.aarso.workdeck.START_PROJECTION"
        const val EXTRA_PROJECTION_RESULT_CODE = "dev.aarso.workdeck.PROJECTION_RESULT_CODE"
        const val EXTRA_PROJECTION_DATA = "dev.aarso.workdeck.PROJECTION_DATA"
        private const val CHANNEL_ID = "workdeck"
        private const val NOTIFICATION_ID = 4047
        private const val RETRY_MILLIS = 2_000L
    }
}
