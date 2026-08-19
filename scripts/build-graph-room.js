// Assemble the self-contained G6 "deep graph room" HTML asset that
// `dev.fonebrew.ui.graph.GraphWebRoom` loads into a locked-down WebView
// (THREAD_TOPOLOGY_PLAN.md WP11). Mirrors the guard idiom of
// hyle-design-system/scripts/build-color-picker.js and build-texture-surface.js: read a
// vendored/extracted source verbatim, fail loudly if it doesn't look like what this script
// expects, then wrap it with a thin, additive scaffold (here: CSP meta, a container div, and
// a bootstrap script exposing `window.__graphRoom.load(json)`) rather than reinterpreting the
// library itself. Unlike those two scripts — which slice a region out of an existing kit HTML
// page this repo already owns — this one's "source" is `third_party/g6/g6.min.js`, a vendored
// third-party UMD build (see that file's own header for provenance/hash), so the guard here
// checks the vendored file's shape instead of a line-number slice.
//
// This repo's root has no package.json (unlike hyle-design-system's, which sets
// "type":"module" for its own scripts) — written as plain CommonJS so it runs with a bare
// `node scripts/build-graph-room.js`, no new root-level config needed.
//
// Output: core-engine/src/main/assets/graph/graph-room.html (committed — Gradle does not run
// this script; it's a dev-time regeneration tool, same relationship build-color-picker.js has
// to its own output. Re-run by hand whenever third_party/g6/g6.min.js is re-vendored.)
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const vendorPath = path.join(root, 'third_party', 'g6', 'g6.min.js');
const outPath = path.join(root, 'core-engine', 'src', 'main', 'assets', 'graph', 'graph-room.html');

const vendored = fs.readFileSync(vendorPath, 'utf8');

// Guard: fail loudly if the vendored file isn't what this script expects, per the
// build-texture-surface.js precedent ("fail loudly if the source ever shifts out from under
// these line numbers"). Checked instead of trusted: the UMD global-export marker, the pinned
// version string, an MIT attribution line, and a coarse size floor (catches a truncated or
// swapped-out download) — plus the renderer-pin invariant binding constraint 7 requires
// (Canvas2D only, WebView rollback trauma commit 1198515): this build must carry no WebGL
// renderer code at all, so a future re-vendor that accidentally pulls in @antv/g-webgl (or a
// build with it bundled in) is caught here rather than silently shipping.
function fail(reason) {
  throw new Error(
    `build-graph-room: ${reason} — re-check third_party/g6/g6.min.js (expected AntV G6 5.1.1, ` +
      'MIT, UMD "G6" global, Canvas-only build; see that file\'s own header for how it was ' +
      'fetched/verified).',
  );
}
const UMD_MARKER = '!function(t,e){"object"==typeof exports';
const umdStart = vendored.indexOf(UMD_MARKER);
if (!vendored.includes('.G6={}')) fail('missing the UMD "G6" global-export marker');
if (!vendored.includes('5.1.1')) fail('missing the pinned "5.1.1" version string');
if (!vendored.includes('MIT License')) fail('missing the hand-prepended MIT License header');
if (!vendored.includes('Alipay')) fail('missing the MIT header\'s Alipay copyright line');
if (vendored.length < 1_300_000) fail(`unexpectedly small (${vendored.length} bytes) — looks truncated`);
if (umdStart < 0) fail('missing the expected UMD wrapper start — can\'t locate the vendored body');
// The renderer-pin check runs only over the vendored *body* (from the UMD wrapper onward), not
// this file's own hand-written header above it — the header's prose legitimately names
// "WebGL"/"g-webgl" while explaining why neither is present in the code that follows.
const vendoredBody = vendored.slice(umdStart);
if (/webgl/i.test(vendoredBody)) fail('vendored body contains a case-insensitive "webgl" match — renderer pin violated');
if (vendoredBody.includes('g-webgl')) fail('vendored body references "g-webgl" — renderer pin violated');

