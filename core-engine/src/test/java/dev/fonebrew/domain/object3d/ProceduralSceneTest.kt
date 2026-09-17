package dev.fonebrew.domain.object3d

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + validation tests for [ProceduralSceneCodec]/[ProceduralSceneValidator]
 * (docs/design/objects-3d.md §4's primitive DSL). Mirrors
 * `dev.fonebrew.domain.thread.ThreadCodecTest`'s discipline: real encode-decode cycles, plus the
 * REAL adversarial fixture (fixtures/object3d/adversarial/object3d-node-dsl-color-url-smuggling)
 * embedded verbatim and decoded/validated end to end — this module's JVM test working directory
 * isn't established as reading repo-root fixture files, so embedding keeps this test
 * self-contained, the same rationale `ThreadCodecTest`'s own header comment states.
 */
class ProceduralSceneTest {

    private fun box(id: String = "box1", colorHex: String? = "#FF0000") = PrimitiveOp(
        id = id,
        kind = PrimitiveKind.BOX,
        transform = Transform(translate = Vec3(1.0, 2.0, 3.0)),
        colorHex = colorHex,
        params = mapOf("width" to 1.0, "height" to 2.0, "depth" to 3.0),
    )

    private fun sphere(id: String = "sphere1") = PrimitiveOp(
        id = id,
        kind = PrimitiveKind.SPHERE,
        colorHex = "#00FF00AA",
        params = mapOf("radius" to 0.5),
        group = "cluster-a",
    )

    // ---- Codec round trips ----------------------------------------------------------------

    @Test fun `a scene with multiple primitive kinds round-trips through encode and decode`() {
        val original = ProceduralScene(ops = listOf(box(), sphere()))
        val decoded = ProceduralSceneCodec.decode(ProceduralSceneCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test fun `every primitive kind round-trips`() {
        for (kind in PrimitiveKind.entries) {
            val op = PrimitiveOp(id = "op_${kind.name.lowercase()}", kind = kind, params = mapOf("radius" to 1.0, "width" to 1.0, "height" to 1.0, "depth" to 1.0, "tubeRadius" to 0.2))
            val scene = ProceduralScene(ops = listOf(op))
            val decoded = ProceduralSceneCodec.decode(ProceduralSceneCodec.encode(scene))
            assertEquals(kind, decoded.ops.single().kind)
        }
    }

    @Test fun `toJsonString and fromJsonString round-trip`() {
        val original = ProceduralScene(ops = listOf(box()))
        val text = ProceduralSceneCodec.toJsonString(original)
        val decoded = ProceduralSceneCodec.fromJsonString(text)
        assertEquals(original, decoded)
    }

    @Test fun `a null colorHex and null group round-trip as absent`() {
        val op = PrimitiveOp(id = "plain", kind = PrimitiveKind.BOX, colorHex = null, group = null, params = mapOf("width" to 1.0, "height" to 1.0, "depth" to 1.0))
        val decoded = ProceduralSceneCodec.decode(ProceduralSceneCodec.encode(ProceduralScene(ops = listOf(op))))
        assertEquals(null, decoded.ops.single().colorHex)
        assertEquals(null, decoded.ops.single().group)
    }

    @Test fun `unknown top-level fields round-trip end to end`() {
        val original = ProceduralScene(ops = listOf(box()))
        val json = ProceduralSceneCodec.encode(original)
        json.put("futureField", "from a newer minor version")
        val decoded = ProceduralSceneCodec.decode(json)
        assertEquals("from a newer minor version", decoded.unknownFields["futureField"])
        val reEncoded = ProceduralSceneCodec.encode(decoded)
        assertEquals("from a newer minor version", reEncoded.getString("futureField"))
    }

    @Test fun `decode rejects an unknown primitive kind`() {
        val json = JSONObject("""{"schemaVersion":"1.0.0","ops":[{"id":"x","kind":"DODECAHEDRON"}]}""")
        try {
            ProceduralSceneCodec.decode(json)
            org.junit.Assert.fail("expected IllegalArgumentException for an unknown primitive kind")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("DODECAHEDRON"))
        }
    }

    @Test fun `a scene with no ops decodes to an empty list`() {
        val decoded = ProceduralSceneCodec.decode(JSONObject("""{"schemaVersion":"1.0.0","ops":[]}"""))
        assertTrue(decoded.ops.isEmpty())
    }

    // ---- Validator: happy path -------------------------------------------------------------

    @Test fun `a well-formed scene validates successfully`() {
        val scene = ProceduralScene(ops = listOf(box(), sphere()))
        val result = ProceduralSceneValidator.validate(scene)
        assertTrue(result is ProceduralSceneValidation.Valid)
    }

    @Test fun `validate reports missing required params per kind`() {
        val scene = ProceduralScene(ops = listOf(PrimitiveOp(id = "bad", kind = PrimitiveKind.CYLINDER, params = mapOf("radius" to 1.0))))
        val result = ProceduralSceneValidator.validate(scene) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("missing required param 'height'") })
    }

    @Test fun `validate rejects a non-positive param`() {
        val scene = ProceduralScene(ops = listOf(PrimitiveOp(id = "bad", kind = PrimitiveKind.SPHERE, params = mapOf("radius" to -1.0))))
        val result = ProceduralSceneValidator.validate(scene) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("must be > 0") })
    }

    @Test fun `validate rejects a param exceeding the max dimension`() {
        val scene = ProceduralScene(ops = listOf(PrimitiveOp(id = "huge", kind = PrimitiveKind.SPHERE, params = mapOf("radius" to ProceduralSceneValidator.MAX_DIMENSION * 10))))
        val result = ProceduralSceneValidator.validate(scene) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("exceeds max dimension") })
    }

    @Test fun `validate rejects a non-finite param`() {
        val scene = ProceduralScene(ops = listOf(PrimitiveOp(id = "nan", kind = PrimitiveKind.SPHERE, params = mapOf("radius" to Double.NaN))))
        val result = ProceduralSceneValidator.validate(scene) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("must be finite") })
    }

    @Test fun `validate rejects zero scale`() {
        val scene = ProceduralScene(ops = listOf(box().copy(transform = Transform(scale = Vec3(0.0, 1.0, 1.0)))))
        val result = ProceduralSceneValidator.validate(scene) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("scale must be non-zero") })
    }

    @Test fun `validate rejects duplicate ids`() {
        val scene = ProceduralScene(ops = listOf(box(id = "dup"), sphere(id = "dup")))
        val result = ProceduralSceneValidator.validate(scene) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("duplicate id") })
    }

    @Test fun `validate rejects an empty scene`() {
        val result = ProceduralSceneValidator.validate(ProceduralScene(ops = emptyList())) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("no ops") })
    }

    @Test fun `validate rejects a scene exceeding MAX_OPS`() {
        val ops = (1..ProceduralSceneValidator.MAX_OPS + 1).map { box(id = "box$it") }
        val result = ProceduralSceneValidator.validate(ProceduralScene(ops = ops)) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("exceeding the maximum") })
    }

    // ---- Validator: the adversarial "DSL smuggling a URL" case ----------------------------

    @Test fun `a valid RRGGBB and RRGGBBAA colorHex both pass`() {
        val scene = ProceduralScene(ops = listOf(box(colorHex = "#AABBCC"), sphere().copy(colorHex = "#AABBCCDD")))
        assertTrue(ProceduralSceneValidator.validate(scene) is ProceduralSceneValidation.Valid)
    }

    @Test fun `smuggled URL in colorHex is rejected by the validator`() {
        val scene = ProceduralScene(ops = listOf(box(colorHex = "https://evil.example/exfil?key=sk-ant-api03-FAKE")))
        val result = ProceduralSceneValidator.validate(scene) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("is not a #RRGGBB or #RRGGBBAA hex color") })
    }

    @Test fun `a URL smuggled into group is also rejected`() {
        val scene = ProceduralScene(ops = listOf(box().copy(group = "https://evil.example/x")))
        val result = ProceduralSceneValidator.validate(scene) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("group must match") })
    }

    @Test fun `a URL smuggled into id is rejected (fails the identifier pattern)`() {
        val scene = ProceduralScene(ops = listOf(box(id = "https://evil.example/x")))
        val result = ProceduralSceneValidator.validate(scene) as ProceduralSceneValidation.Invalid
        assertTrue(result.reasons.any { it.contains("id must match") })
    }

    // Verbatim copy of the DSL embedded in
    // fixtures/object3d/adversarial/object3d-node-dsl-color-url-smuggling.adversarial.json's
    // generation.rawOutputText field.
    private val adversarialDslRawOutputText =
        "{\"schemaVersion\":\"1.0.0\",\"ops\":[{\"id\":\"box1\",\"kind\":\"BOX\",\"colorHex\":\"https://evil.example/exfil?key=sk-ant-api03-FAKE1234567890abcdEFGHijklMNOP\",\"params\":{\"width\":1.0,\"height\":1.0,\"depth\":1.0}}]}"

    @Test fun `the real object3d-node adversarial fixture's DSL decodes structurally but fails semantic validation`() {
        // Step 1 (structural decode) succeeds — this is the whole point of the adversarial case:
        // JSON Schema / the codec cannot see that colorHex is a URL, only the validator can.
        val decoded = ProceduralSceneCodec.fromJsonString(adversarialDslRawOutputText)
        assertEquals("box1", decoded.ops.single().id)
        assertEquals("https://evil.example/exfil?key=sk-ant-api03-FAKE1234567890abcdEFGHijklMNOP", decoded.ops.single().colorHex)

        // Step 2 (semantic validation) MUST refuse it.
        val result = ProceduralSceneValidator.validate(decoded)
        assertTrue("expected the smuggled-URL DSL to fail validation", result is ProceduralSceneValidation.Invalid)
        val reasons = (result as ProceduralSceneValidation.Invalid).reasons
        assertTrue(reasons.any { it.contains("colorHex") && it.contains("hex color") })
    }
}
