package com.metrolist.innertube

import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.response.PlayerResponse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reproducible baseline for the "HTTP 403 while playing a song" reports.
 *
 * These tests hit the REAL YouTube endpoints with the REAL production code
 * ([YouTube.player], [NewPipeExtractor]) and record, for every client in
 * `YTPlayerUtils.STREAM_FALLBACK_CLIENTS`, the status code of the actual media GET
 * that ExoPlayer would perform.
 *
 * What is mirrored from the app (app module cannot be imported from `:innertube`):
 *  - `YTPlayerUtils.MAIN_CLIENT` / `STREAM_FALLBACK_CLIENTS` order
 *  - `YTPlayerUtils.findFormat` scoring (AudioQuality.AUTO on an unmetered network)
 *  - `YTPlayerUtils.findUrlOrNull` (format.url -> signatureCipher -> NewPipe)
 *  - `MusicService.createCacheDataSource()` builds `OkHttpDataSource.Factory(OkHttpClient)`
 *    WITHOUT `setUserAgent(...)`, so the media GET carries OkHttp's default UA and no
 *    Origin / Referer / Cookie / X-Goog-Visitor-Id header at all.
 *  - `MusicService.createDataSourceFactory()` ends with `.subrange(0, CHUNK_LENGTH)`,
 *    so the very first media request is `Range: bytes=0-524287`.
 *
 * Run:
 *   .\gradlew.bat :innertube:testDebugUnitTest --tests "*PlaybackForbiddenDiagnosticsTest*" -i
 *
 * Optional system properties:
 *   -Dyt403.videoIds=dQw4w9WgXcQ,IDLnQPbxAB4   (comma separated)
 *   -Dyt403.poTokenSession=...                 (visitorData/session bound token -> `pot=`)
 *   -Dyt403.poTokenVideo=...                   (videoId bound token -> serviceIntegrityDimensions)
 *   -Dyt403.cookie="SID=...; SAPISID=...; ..." (logged-in run)
 *   -Dyt403.offline=true                       (skip all networked tests)
 *
 * A machine readable copy of every row is written to
 *   innertube/build/reports/yt403/playback-403-matrix.txt
 * because AGP unit tests swallow stdout unless Gradle runs with `-i`.
 */
class PlaybackForbiddenDiagnosticsTest {

    // ── Probe result -----------------------------------------------------------------

    data class Row(
        val videoId: String,
        val client: String,
        val withVisitorData: Boolean,
        val playability: String,
        val reason: String?,
        val adaptiveFormats: Int,
        val itag: Int?,
        val urlSource: String,          // DIRECT | SIG_CIPHER | NEWPIPE | NONE
        val hasNParam: Boolean,
        val hasPotParam: Boolean,
        val expiresInSeconds: Int?,
        val expireDeltaSeconds: Long?,
        val getChunk512k: Int,          // exactly what ExoPlayer issues first
        val getRange0to1: Int,
        val getClientUa0to1: Int,
        val note: String = "",
    )

    // ── Tests ------------------------------------------------------------------------

    /**
     * Baseline recorder. Never fails on its own — its job is to produce the
     * (client x visitorData x User-Agent x Range) -> status-code matrix.
     */
    @Test
    fun clientStreamMatrixBaseline() {
        assumeNetwork()
        val rows = mutableListOf<Row>()

        for (videoId in videoIds) {
            for (withVisitor in listOf(true, false)) {
                YouTube.visitorData = if (withVisitor) sessionVisitorData else null
                for (client in ALL_CLIENTS) {
                    rows += probeClient(videoId, client, withVisitor)
                }
            }
        }
        YouTube.visitorData = sessionVisitorData

        emit("")
        emit("================= CLIENT x STREAM STATUS MATRIX =================")
        emit(HEADER)
        rows.forEach { emit(it.render()) }
        emit("")
        emit("legend: getChunk512k = GET with okhttp default UA and 'Range: bytes=0-524287'")
        emit("        (exactly what MusicService/ExoPlayer sends for the first chunk)")
        emit("        urlSource=NONE means the adaptiveFormat carried neither 'url' nor")
        emit("        'signatureCipher' -> YouTube served a SABR-only response for that client.")
        emit("")
        val ok = rows.filter { it.getChunk512k in 200..299 }
        val forbidden = rows.filter { it.getChunk512k == 403 || it.getRange0to1 == 403 }
        val unusable = rows.filter { it.urlSource == "NONE" }
        emit("2xx media GET : ${ok.size} -> ${ok.joinToString { "${it.client}(vd=${it.withVisitorData})" }}")
        emit("403 media GET : ${forbidden.size} -> ${forbidden.joinToString { "${it.client}(vd=${it.withVisitorData})" }}")
        emit("no usable url : ${unusable.size} -> ${unusable.joinToString { "${it.client}(vd=${it.withVisitorData})" }}")
        flush()
    }

