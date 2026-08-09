package dev.aarso.domain.contracts

import dev.aarso.contracts.common.DigestAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestTest {

    @Test
    fun `of computes the well-known SHA-256 digest of an empty byte array`() {
        val ref = Digest.of(ByteArray(0))
        // The universally-known SHA-256("") test vector.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ref.digestHex)
        assertEquals(0L, ref.byteLength)
        assertEquals(DigestAlgorithm.SHA_256, ref.algorithm)
    }

    @Test
    fun `ofUtf8 computes the well-known SHA-256 digest of 'abc'`() {
        val ref = Digest.ofUtf8("abc")
        // The universally-known SHA-256("abc") test vector (FIPS 180-4 example).
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ref.digestHex)
        assertEquals(3L, ref.byteLength)
    }

    @Test
    fun `digestHex is lowercase and exactly 64 hex characters for SHA-256`() {
        val ref = Digest.ofUtf8("some arbitrary text")
        assertEquals(64, ref.digestHex.length)
        assertTrue(ref.digestHex.all { it in "0123456789abcdef" })
    }

    @Test
    fun `verify returns true for the exact bytes a digest was computed over`() {
        val bytes = "verify me".toByteArray(Charsets.UTF_8)
        val ref = Digest.of(bytes)
        assertTrue(Digest.verify(bytes, ref))
    }

    @Test
    fun `verify returns false when the bytes have changed since the digest was computed`() {
        val original = "original".toByteArray(Charsets.UTF_8)
        val tampered = "tampered".toByteArray(Charsets.UTF_8)
        val ref = Digest.of(original)
        assertFalse(Digest.verify(tampered, ref))
    }

    @Test
    fun `verify never throws on a mismatch, only returns false`() {
        val ref = Digest.ofUtf8("x").copy(digestHex = "0000000000000000000000000000000000000000000000000000000000000000")
        assertFalse(Digest.verify("x".toByteArray(), ref))
    }

    @Test
    fun `of supports SHA-384 and SHA-512 with correctly-sized output`() {
        val sha384 = Digest.ofUtf8("x", DigestAlgorithm.SHA_384)
        val sha512 = Digest.ofUtf8("x", DigestAlgorithm.SHA_512)
        assertEquals(96, sha384.digestHex.length)
        assertEquals(128, sha512.digestHex.length)
    }
}
