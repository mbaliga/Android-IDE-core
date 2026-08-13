package dev.aarso.domain.bridge

import dev.aarso.domain.provenance.ProvenanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCodecTest {

    private fun sample(authorModel: String? = "Claude") = SummaryBridge(
        header = "Switched model: A → B",
        carriedForward = listOf("user: goal", "assistant: reply"),
        fullPriorAvailable = true,
        authorModel = authorModel,
        authorProvenance = ProvenanceState.CLOUD,
    )

    @Test
    fun encodeDecode_roundTrips() {
        val bridge = sample()
        val decoded = BridgeCodec.decode(BridgeCodec.encode(bridge))
        assertEquals(bridge, decoded)
    }

    @Test
    fun encodeDecode_roundTrips_nullAuthorModel() {
        val bridge = sample(authorModel = null)
        val decoded = BridgeCodec.decode(BridgeCodec.encode(bridge))
        assertEquals(bridge, decoded)
        assertNull(decoded?.authorModel)
    }

    @Test
    fun encodeDecode_roundTrips_emptyCarriedForward() {
        val bridge = sample().copy(carriedForward = emptyList(), fullPriorAvailable = false)
        val decoded = BridgeCodec.decode(BridgeCodec.encode(bridge))
        assertEquals(bridge, decoded)
    }

    @Test
    fun encode_isValidJsonObject_withExpectedKeys() {
        val json = org.json.JSONObject(BridgeCodec.encode(sample()))
        assertTrue(json.has("header"))
        assertTrue(json.has("carriedForward"))
        assertTrue(json.has("fullPriorAvailable"))
        assertTrue(json.has("authorProvenance"))
    }

    @Test
    fun decode_malformedJson_returnsNullRatherThanThrowing() {
        assertNull(BridgeCodec.decode("not json at all"))
    }

    @Test
    fun decode_missingRequiredField_returnsNull() {
        val json = "{\"carriedForward\":[],\"fullPriorAvailable\":false,\"authorProvenance\":\"LOCAL\"}"
        assertNull(BridgeCodec.decode(json)) // no "header"
    }

    @Test
    fun decode_unknownProvenanceValue_returnsNull() {
        val json = "{\"header\":\"h\",\"carriedForward\":[],\"fullPriorAvailable\":false,\"authorProvenance\":\"NOT_A_STATE\"}"
        assertNull(BridgeCodec.decode(json))
    }

    @Test
    fun encode_everyProvenanceState_roundTrips() {
        ProvenanceState.entries.forEach { provenance ->
            val bridge = sample().copy(authorProvenance = provenance)
            assertEquals(provenance, BridgeCodec.decode(BridgeCodec.encode(bridge))?.authorProvenance)
        }
    }
}
