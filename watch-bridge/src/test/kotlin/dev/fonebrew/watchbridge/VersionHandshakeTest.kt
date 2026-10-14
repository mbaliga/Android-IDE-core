package dev.fonebrew.watchbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version mismatch decodes to a typed incompatibility result, never a crash and never a payload read. */
class VersionHandshakeTest {

    private fun incompatible(text: String): DecodeResult.Incompatible {
        val r = decodeAny(text)
        assertTrue("expected Incompatible but was $r for: $text", r is DecodeResult.Incompatible)
        return r as DecodeResult.Incompatible
    }

    @Test
    fun contractVersionIsPinned() {
        // Bumping this is a deliberate protocol change: update this test in the same commit.
        assertEquals(1, WatchBridge.CONTRACT_VERSION)
        assertEquals("watchbrew", WatchBridge.CONTRACT_NAME)
    }

    @Test
    fun matchingVersionHello_decodes() {
        val hello = decodedAs<ContractHello>(env("ContractHello", """{"contractVersion":1,"phoneAppVersion":"0.3.1"}"""))
        assertEquals(HelloCompatibility.Compatible, hello.compatibility())
    }

    @Test
    fun unknownNewerVersionHello_isTypedIncompatible_notACrash() {
        val r = incompatible(env("ContractHello", """{"contractVersion":2,"phoneAppVersion":"9.9.9","futureField":{"x":[1]}}""", v = "2"))
        assertEquals(2, r.mismatch.peerVersion)
        assertEquals(1, r.mismatch.localVersion)
        assertTrue(r.mismatch.peerIsNewer)
        assertEquals("ContractHello", r.claimedType)
        assertTrue(r.reason, r.reason.contains("v2") && r.reason.contains("v1"))
        assertTrue("must tell the user who should update: ${r.reason}", r.reason.contains("Update this app"))
    }

    @Test
    fun futureVersionPayload_isNeverInterpreted() {
        // Whatever another version puts in payload/type is its own business: still Incompatible.
        incompatible(env("ContractHello", """{"totally":"different"}""", v = "2"))
        incompatible(env("AskChunk", """"a string payload"""", v = "7"))
        incompatible(env("QuantumMessage", """[1,2,3]""", v = "3"))
        incompatible("""{"contract":"watchbrew","v":2,"type":"AskRequest"}""") // no payload at all
        incompatible("""{"contract":"watchbrew","v":2}""") // no type either
        assertNull(incompatible("""{"contract":"watchbrew","v":2}""").claimedType)
        assertNull(incompatible("""{"contract":"watchbrew","v":2,"type":5}""").claimedType)
    }

    @Test
    fun largestRepresentableVersion_isIncompatible() {
        val r = incompatible(env("ContractHello", "{}", v = Int.MAX_VALUE.toString()))
        assertEquals(Int.MAX_VALUE, r.mismatch.peerVersion)
    }

    @Test
    fun everyMessageType_atAnotherVersion_isIncompatible() {
        for (sample in ALL_SAMPLES) {
            val text = WatchBridgeCodec.encode(sample).replace("\"v\":1", "\"v\":2")
            assertTrue("test setup must have changed the version", text.contains("\"v\":2"))
            val r = incompatible(text)
            assertEquals(sample.wireType().wire, r.claimedType)
        }
    }

    @Test
    fun olderPeer_isReportedAsOlder() {
        // The local side pretends to be v2; a v1 peer is then the OLDER one. (Exercises the same mismatch
        // type the decoder produces, from the hello path.)
        val hello = ContractHello(contractVersion = 1, phoneAppVersion = "0.1.0")
        val c = hello.compatibility(localVersion = 2) as HelloCompatibility.Incompatible
        assertFalse(c.mismatch.peerIsNewer)
        assertTrue(c.mismatch.reason, c.mismatch.reason.contains("Update the other device"))
    }

    @Test
    fun versionBelowOne_orWrongTypedVersion_isMalformedNotIncompatible() {
        for (v in listOf("0", "-1", "\"2\"", "2.0", "true", "null", "2147483648", "4294967297")) {
            malformed(env("ContractHello", """{"contractVersion":1,"phoneAppVersion":"1"}""", v = v))
        }
    }

    @Test
    fun wrongContractMarker_isMalformedEvenAtAnotherVersion() {
        // Only a WatchBrew envelope can be "incompatible"; foreign JSON is just not ours.
        malformed(env("ContractHello", "{}", v = "2", contract = "\"somethingelse\""))
        malformed("""{"v":2,"type":"ContractHello","payload":{}}""")
    }

    @Test
    fun helloWhoseAppSpeaksAnotherVersion_decodesThenReportsIncompatibility() {
        // Envelope shape v1 (we can read it) but the sender's APP speaks contract v2: the downgrade-hello case.
        val hello = decodedAs<ContractHello>(env("ContractHello", """{"contractVersion":2,"phoneAppVersion":"9.0.0"}"""))
        assertEquals(2, hello.contractVersion)
        val c = hello.compatibility()
        assertTrue(c is HelloCompatibility.Incompatible)
        val mismatch = (c as HelloCompatibility.Incompatible).mismatch
        assertEquals(VersionMismatch(peerVersion = 2, localVersion = 1), mismatch)
        assertTrue(mismatch.peerIsNewer)
    }

    @Test
    fun hello_forThisBuild_isCompatibleWithThisBuild() {
        val hello = ContractHello.forThisBuild("0.3.1")
        assertEquals(WatchBridge.CONTRACT_VERSION, hello.contractVersion)
        assertEquals(HelloCompatibility.Compatible, hello.compatibility())
    }

    @Test
    fun versionMismatch_cannotDescribeEqualOrInvalidVersions() {
        assertRejected { VersionMismatch(1, 1) }
        assertRejected { VersionMismatch(0, 1) }
        assertRejected { VersionMismatch(2, 0) }
    }

    @Test
    fun encodedMessages_alwaysStampTheCurrentVersionAndMarker() {
        for (sample in ALL_SAMPLES) {
            val text = WatchBridgeCodec.encode(sample)
            assertTrue(text, text.contains("\"v\":${WatchBridge.CONTRACT_VERSION}"))
            assertTrue(text, text.contains("\"contract\":\"${WatchBridge.CONTRACT_NAME}\""))
        }
    }
}
