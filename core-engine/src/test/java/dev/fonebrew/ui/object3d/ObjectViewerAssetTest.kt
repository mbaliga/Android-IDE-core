package dev.fonebrew.ui.object3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * docs/design/objects-3d.md §7 — mirrors [dev.fonebrew.ui.graph.GraphRoomAssetTest]: asserts the
 * committed, build-script-generated
 * `core-engine/src/main/assets/object3d/object-viewer.html` (`scripts/build-object-viewer.js`)
 * still holds the properties `dev.fonebrew.ui.object3d.ObjectViewerRoom`'s WebView lockdown
 * depends on, without a device: CSP present, zero external URLs outside the vendored library's
 * own license-comment header, the vendored three.js bundle's MIT attribution survived, the WebGL
 * self-test hook is present, the `__objectViewer.load` entry point exists, and every format in
 * §2's matrix has a loader wired into both the vendored bundle and this file's own dispatch.
 *
 * This is an *asset* test, not a *build-script* test: it doesn't re-run
 * `scripts/build-object-viewer.js` (Node isn't part of the JVM gate) — it reads the file the
 * script already wrote and committed, the same "trust but verify the committed output"
 * relationship [dev.fonebrew.ui.graph.GraphRoomAssetTest] has to its own asset.
 *
 * **No external-URL assertion runs over the whole file.** The vendored three.js body legitimately
 * contains one inert third-party string literal — the SVG/XHTML XML namespace URI
 * (`http://www.w3.org/1999/xhtml`, required by `document.createElementNS` even for entirely local
 * elements) — which this page never invokes as a network request, and which
 * `blockNetworkLoads`/CSP `connect-src 'none'` make structurally unreachable even if it did
 * ([dev.fonebrew.ui.object3d.ObjectViewerRoom]'s own lockdown, asserted by construction there, not
 * here). A literal whole-file substring scan for "http" would flag this false positive; instead
 * this test scans only the scaffold **this repo authors** (the CSP meta line + everything between
 * the vendored block's own HTML comment markers), where an external resource-loading tag or call
 * would be a real regression — same split [dev.fonebrew.ui.graph.GraphRoomAssetTest] draws.
 */
class ObjectViewerAssetTest {

    private val html: String by lazy { readAsset() }

    /** Finds `object-viewer.html` regardless of the unit-test task's working directory — same
     *  upward-walk fallback as [dev.fonebrew.ui.graph.GraphRoomAssetTest.readAsset]. */
    private fun readAsset(): String {
        val relative = "src/main/assets/object3d/object-viewer.html"
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val direct = File(dir, "core-engine/$relative")
            if (direct.isFile) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        fail(
            "couldn't find core-engine/src/main/assets/object3d/object-viewer.html from working " +
                "directory '${System.getProperty("user.dir")}' — run " +
                "`node scripts/build-object-viewer.js` from the repo root first.",
        )
        error("unreachable")
    }

    private fun vendoredBlock(): String {
        val start = html.indexOf("<!-- BEGIN VENDORED THREE.JS")
        val end = html.indexOf("<!-- END VENDORED THREE.JS")
        assertTrue("vendored three.js block markers not found", start >= 0 && end > start)
        return html.substring(start, end)
    }

    /** Everything OUTSIDE the vendored block — the scaffold this repo actually authors (CSP meta,
     *  canvas/status markup, the bootstrap script). What the no-external-resource assertion below
     *  scans, per the class KDoc's rationale. */
    private fun ownScaffold(): String {
        val start = html.indexOf("<!-- BEGIN VENDORED THREE.JS")
        val end = html.indexOf("<!-- END VENDORED THREE.JS") + "<!-- END VENDORED THREE.JS -->".length
        assertTrue("vendored three.js block markers not found", start >= 0 && end > start)
        return html.substring(0, start) + html.substring(end)
    }

    @Test fun `the asset file exists and is non-trivially large`() {
        assertTrue("object-viewer.html looks too small (${html.length} chars) to hold the vendored three.js bundle", html.length > 800_000)
    }

    @Test fun `a strict Content-Security-Policy meta tag is present`() {
        val cspLine = Regex("""<meta http-equiv="Content-Security-Policy" content="([^"]*)">""")
            .find(html)?.groupValues?.get(1)
        assertTrue("no CSP meta tag found", cspLine != null)
        val csp = cspLine!!
        assertTrue("CSP missing default-src 'none'", csp.contains("default-src 'none'"))
        assertTrue("CSP missing connect-src 'none'", csp.contains("connect-src 'none'"))
        assertTrue("CSP missing frame-src 'none'", csp.contains("frame-src 'none'"))
        assertTrue("CSP missing object-src 'none'", csp.contains("object-src 'none'"))
        assertTrue("CSP missing worker-src 'none'", csp.contains("worker-src 'none'"))
        assertTrue("CSP missing base-uri 'none'", csp.contains("base-uri 'none'"))
        // The one CSP divergence from GraphRoomAssetTest's G6 policy (documented in
        // scripts/build-object-viewer.js): embedded-texture bytes are materialized as local
        // Blob URLs by GLTFLoader/3MFLoader/USDZLoader, never a network fetch.
        assertTrue("CSP img-src must allow blob: for embedded-texture Blob URLs", csp.contains("img-src") && csp.contains("blob:"))
    }

    @Test fun `the __objectViewer load entry point is defined`() {
        assertTrue("window.__objectViewer is never assigned", html.contains("window.__objectViewer = {"))
        assertTrue("__objectViewer has no load method", Regex("""load\s*:\s*function""").containsMatchIn(html))
        assertTrue("no JSON.parse call in the bootstrap — the procedural DSL path must decode the pushed JSON text", html.contains("JSON.parse"))
        // The two `kind` values ObjectViewerRoom.kt's pushCallFor actually sends.
        assertTrue("bootstrap never branches on kind === 'model'", html.contains("kind === 'model'"))
        assertTrue("bootstrap never branches on kind === 'procedural'", html.contains("kind === 'procedural'"))
    }

    @Test fun `a WebGL context-creation self-test runs before any renderer is constructed`() {
        assertTrue("no webglSelfTest function defined", html.contains("function webglSelfTest"))
        assertTrue("self-test never requests a webgl2 context", html.contains("getContext('webgl2'"))
        assertTrue("self-test never falls back to a webgl context", html.contains("getContext('webgl'"))
        assertTrue("no reportSelfTest hook referenced", html.contains("reportSelfTest"))
        // The honest-failure text ObjectViewerRoom.kt's own FailureCard also shows (dual surface,
        // one message) — ties the JS self-test's failure path to the "never render garbage"
        // requirement in docs/design/objects-3d.md §3.
        assertTrue("no 'WebGL unavailable on this device' status text on self-test failure", html.contains("WebGL unavailable on this device"))
        // Ordering: the function definition (and therefore its call inside initViewer, which the
        // WebGLRenderer construction immediately follows in source order) must appear before the
        // renderer is ever constructed — never after, which would mean the self-test wasn't a
        // gate but an afterthought.
        val selfTestIndex = html.indexOf("function webglSelfTest")
        val rendererIndex = html.indexOf("new THREE.WebGLRenderer(")
        assertTrue("WebGLRenderer construction appears before the self-test function is even defined", selfTestIndex in 0 until rendererIndex)
    }

    @Test fun `the vendored three_js bundle's MIT attribution is present`() {
        val block = vendoredBlock()
        assertTrue("MIT License text missing from the vendored block", block.contains("MIT License"))
        assertTrue("three.js copyright line missing from the vendored block", block.contains("three.js authors"))
        assertTrue("pinned three.js version missing from the vendored block", block.contains("0.180.0"))
        // fflate is bundled transitively (GLTFLoader/3MFLoader/USDZLoader's zip/inflate) and is a
        // second MIT library folded into the same file — its own attribution must survive too.
        assertTrue("fflate attribution missing from the vendored block", block.contains("fflate"))
        assertTrue("fflate copyright line missing from the vendored block", block.contains("Arjun Barrett"))
    }

    @Test fun `the AndroidObjectViewer bridge is referenced narrowly`() {
        // The one divergence from GraphRoomAssetTest's own "no bridge at all" assertion (see
        // ObjectViewerRoom.kt's class KDoc): this page DOES call into a native bridge, but only
        // ever these two write-only status-report methods — never anything broader.
        val calledMethods = Regex("""AndroidObjectViewer\.(\w+)""").findAll(html).map { it.groupValues[1] }.toSet()
        assertEquals("bridge is called through more than the two expected narrow methods", setOf("reportSelfTest", "reportLoad"), calledMethods)
        assertTrue("bridge calls are not guarded by a window.AndroidObjectViewer existence check", html.contains("window.AndroidObjectViewer &&"))
    }

    @Test fun `loader inventory covers every format in the §2 matrix`() {
        val block = vendoredBlock()
        val expectedLoaderExports = listOf(
            "GLTFLoader", // GLB / glTF 2.0
            "OBJLoader", // OBJ
            "MTLLoader", // OBJ (+MTL)
            "STLLoader", // STL ascii+binary
            "PLYLoader", // PLY
            "FBXLoader", // FBX
            "ColladaLoader", // DAE
            "ThreeMFLoader", // 3MF
            "USDZLoader", // USDZ (best-effort)
        )
        for (loaderExport in expectedLoaderExports) {
            assertTrue("vendored bundle is missing the $loaderExport export", block.contains(loaderExport))
        }
        assertTrue("OrbitControls is not part of the vendored bundle", block.contains("OrbitControls"))

        // Every format the bootstrap's own loadModel switch actually dispatches on.
        val expectedFormatCases = listOf("gltf", "glb", "obj", "stl", "ply", "fbx", "dae", "3mf", "usdz")
        val scaffold = ownScaffold()
        for (formatCase in expectedFormatCases) {
            assertTrue("bootstrap has no case for format \"$formatCase\"", scaffold.contains("case '$formatCase':"))
        }
        // USDZ is explicitly best-effort/feature-detected (docs/design/objects-3d.md §2) — the
        // honest-degradation branch must exist even in a build where USDZLoader did ship, since
        // scripts/build-object-viewer.js always emits the runtime feature-detect rather than a
        // build-time-only branch (see that script's own header for why).
        assertTrue("no honest 'not supported yet' USDZ fallback wired", scaffold.contains("USDZ preview not supported yet"))
        assertTrue("USDZ path is not runtime-feature-detected", scaffold.contains("VENDOR.USDZLoader"))
    }

    @Test fun `this repo's own scaffold references no external resource`() {
        val scaffold = ownScaffold()
        assertFalse("scaffold has an http(s):// URL outside the vendored block", Regex("""https?://""").containsMatchIn(scaffold))
        assertFalse("scaffold has a <script src=…>", Regex("""<script[^>]+src=""").containsMatchIn(scaffold))
        assertFalse("scaffold has a <link …>", scaffold.contains("<link"))
        assertFalse("scaffold has an <iframe", scaffold.contains("<iframe"))
        assertFalse("scaffold calls fetch(", scaffold.contains("fetch("))
        assertFalse("scaffold constructs an XMLHttpRequest", scaffold.contains("XMLHttpRequest"))
        assertFalse("scaffold opens a WebSocket", scaffold.contains("WebSocket"))
        assertFalse("scaffold calls document.write", scaffold.contains("document.write"))
    }

    @Test fun `the ProceduralScene DSL interpreter matches the real wire shape`() {
        // dev.fonebrew.domain.object3d.ProceduralSceneCodec's exact org.json output — this
        // bootstrap must read the SAME field names, not an invented parallel shape.
        val scaffold = ownScaffold()
        for (field in listOf("op.kind", "op.params", "op.colorHex", "op.transform", "op.group", "translate", "rotateDeg", "scale")) {
            assertTrue("bootstrap never references \"$field\" — ProceduralScene DSL wire shape mismatch", scaffold.contains(field))
        }
        for (kind in listOf("'BOX'", "'SPHERE'", "'CYLINDER'", "'CONE'", "'TORUS'", "'PLANE'")) {
            assertTrue("bootstrap never handles primitive kind $kind", scaffold.contains("case $kind:"))
        }
        // Grouping is the `group` field (§4: "ops sharing a non-null group value are unioned by
        // the viewer") — not a nested "union" op type.
        assertFalse("bootstrap still expects a nested union op type — DSL wire shape mismatch", scaffold.contains("op.type"))
    }
}
