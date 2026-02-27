package com.metrolist.music.utils.cipher

import android.content.Context
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transforms the YouTube 'n' parameter in streaming URLs to prevent throttling (403).
 *
 * Loads player.js in a minimal WebView and executes the n-transform function
 * that is already embedded in the player. No external JS libraries are required –
 * the n-function is injected onto `window._nFunc` by patching the IIFE closure.
 *
 * Fallback: if IIFE patching fails, a brute-force discovery scans all top-level
 * functions in the player scope for one that mutates a string argument, matching
 * the well-known behaviour of YouTube's n-transform.
 */
object NTransformSolver {
    private const val TAG = "Metrolist_NTransform"

    private var cachedHash: String? = null
    private var solverWebView: SolverWebView? = null

    suspend fun transformNParamInUrl(url: String): String {
        val nMatch = Regex("[?&]n=([^&]+)").find(url) ?: return url
        val nValue = Uri.decode(nMatch.groupValues[1])
        Timber.tag(TAG).d("n-param: $nValue")

        return withContext(NonCancellable) {
            try {
                val solver = getOrCreate() ?: return@withContext url
                if (!solver.nFunctionAvailable) {
                    Timber.tag(TAG).e("N-transform not available in player JS")
                    return@withContext url
                }
                val transformed = solver.transformN(nValue)
                Timber.tag(TAG).d("n-param: $nValue -> $transformed")
                url.replaceFirst(
                    Regex("([?&])n=[^&]+"),
                    "$1n=${Uri.encode(transformed)}"
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "N-transform failed: ${e.message}")
                url
            }
        }
    }

    suspend fun reset() {
        withContext(Dispatchers.Main) { solverWebView?.close() }
        solverWebView = null
        cachedHash = null
    }

    private suspend fun getOrCreate(): SolverWebView? {
        val (playerJs, hash) = PlayerJsFetcher.getPlayerJs() ?: run {
            Timber.tag(TAG).e("Failed to obtain player JS")
            return null
        }

        // Re-use existing WebView if the player JS hasn't changed
        if (cachedHash == hash && solverWebView != null) {
            return solverWebView
        }

        // Close stale WebView if any
        withContext(Dispatchers.Main) { solverWebView?.close() }
        solverWebView = null

        val nFuncInfo = FunctionNameExtractor.extractNFunctionInfo(playerJs)
        if (nFuncInfo == null) {
            Timber.tag(TAG).e("Could not locate n-function in player JS (hash=$hash)")
        }

        Timber.tag(TAG).d(
            "Creating NTransformSolver (hash=$hash, " +
                "nFunc=${nFuncInfo?.name}[${nFuncInfo?.arrayIndex}])"
        )

        return try {
            SolverWebView.create(PlayerJsFetcher.appContext, playerJs, nFuncInfo).also {
                solverWebView = it
                cachedHash = hash
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create NTransformSolver: ${e.message}")
            null
        }
    }

    // ─── Inner WebView ────────────────────────────────────────────────────────

    private class SolverWebView private constructor(
        context: Context,
        playerJs: String,
        nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
        private val initCont: Continuation<SolverWebView>,
    ) {
        private val wv = WebView(context)
        private var transformCont: Continuation<String>? = null

        @Volatile
        var nFunctionAvailable = false
            private set

        init {
            wv.settings.apply {
                @Suppress("SetJavaScriptEnabled")
                javaScriptEnabled = true
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                blockNetworkLoads = true
            }
            wv.addJavascriptInterface(this, "NTransformBridge")

            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.message().contains("Uncaught")) {
                        Timber.tag(TAG).e(
                            "WebView JS error: ${m.message()} at ${m.sourceId()}:${m.lineNumber()}"
                        )
                    }
                    return super.onConsoleMessage(m)
                }
            }

            val cacheDir = File(context.cacheDir, "ntransform")
            cacheDir.mkdirs()

            // Build the export expression for the n-function
            val exportExpr: String? = if (nFuncInfo != null) {
                val base = nFuncInfo.name
                if (nFuncInfo.arrayIndex != null) "$base[${nFuncInfo.arrayIndex}]" else base
            } else null

            // Patch the player JS IIFE to export the n-function.
            // We try several known IIFE terminator patterns and fall through to appending.
            val modifiedJs = patchPlayerJs(playerJs, exportExpr)
            File(cacheDir, "player.js").writeText(modifiedJs)