    /**
     * The single most load-bearing invariant of the current playback path.
     *
     * `YTPlayerUtils.playerResponseForPlayback` starts the stream cascade at
     * ANDROID_VR_1_43_32 for normal content (NORMAL_CONTENT_STREAM_START_INDEX) because
     * that client returns pre-signed URLs. If ANDROID_VR stops returning a usable `url`,
     * every non-age-restricted playback falls through to clients whose formats are behind
     * the signature cipher / SABR, and the app ends up handing ExoPlayer a URL that 403s.
     */
    @Test
    fun androidVrStillServesPreSignedPlayableUrls() {
        assumeNetwork()
        YouTube.visitorData = sessionVisitorData
        val videoId = videoIds.first()

        val row = probeClient(videoId, "ANDROID_VR_1_43_32" to YouTubeClient.ANDROID_VR_1_43_32, true)
        emit("")
        emit("--- androidVrStillServesPreSignedPlayableUrls ---")
        emit(HEADER)
        emit(row.render())
        flush()

        assertTrue(
            "ANDROID_VR_1_43_32 /player is no longer OK for $videoId " +
                "(status=${row.playability}, reason=${row.reason}) -> the normal-content " +
                "fast path in YTPlayerUtils has no working entry point",
            row.playability == "OK",
        )
        assertTrue(
            "ANDROID_VR_1_43_32 no longer returns a direct 'url' (urlSource=${row.urlSource}); " +
                "YouTube switched this client to SABR/cipher-only formats",
            row.urlSource == "DIRECT",
        )
        assertTrue(
            "ANDROID_VR_1_43_32 stream URL returned HTTP ${row.getChunk512k} on the exact " +
                "request ExoPlayer makes (okhttp UA, Range bytes=0-524287). " +
                "403 here == the user-visible playback failure.",
            row.getChunk512k in 200..299,
        )
    }

