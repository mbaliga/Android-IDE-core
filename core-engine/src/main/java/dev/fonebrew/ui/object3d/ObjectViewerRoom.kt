package dev.fonebrew.ui.object3d

import android.annotation.SuppressLint
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fonebrew.domain.object3d.Object3dFormat
import dev.fonebrew.domain.object3d.ProceduralScene
import dev.fonebrew.domain.object3d.ProceduralSceneCodec
import org.json.JSONObject

/**
 * docs/design/objects-3d.md §3 — the offline object viewer. Loads the single committed asset
 * `core-engine/src/main/assets/object3d/object-viewer.html` (`scripts/build-object-viewer.js`'s
 * output — see [ObjectViewerAssetTest] for what that file is asserted to hold) and pushes
 * [content] into it once via `window.__objectViewer.load(kind, payload, format)`.
 *
 * Mirrors [dev.fonebrew.ui.graph.GraphWebRoom]'s WebView lockdown **verbatim** — same
 * `blockNetworkLoads = true`, same `allowFileAccess = false` / `allowContentAccess = false`, same
 * always-block-navigation [WebViewClient.shouldOverrideUrlLoading] / defense-in-depth
 * [WebViewClient.shouldInterceptRequest] asset-path allowlist. See that file's own KDoc for the
 * full rationale of each setting; it is not repeated here.
 *
 * ### The one divergence: this WebView needs WebGL (docs/design/objects-3d.md §3)
 * [dev.fonebrew.ui.graph.GraphWebRoom]'s G6 room is deliberately Canvas2D-only — binding
 * constraint 7's renderer pin, put in place after the prior WebView-in-WebView GPU garbling
 * (Hyle commit `1198515`). A 3D object viewer cannot honor that pin; three.js requires a real
 * WebGL context. So, and ONLY here: hardware acceleration is left on for this WebView (never
 * downgraded to [View.LAYER_TYPE_SOFTWARE], and explicitly stamped [View.LAYER_TYPE_HARDWARE]
 * below so the requirement is visible in code, not just left to the platform default), and the
 * asset itself runs a WebGL context-creation self-test *before* ever constructing a
 * `THREE.WebGLRenderer` (see `scripts/build-object-viewer.js`'s bootstrap:
 * `webglSelfTest`/`initViewer`). That self-test result is the reason this file — unlike
 * `GraphWebRoom`, which registers no `@JavascriptInterface` at all ("the narrowest possible
 * bridge is one that doesn't exist") — registers [ObjectViewerBridge]: a two-method, write-only
 * JS-to-Kotlin channel that exists *specifically* to carry the self-test/load result back out, so
 * a failed context creation renders the honest [FailureCard] below instead of leaving whatever a
 * half-constructed WebGL surface would have drawn on screen — the direct fix for the 1198515
 * garbling, applied at the boundary where garbling can no longer happen structurally. The bridge
 * carries nothing else: no file access, no arbitrary method invocation, two booleans and two
 * strings, always Kotlin-reads-only.
 *
 * Per "Environment honesty" (CLAUDE.md): this container has no device and cannot render a WebView
 * or create a real WebGL context — everything above is a structural/config guarantee, checked by
 * [ObjectViewerAssetTest] and this file's own settings; the actual on-device WebGL
 * render/orbit/zoom/pan feel is owner-verified only, per docs/design/objects-3d.md §7's
 * owner-verify list.
 */
@Composable
fun ObjectViewerRoom(content: ObjectViewerContent, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                ObjectViewerRoomBody(content = content, onClose = onClose)
            }
        }
    }
}

/** What [ObjectViewerRoom] can show — either a model file's raw bytes (§2's format matrix) or an
 *  already-decoded [ProceduralScene] (§4's on-device DSL path). Deliberately a plain domain value,
 *  not a tree node — no message-tree-wired `Object3dNode` type exists yet (a later WP); this stays
 *  the same "take a plain domain value, not something wired to the tree" shape
 *  [dev.fonebrew.ui.graph.GraphWebRoom] uses for its own `ThreadGraph` parameter. */
