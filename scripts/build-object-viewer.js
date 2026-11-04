// Assemble the self-contained offline 3D object viewer HTML asset that
// `dev.fonebrew.ui.object3d.ObjectViewerRoom` loads into a locked-down WebView
// (docs/design/objects-3d.md §3). Mirrors the two-part shape of scripts/build-graph-room.js —
// a vendored third-party library plus a thin, additive bootstrap wrapped in CSP/scaffold — but
// splits into two phases where build-graph-room.js only needed one:
//
//   Phase A (vendor): unlike AntV G6, three.js does not ship a single prebuilt UMD file to vendor
//   verbatim — it ships as ES module *source* (`node_modules/three/examples/jsm/**`). So this
//   phase exists only for three.js: it uses esbuild to bundle three's core + OrbitControls + the
//   loaders in docs/design/objects-3d.md §2's format matrix into one minified IIFE, and writes
//   that bundle to `third_party/threejs/object-viewer.bundle.min.js` (committed, hand-licensed —
//   see NOTICE) — this repo's equivalent of `third_party/g6/g6.min.js`.
//
//   Phase B (assemble): read the vendored bundle (freshly built above, or already sitting in
//   third_party/ from a prior run) and wrap it with a CSP meta, a WebGL context-creation
//   self-test, the `window.__objectViewer.load(kind, payload, format)` entry point, and a
//   ProceduralScene DSL interpreter — same additive-scaffold relationship
//   build-graph-room.js's BOOTSTRAP has to the vendored G6 body. Output:
//   `core-engine/src/main/assets/object3d/object-viewer.html` (committed).
//
// **The guard idiom, adapted**: build-graph-room.js's guard reads a vendored file that's already
// sitting in third_party/ and fails loudly if its *shape* doesn't match what's expected — it
// never touches the network, because G6 has nothing to fetch. This script's Phase A *does* need
// npm (three + esbuild) the first time there is no vendored bundle yet — but once
// `third_party/threejs/object-viewer.bundle.min.js` exists and validates (the same
// fail-loudly-on-a-bad-shape checks as build-graph-room.js's `fail()`), Phase A is skipped
// entirely: no npm, no esbuild, no network, exactly like every run of build-graph-room.js. Delete
// the vendored bundle (or bump PINNED_THREE_VERSION) to force a rebuild.
//
// This repo's root has no package.json (see build-graph-room.js's own header) — written as plain
// CommonJS so it runs with a bare `node scripts/build-object-viewer.js`, no new root-level config
// needed. Phase A's esbuild/three come from a *scratch* npm install outside the repo (this repo
// intentionally has no root node_modules): point `OBJECT_VIEWER_BUILD_DIR` at that scratch dir
// (default `<os tmpdir>/threejs-build`), e.g.:
//   mkdir -p /tmp/threejs-build && cd /tmp/threejs-build && \
//     npm install esbuild@0.24.0 three@0.180.0 --no-save && cd - && \
//     OBJECT_VIEWER_BUILD_DIR=/tmp/threejs-build node scripts/build-object-viewer.js
//
// Output (both committed — Gradle does not run this script; it's a dev-time regeneration tool,
// same relationship build-graph-room.js has to its own outputs. Re-run by hand whenever the
// pinned three.js version changes or the bootstrap needs an edit):
//   third_party/threejs/object-viewer.bundle.min.js
//   core-engine/src/main/assets/object3d/object-viewer.html
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

const root = path.resolve(__dirname, '..');
const bundleOutPath = path.join(root, 'third_party', 'threejs', 'object-viewer.bundle.min.js');
const htmlOutPath = path.join(root, 'core-engine', 'src', 'main', 'assets', 'object3d', 'object-viewer.html');

// The pin: bump this deliberately (and re-run) to move to a newer three.js, never silently.
// Phase A fails loudly if the npm-resolved package doesn't match, same spirit as
// build-graph-room.js's "expected AntV G6 5.1.1" guard.
const PINNED_THREE_VERSION = '0.180.0';

// docs/design/objects-3d.md §2's format matrix, as (format key -> loader export name) pairs.
// USDZLoader is handled separately (see HAS_USDZ_LOADER below) since three.js only *may* ship it.
const CORE_LOADERS = [
  ['gltf/glb', 'GLTFLoader'],
  ['obj', 'OBJLoader'],
  ['obj (+mtl)', 'MTLLoader'],
  ['stl', 'STLLoader'],
  ['ply', 'PLYLoader'],
  ['fbx', 'FBXLoader'],
  ['dae (collada)', 'ColladaLoader'],
  ['3mf', 'ThreeMFLoader'],
];