            val html = """<!DOCTYPE html>
<html><head>
<script>
function _doTransform(v) {
    try {
        if (typeof window._nFunc !== 'function') {
            NTransformBridge.onError('_nFunc is not a function (type: ' + typeof window._nFunc + ')');
            return;
        }
        var result = window._nFunc(v);
        if (result === undefined || result === null) {
            NTransformBridge.onError('_nFunc returned null/undefined');
            return;
        }
        NTransformBridge.onResult(String(result));
    } catch(e) {
        NTransformBridge.onError(String(e));
    }
}

function _discoverAndInit() {
    // 1. Try the exported function from IIFE patch
    if (typeof window._nFunc === 'function') {
        try {
            var test = window._nFunc('test_n_abc123');
            if (typeof test === 'string' && test !== 'test_n_abc123') {
                NTransformBridge.onReady('ok_exported');
                return;
            }
            window._nFunc = null;
        } catch(e) {
            window._nFunc = null;
        }
    }

    // 2. Brute-force: scan window for a 1-arg function that transforms strings
    try {
        var testInput = 'T2Xw3pWQ_Wk0xbOg';
        var keys = Object.getOwnPropertyNames(window);
        for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            if (key === 'NTransformBridge' || key.startsWith('webkit')) continue;
            try {
                var fn = window[key];
                if (typeof fn !== 'function' || fn.length !== 1) continue;
                var result = fn(testInput);
                if (typeof result === 'string' &&
                    result !== testInput &&
                    result.length >= 5 &&
                    result.length <= 100) {
                    window._nFunc = fn;
                    NTransformBridge.onReady('ok_bruteforce:' + key);
                    return;
                }
            } catch(e) {}
        }
    } catch(e) {}

    NTransformBridge.onReady('fail');
}
</script>
<script src="player.js"
    onload="_discoverAndInit()"
    onerror="NTransformBridge.onReady('error_load_player_js')">
</script>
</head><body></body></html>"""

            wv.loadDataWithBaseURL(
                "file://${cacheDir.absolutePath}/",
                html, "text/html", "utf-8", null
            )
        }

        @JavascriptInterface
        fun onReady(status: String) {
            nFunctionAvailable = status.startsWith("ok")
            Timber.tag(TAG).d("NTransformSolver ready: $status (available=$nFunctionAvailable)")
            initCont.resume(this)
        }

        @JavascriptInterface
        fun onError(error: String) {
            if (!nFunctionAvailable) {
                // Called during transform, not init
                transformCont?.resumeWithException(Exception(error))
                transformCont = null
            }
        }

        @JavascriptInterface
        fun onResult(result: String) {
            transformCont?.resume(result)
            transformCont = null
        }

        suspend fun transformN(nValue: String): String = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                transformCont = cont
                val escaped = escapeJs(nValue)
                wv.evaluateJavascript("_doTransform('$escaped')", null)
            }
        }

        fun close() {
            wv.clearHistory()
            wv.clearCache(true)
            wv.loadUrl("about:blank")
            wv.onPause()
            wv.removeAllViews()
            wv.destroy()
            Timber.tag(TAG).d("NTransformSolver WebView closed")
        }

        private fun escapeJs(s: String) = s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        companion object {
            /**
             * Patch the IIFE to export the resolved n-function to `window._nFunc`.
             * Tries common IIFE terminators; falls back to appending at the end.
             */
            fun patchPlayerJs(
                playerJs: String,
                exportExpr: String?,
            ): String {
                if (exportExpr == null) return playerJs

                val exportCode = "; try { window._nFunc = $exportExpr; } catch(_e) {}"

                // Common IIFE terminators – try each in order
                val terminators = listOf(
                    "})(_yt_player);",
                    "})( _yt_player);",
                    "})(yt.player);",
                    "})(_yt_player)",
                    "})();",
                    "})()",
                )
                for (term in terminators) {
                    val idx = playerJs.lastIndexOf(term)
                    if (idx >= 0) {
                        return playerJs.substring(0, idx) +
                            exportCode +
                            playerJs.substring(idx)
                    }
                }

                // Last resort: append after the final closing brace
                Timber.tag(TAG).w("IIFE terminator not found; appending export at end")
                return "$playerJs\n$exportCode"
            }

            suspend fun create(
                context: Context,
                playerJs: String,
                nFuncInfo: FunctionNameExtractor.NFunctionInfo?,
            ): SolverWebView = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    SolverWebView(context, playerJs, nFuncInfo, cont)
                }
            }
        }
    }
}
