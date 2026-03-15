package com.metrolist.music.utils.cipher

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Main cipher deobfuscation orchestrator for YouTube stream URLs.
 *
 * Handles both signature deobfuscation (for signatureCipher streams) and
 * n-parameter transformation (for throttle avoidance / 403 fix).
 */
object CipherDeobfuscator {
    private const val TAG = "Metrolist_CipherDeobfusc"

    lateinit var appContext: Context
        private set

    fun initialize(context: Context) {
        Timber.tag(TAG).d("CipherDeobfuscator initializing...")
        appContext = context.applicationContext
        Timber.tag(TAG).d("CipherDeobfuscator initialized")
    }

    private var cipherWebView: CipherWebView? = null
    private var currentPlayerHash: String? = null

    /**
     * Deobfuscate a signatureCipher stream URL.
     *
     * The signatureCipher is a query string containing:
     * - s: The obfuscated signature
     * - sp: The signature parameter name (usually "sig" or "signature")
     * - url: The base stream URL
     *
     * Returns the full URL with deobfuscated signature, or null if failed.
     */
    suspend fun deobfuscateStreamUrl(signatureCipher: String, videoId: String): String? {
        Timber.tag(TAG).d("=== DEOBFUSCATE STREAM URL ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("signatureCipher length: ${signatureCipher.length}")
        Timber.tag(TAG).d("signatureCipher preview: ${signatureCipher.take(100)}...")

        return try {
            deobfuscateInternal(signatureCipher, videoId, isRetry = false)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Cipher deobfuscation failed, retrying with fresh JS: ${e.message}")
            Timber.tag(TAG).d("Invalidating cache and retrying...")
            try {
                PlayerJsFetcher.invalidateCache()
                closeWebView()
                deobfuscateInternal(signatureCipher, videoId, isRetry = true)
            } catch (retryE: Exception) {
                Timber.tag(TAG).e(retryE, "Cipher deobfuscation retry also failed: ${retryE.message}")
                null
            }
        }
    }

    private suspend fun deobfuscateInternal(signatureCipher: String, videoId: String, isRetry: Boolean): String? {
        Timber.tag(TAG).d("deobfuscateInternal: videoId=$videoId, isRetry=$isRetry")

        // Parse the signatureCipher query string
        val params = parseQueryParams(signatureCipher)
        val obfuscatedSig = params["s"]
        val sigParam = params["sp"] ?: "signature"
        val baseUrl = params["url"]

        Timber.tag(TAG).d("Parsed signatureCipher params:")
        Timber.tag(TAG).d("  s (obfuscated sig): ${obfuscatedSig?.take(30)}... (length=${obfuscatedSig?.length})")
        Timber.tag(TAG).d("  sp (sig param name): $sigParam")
        Timber.tag(TAG).d("  url: ${baseUrl?.take(80)}...")

        if (obfuscatedSig == null || baseUrl == null) {
            Timber.tag(TAG).e("Could not parse signatureCipher params: s=${obfuscatedSig != null}, url=${baseUrl != null}")
            return null
        }

        val webView = getOrCreateWebView(forceRefresh = isRetry)
        if (webView == null) {
            Timber.tag(TAG).e("Failed to get/create CipherWebView")
            return null
        }

        Timber.tag(TAG).d("Calling webView.deobfuscateSignature()...")
        val deobfuscatedSig = webView.deobfuscateSignature(obfuscatedSig)
        Timber.tag(TAG).d("Deobfuscated signature: ${deobfuscatedSig.take(30)}... (length=${deobfuscatedSig.length})")

        // Build the URL with deobfuscated signature
        val separator = if ("?" in baseUrl) "&" else "?"
        val finalUrl = "$baseUrl${separator}${sigParam}=${Uri.encode(deobfuscatedSig)}"

        Timber.tag(TAG).d("=== CIPHER DEOBFUSCATION SUCCESS ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("Final URL length: ${finalUrl.length}")
        Timber.tag(TAG).d("Final URL preview: ${finalUrl.take(100)}...")

        return finalUrl
    }

    /**
     * Transform the 'n' parameter in a streaming URL to avoid throttling/403.
     *
     * Uses the runtime-discovered n-function from the player JS WebView.
     * Returns the URL with the transformed 'n' value, or the original URL if transform fails.
     *
     * IMPORTANT: This must be called for WEB_REMIX, WEB, WEB_CREATOR, TVHTML5 clients
     * and for privately owned tracks (uploaded songs).
     */
    suspend fun transformNParamInUrl(url: String): String {
        Timber.tag(TAG).d("=== N-TRANSFORM URL ===")
        Timber.tag(TAG).d("Input URL length: ${url.length}")
        Timber.tag(TAG).d("Input URL preview: ${url.take(100)}...")

        return try {
            transformNInternal(url)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "N-transform failed, returning original URL: ${e.message}")
            url
        }
    }

    private suspend fun transformNInternal(url: String): String {
        // Extract the 'n' parameter value from the URL
        val nMatch = Regex("[?&]n=([^&]+)").find(url)
        if (nMatch == null) {
            Timber.tag(TAG).d("No 'n' parameter found in URL, skipping transform")
            return url
        }

        val nValueEncoded = nMatch.groupValues[1]
        val nValue = Uri.decode(nValueEncoded)
        Timber.tag(TAG).d("N-param found:")
        Timber.tag(TAG).d("  encoded: $nValueEncoded")
        Timber.tag(TAG).d("  decoded: $nValue")

        val webView = getOrCreateWebView(forceRefresh = false)
        if (webView == null) {
            Timber.tag(TAG).e("Failed to get CipherWebView for n-transform")
            return url
        }

        Timber.tag(TAG).d("CipherWebView state:")
        Timber.tag(TAG).d("  nFunctionAvailable: ${webView.nFunctionAvailable}")
        Timber.tag(TAG).d("  discoveredNFuncName: ${webView.discoveredNFuncName}")
        Timber.tag(TAG).d("  usingHardcodedMode: ${webView.usingHardcodedMode}")

        if (!webView.nFunctionAvailable) {
            Timber.tag(TAG).e("N-transform function was not discovered at init time")
            return url
        }

        Timber.tag(TAG).d("Calling webView.transformN()...")
        val transformedN = webView.transformN(nValue)

        Timber.tag(TAG).d("=== N-TRANSFORM SUCCESS ===")
        Timber.tag(TAG).d("N-param: $nValue -> $transformedN")

        // Replace n= parameter in URL
        val transformedUrl = url.replaceFirst(
            Regex("([?&])n=[^&]+"),
            "$1n=${Uri.encode(transformedN)}"
        )

        Timber.tag(TAG).d("Transformed URL length: ${transformedUrl.length}")
        return transformedUrl
    }

    private suspend fun getOrCreateWebView(forceRefresh: Boolean): CipherWebView? {
        Timber.tag(TAG).d("getOrCreateWebView: forceRefresh=$forceRefresh, existing=${cipherWebView != null}")

        if (!forceRefresh && cipherWebView != null) {
            Timber.tag(TAG).d("Reusing existing CipherWebView (hash=$currentPlayerHash)")
            return cipherWebView
        }

        // Close existing WebView if any
        if (cipherWebView != null) {
            Timber.tag(TAG).d("Closing existing CipherWebView...")
            closeWebView()
        }

        // Fetch player JS
        Timber.tag(TAG).d("Fetching player JS...")
        val result = PlayerJsFetcher.getPlayerJs(forceRefresh = forceRefresh)
        if (result == null) {
            Timber.tag(TAG).e("Failed to get player JS")
            return null
        }
        val (playerJs, hash) = result
        Timber.tag(TAG).d("Got player JS: hash=$hash, length=${playerJs.length}")

        // Run full analysis for logging - pass the known hash from PlayerJsFetcher
        Timber.tag(TAG).d("Analyzing player JS for cipher functions (knownHash=$hash)...")
        val analysis = FunctionNameExtractor.analyzePlayerJs(playerJs, knownHash = hash)

        if (analysis.sigInfo == null) {
            Timber.tag(TAG).e("Could not extract signature function info from player JS")
            return null
        }

        if (analysis.nFuncInfo == null) {
            Timber.tag(TAG).w("Could not extract n-function info from player JS (will try brute-force)")
        }

        Timber.tag(TAG).d("Creating CipherWebView...")
        Timber.tag(TAG).d("  sig: ${analysis.sigInfo.name} (constantArg=${analysis.sigInfo.constantArg}, hardcoded=${analysis.sigInfo.isHardcoded})")
        Timber.tag(TAG).d("  nFunc: ${analysis.nFuncInfo?.name}[${analysis.nFuncInfo?.arrayIndex}] (hardcoded=${analysis.nFuncInfo?.isHardcoded})")

        // Create WebView
        val webView = CipherWebView.create(
            context = appContext,
            playerJs = playerJs,
            sigInfo = analysis.sigInfo,
            nFuncInfo = analysis.nFuncInfo,
        )

        Timber.tag(TAG).d("CipherWebView created successfully")
        Timber.tag(TAG).d("  nFunctionAvailable: ${webView.nFunctionAvailable}")
        Timber.tag(TAG).d("  sigFunctionAvailable: ${webView.sigFunctionAvailable}")
        Timber.tag(TAG).d("  discoveredNFuncName: ${webView.discoveredNFuncName}")

        cipherWebView = webView
        currentPlayerHash = hash
        return webView
    }

    private suspend fun closeWebView() {
        Timber.tag(TAG).d("closeWebView: existing=${cipherWebView != null}")
        withContext(Dispatchers.Main) {
            cipherWebView?.close()
        }
        cipherWebView = null
        currentPlayerHash = null
        Timber.tag(TAG).d("CipherWebView closed and cleared")
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = Uri.decode(pair.substring(0, idx))
                val value = Uri.decode(pair.substring(idx + 1))
                result[key] = value
            }
        }
        Timber.tag(TAG).v("parseQueryParams: ${result.keys.joinToString()}")
        return result
    }

    /**
     * Debug method: Get current state information
     */
    fun getDebugInfo(): Map<String, Any?> {
        return mapOf(
            "hasWebView" to (cipherWebView != null),
            "playerHash" to currentPlayerHash,
            "nFunctionAvailable" to cipherWebView?.nFunctionAvailable,
            "sigFunctionAvailable" to cipherWebView?.sigFunctionAvailable,
            "discoveredNFuncName" to cipherWebView?.discoveredNFuncName,
            "usingHardcodedMode" to cipherWebView?.usingHardcodedMode,
        )
    }
}
