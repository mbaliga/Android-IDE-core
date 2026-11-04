// LICENSE-PENDING — see docs/non_ratified/LICENSE_PENDING.md
package dev.fonebrew.domain.object3d

import java.nio.charset.StandardCharsets

/**
 * docs/design/objects-3d.md §2's format matrix, verbatim — the ten mesh/scene container formats
 * the viewer (`ui/object3d/ObjectViewerRoom.kt`, out of scope for this pure-domain package) must
 * load. Every entry here has a corresponding row in [schemas/object3d/object3d-node.schema.json]'s
 * `Object3dFormat` enum def — the two MUST stay in lockstep, mirroring the discipline every other
 * `dev.fonebrew.contracts.common`/`schemas/common` pair in this corpus already keeps.
 *
 * `THREE_MF` names the "3MF" format — a Kotlin enum entry cannot start with a digit.
 */
enum class Object3dFormat(
    /** Human label for UI surfaces. */
    val label: String,
    /** Lower-case extensions (without the dot) this format is conventionally saved under. */
    val extensions: List<String>,
    /** Best-effort IANA-shaped media type, for ArtifactRef.mediaType-style fields. */
    val mediaType: String,
) {
    GLB("glTF Binary", listOf("glb"), "model/gltf-binary"),
    GLTF("glTF", listOf("gltf"), "model/gltf+json"),
    OBJ("Wavefront OBJ", listOf("obj"), "model/obj"),
    STL_ASCII("STL (ASCII)", listOf("stl"), "model/stl"),
    STL_BINARY("STL (binary)", listOf("stl"), "model/stl"),
    PLY("Polygon File Format", listOf("ply"), "model/ply"),
    FBX("Filmbox", listOf("fbx"), "application/octet-stream"),
    DAE("Collada", listOf("dae"), "model/vnd.collada+xml"),
    THREE_MF("3D Manufacturing Format", listOf("3mf"), "model/3mf"),
    USDZ("Universal Scene Description (zipped)", listOf("usdz"), "model/vnd.usdz+zip"),
    ;

    /** §2: USDZ is explicitly "best-effort" — three.js support is partial. Every other format is
     *  expected to render with full fidelity through its dedicated loader. */
    val isBestEffortOnly: Boolean get() = this == USDZ
}

/**
 * Sniffs [Object3dFormat] from a file's leading bytes and/or its file name extension (§2:
 * "Format sniffed from magic bytes + extension"). Pure, no I/O — callers pass in already-read
 * bytes (a small prefix is enough; see [MIN_SNIFF_BYTES]) and/or a file name.
 *
 * Magic-byte checks run first and win over the extension whenever they can positively identify a
 * format, because an imported file's extension (§1's SAF-picker "3D file..." path) is
 * user/exporter-supplied and not to be trusted over the bytes themselves. The extension is the
 * fallback for formats this sniffer cannot reliably distinguish from bytes alone (plain OBJ text,
 * and — when the STL magic-byte heuristic is inconclusive — a default of [Object3dFormat.STL_BINARY]).
 */
object Object3dFormatSniffer {

    /** Smallest prefix a caller should read before calling [sniff] for every format to have a
     *  chance at a magic-byte match. Callers MAY pass fewer bytes; matches that need more than
     *  they were given simply fall through to the extension check. */
    const val MIN_SNIFF_BYTES = 132

    private val GLB_MAGIC = byteArrayOf('g'.code.toByte(), 'l'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte())
    private val PLY_MAGIC = byteArrayOf('p'.code.toByte(), 'l'.code.toByte(), 'y'.code.toByte())
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val FBX_BINARY_MAGIC = "Kaydara FBX Binary".toByteArray(StandardCharsets.US_ASCII)

    fun sniff(bytes: ByteArray, fileName: String? = null): Object3dFormat? {
        sniffMagicBytes(bytes)?.let { return it }
        sniffTextHeuristics(bytes)?.let { return it }
        val ext = fileNameExtension(fileName)
        if (ext == "stl") {
            // Bytes were available (we got this far) but neither the ZIP/PLY/GLB/FBX magic-byte
            // checks nor the ASCII-STL text heuristic matched — the honest default for an
            // unresolved .stl with real bytes in hand is binary (the far more common case in
            // practice; ASCII STL is the minority format), never a silent guess of ASCII.
            return Object3dFormat.STL_BINARY
        }
        return sniffExtension(fileName)
    }

    /** Extension-only sniff (no bytes available yet — e.g. before a SAF stream is opened).
     *  `.stl` is deliberately unresolved here (returns null) — ASCII vs binary cannot be told
     *  from the extension alone; a caller with bytes in hand should call [sniff] instead. */
    fun sniffExtension(fileName: String?): Object3dFormat? {
        val ext = fileNameExtension(fileName) ?: return null
        if (ext == "stl") return null
        return Object3dFormat.entries.firstOrNull { ext in it.extensions }
    }

