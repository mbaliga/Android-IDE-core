package dev.fonebrew.domain.editor.buffer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** What the bytes on disk were, so a save writes them back the same way. */
enum class TextEncoding(val label: String, val bom: ByteArray) {
    UTF8("UTF-8", byteArrayOf()),
    UTF8_BOM("UTF-8 (BOM)", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    UTF16LE_BOM("UTF-16LE (BOM)", byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    UTF16BE_BOM("UTF-16BE (BOM)", byteArrayOf(0xFE.toByte(), 0xFF.toByte())),

    /** Not detected, assumed: invalid UTF-8 falls back here. Byte-exact round trip, but FLAGGED to the user. */
    LATIN1("ISO-8859-1 (fallback guess)", byteArrayOf());

    /** True when the encoding was a guess the user should be shown, not a fact read from the file. */
    val isGuess: Boolean get() = this == LATIN1
}

enum class RefusalReason { BINARY, UTF32_UNSUPPORTED, MALFORMED_UTF16, ODD_UTF16_LENGTH }

sealed interface DecodeResult {
    data class Ok(val text: String, val encoding: TextEncoding) : DecodeResult
    data class Refused(val reason: RefusalReason, val detail: String) : DecodeResult
}

sealed interface EncodeResult {
    data class Ok(val bytes: ByteArray) : EncodeResult {
        override fun equals(other: Any?) = other is Ok && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    /** [offset] is the first UTF-16 offset that the encoding cannot represent. */
    data class Unrepresentable(val offset: Int, val detail: String) : EncodeResult
}

object TextEncodings {
    private const val BINARY_SNIFF = 8192

    fun decode(bytes: ByteArray): DecodeResult {
        fun starts(vararg b: Int) = bytes.size >= b.size && b.indices.all { (bytes[it].toInt() and 0xFF) == b[it] }
        if (starts(0xFF, 0xFE, 0x00, 0x00) || starts(0x00, 0x00, 0xFE, 0xFF)) {
            return DecodeResult.Refused(RefusalReason.UTF32_UNSUPPORTED, "UTF-32 files are not supported")
        }
        if (starts(0xEF, 0xBB, 0xBF)) {
            return strictUtf8(bytes, 3)?.let { DecodeResult.Ok(it, TextEncoding.UTF8_BOM) }
                ?: latin1(bytes)
        }
        if (starts(0xFF, 0xFE)) return utf16(bytes, java.nio.ByteOrder.LITTLE_ENDIAN, TextEncoding.UTF16LE_BOM)
        if (starts(0xFE, 0xFF)) return utf16(bytes, java.nio.ByteOrder.BIG_ENDIAN, TextEncoding.UTF16BE_BOM)
        for (i in 0 until minOf(bytes.size, BINARY_SNIFF)) {
            if (bytes[i] == 0.toByte()) {
                return DecodeResult.Refused(RefusalReason.BINARY, "NUL byte at offset $i: not a text file (or UTF-16 without a BOM)")
            }
        }
        return strictUtf8(bytes, 0)?.let { DecodeResult.Ok(it, TextEncoding.UTF8) } ?: latin1(bytes)
    }

    private fun strictUtf8(bytes: ByteArray, from: Int): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, from, bytes.size - from)).toString()
    } catch (e: CharacterCodingException) { null }

    private fun latin1(bytes: ByteArray): DecodeResult {
        for (i in 0 until minOf(bytes.size, BINARY_SNIFF)) {
            if (bytes[i] == 0.toByte()) return DecodeResult.Refused(RefusalReason.BINARY, "NUL byte at offset $i")
        }
        return DecodeResult.Ok(String(bytes, StandardCharsets.ISO_8859_1), TextEncoding.LATIN1)
    }

    private fun utf16(bytes: ByteArray, order: java.nio.ByteOrder, enc: TextEncoding): DecodeResult {
        val body = bytes.size - 2
        if (body % 2 != 0) return DecodeResult.Refused(RefusalReason.ODD_UTF16_LENGTH, "odd number of bytes after the BOM")
        val cs = if (order == java.nio.ByteOrder.LITTLE_ENDIAN) StandardCharsets.UTF_16LE else StandardCharsets.UTF_16BE
        return try {
            val s = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 2, body)).toString()
            DecodeResult.Ok(s, enc)
        } catch (e: CharacterCodingException) {
            DecodeResult.Refused(RefusalReason.MALFORMED_UTF16, "unpaired surrogate in UTF-16 data")
        }
    }

    /** Index of the first unpaired surrogate in [text], or -1. */
    fun firstLoneSurrogate(text: CharSequence): Int {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) { i += 2; continue }
                return i
            }
            if (Character.isLowSurrogate(c)) return i
            i++
        }
        return -1
    }

    fun encode(text: String, encoding: TextEncoding): EncodeResult {
        if (encoding == TextEncoding.LATIN1) {
            for (i in text.indices) if (text[i].code > 0xFF) {
                return EncodeResult.Unrepresentable(i, "U+%04X does not fit ISO-8859-1; save as UTF-8 instead".format(text[i].code))
            }
            return EncodeResult.Ok(text.toByteArray(StandardCharsets.ISO_8859_1))
        }
        val lone = firstLoneSurrogate(text)
        if (lone >= 0) return EncodeResult.Unrepresentable(lone, "unpaired surrogate cannot be encoded")
        val body = when (encoding) {
            TextEncoding.UTF8, TextEncoding.UTF8_BOM -> text.toByteArray(StandardCharsets.UTF_8)
            TextEncoding.UTF16LE_BOM -> text.toByteArray(StandardCharsets.UTF_16LE)
            TextEncoding.UTF16BE_BOM -> text.toByteArray(StandardCharsets.UTF_16BE)
            TextEncoding.LATIN1 -> error("handled above")
        }
        return EncodeResult.Ok(encoding.bom + body)
    }
}
