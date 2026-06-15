/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.monochrome

import com.metrolist.music.constants.MonochromeBackend
import com.metrolist.music.constants.UnifiedAudioQuality
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

object MonochromeAudioProvider {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

    private const val STREAM_CACHE_MS = 5 * 60 * 1000L
    private const val REJECT_SCORE = -1_000_000

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val isrc: String?,
        val durationMs: Long?,
        val quality: String = "HI_RES_LOSSLESS",
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

    class MonochromeResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class MatchedTrack(
        val trackId: String,
        val hires: Boolean,
        val bitDepth: Int? = null,
        val samplingRateKhz: Double? = null,
        val isrc: String? = null,
    )

    private data class StreamAttempt(
        val resolved: Resolved? = null,
        val error: String? = null,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val trackCache = ConcurrentHashMap<String, MatchedTrack>()
    private val streamCache = ConcurrentHashMap<String, Resolved>()

    fun qualityStringFor(unifiedQuality: UnifiedAudioQuality): String =
        when (unifiedQuality) {
            UnifiedAudioQuality.HIRES -> "HI_RES_LOSSLESS"
            UnifiedAudioQuality.FLAC -> "LOSSLESS"
            UnifiedAudioQuality.KBPS_320 -> "HIGH"
            UnifiedAudioQuality.YT_HIGH -> "HIGH" // Fallback fallback
        }

    fun baseUrlFor(backend: MonochromeBackend, customUrl: String?): String {
        return when (backend) {
            MonochromeBackend.OFFICIAL -> "https://api.monochrome.tf"
            MonochromeBackend.SAMIDY -> "https://samidy.monochrome.tf"
            MonochromeBackend.CUSTOM -> customUrl?.trim()?.removeSuffix("/").orEmpty().ifBlank { "https://api.monochrome.tf" }
        }
    }

    fun buildQualityFallbackOrder(quality: String): List<String> {
        val ladder = listOf("HI_RES_LOSSLESS", "LOSSLESS", "HIGH", "LOW")
        val startIndex = ladder.indexOf(quality)
        return if (startIndex >= 0) ladder.drop(startIndex) else listOf(quality)
    }

    private fun Query.cacheKey(baseUrl: String, quality: String): String {
        return listOf(
            mediaId,
            trackCacheKey(),
            baseUrl,
            quality,
        ).joinToString("::")
    }

    private fun Query.trackCacheKey(): String {
        return listOf(
            title.normalized(),
            artists.joinToString("|") { it.normalized() },
            album.normalized(),
            isrc?.trim()?.uppercase(Locale.US).orEmpty(),
        ).joinToString("|")
    }

    fun resolve(query: Query, backend: MonochromeBackend, customUrl: String?): Resolved {
        val baseUrl = baseUrlFor(backend, customUrl)
        val streamCacheKey = query.cacheKey(baseUrl, query.quality)
        val now = System.currentTimeMillis()

        streamCache[streamCacheKey]
            ?.takeIf { it.expiresAtMs > now + 20_000L }
            ?.let { return it }

        // Direct-ID short-circuit: when the mediaId encodes a Monochrome/Tidal track ID
        // (bare digits, "monochrome:track:<id>" or "qobuz:track:<id>" URI), skip search entirely.
        val directTrackId = query.mediaId.toMonochromeTrackIdOrNull()
        val trackCacheKey = query.trackCacheKey()
        val track = if (directTrackId != null) {
            MatchedTrack(
                trackId = directTrackId,
                hires = false,
                isrc = query.isrc?.takeIf { it.isNotBlank() },
            )
        } else {
            trackCache[trackCacheKey]
                ?: run {
                    val lookup = findBestTrack(query, baseUrl)
                    lookup.track?.also { trackCache[trackCacheKey] = it } ?: run {
                        val reason = lookup.error?.takeIf { it.isNotBlank() }
                        if (reason != null) {
                            throw MonochromeResolutionException("Monochrome search failed for ${query.title}: $reason")
                        }
                        throw MonochromeResolutionException("Monochrome match not found for ${query.title}")
                    }
                }
        }

        var lastError: String? = null
        for (quality in buildQualityFallbackOrder(query.quality)) {
            val attempt = requestStream(track, quality, baseUrl)
            attempt.resolved?.let { resolved ->
                streamCache[streamCacheKey] = resolved
                return resolved
            }
            if (!attempt.error.isNullOrBlank()) {
                lastError = attempt.error
                Timber.tag("MonochromeAudioProvider").d(
                    "Stream attempt failed for %s (%s, quality=%s): %s",
                    query.title,
                    baseUrl,
                    quality,
                    attempt.error,
                )
            }
        }

        throw MonochromeResolutionException(lastError ?: "Monochrome stream not found for ${query.title}")
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
        baseUrl: String,
    ): TrackLookupResult {
        var lastSearchError: String? = null
        for (term in searchTerms(query)) {
            val search = searchTracks(term, baseUrl)
            if (search.error != null) {
                lastSearchError = search.error
            }
            val results = search.items ?: continue
            selectBestTrack(results, query)?.let { return TrackLookupResult(track = it) }
        }
        return TrackLookupResult(error = lastSearchError)
    }

    private data class TrackSearchResult(
        val items: JSONArray? = null,
        val error: String? = null,
    )

    private fun searchTracks(
        term: String,
        baseUrl: String,
    ): TrackSearchResult {
        val url = "$baseUrl/search/".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("s", term)
            ?.build()
            ?: return TrackSearchResult(error = "Monochrome search URL could not be built")

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
                    return@use TrackSearchResult(
                        error = "Monochrome search HTTP ${response.code}: ${payload.take(160)}"
                    )
                }
                if (payload.isBlank()) {
                    return@use TrackSearchResult(error = "Monochrome search returned an empty response")
                }
                val root = JSONObject(payload)
                val data = root.optJSONObject("data")
                if (data == null) {
                    val apiError = root.optString("detail", "")
                    return@use TrackSearchResult(
                        error = "Monochrome search returned no data: ${apiError.ifBlank { "unknown structure" }}"
                    )
                }
                TrackSearchResult(items = data.optJSONArray("items"))
            }
        }.getOrElse { error ->
            TrackSearchResult(error = "Monochrome search request failed: ${error.message ?: error.javaClass.simpleName}")
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
            val streamable: Boolean,
        )

        val candidates = mutableListOf<Candidate>()
        val evaluated = mutableListOf<EvaluatedCandidate>()
        val minAcceptScore = 320

        for (index in 0 until results.length()) {
            val obj = results.optJSONObject(index) ?: continue
            val allowStreaming = obj.optBoolean("allowStreaming", true)
            val streamReady = obj.optBoolean("streamReady", true)
            if (!allowStreaming || !streamReady) continue

            val trackId = obj.optInt("id", 0).takeIf { it > 0 }?.toString() ?: continue
            val candidateTitle = obj.optString("title").normalized()
            val candidateVersion = obj.optString("version").normalized()
            val candidateCombinedTitle = listOf(candidateTitle, candidateVersion)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val candidateAlbum = obj.optJSONObject("album")
                ?.optString("title")
                .normalized()
            val candidateIsrc = obj.optString("isrc").trim().uppercase(Locale.US)
            val candidateDuration = obj.optInt("duration", 0)
            val candidateArtists = collectArtistNames(obj).map { it.normalized() }.filter { it.isNotBlank() }

            val qualityStr = obj.optString("audioQuality", "LOSSLESS").uppercase(Locale.US)
            val tagsString = obj.optJSONObject("mediaMetadata")?.optJSONArray("tags")?.toString()?.uppercase(Locale.US).orEmpty()
            val hires = qualityStr.contains("HIRES") || qualityStr.contains("HI_RES") || tagsString.contains("HIRES") || tagsString.contains("HI_RES")

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
            if (wantedDurationSec != null && candidateDuration > 0 && abs(wantedDurationSec - candidateDuration) > 25) continue

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

            if (wantedDurationSec != null && candidateDuration > 0) {
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

            evaluated += EvaluatedCandidate(
                trackId = trackId,
                title = candidateCombinedTitle,
                artists = candidateArtists.joinToString(),
                score = score,
                streamable = true,
            )

            if (score > REJECT_SCORE && (score >= minAcceptScore || wantedIsrc.isNotBlank() && candidateIsrc == wantedIsrc)) {
                candidates += Candidate(
                    track = MatchedTrack(
                        trackId = trackId,
                        hires = hires,
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
                    "${it.trackId} score=${it.score} title='${it.title}' artists='${it.artists}'"
                }
            if (top.isNotBlank()) {
                Timber.tag("MonochromeAudioProvider").w(
                    "No acceptable Monochrome candidate for '%s' by '%s' (min=%d). Top matches: %s",
                    query.title,
                    query.artists.joinToString(),
                    minAcceptScore,
                    top,
                )
            }
        }

        return candidates.maxByOrNull { it.score }?.track
    }

    private fun requestStream(
        track: MatchedTrack,
        quality: String,
        baseUrl: String,
    ): StreamAttempt {
        val url = "$baseUrl/track/".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("id", track.trackId)
            ?.addQueryParameter("quality", quality)
            ?.build()
            ?: return StreamAttempt(error = "Monochrome track stream URL could not be built")

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
                    return@use StreamAttempt(error = "Monochrome track stream HTTP ${response.code}: ${payload.take(160)}")
                }
                if (payload.isBlank()) {
                    return@use StreamAttempt(error = "Monochrome track stream returned empty payload")
                }
                val root = JSONObject(payload)
                val data = root.optJSONObject("data")
                if (data == null) {
                    val apiError = root.optString("detail", "")
                    return@use StreamAttempt(
                        error = "Monochrome track stream returned error: ${apiError.ifBlank { "unknown error structure" }}"
                    )
                }

                val manifestBase64 = data.optString("manifest", "")
                if (manifestBase64.isBlank()) {
                    return@use StreamAttempt(error = "Monochrome track stream did not return manifest base64")
                }

                // Decode base64 to parse codec/bitrate/sampleRate metadata
                val manifestXml = runCatching {
                    String(android.util.Base64.decode(manifestBase64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()

                val mimeType = data.optString("manifestMimeType", "application/dash+xml")
                var codecs = "flac"
                var bitrate = 1411200
                var sampleRate = 44100
                var bitDepth = 16
                var hires = track.hires

                if (manifestXml != null) {
                    codecs = Regex("codecs=\"([^\"]+)\"").find(manifestXml)?.groupValues?.get(1) ?: codecs
                    bitrate = Regex("bandwidth=\"(\\d+)\"").find(manifestXml)?.groupValues?.get(1)?.toIntOrNull() ?: bitrate
                    sampleRate = Regex("audioSamplingRate=\"(\\d+)\"").find(manifestXml)?.groupValues?.get(1)?.toIntOrNull() ?: sampleRate

                    val repId = Regex("id=\"([^\"]+)\"").find(manifestXml)?.groupValues?.get(1) ?: ""
                    if (repId.contains(",")) {
                        val parts = repId.split(",")
                        if (parts.size >= 3) {
                            bitDepth = parts[2].toIntOrNull() ?: bitDepth
                        }
                    } else if (codecs.contains("flac")) {
                        bitDepth = 16
                    }
                    hires = bitDepth > 16 || sampleRate > 44100
                }

                val samplingRateKhz = sampleRate.toDouble() / 1000.0
                val label = buildLabel(codecs, bitDepth, samplingRateKhz, bitrate)

                val mediaUri = "data:application/dash+xml;base64,$manifestBase64"
                val resolved = Resolved(
                    mediaUri = mediaUri,
                    trackId = track.trackId,
                    label = label,
                    mimeType = mimeType,
                    codecs = codecs,
                    bitrate = bitrate,
                    sampleRate = sampleRate,
                    expiresAtMs = System.currentTimeMillis() + STREAM_CACHE_MS,
                    hires = hires,
                    bitDepth = bitDepth,
                    samplingRateKhz = samplingRateKhz,
                    isrc = track.isrc,
                )
                StreamAttempt(resolved = resolved)
            }
        }.getOrElse { error ->
            StreamAttempt(error = "Monochrome stream request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun buildLabel(codecs: String, bitDepth: Int, sampleRateKhz: Double, bitrateBps: Int): String {
        val codecName = when {
            codecs.contains("flac", ignoreCase = true) -> "FLAC"
            codecs.contains("mp4a", ignoreCase = true) -> "AAC"
            else -> codecs.uppercase(Locale.US)
        }
        val kbps = (bitrateBps / 1000)
        return if (codecName == "FLAC") {
            "$codecName • $bitDepth bit • ${sampleRateKhz} kHz • $kbps kbps"
        } else {
            "$codecName • $kbps kbps"
        }
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
        track.optJSONObject("artist")?.optString("name")?.let(names::add)
        val artistsArray = track.optJSONArray("artists")
        if (artistsArray != null) {
            for (index in 0 until artistsArray.length()) {
                artistsArray.optJSONObject(index)?.optString("name")?.let(names::add)
            }
        }
        val album = track.optJSONObject("album")
        album?.optJSONObject("artist")?.optString("name")?.let(names::add)
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
        val candidateHasSoft = strictTokens.any { candidateTitle.contains(it) } // wait, softTokens or strictTokens? Let's check queryHasSoft vs candidateHasSoft: it should check softTokens. In Qobuz it checked softTokens.
        val candidateHasSoftReal = softTokens.any { candidateTitle.contains(it) }
        return if (candidateHasSoftReal && !queryHasSoft) -45 else 0
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

    fun String.toMonochromeTrackIdOrNull(): String? {
        val trimmed = trim()
        if (trimmed.matches(Regex("\\d+"))) return trimmed
        Regex("""^(?:monochrome|qobuz):track:(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        Regex("""(?:monochrome|qobuz)\.com/(?:[a-z]{2}-[a-z]{2}/)?(?:album/[^/]+/)?(\d+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { return it }
        return null
    }

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

    fun searchCandidates(
        query: Query,
        backend: MonochromeBackend,
        customUrl: String?,
        limit: Int = 12,
    ): List<CandidateMetadata> {
        val baseUrl = baseUrlFor(backend, customUrl)
        val results = linkedMapOf<String, CandidateMetadata>()
        for (term in searchTerms(query)) {
            val search = searchTracks(term, baseUrl)
            val items = search.items ?: continue
            for (index in 0 until items.length()) {
                val obj = items.optJSONObject(index) ?: continue
                val trackId = obj.optInt("id", 0).takeIf { it > 0 }?.toString() ?: continue
                if (results.containsKey(trackId)) continue
                val title = obj.optString("title", "")
                val version = obj.optString("version", "")
                val titleWithVersion = listOfNotNull(title.takeIf { it.isNotBlank() }, version.takeIf { it.isNotBlank() })
                    .joinToString(" ")
                val artistName = collectArtistNames(obj).firstOrNull().orEmpty()
                val album = obj.optJSONObject("album")?.optString("title")
                val isrc = obj.optString("isrc")
                val durationSec = obj.optInt("duration", 0)

                val qualityStr = obj.optString("audioQuality", "LOSSLESS").uppercase(Locale.US)
                val tagsString = obj.optJSONObject("mediaMetadata")?.optJSONArray("tags")?.toString()?.uppercase(Locale.US).orEmpty()
                val hires = qualityStr.contains("HIRES") || qualityStr.contains("HI_RES") || tagsString.contains("HIRES") || tagsString.contains("HI_RES")

                results[trackId] = CandidateMetadata(
                    trackId = trackId,
                    title = titleWithVersion.ifBlank { title },
                    artist = artistName,
                    album = album,
                    isrc = isrc,
                    durationMs = durationSec.toLong() * 1000L,
                    hires = hires,
                    bitDepth = if (hires) 24 else 16,
                    samplingRateKhz = if (hires) 48.0 else 44.1,
                )
                if (results.size >= limit) return results.values.toList()
            }
        }
        return results.values.toList()
    }
}