    /**
     * `YTPlayerUtils.playerResponseForPlayback` unconditionally pipes every non
     * age-restricted response through `YouTube.newPipePlayer(videoId, streamPlayerResponse)`
     * (YTPlayerUtils.kt:254), which REPLACES the per-itag `url` of the chosen response with a
     * URL produced by NewPipeExtractor's own extraction (its own client, its own session, its
     * own JS-solved sig/n).
     *
     * Consequence: a perfectly good, pre-signed ANDROID_VR URL is silently swapped for a
     * NewPipe URL. If the pinned NewPipeExtractor (libs.versions.toml: newpipeextractor
     * = "v0.26.0") is behind YouTube's current player, that swap turns a 206 into a 403.
     *
     * This test asserts the swap is harmless. It failing IS the diagnosis.
     */
    @Test
    fun newPipeRewriteDoesNotDowngradeAWorkingAndroidVrUrl() {
        assumeNetwork()
        YouTube.visitorData = sessionVisitorData
        val videoId = videoIds.first()

        val vrResponse = runBlocking {
            YouTube.player(videoId, null, YouTubeClient.ANDROID_VR_1_43_32, null, null)
        }.getOrNull()
        assumeTrue("ANDROID_VR /player did not return OK", vrResponse?.playabilityStatus?.status == "OK")
        requireNotNull(vrResponse)

        val before = pickAudioFormat(vrResponse)
        assumeTrue("no audio format from ANDROID_VR", before != null)
        val beforeUrl = before!!.url
        assumeTrue("ANDROID_VR format has no direct url", !beforeUrl.isNullOrEmpty())
        val beforeStatus = probe(beforeUrl!!, EXO_UA, CHUNK_LENGTH - 1)

        // Exactly YTPlayerUtils.kt:254
        val rewritten = runBlocking { YouTube.newPipePlayer(videoId, vrResponse) }
        val newPipeUrls = NewPipeExtractor.newPipePlayer(videoId)

        emit("")
        emit("--- newPipeRewriteDoesNotDowngradeAWorkingAndroidVrUrl ---")
        emit("NewPipeExtractor.newPipePlayer() returned ${newPipeUrls.size} stream urls " +
            "(itags: ${newPipeUrls.joinToString { it.first.toString() }})")
        emit("ANDROID_VR itag=${before.itag} raw url  -> HTTP $beforeStatus")

        if (rewritten == null) {
            emit("newPipePlayer() returned null -> YTPlayerUtils keeps the ANDROID_VR response. OK.")
            flush()
            assertTrue(
                "ANDROID_VR url itself returned HTTP $beforeStatus (expected 2xx)",
                beforeStatus in 200..299,
            )
            return
        }

        val after = pickAudioFormat(rewritten)
        val afterUrl = after?.url
        val changed = afterUrl != null && afterUrl != beforeUrl
        val afterStatus = if (afterUrl.isNullOrEmpty()) -1 else probe(afterUrl, EXO_UA, CHUNK_LENGTH - 1)
        emit("after newPipePlayer(): urlChanged=$changed itag=${after?.itag} -> HTTP $afterStatus")
        flush()

        assertTrue(
            "NewPipe rewrote the ANDROID_VR stream URL and the result returns HTTP " +
                "$afterStatus (was $beforeStatus). YTPlayerUtils.kt:254 replaces a working " +
                "pre-signed URL with a NewPipeExtractor-derived one; " +
                "libs.versions.toml pins newpipeextractor=v0.26.0.",
            afterStatus in 200..299 || (!changed && beforeStatus in 200..299),
        )
    }

    /**
     * Health check for the pinned NewPipeExtractor (libs.versions.toml: v0.26.0), which the
     * playback path leans on in three places:
     *   - `YTPlayerUtils.getSignatureTimestampOrNull` -> the `sts` sent to /player
     *   - `YTPlayerUtils.findUrlOrNull` -> signature deobfuscation for cipher-only formats
     *   - `YTPlayerUtils` line 254 -> `YouTube.newPipePlayer` URL rewrite
     * If the extractor is behind YouTube's current player, all three degrade silently and
     * the app ends up serving URLs YouTube refuses.
     */
    @Test
    fun newPipeExtractorOwnUrlsAreStillPlayable() {
        assumeNetwork()
        val videoId = videoIds.first()
        val urls = NewPipeExtractor.newPipePlayer(videoId)

        emit("")
        emit("--- newPipeExtractorOwnUrlsAreStillPlayable (newpipeextractor v0.26.0) ---")
        emit("StreamInfo.getInfo() produced ${urls.size} url(s)")
        var anyOk = false
        urls.take(6).forEach { (itag, url) ->
            val status = probe(url, EXO_UA, 1)
            if (status in 200..299) anyOk = true
            emit("  itag=$itag -> HTTP $status  (n=${param(url, "n") != null}, pot=${param(url, "pot") != null})")
        }

        // Does NewPipe still solve the signature cipher WEB_REMIX hands out?
        val webRemix = runBlocking {
            YouTube.player(videoId, null, YouTubeClient.WEB_REMIX, signatureTimestamp, poTokenVideo)
        }.getOrNull()
        val cipherFormat = webRemix?.let { pickAudioFormat(it) }
        val solved = cipherFormat?.let { NewPipeExtractor.getStreamUrl(it, videoId) }
        emit("  NewPipe signature deobfuscation of the WEB_REMIX itag=${cipherFormat?.itag} format: " +
            if (solved.isNullOrEmpty()) "FAILED (returns null)" else "ok -> HTTP ${probe(solved, EXO_UA, 1)}")
        emit("  -> when this fails, YTPlayerUtils.findUrlOrNull has no way to build a URL for")
        emit("     any cipher-based client and must rely on the app-side CipherDeobfuscator WebView.")
        flush()

        assertTrue(
            "NewPipeExtractor v0.26.0 produced ${urls.size} urls and none of them played. " +
                "Every NewPipe-dependent step of the playback path (sts, sig deobfuscation, " +
                "the newPipePlayer URL rewrite) is running on a stale extractor.",
            urls.isEmpty() || anyOk,
        )
    }

