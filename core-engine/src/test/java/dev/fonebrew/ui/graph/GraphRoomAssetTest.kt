package dev.fonebrew.ui.graph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * **WP11** — asserts the committed, build-script-generated
 * `core-engine/src/main/assets/graph/graph-room.html` (`scripts/build-graph-room.js`) still holds
 * the properties `dev.fonebrew.ui.graph.GraphWebRoom`'s WebView lockdown depends on, without a
 * device: CSP present, the `__graphRoom.load` entry point exists, the vendored G6 library's MIT
 * attribution survived, and the file carries no WebGL renderer code (binding constraint 7 —
 * "Canvas2D renderer only", the WebView rollback trauma the plan names, Hyle commit `1198515`).
 *
 * This is an *asset* test, not a *build-script* test: it doesn't re-run
 * `scripts/build-graph-room.js` (Node isn't part of the JVM gate) — it reads the file the script
 * already wrote and committed, the same "trust but verify the committed output" relationship
 * [dev.fonebrew.domain.thread.ThreadGraphJsonTest] has to its embedded fixture. If this file is ever
 * regenerated without re-running the build script (a hand-edit), this test is what catches drift.
 *
 * **No external-URL assertion runs over the whole file.** The vendored G6 body legitimately
 * contains a handful of inert third-party string literals — an SVG XML namespace URI
 * (`http://www.w3.org/2000/svg`, required by `document.createElementNS` even for entirely local
 * elements), a couple of doc/attribution comments (`g.antv.antgroup.com`, a Babel-helpers LICENSE
 * link, an html2canvas attribution) — none of which this page ever invokes, and all of which
 * `blockNetworkLoads`/CSP `connect-src 'none'` make structurally unreachable even if it did
 * (`GraphWebRoom`'s own lockdown, asserted by construction there, not here). A literal
 * whole-file substring scan for "http" would flag these false positives; instead this test scans
 * only the scaffold **this repo authors** (the CSP meta line + everything between the vendored
 * block's own HTML comment markers), where an external resource-loading tag or call would be a
 * real regression.
 */
class GraphRoomAssetTest {

    private val html: String by lazy { readAsset() }

    /** Same marker scripts/build-graph-room.js's own guard locates — the vendored UMD body
     *  starts here, after this repo's own hand-written MIT-header prose. */
    private val umdMarker = "!function(t,e){\"object\"==typeof exports"

    /** Finds `graph-room.html` regardless of the unit-test task's working directory: Gradle's
     *  default is the module's own project dir (`core-engine/`), which resolves the plain
     *  relative path directly — the fallback walks upward in case a future Gradle config ever
     *  changes that default, rather than hard-failing on an assumption this test doesn't need to
     *  make. */
    private fun readAsset(): String {
        val relative = "src/main/assets/graph/graph-room.html"
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val direct = File(dir, "core-engine/$relative")
            if (direct.isFile) return direct.readText()
            dir = dir.parentFile ?: return@repeat
        }
        fail(
            "couldn't find core-engine/src/main/assets/graph/graph-room.html from working " +
                "directory '${System.getProperty("user.dir")}' — run `node scripts/build-graph-room.js` " +
                "from the repo root first.",
        )
        error("unreachable")
    }

    private fun vendoredBlock(): String {
        val start = html.indexOf("<!-- BEGIN VENDORED G6")
        val end = html.indexOf("<!-- END VENDORED G6")
        assertTrue("vendored G6 block markers not found", start >= 0 && end > start)
        return html.substring(start, end)
    }

    /** Everything OUTSIDE the vendored block — the scaffold this repo actually authors (CSP meta,
     *  container markup, the bootstrap script). This is what the no-external-resource assertion
     *  below scans, per the class KDoc's rationale. */
    private fun ownScaffold(): String {
        val start = html.indexOf("<!-- BEGIN VENDORED G6")
        val end = html.indexOf("<!-- END VENDORED G6") + "<!-- END VENDORED G6 -->".length
        assertTrue("vendored G6 block markers not found", start >= 0 && end > start)
        return html.substring(0, start) + html.substring(end)
    }

    @Test fun `the asset file exists and is non-trivially large`() {
        assertTrue("graph-room.html looks too small (${html.length} chars) to hold the vendored G6 library", html.length > 1_300_000)
    }

    @Test fun `a strict Content-Security-Policy meta tag is present`() {
        val cspLine = Regex("""<meta http-equiv="Content-Security-Policy" content="([^"]*)">""")
            .find(html)?.groupValues?.get(1)
        assertTrue("no CSP meta tag found", cspLine != null)
        val csp = cspLine!!
        // The specific directives binding constraint 7's lockdown depends on: nothing may load
        // over the network, run in a frame, or open a plugin object, from this page's own script.
        assertTrue("CSP missing default-src 'none'", csp.contains("default-src 'none'"))
        assertTrue("CSP missing connect-src 'none'", csp.contains("connect-src 'none'"))
        assertTrue("CSP missing frame-src 'none'", csp.contains("frame-src 'none'"))
        assertTrue("CSP missing object-src 'none'", csp.contains("object-src 'none'"))
        assertTrue("CSP missing base-uri 'none'", csp.contains("base-uri 'none'"))
    }

    @Test fun `the __graphRoom load entry point is defined`() {
        assertTrue("window.__graphRoom is never assigned", html.contains("window.__graphRoom ="))
        assertTrue("__graphRoom has no load method", Regex("""load\s*:\s*function""").containsMatchIn(html))
        // Named in dev.fonebrew.domain.thread.ThreadGraphJson's own KDoc as this exact call's
        // eventual caller — GraphWebRoom.kt drives it via WebView.evaluateJavascript.
        assertTrue("no JSON.parse call in the bootstrap — load() must decode the pushed JSON text", html.contains("JSON.parse"))
    }

    @Test fun `no JavascriptInterface-style two-way bridge is implied by the asset itself`() {
        // The asset can't assert what GraphWebRoom.kt does or doesn't register on the Android
        // side (that's GraphWebRoom's own contract — no addJavascriptInterface call), but it can
        // assert its own bootstrap never assumes one exists: no reference to a native-injected
        // global other than the G6 library itself and window.__graphRoom, which this file defines.
        assertFalse("bootstrap references an Android JS-interface-style global", html.contains("AndroidBridge"))
    }

    @Test fun `the vendored G6 library's MIT attribution is present`() {
        val block = vendoredBlock()
        assertTrue("MIT License text missing from the vendored block", block.contains("MIT License"))
        assertTrue("Alipay copyright line missing from the vendored block", block.contains("Copyright (c) 2018 Alipay.inc"))
        assertTrue("G6 5.1.1 version pin missing from the vendored block", block.contains("5.1.1"))
    }

    @Test fun `the vendored block carries no WebGL renderer code`() {
        // The structural half of the renderer pin (binding constraint 7): this vendored UMD
        // build's own package.json lists @antv/g-canvas as its only runtime rendering
        // dependency (@antv/g-webgl and @antv/g-svg are devDependencies only), so a build that
        // truly has no "webgl" or "g-webgl" substring anywhere in its vendored body cannot
        // construct a WebGL renderer even if graph-room.html's bootstrap asked it to — there is
        // no such class in the file for it to find. scripts/build-graph-room.js asserts this same
        // invariant at build time (and fails loudly if a re-vendor ever violates it); this test
        // asserts it survived into the committed output.
        // Scoped past the UMD wrapper start, same as scripts/build-graph-room.js's own guard —
        // excludes this repo's *own* header prose sitting above the vendored code (which
        // legitimately names "WebGL"/"g-webgl" in English while explaining their absence below).
        val block = vendoredBlock()
        val umdStart = block.indexOf(umdMarker)
        assertTrue("couldn't locate the UMD wrapper start inside the vendored block", umdStart >= 0)
        val vendoredBody = block.substring(umdStart)
        assertFalse("vendored body contains a case-insensitive \"webgl\" match", Regex("webgl", RegexOption.IGNORE_CASE).containsMatchIn(vendoredBody))
        assertFalse("vendored body references \"g-webgl\"", vendoredBody.contains("g-webgl"))
    }

    @Test fun `the bootstrap never asks G6 for a non-default renderer`() {
        // Defense in depth alongside the previous test: even if some future re-vendor smuggled a
        // WebGL-capable build in, this page's own Graph construction must still never opt into
        // it by requesting anything other than the implicit default. Scoped to this repo's own
        // scaffold (outside the vendored block) — the vendored library's own internal code
        // legitimately destructures a `renderer:` property throughout its own plumbing (that's
        // not a config option a consumer passed in, it's G6's own internals), so a whole-file
        // scan for that token would always be true regardless of what this page's own bootstrap
        // does.
        assertFalse("scaffold passes an explicit renderer option to new G6.Graph", ownScaffold().contains("renderer:"))
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
    }

    @Test fun `node kind maps to a distinct built-in shape per kind, never colour alone`() {
        // WCAG 1.4.1 / binding constraint 6, mirrored on the web surface: each ThreadNodeKind
        // (dev.fonebrew.domain.thread.ThreadNodeKind) must map to its own G6 built-in node type
        // string, not just a colour swap. RUN_ROOT/DECISION added 2026-08-29 audit (gaps 1+3).
        // DECISION uses 'ellipse', not 'donut': this vendored build's donut node draws its ring
        // from a donuts segment-data array and renders nothing without one — a chart node, not a
        // plain-fill primitive; 'ellipse' is, and its own default style is already non-square.
        val shapes = listOf("'circle'", "'triangle'", "'rect'", "'diamond'", "'star'", "'hexagon'", "'ellipse'")
        shapes.forEach { assertTrue("bootstrap never assigns node type $it", html.contains(it)) }
        assertTrue("MESSAGE not mapped", html.contains("case 'MESSAGE': return 'circle';"))
        assertTrue("FORK_ROOT not mapped", html.contains("case 'FORK_ROOT': return 'triangle';"))
        assertTrue("SPAWN_ROOT not mapped", html.contains("case 'SPAWN_ROOT': return 'rect';"))
        assertTrue("RUN_ROOT not mapped", html.contains("case 'RUN_ROOT': return 'hexagon';"))
        assertTrue("MARKER not mapped", html.contains("case 'MARKER': return 'diamond';"))
        assertTrue("DECISION not mapped", html.contains("case 'DECISION': return 'ellipse';"))
        assertTrue("DELEGATION not mapped", html.contains("case 'DELEGATION': return 'star';"))
    }

    @Test fun `a DELEGATION node's outcome is carried as label text, never colour alone`() {
        // 2026-08-29 audit gap 2: DELEGATION's shape is fixed ('star', asserted above) to
        // identify the KIND — outcome (PENDING/KEPT/REVERTED) needs its own, separate WCAG-safe
        // channel, which this bootstrap carries as an appended label suffix.
        assertTrue("no OUTCOME_LABEL map", html.contains("OUTCOME_LABEL"))
        assertTrue("outcome never appended to the DELEGATION label", html.contains("n.kind === 'DELEGATION' && n.outcome"))
    }

    @Test fun `a REPLY edge's line weight varies with the target message's confidence, never alone`() {
        // 2026-08-29 audit gap 4: line-weight/opacity is the always-visible channel, but binding
        // constraint 6 forbids it standing alone — the node inspector (asserted below) is the
        // paired text channel carrying the same number.
        assertTrue("no confidence-driven lineWidth", html.contains("targetConfidence"))
        assertTrue("no confidence-driven strokeOpacity", html.contains("strokeOpacity"))
    }

    @Test fun `a node click renders its kind, outcome, and confidence as inspector text`() {
        // 2026-08-29 audit gap 4's "value also visible on the node inspector" — GraphRoom.kt's
        // NodeDetailsDialog is the native precedent; this is this surface's own equivalent.
        assertTrue("no graph-room-inspector element", html.contains("id=\"graph-room-inspector\""))
        assertTrue("no node:click wiring", html.contains("'node:click'"))
        assertTrue("inspector never renders confidence as text", html.contains("node.confidence"))
        assertTrue("inspector never renders outcome as text", html.contains("node.outcome"))
    }

    @Test fun `no drag-element behavior is enabled`() {
        // Read-only projection parity with the native GraphRoom.kt surface (its own KDoc: "unlike
        // the Loop editor, nothing here can be dragged into a different position") — this deep
        // view stays pan/zoom/select only, never node-repositioning. Scoped to this repo's own
        // scaffold: the vendored library registers a "drag-element" behavior key internally
        // (double-quoted, part of its own extension registry) regardless of what this page's
        // bootstrap opts into, which is the thing this test actually cares about.
        val scaffold = ownScaffold()
        assertFalse("bootstrap enables 'drag-element'", scaffold.contains("'drag-element'"))
        assertTrue("bootstrap doesn't enable drag-canvas", scaffold.contains("'drag-canvas'"))
        assertTrue("bootstrap doesn't enable zoom-canvas", scaffold.contains("'zoom-canvas'"))
    }
}
