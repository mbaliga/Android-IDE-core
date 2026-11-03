// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.object3d

/**
 * docs/design/objects-3d.md §4's "Raw OBJ (accepted)" validator — "for models that prefer
 * emitting mesh text directly." Pure text parsing, no Android, no I/O: the caller already has
 * the fenced ```object3d``` block's text in memory (an LLM completion is bounded by the model's
 * own max-output-tokens, and an imported/SAF file's bytes are read and size-capped by the caller
 * per §3's 32 MB viewer limit before this parser ever sees them) — this file's job is not to be a
 * streaming-safe parser for an unbounded network source, it is to catch a maliciously- or
 * accidentally-crafted OBJ whose HEADER or CONTENT claims a mesh far larger than anything this
 * corpus's on-device path should ever produce, and to refuse it before any buffer sized off that
 * claim gets allocated downstream.
 *
 * This is exactly §6's adversarial minimum: "an OBJ with a 10^9-vertex header" — see
 * fixtures/object3d/adversarial/object3d-node-obj-absurd-vertex-header.adversarial.json.
 */
object ObjParser {

    /** §4: on-device generation targets small, primitive-shaped meshes — a real accepted OBJ from
     *  this path is expected to be a few hundred to a few thousand vertices at most. 200k is a
     *  generous ceiling that still refuses anything resembling a scanned/CAD-scale mesh, which
     *  the on-device chat-completion path was never meant to produce. */
    const val MAX_VERTICES = 200_000

    /** Same rationale as [MAX_VERTICES], for face ('f') lines. */
    const val MAX_FACES = 200_000

    /** Hard stop on total lines scanned, independent of the v/f counters above — defense in depth
     *  against a file that is mostly comments/blank lines and would otherwise never trip the
     *  vertex/face caps while still being enormous. */
    const val MAX_LINES_SCANNED = 2_000_000

    private val VERTEX_COUNT_HINT = Regex("(?i)vert\\w*\\D{0,20}(\\d+)")
    private val FACE_COUNT_HINT = Regex("(?i)face\\w*\\D{0,20}(\\d+)")
    private val WHITESPACE = Regex("\\s+")

    data class ObjStats(
        val vertexCount: Int,
        val faceCount: Int,
        val normalCount: Int,
        val texCoordCount: Int,
    )

    sealed class ObjValidation {
        data class Valid(val stats: ObjStats) : ObjValidation()
        data class Invalid(val reasons: List<String>) : ObjValidation()
    }

    fun validate(text: String): ObjValidation {
        if (text.isBlank()) return ObjValidation.Invalid(listOf("OBJ text is blank"))

        checkDeclaredHeaderCounts(text)?.let { return it }

        return parseAndCountBounded(text)
    }