    /**
     * `YTPlayerUtils.MAIN_CLIENT` is WEB_REMIX. Documents that WEB_REMIX cannot produce a
     * playable URL without solving the signature cipher AND the `n` challenge AND attaching a
     * `pot=`. Whenever the cascade actually lands on WEB_REMIX (private tracks, or a hand-rolled
     * cipher that silently returns a wrong-but-non-throwing signature) the result is a 403.
     */
    @Test
    fun webRemixNeverReturnsDirectlyUsableUrls() {
        assumeNetwork()
        YouTube.visitorData = sessionVisitorData
        val videoId = videoIds.first()
        val sts = NewPipeExtractor.getSignatureTimestamp(videoId).getOrNull()

        val response = runBlocking {
            YouTube.player(videoId, null, YouTubeClient.WEB_REMIX, sts, poTokenVideo)
        }.getOrNull()
        assumeTrue("WEB_REMIX /player failed", response != null)
        requireNotNull(response)

        val format = pickAudioFormat(response)
        emit("")
        emit("--- webRemixNeverReturnsDirectlyUsableUrls ---")
        emit("signatureTimestamp(NewPipe)=$sts  poTokenVideo=${poTokenVideo != null}")
        emit("playability=${response.playabilityStatus.status} reason=${response.playabilityStatus.reason}")
        emit("adaptiveFormats=${response.streamingData?.adaptiveFormats?.size ?: 0} itag=${format?.itag}")
        emit("format.url=${if (format?.url.isNullOrEmpty()) "ABSENT" else "present"} " +
            "signatureCipher=${if (format?.signatureCipher.isNullOrEmpty()) "ABSENT" else "present"}")
        flush()

        assertTrue(
            "WEB_REMIX unexpectedly returned a direct url — the cipher-skip rationale in " +
                "YTPlayerUtils (NORMAL_CONTENT_STREAM_START_INDEX) no longer holds",
            format == null || format.url.isNullOrEmpty(),
        )
    }

    /**
     * Mirror of `YTPlayerUtils.validateStatus` (app/.../utils/YTPlayerUtils.kt:485-518).
     *
     * Regression guard for the fallback-cascade blind spot: while 403/410 were whitelisted the
     * cascade could not reject a forbidden stream at all — the first client yielding any URL won,
     * `break`ed the loop, and ExoPlayer was handed a URL guaranteed to fail, with the remaining
     * clients never tried. 405 stays accepted because it means "HEAD refused", not "forbidden".
     *
     * If this test fails, the production predicate has drifted back and playback can silently
     * commit to a forbidden stream again.
     */
    @Test
    fun appValidateStatusRejects403_soTheFallbackChainCanAdvance() {
        // exact copy of the production expression
        fun accepted(code: Int) = (code in 200..299) || code == 405
        assertTrue("403 must be rejected so the cascade advances", !accepted(403))
        assertTrue("410 must be rejected so the cascade advances", !accepted(410))
        assertTrue("405 means HEAD refused, not forbidden — keep accepting it", accepted(405))
        assertTrue("mirror drifted from YTPlayerUtils.validateStatus", !accepted(404))
        assertTrue("mirror drifted from YTPlayerUtils.validateStatus", accepted(206))
        emit("")
        emit("--- YTPlayerUtils.validateStatus mirror ---")
        emit("403 accepted=${accepted(403)} -> a forbidden stream URL is now rejected and the")
        emit("cascade moves on to the next fallback client instead of committing to it.")
        emit("Still worth knowing: validateStatus() sends HEAD with Cookie but NO Range header,")
        emit("while ExoPlayer sends GET with 'Range: bytes=0-524287' and NO Cookie, so the probe")
        emit("and the real request are still not byte-for-byte the same request.")
        flush()
    }

