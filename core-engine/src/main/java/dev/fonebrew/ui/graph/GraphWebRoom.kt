package dev.fonebrew.ui.graph

import android.annotation.SuppressLint
import android.view.ViewGroup
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.fonebrew.domain.thread.ThreadGraph
import dev.fonebrew.domain.thread.ThreadGraphJson
import dev.fonebrew.ui.theme.LocalHyleColors
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * **WP11** — the G6 "deep graph room" (THREAD_TOPOLOGY_PLAN.md's hybrid-rendering decision:
 * native Compose for the spine/tree/instruments, G6-in-a-locked-down-WebView here for the one
 * surface that benefits from a real graph-visualization engine's layout/interaction machinery).
 * Opened from [GraphRoom]'s "Deep view" entry point as a second full-screen overlay on top of it
 * (mirrors [GraphRoom]'s own relationship to `TreeRoom`'s "Graph" chip — each step opens its own
 * `Dialog` rather than replacing the previous screen's content, so "Back" always returns exactly
 * one step). [dev.fonebrew.domain.disclosure.Surface.GRAPH_DEEP] gates the entry point itself; this
 * composable does not re-check disclosure — same split [GraphRoom] draws with its own caller.
 *
 * Loads the single committed asset `core-engine/src/main/assets/graph/graph-room.html`
 * (`scripts/build-graph-room.js`'s output — see [dev.fonebrew.ui.graph.GraphRoomAssetTest] for what
 * that file is asserted to hold) and pushes the current [graph] into it once via
 * `window.__graphRoom.load(json)`, using
 * [dev.fonebrew.domain.thread.ThreadGraphJson.toJsonString] — the exact forward pointer that
 * function's own KDoc names as its "future WP11 bridge call".
 *
 * ### WebView lockdown (binding constraint 7 — first WebView config in this codebase)
 * - **`blockNetworkLoads = true`**: no request this page's script could ever issue (the vendored
 *   G6 body's own few inert third-party URL strings included — see
 *   [dev.fonebrew.ui.graph.GraphRoomAssetTest]'s KDoc) can reach the network, full stop.
 * - **`allowFileAccess = false` / `allowContentAccess = false`**: no access to the device's
 *   general filesystem or content providers. The one page this WebView ever loads is
 *   `file:///android_asset/graph/graph-room.html`, reachable regardless of `allowFileAccess`
 *   ([WebSettings.setAllowFileAccess]'s own doc: "Assets and resources are still accessible using
 *   file:///android_asset and file:///android_res" — a distinct, always-on special scheme, not
 *   general file-system access).
 * - **Always-block navigation**: [WebViewClient.shouldOverrideUrlLoading] unconditionally returns
 *   `true` (refuse) for every navigation the *page* attempts — this app's own initial [loadUrl]
 *   call is not itself a "navigation" in this hook's sense, so the first load still happens; only
 *   a stray link tap or script-driven redirect afterward would be blocked, and there are none in
 *   this page. [WebViewClient.shouldInterceptRequest] is a second, defense-in-depth layer: any
 *   resource request that isn't for this page's own asset path gets an empty response rather than
 *   reaching Android's normal resource-loading machinery at all.
 * - **Narrow bridge, one direction**: no `addJavascriptInterface` is registered anywhere in this
 *   file — [WebView.evaluateJavascript] is a one-way Kotlin-to-JS push (arbitrary JS execution
 *   *by this app*, not a JS-callable surface exposed *to the page*), which is the narrowest
 *   possible bridge: there is nothing for the page's own script to call back into native code
 *   with, even if it wanted to.
 *
 * Per "Environment honesty" (CLAUDE.md / THREAD_TOPOLOGY_PLAN.md's own verification section):
 * this container has no device and cannot render a WebView or execute G6's Canvas2D drawing —
 * everything above is a structural/config guarantee, checked by [GraphRoomAssetTest] and this
 * file's own settings; the actual on-device touch/pan/zoom/render feel is owner-verified only.
 */
@Composable
fun GraphWebRoom(graph: ThreadGraph, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                GraphWebRoomBody(graph = graph, onClose = onClose)
            }
        }
    }
}