    private fun fileNameExtension(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return null
        return fileName.substring(dot + 1).lowercase()
    }

    /**
     * Magic-byte-only sniff. Returns null (rather than guessing) when the bytes are too short or
     * do not match a known signature — callers fall back to [sniffExtension] in [sniff].
     */
    fun sniffMagicBytes(bytes: ByteArray): Object3dFormat? {
        if (bytes.size >= 4 && bytes.startsWith(GLB_MAGIC)) return Object3dFormat.GLB
        if (bytes.size >= 3 && bytes.startsWith(PLY_MAGIC)) return Object3dFormat.PLY
        if (bytes.size >= FBX_BINARY_MAGIC.size && bytes.startsWith(FBX_BINARY_MAGIC)) return Object3dFormat.FBX
        if (bytes.size >= 84 && bytes.startsWith(ZIP_MAGIC)) {
            // 3MF and USDZ are both ZIP/OPC containers (§2) — distinguished by the entry names a
            // ZIP local-file-header records inline. Searching the raw bytes for the ASCII entry
            // name is a cheap, allocation-free way to tell them apart without a real unzip.
            val text = safeAsciiWindow(bytes)
            if (text.contains("3D/3dmodel.model")) return Object3dFormat.THREE_MF
            if (text.contains(".usdc") || text.contains(".usda")) return Object3dFormat.USDZ
        }
        return null
    }

    /**
     * Binary STL has no magic string at all — its only structural signature is that byte offset
     * 80 holds a little-endian uint32 triangle count N, and (when true) the file is exactly
     * `84 + 50*N` bytes long (an 80-byte header + 4-byte count + N 50-byte triangle records).
     * Some binary STL files nonetheless start with the ASCII text "solid" (a documented
     * interop trap — §2 STL row), which is why [sniffMagicBytes]/[sniff] never try to resolve
     * STL from a length-less byte prefix at all (see their doc comments) — this is the real
     * binary-STL confirmation, usable once a caller knows the file's total byte length
     * (unlike [sniffMagicBytes], which only ever sees a prefix). Returns true when the header's
     * declared triangle count exactly accounts for the rest of the file.
     */
    fun looksLikeBinaryStl(headerAndCount: ByteArray, totalFileLength: Long): Boolean {
        if (headerAndCount.size < 84) return false
        val triangleCount =
            (headerAndCount[80].toLong() and 0xFF) or
                ((headerAndCount[81].toLong() and 0xFF) shl 8) or
                ((headerAndCount[82].toLong() and 0xFF) shl 16) or
                ((headerAndCount[83].toLong() and 0xFF) shl 24)
        if (triangleCount < 0) return false
        return 84L + 50L * triangleCount == totalFileLength
    }

    /**
     * Text-content heuristics for formats with no reliable magic-byte signature: ASCII STL, DAE
     * (XML/Collada), glTF-as-JSON, and OBJ. Tried in this order because DAE/glTF/STL-ascii have
     * fairly specific token requirements while OBJ's tokens (`v `, `f `) are the loosest and would
     * otherwise false-positive on other whitespace-separated text formats.
     */
    private fun sniffTextHeuristics(bytes: ByteArray): Object3dFormat? {
        val text = safeAsciiWindow(bytes).trimStart()
        if (text.isEmpty()) return null
        if (text.startsWith("solid") && (text.contains("facet normal") || text.contains("endsolid"))) {
            return Object3dFormat.STL_ASCII
        }
        if (text.startsWith("<?xml") && text.contains("COLLADA", ignoreCase = true)) {
            return Object3dFormat.DAE
        }
        if (text.startsWith("{") && text.contains("\"asset\"") && (text.contains("\"scenes\"") || text.contains("\"meshes\""))) {
            return Object3dFormat.GLTF
        }
        val lines = text.lineSequence().take(64)
        val objTokenHits = lines.count { line ->
            val t = line.trimStart()
            t.startsWith("v ") || t.startsWith("vn ") || t.startsWith("vt ") || t.startsWith("f ") ||
                t.startsWith("o ") || t.startsWith("g ") || t.startsWith("mtllib ") || t.startsWith("usemtl ")
        }
        if (objTokenHits >= 2) return Object3dFormat.OBJ
        return null
    }

    /** Decodes up to [MIN_SNIFF_BYTES] as US-ASCII for substring probing — never throws on
     *  non-text/binary content (US-ASCII decoding is total: any byte >= 0x80 just becomes '?'). */
    private fun safeAsciiWindow(bytes: ByteArray): String {
        val window = if (bytes.size > MIN_SNIFF_BYTES) bytes.copyOf(MIN_SNIFF_BYTES) else bytes
        return String(window, StandardCharsets.US_ASCII)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
