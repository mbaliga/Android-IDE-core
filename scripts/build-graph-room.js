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
// Same bundled Plus Jakarta Sans TTF ui/theme/Type.kt uses for every native Compose surface —
// embedded as a base64 data: URI (binding rule 1 / "assets stay local" — no @font-face src ever
// fetched at runtime) so this is the one page in the app that DOESN'T fall back to the platform
// default sans, per this WP's own audit finding.
const fontPath = path.join(root, 'core-engine', 'src', 'main', 'res', 'font', 'plus_jakarta_sans.ttf');

const vendored = fs.readFileSync(vendorPath, 'utf8');
const fontBase64 = fs.readFileSync(fontPath).toString('base64');

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
//   MESSAGE -> circle, FORK_ROOT -> triangle, SPAWN_ROOT -> rect, RUN_ROOT -> hexagon,
//   MARKER -> diamond, DECISION -> ellipse, DELEGATION -> star (all seven are registered G6
//   built-in node types in this vendored build — verified against the bundle's own extension
//   registry, not guessed; 'donut' was considered for DECISION and rejected — it draws its ring
//   from a `donuts` segment-data array (a chart node, not a plain-fill primitive) and renders
//   nothing without one).
// Edge kind -> stroke colour + a distinct dash pattern (solid/dashed/dotted), same dual-channel
// rule applied to edges.
//
// Fill/stroke colours come from a `theme` object GraphWebRoom.kt resolves from the live
// dev.fonebrew.ui.theme.HyleColors (LocalHyleColors.current) and pushes alongside the graph JSON
// on every `load()` call — see that file's own KDoc. DEFAULT_THEME below (the exact Color.kt hex
// constants, not a guess) is only the fallback for the narrow window before that first push, or
// if a caller ever invokes `load()` with no second argument at all; the theme this page actually
// draws with always tracks the user's live accent/mode once Kotlin has pushed one.
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

  // Fallback only (see this script's own comment above) — the exact dev.fonebrew.ui.theme.Color.kt
  // hex constants: TextMid, Cyan, Warning, Success, Violet, ErrorRed, TextHigh.
  var DEFAULT_THEME = {
    textMid: '#9ca3af',
    cyan: '#08fed5',
    warning: '#f78819',
    success: '#3ab700',
    violet: '#8e7bff',
    error: '#ee322c',
    textHigh: '#ecedef',
  };

  // Human vocabulary for a raw ThreadNodeKind token, mirroring GraphRoom.kt's own kindLabel() —
  // the native room's precedent for how a node kind is surfaced to a user, never the bare enum.
  // RUN_ROOT/DECISION added 2026-08-29 audit (gaps 1+3).
  var KIND_LABEL = {
    MESSAGE: 'Turn',
    FORK_ROOT: 'Fork',
    SPAWN_ROOT: 'Spawn',
    RUN_ROOT: 'Loop run',
    MARKER: 'Marker',
    DECISION: 'Decision',
    DELEGATION: 'Delegation',
  };

  // Outcome vocabulary for a DELEGATION node's 'outcome' field (2026-08-29 audit gap 2) —
  // mirrors GraphRoom.kt's own outcomeLabel().
  var OUTCOME_LABEL = { PENDING: 'Pending', KEPT: 'Kept', REVERTED: 'Reverted' };

  function shapeFor(kind) {
    switch (kind) {
      case 'MESSAGE': return 'circle';
      case 'FORK_ROOT': return 'triangle';
      case 'SPAWN_ROOT': return 'rect';
      case 'RUN_ROOT': return 'hexagon';
      case 'MARKER': return 'diamond';
      // 'ellipse' (not 'donut'): this vendored build's donut node draws its ring from a
      // donuts segment-data array (a pie/donut-CHART node, not a plain-fill primitive) — with
      // none supplied it renders nothing. 'ellipse' is a real fill primitive whose own default
      // style (size 45x35) is already non-square, so it reads as visually distinct from
      // 'circle' (MESSAGE) with zero extra config needed.
      case 'DECISION': return 'ellipse';
      case 'DELEGATION': return 'star';
      default: return 'circle';
    }
  }
  function fillFor(kind, theme) {
    switch (kind) {
      case 'MESSAGE': return theme.textMid;
      case 'FORK_ROOT': return theme.cyan;
      case 'SPAWN_ROOT': return theme.warning;
      case 'RUN_ROOT': return theme.error;
      case 'MARKER': return theme.success;
      case 'DECISION': return theme.textHigh;
      case 'DELEGATION': return theme.violet;
      default: return theme.textMid;
    }
  }
  function edgeStyleFor(kind, theme) {
    switch (kind) {
      case 'REPLY': return { stroke: theme.textMid, lineDash: null };
      case 'FORK': return { stroke: theme.cyan, lineDash: null };
      case 'SPAWN': return { stroke: theme.cyan, lineDash: null };
      case 'LINEAGE': return { stroke: theme.violet, lineDash: [4, 3] };
      case 'MARKER_ANCHOR': return { stroke: '#5b6068', lineDash: [1, 3] };
      case 'DECISION_ANCHOR': return { stroke: '#5b6068', lineDash: [1, 3] };
      case 'DELEGATION_ANCHOR': return { stroke: '#5b6068', lineDash: [1, 3] };
      default: return { stroke: theme.textMid, lineDash: null };
    }
  }

  // A blank/missing label used to render the raw enum token (e.g. "FORK_ROOT") straight onto the
  // canvas — GraphRoom.kt's own kindLabel() is the native precedent this mirrors. A DELEGATION's
  // resolved outcome (2026-08-29 audit gap 2) is appended as a textual suffix — the WCAG-safe
  // "shape/label channel, never hue alone" this kind's fixed 'star' shape can't carry on its own
  // (the shape identifies the KIND; the label is what's left to carry the VALUE).
  function labelFor(n) {
    var raw = n.label;
    var base = (raw && String(raw).trim().length > 0) ? raw : (KIND_LABEL[n.kind] || n.kind);
    if (n.kind === 'DELEGATION' && n.outcome && OUTCOME_LABEL[n.outcome]) {
      return base + ' · ' + OUTCOME_LABEL[n.outcome];
    }
    return base;
  }

  // ThreadGraph (schemas/thread/thread-graph.schema.json) -> G6 GraphData. Every style value is
  // baked in per-node/per-edge here (not a global node/edge style-mapping callback) so there is
  // nothing left for G6 to resolve dynamically beyond drawing exactly what this function hands
  // it — easier to reason about, and easier for this file's own JVM asset test to have nothing
  // load-bearing to assert about G6's mapping-callback API surface.
  function toGraphData(g, theme) {
    const nodesRaw = g.nodes || [];
    // 2026-08-29 audit gap 4: id -> raw node, so a REPLY edge below can read its TARGET
    // message's confidence without a second pass over g.nodes per edge.
    const byId = {};
    nodesRaw.forEach(function (n) { byId[n.id] = n; });

    const nodes = nodesRaw.map(function (n) {
      return {
        id: n.id,
        type: shapeFor(n.kind),
        style: {
          fill: fillFor(n.kind, theme),
          stroke: '#fff',
          lineWidth: 1,
          label: true,
          labelText: labelFor(n),
          labelFontSize: 10,
          labelFontFamily: "'Plus Jakarta Sans', -apple-system, system-ui, sans-serif",
          labelFill: '#e6e6e6',
          labelPlacement: 'bottom',
        },
        // sha/repoRef (1.2.0, COMMIT node — schema only, no producer mints one yet): passthrough
        // only, same rationale the edge data's derivation/because carry below.
        data: { kind: n.kind, at: n.at, label: n.label, outcome: n.outcome, confidence: n.confidence, sha: n.sha, repoRef: n.repoRef },
      };
    });
    const edges = (g.edges || []).map(function (e, i) {
      const st = edgeStyleFor(e.kind, theme);
      // Confidence-weighted line width/opacity (2026-08-29 audit gap 4) — absent (undefined,
      // not a fabricated 0) when the target never captured one, leaving the edge exactly as
      // before. The raw number is also carried in data.confidence for the click inspector below
      // — never a line-weight/opacity-only channel per binding constraint 6.
      const targetConfidence = e.kind === 'REPLY' ? (byId[e.to] || {}).confidence : null;
      const lineWidth = (typeof targetConfidence === 'number')
        ? 1.5 * (0.7 + 1.8 * targetConfidence)
        : 1.5;
      const strokeOpacity = (typeof targetConfidence === 'number')
        ? (0.5 + 0.5 * targetConfidence)
        : 1;
      return {
        id: 'e' + i,
        source: e.from,
        target: e.to,
        style: { stroke: st.stroke, lineDash: st.lineDash, lineWidth: lineWidth, strokeOpacity: strokeOpacity },
        // derivation/because (1.2.0, graph-wave lane A): passthrough only — carried onto the G6
        // edge's own data so a future load()/inspector call can read them, same as every other
        // field here. Absent (undefined) for an edge from a pre-1.2.0 snapshot or one this
        // producer never populated, never a fabricated placeholder. No new visual encoding is
        // added for them here (no colour/dash driven off derivation) — that's a rendering
        // decision for a later work package, not this passthrough.
        data: { kind: e.kind, confidence: targetConfidence, derivation: e.derivation, because: e.because },
      };
    });
    return { nodes: nodes, edges: edges };
  }

  function setStatus(text) {
    var el = document.getElementById('graph-room-status');
    if (el) el.textContent = text;
  }

  // The legend row (GraphRoom.kt's own Legend() is the native precedent: a shape swatch + label
  // per ThreadNodeKind) — rendered once at page-init with DEFAULT_THEME so the page never looks
  // unfinished before Kotlin's first load() call, then re-rendered with the real theme once one
  // arrives, so it never drifts from the node/edge colours actually drawn.
  function renderLegend(theme) {
    var el = document.getElementById('graph-room-legend');
    if (!el) return;
    var order = ['MESSAGE', 'FORK_ROOT', 'SPAWN_ROOT', 'RUN_ROOT', 'MARKER', 'DECISION', 'DELEGATION'];
    var shapeCss = {
      MESSAGE: 'border-radius:50%;',
      FORK_ROOT: 'border-radius:2px;transform:rotate(45deg);',
      SPAWN_ROOT: 'border-radius:2px;',
      RUN_ROOT: 'border-radius:2px;',
      MARKER: 'border-radius:2px;transform:rotate(45deg);',
      DECISION: 'border-radius:50%; width:11px; height:7px;',
      DELEGATION: 'border-radius:50%;',
    };
    el.innerHTML = order.map(function (kind) {
      var color = fillFor(kind, theme);
      var swatch = '<span style="display:inline-block;width:8px;height:8px;margin-right:4px;' +
        'background:' + color + ';' + shapeCss[kind] + '"></span>';
      return '<span style="display:inline-flex;align-items:center;margin-right:10px;">' +
        swatch + (KIND_LABEL[kind] || kind) + '</span>';
    }).join('');
  }

  // Filled by every load() call — the node-click inspector reads its data straight from here
  // (the exact ThreadGraph JSON this page already parsed) rather than depending on any G6
  // internal node-data-lookup API, so this stays correct even if a future vendor bump changes
  // that internal shape.
  var lastGraphNodesById = {};

  function renderInspector(node) {
    var el = document.getElementById('graph-room-inspector');
    if (!el) return;
    if (!node) { el.hidden = true; el.textContent = ''; return; }
    var lines = [(KIND_LABEL[node.kind] || node.kind)];
    if (node.label) lines.push(String(node.label));
    if (node.outcome && OUTCOME_LABEL[node.outcome]) lines.push('Outcome: ' + OUTCOME_LABEL[node.outcome]);
    if (typeof node.confidence === 'number') lines.push('Confidence: ' + Math.round(node.confidence * 100) + '%');
    if (node.at) lines.push(String(node.at));
    el.textContent = lines.join('\\n');
    el.hidden = false;
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
    // 2026-08-29 audit gap 4's node inspector: 'node:click' is a real graph-level event this
    // vendored build's own click-select behavior and Tooltip plugin both key off internally
    // (evt.target.id is their own pattern for "which element was clicked", not a guess) — attached
    // once, here, never re-attached per load() (ensureGraph() itself is already a create-once
    // guard). try/catch: a node inspector that fails to render must never break panning/zooming.
    graph.on('node:click', function (evt) {
      try {
        var id = evt && evt.target && evt.target.id;
        renderInspector(id ? lastGraphNodesById[id] : null);
      } catch (err) { /* inspector is best-effort; the graph itself must keep working */ }
    });
    graph.on('canvas:click', function () { renderInspector(null); });
    return graph;
  }

  window.__graphRoom = {
    load: function (jsonText, themeJsonText) {
      try {
        var theme = DEFAULT_THEME;
        if (themeJsonText) {
          try {
            var pushed = JSON.parse(themeJsonText);
            theme = {
              textMid: pushed.textMid || DEFAULT_THEME.textMid,
              cyan: pushed.cyan || DEFAULT_THEME.cyan,
              warning: pushed.warning || DEFAULT_THEME.warning,
              success: pushed.success || DEFAULT_THEME.success,
              violet: pushed.violet || DEFAULT_THEME.violet,
              error: pushed.error || DEFAULT_THEME.error,
              textHigh: pushed.textHigh || DEFAULT_THEME.textHigh,
            };
          } catch (themeErr) {
            theme = DEFAULT_THEME;
          }
        }
        renderLegend(theme);
        var parsed = JSON.parse(jsonText);
        lastGraphNodesById = {};
        (parsed.nodes || []).forEach(function (n) { lastGraphNodesById[n.id] = n; });
        renderInspector(null); // a fresh load invalidates whatever was selected before
        var data = toGraphData(parsed, theme);
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

  renderLegend(DEFAULT_THEME);
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
  @font-face {
    font-family: 'Plus Jakarta Sans';
    src: url(data:font/ttf;base64,${fontBase64}) format('truetype');
    font-weight: 200 800;
    font-display: swap;
  }
  html, body {
    margin: 0; height: 100%; background: #0e0e12; overflow: hidden;
    font-family: 'Plus Jakarta Sans', -apple-system, system-ui, sans-serif;
  }
  #graph-room-container { position: fixed; inset: 0; width: 100vw; height: 100vh; }
  #graph-room-status {
    position: fixed; left: 8px; bottom: 8px; z-index: 10;
    font: 11px/1.4 'Plus Jakarta Sans', -apple-system, system-ui, sans-serif; color: #9aa0a6;
    background: rgba(0, 0, 0, .45); padding: 4px 8px; border-radius: 6px; pointer-events: none;
  }
  #graph-room-legend {
    position: fixed; left: 8px; bottom: 32px; z-index: 10;
    font: 10px/1.4 'Plus Jakarta Sans', -apple-system, system-ui, sans-serif; color: #e6e6e6;
    background: rgba(0, 0, 0, .45); padding: 4px 8px; border-radius: 6px; pointer-events: none;
    max-width: calc(100vw - 16px); display: flex; flex-wrap: wrap;
  }
  #graph-room-inspector {
    position: fixed; right: 8px; top: 8px; z-index: 10;
    font: 11px/1.5 'Plus Jakarta Sans', -apple-system, system-ui, sans-serif; color: #e6e6e6;
    background: rgba(0, 0, 0, .6); padding: 6px 10px; border-radius: 6px; pointer-events: none;
    max-width: min(60vw, 260px); white-space: pre-line;
  }
</style>
</head>
<body>
<div id="graph-room-container"></div>
<!-- GraphRoom.kt's own Legend() is the native precedent this mirrors (shape swatch + label per
     ThreadNodeKind) — populated by the bootstrap script below, not hand-written here, so it never
     drifts from the same fillFor()/KIND_LABEL maps the nodes themselves draw from. -->
<div id="graph-room-legend"></div>
<div id="graph-room-status">waiting for data&hellip;</div>
<!-- The node inspector (2026-08-29 audit gap 4): GraphRoom.kt's own NodeDetailsDialog is the
     native precedent — a tap always surfaces the raw kind/label/outcome/confidence as TEXT, never
     leaving a line-weight/opacity/shape cue as the only way to read a value. Empty/hidden until
     the bootstrap's node:click handler below fills it. -->
<div id="graph-room-inspector" hidden></div>
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