sealed class ObjectViewerContent {
    data class Model(val bytes: ByteArray, val format: Object3dFormat) : ObjectViewerContent()
    data class Procedural(val scene: ProceduralScene) : ObjectViewerContent()
}

/** §3: "Model bytes... >32 MB refused with an honest size message (base64 inflation is real;
 *  `WebViewAssetLoader` is the documented optimization if the cap bites)." Checked before the
 *  bridge is ever touched — an oversized model never reaches `evaluateJavascript`, base64 encoding,
 *  or the WebView at all. */
const val MAX_MODEL_BYTES: Long = 32L * 1024 * 1024

@Composable
private fun ObjectViewerRoomBody(content: ObjectViewerContent, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("‹ Back") }
            Text("Object viewer", style = MaterialTheme.typography.titleMedium)
            // Balances the "‹ Back" button so the title stays centred — no action here, matching
            // GraphWebRoom's own empty-Text balancing slot.
            Text("", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        Box(Modifier.fillMaxSize()) {
            val oversizeBytes = (content as? ObjectViewerContent.Model)?.bytes?.size?.toLong()?.takeIf { it > MAX_MODEL_BYTES }
            if (oversizeBytes != null) {
                FailureCard(
                    "This model is ${oversizeBytes / (1024 * 1024)} MB, over the ${MAX_MODEL_BYTES / (1024 * 1024)} MB " +
                        "preview limit — it was never sent to the viewer.",
                )
            } else {
                ObjectViewerWebView(content = content, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun FailureCard(message: String) {
    Box(Modifier.fillMaxSize()) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ObjectViewerWebView(content: ObjectViewerContent, modifier: Modifier = Modifier) {
    var pageLoaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var webglAvailable by remember { mutableStateOf<Boolean?>(null) }
    var loadDetail by remember { mutableStateOf<String?>(null) }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

                    // Explicit, not left to the platform default — see this file's own KDoc
                    // ("The one divergence"): three.js needs a real GPU-backed WebGL context, so
                    // unlike a Canvas2D-only room this view must never be downgraded to a
                    // software layer.
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)

                    // ---- Lockdown, verbatim from GraphWebRoom.kt (see that file's KDoc) --------
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.setGeolocationEnabled(false)
                    settings.setSupportZoom(false) // OrbitControls owns the gesture; no second zoom layer
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    // ---- The narrow bridge (this file's one divergence from GraphWebRoom) ------
                    addJavascriptInterface(
                        ObjectViewerBridge(
                            onSelfTest = { ok, detail -> webglAvailable = ok; if (!ok) loadDetail = detail },
                            onLoadResult = { ok, detail -> loadDetail = if (ok) null else detail },
                        ),
                        BRIDGE_NAME,
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            pageLoaded = true
                            view.evaluateJavascript(pushCallFor(content), null)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) loadError = error.description?.toString() ?: "failed to load the object viewer"
                        }

                        // Always-block navigation: the page itself must never navigate anywhere.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                        @Deprecated("Deprecated in Java, still called on API < 24")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = true

                        // Defense in depth: only this page's own asset path may ever be served.
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            return if (url.startsWith(ASSET_URL_PREFIX)) {
                                super.shouldInterceptRequest(view, request)
                            } else {
                                WebResourceResponse(null, null, null)
                            }
                        }
                    }

                    loadUrl(ASSET_URL)
                }
            },
            update = { webView ->
                // Re-push on recomposition too (a no-op via the page's own `window.__objectViewer &&`
                // guard until onPageFinished has already run once) — covers [content] changing after
                // the first load without needing a second WebView.
                if (pageLoaded) webView.evaluateJavascript(pushCallFor(content), null)
            },
        )

        when {
            webglAvailable == false ->
                FailureCard("WebGL unavailable on this device.${loadDetail?.let { " ($it)" } ?: ""}")
            loadError != null ->
                FailureCard("Couldn't load the object viewer: $loadError")
            !pageLoaded ->
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * The narrow `@JavascriptInterface` bridge (see this file's class KDoc — the one divergence from
 * [dev.fonebrew.ui.graph.GraphWebRoom]'s zero-bridge design). Two write-only methods, nothing
 * else: no getters, no file/content access, no ability to invoke anything beyond handing a
 * (boolean, string) status pair back to Kotlin. Method names match exactly what
 * `scripts/build-object-viewer.js`'s bootstrap calls (`window.AndroidObjectViewer.reportSelfTest`
 * / `.reportLoad`), guarded there with `window.AndroidObjectViewer &&` so the page still runs
 * standalone outside this WebView (e.g. opened in a desktop browser during development).
 */
private class ObjectViewerBridge(
    private val onSelfTest: (ok: Boolean, detail: String) -> Unit,
    private val onLoadResult: (ok: Boolean, detail: String) -> Unit,
) {
    @JavascriptInterface
    fun reportSelfTest(ok: Boolean, detail: String) {
        onSelfTest(ok, detail)
    }

    @JavascriptInterface
    fun reportLoad(ok: Boolean, detail: String) {
        onLoadResult(ok, detail)
    }
}

private const val BRIDGE_NAME = "AndroidObjectViewer"
private const val ASSET_URL_PREFIX = "file:///android_asset/object3d/"
private const val ASSET_URL = ASSET_URL_PREFIX + "object-viewer.html"

/** docs/design/objects-3d.md §2's format matrix, mapped to the lower-case format string
 *  `scripts/build-object-viewer.js`'s bootstrap dispatches on. [Object3dFormat.STL_ASCII] and
 *  [Object3dFormat.STL_BINARY] both map to `"stl"` — three.js's `STLLoader.parse` auto-detects
 *  ASCII vs binary from the bytes themselves, so the viewer bridge does not need to distinguish
 *  them (only [dev.fonebrew.domain.object3d.Object3dFormatSniffer] does, for its own
 *  provenance-labelling reasons upstream of this file). */
private fun Object3dFormat.toBridgeFormat(): String = when (this) {
    Object3dFormat.GLB -> "glb"
    Object3dFormat.GLTF -> "gltf"
    Object3dFormat.OBJ -> "obj"
    Object3dFormat.STL_ASCII, Object3dFormat.STL_BINARY -> "stl"
    Object3dFormat.PLY -> "ply"
    Object3dFormat.FBX -> "fbx"
    Object3dFormat.DAE -> "dae"
    Object3dFormat.THREE_MF -> "3mf"
    Object3dFormat.USDZ -> "usdz"
}

/** [JSONObject.quote] produces a properly backslash/quote/control-char-escaped JS string literal
 *  (with its own surrounding quotes) — safe to splice into an `evaluateJavascript` call even
 *  though the payload is untrusted-shaped data, because it's never interpreted as anything but a
 *  JS string literal (and, for the procedural case, further decoded by the page's own
 *  `JSON.parse`) by the receiving bootstrap. Mirrors [dev.fonebrew.ui.graph.GraphWebRoom]'s own
 *  `pushCall` helper. */
private fun pushCallFor(content: ObjectViewerContent): String = when (content) {
    is ObjectViewerContent.Model -> {
        val base64 = Base64.encodeToString(content.bytes, Base64.NO_WRAP)
        "window.__objectViewer && window.__objectViewer.load('model', " +
            "${JSONObject.quote(base64)}, ${JSONObject.quote(content.format.toBridgeFormat())});"
    }
    is ObjectViewerContent.Procedural -> {
        val sceneJson = ProceduralSceneCodec.toJsonString(content.scene)
        "window.__objectViewer && window.__objectViewer.load('procedural', ${JSONObject.quote(sceneJson)}, null);"
    }
}
