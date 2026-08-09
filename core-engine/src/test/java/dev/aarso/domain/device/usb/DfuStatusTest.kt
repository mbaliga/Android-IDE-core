package dev.aarso.domain.device.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DfuStatusTest {

    @Test
    fun `a GetStatus response round-trips exactly through encode then decode`() {
        val original = DfuStatus.GetStatusResponse(
            status = DfuStatus.Status.OK, pollTimeoutMillis = 5000, state = DfuStatus.State.DFU_DNLOAD_IDLE, stringIndex = 0,
        )
        val decoded = DfuStatus.decodeGetStatus(DfuStatus.encodeGetStatus(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `pollTimeoutMillis correctly round-trips a full 3-byte (24-bit) value, not truncated to one byte`() {
        val original = DfuStatus.GetStatusResponse(DfuStatus.Status.OK, pollTimeoutMillis = 0xABCDEF, state = DfuStatus.State.DFU_DNBUSY, stringIndex = 0)
        val decoded = DfuStatus.decodeGetStatus(DfuStatus.encodeGetStatus(original))
        assertEquals(0xABCDEF, decoded.pollTimeoutMillis)
    }

    @Test
    fun `decodeGetStatus rejects anything other than exactly 6 bytes`() {
        try {
            DfuStatus.decodeGetStatus(ByteArray(5))
            org.junit.Assert.fail("expected MalformedResponseException")
        } catch (expected: DfuStatus.MalformedResponseException) {
            // expected
        }
    }

    @Test
    fun `an error status decodes to the correct named Status, not just OK`() {
        val response = DfuStatus.GetStatusResponse(DfuStatus.Status.ERR_VERIFY, pollTimeoutMillis = 0, state = DfuStatus.State.DFU_ERROR, stringIndex = 0)
        val decoded = DfuStatus.decodeGetStatus(DfuStatus.encodeGetStatus(response))
        assertEquals(DfuStatus.Status.ERR_VERIFY, decoded.status)
        assertEquals(DfuStatus.State.DFU_ERROR, decoded.state)
    }

    @Test
    fun `the download-path happy sequence -- IDLE through DNLOAD_SYNC and DNBUSY to MANIFEST_WAIT_RESET`() {
        var state = DfuStatus.State.DFU_IDLE
        state = advanced(state, DfuStatus.Event.DnloadWithData)
        assertEquals(DfuStatus.State.DFU_DNLOAD_SYNC, state)
        state = advanced(state, DfuStatus.Event.GetStatusStillBusy)
        assertEquals(DfuStatus.State.DFU_DNBUSY, state)
        state = advanced(state, DfuStatus.Event.GetStatusPollComplete)
        assertEquals(DfuStatus.State.DFU_DNLOAD_IDLE, state)
        state = advanced(state, DfuStatus.Event.DnloadZeroLengthManifestTrigger)
        assertEquals(DfuStatus.State.DFU_MANIFEST_SYNC, state)
        state = advanced(state, DfuStatus.Event.GetStatusPollComplete)
        assertEquals(DfuStatus.State.DFU_MANIFEST_WAIT_RESET, state)
    }

    @Test
    fun `multiple DNLOAD chunks loop DNLOAD_IDLE back through DNLOAD_SYNC before the final zero-length manifest trigger`() {
        var state = DfuStatus.State.DFU_DNLOAD_IDLE
        state = advanced(state, DfuStatus.Event.DnloadWithData) // second chunk
        assertEquals(DfuStatus.State.DFU_DNLOAD_SYNC, state)
    }

    @Test
    fun `DFU_ERROR only accepts ClrStatus, returning to DFU_IDLE`() {
        val result = DfuStatus.apply(DfuStatus.State.DFU_ERROR, DfuStatus.Event.ClrStatus)
        assertTrue(result is DfuStatus.Result.Advanced)
        assertEquals(DfuStatus.State.DFU_IDLE, (result as DfuStatus.Result.Advanced).state)
        assertTrue(DfuStatus.apply(DfuStatus.State.DFU_ERROR, DfuStatus.Event.DnloadWithData) is DfuStatus.Result.Rejected)
    }

    @Test
    fun `states outside the download-path subset -- APP_IDLE, APP_DETACH, DFU_UPLOAD_IDLE -- accept no event here`() {
        assertTrue(DfuStatus.apply(DfuStatus.State.APP_IDLE, DfuStatus.Event.DnloadWithData) is DfuStatus.Result.Rejected)
        assertTrue(DfuStatus.apply(DfuStatus.State.DFU_UPLOAD_IDLE, DfuStatus.Event.ManifestComplete) is DfuStatus.Result.Rejected)
    }

    private fun advanced(state: DfuStatus.State, event: DfuStatus.Event): DfuStatus.State {
        val result = DfuStatus.apply(state, event)
        assertTrue("expected Advanced for $event from $state, got $result", result is DfuStatus.Result.Advanced)
        return (result as DfuStatus.Result.Advanced).state
    }
}