@Composable
private fun GraphWebRoomBody(graph: ThreadGraph, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("‹ Back") }
            Text("Deep view", style = MaterialTheme.typography.titleMedium)
            // Balances the "‹ Back" button so the title stays centred — no action here, unlike
            // GraphRoom's "Observations" button in the same slot.
            Text("", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        Box(Modifier.fillMaxSize()) {
            GraphWebView(graph = graph, modifier = Modifier.fillMaxSize())
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GraphWebView(graph: ThreadGraph, modifier: Modifier = Modifier) {
    val json = remember(graph) { ThreadGraphJson.toJsonString(graph) }
    // Fixed per audit (material-language.md's "material sovereignty" — the user isn't choosing a
    // hex accent, they're choosing their material world): the deep-view bootstrap used to hard-
    // code its own second palette that never matched Hyle's real tokens and could never re-theme
    // with the user's chosen accent, because nothing ever crossed the one-way JSON bridge except
    // the graph itself. Resolving the live [LocalHyleColors] here and pushing it alongside the
    // graph JSON on every load call is what lets `build-graph-room.js`'s fillFor/edgeStyleFor read
    // real theme values instead of switch-cased literals.
    val colors = LocalHyleColors.current
    val themeJson = remember(colors) {
        JSONObject().apply {
            put("textMid", colors.textMid.toWebHex())
            put("cyan", colors.cyan.toWebHex())
            put("warning", colors.warning.toWebHex())
            put("success", colors.success.toWebHex())
            put("violet", colors.violet.toWebHex())
        }.toString()
    }
    var pageLoaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

                    // ---- Lockdown (binding constraint 7) — see this file's own KDoc for why
                    // each setting is here. javaScriptEnabled is the one thing this page needs
                    // on: G6 and the bootstrap are both scripts, and without this the asset would
                    // load as inert markup only.
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
                    settings.setSupportZoom(false) // G6's own drag-canvas/zoom-canvas own the gesture; the WebView chrome shouldn't add a second zoom layer
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            pageLoaded = true
                            view.evaluateJavascript(pushCall(json, themeJson), null)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) loadError = error.description?.toString() ?: "failed to load the graph room"
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
                // Re-push on recomposition too (a no-op via the `window.__graphRoom &&` guard in
                // the bootstrap until onPageFinished has already run once) — covers the case
                // where [graph] (or the live theme, e.g. an accent change) changes after the first
                // load without needing a second WebView.
                if (pageLoaded) webView.evaluateJavascript(pushCall(json, themeJson), null)
            },
        )

        if (!pageLoaded && loadError == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        loadError?.let { message ->
            Text(
                "Couldn't load the deep view: $message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

private const val ASSET_URL_PREFIX = "file:///android_asset/graph/"
private const val ASSET_URL = ASSET_URL_PREFIX + "graph-room.html"

/** [JSONObject.quote] produces a properly backslash/quote/control-char-escaped JS string
 *  literal (with its own surrounding quotes) — safe to splice into an `evaluateJavascript` call
 *  even though [json]/[themeJson] are untrusted-shaped app data, because neither is ever
 *  interpreted as anything but a JS string literal by the receiving `JSON.parse` calls in the
 *  bootstrap. [themeJson] is the live [dev.fonebrew.ui.theme.HyleColors] resolved to hex strings
 *  (see [Color.toWebHex]) — the deep view's own re-theme forward pointer this WP's audit named. */
private fun pushCall(json: String, themeJson: String): String =
    "window.__graphRoom && window.__graphRoom.load(${JSONObject.quote(json)}, ${JSONObject.quote(themeJson)});"

/** `#rrggbb` (alpha dropped — every [dev.fonebrew.ui.theme.HyleColors] entry this pushes is
 *  fully opaque) for splicing into the deep view's CSS/G6-style JSON. */
private fun Color.toWebHex(): String {
    val r = (red * 255f).roundToInt().coerceIn(0, 255)
    val g = (green * 255f).roundToInt().coerceIn(0, 255)
    val b = (blue * 255f).roundToInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(r, g, b)
}