const GLOBAL_NAME = '__OBJECT_VIEWER_VENDOR__';

function log(msg) {
  console.log(`build-object-viewer: ${msg}`);
}

function fail(reason) {
  throw new Error(`build-object-viewer: ${reason}`);
}

/** Same fail-loudly idiom as build-graph-room.js's `fail()` — checked instead of trusted. */
function validateBundle(text) {
  const problems = [];
  if (text.length < 400_000) {
    problems.push(`bundle looks too small (${text.length} bytes) to hold three.js + its loaders — likely truncated`);
  }
  if (!text.includes('MIT License')) problems.push('missing the hand-written MIT header');
  if (!text.includes('three.js authors')) problems.push("missing the three.js MIT header's copyright line");
  if (!text.includes(PINNED_THREE_VERSION)) problems.push(`missing the pinned "${PINNED_THREE_VERSION}" three.js version string`);
  if (!text.includes(GLOBAL_NAME)) problems.push(`missing the IIFE global export marker "${GLOBAL_NAME}"`);
  if (!text.includes('THREE')) problems.push('missing the THREE export');
  if (!text.includes('OrbitControls')) problems.push('missing the OrbitControls export');
  for (const [, exportName] of CORE_LOADERS) {
    if (!text.includes(exportName)) problems.push(`missing the ${exportName} export`);
  }
  return problems;
}

/** Phase A: build the vendored bundle via esbuild from a scratch npm install. Only runs when no
 *  valid bundle already sits in third_party/ (the "skips network" half of the guard idiom). */
function buildVendorBundle() {
  const buildDir = process.env.OBJECT_VIEWER_BUILD_DIR || path.join(os.tmpdir(), 'threejs-build');
  const esbuildModulePath = path.join(buildDir, 'node_modules', 'esbuild');
  const threePackageJsonPath = path.join(buildDir, 'node_modules', 'three', 'package.json');

  if (!fs.existsSync(esbuildModulePath) || !fs.existsSync(threePackageJsonPath)) {
    fail(
      'no vendored bundle exists yet and no scratch npm install was found at ' +
        `${buildDir} — run:\n` +
        `  mkdir -p ${buildDir} && cd ${buildDir} && ` +
        `npm install esbuild@0.24.0 three@${PINNED_THREE_VERSION} --no-save\n` +
        'then re-run this script (optionally with OBJECT_VIEWER_BUILD_DIR pointed elsewhere).',
    );
  }

  const threePkg = JSON.parse(fs.readFileSync(threePackageJsonPath, 'utf8'));
  if (threePkg.version !== PINNED_THREE_VERSION) {
    fail(
      `scratch install at ${buildDir} resolves three@${threePkg.version}, expected ` +
        `three@${PINNED_THREE_VERSION} — install the pinned version or bump PINNED_THREE_VERSION ` +
        'deliberately.',
    );
  }

  // three.js deprecated USDZLoader in favor of USDLoader (r179) but still ships it as a thin
  // subclass — docs/design/objects-3d.md §2 names USDZLoader by that name, so we use it while it
  // exists. If a future re-pin ever drops the file, this degrades to the honest "not supported
  // yet" path entirely at the HTML/bootstrap level (see buildHtml below) — no code here needs to
  // change, since the bootstrap always feature-detects `VENDOR.USDZLoader` at runtime rather than
  // assuming it was bundled.
  const usdzLoaderPath = path.join(buildDir, 'node_modules', 'three', 'examples', 'jsm', 'loaders', 'USDZLoader.js');
  const hasUsdzLoader = fs.existsSync(usdzLoaderPath);
  log(`three@${threePkg.version} resolved at ${buildDir}; USDZLoader ${hasUsdzLoader ? 'present — bundling it' : 'ABSENT — USDZ will degrade to the honest unsupported card'}.`);

  const entrySource = [
    "import * as THREE from 'three';",
    "import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';",
    "import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';",
    "import { OBJLoader } from 'three/examples/jsm/loaders/OBJLoader.js';",
    "import { MTLLoader } from 'three/examples/jsm/loaders/MTLLoader.js';",
    "import { STLLoader } from 'three/examples/jsm/loaders/STLLoader.js';",
    "import { PLYLoader } from 'three/examples/jsm/loaders/PLYLoader.js';",
    "import { FBXLoader } from 'three/examples/jsm/loaders/FBXLoader.js';",
    "import { ColladaLoader } from 'three/examples/jsm/loaders/ColladaLoader.js';",
    "import { ThreeMFLoader } from 'three/examples/jsm/loaders/3MFLoader.js';",
    hasUsdzLoader ? "import { USDZLoader } from 'three/examples/jsm/loaders/USDZLoader.js';" : '',
    '',
    'export {',
    '  THREE,',
    '  OrbitControls,',
    '  GLTFLoader,',
    '  OBJLoader,',
    '  MTLLoader,',
    '  STLLoader,',
    '  PLYLoader,',
    '  FBXLoader,',
    '  ColladaLoader,',
    '  ThreeMFLoader,',
    hasUsdzLoader ? '  USDZLoader,' : '',
    '};',
    '',
  ].filter((line) => line !== '').join('\n');

  const entryPath = path.join(buildDir, 'object-viewer-entry.generated.js');
  fs.writeFileSync(entryPath, entrySource, 'utf8');

  // eslint-disable-next-line import/no-dynamic-require, global-require
  const esbuild = require(esbuildModulePath);
  const result = esbuild.buildSync({
    entryPoints: [entryPath],
    bundle: true,
    format: 'iife',
    globalName: GLOBAL_NAME,
    minify: true,
    target: 'es2019',
    legalComments: 'none',
    write: false,
  });
  if (result.warnings.length > 0) {
    for (const w of result.warnings) log(`esbuild warning: ${w.text}`);
  }
  const bundled = result.outputFiles[0].text;

  const header = mitHeader(hasUsdzLoader);
  const withHeader = `${header}\n${bundled}\n`;

  const problems = validateBundle(withHeader);
  if (hasUsdzLoader && !withHeader.includes('USDZLoader')) problems.push('USDZLoader was supposed to be bundled but is missing from the output');
  if (problems.length > 0) {
    fail(`freshly-built bundle failed validation:\n  - ${problems.join('\n  - ')}`);
  }

  fs.mkdirSync(path.dirname(bundleOutPath), { recursive: true });
  fs.writeFileSync(bundleOutPath, withHeader, 'utf8');
  log(`wrote ${bundleOutPath} (${withHeader.length} bytes)`);
  return withHeader;
}

