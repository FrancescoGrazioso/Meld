/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.qobuz

import com.metrolist.music.constants.QobuzAudioQuality
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

object QobuzAudioProvider {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

    // Single tag for the whole Qobuz resolution path so the full lifecycle of a
    // resolution can be followed with `adb logcat -s Qobuz`.
    const val LOG_TAG = "Qobuz"

    const val SQUID_BASE_URL = "https://qobuz.squid.wtf"
    const val JUMO_BASE_URL = "https://jumo-dl.pages.dev"
    const val KENNY_BASE_URL = "https://qobuz.kennyy.com.br"
    const val TRYPT_BASE_URL = "https://trypt-hifi-dl-456461932686.us-west1.run.app"
    private const val STREAM_CACHE_MS = 5 * 60 * 1000L
    private const val REJECT_SCORE = -1_000_000
    private const val MIN_REASONABLE_BITRATE = 32_000
    private const val MAX_REASONABLE_BITRATE = 20_000_000
    private const val DEFAULT_LOSSLESS_CHANNELS = 2
    private const val FLAC_ESTIMATED_COMPRESSION_RATIO = 0.6

    private val JUMO_SUPPORTED_REGIONS = setOf("FR", "NL", "NZ", "JP")

    enum class ResolverBackend {
        MONOKENNY,
        JUMO,
        SQUID,
        TRYPT,
    }