// Content-Security-Policy: the tightest policy that still lets the page run — everything is
// inline/self (no external script/style/font/image ever loads; blockNetworkLoads on the
// WebView side is the second, independent layer of the same guarantee, per binding
// constraint 7). connect-src/frame-src/object-src 'none' — this page never opens a network
// connection, an iframe, or a plugin object under any circumstance.
const CSP =
  "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
  "img-src 'self' data:; font-src 'self' data:; connect-src 'none'; frame-src 'none'; " +
  "object-src 'none'; base-uri 'none'; form-action 'none';";

// The bootstrap: additive only, like build-color-picker.js's postMessage bridge — it never
// touches the vendored library's own code, only calls the public `G6.Graph` API it exports.
// One entry point, one direction: `window.__graphRoom.load(jsonText)` is called from Kotlin via
// WebView.evaluateJavascript with a pre-serialized ThreadGraph JSON string (see
// dev.fonebrew.domain.thread.ThreadGraphJson.toJsonString's own KDoc, which names this exact call
// as its forward pointer) — there is no reverse (JS-to-Kotlin) channel: no
// addJavascriptInterface is registered on the Android side (see GraphWebRoom.kt), which is the
// "narrow bridge" binding constraint 7 asks for — the narrowest bridge is one that doesn't
// exist in the attacker-reachable direction at all.
//
// Node kind -> a distinct built-in G6 node type (shape, never colour-only per binding
// constraint 6 / WCAG 1.4.1) + a distinct fill, mirroring GraphRoom.kt's own glyphFor mapping
// so the native and deep-web views read as the same visual language:
//   MESSAGE -> circle, FORK_ROOT -> triangle, SPAWN_ROOT -> rect, MARKER -> diamond,
//   DELEGATION -> star (all five are registered G6 built-in node types in this vendored build —
//   verified against the bundle's own extension registry, not guessed).
// Edge kind -> stroke colour + a distinct dash pattern (solid/dashed/dotted), same dual-channel
// rule applied to edges.
//
// Deliberately NOT wired here (forward pointers, not built now — this WP's scope is the base
// wiring, not every G6 feature the exploration section named as available): Fisheye,
// EdgeBundling, BubbleSets, Minimap, Timebar. All are real exports of this vendored build
// (`G6.Fisheye`, `G6.EdgeBundling`, `G6.BubbleSets`, `G6.Minimap`, `G6.Timebar`) a later WP can
// add as `plugins: [...]` entries once a device confirms this base wiring renders at all — see
// "Environment honesty": nothing about G6's actual on-device rendering/touch behaviour can be
// verified from this container.
const BOOTSTRAP = `
(function () {
  'use strict';

  function shapeFor(kind) {
    switch (kind) {
      case 'MESSAGE': return 'circle';
      case 'FORK_ROOT': return 'triangle';
      case 'SPAWN_ROOT': return 'rect';
      case 'MARKER': return 'diamond';
      case 'DELEGATION': return 'star';
      default: return 'circle';
    }
  }
  function fillFor(kind) {
    switch (kind) {
      case 'MESSAGE': return '#9aa0a6';
      case 'FORK_ROOT': return '#22d3ee';
      case 'SPAWN_ROOT': return '#f5a524';
      case 'MARKER': return '#4ade80';
      case 'DELEGATION': return '#a78bfa';
      default: return '#9aa0a6';
    }
  }
  function edgeStyleFor(kind) {
    switch (kind) {
      case 'REPLY': return { stroke: '#9aa0a6', lineDash: null };
      case 'FORK': return { stroke: '#22d3ee', lineDash: null };
      case 'SPAWN': return { stroke: '#22d3ee', lineDash: null };
      case 'LINEAGE': return { stroke: '#a78bfa', lineDash: [4, 3] };
      case 'MARKER_ANCHOR': return { stroke: '#5b6068', lineDash: [1, 3] };
      case 'DELEGATION_ANCHOR': return { stroke: '#5b6068', lineDash: [1, 3] };
      default: return { stroke: '#9aa0a6', lineDash: null };
    }
  }

  // ThreadGraph (schemas/thread/thread-graph.schema.json) -> G6 GraphData. Every style value is
  // baked in per-node/per-edge here (not a global node/edge style-mapping callback) so there is
  // nothing left for G6 to resolve dynamically beyond drawing exactly what this function hands
  // it — easier to reason about, and easier for this file's own JVM asset test to have nothing
  // load-bearing to assert about G6's mapping-callback API surface.
  function toGraphData(g) {
    const nodes = (g.nodes || []).map(function (n) {
      return {
        id: n.id,
        type: shapeFor(n.kind),
        style: {
          fill: fillFor(n.kind),
          stroke: '#fff',
          lineWidth: 1,
          label: true,
          labelText: n.label || n.kind,
          labelFontSize: 10,
          labelFill: '#e6e6e6',
          labelPlacement: 'bottom',
        },
        data: { kind: n.kind, at: n.at },
      };
    });
    const edges = (g.edges || []).map(function (e, i) {
      const st = edgeStyleFor(e.kind);
      return {
        id: 'e' + i,
        source: e.from,
        target: e.to,
        style: { stroke: st.stroke, lineDash: st.lineDash, lineWidth: 1.5 },
      };
    });
    return { nodes: nodes, edges: edges };
  }

  function setStatus(text) {
    var el = document.getElementById('graph-room-status');
    if (el) el.textContent = text;
  }

  var graph = null;
  function ensureGraph() {
    if (graph) return graph;
    // No explicit renderer option: this vendored build's default renderer is the only one it
    // physically contains (Canvas2D via @antv/g-canvas — see g6.min.js's own header and this
    // script's guard above), so omitting the option *is* the pin, not a gap. Read-only view:
    // pan the canvas, pinch/wheel zoom it, tap to select a node — that's the whole behavior
    // list below. Nothing here lets an element be dragged to a new position, matching
    // GraphRoom.kt's native surface (deliberately undraggable — nothing here is a judgment-value
    // drag either, binding constraint 1 is about the native message-drag surface specifically,
    // not this read-only projection, but parity avoids any ambiguity about what dragging could
    // mean on a thread-topology surface).
    graph = new G6.Graph({
      container: 'graph-room-container',
      autoFit: 'view',
      layout: { type: 'force', preventOverlap: true },
      behaviors: ['drag-canvas', 'zoom-canvas', 'click-select'],
    });
    return graph;
  }

  window.__graphRoom = {
    load: function (jsonText) {
      try {
        var parsed = JSON.parse(jsonText);
        var data = toGraphData(parsed);
        var g = ensureGraph();
        g.setData(data);
        g.render().then(function () {
          setStatus(data.nodes.length + ' nodes \\u00b7 ' + data.edges.length + ' edges');
        }).catch(function (err) {
          setStatus('render failed: ' + (err && err.message ? err.message : String(err)));
        });
      } catch (err) {
        setStatus('load failed: ' + (err && err.message ? err.message : String(err)));
      }
    },
  };

  setStatus('waiting for data\\u2026');
})();
`;