/** Hand-written MIT header — this vendored asset has no Maven POM for `:app:checkLicense` /
 *  `generateLicenseReport` to read (same rationale as third_party/g6/g6.min.js's own header and
 *  the NOTICE entry it required); reproduced in full there too. Two libraries are folded into
 *  this one bundle: three.js itself, and fflate (three.js's own vendored copy, pulled in
 *  transitively by GLTFLoader/3MFLoader/USDZLoader for zip/inflate) — both MIT, both reproduced
 *  below and in NOTICE. */
function mitHeader(hasUsdzLoader) {
  const usdzLine = hasUsdzLoader
    ? 'bundled — three.js ships it as a thin deprecated subclass of USDLoader (since r179); ' +
      'still exported under its original name per docs/design/objects-3d.md §2.'
    : 'NOT bundled in this build (the pinned three.js release does not ship it) — ' +
      'core-engine/src/main/assets/object3d/object-viewer.html degrades USDZ loads to an honest ' +
      '"preview not supported yet" card instead of pretending to render one.';
  return `/*!
 * three.js v${PINNED_THREE_VERSION} + examples/jsm addons — https://threejs.org/
 *
 * Bundled (not vendored verbatim as a single upstream file — unlike AntV G6's prebuilt UMD,
 * three.js ships as ES module source) by scripts/build-object-viewer.js via esbuild, from the
 * npm package three@${PINNED_THREE_VERSION}, for docs/design/objects-3d.md §3's offline object
 * viewer (core-engine/src/main/assets/object3d/object-viewer.html, loaded by
 * dev.fonebrew.ui.object3d.ObjectViewerRoom). Provenance: OnDevice-only bundling, no telemetry,
 * no network access baked into anything below (binding constraint 1) — verified by
 * ObjectViewerAssetTest and this build script's own guard.
 *
 * Included: three.js core, examples/jsm/controls/OrbitControls.js, and the loaders named in
 * docs/design/objects-3d.md §2's format matrix — GLTFLoader, OBJLoader, MTLLoader, STLLoader,
 * PLYLoader, FBXLoader, ColladaLoader, and 3MFLoader (exported here as ThreeMFLoader, its actual
 * class name).
 *
 * USDZLoader: ${usdzLine}
 *
 * MIT License
 *
 * Copyright © 2010-2025 three.js authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * ---
 *
 * fflate v0.8.2 (as vendored inside three.js's own examples/jsm/libs/fflate.module.js) —
 * fast JavaScript compression/decompression, <https://101arrowz.github.io/fflate>. Pulled in
 * transitively by GLTFLoader (embedded-image bufferViews), 3MFLoader, and USDZLoader (both zip
 * containers) for synchronous zip/inflate only — this bundle never reaches fflate's async
 * Worker-based path (nothing here calls it), so no Worker/Blob-URL script execution is ever
 * spawned by this file, only inert plain-data unzip/inflate calls.
 *
 * MIT License
 *
 * Copyright (c) 2026 Arjun Barrett
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */`;
}

