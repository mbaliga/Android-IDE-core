package dev.aarso.data.workdeck

import dev.aarso.domain.workdeck.GrayFrame
import dev.aarso.domain.workdeck.NormalizedPointer
import dev.aarso.domain.workdeck.WorkdeckCapabilities
import dev.aarso.domain.workdeck.WorkdeckMessageType
import dev.aarso.domain.workdeck.WorkdeckPacket
import dev.aarso.domain.workdeck.WorkdeckQuality
import dev.aarso.domain.workdeck.WorkdeckRect
import dev.aarso.domain.workdeck.WorkdeckPayloadCodec
import dev.aarso.domain.workdeck.WorkdeckNativeDocument
import dev.aarso.domain.workdeck.WorkdeckNativeDocumentCodec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface WorkdeckInboundEvent {
    data class Pointer(val pointer: NormalizedPointer, val action: Int) : WorkdeckInboundEvent
    data class Keyboard(val text: String) : WorkdeckInboundEvent
    data class Clipboard(val text: String) : WorkdeckInboundEvent
    data class Control(val action: String) : WorkdeckInboundEvent
    data object ClientSuspended : WorkdeckInboundEvent
    data object ClientWoke : WorkdeckInboundEvent
}

data class WorkdeckServerState(
    val listeningAddress: String? = null,
    val connectedDevice: WorkdeckCapabilities? = null,
    val lastError: String? = null,
    val projectionActive: Boolean = false,
    val connectionEpoch: Long = 0,
    val nativeDocument: WorkdeckNativeDocument? = null,
)

/** Process-level bus. The service is authoritative; UI/projection code only publishes intent. */
object WorkdeckSessionHub {
    private val mutableState = MutableStateFlow(WorkdeckServerState())
    val state: StateFlow<WorkdeckServerState> = mutableState.asStateFlow()

    private val mutableInbound = MutableSharedFlow<WorkdeckInboundEvent>(extraBufferCapacity = 128)
    val inbound: SharedFlow<WorkdeckInboundEvent> = mutableInbound.asSharedFlow()

    private val mutableOutbound = MutableSharedFlow<WorkdeckPacket>(extraBufferCapacity = 128)
    val outbound: SharedFlow<WorkdeckPacket> = mutableOutbound.asSharedFlow()

    internal fun update(transform: (WorkdeckServerState) -> WorkdeckServerState) {
        mutableState.value = transform(mutableState.value)
    }

    internal fun receive(event: WorkdeckInboundEvent) { mutableInbound.tryEmit(event) }
    fun send(packet: WorkdeckPacket): Boolean = mutableOutbound.tryEmit(packet)

    fun sendText(text: String, sequence: Long): Boolean = send(
        WorkdeckPacket(type = WorkdeckMessageType.CLIPBOARD_TEXT, sequence = sequence, payload = WorkdeckPayloadCodec.text(text)),
    )

    fun showNativeDocument(document: WorkdeckNativeDocument, sequence: Long = 0): Boolean {
        update { it.copy(nativeDocument = document) }
        return send(WorkdeckPacket(
            type = WorkdeckMessageType.NATIVE_DOCUMENT,
            sequence = sequence,
            payload = WorkdeckNativeDocumentCodec.encode(document),
        ))
    }
}

data class WorkdeckEncodedRegion(
    val sequence: Long,
    val fullFrame: Boolean,
    val frame: GrayFrame,
    val rect: WorkdeckRect,
    val quality: WorkdeckQuality,
)
