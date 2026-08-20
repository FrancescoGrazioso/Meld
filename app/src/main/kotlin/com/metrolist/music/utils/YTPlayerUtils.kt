/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IPADOS
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.VISIONOS
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.YTPlayerUtils.MAIN_CLIENT
import com.metrolist.music.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.metrolist.music.utils.YTPlayerUtils.validateStatus
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import com.metrolist.music.utils.sabr.EjsNTransformSolver
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    /**
     * Size of the first media chunk ExoPlayer requests. Must stay in sync with
     * `MusicService.CHUNK_LENGTH`; kept as a local copy so this object does not have to depend
     * on the playback service. Used by [validateStatus] so the probe and the real request match.
     */
    private const val VALIDATION_CHUNK_LENGTH = 512 * 1024L

    /**
     * Compact, redacted description of a googlevideo stream URL, for logging.
     *
     * User-supplied logcats for the 403 / IO_UNSPECIFIED reports contained **no stream URL at
     * all** — zero occurrences of `googlevideo`, `expire=`, `itag=` or `pot=` across 32k lines —
     * so every conclusion had to be inferred from mime and bitrate. These are the parameters that
     * actually decide whether the CDN serves the stream. Signature material (`sig`, `signature`,
     * `sparams`) is never printed, and `pot`/`n` are reduced to presence and length.
     */
    private fun describeStreamUrl(url: String): String =
        try {
            val uri = Uri.parse(url)
            val expire = uri.getQueryParameter("expire")?.toLongOrNull()
            val nowSec = System.currentTimeMillis() / 1000
            buildString {
                append("host=").append(uri.host ?: "?")
                append(" itag=").append(uri.getQueryParameter("itag") ?: "-")
                append(" mime=").append(uri.getQueryParameter("mime") ?: "-")
                append(" c=").append(uri.getQueryParameter("c") ?: "-")
                append(" expire=").append(expire ?: "-")
                if (expire != null) append("(in ").append(expire - nowSec).append("s)")
                append(" hasPot=").append(uri.getQueryParameter("pot") != null)
                append(" nLen=").append(uri.getQueryParameter("n")?.length ?: -1)
                append(" cpn=").append(uri.getQueryParameter("cpn") ?: "-")
                append(" lmt=").append(uri.getQueryParameter("lmt") ?: "-")
                append(" sabr=").append(uri.getQueryParameter("sabr") ?: "-")
                append(" clen=").append(uri.getQueryParameter("clen") ?: "-")
            }
        } catch (e: Exception) {
            "unparseable url (${e.javaClass.simpleName})"
        }

    /**
     * Everything about a `/player` response that decides whether it can produce a playable stream.
     *
     * `urls`/`ciphers`/`bare` is the key triple: a format carrying neither a `url` nor a
     * `signatureCipher` means YouTube served a **SABR-only** response for that client, which this
     * app cannot use at all. Distinguishing that from "no suitable format" and from "playability
     * not OK" is the difference between three completely different bugs.
     */
    private fun describeResponse(client: YouTubeClient, response: PlayerResponse?): String =
        try {
            if (response == null) {
                Fix403.kv("client" to client.clientName, "response" to "NULL(requestFailed)")
            } else {
                val adaptive = response.streamingData?.adaptiveFormats.orEmpty()
                val audio = adaptive.filter { it.isAudio }
                Fix403.kv(
                    "client" to client.clientName,
                    "clientVersion" to client.clientVersion,
                    "status" to response.playabilityStatus.status,
                    "reason" to response.playabilityStatus.reason,
                    "hasStreamingData" to (response.streamingData != null),
                    "expiresInSeconds" to response.streamingData?.expiresInSeconds,
                    "adaptiveFormats" to adaptive.size,
                    "audioFormats" to audio.size,
                    "urls" to adaptive.count { !it.url.isNullOrEmpty() },
                    "ciphers" to adaptive.count { !it.signatureCipher.isNullOrEmpty() || !it.cipher.isNullOrEmpty() },
                    // Neither url nor cipher => SABR-only response, unusable by this app.
                    "bare" to adaptive.count {
                        it.url.isNullOrEmpty() && it.signatureCipher.isNullOrEmpty() && it.cipher.isNullOrEmpty()
                    },
                    "audioItags" to audio.joinToString("/") { it.itag.toString() }.ifEmpty { "-" },
                    "musicVideoType" to response.videoDetails?.musicVideoType,
                    "title" to response.videoDetails?.title,
                )
            }
        } catch (e: Exception) {
            "describeResponse failed (${e.javaClass.simpleName}: ${e.message})"
        }

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    /**
     * Ordered by *measured* ability to serve a whole file, not by theory.
     *
     * The decisive measurement: an IOS/IPADOS/ANDROID_VR(old) stream URL is a **~1 MiB preview**.
     * googlevideo serves a fixed byte prefix and answers 403 to everything past it — an
     * offset-based cap, binary-searched to the byte and stable across independent resolves:
     *
     * ```
     * videoId       itag 251 last readable byte   = seconds of audio
     * Rr1Cdli5nE8   1040807                         ~61 of 268
     * phLb_SoPBlA   1049091                         ~61 of 274
     * UbX5Yns8fHk   1019638                         ~67 of 159
     * ```
     *
     * It is not request-count, not rate, not expiry: a fresh URL asked for `bytes=524288-1048575`
     * as its *first* request already 403s, and a range straddling the cap is rejected wholesale.
     * Adding `cpn`/`rn`, the client's own User-Agent, Origin/Referer, `alr`, `ratebypass` or a
     * bogus `pot` changes nothing. This is what produced the field reports of playback dying after
     * 30-90 seconds — ExoPlayer reads ahead, so the 403 lands before the audible stall.
     *
     * VISIONOS is the exception and the reason it now leads: probed on the same three videoIds it
     * read every file start to finish (2.4 / 4.5 / 4.7 MB, 100% 206), survived 51 ranged reads
     * paced over 300 s, and returned 206 for the exact byte offsets that 403 in production.
     *
     * IOS/IPADOS are kept at the tail deliberately — upstream deleted them, but a 1 MiB preview
     * still beats no stream at all if everything above fails. They must never be reached while a
     * client above them can serve the file.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        VISIONOS,                        // only client measured to serve a complete file
        ANDROID_VR_1_65_10,              // current yt-dlp/YouTube.js pin; whole-file capable
        TVHTML5,
        ANDROID_VR_1_43_32,              // version-gated; kept as the control against 1.65.10
        IPADOS,                          // ~1 MiB preview only — last resort
        IOS,                             // ~1 MiB preview only — last resort
        // The only client that answers OK for age-restricted / explicit tracks, because it is the
        // only authenticated one left in the chain. Its formats are always behind the signature
        // cipher, so it only works while PlayerConfigStore has a config for the live player.
        WEB_CREATOR
    )
    // TVHTML5_SIMPLY_EMBEDDED_PLAYER was here as the login-free age-restriction bypass. It is dead
    // server-side: measured on device across four consecutive cascades and again in isolated probes,
    // it answers `ERROR / "YouTube is no longer supported in this application or device"` every
    // time, with zero formats. Keeping it cost a round trip (~70 ms) on every resolution that had
    // to pass through it and could never succeed. The definition is left in YouTubeClient.

    /**
     * For normal content we skip the MAIN_CLIENT (WEB_REMIX) stream attempt and start at the top
     * of [STREAM_FALLBACK_CLIENTS]. WEB_REMIX returns formats behind YouTube's signature cipher /
     * n-challenge, which can no longer be solved client-side; that attempt lives at
     * `clientIndex == -1`, not inside the array, so skipping it means starting at index 0.
     * Metadata/history still come from the WEB_REMIX response fetched above.
     *
     * This used to be `indexOf(ANDROID_VR_1_43_32)`, which was index 2 under the old ordering.
     * Under the current ordering that expression resolves to **4**, silently skipping VISIONOS,
     * ANDROID_VR 1.65.10 and both TVHTML5 entries on every normal-content playback — i.e. it would
     * have quietly disabled the entire fix. Pinned to 0 and covered by a unit test.
     */
    private val NORMAL_CONTENT_STREAM_START_INDEX: Int = 0

    /**
     * Privately-owned (uploaded) tracks need TVHTML5. Resolved by identity rather than by a
     * hardcoded index, which under the previous ordering happened to be 1 and would now point at
     * [ANDROID_VR_1_65_10].
     */
    private val PRIVATE_TRACK_STREAM_START_INDEX: Int =
        STREAM_FALLBACK_CLIENTS.indexOf(TVHTML5).takeIf { it >= 0 } ?: 0

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        val fx = Fix403.nextId("res")
        Timber.tag(TAG).d("=== PLAYER RESPONSE FOR PLAYBACK ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("playlistId: $playlistId")
        Timber.tag(TAG).d("audioQuality: $audioQuality")

        // Check if this is an uploaded/privately owned track
        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true
        Timber.tag(TAG).d("Content type detection (preliminary):")
        Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")

        val isLoggedIn = YouTube.cookie != null
        Timber.tag(TAG).d("Authentication status: ${if (isLoggedIn) "LOGGED_IN" else "ANONYMOUS"}")

        Fix403.i(
            fx, "resolve.begin",
            Fix403.kv(
                "videoId" to videoId,
                "playlistId" to playlistId,
                "quality" to audioQuality,
                "uploadedTrack" to isUploadedTrack,
                "loggedIn" to isLoggedIn,
                "thread" to Thread.currentThread().name,
            ),
        )
        // Session identity. These decide whether YouTube treats the request as coherent, so their
        // presence/absence is the first thing to check on any 403 — see the account-bound
        // visitorData bug. Values are redacted; only presence and identity-stability matter.
        Fix403.i(
            fx, "resolve.session",
            Fix403.kv(
                "cookie" to Fix403.redact(YouTube.cookie),
                "visitorData" to Fix403.redact(YouTube.visitorData),
                "dataSyncId" to Fix403.redact(YouTube.dataSyncId),
                "proxy" to (YouTube.proxy?.toString() ?: "none"),
                "locale" to "${YouTube.locale.hl}/${YouTube.locale.gl}",
            ),
        )

        // Get signature timestamp (same as before for normal content)
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        Timber.tag(logTag).d("Signature timestamp: ${signatureTimestamp.timestamp}")
        Fix403.i(
            fx, "resolve.sts",
            Fix403.kv("sts" to signatureTimestamp.timestamp, "source" to "NewPipeExtractor"),
        )

        // Generate PoToken
        var poToken: PoTokenResult? = null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        val mainClientNeedsPoToken = MAIN_CLIENT.useWebPoTokens
        Fix403.i(
            fx, "potoken.decide",
            Fix403.kv(
                "mainClientNeedsPoToken" to mainClientNeedsPoToken,
                "sessionIdSource" to if (isLoggedIn) "dataSyncId" else "visitorData",
                "sessionId" to Fix403.redact(sessionId),
                // An EMPTY (not null) sessionId still passes the null check below and mints a
                // token bound to "" — which can never be valid. Called out explicitly.
                "sessionIdEmpty" to (sessionId != null && sessionId.isEmpty()),
            ),
        )
        if (mainClientNeedsPoToken && sessionId != null) {
            Timber.tag(logTag).d("Generating PoToken for WEB_REMIX with sessionId")
            try {
                poToken = Fix403.timed(fx, "potoken.generate") {
                    poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                }
                if (poToken != null) {
                    Timber.tag(logTag).d("PoToken generated successfully")
                }
                Fix403.i(
                    fx, "potoken.result",
                    Fix403.kv(
                        "obtained" to (poToken != null),
                        "playerRequestPoToken" to Fix403.redact(poToken?.playerRequestPoToken),
                        "streamingDataPoToken" to Fix403.redact(poToken?.streamingDataPoToken),
                    ),
                )
            } catch (e: Exception) {
                Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
                Fix403.fail(fx, "potoken.generate.failed", e)
            }
        } else {
            Fix403.w(
                fx, "potoken.skipped",
                Fix403.kv("reason" to if (!mainClientNeedsPoToken) "mainClientDoesNotUseIt" else "sessionIdNull"),
            )
        }
        // If MAIN_CLIENT needs a PoToken but we couldn't get one (WebView missing, JS
        // blocked, network hostile), WEB_REMIX will return streams that 403 on play.
        // Skip it and go straight to the fallback chain.
        val skipMainClient = mainClientNeedsPoToken && poToken == null
        if (skipMainClient) {
            Timber.tag(TAG).w("PoToken unavailable — skipping MAIN_CLIENT and using fallback chain directly")
            Fix403.w(fx, "mainClient.skipped", Fix403.kv("reason" to "poTokenUnavailable"))
        }

        // Try WEB_REMIX with signature timestamp and poToken (same as before)
        Timber.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        var mainPlayerResponse = Fix403.trapRethrow(fx, "mainClient.player") {
            Fix403.timed(fx, "mainClient.request") {
                YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp.timestamp, poToken?.playerRequestPoToken).getOrThrow()
            }
        }
        Fix403.i(fx, "mainClient.response", describeResponse(MAIN_CLIENT, mainPlayerResponse))

        // Debug uploaded track response
        if (isUploadedTrack || playlistId?.contains("MLPT") == true) {
            println("[PLAYBACK_DEBUG] Main player response status: ${mainPlayerResponse.playabilityStatus.status}")
            println("[PLAYBACK_DEBUG] Playability reason: ${mainPlayerResponse.playabilityStatus.reason}")
            println("[PLAYBACK_DEBUG] Video details: title=${mainPlayerResponse.videoDetails?.title}, videoId=${mainPlayerResponse.videoDetails?.videoId}")
            println("[PLAYBACK_DEBUG] Streaming data null? ${mainPlayerResponse.streamingData == null}")
            println("[PLAYBACK_DEBUG] Adaptive formats count: ${mainPlayerResponse.streamingData?.adaptiveFormats?.size ?: 0}")
        }

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        // Check if WEB_REMIX response indicates age-restricted
        val mainStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestrictedFromResponse = mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            // Age-restricted: use WEB_CREATOR directly (no NewPipe needed from here)
            Timber.tag(logTag).d("Age-restricted detected, using WEB_CREATOR")
            Timber.tag(TAG).i("Age-restricted: using WEB_CREATOR for videoId=$videoId")
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("WEB_CREATOR works for age-restricted content")
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        // If we still don't have a valid response, throw

        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        val retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        // Check current status
        val currentStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestricted = currentStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")

        if (isAgeRestricted) {
            Timber.tag(logTag).d("Content is still age-restricted (status: $currentStatus), will try fallback clients")
            Timber.tag(TAG)
                .i("Age-restricted content detected: videoId=$videoId, status=$currentStatus")
        }

        // Check if this is a privately owned track (uploaded song)
        val isPrivateTrack = mainPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        // For private tracks: use TVHTML5 with PoToken + n-transform
        // For age-restricted: skip main client, start with fallbacks
        // For normal content: standard order
        val startIndex = when {
            isPrivateTrack -> PRIVATE_TRACK_STREAM_START_INDEX
            isAgeRestricted -> 0
            skipMainClient -> 0  // MAIN_CLIENT streams unplayable without PoToken
            // Normal content: skip the WEB_REMIX stream attempt (its formats are behind the
            // cipher / n-challenge) and start at the top of the array. See
            // NORMAL_CONTENT_STREAM_START_INDEX.
            else -> NORMAL_CONTENT_STREAM_START_INDEX
        }

        /**
         * One entry per client tried, so the whole cascade fits on a single log line. Without it,
         * reconstructing "which clients were tried and why each was dropped" means correlating a
         * dozen scattered debug lines — and the answer is the first thing you need for any 403 or
         * IO_UNSPECIFIED report.
         */
        val cascade = mutableListOf<String>()
        fun logCascade(outcome: String) = Fix403.i(
            fx, "cascade.$outcome",
            Fix403.kv("videoId" to videoId, "tried" to cascade.size) + " :: " + cascade.joinToString(" | "),
        )

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                // try with streams from main client first (use retry response if available)
                client = MAIN_CLIENT
                streamPlayerResponse = retryMainPlayerResponse ?: mainPlayerResponse
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                // after main client use fallback clients
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    cascade += "${client.clientName}=SKIP(loginRequired)"
                    Fix403.w(fx, "client.skip", Fix403.kv("client" to client.clientName, "reason" to "loginRequiredButAnonymous"))
                    continue
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                // Only pass poToken for clients that support it
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                // Skip signature timestamp for age-restricted (faster), use it for normal content
                val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else signatureTimestamp.timestamp
                Fix403.i(
                    fx, "client.request",
                    Fix403.kv(
                        "idx" to "${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}",
                        "client" to client.clientName,
                        "clientVersion" to client.clientVersion,
                        "loginSupported" to client.loginSupported,
                        "useWebPoTokens" to client.useWebPoTokens,
                        "sts" to clientSigTimestamp,
                        "poToken" to Fix403.redact(clientPoToken),
                    ),
                )
                streamPlayerResponse = Fix403.trap(fx, "client.request.${client.clientName}") {
                    Fix403.timed(fx, "client.http.${client.clientName}") {
                        // .getOrNull() hides the failure; surface it before discarding it.
                        YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken)
                            .onFailure { Fix403.fail(fx, "client.player.failed.${client.clientName}", it) }
                            .getOrNull()
                    }
                }
                Fix403.i(fx, "client.response", describeResponse(client, streamPlayerResponse))
            }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")

                // Skip NewPipe for age-restricted content (NewPipe doesn't use our auth)
                val responseToUse = if (wasOriginallyAgeRestricted) {
                    Timber.tag(logTag).d("Skipping NewPipe for age-restricted content")
                    streamPlayerResponse
                } else {
                    // Try to get streams using newPipePlayer method
                    val newPipeResponse = YouTube.newPipePlayer(videoId, streamPlayerResponse)
                    newPipeResponse ?: streamPlayerResponse
                }

                format =
                    findFormat(
                        responseToUse,
                        audioQuality,
                        connectivityManager,
                    )

                if (format == null) {
                    Timber.tag(logTag).d("No suitable format found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    cascade += "${client.clientName}=NO_FORMAT"
                    Fix403.w(
                        fx, "client.noFormat",
                        Fix403.kv("client" to client.clientName, "quality" to audioQuality) + " " +
                            describeResponse(client, responseToUse),
                    )
                    continue
                }

                Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                // Which of the three sources produced the URL is decisive: a format's own `url`
                // is pre-signed, a `signatureCipher` needs the WebView deobfuscator, and NewPipe
                // substitutes a URL minted by a different session entirely.
                val urlSource = when {
                    !format.url.isNullOrEmpty() -> "FORMAT_URL"
                    !format.signatureCipher.isNullOrEmpty() || !format.cipher.isNullOrEmpty() -> "SIG_CIPHER"
                    else -> "NEWPIPE_OR_NONE"
                }
                streamUrl = Fix403.trap(fx, "findUrl.${client.clientName}") {
                    findUrlOrNull(format, videoId, responseToUse, skipNewPipe = wasOriginallyAgeRestricted)
                }
                Fix403.i(
                    fx, "client.url",
                    Fix403.kv(
                        "client" to client.clientName,
                        "itag" to format.itag,
                        "mime" to format.mimeType,
                        "bitrate" to format.bitrate,
                        "urlSource" to urlSource,
                        "resolved" to (streamUrl != null),
                    ) + if (streamUrl != null) " " + describeStreamUrl(streamUrl) else "",
                )
                if (streamUrl == null) {
                    Timber.tag(logTag).d("Stream URL not found for format")
                    cascade += "${client.clientName}=NO_URL($urlSource)"
                    Fix403.w(fx, "client.noUrl", Fix403.kv("client" to client.clientName, "urlSource" to urlSource))
                    continue
                }

                // Apply n-transform for throttle parameter handling
                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }

                // Check if this is a privately owned track
                val isPrivatelyOwnedTrack = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
                val musicVideoType = streamPlayerResponse.videoDetails?.musicVideoType

                Timber.tag(TAG).d("=== N-TRANSFORM DECISION ===")
                Timber.tag(TAG).d("Content type analysis:")
                Timber.tag(TAG).d("  musicVideoType: $musicVideoType")
                Timber.tag(TAG).d("  isPrivatelyOwnedTrack: $isPrivatelyOwnedTrack")
                Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")
                Timber.tag(TAG).d("  wasOriginallyAgeRestricted: $wasOriginallyAgeRestricted")
                Timber.tag(TAG).d("Client analysis:")
                Timber.tag(TAG).d("  currentClient: ${currentClient.clientName}")
                Timber.tag(TAG).d("  useWebPoTokens: ${currentClient.useWebPoTokens}")

                // Apply n-transform and PoToken for web clients OR for private tracks (including TVHTML5)
                val needsNTransform = currentClient.useWebPoTokens ||
                    currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5") ||
                    isPrivatelyOwnedTrack

                Timber.tag(TAG).d("N-transform decision:")
                Timber.tag(TAG).d("  needsNTransform: $needsNTransform")
                Timber.tag(TAG).d("  Reason: useWebPoTokens=${currentClient.useWebPoTokens}, " +
                    "clientInList=${currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5")}, " +
                    "isPrivatelyOwnedTrack=$isPrivatelyOwnedTrack")

                if (needsNTransform) {
                    try {
                        Timber.tag(TAG).d("Applying n-transform to stream URL...")
                        Timber.tag(TAG).d("  Original URL length: ${streamUrl.length}")
                        Timber.tag(TAG).d("  Original URL preview: ${streamUrl.take(100)}...")

                        val originalUrl = streamUrl
                        // Use CipherDeobfuscator for n-transform (fixed implementation)
                        streamUrl = CipherDeobfuscator.transformNParamInUrl(streamUrl)

                        Timber.tag(TAG).d("  Transformed URL length: ${streamUrl.length}")
                        Timber.tag(TAG).d("  URL changed: ${originalUrl != streamUrl}")

                        // Append pot= parameter with streaming data poToken
                        val needsPoToken = (currentClient.useWebPoTokens || isPrivatelyOwnedTrack) && poToken?.streamingDataPoToken != null
                        Timber.tag(TAG).d("PoToken decision:")
                        Timber.tag(TAG).d("  needsPoToken: $needsPoToken")
                        Timber.tag(TAG).d("  hasStreamingDataPoToken: ${poToken?.streamingDataPoToken != null}")

                        if (needsPoToken) {
                            Timber.tag(TAG).d("Appending pot= parameter to stream URL")
                            val separator = if ("?" in streamUrl) "&" else "?"
                            streamUrl = "${streamUrl}${separator}pot=${Uri.encode(poToken.streamingDataPoToken)}"
                            Timber.tag(TAG).d("  Final URL length (with pot): ${streamUrl.length}")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "N-transform or pot append failed: ${e.message}")
                        Timber.tag(TAG).e("Stack trace: ${e.stackTraceToString().take(500)}")
                        // Continue with original URL
                    }
                } else {
                    Timber.tag(TAG).d("Skipping n-transform (not required for this client/content)")
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    cascade += "${client.clientName}=NO_EXPIRE"
                    Fix403.w(
                        fx, "client.noExpire",
                        Fix403.kv("client" to client.clientName, "hasStreamingData" to (streamPlayerResponse.streamingData != null)),
                    )
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                // Check if this is a privately owned track (uploaded song)
                val isPrivatelyOwned = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || isPrivatelyOwned) {
                    /** skip [validateStatus] for last client or private tracks */
                    if (isPrivatelyOwned) {
                        Timber.tag(logTag).d("Skipping validation for privately owned track: ${currentClient.clientName}")
                        println("[PLAYBACK_DEBUG] Using stream without validation for PRIVATELY_OWNED_TRACK")
                    } else {
                        Timber.tag(logTag).d("Using last fallback client without validation: ${STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    }
                    Timber.tag(TAG)
                        .i("Playback: client=${currentClient.clientName}, videoId=$videoId, private=$isPrivatelyOwned")
                    cascade += "${currentClient.clientName}=ACCEPTED(unvalidated)"
                    Fix403.i(
                        fx, "client.accepted",
                        Fix403.kv(
                            "client" to currentClient.clientName,
                            "validated" to false,
                            "why" to if (isPrivatelyOwned) "privatelyOwnedTrack" else "lastFallbackClient",
                            "expiresInSeconds" to streamExpiresInSeconds,
                        ) + " " + describeStreamUrl(streamUrl),
                    )
                    logCascade("resolved")
                    break
                }

                if (validateStatus(
                        streamUrl,
                        format.contentLength,
                        Fix403.kv("fx" to fx, "client" to currentClient.clientName, "itag" to format.itag),
                    )
                ) {
                    // working stream found
                    Timber.tag(logTag).d("Stream validated successfully with client: ${currentClient.clientName}")
                    // Log for release builds
                    Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId")
                    cascade += "${currentClient.clientName}=ACCEPTED"
                    Fix403.i(
                        fx, "client.accepted",
                        Fix403.kv(
                            "client" to currentClient.clientName,
                            "validated" to true,
                            "expiresInSeconds" to streamExpiresInSeconds,
                        ) + " " + describeStreamUrl(streamUrl),
                    )
                    logCascade("resolved")
                    break
                } else {
                    Timber.tag(logTag).d("Stream validation failed for client: ${currentClient.clientName}")
                    cascade += "${currentClient.clientName}=REJECTED(validate)"
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
                cascade += "${client.clientName}=NOT_OK(${streamPlayerResponse?.playabilityStatus?.status ?: "null"})"
                Fix403.w(
                    fx, "client.notOk",
                    Fix403.kv(
                        "client" to client.clientName,
                        "status" to streamPlayerResponse?.playabilityStatus?.status,
                        "reason" to streamPlayerResponse?.playabilityStatus?.reason,
                    ),
                )
            }
        }

        // Every throw below means the cascade ran to exhaustion. Emit the whole cascade first —
        // the exception message alone ("Could not find stream url") never says which clients were
        // tried or why each one was dropped, which is the only thing that identifies the cause.
        if (streamPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            if (isUploadedTrack) {
                println("[PLAYBACK_DEBUG] FAILURE: All clients failed for uploaded track videoId=$videoId")
            }
            logCascade("exhausted")
            Fix403.e(fx, "resolve.failed", Fix403.kv("videoId" to videoId, "why" to "badStreamPlayerResponse"))
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            if (isUploadedTrack) {
                println("[PLAYBACK_DEBUG] FAILURE: Playability not OK for uploaded track - status=${streamPlayerResponse.playabilityStatus.status}, reason=$errorReason")
            }
            logCascade("exhausted")
            Fix403.e(
                fx, "resolve.failed",
                Fix403.kv(
                    "videoId" to videoId,
                    "why" to "playabilityNotOk",
                    "status" to streamPlayerResponse.playabilityStatus.status,
                    "reason" to errorReason,
                ),
            )
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            logCascade("exhausted")
            Fix403.e(fx, "resolve.failed", Fix403.kv("videoId" to videoId, "why" to "missingExpireTime"))
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            Timber.tag(logTag).e("Could not find format")
            logCascade("exhausted")
            Fix403.e(fx, "resolve.failed", Fix403.kv("videoId" to videoId, "why" to "noFormat"))
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url")
            logCascade("exhausted")
            Fix403.e(fx, "resolve.failed", Fix403.kv("videoId" to videoId, "why" to "noStreamUrl"))
            throw Exception("Could not find stream url")
        }

        Fix403.i(
            fx, "resolve.success",
            Fix403.kv("videoId" to videoId, "itag" to format.itag, "expiresInSeconds" to streamExpiresInSeconds) +
                " " + describeStreamUrl(streamUrl),
        )

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        if (isUploadedTrack) {
            println("[PLAYBACK_DEBUG] SUCCESS: Got playback data for uploaded track - format=${format.mimeType}, streamUrl=${streamUrl.take(100)}...")
        }
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }.onFailure { e ->
        println("[PLAYBACK_DEBUG] EXCEPTION during playback for videoId=$videoId: ${e::class.simpleName}: ${e.message}")
        e.printStackTrace()
        // This runCatching is the last place the original exception exists intact: MusicService
        // rewraps it into a DataSourceException and Media3 truncates the chain further downstream.
        Fix403.fail(
            Fix403.nextId("resolve-fail"), "resolve.exception", e,
            Fix403.kv("videoId" to videoId, "playlistId" to playlistId),
        )
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val format = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    /**
     * Checks if the stream url returns a successful status.
     *
     * **The probe must mirror the request ExoPlayer actually issues.** `MusicService`'s resolver
     * ends with `.subrange(0, CHUNK_LENGTH)`, so the first media request is always
     * `Range: bytes=0-524287`. A Range-*less* probe is a different request, and googlevideo
     * answers it with **403 on every YouTube Music art track** (`- Topic` uploads — i.e. nearly
     * everything this app plays) while serving the ranged GET with 206. Measured:
     *
     * ```
     * videoId       client   HEAD(noRange)  HEAD(Range0-512k)  GET(Range0-512k)
     * Rr1Cdli5nE8   IPADOS   403            206                206   "Like That"
     * phLb_SoPBlA   IPADOS   403            206                206   "Not Like Us"
     * dQw4w9WgXcQ   IPADOS   200            206                206   ordinary video
     * ```
     *
     * Ordinary videos answer 200, which is why a Range-less probe looks fine in casual testing
     * and fails systematically in production. Sending the Range makes a 403 here mean the stream
     * really is forbidden, which in turn makes rejecting it safe.
     *
     * Rules here:
     *  - 2xx (200/206) → valid
     *  - 405 → treat as valid (HEAD method refused outright; ExoPlayer will GET)
     *  - 403/410 → invalid; move on to the next fallback client
     *  - IOException (timeout/reset) → treat as valid; ExoPlayer has its own retry and
     *    killing the client here just cascades us down the fallback chain for no reason
     *  - other HTTP codes (4xx/5xx) → invalid
     *
     * **The probe must also reach past the preview window.** IOS/IPADOS/old-ANDROID_VR URLs are
     * served only for a fixed prefix of roughly 1 MiB and 403 beyond it (see the
     * [STREAM_FALLBACK_CLIENTS] KDoc for the measured per-video caps). A probe that only asks for
     * `bytes=0-524287` sits entirely inside that window, so it returns 206 for a stream that is
     * guaranteed to die around 60 seconds in — it accepts precisely the URLs we need to reject.
     *
     * So when the format's `contentLength` is known we probe the **last byte of the file**
     * instead: a URL that will serve its final byte is not a truncated preview, and the check is
     * independent of where any particular cap happens to fall. We fall back to the first-chunk
     * probe only when `contentLength` is absent.
     *
     * Remaining known discrepancy: we send `Cookie` here and ExoPlayer does not.
     */
    private fun validateStatus(url: String, contentLength: Long? = null, label: String = ""): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            // Last byte when we know the size, else the first chunk ExoPlayer will ask for.
            val range = if (contentLength != null && contentLength > 0) {
                "bytes=${contentLength - 1}-${contentLength - 1}"
            } else {
                "bytes=0-${VALIDATION_CHUNK_LENGTH - 1}"
            }
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)
                .addHeader("Range", range)

            YouTube.cookie?.let { cookie ->
                requestBuilder.addHeader("Cookie", cookie)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.close()
            val code = response.code
            val accepted = response.isSuccessful || code == 405
            when {
                !accepted ->
                    Timber.tag(logTag).w("Stream URL REJECTED: code=$code range=$range $label ${describeStreamUrl(url)}")
                !response.isSuccessful ->
                    Timber.tag(logTag).w("Stream URL accepted on non-2xx code=$code (HEAD refused) range=$range $label")
                else ->
                    Timber.tag(logTag).d("Stream URL validation: code=$code range=$range accepted $label")
            }
            return accepted
        } catch (e: java.io.IOException) {
            // Network timeout / reset while HEAD-probing. The stream URL itself may still
            // be fine — let ExoPlayer attempt GET rather than burning a fallback client.
            Timber.tag(logTag).w(e, "Stream URL HEAD probe failed (IO); accepting optimistically")
            return true
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }
    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        return result.fold(
            onSuccess = { timestamp ->
                Timber.tag(logTag).d("Signature timestamp obtained: $timestamp")
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                    error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    Timber.tag(logTag).d("Age-restricted content detected from NewPipe")
                    Timber.tag(TAG).i("Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    Timber.tag(logTag).e(error, "Failed to get signature timestamp")
                    reportException(error)
                }
                SignatureTimestampResult(null, isAgeRestricted)
            }
        )
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        // First check if format already has a URL
        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        // Try custom cipher deobfuscation for signatureCipher formats
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            Timber.tag(logTag).d("Format has signatureCipher, using custom deobfuscation")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("Custom cipher deobfuscation failed")
        }

        // Skip NewPipe for age-restricted content
        if (skipNewPipe) {
            Timber.tag(logTag).d("Skipping NewPipe methods for age-restricted content")
            return null
        }

        // Try to get URL using NewPipeExtractor signature deobfuscation
        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        // Fallback: try to get URL from StreamInfo
        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            // If exact itag not found, try to find any audio stream
            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }
}