    private enum class SearchBackend {
        KENNY,
        SQUID,
        TRYPT,
    }

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val countryCode: String,
        val backend: ResolverBackend,
        val qualityCode: Int = 27,
    )

    data class Resolved(
        val mediaUri: String,
        val trackId: String,
        val label: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val expiresAtMs: Long,
        val hires: Boolean = false,
        val bitDepth: Int? = null,
        val samplingRateKhz: Double? = null,
        val isrc: String? = null,
    )

    class QobuzResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class MatchedTrack(
        val trackId: String,
        val hires: Boolean,
        val bitDepth: Int?,
        val samplingRateKhz: Double?,
        val isrc: String? = null,
    )

    private data class StreamAttempt(
        val resolved: Resolved? = null,
        val error: String? = null,
    )

    // Tight timeouts so a dead/stalling backend fails fast on the FIRST play
    // (before its cooldown is set). The MusicService withTimeout() wrapper can't
    // interrupt these blocking OkHttp calls — cancellation is cooperative and
    // execute() has no suspension point — so callTimeout is the real per-request
    // cap that stops a Cloudflare 522 from hanging ~20s before we mark the host
    // down and fall back to YouTube.
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()

    private val trackCache = ConcurrentHashMap<String, MatchedTrack>()
    private val streamCache = ConcurrentHashMap<String, Resolved>()
    // Backend cooldown after captcha/403. Skip the backend until the timestamp.
    private val backendCooldownUntilMs = ConcurrentHashMap<ResolverBackend, Long>()
    private val CAPTCHA_COOLDOWN_MS = 5 * 60 * 1000L

    // Dead-host cooldown, keyed by hostname. When a backend host returns a hard
    // infrastructure failure (DNS miss, connection refused, 5xx, or an HTML
    // maintenance/"offline" page instead of JSON) we stop hitting it for a few
    // minutes. Without this a fully-down backend burns the entire search matrix
    // (up to ~7 terms × 3 search backends) plus the 4-step quality ladder on
    // long socket timeouts before the caller can fall back to YouTube.
    private val hostCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private const val HOST_COOLDOWN_MS = 3 * 60 * 1000L

    // Runtime-overridable endpoints. Blank/invalid falls back to the built-in
    // default constant. Set from settings via [configureEndpoints] so users can
    // point at a self-hosted squid.wtf instance when the public mirrors are down.
    @Volatile private var squidBaseOverride: String? = null
    @Volatile private var kennyBaseOverride: String? = null
    @Volatile private var tryptBaseOverride: String? = null
    @Volatile private var jumoBaseOverride: String? = null

    val squidBase: String get() = squidBaseOverride ?: SQUID_BASE_URL
    val kennyBase: String get() = kennyBaseOverride ?: KENNY_BASE_URL
    val tryptBase: String get() = tryptBaseOverride ?: TRYPT_BASE_URL
    val jumoBase: String get() = jumoBaseOverride ?: JUMO_BASE_URL

    /**
     * Overrides the resolver base URLs from settings. A blank or malformed value
     * clears the override and reverts to the built-in default for that backend.
     */
    fun configureEndpoints(
        squid: String?,
        kenny: String?,
        trypt: String?,
        jumo: String?,
    ) {
        fun norm(value: String?): String? =
            value?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() && it.toHttpUrlOrNull() != null }
        squidBaseOverride = norm(squid)
        kennyBaseOverride = norm(kenny)
        tryptBaseOverride = norm(trypt)
        jumoBaseOverride = norm(jumo)
        Timber.tag(LOG_TAG).d(
            "endpoints configured | squid=%s kenny=%s trypt=%s jumo=%s",
            squidBase, kennyBase, tryptBase, jumoBase,
        )
    }

    private fun hostOf(baseUrl: String): String = baseUrl.toHttpUrlOrNull()?.host ?: baseUrl

    /** Remaining cooldown ms for [baseUrl]'s host, or null when it's clear. */
    private fun hostCoolingRemainingMs(baseUrl: String): Long? {
        val host = hostOf(baseUrl)
        val until = hostCooldownUntilMs[host] ?: return null
        val now = System.currentTimeMillis()
        return if (until > now) {
            until - now
        } else {
            hostCooldownUntilMs.remove(host)
            null
        }
    }

    private fun markHostDown(baseUrl: String, reason: String) {
        val host = hostOf(baseUrl)
        hostCooldownUntilMs[host] = System.currentTimeMillis() + HOST_COOLDOWN_MS
        Timber.tag(LOG_TAG).w(
            "host %s marked down (%s), cooldown %dm", host, reason, HOST_COOLDOWN_MS / 60_000,
        )
    }

    /** A network-level exception that means the host is unreachable, not a soft error. */
    private fun isHostUnreachable(error: Throwable): Boolean =
        error is java.net.UnknownHostException ||
            error is java.net.ConnectException ||
            // Covers both SocketTimeoutException (connect/read timeout) and the
            // plain InterruptedIOException that OkHttp's callTimeout throws.
            error is java.io.InterruptedIOException ||
            error.message?.contains("Unable to resolve host", ignoreCase = true) == true

    /** A JSON API never starts with '<'; an HTML body means a proxy/maintenance page. */
    private fun looksLikeHtml(payload: String): Boolean {
        val head = payload.trimStart().take(1).firstOrNull()
        return head == '<'
    }

    fun qualityCodeFor(audioQuality: QobuzAudioQuality): Int =
        when (audioQuality) {
            QobuzAudioQuality.AAC_320 -> 5
            QobuzAudioQuality.CD_QUALITY -> 6
            QobuzAudioQuality.HI_RES_LOSSLESS -> 27
        }

    fun normalizeResolverRegion(
        countryCode: String,
        backend: ResolverBackend,
    ): String {
        val normalized = countryCode.trim().uppercase(Locale.US)
        return when (backend) {
            ResolverBackend.JUMO -> normalized.takeIf { it in JUMO_SUPPORTED_REGIONS } ?: "FR"
            ResolverBackend.SQUID -> normalized.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
            ResolverBackend.MONOKENNY -> normalized.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
            ResolverBackend.TRYPT -> normalized.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
        }
    }

    fun resolve(query: Query): Resolved {
        val resolverRegion = normalizeResolverRegion(query.countryCode, query.backend)
        val streamCacheKey = query.cacheKey(resolverRegion)
        val trackSearchRegion = when (query.backend) {
            ResolverBackend.JUMO -> resolverRegion
            ResolverBackend.SQUID -> query.countryCode
            ResolverBackend.MONOKENNY -> resolverRegion
            ResolverBackend.TRYPT -> resolverRegion
        }
        val now = System.currentTimeMillis()

        Timber.tag(LOG_TAG).d(
            "resolve ▶ '%s' by '%s' | backend=%s region=%s quality=%d isrc=%s dur=%sms",
            query.title,
            query.artists.joinToString(),
            query.backend.name,
            resolverRegion,
            query.qualityCode,
            query.isrc?.takeIf { it.isNotBlank() } ?: "-",
            query.durationMs?.toString() ?: "-",
        )

        // Skip backend if it's in captcha cooldown
        backendCooldownUntilMs[query.backend]?.let { cooldownUntil ->
            if (cooldownUntil > now) {
                val remainingSec = (cooldownUntil - now) / 1000
                Timber.tag(LOG_TAG).d(
                    "resolve ✘ '%s': backend %s in captcha cooldown (%ds left)",
                    query.title, query.backend.name, remainingSec,
                )
                throw QobuzResolutionException(
                    "Qobuz backend ${query.backend.name} in cooldown for " +
                        "${remainingSec}s after captcha"
                )
            } else {
                backendCooldownUntilMs.remove(query.backend)
            }
        }

        streamCache[streamCacheKey]
            ?.takeIf { it.expiresAtMs > now + 20_000L }
            ?.let {
                Timber.tag(LOG_TAG).d("resolve ✔ '%s': stream-cache hit (%s)", query.title, it.label)
                return it
            }

        // Direct-ID short-circuit: when the mediaId encodes a Qobuz track ID
        // (bare digits, "qobuz:track:<id>" URI, or a qobuz.com URL), skip the
        // fuzzy matcher entirely and go straight to stream resolution.
        val directTrackId = query.mediaId.toQobuzTrackIdOrNull()
        val trackCacheKey = query.trackCacheKey()
        val track = if (directTrackId != null) {
            Timber.tag(LOG_TAG).d("resolve · '%s': direct track id %s (skipping search)", query.title, directTrackId)
            MatchedTrack(
                trackId = directTrackId,
                hires = false,
                bitDepth = null,
                samplingRateKhz = null,
                isrc = query.isrc?.takeIf { it.isNotBlank() },
            )
        } else {
            trackCache[trackCacheKey]
                ?.also { Timber.tag(LOG_TAG).d("resolve · '%s': track-cache hit id=%s", query.title, it.trackId) }
                ?: run {
                    Timber.tag(LOG_TAG).d("resolve · '%s': searching (region=%s)…", query.title, trackSearchRegion)
                    val lookup = findBestTrack(query, trackSearchRegion)
                    lookup.track?.also {
                        trackCache[trackCacheKey] = it
                        Timber.tag(LOG_TAG).d("resolve · '%s': search matched id=%s", query.title, it.trackId)
                    } ?: run {
                        val reason = lookup.error?.takeIf { it.isNotBlank() }
                        if (reason != null) {
                            Timber.tag(LOG_TAG).d("resolve ✘ '%s': search failed: %s", query.title, reason)
                            throw QobuzResolutionException("Qobuz search failed for ${query.title}: $reason")
                        }
                        Timber.tag(LOG_TAG).d("resolve ✘ '%s': no catalog match", query.title)
                        throw QobuzResolutionException("Qobuz match not found for ${query.title}")
                    }
                }
        }

        var lastError: String? = null
        for (quality in buildQualityFallbackOrder(query.qualityCode)) {
            val attempt = when (query.backend) {
                ResolverBackend.JUMO -> requestJumoStream(track, quality, resolverRegion, query.durationMs)
                ResolverBackend.SQUID -> requestSquidStream(track, query.countryCode, quality, query.durationMs)
                ResolverBackend.MONOKENNY -> requestMonoStream(track, quality, resolverRegion, query.durationMs)
                ResolverBackend.TRYPT -> requestTrypTStream(track, quality, resolverRegion, query.durationMs)
            }
            attempt.resolved?.let { resolved ->
                streamCache[streamCacheKey] = resolved
                Timber.tag(LOG_TAG).d(
                    "resolve ✔ '%s' via %s quality=%d → %s (%dbps%s, trackId=%s)",
                    query.title,
                    query.backend.name,
                    quality,
                    resolved.label,
                    resolved.bitrate,
                    resolved.sampleRate?.let { ", ${it}Hz" } ?: "",
                    resolved.trackId,
                )
                return resolved
            }
            if (!attempt.error.isNullOrBlank()) {
                lastError = attempt.error
                Timber.tag(LOG_TAG).d(
                    "stream ✘ '%s' (%s, region=%s, quality=%d): %s",
                    query.title,
                    query.backend.name,
                    resolverRegion,
                    quality,
                    attempt.error,
                )
                if (attempt.error.contains("captcha", ignoreCase = true)) {
                    backendCooldownUntilMs[query.backend] = System.currentTimeMillis() + CAPTCHA_COOLDOWN_MS
                    Timber.tag(LOG_TAG).w(
                        "Backend %s captcha-blocked, cooldown for %d minutes",
                        query.backend.name,
                        CAPTCHA_COOLDOWN_MS / 60_000,
                    )
                    break
                }
                if (attempt.error.contains("preview", ignoreCase = true)) {
                    // Preview = track not in catalog at this quality tier.
                    // Cascading to lower qualities almost always hits the same
                    // preview wall — short-circuit to save ~9s per failure.
                    Timber.tag(LOG_TAG).d(
                        "stream · preview at quality %d for '%s', aborting ladder",
                        quality,
                        query.title,
                    )
                    break
                }
                if (attempt.error.contains("cooling down", ignoreCase = true) ||
                    attempt.error.contains("offline", ignoreCase = true) ||
                    attempt.error.contains("HTTP 5", ignoreCase = true)
                ) {
                    // Host is down/cooling — every quality tier hits the same wall.
                    // Abort the ladder immediately so the caller falls back fast.
                    Timber.tag(LOG_TAG).d(
                        "stream · backend down for '%s', aborting ladder", query.title,
                    )
                    break
                }
            }
        }

        Timber.tag(LOG_TAG).d(
            "resolve ✘ '%s' via %s: exhausted quality ladder (%s)",
            query.title, query.backend.name, lastError ?: "no stream",
        )
        throw QobuzResolutionException(lastError ?: "Qobuz stream not found for ${query.title}")
    }

    fun invalidate(mediaId: String) {
        streamCache.keys
            .filter { it.startsWith("$mediaId::") }
            .forEach { streamCache.remove(it) }
    }

    fun primeKnownTrack(
        query: Query,
        trackId: String,
        hires: Boolean,
        bitDepth: Int?,
        samplingRateKhz: Double?,
        isrc: String?,
    ) {
        trackCache[query.trackCacheKey()] = MatchedTrack(
            trackId = trackId,
            hires = hires,
            bitDepth = bitDepth,
            samplingRateKhz = samplingRateKhz,
            isrc = isrc?.takeIf { it.isNotBlank() },
        )
    }

    private data class TrackLookupResult(
        val track: MatchedTrack? = null,
        val error: String? = null,
    )

    private fun findBestTrack(
        query: Query,
        searchRegion: String,
    ): TrackLookupResult {
        var lastSearchError: String? = null
        for (term in searchTerms(query)) {
            for (backend in searchBackendOrder(query.backend)) {
                val search = when (backend) {
                    SearchBackend.KENNY -> searchTracks(term, query.countryCode, SearchBackend.KENNY)
                    SearchBackend.SQUID -> when (query.backend) {
                        ResolverBackend.MONOKENNY -> searchTracks(term, searchRegion, SearchBackend.SQUID)
                        ResolverBackend.JUMO -> searchTracksJumo(term, searchRegion)
                        ResolverBackend.SQUID -> searchTracks(term, searchRegion, SearchBackend.SQUID)
                        ResolverBackend.TRYPT -> searchTracks(term, searchRegion, SearchBackend.SQUID)
                    }
                    SearchBackend.TRYPT -> searchTracks(term, searchRegion, SearchBackend.TRYPT)
                }
                if (search.error != null) {
                    lastSearchError = search.error
                    Timber.tag(LOG_TAG).d(
                        "search ✘ term='%s' backend=%s: %s", term, backend.name, search.error,
                    )
                }
                val results = search.items ?: continue
                Timber.tag(LOG_TAG).d(
                    "search · term='%s' backend=%s → %d results", term, backend.name, results.length(),
                )
                selectBestTrack(results, query)?.let {
                    Timber.tag(LOG_TAG).d(
                        "search ✔ matched id=%s (term='%s', backend=%s)", it.trackId, term, backend.name,
                    )
                    return TrackLookupResult(track = it)
                }
            }
        }
        Timber.tag(LOG_TAG).d(
            "search ✘ '%s': no match across %d term(s)", query.title, searchTerms(query).size,
        )
        return TrackLookupResult(error = lastSearchError)
    }

    private fun searchBackendOrder(preferred: ResolverBackend): List<SearchBackend> {
        // JUMO has no search endpoint — when JUMO is the preferred stream
        // backend we lead search with TRYPT instead. Other backends lead with
        // their own search and fall through.
        return when (preferred) {
            ResolverBackend.MONOKENNY -> listOf(SearchBackend.KENNY, SearchBackend.TRYPT, SearchBackend.SQUID)
            ResolverBackend.JUMO -> listOf(SearchBackend.TRYPT, SearchBackend.KENNY, SearchBackend.SQUID)
            ResolverBackend.SQUID -> listOf(SearchBackend.SQUID, SearchBackend.TRYPT, SearchBackend.KENNY)
            ResolverBackend.TRYPT -> listOf(SearchBackend.TRYPT, SearchBackend.KENNY, SearchBackend.SQUID)
        }
    }

    private data class TrackSearchResult(
        val items: JSONArray? = null,
        val error: String? = null,
    )

    private fun searchTracks(
        term: String,
        countryCode: String,
        backend: SearchBackend,
    ): TrackSearchResult {
        val baseUrl = when (backend) {
            SearchBackend.KENNY -> kennyBase
            SearchBackend.SQUID -> squidBase
            SearchBackend.TRYPT -> tryptBase
        }
        hostCoolingRemainingMs(baseUrl)?.let { remaining ->
            return TrackSearchResult(
                error = "${backend.name} search skipped: host cooling down for ${remaining / 1000}s",
            )
        }
        val url = "$baseUrl/api/get-music".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", term)
            ?.addQueryParameter("offset", "0")
            ?.build()
            ?: return TrackSearchResult(error = "Qobuz search URL could not be built")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Referer", "$baseUrl/")
            .header("User-Agent", "Mozilla/5.0")
            .apply {
                if (backend == SearchBackend.SQUID || backend == SearchBackend.TRYPT) {
                    header("Token-Country", countryCode)
                }
            }
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code in 500..599) {
                        markHostDown(baseUrl, "HTTP ${response.code}")
                    }
                    return@use TrackSearchResult(
                        error = "${backend.name} search HTTP ${response.code}: ${payload.take(160)}"
                    )
                }
                if (payload.isBlank()) {
                    return@use TrackSearchResult(error = "${backend.name} search returned an empty response")
                }
                if (looksLikeHtml(payload)) {
                    markHostDown(baseUrl, "HTML response (offline/maintenance page)")
                    return@use TrackSearchResult(
                        error = "${backend.name} search backend offline (returned HTML, not JSON)"
                    )
                }
                val root = JSONObject(payload)
                if (!root.optBoolean("success", false)) {
                    return@use TrackSearchResult(
                        error = "${backend.name} search rejected request: ${root.stringOrNull("error") ?: "unknown error"}"
                    )
                }
                TrackSearchResult(
                    items = root.optJSONObject("data")
                        ?.optJSONObject("tracks")
                        ?.optJSONArray("items")
                )
            }
        }.getOrElse { error ->
            if (isHostUnreachable(error)) {
                markHostDown(baseUrl, error.message ?: error.javaClass.simpleName)
            }
            TrackSearchResult(error = "${backend.name} search request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun searchTracksJumo(
        term: String,
        region: String,
    ): TrackSearchResult {
        hostCoolingRemainingMs(jumoBase)?.let { remaining ->
            return TrackSearchResult(error = "JUMO search skipped: host cooling down for ${remaining / 1000}s")
        }
        val url = "$jumoBase/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("query", term)
            ?.addQueryParameter("offset", "0")
            ?.addQueryParameter("limit", "30")
            ?.addQueryParameter("region", region)
            ?.build()
            ?: return TrackSearchResult(error = "JUMO search URL could not be built")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code in 500..599) {
                        markHostDown(jumoBase, "HTTP ${response.code}")
                    }
                    return@use TrackSearchResult(
                        error = "JUMO search HTTP ${response.code}: ${payload.take(160)}"
                    )
                }
                if (payload.isBlank()) {
                    return@use TrackSearchResult(error = "JUMO search returned an empty response")
                }
                if (looksLikeHtml(payload)) {
                    markHostDown(jumoBase, "HTML response (offline/maintenance page)")
                    return@use TrackSearchResult(
                        error = "JUMO search backend offline (returned HTML, not JSON)"
                    )
                }
                val root = JSONObject(payload)
                if (!root.optBoolean("success", false)) {
                    return@use TrackSearchResult(
                        error = "JUMO search rejected request: ${root.stringOrNull("error") ?: "unknown error"}"
                    )
                }
                TrackSearchResult(
                    items = root.optJSONObject("data")
                        ?.optJSONObject("tracks")
                        ?.optJSONArray("items")
                )
            }
        }.getOrElse { error ->
            if (isHostUnreachable(error)) {
                markHostDown(jumoBase, error.message ?: error.javaClass.simpleName)
            }
            TrackSearchResult(error = "JUMO search request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun selectBestTrack(
        results: JSONArray,
        query: Query,
    ): MatchedTrack? {
        val wantedTitle = query.title.normalized()
        val wantedArtists = query.artists.map { it.normalized() }.filter { it.isNotBlank() }
        val wantedAlbum = query.album.normalized()
        val wantedIsrc = query.isrc?.trim()?.uppercase(Locale.US).orEmpty()
        val wantedDescriptorText = listOf(wantedTitle, wantedAlbum).joinToString(" ")
        val wantedDurationSec = query.durationMs?.let { (it / 1000L).toInt() }
        val wantedTitleTokens = significantTokens(wantedTitle)

        data class Candidate(
            val track: MatchedTrack,
            val score: Int,
        )
        data class EvaluatedCandidate(
            val trackId: String,
            val title: String,
            val artists: String,
            val score: Int,
            val downloadable: Boolean,
        )

        val candidates = mutableListOf<Candidate>()
        val evaluated = mutableListOf<EvaluatedCandidate>()
        val minAcceptScore = if (query.backend == ResolverBackend.JUMO) 300 else 330
        for (index in 0 until results.length()) {
            val obj = results.optJSONObject(index) ?: continue
            val downloadable = obj.optBoolean("downloadable", false)
            val streamable = obj.optBoolean("streamable", false)
            if (query.backend == ResolverBackend.SQUID && !downloadable && !streamable) continue

            val trackId = obj.stringOrNull("id") ?: continue
            val candidateTitle = obj.stringOrNull("title").normalized()
            val candidateVersion = obj.stringOrNull("version").normalized()
            val candidateCombinedTitle = listOf(candidateTitle, candidateVersion)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val candidateAlbum = obj.optJSONObject("album")
                ?.stringOrNull("title")
                .normalized()
            val candidateIsrc = obj.stringOrNull("isrc")?.trim()?.uppercase(Locale.US).orEmpty()
            val candidateDuration = obj.intOrNull("duration")
            val candidateArtists = collectArtistNames(obj).map { it.normalized() }.filter { it.isNotBlank() }
            val hires = obj.optBoolean("hires", false)
            val bitDepth = obj.intOrNull("maximum_bit_depth")
            val samplingRate = obj.doubleOrNull("maximum_sampling_rate")
            val candidateTitleTokens = significantTokens(candidateCombinedTitle)
            val matchedTitleTokens = wantedTitleTokens.count(candidateTitleTokens::contains)
            val titleRecall = if (wantedTitleTokens.isEmpty()) 1.0 else matchedTitleTokens.toDouble() / wantedTitleTokens.size
            val titlePrecision = if (candidateTitleTokens.isEmpty()) 0.0 else matchedTitleTokens.toDouble() / candidateTitleTokens.size
            val exactArtistMatches = wantedArtists.count { wanted -> candidateArtists.any { it == wanted } }
            val partialArtistMatches = wantedArtists.count { wanted ->
                candidateArtists.any { candidate -> artistNamesMatch(wanted, candidate) }
            }

            val versionPenalty = versionMismatchPenalty(wantedDescriptorText, candidateCombinedTitle)
            if (versionPenalty <= REJECT_SCORE) continue
            if (!titleTokensPass(
                    wantedTitle = wantedTitle,
                    candidateTitle = candidateCombinedTitle,
                    wantedTokens = wantedTitleTokens,
                    matchedTokenCount = matchedTitleTokens,
                    recall = titleRecall,
                    precision = titlePrecision,
                )
            ) {
                continue
            }
            if (wantedArtists.isNotEmpty() && exactArtistMatches == 0 && partialArtistMatches == 0) continue
            if (wantedDurationSec != null && candidateDuration != null && abs(wantedDurationSec - candidateDuration) > 25) continue

            var score = 0
            score += versionPenalty

            if (wantedIsrc.isNotBlank() && candidateIsrc == wantedIsrc) {
                score += 1000
            }

            if (wantedTitle.isNotBlank()) {
                score += when {
                    candidateTitle == wantedTitle -> 360
                    candidateCombinedTitle == wantedTitle -> 340
                    candidateCombinedTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle) -> 180
                    wantedTitle.wordsOverlap(candidateCombinedTitle) >= 3 -> 110
                    else -> -120
                }
            }

            if (wantedTitleTokens.isNotEmpty()) {
                when {
                    matchedTitleTokens == wantedTitleTokens.size -> score += 180
                    matchedTitleTokens >= wantedTitleTokens.size.coerceAtLeast(1) - 1 -> score += 70
                    matchedTitleTokens >= wantedTitleTokens.size.coerceAtLeast(3) - 2 -> score -= 20
                    else -> score -= 180
                }
            }

            if (wantedAlbum.isNotBlank() && candidateAlbum.isNotBlank()) {
                score += when {
                    candidateAlbum == wantedAlbum -> 160
                    candidateAlbum.contains(wantedAlbum) || wantedAlbum.contains(candidateAlbum) -> 60
                    wantedAlbum.wordsOverlap(candidateAlbum) >= 2 -> 35
                    else -> -50
                }
            }

            if (wantedArtists.isNotEmpty()) {
                score += when {
                    exactArtistMatches > 0 -> 260 + ((exactArtistMatches - 1) * 55)
                    partialArtistMatches > 0 -> 120 + ((partialArtistMatches - 1) * 40)
                    else -> REJECT_SCORE
                }
            }

            if (wantedDurationSec != null && candidateDuration != null) {
                val diff = abs(wantedDurationSec - candidateDuration)
                score += when {
                    diff <= 2 -> 180
                    diff <= 5 -> 120
                    diff <= 10 -> 50
                    diff <= 15 -> 10
                    else -> -90
                }
            }

            if (hires) score += 15
            if (!downloadable && !streamable) score -= 25

            evaluated += EvaluatedCandidate(
                trackId = trackId,
                title = candidateCombinedTitle,
                artists = candidateArtists.joinToString(),
                score = score,
                downloadable = downloadable || streamable,
            )

            if (score > REJECT_SCORE && (score >= minAcceptScore || wantedIsrc.isNotBlank() && candidateIsrc == wantedIsrc)) {
                candidates += Candidate(
                    track = MatchedTrack(
                        trackId = trackId,
                        hires = hires,
                        bitDepth = bitDepth,
                        samplingRateKhz = samplingRate,
                        isrc = candidateIsrc.takeIf { it.isNotBlank() },
                    ),
                    score = score,
                )
            }
        }

        if (candidates.isEmpty()) {
            val top = evaluated
                .sortedByDescending { it.score }
                .take(3)
                .joinToString(" | ") {
                    "${it.trackId} score=${it.score} downloadable=${it.downloadable} title='${it.title}' artists='${it.artists}'"
                }
            if (top.isNotBlank()) {
                Timber.tag(LOG_TAG).w(
                    "No acceptable Qobuz candidate for '%s' by '%s' (backend=%s, country=%s, min=%d). Top matches: %s",
                    query.title,
                    query.artists.joinToString(),
                    query.backend.name,
                    query.countryCode,
                    minAcceptScore,
                    top,
                )
            }
        }

        return candidates.maxByOrNull { it.score }?.track
    }

    private fun requestSquidStream(
        track: MatchedTrack,
        countryCode: String,
        qualityCode: Int,
        durationMs: Long?,
    ): StreamAttempt {
        hostCoolingRemainingMs(squidBase)?.let { remaining ->
            return StreamAttempt(error = "Qobuz backend cooling down for ${remaining / 1000}s")
        }
        val url = "$squidBase/api/download-music".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("track_id", track.trackId)
            ?.addQueryParameter("quality", qualityCode.toString())
            ?.build()
            ?: return StreamAttempt(error = "Qobuz request URL could not be built")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Token-Country", countryCode)
            .header("Origin", squidBase)
            .header("Referer", "$squidBase/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code in 500..599) markHostDown(squidBase, "HTTP ${response.code}")
                    return@use StreamAttempt(error = "Qobuz HTTP ${response.code}: ${payload.take(160)}")
                }
                if (payload.isBlank()) {
                    return@use StreamAttempt(error = "Qobuz returned an empty response")
                }
                if (looksLikeHtml(payload)) {
                    markHostDown(squidBase, "HTML response (offline/maintenance page)")
                    return@use StreamAttempt(error = "Qobuz backend offline (returned HTML, not JSON)")
                }
                val root = JSONObject(payload)
                if (!root.optBoolean("success", false)) {
                    val apiError = root.stringOrNull("error")
                    val message = if (apiError.equals("Captcha required.", ignoreCase = true)) {
                        "Qobuz download API requires a browser captcha right now"
                    } else {
                        "Qobuz rejected quality $qualityCode: ${apiError ?: "unknown error"}"
                    }
                    return@use StreamAttempt(error = message)
                }

                val data = root.optJSONObject("data")
                val streamUrl = data?.stringOrNull("url")
                    ?: return@use StreamAttempt(error = "Qobuz did not return a stream URL for quality $qualityCode")
                val bitDepth = data.intOrNull("bit_depth") ?: track.bitDepth
                val samplingRate = data.doubleOrNull("sampling_rate") ?: track.samplingRateKhz
                val lossyBitrate = data.intOrNull("bitrate")
                    ?: data.intOrNull("bit_rate")
                    ?: root.intOrNull("bitrate")
                    ?: root.intOrNull("bit_rate")
                val losslessBitrate = estimateStreamBitrateFromContentLength(streamUrl, durationMs)
                    ?: normalizeBitrate(
                        data.intOrNull("average_bitrate")
                            ?: root.intOrNull("average_bitrate")
                    ).takeIf { it > 0 }
                val format = formatFrom(
                    mimeType = if (qualityCode == 5) "audio/mpeg" else "audio/flac",
                    bitDepth = bitDepth,
                    samplingRateKhz = samplingRate,
                    bitrate = lossyBitrate,
                    losslessBitrate = losslessBitrate,
                    hires = track.hires || (bitDepth ?: 0) > 16 || (samplingRate ?: 0.0) > 44.1 || qualityCode >= 7,
                )
                StreamAttempt(resolved = format.toResolved(streamUrl, track.trackId, track.isrc))
            }
        }.getOrElse { error ->
            if (isHostUnreachable(error)) markHostDown(squidBase, error.message ?: error.javaClass.simpleName)
            StreamAttempt(error = "Qobuz request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requestJumoStream(
        track: MatchedTrack,
        qualityCode: Int,
        region: String,
        durationMs: Long?,
    ): StreamAttempt {
        hostCoolingRemainingMs(jumoBase)?.let { remaining ->
            return StreamAttempt(error = "JUMO backend cooling down for ${remaining / 1000}s")
        }
        val url = "$jumoBase/fetch".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("track_id", track.trackId)
            ?.addQueryParameter("format_id", qualityCode.toString())
            ?.addQueryParameter("region", region)
            ?.build()
            ?: return StreamAttempt(error = "JUMO request URL could not be built")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", jumoBase)
            .header("Referer", "$jumoBase/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code in 500..599) markHostDown(jumoBase, "HTTP ${response.code}")
                    return@use StreamAttempt(error = "JUMO HTTP ${response.code}: ${payload.take(160)}")
                }
                if (payload.isBlank()) {
                    return@use StreamAttempt(error = "JUMO returned an empty response")
                }
                if (looksLikeHtml(payload)) {
                    markHostDown(jumoBase, "HTML response (offline/maintenance page)")
                    return@use StreamAttempt(error = "JUMO backend offline (returned HTML, not JSON)")
                }
                val root = JSONObject(payload)
                root.stringOrNull("error")?.takeIf { it.isNotBlank() }?.let { apiError ->
                    return@use StreamAttempt(error = "JUMO rejected quality $qualityCode: $apiError")
                }
                if (root.optBoolean("previewDetected", false)) {
                    return@use StreamAttempt(error = "JUMO returned a preview instead of a full Qobuz stream")
                }

                val streamUrl = root.stringOrNull("directUrl")
                    ?: root.stringOrNull("url")
                    ?: return@use StreamAttempt(error = "JUMO did not return a stream URL for quality $qualityCode")
                val bitDepth = root.intOrNull("bit_depth") ?: track.bitDepth
                val samplingRate = root.doubleOrNull("sampling_rate") ?: track.samplingRateKhz
                val mimeType = root.stringOrNull("mime_type")
                    ?: if (qualityCode == 5) "audio/mpeg" else "audio/flac"
                val lossyBitrate = root.intOrNull("bitrate")
                    ?: root.intOrNull("bit_rate")
                val losslessBitrate = estimateStreamBitrateFromContentLength(streamUrl, durationMs)
                    ?: normalizeBitrate(root.intOrNull("average_bitrate")).takeIf { it > 0 }
                val hires = (bitDepth ?: 0) > 16 || (samplingRate ?: 0.0) > 44.1 || qualityCode >= 7
                val format = formatFrom(
                    mimeType = mimeType,
                    bitDepth = bitDepth,
                    samplingRateKhz = samplingRate,
                    bitrate = lossyBitrate,
                    losslessBitrate = losslessBitrate,
                    hires = hires,
                )
                StreamAttempt(
                    resolved = format.toResolved(
                        url = streamUrl,
                        trackId = root.stringOrNull("resolvedTrackId") ?: track.trackId,
                        isrc = track.isrc,
                    )
                )
            }
        }.getOrElse { error ->
            if (isHostUnreachable(error)) markHostDown(jumoBase, error.message ?: error.javaClass.simpleName)
            StreamAttempt(error = "JUMO request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requestMonoStream(
        track: MatchedTrack,
        qualityCode: Int,
        region: String,
        durationMs: Long?,
    ): StreamAttempt {
        hostCoolingRemainingMs(kennyBase)?.let { remaining ->
            return StreamAttempt(error = "Monokenny backend cooling down for ${remaining / 1000}s")
        }
        val url = "$kennyBase/api/download-music".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("track_id", track.trackId)
            ?.addQueryParameter("quality", qualityCode.toString())
            ?.build()
            ?: return StreamAttempt(error = "Monokenny request URL could not be built")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", kennyBase)
            .header("Referer", "$kennyBase/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code in 500..599) markHostDown(kennyBase, "HTTP ${response.code}")
                    return@use StreamAttempt(error = "Monokenny HTTP ${response.code}: ${payload.take(160)}")
                }
                if (payload.isBlank()) {
                    return@use StreamAttempt(error = "Monokenny returned an empty response")
                }
                if (looksLikeHtml(payload)) {
                    markHostDown(kennyBase, "HTML response (offline/maintenance page)")
                    return@use StreamAttempt(error = "Monokenny backend offline (returned HTML, not JSON)")
                }
                val root = JSONObject(payload)
                if (!root.optBoolean("success", false)) {
                    val apiError = root.stringOrNull("error")
                    val message = if (apiError.equals("Captcha required.", ignoreCase = true)) {
                        "Monokenny download API requires a browser captcha right now"
                    } else {
                        "Monokenny rejected quality $qualityCode: ${apiError ?: "unknown error"}"
                    }
                    return@use StreamAttempt(error = message)
                }

                val data = root.optJSONObject("data")
                val streamUrl = data?.stringOrNull("url")
                    ?: return@use StreamAttempt(error = "Monokenny did not return a stream URL for quality $qualityCode")
                val bitDepth = data.intOrNull("bit_depth") ?: track.bitDepth
                val samplingRate = data.doubleOrNull("sampling_rate") ?: track.samplingRateKhz
                val lossyBitrate = data.intOrNull("bitrate")
                    ?: data.intOrNull("bit_rate")
                    ?: root.intOrNull("bitrate")
                    ?: root.intOrNull("bit_rate")
                val losslessBitrate = estimateStreamBitrateFromContentLength(streamUrl, durationMs)
                    ?: normalizeBitrate(
                        data.intOrNull("average_bitrate")
                            ?: root.intOrNull("average_bitrate")
                    ).takeIf { it > 0 }
                val format = formatFrom(
                    mimeType = if (qualityCode == 5) "audio/mpeg" else "audio/flac",
                    bitDepth = bitDepth,
                    samplingRateKhz = samplingRate,
                    bitrate = lossyBitrate,
                    losslessBitrate = losslessBitrate,
                    hires = track.hires || (bitDepth ?: 0) > 16 || (samplingRate ?: 0.0) > 44.1 || qualityCode >= 7,
                )
                StreamAttempt(resolved = format.toResolved(streamUrl, track.trackId, track.isrc))
            }
        }.getOrElse { error ->
            if (isHostUnreachable(error)) markHostDown(kennyBase, error.message ?: error.javaClass.simpleName)
            StreamAttempt(error = "Monokenny request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requestTrypTStream(
        track: MatchedTrack,
        qualityCode: Int,
        region: String,
        durationMs: Long?,
    ): StreamAttempt {
        hostCoolingRemainingMs(tryptBase)?.let { remaining ->
            return StreamAttempt(error = "TrypT backend cooling down for ${remaining / 1000}s")
        }
        val url = "$tryptBase/api/download-music".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("track_id", track.trackId)
            ?.addQueryParameter("quality", qualityCode.toString())
            ?.build()
            ?: return StreamAttempt(error = "TrypT request URL could not be built")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json,text/plain,*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Token-Country", region)
            .header("Origin", tryptBase)
            .header("Referer", "$tryptBase/")
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code in 500..599) markHostDown(tryptBase, "HTTP ${response.code}")
                    return@use StreamAttempt(error = "TrypT HTTP ${response.code}: ${payload.take(160)}")
                }
                if (payload.isBlank()) {
                    return@use StreamAttempt(error = "TrypT returned an empty response")
                }
                if (looksLikeHtml(payload)) {
                    markHostDown(tryptBase, "HTML response (offline/maintenance page)")
                    return@use StreamAttempt(error = "TrypT backend offline (returned HTML, not JSON)")
                }
                val root = JSONObject(payload)
                if (!root.optBoolean("success", true)) {
                    val apiError = root.stringOrNull("error") ?: root.stringOrNull("message")
                    val message = if (apiError != null && apiError.contains("captcha", ignoreCase = true)) {
                        "TrypT download API requires a browser captcha right now"
                    } else {
                        "TrypT rejected quality $qualityCode: ${apiError ?: "unknown error"}"
                    }
                    return@use StreamAttempt(error = message)
                }

                val data = root.optJSONObject("data")
                val streamUrl = data?.stringOrNull("url")
                    ?: root.stringOrNull("url")
                    ?: return@use StreamAttempt(error = "TrypT did not return a stream URL for quality $qualityCode")
                val bitDepth = data?.intOrNull("bit_depth")
                    ?: root.intOrNull("bit_depth")
                    ?: track.bitDepth
                val samplingRate = data?.doubleOrNull("sampling_rate")
                    ?: root.doubleOrNull("sampling_rate")
                    ?: track.samplingRateKhz
                val mimeType = data?.stringOrNull("mime_type")
                    ?: root.stringOrNull("mime_type")
                    ?: if (qualityCode == 5) "audio/mpeg" else "audio/flac"
                val lossyBitrate = data?.intOrNull("bitrate")
                    ?: data?.intOrNull("bit_rate")
                    ?: root.intOrNull("bitrate")
                    ?: root.intOrNull("bit_rate")
                val losslessBitrate = estimateStreamBitrateFromContentLength(streamUrl, durationMs)
                    ?: normalizeBitrate(
                        data?.intOrNull("average_bitrate")
                            ?: root.intOrNull("average_bitrate")
                    ).takeIf { it > 0 }
                val format = formatFrom(
                    mimeType = mimeType,
                    bitDepth = bitDepth,
                    samplingRateKhz = samplingRate,
                    bitrate = lossyBitrate,
                    losslessBitrate = losslessBitrate,
                    hires = track.hires || (bitDepth ?: 0) > 16 || (samplingRate ?: 0.0) > 44.1 || qualityCode >= 7,
                )
                StreamAttempt(resolved = format.toResolved(streamUrl, track.trackId, track.isrc))
            }
        }.getOrElse { error ->
            if (isHostUnreachable(error)) markHostDown(tryptBase, error.message ?: error.javaClass.simpleName)
            StreamAttempt(error = "TrypT request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private data class StreamFormat(
        val label: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val hires: Boolean = false,
        val bitDepth: Int? = null,
        val samplingRateKhz: Double? = null,
    ) {
        fun toResolved(
            url: String,
            trackId: String,
            isrc: String? = null,
        ) = Resolved(
            mediaUri = url,
            trackId = trackId,
            label = label,
            mimeType = mimeType,
            codecs = codecs,
            bitrate = bitrate,
            sampleRate = sampleRate,
            expiresAtMs = extractExpiryMs(url),
            hires = hires,
            bitDepth = bitDepth,
            samplingRateKhz = samplingRateKhz,
            isrc = isrc,
        )
    }

    private fun formatFrom(
        mimeType: String,
        bitDepth: Int?,
        samplingRateKhz: Double?,
        bitrate: Int?,
        losslessBitrate: Int?,
        hires: Boolean,
    ): StreamFormat {
        val lowerMime = mimeType.lowercase(Locale.US)
        val sampleRate = samplingRateKhz
            ?.takeIf { it > 0.0 }
            ?.let { (it * 1000.0).roundToInt() }
        val normalizedBitrate = normalizeBitrate(bitrate)

        return when {
            lowerMime.contains("mpeg") || lowerMime.contains("mp3") -> StreamFormat(
                label = "Qobuz MP3",
                mimeType = "audio/mpeg",
                codecs = "mp3",
                bitrate = normalizedBitrate.takeIf { it > 0 } ?: 320_000,
                sampleRate = sampleRate,
                hires = hires,
                bitDepth = bitDepth,
                samplingRateKhz = samplingRateKhz,
            )

            lowerMime.contains("aac") || lowerMime.contains("mp4") -> StreamFormat(
                label = "Qobuz AAC",
                mimeType = "audio/mp4",
                codecs = "mp4a.40.2",
                bitrate = normalizedBitrate.takeIf { it > 0 } ?: 320_000,
                sampleRate = sampleRate,
                hires = hires,
                bitDepth = bitDepth,
                samplingRateKhz = samplingRateKhz,
            )

            else -> {
                StreamFormat(
                    label = buildFlacLabel(bitDepth, samplingRateKhz, hires),
                    mimeType = "audio/flac",
                    codecs = "flac",
                    bitrate = losslessBitrate
                        ?.takeIf { it > 0 }
                        ?: estimateCompressedLosslessBitrate(bitDepth, samplingRateKhz, hires),
                    sampleRate = sampleRate,
                    hires = hires,
                    bitDepth = bitDepth,
                    samplingRateKhz = samplingRateKhz,
                )
            }
        }
    }

    private fun normalizeBitrate(value: Int?): Int {
        return value
            ?.takeIf { it > 0 }
            ?.let { if (it in 1..9999) it * 1000 else it }
            ?: 0
    }

    private fun estimateStreamBitrateFromContentLength(
        url: String,
        durationMs: Long?,
    ): Int? {
        val safeDuration = durationMs?.takeIf { it > 0L } ?: return null
        val length = fetchStreamContentLength(url) ?: return null
        val bitrate = (length * 8L * 1000L) / safeDuration
        return bitrate
            .takeIf { it in 32_000L..20_000_000L }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
    }

    private fun estimateCompressedLosslessBitrate(
        bitDepth: Int?,
        samplingRateKhz: Double?,
        hires: Boolean,
    ): Int {
        val depth = bitDepth?.takeIf { it > 0 } ?: if (hires) 24 else 16
        val sampleRate = samplingRateKhz
            ?.takeIf { it > 0.0 }
            ?.let { (it * 1000.0).roundToInt() }
            ?: if (hires) 96_000 else 44_100
        val estimated = depth * sampleRate * DEFAULT_LOSSLESS_CHANNELS * FLAC_ESTIMATED_COMPRESSION_RATIO
        return estimated
            .roundToInt()
            .coerceIn(MIN_REASONABLE_BITRATE, MAX_REASONABLE_BITRATE)
    }

    private fun fetchStreamContentLength(url: String): Long? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val commonBuilder = Request.Builder()
            .url(httpUrl)
            .header("Accept", "*/*")
            .header("User-Agent", BROWSER_USER_AGENT)

        runCatching {
            client.newCall(commonBuilder.head().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
            }
        }.getOrNull()?.let { return it }

        return runCatching {
            client.newCall(
                commonBuilder
                    .get()
                    .header("Range", "bytes=0-0")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.header("Content-Range")
                    ?.substringAfterLast('/', missingDelimiterValue = "")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L && response.code == 206 }
            }
        }.getOrNull()
    }

    private fun buildFlacLabel(
        bitDepth: Int?,
        samplingRateKhz: Double?,
        hires: Boolean,
    ): String = buildString {
        append("Qobuz ")
        append(if (hires) "Hi-Res FLAC" else "CD FLAC")
        if (bitDepth != null && samplingRateKhz != null) {
            append(" ")
            append(bitDepth)
            append("bit/")
            append(formatSamplingRate(samplingRateKhz))
            append("kHz")
        }
    }

    private fun buildQualityFallbackOrder(qualityCode: Int): List<Int> {
        val ladder = listOf(27, 7, 6, 5)
        val startIndex = ladder.indexOf(qualityCode)
        return if (startIndex >= 0) ladder.drop(startIndex) else listOf(qualityCode)
    }

    private fun searchTerms(query: Query): List<String> {
        val title = query.title.trim()
        val artists = query.artists.map { it.trim() }.filter { it.isNotBlank() }
        val artistPart = artists.take(3).joinToString(" ")
        val album = query.album.orEmpty().trim()
        return linkedSetOf(
            query.isrc.orEmpty().trim(),
            listOf(title, artists.firstOrNull().orEmpty(), album).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, artistPart, album).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, artists.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, artistPart).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, album).filter { it.isNotBlank() }.joinToString(" "),
            title,
        ).filter { it.isNotBlank() }
    }

    private fun collectArtistNames(track: JSONObject): List<String> {
        val names = mutableListOf<String>()
        track.optJSONObject("performer")?.stringOrNull("name")?.let(names::add)
        val album = track.optJSONObject("album")
        album?.optJSONObject("artist")?.stringOrNull("name")?.let(names::add)
        album?.optJSONArray("artists")?.let { artists ->
            for (index in 0 until artists.length()) {
                artists.optJSONObject(index)?.stringOrNull("name")?.let(names::add)
            }
        }
        return names.distinct()
    }

    private fun versionMismatchPenalty(
        query: String,
        candidateTitle: String,
    ): Int {
        val strictTokens = listOf(
            "remix",
            "live",
            "instrumental",
            "karaoke",
            "sped up",
            "slowed",
        )
        val softTokens = listOf(
            "remaster",
            "remastered",
            "edit",
            "acoustic",
            "version",
            "mono",
            "stereo",
            "deluxe",
        )
        val queryHasStrict = strictTokens.any { query.contains(it) }
        val candidateHasStrict = strictTokens.any { candidateTitle.contains(it) }
        if (candidateHasStrict && !queryHasStrict) return REJECT_SCORE

        val queryHasSoft = softTokens.any { query.contains(it) }
        val candidateHasSoft = softTokens.any { candidateTitle.contains(it) }
        return if (candidateHasSoft && !queryHasSoft) -45 else 0
    }

    private fun Query.cacheKey(resolverRegion: String): String {
        return listOf(
            mediaId,
            trackCacheKey(),
            backend.name,
            resolverRegion,
            qualityCode.toString(),
        ).joinToString("::")
    }

    private fun Query.trackCacheKey(): String {
        return listOf(
            title.normalized(),
            artists.joinToString("|") { it.normalized() },
            album.normalized(),
            isrc?.trim()?.uppercase(Locale.US).orEmpty(),
            countryCode.uppercase(Locale.US),
        ).joinToString("|")
    }

    private fun extractExpiryMs(url: String): Long {
        val etsp = url.toHttpUrlOrNull()
            ?.queryParameter("etsp")
            ?.toLongOrNull()
        if (etsp != null) {
            return (etsp * 1000L) - 15_000L
        }
        return System.currentTimeMillis() + STREAM_CACHE_MS
    }

    private fun formatSamplingRate(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            value.toString()
        }
    }

    private fun significantTokens(value: String): Set<String> {
        val stopWords = setOf("a", "an", "and", "feat", "ft", "for", "of", "the", "with")
        return value.split(" ")
            .map { it.trim() }
            .filter { it.length > 1 && it !in stopWords }
            .toSet()
    }

    private fun titleTokensPass(
        wantedTitle: String,
        candidateTitle: String,
        wantedTokens: Set<String>,
        matchedTokenCount: Int,
        recall: Double,
        precision: Double,
    ): Boolean {
        if (wantedTokens.isEmpty()) return true
        if (candidateTitle == wantedTitle || candidateTitle.contains(wantedTitle)) return true
        return when {
            wantedTokens.size <= 2 -> matchedTokenCount == wantedTokens.size
            wantedTokens.size <= 4 -> matchedTokenCount >= wantedTokens.size - 1 && recall >= 0.75 && precision >= 0.45
            wantedTokens.size <= 7 -> matchedTokenCount >= maxOf(3, wantedTokens.size - 2) && recall >= 0.6 && precision >= 0.4
            else -> matchedTokenCount >= maxOf(4, (wantedTokens.size * 3) / 5) && recall >= 0.6 && precision >= 0.35
        }
    }

    private fun artistNamesMatch(
        wantedArtist: String,
        candidateArtist: String,
    ): Boolean {
        if (wantedArtist == candidateArtist) return true
        if (wantedArtist.contains(candidateArtist) || candidateArtist.contains(wantedArtist)) {
            return minOf(wantedArtist.length, candidateArtist.length) >= 5
        }
        val wantedTokens = significantTokens(wantedArtist)
        val candidateTokens = significantTokens(candidateArtist)
        if (wantedTokens.isEmpty() || candidateTokens.isEmpty()) return false
        val overlap = wantedTokens.intersect(candidateTokens).size
        return overlap >= minOf(wantedTokens.size, candidateTokens.size).coerceAtMost(2)
    }

    private fun String?.normalized(): String {
        val ascii = Normalizer.normalize(this.orEmpty(), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return ascii
            .lowercase(Locale.US)
            .replace("&", " and ")
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private fun String.wordsOverlap(other: String): Int {
        val first = split(' ').filter { it.length > 1 }.toSet()
        val second = other.split(' ').filter { it.length > 1 }.toSet()
        return first.intersect(second).size
    }

    private fun JSONObject.stringOrNull(name: String): String? {
        return optString(name).trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.intOrNull(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getInt(name) }.getOrElse {
            optString(name).trim().toIntOrNull()
        }
    }

    private fun JSONObject.doubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getDouble(name) }.getOrElse {
            optString(name).trim().toDoubleOrNull()
        }
    }

    /**
     * Parses three possible direct-ID forms out of a string and returns the
     * Qobuz track ID. Used by [resolve] to short-circuit fuzzy matching when
     * the caller already knows the exact track.
     *
     * Accepted forms:
     * - bare digits ("12345678")
     * - the "qobuz:track:<id>" URI scheme (case-insensitive)
     * - a qobuz.com URL with optional locale and album segments
     */
    fun String.toQobuzTrackIdOrNull(): String? {
        val trimmed = trim()
        if (trimmed.matches(Regex("\\d+"))) return trimmed
        Regex("""^qobuz:track:(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        Regex("""qobuz\.com/(?:[a-z]{2}-[a-z]{2}/)?(?:album/[^/]+/)?(\d+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        return null
    }

    /**
     * Lightweight metadata returned to the manual-match UI so the user can
     * pick the right Qobuz track when the auto-matcher is wrong.
     */
    data class CandidateMetadata(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val hires: Boolean,
        val bitDepth: Int?,
        val samplingRateKhz: Double?,
    )

    /**
     * Returns up to [limit] de-duplicated Qobuz candidates for the query,
     * gathered across every available search backend and every search-term
     * variant. Used by the manual override UI.
     */
    fun searchCandidates(
        query: Query,
        limit: Int = 12,
    ): List<CandidateMetadata> {
        val resolverRegion = normalizeResolverRegion(query.countryCode, query.backend)
        val results = linkedMapOf<String, CandidateMetadata>()
        for (term in searchTerms(query)) {
            for (backend in searchBackendOrder(query.backend)) {
                val search = when (backend) {
                    SearchBackend.KENNY -> searchTracks(term, query.countryCode, SearchBackend.KENNY)
                    SearchBackend.SQUID -> searchTracks(term, resolverRegion, SearchBackend.SQUID)
                    SearchBackend.TRYPT -> searchTracks(term, resolverRegion, SearchBackend.TRYPT)
                }
                val items = search.items ?: continue
                for (index in 0 until items.length()) {
                    val obj = items.optJSONObject(index) ?: continue
                    val trackId = obj.stringOrNull("id") ?: continue
                    if (results.containsKey(trackId)) continue
                    val title = obj.stringOrNull("title").orEmpty()
                    val version = obj.stringOrNull("version")
                    val titleWithVersion = listOfNotNull(title.takeIf { it.isNotBlank() }, version)
                        .joinToString(" ")
                    val artistName = collectArtistNames(obj).firstOrNull().orEmpty()
                    val album = obj.optJSONObject("album")?.stringOrNull("title")
                    val isrc = obj.stringOrNull("isrc")
                    val durationSec = obj.intOrNull("duration")
                    val hires = obj.optBoolean("hires", false)
                    val bitDepth = obj.intOrNull("maximum_bit_depth")
                    val samplingRate = obj.doubleOrNull("maximum_sampling_rate")
                    results[trackId] = CandidateMetadata(
                        trackId = trackId,
                        title = titleWithVersion.ifBlank { title },
                        artist = artistName,
                        album = album,
                        isrc = isrc,
                        durationMs = durationSec?.toLong()?.times(1000L),
                        hires = hires,
                        bitDepth = bitDepth,
                        samplingRateKhz = samplingRate,
                    )
                    if (results.size >= limit) return results.values.toList()
                }
            }
        }
        return results.values.toList()
    }

}
