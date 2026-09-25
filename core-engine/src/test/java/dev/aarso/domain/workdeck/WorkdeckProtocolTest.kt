package dev.aarso.domain.workdeck

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkdeckProtocolTest {
    @Test fun packetRoundTrips() {
        val original = WorkdeckPacket(type = WorkdeckMessageType.CLIPBOARD_TEXT, sequence = 7, payload = "hello".toByteArray())
        assertEquals(original, WorkdeckProtocol.decode(WorkdeckProtocol.encode(original)))
    }

    @Test fun helloRoundTripsCapabilities() {
        val original = WorkdeckCapabilities("koa3-1", 1264, 1680, 16, touch = true, keyboard = false, pageButtons = true, waveforms = setOf("GC16", "DU"))
        assertEquals(original, WorkdeckPayloadCodec.decodeHello(WorkdeckPayloadCodec.hello(original)))
    }

    @Test fun viewportRoundTripsOrientation() {
        assertEquals(
            WorkdeckPayloadCodec.ViewportPayload(1680, 1264, 90),
            WorkdeckPayloadCodec.decodeViewport(WorkdeckPayloadCodec.viewport(1680, 1264, 90)),
        )
    }

    @Test fun authenticationBindsNonceAndDevice() {
        val secret = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(32) { (it * 3).toByte() }
        val response = WorkdeckAuthenticator.response(secret, nonce, "koa3")
        assertTrue(WorkdeckAuthenticator.verify(secret, nonce, "koa3", response))
        assertFalse(WorkdeckAuthenticator.verify(secret, nonce, "other", response))
    }

    @Test fun dirtyRegionFindsSeparateChangesAndEncodesOnlyThem() {
        val before = GrayFrame(64, 64, ByteArray(64 * 64))
        val pixels = ByteArray(64 * 64)
        pixels[2 * 64 + 2] = 127
        pixels[52 * 64 + 52] = 127
        val after = GrayFrame(64, 64, pixels)
        val result = DirtyRegionDetector(tileSize = 16, pixelThreshold = 1).detect(before, after)
        assertFalse(result.fullFrame)
        assertEquals(2, result.rectangles.size)
        assertTrue(result.rectangles.sumOf { it.area } < pixels.size)
        assertTrue(WorkdeckRegionEncoder.encode(after, result.rectangles.first(), WorkdeckQuality.GENERAL).isNotEmpty())
    }

    @Test fun orientationMapsNormalizedInput() {
        assertEquals(PixelPointer(99, 0), WorkdeckCoordinateMapper.toPixels(NormalizedPointer(0f, 0f), 100, 200, 90))
        assertEquals(PixelPointer(0, 199), WorkdeckCoordinateMapper.toPixels(NormalizedPointer(0f, 0f), 100, 200, 270))
    }

    @Test fun refreshSchedulerEventuallyForcesCleanup() {
        val scheduler = WorkdeckRefreshScheduler(fullRefreshAfterPartials = 2, fullRefreshAfterMillis = Long.MAX_VALUE)
        scheduler.decide(1, 0.01f, scrolling = false, textDominant = false)
        scheduler.decide(2, 0.01f, scrolling = false, textDominant = false)
        assertTrue(scheduler.decide(3, 0.01f, scrolling = false, textDominant = false).forceFullFrame)
    }

    @Test fun nativeDocumentRoundTripsAllStructuredModes() {
        val original = WorkdeckNativeDocument(
            title = "Fonebrew",
            revision = 19,
            sections = WorkdeckSectionKind.entries.map {
                WorkdeckNativeSection(it, it.name, "line one\nline two")
            },
        )
        assertEquals(original, WorkdeckNativeDocumentCodec.decode(WorkdeckNativeDocumentCodec.encode(original)))
    }

    @Test fun everyPostHandshakePacketIsMacBoundToTypeSequenceAndPayload() {
        val secret = ByteArray(32) { (it + 1).toByte() }
        val packet = WorkdeckPacket( type = WorkdeckMessageType.PING_RECONNECT, sequence = 4, payload = byteArrayOf(7))
        val sealed = WorkdeckPacketAuthenticator.seal(secret, packet)
        assertEquals(packet, WorkdeckPacketAuthenticator.open(secret, sealed))
        val tampered = sealed.copy(payload = sealed.payload.clone().also { it[it.lastIndex] = 8 })
        assertTrue(runCatching { WorkdeckPacketAuthenticator.open(secret, tampered) }.isFailure)
    }

    @Test fun projectionFitsKindleAspectWithoutStretching() {
        val source = GrayFrame(4, 2, byteArrayOf(0, 32, 64, 96, 128.toByte(), 160.toByte(), 192.toByte(), 255.toByte()))
        val fitted = GrayFrameScaler.fit(source, 4, 4)
        assertEquals(4, fitted.width)
        assertEquals(4, fitted.height)
        assertTrue((0 until 4).all { fitted.unsignedAt(it, 0) == 255 })
        assertTrue((0 until 4).all { fitted.unsignedAt(it, 3) == 255 })
        assertEquals(0, fitted.unsignedAt(0, 1))
        assertEquals(255, fitted.unsignedAt(3, 2))
    }
}