// Content-Security-Policy: the tightest policy that still lets the page run. Differs from
// build-graph-room.js's CSP in exactly one directive: img-src additionally allows `blob:`, because
// GLTFLoader/3MFLoader/USDZLoader materialize embedded-texture bytes as local Blob URLs (an
// in-memory, same-origin-scoped virtual URL — never a network fetch) to hand to an <img> element;
// blockNetworkLoads on the WebView side (ObjectViewerRoom.kt) is the second, independent layer of
// the same guarantee that a `blob:` URL can never resolve to anything network-sourced.
// connect-src/frame-src/object-src/worker-src/media-src 'none' — this page never opens a network
// connection, iframe, plugin object, worker, or audio/video element under any circumstance.
const CSP =
  "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
  "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'none'; frame-src 'none'; " +
  "object-src 'none'; worker-src 'none'; media-src 'none'; base-uri 'none'; form-action 'none';";

// The bootstrap: additive only, like build-graph-room.js's own BOOTSTRAP — never touches the
// vendored library's code, only calls its public API. Two entry points:
//   - `window.__objectViewer.load(kind, payload, format)`, called from Kotlin via
//     WebView.evaluateJavascript — `kind` is "model" (payload = base64 file bytes, `format` picks
//     the loader) or "procedural" (payload = ProceduralScene DSL JSON text, docs/design/
//     objects-3d.md §4; `format` unused). Mirrors GraphWebRoom's `window.__graphRoom.load(json)`
//     shape.
//   - `window.AndroidObjectViewer` — NOT defined by this file; it is the narrow
//     `@JavascriptInterface` bridge `dev.fonebrew.ui.object3d.ObjectViewerRoom` registers on the
//     Android side (the ONE divergence from GraphWebRoom's zero-bridge design — see that file's
//     KDoc). This bootstrap only ever *calls* two of its methods (`reportSelfTest`,
//     `reportLoad`), always guarded with `window.AndroidObjectViewer &&`, so the page still runs
//     standalone (e.g. opened directly in a desktop browser during development) when the bridge
//     doesn't exist.
function buildBootstrap(hasUsdzLoaderHint) {
  return `
(function () {
  'use strict';

  var VENDOR = window.${GLOBAL_NAME};
  var THREE = VENDOR.THREE;
  var OrbitControls = VENDOR.OrbitControls;
  var GLTFLoader = VENDOR.GLTFLoader;
  var OBJLoader = VENDOR.OBJLoader;
  var STLLoader = VENDOR.STLLoader;
  var PLYLoader = VENDOR.PLYLoader;
  var FBXLoader = VENDOR.FBXLoader;
  var ColladaLoader = VENDOR.ColladaLoader;
  var ThreeMFLoader = VENDOR.ThreeMFLoader;
  // MTLLoader is bundled (docs/design/objects-3d.md §2's "OBJ (+MTL)" row names it as the OBJ
  // format's loader pair) but this viewer's fixed 3-arg bridge entry point — load(kind, payload,
  // format) — has no second payload channel for a companion .mtl file yet. OBJ loads geometry
  // only, with a neutral studio material, until a future bridge revision adds one; this is
  // stated plainly in the status/report text below rather than silently claiming full fidelity.
  // USDZLoader is feature-detected at *runtime* (VENDOR.USDZLoader may be undefined if the
  // pinned three.js release this bundle was built from didn't ship it — see
  // scripts/build-object-viewer.js) rather than assumed present, so this bootstrap is correct
  // either way without needing to know which branch built it.

  function setStatus(text) {
    var el = document.getElementById('object-viewer-status');
    if (el) el.textContent = text;
  }

  function reportSelfTest(ok, detail) {
    if (window.AndroidObjectViewer && window.AndroidObjectViewer.reportSelfTest) {
      window.AndroidObjectViewer.reportSelfTest(ok, String(detail || ''));
    }
  }

  function reportLoad(ok, detail) {
    setStatus(detail || (ok ? 'loaded' : 'load failed'));
    if (window.AndroidObjectViewer && window.AndroidObjectViewer.reportLoad) {
      window.AndroidObjectViewer.reportLoad(ok, String(detail || ''));
    }
  }

  function describeError(err) {
    return err && err.message ? err.message : String(err);
  }

  // ---- WebGL context-creation self-test (docs/design/objects-3d.md §3) ----------------------
  // Runs BEFORE any THREE.WebGLRenderer is constructed. If no context can be created, this
  // reports failure through the bridge and returns null — the caller must not proceed to render
  // anything. This is the guard against "the prior Hyle 1198515 garbling": never draw with a
  // context that failed to come up cleanly.
  function webglSelfTest(canvas) {
    var ctx = null;
    var contextType = null;
    try {
      ctx = canvas.getContext('webgl2', { failIfMajorPerformanceCaveat: false });
      if (ctx) contextType = 'webgl2';
    } catch (e) { /* fall through to webgl1 */ }
    if (!ctx) {
      try {
        ctx = canvas.getContext('webgl', { failIfMajorPerformanceCaveat: false });
        if (ctx) contextType = 'webgl';
      } catch (e) { /* fall through to failure */ }
    }
    return { ctx: ctx, contextType: contextType };
  }

  var scene, camera, renderer, controls, contentRoot;

  function initViewer() {
    var canvas = document.getElementById('object-viewer-canvas');
    var probe = webglSelfTest(canvas);
    if (!probe.ctx) {
      setStatus('WebGL unavailable on this device');
      reportSelfTest(false, 'no webgl2/webgl context could be created');
      return false;
    }
    try {
      renderer = new THREE.WebGLRenderer({ canvas: canvas, context: probe.ctx, antialias: true, alpha: false });
    } catch (err) {
      setStatus('WebGL unavailable on this device');
      reportSelfTest(false, 'WebGLRenderer construction failed: ' + describeError(err));
      return false;
    }
    reportSelfTest(true, probe.contextType);

    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setSize(window.innerWidth, window.innerHeight, false);
    renderer.setClearColor(0x14151a, 1);

    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(45, window.innerWidth / window.innerHeight, 0.01, 1000);
    camera.position.set(2.4, 1.8, 2.4);

    // Neutral studio lighting — no single dominant colour so this reads as a preview stage, not
    // a scene-specific mood.
    scene.add(new THREE.HemisphereLight(0xffffff, 0x3a3d47, 1.0));
    var key = new THREE.DirectionalLight(0xffffff, 1.4);
    key.position.set(3, 5, 4);
    scene.add(key);
    var fill = new THREE.DirectionalLight(0xffffff, 0.55);
    fill.position.set(-4, 2, -3);
    scene.add(fill);
    var rim = new THREE.DirectionalLight(0xffffff, 0.35);
    rim.position.set(0, 3, -5);
    scene.add(rim);

    // Grid ground.
    var grid = new THREE.GridHelper(10, 20, 0x5b6577, 0x2b2e37);
    scene.add(grid);

    controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.minDistance = 0.05;
    controls.maxDistance = 200;

    contentRoot = new THREE.Group();
    scene.add(contentRoot);

    window.addEventListener('resize', function () {
      camera.aspect = window.innerWidth / window.innerHeight;
      camera.updateProjectionMatrix();
      renderer.setSize(window.innerWidth, window.innerHeight, false);
    });

    (function animate() {
      requestAnimationFrame(animate);
      controls.update();
      renderer.render(scene, camera);
    })();

    return true;
  }

  function clearScene() {
    if (!contentRoot) return;
    for (var i = contentRoot.children.length - 1; i >= 0; i--) {
      contentRoot.remove(contentRoot.children[i]);
    }
  }

  function frameObject(object3d) {
    var box = new THREE.Box3().setFromObject(object3d);
    if (box.isEmpty()) return;
    var size = box.getSize(new THREE.Vector3());
    var center = box.getCenter(new THREE.Vector3());
    var radius = Math.max(size.x, size.y, size.z, 0.001) * 0.5;
    var distance = radius / Math.sin((camera.fov * Math.PI) / 360) * 1.4;
    controls.target.copy(center);
    camera.position.copy(center).add(new THREE.Vector3(distance, distance * 0.7, distance));
    camera.near = Math.max(distance / 100, 0.01);
    camera.far = Math.max(distance * 100, 100);
    camera.updateProjectionMatrix();
    controls.update();
  }

  function addToScene(object3d) {
    clearScene();
    contentRoot.add(object3d);
    frameObject(object3d);
  }

  // ---- base64 bridge payload decoding --------------------------------------------------------
  // Model bytes always arrive as base64 (docs/design/objects-3d.md §3: "no file:// widening").
  // Kotlin refuses anything over 32 MB before it ever reaches this bridge call.
  function decodeBase64ToArrayBuffer(base64) {
    var binary = atob(base64);
    var len = binary.length;
    var bytes = new Uint8Array(len);
    for (var i = 0; i < len; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
  }

  function decodeBase64ToText(base64) {
    return new TextDecoder('utf-8').decode(decodeBase64ToArrayBuffer(base64));
  }

  // ---- model-format loaders (docs/design/objects-3d.md §2's format matrix) -------------------
  function loadModel(payload, format) {
    var fmt = String(format || '').toLowerCase();
    switch (fmt) {
      case 'gltf':
      case 'glb': {
        var buf = decodeBase64ToArrayBuffer(payload);
        new GLTFLoader().parse(buf, '', function (gltf) {
          addToScene(gltf.scene || gltf.scenes[0]);
          reportLoad(true, 'gltf/glb loaded');
        }, function (err) {
          reportLoad(false, 'gltf/glb parse failed: ' + describeError(err));
        });
        return;
      }
      case 'obj': {
        var text = decodeBase64ToText(payload);
        var obj = new OBJLoader().parse(text);
        addToScene(obj);
        reportLoad(true, 'obj loaded (geometry only — MTL companion loading is not wired through this bridge call yet)');
        return;
      }
      case 'stl': {
        var stlBuf = decodeBase64ToArrayBuffer(payload);
        var geometry = new STLLoader().parse(stlBuf);
        geometry.computeVertexNormals();
        var stlMesh = new THREE.Mesh(geometry, new THREE.MeshStandardMaterial({ color: 0x9aa5b8, roughness: 0.55, metalness: 0.05 }));
        addToScene(stlMesh);
        reportLoad(true, 'stl loaded');
        return;
      }
      case 'ply': {
        var plyBuf = decodeBase64ToArrayBuffer(payload);
        var plyGeometry = new PLYLoader().parse(plyBuf);
        plyGeometry.computeVertexNormals();
        var hasColor = !!(plyGeometry.attributes && plyGeometry.attributes.color);
        var plyObject;
        if (plyGeometry.index) {
          var plyMat = hasColor
            ? new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.6 })
            : new THREE.MeshStandardMaterial({ color: 0x9aa5b8, roughness: 0.6 });
          plyObject = new THREE.Mesh(plyGeometry, plyMat);
        } else {
          var pointsMat = hasColor
            ? new THREE.PointsMaterial({ size: 0.01, vertexColors: true })
            : new THREE.PointsMaterial({ size: 0.01, color: 0x9aa5b8 });
          plyObject = new THREE.Points(plyGeometry, pointsMat);
        }
        addToScene(plyObject);
        reportLoad(true, 'ply loaded');
        return;
      }
      case 'fbx': {
        var fbxBuf = decodeBase64ToArrayBuffer(payload);
        var fbxObject = new FBXLoader().parse(fbxBuf, '');
        addToScene(fbxObject);
        reportLoad(true, 'fbx loaded');
        return;
      }
      case 'dae': {
        var daeText = decodeBase64ToText(payload);
        var collada = new ColladaLoader().parse(daeText, '');
        addToScene(collada.scene);
        reportLoad(true, 'dae (collada) loaded');
        return;
      }
      case '3mf': {
        var mfBuf = decodeBase64ToArrayBuffer(payload);
        var mfObject = new ThreeMFLoader().parse(mfBuf);
        addToScene(mfObject);
        reportLoad(true, '3mf loaded');
        return;
      }
      case 'usdz': {
        if (!VENDOR.USDZLoader) {
          setStatus('USDZ preview not supported yet');
          reportLoad(false, 'USDZ preview not supported yet — this build\\'s vendored three.js does not include USDZLoader');
          return;
        }
        var usdBuf = decodeBase64ToArrayBuffer(payload);
        var usdObject = new VENDOR.USDZLoader().parse(usdBuf);
        addToScene(usdObject);
        reportLoad(true, 'usdz loaded (best-effort — three.js USDZ support is partial per docs/design/objects-3d.md §2)');
        return;
      }
      default:
        setStatus('preview not supported yet for "' + fmt + '"');
        reportLoad(false, 'unsupported format "' + fmt + '" — preview not supported yet');
    }
  }

  // ---- ProceduralScene DSL (docs/design/objects-3d.md §4) ------------------------------------
  // Wire shape is dev.fonebrew.domain.object3d.ProceduralSceneCodec's exact org.json output —
  // this bootstrap is that codec's one and only consumer, so it matches it field-for-field
  // rather than inventing a parallel shape:
  //   { "schemaVersion": "1.0.0", "ops": [ {
  //       "id": string, "kind": "BOX"|"SPHERE"|"CYLINDER"|"CONE"|"TORUS"|"PLANE",
  //       "transform": { "translate": {x,y,z}, "rotateDeg": {x,y,z}, "scale": {x,y,z} },
  //       "colorHex": "#RRGGBB" | "#RRGGBBAA" | null,
  //       "params": { <kind-specific keys, see PARAMS_BY_KIND below> },
  //       "group": string | null,
  //   } ] }
  // "union grouping" (docs/design/objects-3d.md §4) is the "group" field, not a nested op: ops
  // sharing a non-null "group" value are collected into one THREE.Group (dev.fonebrew.domain.
  // object3d.ProceduralScene.kt's own KDoc: "ops sharing a non-null group value are unioned by
  // the viewer") — a visual grouping only, no CSG boolean geometry union; real mesh boolean ops
  // are an explicit v1 non-goal per docs/design/objects-3d.md §9.
  //
  // Required params per kind mirror ProceduralSceneValidator.REQUIRED_PARAMS exactly (the
  // validator already refused anything missing these before this scene ever reached the bridge,
  // but the "|| <default>" fallbacks below are cheap insurance against a hand-authored or
  // future-relaxed scene skipping validation).
  function applyColorHexToMaterial(material, colorHex) {
    if (!colorHex) { material.color.setHex(0x8a93a6); return; }
    var rgb = colorHex.length >= 7 ? colorHex.slice(0, 7) : colorHex;
    try { material.color.set(rgb); } catch (e) { material.color.setHex(0x8a93a6); }
    if (colorHex.length === 9) {
      var alpha = parseInt(colorHex.slice(7, 9), 16);
      if (!isNaN(alpha)) {
        material.transparent = true;
        material.opacity = alpha / 255;
      }
    }
  }

  function applyTransform(object3d, t) {
    if (!t) return;
    var translate = t.translate || {};
    var rotateDeg = t.rotateDeg || {};
    var scale = t.scale || {};
    object3d.position.set(translate.x || 0, translate.y || 0, translate.z || 0);
    // rotateDeg is DEGREES (the field name says so, and so does ProceduralScene.kt's KDoc) —
    // three.js Euler angles are radians, so convert.
    object3d.rotation.set(
      ((rotateDeg.x || 0) * Math.PI) / 180,
      ((rotateDeg.y || 0) * Math.PI) / 180,
      ((rotateDeg.z || 0) * Math.PI) / 180,
    );
    object3d.scale.set(
      scale.x != null ? scale.x : 1,
      scale.y != null ? scale.y : 1,
      scale.z != null ? scale.z : 1,
    );
  }

  function buildPrimitiveMesh(op) {
    var p = op.params || {};
    var geometry;
    switch (op.kind) {
      case 'BOX':
        geometry = new THREE.BoxGeometry(p.width || 1, p.height || 1, p.depth || 1);
        break;
      case 'SPHERE':
        geometry = new THREE.SphereGeometry(p.radius || 0.5, 24, 16);
        break;
      case 'CYLINDER':
        // ProceduralSceneValidator only requires a single uniform "radius" (+ "height") for
        // CYLINDER — not separate radiusTop/radiusBottom — so both ends use the same value.
        geometry = new THREE.CylinderGeometry(p.radius || 0.5, p.radius || 0.5, p.height || 1, 24);
        break;
      case 'CONE':
        geometry = new THREE.ConeGeometry(p.radius || 0.5, p.height || 1, 24);
        break;
      case 'TORUS':
        geometry = new THREE.TorusGeometry(p.radius || 0.5, p.tubeRadius || 0.15, 16, 48);
        break;
      case 'PLANE':
        geometry = new THREE.PlaneGeometry(p.width || 1, p.height || 1);
        break;
      default:
        throw new Error('unknown ProceduralScene op kind: ' + op.kind);
    }
    var material = new THREE.MeshStandardMaterial({ roughness: 0.55, metalness: 0.05 });
    applyColorHexToMaterial(material, op.colorHex);
    var mesh = new THREE.Mesh(geometry, material);
    applyTransform(mesh, op.transform);
    if (op.id) mesh.name = op.id;
    return mesh;
  }

  function loadProcedural(payload) {
    var sceneJson = JSON.parse(payload);
    var ops = sceneJson.ops || [];
    var root = new THREE.Group();
    var groups = {}; // group key -> THREE.Group, first-seen order
    ops.forEach(function (op) {
      var mesh = buildPrimitiveMesh(op);
      if (op.group) {
        var g = groups[op.group];
        if (!g) {
          g = new THREE.Group();
          g.name = op.group;
          groups[op.group] = g;
          root.add(g);
        }
        g.add(mesh);
      } else {
        root.add(mesh);
      }
    });
    addToScene(root);
    reportLoad(true, 'procedural scene: ' + ops.length + ' op(s), ' + Object.keys(groups).length + ' group(s)');
  }

  window.__objectViewer = {
    load: function (kind, payload, format) {
      try {
        if (!scene && !initViewer()) return; // self-test already reported failure
        if (kind === 'procedural') {
          loadProcedural(payload);
        } else if (kind === 'model') {
          loadModel(payload, format);
        } else {
          reportLoad(false, 'unknown load kind: ' + kind);
        }
      } catch (err) {
        reportLoad(false, 'load failed: ' + describeError(err));
      }
    },
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { initViewer(); });
  } else {
    initViewer();
  }

  setStatus('waiting for a model\\u2026');
})();
`;
}

