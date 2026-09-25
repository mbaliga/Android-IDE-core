package dev.aarso.domain.workdeck

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicLong

enum class WorkdeckSectionKind {
    PROMPT_RESPONSE,
    REPOSITORY_STATE,
    TERMINAL_OUTPUT,
    DIFF,
    FILE_CONTEXT,
    AGENT_STATUS,
    LONG_FORM_READING,
}

data class WorkdeckNativeSection(
    val kind: WorkdeckSectionKind,
    val title: String,
    val body: String,
) {
    init {
        require(title.length <= MAX_TITLE_CHARS) { "Workdeck section title is too long." }
        require(body.length <= MAX_BODY_CHARS) { "Workdeck section body is too long." }
    }

    private companion object {
        const val MAX_TITLE_CHARS = 256
        const val MAX_BODY_CHARS = 512 * 1024
    }
}

/** Semantic, text-first mode rendered by the Kindle rather than screen-captured pixels. */
data class WorkdeckNativeDocument(
    val title: String,
    val revision: Long,
    val sections: List<WorkdeckNativeSection>,
) {
    init {
        require(title.isNotBlank() && title.length <= 256) { "Workdeck document title is invalid." }
        require(revision >= 0) { "Workdeck document revision must be non-negative." }
        require(sections.isNotEmpty() && sections.size <= 64) { "Workdeck document section count is invalid." }
    }
}

object WorkdeckNativeDocumentCodec {
    fun encode(document: WorkdeckNativeDocument): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeText(document.title)
            output.writeLong(document.revision)
            output.writeInt(document.sections.size)
            document.sections.forEach { section ->
                output.writeByte(section.kind.ordinal)
                output.writeText(section.title)
                output.writeText(section.body)
            }
        }
        bytes.toByteArray().also {
            require(it.size <= WorkdeckProtocol.MAX_PAYLOAD_BYTES) { "Workdeck native document exceeds protocol limit." }
        }
    }

    fun decode(payload: ByteArray): WorkdeckNativeDocument {
        require(payload.size <= WorkdeckProtocol.MAX_PAYLOAD_BYTES)
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val title = input.readText()
            val revision = input.readLong()
            val count = input.readInt()
            require(count in 1..64) { "Invalid Workdeck native document section count." }
            WorkdeckNativeDocument(
                title = title,
                revision = revision,
                sections = List(count) {
                    val kind = WorkdeckSectionKind.entries.getOrNull(input.readUnsignedByte())
                        ?: error("Unknown Workdeck section kind.")
                    WorkdeckNativeSection(kind, input.readText(), input.readText())
                },
            ).also { require(input.available() == 0) { "Trailing Workdeck native document data." } }
        }
    }

    private fun DataOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "Workdeck native text field is too large." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val count = readInt()
        require(count in 0..MAX_TEXT_BYTES) { "Invalid Workdeck native text length." }
        return String(ByteArray(count).also(::readFully), Charsets.UTF_8)
    }

    private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
}

/** Public seam used by Fonebrew/Nooz/Council/Loop surfaces to publish e-ink-native state. */
class WorkdeckNativePublisher(
    private val send: (WorkdeckPacket) -> Boolean,
) {
    private val sequence = AtomicLong(1)

    fun publish(document: WorkdeckNativeDocument): Boolean = send(
        WorkdeckPacket(
            type = WorkdeckMessageType.NATIVE_DOCUMENT,
            sequence = sequence.getAndIncrement(),
            payload = WorkdeckNativeDocumentCodec.encode(document),
        ),
    )
}