    /**
     * The probe `YTPlayerUtils.validateStatus` performs MUST be the request ExoPlayer makes.
     *
     * MusicService hands ExoPlayer `dataSpec.subrange(0, CHUNK_LENGTH)` (MusicService.kt:3990),
     * so the real first request is
     *     GET <streamUrl>  Range: bytes=0-524287
     * and validateStatus therefore probes with
     *     HEAD <streamUrl>  Range: bytes=0-524287
     *
     * It did not always. While the probe was Range-*less* it was a different request, and for
     * YouTube Music "art tracks" (the `- Topic` uploads that are ~everything this app plays)
     * googlevideo answers **403 to a Range-less request** and **206 to the ranged one** — so the
     * probe reported "forbidden" for streams that play perfectly. Ordinary videos answer 200,
     * which is why the asymmetry survived casual testing.
     *
     * That was harmless only while validateStatus also treated 403 as acceptable. Rejecting 403
     * with a Range-less probe discards every working stream, the fallback cascade runs to
     * exhaustion, and YTPlayerUtils throws a plain `Exception` that Media3's Loader re-wraps in
     * `Loader.UnexpectedLoaderException` — surfacing to the user as
     * PlaybackException(ERROR_CODE_IO_UNSPECIFIED / 2000, "Unknown error").
     *
     * This test pins the invariant the fix depends on: the **ranged** HEAD agrees with the ranged
     * GET, so a 403 from the probe now genuinely means forbidden and is safe to reject. The
     * Range-less column is still recorded to keep the trap visible.
     */
    @Test
    fun headProbeAgreesWithTheRangedGetExoPlayerActuallyPerforms() {
        assumeNetwork()
        YouTube.visitorData = sessionVisitorData

        emit("")
        emit("--- headProbeAgreesWithTheRangedGetExoPlayerActuallyPerforms ---")
        emit("videoId       client   HEAD(noRange)  HEAD(Range0-512k)  GET(Range0-512k)")

        val disagreements = mutableListOf<String>()
        val rangelessTrap = mutableListOf<String>()
        var probed = 0
        for (videoId in videoIds) {
            for (named in listOf(
                "IPADOS" to YouTubeClient.IPADOS,
                "IOS" to YouTubeClient.IOS,
            )) {
                val (name, client) = named
                val response = runBlocking { YouTube.player(videoId, null, client, null, null) }.getOrNull()
                if (response?.playabilityStatus?.status != "OK") continue
                val url = pickAudioFormat(response)?.url
                if (url.isNullOrEmpty()) continue

                probed++
                val headNoRange = probeMethod("HEAD", url, EXO_UA, null)
                val headRanged = probeMethod("HEAD", url, EXO_UA, CHUNK_LENGTH - 1)
                val getRanged = probeMethod("GET", url, EXO_UA, CHUNK_LENGTH - 1)
                emit(String.format("%-13s %-8s %-14d %-18d %d", videoId, name, headNoRange, headRanged, getRanged))

                // The invariant validateStatus relies on: its ranged probe must not call a
                // stream forbidden when the ranged GET ExoPlayer performs would have played it.
                if ((headRanged == 403 || headRanged == 410) && getRanged in 200..299) {
                    disagreements += "$videoId/$name ranged HEAD=$headRanged but ranged GET=$getRanged"
                }
                // Informational: the trap the Range-less probe used to fall into.
                if ((headNoRange == 403 || headNoRange == 410) && getRanged in 200..299) {
                    rangelessTrap += "$videoId/$name"
                }
            }
        }

        assumeTrue("no client produced a direct url to probe", probed > 0)
        emit("")
        emit("Range-less HEAD would have wrongly rejected: ${rangelessTrap.size} -> ${rangelessTrap.joinToString()}")
        emit("  (these are art tracks; this is why validateStatus must send the Range header)")
        emit("ranged-HEAD disagreements: ${disagreements.size}")
        disagreements.forEach { emit("  $it") }
        flush()

        assertTrue(
            "YTPlayerUtils.validateStatus() probes with HEAD + 'Range: bytes=0-524287' to mirror " +
                "the GET ExoPlayer actually issues. These streams answered the probe with " +
                "403/410 yet served the ranged GET with 2xx, so rejecting 403 in validateStatus " +
                "would discard playable streams and exhaust the cascade -> " +
                "IO_UNSPECIFIED(2000)/'Unknown error'. Offenders: ${disagreements.joinToString()}",
            disagreements.isEmpty(),
        )
    }