    /**
     * Pass 1 — scans only the LEADING run of comment (`#`)/blank lines (the conventional header
     * block) for a declared vertex/face count and refuses immediately, before any per-line
     * geometry parsing, if that declared count alone already exceeds [MAX_VERTICES]/[MAX_FACES].
     * This is the direct defense against §6's "10^9-vertex header" case: the header is read and
     * rejected without the parser ever attempting to size anything off of it.
     */
    private fun checkDeclaredHeaderCounts(text: String): ObjValidation.Invalid? {
        var declaredVertices: Long? = null
        var declaredFaces: Long? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("#")) break // header block ends at the first non-comment content
            VERTEX_COUNT_HINT.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let {
                declaredVertices = maxOf(declaredVertices ?: 0L, it)
            }
            FACE_COUNT_HINT.find(line)?.groupValues?.get(1)?.toLongOrNull()?.let {
                declaredFaces = maxOf(declaredFaces ?: 0L, it)
            }
        }
        val reasons = mutableListOf<String>()
        declaredVertices?.let {
            if (it > MAX_VERTICES) reasons += "header comment declares $it vertices, exceeding the maximum of $MAX_VERTICES — refused before parsing geometry"
        }
        declaredFaces?.let {
            if (it > MAX_FACES) reasons += "header comment declares $it faces, exceeding the maximum of $MAX_FACES — refused before parsing geometry"
        }
        return if (reasons.isEmpty()) null else ObjValidation.Invalid(reasons)
    }

    /**
     * Pass 2 — the real line-by-line parse. Aborts the moment ACTUAL content (not just a
     * declared header) exceeds the vertex/face/line caps too, so a file with no declaring header
     * at all (or one that under-declares) cannot get around pass 1.
     */
    private fun parseAndCountBounded(text: String): ObjValidation {
        val reasons = mutableListOf<String>()
        var vertexCount = 0
        var faceCount = 0
        var normalCount = 0
        var texCoordCount = 0
        var lineNo = 0

        for (rawLine in text.lineSequence()) {
            lineNo++
            if (lineNo > MAX_LINES_SCANNED) {
                return ObjValidation.Invalid(listOf("OBJ text exceeds $MAX_LINES_SCANNED lines — refused"))
            }
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val tokens = line.split(WHITESPACE)
            when (tokens[0]) {
                "v" -> {
                    vertexCount++
                    if (vertexCount > MAX_VERTICES) {
                        return ObjValidation.Invalid(listOf("OBJ text has more than $MAX_VERTICES actual 'v' lines — refused"))
                    }
                    if (tokens.size < 4) reasons += "line $lineNo: 'v' needs at least x y z"
                    else validateNumericTokens(tokens.subList(1, 4), lineNo, "v")?.let { reasons += it }
                }
                "vn" -> {
                    normalCount++
                    if (tokens.size < 4) reasons += "line $lineNo: 'vn' needs at least x y z"
                }
                "vt" -> {
                    texCoordCount++
                    if (tokens.size < 2) reasons += "line $lineNo: 'vt' needs at least u"
                }
                "f" -> {
                    faceCount++
                    if (faceCount > MAX_FACES) {
                        return ObjValidation.Invalid(listOf("OBJ text has more than $MAX_FACES actual 'f' lines — refused"))
                    }
                    if (tokens.size < 4) {
                        reasons += "line $lineNo: 'f' needs at least 3 vertex references (a triangle)"
                    } else {
                        for (ref in tokens.subList(1, tokens.size)) {
                            validateFaceVertexRef(ref, lineNo, vertexCount)?.let { reasons += it }
                        }
                    }
                }
                "o", "g", "usemtl", "mtllib", "s" -> { /* recognized structural/material directives, not validated further here */ }
                else -> reasons += "line $lineNo: unrecognized directive '${tokens[0]}'"
            }
        }

        if (vertexCount == 0) reasons += "OBJ text declares no vertices"
        return if (reasons.isEmpty()) {
            ObjValidation.Valid(ObjStats(vertexCount, faceCount, normalCount, texCoordCount))
        } else {
            ObjValidation.Invalid(reasons)
        }
    }

    /** A face vertex reference is `v`, `v/vt`, `v//vn`, or `v/vt/vn`; only the `v` (vertex index)
     *  part is validated here. Negative indices are relative-to-current per the OBJ spec (§4 —
     *  "-1" means the most recently emitted vertex), resolved against [vertexCountSoFar]. */
    private fun validateFaceVertexRef(ref: String, lineNo: Int, vertexCountSoFar: Int): String? {
        val idxToken = ref.substringBefore('/')
        val idx = idxToken.toIntOrNull() ?: return "line $lineNo: face reference '$ref' has a non-integer vertex index"
        val resolved = if (idx < 0) vertexCountSoFar + idx + 1 else idx
        if (resolved < 1 || resolved > vertexCountSoFar) {
            return "line $lineNo: face references vertex index $idx, out of range for $vertexCountSoFar vertices seen so far"
        }
        return null
    }

    private fun validateNumericTokens(tokens: List<String>, lineNo: Int, directive: String): String? {
        for (t in tokens) {
            val d = t.toDoubleOrNull()
            if (d == null || !d.isFinite()) {
                return "line $lineNo: '$directive' has a non-numeric or non-finite coordinate '$t'"
            }
        }
        return null
    }
}
