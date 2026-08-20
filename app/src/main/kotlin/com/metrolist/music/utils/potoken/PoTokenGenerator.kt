package com.metrolist.music.utils.potoken

import android.webkit.CookieManager
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

class PoTokenGenerator {
    private val TAG = "PoTokenGenerator"

    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false // whether the system has a bad WebView implementation

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        Timber.tag(TAG).d("getWebClientPoToken called: videoId=$videoId, sessionId=$sessionId")
        Timber.tag(TAG).d("WebView state: supported=$webViewSupported, badImpl=$webViewBadImpl")
        if (!webViewSupported || webViewBadImpl) {
            Timber.tag(TAG).d("WebView not available: supported=$webViewSupported, badImpl=$webViewBadImpl")
            return null
        }

        return try {
            Timber.tag(TAG).d("Calling runBlocking to generate poToken (timeout=${POTOKEN_TIMEOUT_MS}ms)...")
            runBlocking {
                withTimeout(POTOKEN_TIMEOUT_MS) {
                    getWebClientPoToken(videoId, sessionId, forceRecreate = false)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // The WebView's sandboxed process can be culled by the OS (storage pressure, low
            // memory, etc.) which leaves the PoToken WebView call hung indefinitely. Cap it so
            // playerResponseForPlayback can fall through to non-PoToken fallback clients (e.g.
            // ANDROID_VR) instead of blocking the entire playback path.
            Timber.tag(TAG).w("poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms; proceeding without PoToken")
            runBlocking {
                webPoTokenGenLock.withLock {
                    try {
                        withContext(Dispatchers.Main) {
                            webPoTokenGenerator?.close()
                        }
                    } catch (closeEx: Exception) {
                        Timber.tag(TAG).e(closeEx, "Exception closing PoTokenWebView during timeout cleanup")
                    }
                    webPoTokenGenerator = null
                    webPoTokenStreamingPot = null
                    webPoTokenSessionId = null
                }
            }
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "poToken generation exception: ${e.javaClass.simpleName}: ${e.message}")
            when (e) {
                is BadWebViewException -> {
                    Timber.tag(TAG).e(e, "Could not obtain poToken because WebView is broken")
                    webViewBadImpl = true
                    null
                }
                else -> throw e // includes PoTokenException
            }
        }
    }

    private companion object {
        // Healthy cold-start (WebView spin-up + botguard JS + token gen) is ~2–5s in practice;
        // 8s leaves slack for a slow device without making the user wait too long before the
        // fallback chain (ANDROID_VR, etc.) takes over when the WebView hangs.
        const val POTOKEN_TIMEOUT_MS = 8_000L
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     * case the current [webPoTokenGenerator] threw an error last time
     * [PoTokenWebView.generatePoToken] was called
     */
    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        Timber.tag(TAG).d("Web poToken requested: videoId=$videoId, sessionId=$sessionId")

        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                val shouldRecreate =
                    forceRecreate || webPoTokenGenerator == null || webPoTokenGenerator!!.isExpired || webPoTokenSessionId != sessionId

                if (shouldRecreate) {
                    Timber.tag(TAG).d("Creating new PoTokenWebView (forceRecreate=$forceRecreate)")
                    webPoTokenSessionId = sessionId

                    withContext(Dispatchers.Main) {
                        webPoTokenGenerator?.close()
                    }

                    // create a new webPoTokenGenerator
                    webPoTokenGenerator = PoTokenWebView.getNewPoTokenGenerator(CipherDeobfuscator.appContext)

                    // The streaming poToken needs to be generated exactly once before generating
                    // any other (player) tokens.
                    webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(webPoTokenSessionId!!)
                    Timber.tag(TAG).d("Streaming poToken generated for sessionId=${webPoTokenSessionId?.take(20)}...")
                }

                Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
            }

        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                // the poTokenGenerator has just been recreated (and possibly this is already the
                // second time we try), so there is likely nothing we can do
                throw throwable
            } else {
                // retry, this time recreating the [webPoTokenGenerator] from scratch;
                // this might happen for example if the app goes in the background and the WebView
                // content is lost
                Timber.tag(TAG).e(throwable, "Failed to obtain poToken, retrying")
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        }

        Timber.tag(TAG).d("poToken generated successfully: session=${streamingPot.take(20)}..., video=${playerPot.take(20)}...")

        /*
         * Content binding, per yt-dlp's `get_webpo_content_binding` and NewPipe's
         * PoTokenProviderImpl:
         *   - GVS context (the `pot=` appended to a googlevideo URL) -> bound to the SESSION,
         *     i.e. dataSyncId when authenticated, visitor_data otherwise.
         *   - PLAYER context (`serviceIntegrityDimensions` in the /player body) -> bound to the
         *     video id, with WEB_REMIX special-cased back into the session branch.
         *
         * `streamingDataPoToken` is what gets appended as `pot=`, so it must be the SESSION-bound
         * one. It was `playerPot` (video-id bound), which is the opposite. The device trace shows
         * the mismatch directly: `playerRequestPoToken` stays identical across different videoIds
         * within a process (session-bound) while `streamingDataPoToken` changes every call.
         *
         * `playerRequestPoToken` is deliberately left as `streamingPot`: MAIN_CLIENT is WEB_REMIX,
         * which is exactly the client yt-dlp special-cases into the session branch for the PLAYER
         * context too. Swapping the two fields would fix the URL and break the /player request.
         * A separate video-id-bound field would be needed to serve TVHTML5/WEB_CREATOR correctly
         * on the PLAYER side; not added until there is a measurement asking for it.
         */
        return PoTokenResult(
            playerRequestPoToken = streamingPot,
            streamingDataPoToken = streamingPot,
        )
    }
}