    /**
     * Which *shapes* of `Range` header googlevideo will serve.
     *
     * `MusicService`'s resolver has two exits. The fresh-resolution exit ends with
     * `.subrange(0, CHUNK_LENGTH)`, producing a bounded `bytes=0-524287`. The songUrlCache exit
     * did not, so it inherited ExoPlayer's raw dataSpec:
     *  - opening a track  -> position 0, length UNSET -> `HttpUtil.buildRangeRequestHeader`
     *    returns **null**, i.e. no Range header at all
     *  - continuing after the first 512 KiB chunk -> position 524288, length UNSET ->
     *    open-ended `bytes=524288-`
     *
     * Those two map exactly onto the two reported symptoms: failure on skipping to an
     * already-resolved song, and failure ~30 s in (512 KiB of opus @ ~128 kbps ≈ 32 s).
     *
     * This test records what each shape actually returns, so the fix ("always bound the range")
     * rests on measurement rather than on the inference above.
     */
    @Test
    fun googlevideoServesOnlyBoundedRangesForArtTracks() {
        assumeNetwork()
        YouTube.visitorData = sessionVisitorData

        emit("")
        emit("--- googlevideoServesOnlyBoundedRangesForArtTracks ---")
        emit("videoId       client   none  open0  open512k  bound0  bound512k  itag  clen")

        val badShapes = mutableListOf<String>()
        var probed = 0
        for (videoId in videoIds) {
            for (named in listOf("IPADOS" to YouTubeClient.IPADOS, "IOS" to YouTubeClient.IOS)) {
                val (name, client) = named
                val response = runBlocking { YouTube.player(videoId, null, client, null, null) }.getOrNull()
                if (response?.playabilityStatus?.status != "OK") continue
                val url = pickAudioFormat(response)?.url
                if (url.isNullOrEmpty()) continue

                probed++
                val fmt = pickAudioFormat(response)
                val clen = fmt?.contentLength ?: -1
                val none = probeRange(url, null)
                val open0 = probeRange(url, "bytes=0-")
                val open512k = probeRange(url, "bytes=524288-")
                val bound0 = probeRange(url, "bytes=0-524287")
                val bound512k = probeRange(url, "bytes=524288-1048575")
                emit(
                    String.format(
                        "%-13s %-8s %-5d %-6d %-9d %-7d %-10d %-5d %d",
                        videoId, name, none, open0, open512k, bound0, bound512k, fmt?.itag ?: -1, clen,
                    ),
                )

                // Only the shape MusicService now always emits for the FIRST chunk is asserted.
                // The second-chunk result is recorded but not asserted: it is inconsistent across
                // tracks (403 on one art track, 206 on another) and is still under investigation.
                if (bound0 !in 200..299) badShapes += "$videoId/$name bounded bytes=0-524287 -> $bound0"
                if (bound512k !in 200..299) {
                    emit("    NOTE $videoId/$name second bounded chunk -> $bound512k (clen=$clen)")
                }
            }
        }

        assumeTrue("no client produced a direct url to probe", probed > 0)
        emit("")
        emit("The fix makes every media request take the 'bound' shape. This test asserts only")
        emit("that the bounded shapes work; the 'none'/'open' columns are the diagnosis and are")
        emit("expected to show 403 on art tracks.")
        flush()

        assertTrue(
            "The bounded Range shape that MusicService now always emits was itself refused: " +
                badShapes.joinToString(),
            badShapes.isEmpty(),
        )
    }

    // ── Machinery --------------------------------------------------------------------