function buildHtml(bundleText) {
  const hasUsdzLoader = bundleText.includes('USDZLoader');
  const bootstrap = buildBootstrap(hasUsdzLoader);

  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, user-scalable=no">
<meta http-equiv="Content-Security-Policy" content="${CSP}">
<title>Fonebrew — Object Viewer</title>
<style>
  html, body { margin: 0; height: 100%; background: #14151a; overflow: hidden; }
  #object-viewer-canvas { position: fixed; inset: 0; width: 100vw; height: 100vh; display: block; touch-action: none; }
  #object-viewer-status {
    position: fixed; left: 8px; bottom: 8px; z-index: 10;
    font: 11px/1.4 -apple-system, system-ui, sans-serif; color: #9aa0a6;
    background: rgba(0, 0, 0, .45); padding: 4px 8px; border-radius: 6px; pointer-events: none;
  }
</style>
</head>
<body>
<canvas id="object-viewer-canvas"></canvas>
<div id="object-viewer-status">waiting for a model&hellip;</div>
<!-- BEGIN VENDORED THREE.JS (third_party/threejs/object-viewer.bundle.min.js, built by scripts/build-object-viewer.js from npm three@${PINNED_THREE_VERSION} via esbuild; MIT header prepended verbatim) -->
<script>
${bundleText}
</script>
<!-- END VENDORED THREE.JS -->
<!-- BEGIN OBJECT VIEWER BOOTSTRAP (additive; not part of the vendored library) -->
<script>
${bootstrap}
</script>
<!-- END OBJECT VIEWER BOOTSTRAP -->
</body>
</html>
`;

  fs.mkdirSync(path.dirname(htmlOutPath), { recursive: true });
  fs.writeFileSync(htmlOutPath, html, 'utf8');
  log(`wrote ${htmlOutPath} (${html.length} bytes; USDZLoader ${hasUsdzLoader ? 'bundled' : 'absent — honest-failure card wired for usdz'})`);
}

function main() {
  let bundleText;
  if (fs.existsSync(bundleOutPath)) {
    const existing = fs.readFileSync(bundleOutPath, 'utf8');
    const problems = validateBundle(existing);
    if (problems.length === 0) {
      log(`${bundleOutPath} already exists and validates — skipping npm/esbuild entirely.`);
      bundleText = existing;
    } else {
      log(`${bundleOutPath} exists but failed validation (${problems.join('; ')}) — rebuilding.`);
      bundleText = buildVendorBundle();
    }
  } else {
    log(`${bundleOutPath} does not exist yet — building via esbuild.`);
    bundleText = buildVendorBundle();
  }

  buildHtml(bundleText);
}

main();
