package com.metrolist.music.utils.cipher

import timber.log.Timber

/**
 * Orchestrates YouTube cipher operations:
 *  - Signature deobfuscation (pure Kotlin, no WebView)
 *  - N-parameter transform (WebView with player.js only, no external libraries)
 *
 * Callers should use [deobfuscateSignatureCipher] for signatureCipher formats
 * (age-restricted / user-uploaded content) and [transformNParam] for throttle
 * avoidance on SABR / WEB_REMIX stream URLs.
 */
object CipherManager {
    private const val TAG = "Metrolist_CipherMgr"

    /**
     * Decode a signatureCipher string and return the ready-to-use stream URL.
     *
     * This is the pure-Kotlin replacement for the old CipherWebView-based
     * [CipherDeobfuscator]. Internally it:
     *  1. Fetches / caches the player JS via [PlayerJsFetcher].
     *  2. Locates the sig-function name with [FunctionNameExtractor].
     *  3. Parses the three elementary operations (reverse/splice/swap) from player JS.
     *  4. Applies the operations in Kotlin to obtain the plain signature.
     *
     * @param signatureCipher  The raw "s=XXX&sp=sig&url=…" value from the player response.
     * @param videoId          Video ID used only for logging.
     * @return Deobfuscated stream URL, or null if any step fails.
     */
    suspend fun deobfuscateSignatureCipher(
        signatureCipher: String,
        videoId: String,
    ): String? = try {
        deobfuscateInternal(signatureCipher, videoId, isRetry = false)
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "[$videoId] Sig deobfuscation failed, retrying with fresh player JS")
        try {
            PlayerJsFetcher.invalidateCache()
            SignatureDeobfuscator.invalidateCache()
            deobfuscateInternal(signatureCipher, videoId, isRetry = true)
        } catch (re: Exception) {
            Timber.tag(TAG).e(re, "[$videoId] Sig deobfuscation retry also failed")
            null
        }
    }

    private suspend fun deobfuscateInternal(
        signatureCipher: String,
        videoId: String,
        isRetry: Boolean,
    ): String? {
        val (playerJs, playerHash) = PlayerJsFetcher.getPlayerJs(forceRefresh = isRetry) ?: run {
            Timber.tag(TAG).e("[$videoId] Failed to obtain player JS")
            return null
        }

        val sigInfo = FunctionNameExtractor.extractSigFunctionInfo(playerJs) ?: run {
            Timber.tag(TAG).e("[$videoId] Cannot locate sig function in player JS")
            return null
        }

        val result = SignatureDeobfuscator.deobfuscateUrl(
            signatureCipher = signatureCipher,
            playerJs = playerJs,
            playerHash = playerHash,
            sigInfo = sigInfo,
        )

        if (result != null) {
            Timber.tag(TAG).d("[$videoId] Sig deobfuscation succeeded (retry=$isRetry)")
        } else {
            Timber.tag(TAG).e("[$videoId] SignatureDeobfuscator returned null")
        }

        return result
    }

}