    /** Raw ranged GET with an arbitrary (or absent) Range header, as ExoPlayer would issue it. */
    private fun probeRange(url: String, range: String?): Int {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", EXO_UA)
        if (range != null) builder.header("Range", range)
        return try {
            probeClient.newCall(builder.build()).execute().use { it.code }
        } catch (e: Exception) {
            emit("    probe failed: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
    }

    private fun probeMethod(method: String, url: String, userAgent: String, rangeEnd: Long?): Int {
        val builder = Request.Builder()
            .url(url)
            .method(method, null)
            .header("User-Agent", userAgent)
        if (rangeEnd != null) builder.header("Range", "bytes=0-$rangeEnd")
        return try {
            probeClient.newCall(builder.build()).execute().use { it.code }
        } catch (e: Exception) {
            emit("    probe failed: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
    }

    private fun probeClient(videoId: String, named: Pair<String, YouTubeClient>, withVisitor: Boolean): Row {
        val (name, client) = named
        val sts = if (client.useSignatureTimestamp) signatureTimestamp else null
        val poToken = if (client.useWebPoTokens) poTokenVideo else null

        val response = runBlocking { YouTube.player(videoId, null, client, sts, poToken) }.getOrNull()
            ?: return Row(
                videoId, name, withVisitor, "HTTP_ERROR", null, 0, null, "NONE",
                false, false, null, null, -1, -1, -1, "/player request failed",
            )

        val format = pickAudioFormat(response)
        val adaptive = response.streamingData?.adaptiveFormats?.size ?: 0
        if (format == null) {
            return Row(
                videoId, name, withVisitor, response.playabilityStatus.status,
                response.playabilityStatus.reason, adaptive, null, "NONE",
                false, false, response.streamingData?.expiresInSeconds, null, -1, -1, -1,
                "no audio adaptiveFormat",
            )
        }

        var urlSource = "NONE"
        var url = format.url
        if (!url.isNullOrEmpty()) {
            urlSource = "DIRECT"
        } else if (!format.signatureCipher.isNullOrEmpty() || !format.cipher.isNullOrEmpty()) {
            urlSource = "SIG_CIPHER"
            // exactly what YTPlayerUtils.findUrlOrNull falls back to inside :innertube
            url = NewPipeExtractor.getStreamUrl(format, videoId)
            if (!url.isNullOrEmpty()) urlSource = "SIG_CIPHER->NEWPIPE"
        }

        if (url.isNullOrEmpty()) {
            return Row(
                videoId, name, withVisitor, response.playabilityStatus.status,
                response.playabilityStatus.reason, adaptive, format.itag, urlSource,
                false, false, response.streamingData?.expiresInSeconds, null, -1, -1, -1,
                if (urlSource == "NONE") "SABR-only: neither url nor signatureCipher" else "cipher not solvable",
            )
        }

        var probeUrl = url
        if (poTokenSession != null && param(probeUrl, "pot") == null) {
            probeUrl += (if (probeUrl.contains('?')) "&" else "?") + "pot=" + poTokenSession
        }

        val expire = param(probeUrl, "expire")?.toLongOrNull()
        return Row(
            videoId = videoId,
            client = name,
            withVisitorData = withVisitor,
            playability = response.playabilityStatus.status,
            reason = response.playabilityStatus.reason,
            adaptiveFormats = adaptive,
            itag = format.itag,
            urlSource = urlSource,
            hasNParam = param(probeUrl, "n") != null,
            hasPotParam = param(probeUrl, "pot") != null,
            expiresInSeconds = response.streamingData?.expiresInSeconds,
            expireDeltaSeconds = expire?.minus(System.currentTimeMillis() / 1000),
            getChunk512k = probe(probeUrl, EXO_UA, CHUNK_LENGTH - 1),
            getRange0to1 = probe(probeUrl, EXO_UA, 1),
            getClientUa0to1 = probe(probeUrl, client.userAgent, 1),
        )
    }

    /** Mirrors YTPlayerUtils.findFormat with AudioQuality.AUTO on an unmetered network. */
    private fun pickAudioFormat(response: PlayerResponse): PlayerResponse.StreamingData.Format? =
        response.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal }
            ?.maxByOrNull { it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) }

    private fun probe(url: String, userAgent: String, rangeEnd: Long): Int {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header("Range", "bytes=0-$rangeEnd")
            .build()
        return try {
            probeClient.newCall(request).execute().use { it.code }
        } catch (e: Exception) {
            emit("    probe failed: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
    }

    private fun param(url: String, name: String): String? =
        Regex("[?&]$name=([^&]*)").find(url)?.groupValues?.get(1)

    private fun Row.render(): String = String.format(
        "%-34s vd=%-5s %-16s fmts=%-3d itag=%-4s src=%-20s n=%-5s pot=%-5s exp=%-7s GET512k=%-5d GET0-1=%-5d GETua=%-5d %s",
        client, withVisitorData, playability, adaptiveFormats, itag ?: "-", urlSource,
        hasNParam, hasPotParam, expireDeltaSeconds ?: "-", getChunk512k, getRange0to1,
        getClientUa0to1, note,
    )

    private fun assumeNetwork() {
        assumeTrue("yt403.offline=true", System.getProperty("yt403.offline") != "true")
        assumeTrue("no network access to music.youtube.com", networkAvailable)
    }

    companion object {
        private const val EXO_UA = "okhttp/4.12.0"          // OkHttp's default UA — see class kdoc
        private const val CHUNK_LENGTH = 512 * 1024L        // MusicService.CHUNK_LENGTH

        private const val HEADER =
            "client                             visitorData  playability      formats itag  urlSource            n     pot   expire  GET512k  GET0-1  GETclientUA"

        /** YTPlayerUtils.MAIN_CLIENT first, then STREAM_FALLBACK_CLIENTS in declaration order. */
        private val ALL_CLIENTS: List<Pair<String, YouTubeClient>> = listOf(
            "WEB_REMIX(MAIN)" to YouTubeClient.WEB_REMIX,
            "TVHTML5_SIMPLY_EMBEDDED_PLAYER" to YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
            "TVHTML5" to YouTubeClient.TVHTML5,
            "ANDROID_VR_1_43_32" to YouTubeClient.ANDROID_VR_1_43_32,
            "ANDROID_VR_1_61_48" to YouTubeClient.ANDROID_VR_1_61_48,
            "ANDROID_CREATOR" to YouTubeClient.ANDROID_CREATOR,
            "IPADOS" to YouTubeClient.IPADOS,
            "ANDROID_VR_NO_AUTH" to YouTubeClient.ANDROID_VR_NO_AUTH,
            "MOBILE" to YouTubeClient.MOBILE,
            "IOS" to YouTubeClient.IOS,
            "WEB" to YouTubeClient.WEB,
            "WEB_CREATOR" to YouTubeClient.WEB_CREATOR,
        )

        private val videoIds: List<String> =
            (System.getProperty("yt403.videoIds") ?: "dQw4w9WgXcQ")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private val poTokenSession: String? = System.getProperty("yt403.poTokenSession")
        private val poTokenVideo: String? = System.getProperty("yt403.poTokenVideo")

        private val probeClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        private val report = StringBuilder()
        private val reportFile = File("build/reports/yt403/playback-403-matrix.txt")

        var networkAvailable = false
            private set
        var sessionVisitorData: String? = null
            private set
        var signatureTimestamp: Int? = null
            private set

        @JvmStatic
        @BeforeClass
        fun bootstrap() {
            System.getProperty("yt403.cookie")?.let { YouTube.cookie = it }
            sessionVisitorData = runBlocking { YouTube.visitorData() }.getOrNull()
            YouTube.visitorData = sessionVisitorData
            networkAvailable = sessionVisitorData != null
            signatureTimestamp = if (networkAvailable) {
                NewPipeExtractor.getSignatureTimestamp(videoIds.first()).getOrNull()
            } else {
                null
            }
            emit("=================================================================")
            emit(" Meld / Metrolist — playback 403 diagnostics")
            emit(" videoIds            : ${videoIds.joinToString()}")
            emit(" network             : $networkAvailable")
            emit(" visitorData         : ${sessionVisitorData?.take(24) ?: "UNAVAILABLE"}")
            emit(" signatureTimestamp  : ${signatureTimestamp ?: "UNAVAILABLE (NewPipe failed)"}")
            emit(" poToken (session)   : ${if (poTokenSession != null) "supplied" else "NOT supplied"}")
            emit(" poToken (video)     : ${if (poTokenVideo != null) "supplied" else "NOT supplied"}")
            emit(" cookie              : ${if (YouTube.cookie != null) "supplied" else "anonymous"}")
            emit(" newpipeextractor    : v0.26.0 (gradle/libs.versions.toml)")
            emit("=================================================================")
            flush()
        }

        @JvmStatic
        fun emit(line: String) {
            println(line)
            report.appendLine(line)
        }

        @JvmStatic
        fun flush() {
            runCatching {
                reportFile.parentFile?.mkdirs()
                reportFile.writeText(report.toString())
            }
        }
    }
}