const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, user-scalable=no">
<meta http-equiv="Content-Security-Policy" content="${CSP}">
<title>Aarso — Graph Room</title>
<style>
  html, body { margin: 0; height: 100%; background: #0e0e12; overflow: hidden; }
  #graph-room-container { position: fixed; inset: 0; width: 100vw; height: 100vh; }
  #graph-room-status {
    position: fixed; left: 8px; bottom: 8px; z-index: 10;
    font: 11px/1.4 -apple-system, system-ui, sans-serif; color: #9aa0a6;
    background: rgba(0, 0, 0, .45); padding: 4px 8px; border-radius: 6px; pointer-events: none;
  }
</style>
</head>
<body>
<div id="graph-room-container"></div>
<div id="graph-room-status">waiting for data&hellip;</div>
<!-- BEGIN VENDORED G6 (third_party/g6/g6.min.js, verbatim + prepended MIT header) -->
<script>
${vendored}
</script>
<!-- END VENDORED G6 -->
<!-- BEGIN GRAPH ROOM BOOTSTRAP (additive; not part of the vendored library) -->
<script>
${BOOTSTRAP}
</script>
<!-- END GRAPH ROOM BOOTSTRAP -->
</body>
</html>
`;

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, html, 'utf8');
console.log('build-graph-room: wrote', outPath, `(${html.length} bytes)`);
