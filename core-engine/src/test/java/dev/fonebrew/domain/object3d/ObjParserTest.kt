package dev.fonebrew.domain.object3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [ObjParser] (docs/design/objects-3d.md §4's "Raw OBJ (accepted)" validator),
 * including the REAL adversarial fixture
 * (fixtures/object3d/adversarial/object3d-node-obj-absurd-vertex-header) embedded verbatim, same
 * "embed the real fixture text" rationale `dev.fonebrew.domain.thread.ThreadCodecTest` documents.
 */
class ObjParserTest {

    private val validTriangle = """
        # a single triangle
        v 0.0 0.0 0.0
        v 1.0 0.0 0.0
        v 0.0 1.0 0.0
        f 1 2 3
    """.trimIndent()

    // ---- Happy path -----------------------------------------------------------------------

    @Test fun `a well-formed triangle validates with the correct stats`() {
        val result = ObjParser.validate(validTriangle) as ObjParser.ObjValidation.Valid
        assertEquals(3, result.stats.vertexCount)
        assertEquals(1, result.stats.faceCount)
        assertEquals(0, result.stats.normalCount)
        assertEquals(0, result.stats.texCoordCount)
    }

    @Test fun `normals and texture coordinates are counted`() {
        val text = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            vn 0 0 1
            vn 0 0 1
            vt 0 0
            vt 1 0
            f 1/1/1 2/2/1 3/1/1
        """.trimIndent()
        val result = ObjParser.validate(text) as ObjParser.ObjValidation.Valid
        assertEquals(3, result.stats.vertexCount)
        assertEquals(2, result.stats.normalCount)
        assertEquals(2, result.stats.texCoordCount)
        assertEquals(1, result.stats.faceCount)
    }

    @Test fun `negative relative face indices resolve against the vertex count so far`() {
        val text = """
            v 0 0 0
            v 1 0 0
            v 0 1 0
            f -3 -2 -1
        """.trimIndent()
        val result = ObjParser.validate(text)
        assertTrue("expected relative indices -3 -2 -1 to resolve to vertices 1 2 3, got $result", result is ObjParser.ObjValidation.Valid)
    }

    @Test fun `recognized structural directives are ignored without affecting validity`() {
        val text = """
            o Cube
            g default
            mtllib cube.mtl
            usemtl Material
            s 1
            v 0 0 0
            v 1 0 0
            v 0 1 0
            f 1 2 3
        """.trimIndent()
        assertTrue(ObjParser.validate(text) is ObjParser.ObjValidation.Valid)
    }

    // ---- Structural rejections --------------------------------------------------------------

    @Test fun `blank text is rejected`() {
        val result = ObjParser.validate("   \n  ") as ObjParser.ObjValidation.Invalid
        assertTrue(result.reasons.any { it.contains("blank") })
    }

    @Test fun `text with no vertices is rejected`() {
        val result = ObjParser.validate("# just a comment\n") as ObjParser.ObjValidation.Invalid
        assertTrue(result.reasons.any { it.contains("no vertices") })
    }

    @Test fun `a face referencing an out-of-range vertex index is rejected`() {
        val text = """
            v 0 0 0
            v 1 0 0
            f 1 2 5
        """.trimIndent()
        val result = ObjParser.validate(text) as ObjParser.ObjValidation.Invalid
        assertTrue(result.reasons.any { it.contains("out of range") })
    }

    @Test fun `a v line with too few coordinates is rejected`() {
        val text = "v 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"
        val result = ObjParser.validate(text) as ObjParser.ObjValidation.Invalid
        assertTrue(result.reasons.any { it.contains("'v' needs at least") })
    }

    @Test fun `a non-numeric coordinate is rejected`() {
        val text = "v 0 0 notanumber\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"
        val result = ObjParser.validate(text) as ObjParser.ObjValidation.Invalid
        assertTrue(result.reasons.any { it.contains("non-numeric") })
    }

    @Test fun `an unrecognized directive is rejected`() {
        val text = "v 0 0 0\nv 1 0 0\nv 0 1 0\nzork 1 2 3\nf 1 2 3\n"
        val result = ObjParser.validate(text) as ObjParser.ObjValidation.Invalid
        assertTrue(result.reasons.any { it.contains("unrecognized directive 'zork'") })
    }

    // ---- The absurd-size-header defense (§6's second adversarial minimum) -------------------

    @Test fun `a header declaring more vertices than MAX_VERTICES is refused before any geometry parsing`() {
        val text = "# vertices: ${ObjParser.MAX_VERTICES + 1}\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"
        val result = ObjParser.validate(text) as ObjParser.ObjValidation.Invalid
        assertTrue(result.reasons.any { it.contains("exceeding the maximum of ${ObjParser.MAX_VERTICES}") })
    }

    @Test fun `a header declaring more faces than MAX_FACES is refused before any geometry parsing`() {
        val text = "# faces: ${ObjParser.MAX_FACES + 1}\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"
        val result = ObjParser.validate(text) as ObjParser.ObjValidation.Invalid
        assertTrue(result.reasons.any { it.contains("exceeding the maximum of ${ObjParser.MAX_FACES}") })
    }

    @Test fun `actual content exceeding MAX_VERTICES is refused even with no declaring header`() {
        val sb = StringBuilder()
        repeat(ObjParser.MAX_VERTICES + 10) { sb.append("v 0 0 0\n") }
        val result = ObjParser.validate(sb.toString()) as ObjParser.ObjValidation.Invalid
        assertTrue(result.reasons.any { it.contains("more than ${ObjParser.MAX_VERTICES} actual 'v' lines") })
    }

    // Verbatim copy of the OBJ text embedded in
    // fixtures/object3d/adversarial/object3d-node-obj-absurd-vertex-header.adversarial.json's
    // generation.rawOutputText field.
    private val adversarialObjRawOutputText =
        "# aarso-object3d generated mesh\n# vertices: 2000000000\n# faces: 1000000000\nv 0.0 0.0 0.0\nv 1.0 0.0 0.0\nv 0.0 1.0 0.0\nf 1 2 3\n"

    @Test fun `the real object3d-node adversarial fixture's OBJ text is refused before parsing its tiny real content`() {
        val result = ObjParser.validate(adversarialObjRawOutputText)
        assertTrue("expected the absurd-header OBJ to be refused", result is ObjParser.ObjValidation.Invalid)
        val reasons = (result as ObjParser.ObjValidation.Invalid).reasons
        assertTrue(reasons.any { it.contains("2000000000 vertices") && it.contains("refused before parsing geometry") })
        assertTrue(reasons.any { it.contains("1000000000 faces") && it.contains("refused before parsing geometry") })
    }

    @Test fun `header count scanning stops at the first non-comment line`() {
        // A "declared count" appearing only AFTER real geometry has already started must not be
        // picked up by the header-only pass 1 scan (it is not a header at that point).
        val text = "v 0 0 0\n# vertices: 999999999999\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"
        val result = ObjParser.validate(text)
        assertTrue("a mid-file comment must not be treated as the declared header count", result is ObjParser.ObjValidation.Valid)
    }
}
