package dev.fonebrew.domain.gitcore

import java.security.MessageDigest

/**
 * Hash algorithm of a repository. Only SHA-1 is implemented; the width is carried by the
 * algorithm (not hard-coded in [ObjectId]) so SHA-256 is an additive follow-up.
 */
enum class HashAlgorithm(val gitName: String, val byteLength: Int, private val jca: String) {
    SHA1("sha1", 20, "SHA-1");

    val hexLength: Int get() = byteLength * 2
    fun newDigest(): MessageDigest = MessageDigest.getInstance(jca)
    fun zero(): ObjectId = ObjectId(ByteArray(byteLength), this)
}

/** An object name: the raw hash bytes plus the algorithm that produced them. Value semantics. */
class ObjectId(bytes: ByteArray, val algorithm: HashAlgorithm = HashAlgorithm.SHA1) : Comparable<ObjectId> {
    private val raw: ByteArray = bytes.copyOf()

    init {
        if (raw.size != algorithm.byteLength) malformed("object id must be ${algorithm.byteLength} bytes, got ${raw.size}")
    }

    fun toByteArray(): ByteArray = raw.copyOf()
    fun byteAt(i: Int): Int = raw[i].toInt() and 0xff
    fun copyInto(dst: ByteArray, offset: Int) { System.arraycopy(raw, 0, dst, offset, raw.size) }

    val hex: String get() = buildString(raw.size * 2) {
        for (b in raw) { append(HEX[(b.toInt() shr 4) and 0xf]); append(HEX[b.toInt() and 0xf]) }
    }
    val isZero: Boolean get() = raw.all { it == 0.toByte() }
    fun abbrev(n: Int = 7): String = hex.take(n)

    override fun equals(other: Any?) = other is ObjectId && other.algorithm == algorithm && other.raw.contentEquals(raw)
    override fun hashCode(): Int = raw.contentHashCode()
    override fun toString(): String = hex
    override fun compareTo(other: ObjectId): Int {
        for (i in raw.indices) {
            val c = (raw[i].toInt() and 0xff) - (other.raw[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return 0
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        fun fromBytes(src: ByteArray, offset: Int = 0, algorithm: HashAlgorithm = HashAlgorithm.SHA1): ObjectId {
            if (offset < 0 || offset + algorithm.byteLength > src.size) malformed("object id runs past end of data")
            return ObjectId(src.copyOfRange(offset, offset + algorithm.byteLength), algorithm)
        }

        /** Parses lower- or upper-case hex; null when not exactly a full-length hex id. */
        fun fromHexOrNull(hex: String, algorithm: HashAlgorithm = HashAlgorithm.SHA1): ObjectId? {
            if (hex.length != algorithm.hexLength) return null
            val out = ByteArray(algorithm.byteLength)
            for (i in out.indices) {
                val h = Character.digit(hex[2 * i], 16)
                val l = Character.digit(hex[2 * i + 1], 16)
                if (h < 0 || l < 0) return null
                out[i] = ((h shl 4) or l).toByte()
            }
            return ObjectId(out, algorithm)
        }

        fun fromHex(hex: String, algorithm: HashAlgorithm = HashAlgorithm.SHA1): ObjectId =
            fromHexOrNull(hex, algorithm) ?: malformed("not a ${algorithm.hexLength}-digit hex object id")

        /** The id of an object: hash of `"<type> <size>\0" + body`. */
        fun hashObject(type: ObjectType, body: ByteArray, algorithm: HashAlgorithm = HashAlgorithm.SHA1): ObjectId {
            val md = algorithm.newDigest()
            md.update("${type.tag} ${body.size}".toByteArray(Charsets.US_ASCII))
            md.update(0)
            md.update(body)
            return ObjectId(md.digest(), algorithm)
        }
    }
}
